package com.example.myapplication.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BookMetaExtractor 的单元测试。
 *
 * ══ 测试是怎么组织的：一条规则一组用例 ══════════════════════════
 * BookMetaExtractor 有 3 条优先级递减的规则：
 *   ① 显式标记"书名：xxx"  →  ② 整行只有《xxx》  →  ③ 第一行非空内容
 * 每个 @Test 方法对应一条规则，再加几个边界用例。
 *
 * ══ 新手最容易忽略的是【反例测试】══════════════════════════════
 * 比如"正文里提到书名号不能被当成书名"。
 * 这种"确保错误的事情不发生"的测试，价值往往比正例更高 ——
 * 因为它锁住的是你已经踩过的坑，防止以后改代码时又踩回去。
 *
 * ══ 关于反引号函数名 ════════════════════════════════════════════
 * Kotlin 的 JVM 后端允许用 `反引号包住中文/空格` 当函数名。
 * 好处是测试报告里能直接看懂在测什么，不用先翻译英文。
 * 注意：反引号里不能出现 `.` `;` `[` `]` `/` `\` 这些 JVM 不允许的字符。
 * ══════════════════════════════════════════════════════════════
 */
class BookMetaExtractorTest {

    // ══ 规则①：显式标记"书名：xxx" ═══════════════════════════

    @Test
    fun `从显式的书名标记抽取`() {
        val text = "书名：剑与灯\n作者：练习生\n\n第一章 风起\n正文。\n"
        val meta = BookMetaExtractor.extract("随便.txt", text)
        assertEquals("剑与灯", meta.title)
        assertEquals("练习生", meta.author)
    }

    // ══ 规则②：整行只有《xxx》 ═══════════════════════════════

    @Test
    fun `从书名号抽取`() {
        val meta = BookMetaExtractor.extract("x.txt", "《剑与灯》\n作者：练习生\n")
        assertEquals("剑与灯", meta.title)
    }

    /**
     * 【反例】书名号出现在正文句子里时，不能被当成书名。
     *
     * 为什么这条重要：正则 BRACKET_TITLE_ONLY 特意加了 ^ 和 $，
     * 要求【整行只有】《...》。如果不加这两个锚点，这一行正文
     * 会抽出"剑与灯"，而真正的书名行反而被白白跳过。
     *
     * 断言的结果也顺便说明了"规则②失效后会落到规则③"：
     * 书名变成了整行正文。
     */
    @Test
    fun `正文里提到书名号不会被误当成书名`() {
        // 整行不只有《》时，规则②不成立，会走规则③取第一行
        val text = "他读完了《剑与灯》这本书。\n\n第一章 风起\n正文。\n"
        val meta = BookMetaExtractor.extract("x.txt", text)
        assertEquals("他读完了《剑与灯》这本书。", meta.title)
    }

    // ══ 规则③：第一行非空内容 ═══════════════════════════════

    /**
     * 一条元信息都没有时，回退到文件名。
     * 文件名"剑与灯.txt" → substringBeforeLast('.') → "剑与灯"。
     */
    @Test
    fun `没有元信息时回退到文件名`() {
        val meta = BookMetaExtractor.extract("剑与灯.txt", "第一章 风起\n正文。\n")
        assertEquals("剑与灯", meta.title)
        assertNull(meta.author)
    }

    /**
     * 【反例】文件第一行就是章节标题时，绝对不能把它当书名。
     *
     * 这条防的是一个很荒谬的后果：如果规则③放宽成
     * "第一个看起来不像章节标题的行"，那么以"第一章 风起"开头的文件
     * 会一路往后找，最后把"　　正文段落。"当成书名。
     * 所以规则③只认【第一行】，错了也错得有限 —— 大不了回退文件名。
     */
    @Test
    fun `第一行就是章节标题时不把它当书名`() {
        val meta = BookMetaExtractor.extract("无名之书.txt", "第一章 风起\n正文。\n")
        assertEquals("无名之书", meta.title)
    }

    // ══ 作者抽取的边界情况 ══════════════════════════════════

    /**
     * AUTHOR_REGEX 用的是 \S{1,30}，而"。"不是空白字符，
     * 所以它会把句号一起吞进来，得到"练习生。"。
     * 代码里必须用 trimEnd 收尾。这个测试就是在锁这个行为。
     */
    @Test
    fun `作者名末尾的标点被清掉`() {
        val meta = BookMetaExtractor.extract("x.txt", "剑与灯\n作者：练习生。\n")
        assertEquals("练习生", meta.author)
    }

    /**
     * 只在前 2000 字符里找作者（SCAN_LIMIT）。
     *
     * 为什么必须有这个限制：真实 txt 的正文里经常出现"作者"两个字
     * （比如"作者写道"、"作者按"）。如果不限范围地全篇搜索，
     * 就会把正文片段当成作者名，而且它会覆盖掉真正的作者信息。
     *
     * 这里构造 2400 字正文把"作者：练习生"挤出前 2000 字，
     * 断言结果为 null —— 证明这个限制真的生效了。
     */
    @Test
    fun `作者只在前两千字里找`() {
        val filler = "正文内容。".repeat(600)      // 5 字 × 600 = 3000 字，远超 2000 的上限
        val meta = BookMetaExtractor.extract("x.txt", "剑与灯\n\n$filler\n作者：练习生\n")
        assertNull(meta.author)
    }
}
