package com.example.myapplication.domain.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextSegmenter 的单元测试。
 *
 * ══ 【这个类里最重要的一条断言】═══════════════════════════════════
 * 每一段的 [SpeechSegment.startOffset] 必须能在原文里【精确还原】出
 * 它自己的 text：
 *
 *     text.substring(seg.startOffset, seg.startOffset + seg.text.length) == seg.text
 *
 * 为什么它是命根子：将来做"念到哪、屏幕跟到哪"的高亮同步时，
 * 全靠这个 offset 去定位屏幕位置。offset 偏一个字符，
 * 高亮就会跑到别的字上 —— 而且这种 bug 在界面上看起来
 * 只是"高亮有点歪"，很难联想到是分句逻辑算错了。
 *
 * 所以下面每个测试都会顺手验一遍这条。
 * ═══════════════════════════════════════════════════════════════
 */
class TextSegmenterTest {

    /** 校验：每一段的 offset 都能在原文里精确取出它自己的文本 */
    private fun assertOffsetsConsistent(text: String, segments: List<SpeechSegment>) {
        segments.forEach { seg ->
            val end = seg.startOffset + seg.text.length
            assertTrue(
                "分段越界了：startOffset=${seg.startOffset} len=${seg.text.length} 原文长度=${text.length}",
                end <= text.length
            )
            assertEquals(
                "offset 和 text 对不上（这说明分句逻辑算错了位置）",
                seg.text,
                text.substring(seg.startOffset, end)
            )
        }
    }

    private val novel = """
        第1章 测试章节1

        这是第 1 章的第 1 段正文。中文编码测试：剑、灯、龙、雪、风。
        这是第 1 章的第 2 段正文。这一句故意写得长一些，用来验证分页和滚动是否正确显示。
    """.trimIndent()

    // ══ 基本情况 ════════════════════════════════════════════

    @Test
    fun `空文本返回空列表`() {
        assertTrue(TextSegmenter.split("", 0).isEmpty())
    }

    @Test
    fun `起始偏移超出文本长度返回空列表`() {
        assertTrue(TextSegmenter.split("很短的一句话。", 999).isEmpty())
    }

    @Test
    fun `普通文本按句号切分`() {
        val segments = TextSegmenter.split(novel, 0)

        assertTrue("应该切出多段", segments.size > 1)
        assertOffsetsConsistent(novel, segments)
        // 每段都以句末标点结尾（最后一段可能例外）
        segments.dropLast(1).forEach { seg ->
            assertTrue(
                "分段应该在句末标点后断开，实际结尾是「${seg.text.last()}」",
                seg.text.last() in charArrayOf('。', '！', '？', '；', '…', '\n', '!', '?', ';')
            )
        }
    }

    // ══ 起始位置（朗读起点 = 当前阅读位置）════════════════════

    /**
     * 【这是"从当前位置开始朗读"的核心】
     * 用户读到一半按朗读，必须从他看的那一行念起，
     * 而不是从全书开头重新念一遍。
     */
    @Test
    fun `从指定偏移开始切分`() {
        val from = novel.indexOf("第 2 段")
        val segments = TextSegmenter.split(novel, from)

        assertTrue(segments.isNotEmpty())
        assertOffsetsConsistent(novel, segments)

        // 第一段必须从 from 处（或之后）开始
        assertTrue(
            "第一段不应早于起始偏移，实际 startOffset=${segments.first().startOffset}",
            segments.first().startOffset >= from
        )
        // 而且不能把后面还没读的内容漏掉
        val joined = segments.joinToString("") { it.text }
        assertTrue("应该包含起始位置之后的内容", joined.contains("第 2 段"))
    }

    @Test
    fun `起始偏移正好落在标点上也不会错位`() {
        val dotIndex = novel.indexOf('。')
        val segments = TextSegmenter.split(novel, dotIndex)

        assertOffsetsConsistent(novel, segments)
    }

    // ══ 长句处理 ════════════════════════════════════════════

    @Test
    fun `超长句在逗号处退让切分`() {
        // 一整句没有句号，只有逗号
        val longSentence = "这是一句很长的话，" .repeat(30) + "结束。"
        val segments = TextSegmenter.split(longSentence, 0, maxChars = 60)

        assertOffsetsConsistent(longSentence, segments)
        assertTrue("应该被切成多段", segments.size > 1)
        segments.forEach { seg ->
            assertTrue(
                "每段不应超过上限太多，实际 ${seg.text.length}",
                seg.text.length <= 70
            )
        }
    }

    /**
     * 【这条守的是"死循环"】
     * 如果一整段文本里【一个标点都没有】，退让逻辑找不到任何停顿点。
     * 这时必须硬切 —— 否则 while 循环永远出不去，App 直接卡死。
     */
    @Test
    fun `完全没有标点的长文本也能切出来`() {
        val noPunctuation = "字".repeat(500)
        val segments = TextSegmenter.split(noPunctuation, 0, maxChars = 50)

        assertOffsetsConsistent(noPunctuation, segments)
        assertTrue("必须切出多段，不能一段都不切", segments.size > 1)

        // 把切出来的拼回去，应该和原文完全一致（因为没有可跳过的空白）
        assertEquals(noPunctuation, segments.joinToString("") { it.text })
    }

    @Test
    fun `短文本不会被切碎`() {
        val short = "就一句话。"
        val segments = TextSegmenter.split(short, 0)
        assertEquals(1, segments.size)
        assertEquals(short, segments.first().text)
        assertEquals(0, segments.first().startOffset)
    }

    // ══ 过滤无意义内容 ══════════════════════════════════════

    /**
     * 纯空白和纯符号交给 TTS 只会得到一段尴尬的静音，
     * 白白拖慢朗读、还让进度回调变混乱。
     */
    @Test
    fun `纯空白和纯符号段会被跳过`() {
        val text = "正文一。\n\n\n    \n。。。\n正文二。"
        val segments = TextSegmenter.split(text, 0)

        assertOffsetsConsistent(text, segments)
        segments.forEach { seg ->
            assertTrue("不该有空白段：'${seg.text}'", seg.text.isNotBlank())
            assertTrue(
                "不该有纯符号段：'${seg.text}'",
                seg.text.any { it.isLetterOrDigit() }
            )
        }
        assertEquals("应该只剩下两句正文", 2, segments.size)
    }

    // ══ 守恒律（穷举参数）═══════════════════════════════════

    /**
     * 【最重要的一条】
     * 对任意起始位置、任意分段上限，每一段的 offset 都必须精确。
     *
     * 这种"穷举参数"的写法比手写几个例子强得多 ——
     * 边界 bug 恰恰藏在那些不太可能被手动想到的参数组合里。
     */
    @Test
    fun `任意起点和任意上限下 offset 都保持精确`() {
        for (start in 0..novel.length step 7) {
            for (maxChars in listOf(10, 25, 60, 160, 1000)) {
                val segments = TextSegmenter.split(novel, start, maxChars)
                assertOffsetsConsistent(novel, segments)

                // 段与段的 offset 必须严格递增，不能重叠
                segments.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        "分段重叠了：start=$start max=$maxChars " +
                            "[${a.startOffset},${a.startOffset + a.text.length}) 与 ${b.startOffset}",
                        a.startOffset + a.text.length <= b.startOffset
                    )
                }
            }
        }
    }
}
