package com.example.myapplication.data.parser

/** 从文本里猜出来的书名和作者 */
data class BookMeta(val title: String, val author: String?)

/**
 * 元信息抽取：猜书名和作者。
 *
 * 【为什么要"猜"】
 * txt 格式没有任何结构化字段，书名和作者就混在正文里。
 * 这是 txt 相比 epub 最大的劣势 —— epub 有 metadata，txt 只能靠猜。
 *
 * 【策略：显式标记 → 括号 → 第一行，从强到弱】
 * 每条规则只在前 2000 字符里找 —— 书名作者总在开头，
 * 全文搜索只会搜到正文里偶然出现的"作者"两个字。
 */
object BookMetaExtractor {

    private const val SCAN_LIMIT = 2000
    private const val MAX_TITLE_LENGTH = 50

    /** "作者：xxx" / "作 者 xxx" */
    private val AUTHOR_REGEX = Regex("""作\s*者\s*[:：\s]\s*(\S{1,30})""")

    /** "书名：xxx" / "标题：xxx" */
    private val TITLE_PREFIX_REGEX = Regex("""(?:书\s*名|标\s*题)\s*[:：]\s*(.+)""")

    /**
     * 整行只有《xxx》才算书名。
     * 【注意为什么加 ^ 和 $】如果不加，"他读完了《剑与灯》这本书。" 这行正文
     * 也会被当成书名，抽出"剑与灯"来。这就是 2.4 里说的"宁可漏不可错杀"。
     */
    private val BRACKET_TITLE_ONLY = Regex("""^\s*《([^》]{1,50})》\s*$""")

    fun extract(fileName: String, text: String): BookMeta {
        val head = text.take(SCAN_LIMIT)
        val lines = head.split('\n')

        val author = AUTHOR_REGEX.find(head)
            ?.groupValues?.get(1)
            ?.trim()
            ?.trimEnd('。', '，', ',', '.', '、', '：', ':', '（', '(', '）', ')')
            ?.takeIf { it.isNotEmpty() }

        return BookMeta(title = guessTitle(lines) ?: fileNameToTitle(fileName), author = author)
    }

    private fun guessTitle(lines: List<String>): String? {
        // ① 最强证据：显式写了"书名：xxx"
        lines.take(20).forEach { line ->
            TITLE_PREFIX_REGEX.find(line)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotEmpty()) return it
            }
        }

        // ② 次强：整行是《xxx》
        lines.take(10).forEach { line ->
            BRACKET_TITLE_ONLY.find(line)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotEmpty()) return it
            }
        }

        // ③ 兜底：文件的【第一行非空内容】。
        //    【为什么只看第一行，而不是"第一个像书名的行"】
        //    如果放宽成"第一个看起来不像章节标题的行"，
        //    遇到"第一章 风起"开头的文件，会往后找到"　　正文段落。"当书名 —— 荒谬。
        //    只认第一行，错了也错得有限：大不了回退到文件名。
        val firstLine = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (firstLine.length > MAX_TITLE_LENGTH) return null
        if (ChapterSplitter.isChapterLine(firstLine)) return null
        if (AUTHOR_REGEX.containsMatchIn(firstLine)) return null
        return firstLine
    }

    private fun fileNameToTitle(fileName: String): String =
        fileName.substringBeforeLast('.').ifBlank { fileName }
}