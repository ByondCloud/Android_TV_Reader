package com.example.myapplication.domain.reader

/**
 * 一页在【章内文本】中的字符范围。
 *
 * 左闭右开：包含 [startOffset]，不包含 [endOffset]。
 * 这样下一页的 startOffset 正好等于上一页的 endOffset，拼接时不会重叠也不会漏。
 */
data class PageRange(
    val startOffset: Int,
    val endOffset: Int,
) {
    val length: Int get() = endOffset - startOffset
}

/**
 * 分页器：把"行边界"分组成"页边界"。
 *
 * ══ 为什么要把它单独抽出来，而不是直接写在界面里 ══════════════════
 * 分页是阅读器最容易出 bug 的地方，而且 bug 很隐蔽：
 * 少切一个字符 → 某段文字莫名跑到上一页末尾；
 * 多切一个字符 → 每个翻页都重复显示一个字。
 * 这两种问题靠肉眼翻页很难发现，但用户读起来会非常难受。
 *
 * 解决办法是【把"能测的部分"和"不能测的部分"分开】：
 *
 *   - 不能单测的部分：调用 Compose 的 TextMeasurer 得到每一行的起止下标。
 *     它需要真实的字体和排版环境，只能在设备上跑。
 *
 *   - 能单测的部分：【就是这个类】。给它一组行边界，让它算出页边界。
 *     它是纯算术，没有任何 Android 依赖，所以能在电脑上毫秒级跑单元测试。
 *
 * 而"分组"恰好是最容易算错的地方（差一、边界条件、最后一行）。
 * 把最容易错的部分变成可测的，这就是这个类存在的全部理由。
 * ═══════════════════════════════════════════════════════════════
 *
 * ══ 为什么按"行"分页而不是按"高度"切 ═══════════════════════════
 * 因为文本换行是【贪心】的：从左到右填，填不下就换行。
 * 这意味着只要从某个【行首】开始渲染，后面的换行位置和整章渲染时完全一致。
 * 所以测量一次就能得到所有页的边界，不需要逐页反复测量 ——
 * 一本几十万字的书，也只需要测量一章。
 * ═══════════════════════════════════════════════════════════════
 */
object Paginator {

    /**
     * 按固定的行数把行边界分组成页。
     *
     * @param lineStarts 每一行的起始字符下标，升序，来自 TextLayoutResult.getLineStart()
     * @param lineEnds   每一行的结束字符下标，升序，来自 TextLayoutResult.getLineEnd()
     *                   【重要】必须用默认的 visibleEnd = false，
     *                   这样行尾会【包含换行符】，相邻两行首尾相接，拼接时不会漏掉 \n。
     * @param linesPerPage 每页显示多少行，必须 >= 1
     * @param textLength 章内文本的总长度，用于兜住最后一页的结束位置
     * @return 页范围列表。文本为空时返回空列表
     */
    fun paginate(
        lineStarts: IntArray,
        lineEnds: IntArray,
        linesPerPage: Int,
        textLength: Int,
    ): List<PageRange> {
        // 每页行数来自"可用高度 ÷ 行高"，理论上算不出 0，
        // 但如果屏幕高度异常（比如 0），这里就是最后一道防线。
        // 与其让后面除零或者死循环，不如在这里明确报错。
        require(linesPerPage >= 1) { "linesPerPage 必须 >= 1，实际是 $linesPerPage" }

        if (textLength <= 0) return emptyList()

        // 极端兜底：测量失败（没有行信息）时，整章当一页。
        // 至少保证用户能读到内容，而不是看到一片空白。
        if (lineStarts.isEmpty() || lineEnds.isEmpty()) {
            return listOf(PageRange(0, textLength))
        }

        val pageCount = (lineStarts.size + linesPerPage - 1) / linesPerPage
        val pages = ArrayList<PageRange>(pageCount)

        var firstLine = 0
        while (firstLine < lineStarts.size) {
            // 本页最后一行。用 minOf 兜住"最后一页不满"的情况。
            val lastLine = minOf(firstLine + linesPerPage, lineStarts.size) - 1

            val start = lineStarts[firstLine]
            // coerceAtMost 是必要的：如果行边界来自一次不完整的测量，
            // end 有可能超过实际文本长度，substring 会直接抛越界异常。
            val end = lineEnds[lastLine].coerceAtMost(textLength)

            // end 小于等于 start 说明这一页没有任何内容（理论上不该发生），
            // 跳过它，避免产生无法渲染的空页。
            if (end > start) {
                pages.add(PageRange(start, end))
            }

            firstLine += linesPerPage
        }

        return pages
    }
}
