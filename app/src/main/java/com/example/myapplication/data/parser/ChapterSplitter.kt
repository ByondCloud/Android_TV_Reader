package com.example.myapplication.data.parser

import com.example.myapplication.domain.model.ParsedChapter

/**
 * 把整本书的文本切成章节。
 *
 * ══ 核心认知 ═══════════════════════════════════════════════════
 * 解析真实世界的脏文本，**不存在"一个完美正则"**。
 *
 * 正确的做法是【一层层务实的启发式规则】，每层解决一类具体误判。
 * 下面 5 层守卫，每一层都是被某个真实的误判案例逼出来的，
 * 而且每一层都有对应测试（见 ChapterSplitterTest）。
 * ═══════════════════════════════════════════════════════════════
 *
 * 【这个类为什么是 object 而不是 class】
 * 它没有状态，只有纯函数。无状态的工具用 object 最合适 ——
 * 不用 new，也不会有"两个实例行为不一致"的问题。
 */
object ChapterSplitter {

    /** 守卫 1：识别标题行的正则（第1组=分隔符，第2组=标题正文） */
    private val CHAPTER_LINE_REGEX = Regex(
        "^[\\s\\u3000]*[【\\[（(]?" +
                "(?:" +
                "第[\\s\\u3000]*[0-9０-９〇零一二三四五六七八九十百千万两]+[\\s\\u3000]*[章节回卷篇集话]" +
                "|序章|序言|自序|楔子|引子|前言|后记|尾声|终章|大结局" +
                "|番外[0-9０-９一二三四五六七八九十]*" +
                "|Chapter[\\s]*[0-9]+" +
                ")" +
                "[】\\]）)]?" +
                "([\\s\\u3000、．,，:：\\-—]*)" +   // 第1组：标记与标题之间的分隔符
                "(.{0,40})$"                        // 第2组：标题正文，不跨行，≤40 字
    )

    /** 守卫 4：标题行结尾不允许出现这些标点 —— 正文才有句末标点 */
    private const val SENTENCE_END = "。！？；，、："

    /**
     * 守卫 5：标记与标题之间【没有分隔符】时，标题首字不允许是这些结构助词。
     *
     * 【为什么黑名单只有这 5 个字】
     * 第一版我写了"的了是在有和与就都也很不没这那地得着"，
     * 结果把 "番外一 那年夏天" 这种正常标题误杀了（首字"那"）。
     *
     * 判断规则要不要放宽时，先问：**两种错误的代价一样吗？**
     *   - 漏判一个标题 → 该章没切开，正文合并进上一章 → 内容还在
     *   - 误判一行正文 → 这行被当成标题【从正文里吃掉】→ 内容真丢了
     * 代价不对等，所以黑名单必须收窄 —— **宁可漏，不可错杀**。
     */
    private const val STRUCTURAL_PARTICLES = "的了着地得"

    /** 一行文本在全文中的位置（end 指向 \n 或文本末尾，不含 \n） */
    private data class Line(val start: Int, val end: Int, val text: String)

    /**
     * 判断【单独一行】是不是章节标题。
     *
     * 注意：这里只做"这一行长什么样"的判断。
     * "上一行必须是空行"这个条件不在这里 —— 因为它需要上下文，
     * 放在 [split] 里处理。**能只看单行的判断就别引入上下文**，这样更好测。
     */
    fun isChapterLine(line: String): Boolean {
        val m = CHAPTER_LINE_REGEX.matchEntire(line) ?: return false

        val separator = m.groupValues[1]
        val title = m.groupValues[2].trim()

        // 守卫 4：标题结尾不能是句末标点
        if (title.isNotEmpty() && title.last() in SENTENCE_END) return false

        // 守卫 5：无分隔符时，标题首字不能是结构助词
        if (separator.isEmpty() && title.isNotEmpty() && title.first() in STRUCTURAL_PARTICLES) {
            return false
        }

        return true
    }

    /**
     * 切分章节。
     *
     * @return 至少有一个元素。没有识别出任何标题时，整本书作为一章，标题是"全文"。
     */
    fun split(text: String): List<ParsedChapter> {
        val lines = splitLines(text)

        // ── 第一步：找出所有标题行的行号 ──────────────────────
        val headingLineIndexes = ArrayList<Int>()
        var prevWasBlank = true        // 文件开头视为"前面是空行"
        var prevWasHeading = false     // 守卫 2 的后半句：支持两个标题紧挨着

        for (i in lines.indices) {
            val line = lines[i].text
            // 守卫 2：上一行是空行，或上一行本身也是标题
            val isHeading = (prevWasBlank || prevWasHeading) && isChapterLine(line)
            if (isHeading) headingLineIndexes.add(i)
            prevWasHeading = isHeading
            prevWasBlank = line.isBlank()
        }

        // ── 第二步：一个标题都没有 —— 整本书当一章 ────────────
        if (headingLineIndexes.isEmpty()) {
            return listOf(ParsedChapter(index = 0, title = "全文", startOffset = 0, endOffset = text.length))
        }

        val result = ArrayList<ParsedChapter>()

        // ── 第三步：第一个标题之前的内容 ──────────────────────
        // 【设计决策】非空就保留成一章"前言"，而不是悄悄丢掉。
        // 理由：网络下载的 txt 开头经常有简介、广告、更新说明。
        // 悄悄丢掉等于吞内容；让用户看见，他才知道这本书的来历。
        val firstHeadingStart = lines[headingLineIndexes.first()].start
        if (text.substring(0, firstHeadingStart).isNotBlank()) {
            result.add(ParsedChapter(index = 0, title = "前言", startOffset = 0, endOffset = firstHeadingStart))
        }

        // ── 第四步：每个标题 → 下一个标题之前 ────────────────
        for ((n, lineIndex) in headingLineIndexes.withIndex()) {
            val headingLine = lines[lineIndex]
            // 正文从标题行的【下一行行首】开始：标题行本身由 chapter.title 承担，
            // 如果正文里再包含一次，阅读器就会把标题显示两遍。
            val contentStart = (headingLine.end + 1).coerceAtMost(text.length)
            val contentEnd = if (n + 1 < headingLineIndexes.size) {
                lines[headingLineIndexes[n + 1]].start
            } else {
                text.length
            }
            result.add(
                ParsedChapter(
                    index = result.size,
                    title = headingLine.text.trim(),
                    startOffset = contentStart,
                    endOffset = contentEnd,
                )
            )
        }

        return result
    }

    /** 自己按 \n 切行，而不是用 split('\n')，因为我们要保留每行的【下标】 */
    private fun splitLines(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var start = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                lines.add(Line(start, i, text.substring(start, i)))
                start = i + 1
            }
            i++
        }
        return lines
    }
}