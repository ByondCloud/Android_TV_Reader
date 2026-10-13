package com.cloud.androidtvreader.domain.reader

import com.cloud.androidtvreader.domain.model.ParsedChapter

/**
 * 一个【阅读单元】—— 阅读器排版和翻页的最小单位。
 *
 * ══ 为什么要引入这一层抽象 ══════════════════════════════════════
 * 最初阅读器直接吃 [ParsedChapter]，也就是"一章 = 一个阅读单元"。
 * 但用户可能不想要章节边界（章节之间会断开，一章末尾那页填不满），
 * 于是需要另一种切法："不管章节，按大小切成等份"。
 *
 * 如果让阅读器同时认识这两种切法，它的代码里就会到处是 if-else。
 * 抽成 ReadingUnit 之后，阅读器只认识一种东西：
 *   「给我一个文本区间，我把它排版显示出来」
 * 至于这个区间是"一章"还是"八千字"的一段，它不需要知道。
 *
 * 这就是**用抽象隔离变化**：变化的是"怎么切"，不变的是"怎么显示"。
 * ═══════════════════════════════════════════════════════════════
 */
data class ReadingUnit(
    /** 序号，从 0 开始 */
    val index: Int,
    /** 显示用的标题。整本模式下为空串（表示"不显示单元标题"） */
    val title: String,
    /** 在全书文本中的起始下标（含） */
    val startOffset: Int,
    /** 结束下标（不含） */
    val endOffset: Int,
) {
    val length: Int get() = endOffset - startOffset
}

/**
 * 阅读单元的切分策略。
 *
 * 【为什么不直接在阅读器里调它，而要单独一个对象】
 * 因为"怎么切"是纯算术，可以写单元测试；而阅读器依赖 Compose，测不了。
 * 把它独立出来，就能用一条【守恒律】断言守住正确性：
 *
 *     所有单元拼起来 == 原文，一个字符都不差
 *
 * 这条断言比"切成了几段"重要得多 —— 段数错了只是多按几次翻页键，
 * 丢字是内容错误，用户会读到缺句子的书。
 */
object ReadingUnits {

    /**
     * 关闭章节分页时，每个单元的目标大小（字符数）。
     *
     * 【为什么是 8000】
     * 这个值要在两个矛盾的目标之间取平衡：
     *
     *   太大 → 排版一次要处理的文本太多。TextMeasurer 为整块文本建
     *          排版对象，100 万字符会吃掉几百 MB 内存并卡住几秒。
     *   太小 → 单元之间的"页面填不满"的间隙出现得太频繁，用户会
     *          明显感觉到"怎么又断了一下"。
     *
     * 8000 字符 ≈ 300 行 ≈ 35 屏。也就是说：大约每翻 35 次才遇到一次
     * 单元边界。对比章节模式（一章可能只有 1~2 屏就断了），
     * 这个密度用户基本察觉不到。
     */
    const val DEFAULT_CHUNK_CHARS = 8000

    /**
     * 按【章节】切分 —— 保持章节边界，目录、跳章都能用。
     */
    fun byChapter(chapters: List<ParsedChapter>): List<ReadingUnit> =
        chapters.map { chapter ->
            ReadingUnit(
                index = chapter.index,
                title = chapter.title,
                startOffset = chapter.startOffset,
                endOffset = chapter.endOffset,
            )
        }

    /**
     * 按【大小】切分 —— 忽略章节，把全文切成等份，读起来是连续的一整本。
     *
     * 【为什么尽量对齐到换行符】
     * 如果死板地每 8000 字符切一刀，很可能正好切在一句话中间 ——
     * 单元边界处那一屏会以半句话结尾，看起来像排版出错。
     * 所以往后找最近的换行符作为边界，让断开处落在段落之间。
     *
     * 【为什么要限制"往回找的距离"】
     * 万一后面 8000 字符都没有换行（比如一整段超长文本），
     * 一路找下去会让这个单元变得极大。所以只接受位置过半之后的换行，
     * 否则宁可硬切。这是一种"务实的启发式"：绝大多数情况好看，
     * 极端情况也不会失控。
     */
    fun bySize(
        text: String,
        targetChars: Int = DEFAULT_CHUNK_CHARS,
    ): List<ReadingUnit> {
        require(targetChars >= 1) { "targetChars 必须 >= 1，实际是 $targetChars" }
        if (text.isEmpty()) return emptyList()

        val units = ArrayList<ReadingUnit>()
        var start = 0
        var index = 0

        while (start < text.length) {
            var end = (start + targetChars).coerceAtMost(text.length)

            if (end < text.length) {
                // 从目标位置往前找最近的换行符
                val newline = text.lastIndexOf('\n', end - 1)
                // 只有"不算太靠前"才采用，避免把单元切得过小
                if (newline > start + targetChars / 2) {
                    end = newline + 1        // 换行符本身归上一段，保证拼接时不漏 \n
                }
            }

            units.add(
                ReadingUnit(
                    index = index,
                    title = "",              // 整本模式下不显示单元标题
                    startOffset = start,
                    endOffset = end,
                )
            )
            start = end
            index++
        }

        return units
    }

    /**
     * 统一入口：根据设置决定用哪种切法。
     *
     * 【为什么要在这里再兜一层】
     * byChapter 依赖外部传进来的 chapters。如果那份数据有问题
     * （空列表、区间为 0 长度、首尾对不上全文），阅读器就会显示空白或崩溃。
     * 这里是最后一道防线：切出来的结果不合法时，退回"整本当一个单元"。
     */
    fun build(
        text: String,
        chapters: List<ParsedChapter>,
        chapterSplitEnabled: Boolean,
        chunkSize: Int = DEFAULT_CHUNK_CHARS,
    ): List<ReadingUnit> {
        if (text.isEmpty()) return emptyList()

        val units = if (chapterSplitEnabled) {
            val fromChapters = byChapter(chapters).filter { it.length > 0 }
            fromChapters.ifEmpty { bySize(text, chunkSize) }
        } else {
            bySize(text, chunkSize)
        }

        return units.ifEmpty {
            listOf(ReadingUnit(0, "", 0, text.length))
        }
    }
}
