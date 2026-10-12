package com.example.myapplication.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    // ══ 守卫 1：能否认出来 ══════════════════════════════════

    @Test
    fun `识别各种格式的章节标题`() {
        val shouldMatch = listOf(
            "第一章 风起",
            "第 2 章 有空格",
            "第一百二十三章 决战",
            "【第四章】带括号的",
            "番外一 那年夏天",
            "第一章风起没有空格",
            "第十章",
            "第1章无空格",
            "序章",
            "Chapter 5 English",
        )
        shouldMatch.forEach {
            assertTrue("应该识别为标题: <$it>", ChapterSplitter.isChapterLine(it))
        }
    }

    // ══ 守卫 2~5：会不会误判 ════════════════════════════════

    @Test
    fun `拒绝伪装成标题的正文行`() {
        val shouldNotMatch = listOf(
            // 守卫 1 拦下：不在行首
            "他在第一章里看到过这句话。",
            // 守卫 5 拦下：无分隔符 + 首字是结构助词"的"
            "第一章的内容很有意思",
            // 守卫 4 拦下：结尾是句末标点
            "第一章的内容很有意思。",
            "这一行是普通正文，不应该被当成章节。",
            // 守卫 3 拦下：标题部分超过 40 字
            "第一章 " + "很长的标题内容".repeat(8),
            // 空行 / 纯空白
            "",
            "　　　　",
        )
        shouldNotMatch.forEach {
            assertFalse("不应该识别为标题: <$it>", ChapterSplitter.isChapterLine(it))
        }
    }

    // ══ 切分结果 ══════════════════════════════════════════

    @Test
    fun `一个标题都没有时整本书算一章`() {
        val text = "只有正文，没有任何章节标记。\n第二行。\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
        assertEquals(0, chapters[0].startOffset)
        assertEquals(text.length, chapters[0].endOffset)
    }

    @Test
    fun `第一行就是标题时不产生前言章节`() {
        val chapters = ChapterSplitter.split("第一章 开始\n正文。\n")
        assertEquals(1, chapters.size)
        assertEquals("第一章 开始", chapters[0].title)
    }

    @Test
    fun `标题之前有内容时产生前言章节`() {
        val text = "书名：测试\n作者：某人\n\n第一章 开始\n正文。\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertEquals("前言", chapters[0].title)
        assertEquals("第一章 开始", chapters[1].title)
    }

    @Test
    fun `章节正文不包含标题行本身`() {
        val text = "第一章 开始\n正文第一段。\n"
        val c = ChapterSplitter.split(text)[0]
        assertEquals("正文第一段。\n", text.substring(c.startOffset, c.endOffset))
    }

    @Test
    fun `紧挨着的两个标题都能识别`() {
        // 这条例子的关键：第二个标题的上一行【不是空行】而是另一个标题。
        // 靠的是守卫 2 的后半句 prevWasHeading。
        val chapters = ChapterSplitter.split("第一章 甲\n第二章 乙\n正文。\n")
        assertEquals(2, chapters.size)
        assertEquals("第一章 甲", chapters[0].title)
        assertEquals("第二章 乙", chapters[1].title)
    }

    @Test
    fun `多章节的标题抽取全部正确`() {
        val text = """
            测试小说：剑与灯
            作者：练习生

            第一章 风起
            　　正文一。

            第 2 章 有空格
            　　正文二。

            番外一 那年夏天
            　　正文三。
        """.trimIndent()

        val titles = ChapterSplitter.split(text).map { it.title }
        assertEquals(
            listOf("前言", "第一章 风起", "第 2 章 有空格", "番外一 那年夏天"),
            titles
        )
    }

    @Test
    fun `章节偏移量递增且互不重叠`() {
        val text = "书名\n\n第一章 甲\n正文一。\n\n第二章 乙\n正文二。\n"
        val chapters = ChapterSplitter.split(text)
        for (i in 0 until chapters.size - 1) {
            assertTrue(
                "第 $i 章结束位置 ${chapters[i].endOffset} 超过了下一章开始位置 ${chapters[i + 1].startOffset}",
                chapters[i].endOffset <= chapters[i + 1].startOffset
            )
        }
    }
}