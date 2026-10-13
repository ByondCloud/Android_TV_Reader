package com.cloud.androidtvreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paginator 的单元测试。
 *
 * ══ 这个类测试的核心是一条【守恒律】═══════════════════════════
 *
 *   所有页的文本拼起来，必须严格等于原文。
 *
 * 一个字符都不能少、不能多、不能重复。
 * 这条断言比"页数对不对"重要得多 —— 页数错了用户只是多按几下翻页键，
 * 而丢字是【内容错误】，用户会读到缺句子的书。
 *
 * 这也是为什么分页逻辑必须和 Compose 解耦：只有它是纯函数，
 * 我才能在电脑上跑这条断言，而不必在模拟器上一页页翻着找。
 * ═══════════════════════════════════════════════════════════════
 */
class PaginatorTest {

    /**
     * 模拟 TextLayoutResult 给出的行边界。
     *
     * 【为什么可以直接用 \n 来切】
     * 因为 Paginator 的正确性【不依赖行边界是怎么算出来的】——
     * 它只要求边界是"连续的"（上一行的 end == 下一行的 start）。
     * 真实的 Compose 排版会把长段落切成多个视觉行，
     * 但那些行同样是连续的，所以这里用 \n 切出来的边界
     * 和真实排版在结构上是等价的，足以验证分组逻辑。
     *
     * 注意 end 包含换行符本身 —— 这正是 Compose 的
     * getLineEnd(lineIndex)（visibleEnd = false）的行为。
     */
    private fun bounds(text: String): Pair<IntArray, IntArray> {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                starts.add(start)
                ends.add(i + 1)          // 含 \n
                start = i + 1
            }
            i++
        }
        // 最后一行（可能没有结尾换行）
        starts.add(start)
        ends.add(text.length)
        return starts.toIntArray() to ends.toIntArray()
    }

    private fun paginate(text: String, linesPerPage: Int): List<PageRange> {
        val (starts, ends) = bounds(text)
        return Paginator.paginate(starts, ends, linesPerPage, text.length)
    }

    /** 把页范围拼回一个字符串，用于守恒断言 */
    private fun joinPages(text: String, pages: List<PageRange>): String =
        buildString {
            pages.forEach { append(text, it.startOffset, it.endOffset) }
        }

    // ══ 守恒律：最重要的一组测试 ════════════════════════════

    @Test
    fun `每页两行时，所有页拼起来等于原文`() {
        val text = "第一行\n第二行\n第三行\n第四行\n第五行"
        val pages = paginate(text, linesPerPage = 2)

        assertEquals(3, pages.size)                       // 2 + 2 + 1
        assertEquals(text, joinPages(text, pages))
    }

    @Test
    fun `行数不能被每页行数整除时，最后一页更短且不丢字`() {
        val text = "1\n2\n3\n4\n5\n6\n7"
        val pages = paginate(text, linesPerPage = 3)

        assertEquals(3, pages.size)                       // 3 + 3 + 1
        assertEquals(text, joinPages(text, pages))
        assertTrue("最后一页应该更短", pages.last().length < pages.first().length)
    }

    @Test
    fun `每页一行时逐行切分`() {
        val text = "甲\n乙\n丙"
        val pages = paginate(text, linesPerPage = 1)

        assertEquals(3, pages.size)
        assertEquals(text, joinPages(text, pages))
    }

    @Test
    fun `每页行数大于总行数时只有一页`() {
        val text = "甲\n乙"
        val pages = paginate(text, linesPerPage = 100)

        assertEquals(1, pages.size)
        assertEquals(text, joinPages(text, pages))
    }

    @Test
    fun `页之间首尾相接，既不重叠也不留缝`() {
        val text = "第一行\n第二行\n第三行\n第四行\n第五行\n第六行"
        val pages = paginate(text, linesPerPage = 2)

        assertEquals(0, pages.first().startOffset)
        for (i in 0 until pages.size - 1) {
            assertEquals(
                "第 $i 页的结束位置应该等于第 ${i + 1} 页的开始位置",
                pages[i].endOffset,
                pages[i + 1].startOffset,
            )
        }
        assertEquals(text.length, pages.last().endOffset)
    }

    // ══ 中文与全角空格（真实场景）═══════════════════════════

    @Test
    fun `带全角缩进的正文不会丢字`() {
        val text = "第一章 风起\n\n　　剑与灯，龙与雪。\n　　风雪夜归人。\n\n　　第二章的引子。"
        val pages = paginate(text, linesPerPage = 2)

        assertEquals(text, joinPages(text, pages))
        assertTrue(pages.size > 1)
    }

    @Test
    fun `文本末尾有换行符时也不丢字`() {
        // 真实 txt 文件的最后一行通常也带换行符。
        // 如果 Paginator 把末尾的空行吞掉，用户就会觉得"最后一行没了"。
        val text = "甲\n乙\n丙\n"
        val pages = paginate(text, linesPerPage = 2)

        assertEquals(text, joinPages(text, pages))
    }

    // ══ 边界情况 ════════════════════════════════════════════

    @Test
    fun `空文本返回空列表`() {
        assertTrue(Paginator.paginate(IntArray(0), IntArray(0), 3, 0).isEmpty())
    }

    @Test
    fun `没有行信息时整章算一页，而不是返回空`() {
        // 这是"测量失败"的兜底路径。
        // 宁可整章挤在一页里让用户滚动，也不能返回空白页 —— 那等于内容消失。
        val pages = Paginator.paginate(IntArray(0), IntArray(0), 10, textLength = 500)
        assertEquals(1, pages.size)
        assertEquals(PageRange(0, 500), pages[0])
    }

    @Test
    fun `行尾下标超过文本长度时被截断，不抛越界异常`() {
        // 防御性测试：万一测量结果和文本长度不一致（增量测量、文本被改动），
        // substring 会直接抛 StringIndexOutOfBoundsException。
        // Paginator 用 coerceAtMost 挡住了这种情况。
        val pages = Paginator.paginate(
            lineStarts = intArrayOf(0, 4),
            lineEnds = intArrayOf(4, 999),       // 故意超出
            linesPerPage = 2,
            textLength = 10,
        )
        assertEquals(1, pages.size)
        assertEquals(10, pages[0].endOffset)
    }

    @Test
    fun `每页行数为零或负数时明确报错`() {
        // 与其让后面除零或者死循环，不如在这里立刻失败。
        // 这类"参数不合法就大声报错"的写法叫 fail-fast。
        assertThrows(IllegalArgumentException::class.java) {
            Paginator.paginate(intArrayOf(0), intArrayOf(1), linesPerPage = 0, textLength = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Paginator.paginate(intArrayOf(0), intArrayOf(1), linesPerPage = -3, textLength = 1)
        }
    }

    // ══ 页数计算 ════════════════════════════════════════════

    @Test
    fun `页数等于总行数除以每页行数向上取整`() {
        val text = (1..10).joinToString("\n") { "第 $it 行" }
        val (starts, ends) = bounds(text)

        for (perPage in 1..12) {
            val pages = Paginator.paginate(starts, ends, perPage, text.length)
            val expected = (starts.size + perPage - 1) / perPage
            assertEquals("每页 $perPage 行时页数不对", expected, pages.size)
        }
    }
}
