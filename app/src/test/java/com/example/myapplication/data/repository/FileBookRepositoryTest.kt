package com.example.myapplication.data.repository

import com.example.myapplication.data.parser.TxtBookParser
import com.example.myapplication.domain.model.Book
import com.example.myapplication.domain.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * FileBookRepository 的单元测试。
 *
 * ══ 这个测试类的价值 ═══════════════════════════════════════════
 * 它测的是【真正的磁盘读写】—— 真的建目录、真的写文件、真的再读回来。
 * 不是 mock，不是内存替身。
 *
 * 这类测试能抓到 mock 测试永远抓不到的问题：
 *   - Properties 用了错误的编码版本 → 中文书名变乱码
 *   - 保存进度时把书名作者一起覆盖掉了
 *   - 目录名里的非法字符导致创建失败
 *
 * 【为什么不需要 Android】
 * 因为 FileBookRepository 收的是 java.io.File。
 * 这是把它设计成"不依赖 Context"的直接回报。
 * ══════════════════════════════════════════════════════════════
 */
class FileBookRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun booksDir(): File = File(tmp.root, "books")

    /**
     * 【为什么用 by lazy 而不是直接初始化】
     * 因为 @Rule 的临时目录是在【测试方法执行前】才创建的，
     * 而字段初始化发生在更早的构造阶段。
     * 直接写 `private val repo = FileBookRepository(booksDir())`
     * 会拿到一个还不存在的路径（虽然不一定报错，但语义是错的）。
     * by lazy 让它在第一次真正使用时才求值，那时 Rule 已经生效了。
     */
    private val repo: FileBookRepository by lazy { FileBookRepository(booksDir()) }

    private val parser = TxtBookParser()

    private val novel = """
        剑与灯
        作者：练习生

        第一章 风起
        　　剑与灯，龙与雪。

        第二章 灯灭
        　　风雪夜归人。
    """.trimIndent() + "\n"

    /** 走完"解析 → 导入"整个流程，返回入库后的 Book */
    private fun importSample(name: String = "剑与灯.txt"): Book =
        repo.importBook(parser.parse(name, novel.toByteArray(Charsets.UTF_8)), name)

    // ══ 导入 ════════════════════════════════════════════════

    @Test
    fun `导入后能在书库里查到`() {
        val book = importSample()

        assertEquals("剑与灯", book.title)
        assertEquals("练习生", book.author)
        assertEquals(BookFormat.TXT, book.format)
        assertEquals("UTF-8", book.charsetName)

        val listed = repo.listBooks()
        assertEquals(1, listed.size)
        assertEquals(book.id, listed[0].id)
        assertEquals("剑与灯", listed[0].title)
    }

    @Test
    fun `导入会创建目录并写入两个文件`() {
        val book = importSample()
        val dir = File(booksDir(), book.id)

        assertTrue("书籍目录应该存在", dir.isDirectory)
        assertTrue("元数据文件应该存在", File(dir, "meta.properties").isFile)
        assertTrue("正文文件应该存在", File(dir, "content.txt").isFile)
    }

    /**
     * 【这条守的是最经典的编码坑】
     * Properties 有 InputStream/OutputStream 和 Reader/Writer 两套重载。
     * 用错前者的话，它按 ISO-8859-1 处理，中文会被转成 \uXXXX 转义
     * 甚至直接坏掉。这条测试一挂，就说明 load/store 用错版本了。
     */
    @Test
    fun `书名和作者能正确往返中文`() {
        val book = importSample()
        val reloaded = repo.findBook(book.id)!!

        assertEquals("剑与灯", reloaded.title)
        assertEquals("练习生", reloaded.author)
    }

    /**
     * 正文读回来必须和原文【完全一致】—— 包括末尾的换行符。
     * 差一个字符就会导致最后一章的下标范围偏移，
     * 表现为阅读器里"最后一行莫名其妙没了"，极难排查。
     */
    @Test
    fun `正文读回来和原文完全一致`() {
        val book = importSample()
        assertEquals(novel, repo.loadText(book.id))
    }

    /**
     * 章节是从正文现场重新切分的（磁盘上没有第二份章节数据）。
     * 这条同时验证了"正文存对了"和"切分接上了"。
     */
    @Test
    fun `章节可以从正文重新切分出来`() {
        val book = importSample()
        val titles = repo.loadChapters(book.id).map { it.title }
        assertEquals(listOf("前言", "第一章 风起", "第二章 灯灭"), titles)
    }

    // ══ 阅读进度 ════════════════════════════════════════════

    @Test
    fun `新导入的书进度是空的`() {
        val book = importSample()
        assertEquals(0, book.progress.chapterIndex)
        assertEquals(0, book.progress.charOffset)
        assertFalse(book.progress.isStarted)
    }

    @Test
    fun `保存进度之后能读回来`() {
        val book = importSample()

        repo.saveProgress(book.id, chapterIndex = 1, charOffset = 42)

        val reloaded = repo.findBook(book.id)!!
        assertEquals(1, reloaded.progress.chapterIndex)
        assertEquals(42, reloaded.progress.charOffset)
        assertTrue("updatedAt 应该被填上", reloaded.progress.updatedAt > 0)
        assertTrue(reloaded.progress.isStarted)
    }

    /**
     * 【这条守的是一个很容易犯的错】
     * saveProgress 的正确实现是"读出已有元数据 → copy 进度的字段 → 写回去"。
     * 如果图省事直接 new 一个只带进度的 Book 覆盖文件，
     * 书名、作者、编码就会【全部丢失】—— 书还在，但变成了无名氏。
     * 这种 bug 在界面上要过很久才会被发现。
     */
    @Test
    fun `保存进度不会丢掉书名作者和编码`() {
        val book = importSample()
        repo.saveProgress(book.id, 2, 7)

        val reloaded = repo.findBook(book.id)!!
        assertEquals("剑与灯", reloaded.title)
        assertEquals("练习生", reloaded.author)
        assertEquals("UTF-8", reloaded.charsetName)
        assertEquals("剑与灯.txt", reloaded.sourceFileName)
        assertEquals(BookFormat.TXT, reloaded.format)
    }

    // ══ id 生成 ═════════════════════════════════════════════

    @Test
    fun `同名文件导入两次会得到不同的 id`() {
        val a = importSample("剑与灯.txt")
        val b = importSample("剑与灯.txt")

        assertNotEquals(a.id, b.id)
        assertEquals(2, repo.listBooks().size)
        // 两本书都能独立读到正文
        assertEquals(novel, repo.loadText(a.id))
        assertEquals(novel, repo.loadText(b.id))
    }

    /**
     * 文件名里带路径分隔符或 ".." 时，必须被替换掉。
     * 这不只是"目录名难看"的问题 —— 如果 id 里真出现 "../"，
     * deleteBook 的 deleteRecursively 就可能删到书库外面去（路径穿越漏洞）。
     */
    @Test
    fun `非法字符被替换掉，不会跑出书库目录`() {
        val book = importSample("../坏/名字.txt")
        assertFalse("id 里不能出现斜杠", book.id.contains('/'))
        assertFalse("id 里不能出现反斜杠", book.id.contains('\\'))
        assertFalse("id 里不能出现 ..", book.id.contains(".."))

        // 而且它必须老老实实待在书库目录里面
        assertTrue(File(booksDir(), book.id).absolutePath.startsWith(booksDir().absolutePath))
    }

    // ══ 修改书名 ════════════════════════════════════════════

    @Test
    fun `修改书名之后能读回来`() {
        val book = importSample()
        repo.renameBook(book.id, "新的书名")

        assertEquals("新的书名", repo.findBook(book.id)!!.title)
    }

    /**
     * 【这条守的是和 saveProgress 同一类错误】
     * renameBook 的正确实现是"读出已有元数据 → copy 改 title → 写回"。
     * 如果图省事直接 new 一个只带书名的 Book 覆盖文件，
     * 作者、编码、阅读进度就会【全部丢失】。
     * 这种 bug 在界面上表现为"改个书名，进度没了"，很难联想到一起。
     */
    @Test
    fun `修改书名不会丢掉作者编码和阅读进度`() {
        val book = importSample()
        repo.saveProgress(book.id, chapterIndex = 3, charOffset = 999)
        repo.renameBook(book.id, "剑与灯（修订版）")

        val reloaded = repo.findBook(book.id)!!
        assertEquals("剑与灯（修订版）", reloaded.title)
        assertEquals("练习生", reloaded.author)
        assertEquals("UTF-8", reloaded.charsetName)
        assertEquals(BookFormat.TXT, reloaded.format)
        assertEquals(3, reloaded.progress.chapterIndex)
        assertEquals(999, reloaded.progress.charOffset)
    }

    /**
     * 书名改了，但 bookId（目录名）不变。
     * 这是刻意的设计：id 是内部标识，改它要迁移文件、更新所有引用，
     * 风险大收益小。书名只是显示名称。
     */
    @Test
    fun `修改书名不会改变书籍目录`() {
        val book = importSample()
        repo.renameBook(book.id, "完全不同的名字")

        assertTrue("目录应该还在原来的位置", File(booksDir(), book.id).isDirectory)
        assertEquals(1, repo.listBooks().size)
    }

    @Test
    fun `修改不存在的书会失败`() {
        assertThrows(NoSuchElementException::class.java) {
            repo.renameBook("根本没有这本书", "新名字")
        }
    }

    // ══ 删除 ════════════════════════════════════════════════

    @Test
    fun `删除书会连同目录一起清掉`() {
        val book = importSample()
        repo.deleteBook(book.id)

        assertTrue(repo.listBooks().isEmpty())
        assertFalse(File(booksDir(), book.id).exists())
    }

    @Test
    fun `不存在的书返回 null`() {
        assertNull(repo.findBook("根本没有这本书"))
    }

    // ══ 排序 ════════════════════════════════════════════════

    /**
     * 书架页的排序规则：最近读过的排最前。
     * 这条决定用户打开 App 第一眼看到什么 —— 应该是"继续读"的那本。
     *
     * 这里 sleep 是为了让时间戳拉开差距。
     * Windows 上 System.currentTimeMillis() 的精度大约 1~15 毫秒，
     * 不 sleep 的话两次导入可能拿到相同的时间戳，测试就会变成随机失败（flaky）。
     */
    @Test
    fun `最近阅读的书排在前面`() {
        val older = importSample("甲.txt")
        Thread.sleep(20)
        importSample("乙.txt")                 // 更晚导入，名次本该更靠前

        // 给"甲"记一次阅读进度，它应该反超到第一位
        repo.saveProgress(older.id, chapterIndex = 1, charOffset = 10)

        assertEquals(older.id, repo.listBooks().first().id)
    }

    /**
     * 【第一种"损坏"：元数据文件被清空】
     *
     * 有个反直觉的点：Properties.load() 读一个【空文件】不会报错，
     * 它只是加载到 0 个属性。所以 readMeta 里那些 `?: 兜底值` 会生效 ——
     * id 和 title 回退到目录名。
     *
     * 这是【有意的设计】：书仍然出现在书架上，用户能看到、能打开、能读，
     * 只是名字变成了目录名。总比默默消失好 ——
     * 那样用户会以为自己的文件丢了。
     *
     * （我第一版测试在这里断言的是"应该被跳过"，结果挂了。
     *   这次失败让我意识到：跳过才是更糟的行为。
     *   写测试时遇到断言失败，先问"是不是我的预期错了"。）
     */
    @Test
    fun `元数据被清空时书名回退到目录名，书仍然可用`() {
        val book = importSample("某个奇怪的文件名.txt")

        // 把元数据文件清空，模拟损坏
        File(File(booksDir(), book.id), "meta.properties").writeText("", Charsets.UTF_8)

        val listed = repo.listBooks()
        assertEquals("书不该从书架上消失", 1, listed.size)

        val reloaded = listed[0]
        assertEquals(book.id, reloaded.title)                 // 回退到目录名
        assertEquals(BookFormat.UNKNOWN, reloaded.format)     // 格式也认不出来了
        assertNull(reloaded.author)
        assertEquals(novel, repo.loadText(book.id))           // 但正文完全可用
    }

    /**
     * 【第二种"损坏"：元数据文件根本不是文件】
     *
     * 这条走的是 readMeta 里 `if (!f.isFile) return null` 那个分支。
     * 断言的是：坏掉的这一本被跳过，但【其他书不受影响】——
     * 整个书架不能因为一本书坏掉就打不开。
     */
    @Test
    fun `元数据文件无法读取时该书被跳过，不影响其他书`() {
        val good = importSample("好书.txt")
        val broken = importSample("坏书.txt")

        // 把元数据文件换成一个同名目录，isFile 就会返回 false
        val meta = File(File(booksDir(), broken.id), "meta.properties")
        meta.delete()
        meta.mkdirs()

        val listed = repo.listBooks()
        assertEquals(1, listed.size)
        assertEquals(good.id, listed[0].id)
    }
}
