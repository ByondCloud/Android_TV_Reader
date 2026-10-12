package com.example.myapplication.data.parser

import com.example.myapplication.domain.model.BookFormat
import com.example.myapplication.domain.parser.BookParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * TxtBookParser 的集成测试 —— 【模块 2 的总验收】。
 *
 * ══ 它和前面几个测试类的分工 ══════════════════════════════════
 * 前面每个类只测一个零件：
 *   CharsetDetectorTest   → 编码探测对不对
 *   TextNormalizerTest    → 换行统一对不对
 *   ChapterSplitterTest   → 章节切分对不对
 *   BookMetaExtractorTest → 书名作者猜得对不对
 *
 * 而这里测的是【"给我一堆字节，能不能得到一本书"】——
 * 5 个零件串起来之后的端到端结果。
 *
 * 集成测试会重复覆盖一些单元测试已经测过的逻辑，这是故意的：
 * 单元测试保证"每个零件是好的"，集成测试保证"零件之间接对了"。
 * 假如哪天有人在 TxtBookParser 里误删了 TextNormalizer 调用，
 * 前面 4 个测试类会全绿，只有这里的 CRLF 用例会挂 ——
 * 这就是集成测试存在的意义。
 *
 * ══ 为什么不需要任何测试用的 txt 文件 ═══════════════════════════
 * 因为 BookParser 的入参是 ByteArray。测试里用 Charset 现场把字符串
 * 编成 GBK / UTF-8 / 带 BOM 的字节，比往 src/test/resources 里
 * 塞二进制文件干净得多，也不会因为 git 自动改写换行符而"莫名挂掉"。
 * ══════════════════════════════════════════════════════════════
 */
class TxtBookParserTest {

    private val parser = TxtBookParser()
    private val gb18030 = Charset.forName("GB18030")

    /**
     * 一本典型的、带书名作者块的中文小说。
     *
     * trimIndent() 会去掉每行【共同的代码缩进】（也就是这段文字在 .kt 文件里
     * 左侧的那些空格），但【不会】动正文里的全角空格 　　——
     * 那正是中文小说的段首缩进，必须原样保留。
     *
     * 末尾的 + "\n" 是为了模拟真实文件"最后一行也有换行符"。
     * 不加的话，最后一章的下标范围会和真实文件差一个字符，
     * 那种 bug 在阅读器里表现为"最后一行莫名其妙没了"，极难查。
     */
    private val novel = """
        剑与灯
        作者：练习生

        第一章 风起
        　　剑与灯，龙与雪。

        第二章 灯灭
        　　风雪夜归人。

        番外一 那年夏天
        　　夏天。
    """.trimIndent() + "\n"

    // ══ canParse：扩展名分发 ════════════════════════════════

    /**
     * 注意 `canParse("txt")` 必须是 false：
     * substringAfterLast('.', "") 在没有点时返回的是【默认值 ""】，
     * 而不是原字符串。如果当初写成 substringAfterLast('.')（不带第二个参数），
     * "txt" 会返回 "txt" 本身，然后被误判成"扩展名是 txt"。
     */
    @Test
    fun `扩展名判断`() {
        assertTrue(parser.canParse("剑与灯.txt"))
        assertTrue(parser.canParse("剑与灯.TXT"))    // 大写也要认
        assertFalse(parser.canParse("剑与灯.epub"))
        assertFalse(parser.canParse("没有扩展名"))
        assertFalse(parser.canParse("txt"))
    }

    // ══ UTF-8 全流程 ════════════════════════════════════════

    @Test
    fun `UTF8 文件解析出正确的书名作者和章节`() {
        val book = parser.parse("剑与灯.txt", novel.toByteArray(Charsets.UTF_8))

        assertEquals("剑与灯", book.title)
        assertEquals("练习生", book.author)
        assertEquals(BookFormat.TXT, book.format)
        // Charsets.UTF_8.name() 返回的就是 "UTF-8"
        assertEquals("UTF-8", book.charsetName)
        assertEquals(
            listOf("前言", "第一章 风起", "第二章 灯灭", "番外一 那年夏天"),
            book.chapters.map { it.title }
        )
    }

    /**
     * 验证章节正文的【下标范围】切得准不准。
     *
     * 这是最容易被写错、又最难靠肉眼发现的地方：
     * startOffset 少加或多加一个字符，阅读器的页码就会错位，
     * 现象是"某段文字莫名其妙跑到上一章末尾去了"，极难排查。
     * 所以这里直接把 substring 的结果和期望字符串逐字对比。
     *
     * 期望值里末尾那两个 \n 分别来自：
     *   - 正文那一行自己的换行
     *   - 正文和下一章标题之间的那个空行
     * 这不是多余的 —— 它们确实属于"本章的正文范围"。
     */
    @Test
    fun `章节正文内容正确`() {
        val book = parser.parse("剑与灯.txt", novel.toByteArray(Charsets.UTF_8))
        val first = book.chapters[1]                       // 第一章
        val body = book.text.substring(first.startOffset, first.endOffset)
        assertEquals("　　剑与灯，龙与雪。\n\n", body)
    }

