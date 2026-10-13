package com.cloud.androidtvreader.domain.usecase

import com.cloud.androidtvreader.data.parser.BookParsers
import com.cloud.androidtvreader.data.repository.FileBookRepository
import com.cloud.androidtvreader.domain.source.SourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * BookImporter 的单元测试。
 *
 * ══ 这里用的是【真实的】依赖，不是 mock ═══════════════════════
 * registry 用的是 BookParsers.registry（真的 TxtBookParser），
 * repository 用的是真的 FileBookRepository（真的写磁盘到临时目录）。
 *
 * 为什么不用 mock：
 *   - 这些依赖本来就快（纯 JVM，没有网络、没有 Android）
 *   - 真实对象能测出"装配是否正确"，mock 只能测出"我按自己的假设调用了它"
 *   - 少一个测试库依赖（Mockito/MockK），构建更简单
 *
 * 什么时候才该用 mock？当依赖很慢（网络）、有副作用（发短信）、
 * 或者结果不确定（当前时间、随机数）的时候。文件 IO 到临时目录不属于这些。
 * ══════════════════════════════════════════════════════════════
 */
class BookImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val novel = """
        剑与灯
        作者：练习生

        第一章 风起
        　　剑与灯，龙与雪。
    """.trimIndent() + "\n"

    /** 把 registry 和 repository 真的接起来 —— 这本身就是一次"装配验证" */
    private val repository by lazy { FileBookRepository(File(tmp.root, "books")) }
    private val importer by lazy { BookImporter(BookParsers.registry, repository) }

    /** 在临时目录里造一个待导入的文件，包成 SourceFile */
    private fun source(name: String, content: ByteArray): SourceFile {
        val f = tmp.newFile(name)
        f.writeBytes(content)
        return SourceFile(
            file = f,
            displayName = name,
            sizeBytes = f.length(),
            lastModified = f.lastModified(),
        )
    }

    private fun txtSource(name: String = "剑与灯.txt") =
        source(name, novel.toByteArray(Charsets.UTF_8))

    // ══ 成功路径 ════════════════════════════════════════════

    @Test
    fun `成功导入一个 txt`() {
        val result = importer.import(txtSource())

        assertTrue("期望成功，实际是 $result", result is ImportResult.Success)

        val book = (result as ImportResult.Success).book
        assertEquals("剑与灯", book.title)
        assertEquals("练习生", book.author)

        // 真的进了书库
        assertEquals(1, repository.listBooks().size)
        assertEquals(book.id, repository.listBooks()[0].id)
    }

    // ══ 失败路径：格式不支持 ════════════════════════════════

    /**
     * 【重要】格式不支持必须是"返回失败"，不是"抛异常"。
     *
     * 因为导入是批量的：用户一次选 10 本书，混进一个 epub，
     * 不应该导致另外 9 本也一起失败。
     */
    @Test
    fun `不支持的格式返回失败而不是抛异常`() {
        val result = importer.import(source("某本书.epub", "不是真的 epub".toByteArray()))

        assertTrue("期望失败，实际是 $result", result is ImportResult.Failure)
        val failure = result as ImportResult.Failure
        assertEquals("某本书.epub", failure.fileName)
        assertEquals("暂不支持这种格式", failure.reason)

        // 没有半成品进书库
        assertTrue(repository.listBooks().isEmpty())
    }

    @Test
    fun `没有扩展名的文件也返回失败`() {
        val result = importer.import(source("README", "随便什么".toByteArray()))
        assertTrue(result is ImportResult.Failure)
    }

    // ══ 失败路径：解析出错 ══════════════════════════════════

    /**
     * 空文件会被 TxtBookParser 拒绝并抛 BookParseException。
     * BookImporter 要把它转成带【人话原因】的 Failure ——
     * 因为这条消息最终会显示在电视屏幕上给用户看。
     */
    @Test
    fun `空文件返回失败并带上可读的原因`() {
        val result = importer.import(source("空书.txt", ByteArray(0)))

        assertTrue("期望失败，实际是 $result", result is ImportResult.Failure)
        assertEquals("文件是空的", (result as ImportResult.Failure).reason)
    }

    @Test
    fun `只有空白字符的文件返回失败`() {
        val result = importer.import(source("空白.txt", "   \n\n".toByteArray(Charsets.UTF_8)))
        assertTrue(result is ImportResult.Failure)
    }

    // ══ 批量导入 ════════════════════════════════════════════

    /**
     * 【这是整个类最重要的一条】
     * 批量导入时，一本坏书不能让其他好书也失败。
     * 断言三件事：总结果数对、成功的真的入库了、失败的带原因。
     */
    @Test
    fun `批量导入时一本失败不影响其他`() {
        val files = listOf(
            txtSource("好书一.txt"),
            source("坏书.epub", "x".toByteArray()),        // 格式不支持
            txtSource("好书二.txt"),
            source("空书.txt", ByteArray(0)),               // 解析失败
        )

        val results = importer.importAll(files)

        assertEquals(4, results.size)
        assertEquals(2, results.count { it is ImportResult.Success })
        assertEquals(2, results.count { it is ImportResult.Failure })

        // 两本好书都真的在书库里
        assertEquals(2, repository.listBooks().size)

        // 失败的两条都带着文件名和原因，可以直接展示给用户
        results.filterIsInstance<ImportResult.Failure>().forEach {
            assertTrue("文件名不该为空", it.fileName.isNotBlank())
            assertTrue("失败原因不该为空", it.reason.isNotBlank())
        }
    }

    @Test
    fun `批量导入空列表返回空结果`() {
        assertTrue(importer.importAll(emptyList()).isEmpty())
    }

    // ══ 查重 ════════════════════════════════════════════════

    /**
     * 【这一轮新增的核心行为】
     * 同一个文件导入两次，第二次必须被识别出来并跳过。
     *
     * 为什么重要：用户会反复点「全部导入」（尤其是他以为没成功的时候）。
     * 没有查重的话，书架上会出现一堆一模一样的书，
     * 每本还各占一份正文文件 —— 磁盘会被悄悄吃掉。
     */
    @Test
    fun `重复导入同一个文件会被跳过`() {
        val src = txtSource()

        assertTrue(importer.import(src) is ImportResult.Success)

        // 再导一次同一个文件
        val second = importer.import(src)

        assertTrue("期望 Duplicate，实际是 $second", second is ImportResult.Duplicate)
        val dup = second as ImportResult.Duplicate
        assertEquals("剑与灯.txt", dup.fileName)
        assertEquals("剑与灯", dup.existingTitle)   // 告诉用户"书架上已经有这本了"

        // 关键断言：书库里还是只有一本，没有产生第二份
        assertEquals(1, repository.listBooks().size)
    }

    /**
     * 用户主动改文件名再导入 → 当成一本新书。
     *
     * 这是有意的：改名是他明确表达"我要再来一份"的方式。
     * 如果这里也拦，用户就没办法导入同一本书的两个版本了。
     */
    @Test
    fun `改了文件名就当成新书导入`() {
        assertTrue(importer.import(txtSource("剑与灯.txt")) is ImportResult.Success)
        assertTrue(importer.import(txtSource("剑与灯-第二版.txt")) is ImportResult.Success)

        assertEquals(2, repository.listBooks().size)
    }

    /**
     * 批量导入时，重复的算 Duplicate，【不能算 Failure】。
     *
     * 因为界面会据此显示不同的东西：
     *   Duplicate → "已在书架 2 本"（正常提示）
     *   Failure   → "失败 2 本"（红色警告，用户会以为坏了）
     */
    @Test
    fun `批量导入时重复的不算失败`() {
        val a = txtSource("甲.txt")
        val b = txtSource("乙.txt")
        importer.import(a)                       // 先把甲导进去

        val results = importer.importAll(listOf(a, b))

        assertEquals(1, results.count { it is ImportResult.Success })
        assertEquals(1, results.count { it is ImportResult.Duplicate })
        assertEquals("重复不该被算作失败", 0, results.count { it is ImportResult.Failure })
        assertEquals(2, repository.listBooks().size)
    }

    /**
     * 查重必须发生在【读文件之前】。
     *
     * 构造方式：先把一本好书导进去，然后把源文件内容清空。
     *   - 如果查重在前 → Duplicate（压根没去读那个空文件）
     *   - 如果查重在后 → Failure("文件是空的")（说明白读了一遍）
     *
     * 这条守的是一个性能前提：用户重复点「全部导入」时，
     * 每本重复的书都不该再经历一次完整的读盘 + 解码 + 解析。
     * 一本书几 MB，几十本就是几十秒的无效工作。
     */
    @Test
    fun `查重发生在读文件之前`() {
        val f = File(tmp.root, "剑与灯.txt")
        f.writeBytes(novel.toByteArray(Charsets.UTF_8))
        val src = SourceFile(f, "剑与灯.txt", f.length(), f.lastModified())

        assertTrue(importer.import(src) is ImportResult.Success)

        // 把源文件清空
        f.writeBytes(ByteArray(0))
        val emptied = SourceFile(f, "剑与灯.txt", f.length(), f.lastModified())

        val second = importer.import(emptied)

        assertTrue(
            "查重应该先于读取文件，实际是 $second（说明白读了一遍空文件）",
            second is ImportResult.Duplicate
        )
    }

    // ══ 与编码探测的联动 ════════════════════════════════════

    /**
     * GBK 文件走完整条链路也不能乱码。
     *
     * 这条看起来和 TxtBookParserTest 重复，但覆盖的路径不同：
     * 这里验证的是【经过 BookImporter 和 Repository 之后】书名仍然正确 ——
     * 也就是"编码探测 → 解码 → 写进 properties → 读回来"整条链路都没问题。
     */
    @Test
    fun `GBK 文件导入后书名依然正确`() {
        val gbk = Charset.forName("GB18030")
        val result = importer.import(source("剑与灯.txt", novel.toByteArray(gbk)))

        assertTrue(result is ImportResult.Success)
        val book = (result as ImportResult.Success).book
        assertEquals("剑与灯", book.title)
        assertEquals("GB18030", book.charsetName)

        // 从书库再读一遍，中文没坏
        assertEquals("剑与灯", repository.findBook(book.id)!!.title)
    }
}
