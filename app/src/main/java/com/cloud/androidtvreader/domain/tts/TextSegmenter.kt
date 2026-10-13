package com.cloud.androidtvreader.domain.tts

/**
 * 一段待朗读的文本。
 *
 * [startOffset] 是它在【全书】中的起始字符偏移 ——
 * 和 [com.cloud.androidtvreader.domain.model.ReadingProgress] 用的是同一套坐标系。
 *
 * 【这一点是白捡的便宜】
 * Android TTS 的 `onRangeStart(utteranceId, start, end, frame)` 回调给出的
 * start/end 也是"这段文本里的字符下标"。两套坐标天然对得上，
 * 将来要做"念到哪、屏幕跟到哪"的高亮同步时，不需要任何换算。
 */
data class SpeechSegment(
    val text: String,
    /** 在全书中的起始字符偏移 */
    val startOffset: Int,
)

/**
 * 把正文切成适合朗读的小段。
 *
 * ══ 【为什么必须切，不能整段丢给 TTS】═══════════════════════════
 * 1. **引擎有长度上限**。很多 TTS 实现对单次输入有几千字符的限制，
 *    超了会截断或者直接报错。
 * 2. **没法跟踪进度**。整段提交只有一个"开始/结束"，中间是黑盒；
 *    切成小段之后，每段读完都有回调，才能知道"念到哪了"。
 * 3. **打断不灵活**。用户按停止时，整段提交就只能全丢。
 *
 * ══ 【断句规则】══════════════════════════════════════════════════
 * 优先在【句末标点】断开（。！？；…），这样每段是一句完整的话，
 * 听感最自然。如果一句话特别长（超过 [DEFAULT_MAX_CHARS]），
 * 就退一步在【逗号、顿号、空格】这些"软停顿"处切 ——
 * 总比在一句话中间硬断要好。
 *
 * 实在找不到任何停顿点（比如一整段没有标点的流水账），
 * 才按长度硬切。这是最后手段，但必须有，否则死循环。
 * ═══════════════════════════════════════════════════════════════
 */
object TextSegmenter {

    /**
     * 单个朗读段的最大字符数。
     *
     * 【为什么是 160】
     * 中文按正常语速大约每秒 4~5 个字，160 字约 35 秒。
     * 这个长度下：
     *   · 远低于任何引擎的上限，安全
     *   · 进度回调的粒度够细（不会出现"停了半分钟没反应"）
     *   · 又不会碎到影响听感（每段都是完整的一两句）
     */
    const val DEFAULT_MAX_CHARS = 160

    /** 句末标点：在这些字符【之后】断开是一句完整的话 */
    private val SENTENCE_END = charArrayOf(
        '。', '！', '？', '；', '…', '!', '?', ';', '\n',
    )

    /** 软停顿：句子太长时的退让点 */
    private val SOFT_BREAK = charArrayOf(
        '，', '、', '：', ',', ':', '—', ' ', '\t', '“', '”', '「', '」',
    )

    /**
     * 从 [fromOffset] 开始，把 [text] 切成朗读段。
     *
     * @param fromOffset 起始字符偏移（就是用户当前的阅读位置）
     * @param maxChars 单段最大长度
     */
    fun split(
        text: String,
        fromOffset: Int,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<SpeechSegment> {
        if (text.isEmpty()) return emptyList()

        val start = fromOffset.coerceIn(0, text.length)
        if (start >= text.length) return emptyList()

        val result = ArrayList<SpeechSegment>()
        var segStart = start
        var i = start

        while (i < text.length) {
            val ch = text[i]
            val hitSentenceEnd = ch in SENTENCE_END
            val hitMaxLength = i - segStart + 1 >= maxChars

            if (hitSentenceEnd) {
                // 句末标点：就断在这里（含标点本身）
                addSegment(text, segStart, i + 1, result)
                segStart = i + 1
                i = segStart
            } else if (hitMaxLength) {
                // 太长了：尽量退到最近的软停顿处；
                // 找不到就硬切（必须有这条兜底，否则这个循环永远出不去）
                val cut = findLastSoftBreak(text, segStart, i) ?: i
                // +1 是为了把那个逗号一起包含进来 —— 留着它，TTS 会读出一个
                // 自然的停顿。不过注意：如果是硬切（cut == i），
                // 这里会多算一个字符，下一段的起点要跟着调整
                val endExclusive = if (cut == i) i else cut + 1
                addSegment(text, segStart, endExclusive, result)
                segStart = endExclusive
                i = segStart
            } else {
                i++
            }
        }

        // 最后一段可能没有以标点结尾
        if (segStart < text.length) {
            addSegment(text, segStart, text.length, result)
        }

        return result
    }

    /** 从 [from]（含）到 [to]（含）倒着找第一个软停顿 */
    private fun findLastSoftBreak(text: String, from: Int, to: Int): Int? {
        for (i in to downTo from) {
            if (text[i] in SOFT_BREAK) return i
        }
        return null
    }

    /**
     * 收集一段。
     *
     * 【为什么要过滤】
     * 正文里夹杂的纯空白、纯符号段落交给 TTS 只会得到一段尴尬的静音，
     * 白白拖慢朗读、还让进度回调变得混乱。直接跳过更干净。
     */
    private fun addSegment(
        text: String,
        from: Int,
        to: Int,
        out: MutableList<SpeechSegment>,
    ) {
        if (from >= to) return
        val raw = text.substring(from, to)
        if (raw.isBlank()) return
        // 一个字母或数字都没有 → 整段都是符号，不值得念
        if (raw.none { it.isLetterOrDigit() }) return
        out.add(SpeechSegment(text = raw, startOffset = from))
    }
}