    // ══ GBK 全流程：模块 2 最关键的一个测试 ═════════════════

    /**
     * 同一本小说，编码换成 GBK。
     *
     * 如果编码探测错了（比如错当成 UTF-8），解码出来的书名会是一串乱码，
     * 下面每一个断言都会失败。
     * 这个测试就是"中文 txt 不乱码"的守门员 ——
     * 而乱码正是中文阅读类 App 最经典的翻车方式。
     *
     * 注意断言 charsetName == "GB18030" 而不是 "GBK"：
     * 因为 CharsetDetector 统一用 GB18030 兜底（它是 GBK/GB2312 的超集）。
     * 用超集解码 GBK 文件是安全的，反过来则不然。
     */
    @Test
    fun `GBK 文件解析出正确的书名作者和章节`() {
        // 同一本小说，编码换成 GBK。
        // 如果编码探测错了，书名会变成一串乱码，这个断言就会失败。
        val book = parser.parse("剑与灯.txt", novel.toByteArray(gb18030))

        assertEquals("GB18030", book.charsetName)
        assertEquals("剑与灯", book.title)
        assertEquals("练习生", book.author)
        assertEquals(4, book.chapters.size)
    }

    /**
     * 带 BOM 的 UTF-8。
     *
     * 这条同时验证了两件事：
     *   1. CharsetDetector 认出了 BOM（bomLength = 3）
     *   2. TxtBookParser 解码时真的跳过了那 3 个字节
     * 如果只做了第 1 步忘了第 2 步，书名会变成 "\uFEFF剑与灯"，
     * 断言就会失败。
     */
    @Test
    fun `UTF8 带 BOM 的文件也能正确解析`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val book = parser.parse("剑与灯.txt", bom + novel.toByteArray(Charsets.UTF_8))

        assertEquals("剑与灯", book.title)
        assertEquals(4, book.chapters.size)
    }

    /**
     * Windows 记事本存出来的 CRLF 换行。
     *
     * 这条在验证 TextNormalizer 真的【接在了主流程上】。
     * 如果忘了规范化，按 \n 切行会切不开，书名和章节会全错，
     * 而其他所有测试仍然是绿的。
     */
    @Test
    fun `CRLF 换行的 Windows 文件也能正确解析`() {
        val crlf = novel.replace("\n", "\r\n")
        val book = parser.parse("剑与灯.txt", crlf.toByteArray(Charsets.UTF_8))

        assertEquals("剑与灯", book.title)
        assertEquals(4, book.chapters.size)     // 规范化后照样切得开
    }

    // ══ 没有章节标记时的退路 ════════════════════════════════

    /**
     * 散文、短篇、笔记类 txt 没有"第X章"这种标记。
     * 这时不能报错，而应该整本当成一章 —— 用户至少还能读。
     */
    @Test
    fun `没有章节标记时整本书算一章`() {
        val plain = "这不是小说\n只是一段没有章节标记的文本。\n"
        val book = parser.parse("随便.txt", plain.toByteArray(Charsets.UTF_8))

        assertEquals(1, book.chapters.size)
        assertEquals("全文", book.chapters[0].title)
    }

    // ══ 异常路径 ════════════════════════════════════════════

    /**
     * assertThrows 是 JUnit 4.13 才加进来的（本项目用 4.13.2）。
     * 更老的写法是 try/catch + fail()，又长又容易写错。
     *
     * 语法上：assertThrows 的第二个参数是 SAM 接口 ThrowingRunnable，
     * 所以 Kotlin 能直接用尾随 lambda { ... } 传进去。
     */
    @Test
    fun `空文件抛 BookParseException`() {
        assertThrows(BookParseException::class.java) {
            parser.parse("空.txt", ByteArray(0))
        }
    }

    /**
     * 只有空格/换行/制表符的文件 —— 解码不报错，但没有任何可读内容。
     * 这条走的是 TxtBookParser 里第二个校验：text.isBlank()。
     * （第一个校验是 bytes.isEmpty()，那只是字节层面为空。）
     */
    @Test
    fun `只有空白字符的文件抛 BookParseException`() {
        assertThrows(BookParseException::class.java) {
            parser.parse("空.txt", "   \n\n\t\n".toByteArray(Charsets.UTF_8))
        }
    }
}
