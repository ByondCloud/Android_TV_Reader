package com.cloud.androidtvreader.domain.reader

import com.cloud.androidtvreader.domain.model.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReadingUnits 的单元测试。
 *
 * ══ 核心断言是【守恒律】═══════════════════════════════════════
 *
 *   所有阅读单元拼起来，必须严格等于原文。
 *
 * 一个字符都不能少、不能多、不能重复。
 * 这条比"切成了几个单元"重要得多 —— 单元数错了用户只是多翻几次，
 * 丢字是内容错误，用户会读到缺句子的书。
 * ═══════════════════════════════════════════════════════════════
 */
class ReadingUnitTest {

    /** 把单元拼回一个字符串，用于守恒断言 */
    private fun join(text: String, units: List<ReadingUnit>): String =
        buildString {
            units.forEach { append(text, it.startOffset, it.endOffset) }
        }

    /** 造一批假章节，模拟 ChapterSplitter 的输出 */
    private fun fakeChapters(text: String, count: Int): List<ParsedChapter> {
        val size = text.length / count
        return (0 until count).map { i ->
            val start = i * size
            val end = if (i == count - 1) text.length else (i + 1) * size
            ParsedChapter(index = i, title = "第 ${i + 1} 章", startOffset = start, endOffset = end)
        }
    }

    private val longText: String = (1..500).joinToString("\n") { "第 $it 行的内容" }

    // ══ 按大小切分 ══════════════════════════════════════════

    @Test
    fun `文本比目标小时只有一个单元`() {
        val text = "很短的一段文字"
        val units = ReadingUnits.bySize(text, targetChars = 1000)

        assertEquals(1, units.size)
        assertEquals(0, units[0].startOffset)
        assertEquals(text.length, units[0].endOffset)
    }

    @Test
    fun `长文本被切成多个单元且不丢字`() {
        val units = ReadingUnits.bySize(longText, targetChars = 200)

        assertTrue("应该切成多个单元，实际 ${units.size} 个", units.size > 1)
        assertEquals(longText, join(longText, units))
    }

    /**
     * 【最重要的一条】穷举各种目标大小，守恒律都必须成立。
     * 一条参数化的断言，比手写十几个用例更能发现问题。
     */
    @Test
    fun `无论目标大小是多少，所有单元拼起来都等于原文`() {
        for (target in 1..300 step 7) {
            val units = ReadingUnits.bySize(longText, targetChars = target)
            assertEquals(
                "targetChars=$target 时守恒律被破坏",
                longText,
                join(longText, units),
            )
        }
    }

    @Test
    fun `单元之间首尾相接，既不重叠也不留缝`() {
        val units = ReadingUnits.bySize(longText, targetChars = 150)

        assertEquals(0, units.first().startOffset)
        for (i in 0 until units.size - 1) {
            assertEquals(
                "第 $i 个单元的结束位置应该等于第 ${i + 1} 个的开始位置",
                units[i].endOffset,
                units[i + 1].startOffset,
            )
        }
        assertEquals(longText.length, units.last().endOffset)
    }

    /**
     * 切分边界要落在换行符之后 —— 否则会把一句话劈成两半，
     * 单元边界那一屏看起来像排版出错。
     */
    @Test
    fun `切分边界尽量对齐到换行符`() {
        val units = ReadingUnits.bySize(longText, targetChars = 200)

        // 除了最后一个单元，每个单元的最后一个字符都应该是换行符
        units.dropLast(1).forEachIndexed { i, unit ->
            assertEquals(
                "第 $i 个单元应该以换行符结尾，实际结尾是 '${longText[unit.endOffset - 1]}'",
                '\n',
                longText[unit.endOffset - 1],
            )
        }
    }

    /**
     * 极端情况：一整段超长文本完全没有换行符。
     * 这时必须硬切，不能为了找换行符把单元撑得无限大。
     */
    @Test
    fun `整段没有换行时按目标大小硬切`() {
        val text = "a".repeat(1000)
        val units = ReadingUnits.bySize(text, targetChars = 100)

        assertEquals(10, units.size)
        assertEquals(text, join(text, units))
    }

    @Test
    fun `空文本返回空列表`() {
        assertTrue(ReadingUnits.bySize("", targetChars = 100).isEmpty())
    }

    @Test
    fun `目标大小为零或负数时明确报错`() {
        // fail-fast：与其让后面除零或死循环，不如在这里立刻失败
        assertThrows(IllegalArgumentException::class.java) {
            ReadingUnits.bySize("abc", targetChars = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingUnits.bySize("abc", targetChars = -5)
        }
    }

    // ══ 按章节切分 ══════════════════════════════════════════

    @Test
    fun `按章节切分保留标题和区间`() {
        val chapters = fakeChapters(longText, count = 5)
        val units = ReadingUnits.byChapter(chapters)

        assertEquals(5, units.size)
        assertEquals("第 1 章", units[0].title)
        assertEquals("第 5 章", units[4].title)
        assertEquals(longText, join(longText, units))
    }

    // ══ 统一入口 build() ════════════════════════════════════

    @Test
    fun `开关打开时按章节切分`() {
        val chapters = fakeChapters(longText, count = 6)
        val units = ReadingUnits.build(
            text = longText,
            chapters = chapters,
            chapterSplitEnabled = true,
            chunkSize = 100,
        )

        assertEquals(6, units.size)
        assertEquals("第 1 章", units[0].title)
    }

    @Test
    fun `开关关闭时忽略章节，按大小切分`() {
        val chapters = fakeChapters(longText, count = 6)
        val units = ReadingUnits.build(
            text = longText,
            chapters = chapters,
            chapterSplitEnabled = false,
            chunkSize = 100,
        )

        // 单元数由大小决定，和章节数无关
        assertTrue("应该切成很多份，实际 ${units.size}", units.size > 6)
        // 整本模式下不显示单元标题
        assertTrue(units.all { it.title.isEmpty() })
        assertEquals(longText, join(longText, units))
    }

    /**
     * 兜底：外部传进来的章节数据不可用时（空列表），
     * 不能返回空单元列表 —— 那会让阅读器显示一片空白。
     */
    @Test
    fun `章节数据为空时退回按大小切分，而不是显示空白`() {
        val units = ReadingUnits.build(
            text = longText,
            chapters = emptyList(),
            chapterSplitEnabled = true,
            chunkSize = 200,
        )

        assertTrue(units.isNotEmpty())
        assertEquals(longText, join(longText, units))
    }

    /**
     * 兜底：章节区间全是 0 长度（数据坏了）时，同样要退回按大小切分。
     */
    @Test
    fun `章节区间为空的坏数据被过滤掉并退回按大小切分`() {
        val broken = listOf(
            ParsedChapter(0, "空章", 0, 0),
            ParsedChapter(1, "另一个空章", 10, 10),
        )
        val units = ReadingUnits.build(
            text = longText,
            chapters = broken,
            chapterSplitEnabled = true,
            chunkSize = 200,
        )

        assertTrue(units.isNotEmpty())
        assertEquals(longText, join(longText, units))
    }

    @Test
    fun `空文本在任何模式下都返回空列表`() {
        assertTrue(ReadingUnits.build("", emptyList(), true).isEmpty())
        assertTrue(ReadingUnits.build("", emptyList(), false).isEmpty())
    }
}
