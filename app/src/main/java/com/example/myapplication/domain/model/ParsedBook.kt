package com.example.myapplication.domain.model

/**
 * 解析出来的一个章节。
 *
 * 【关键设计】这里存的是**字符下标**，不是页码。
 *
 * 为什么？因为页码会随字号、行距、屏幕尺寸变化 —— 同一本书在 65 寸电视和
 * 手机上，"第 12 页"指向的位置完全不同。而字符下标是绝对坐标，永远不变。
 *
 * 翻页模式和滚动模式共用这同一个"进度坐标"，切换阅读模式时才能不丢位置。
 * 这是模块 5、6 能顺利做出来的前提。
 */
data class ParsedChapter(
    /** 章节序号，从 0 开始 */
    val index: Int,
    /** 章节标题，取自标题行本身，例如 "第一章 风起" */
    val title: String,
    /** 正文起始下标（含），指向标题行的【下一行行首】 */
    val startOffset: Int,
    /** 正文结束下标（不含） */
    val endOffset: Int,
) {
    /** 正文字数，做进度百分比时要用 */
    val length: Int get() = endOffset - startOffset
}

/**
 * 一个书籍文件的完整解析结果。
 *
 * 【v1 的取舍】整本书的正文都放在内存里（[text]）。
 * 一本 5MB 的 txt 解码成 String 大约占 10MB 内存 ——
 * 对 TV 盒子（通常 1~2GB RAM）可以接受，而且让分页逻辑变得非常简单。
 *
 * 【后续升级点】如果将来要支持 50MB 以上的巨型文本，改成：
 * 只存章节的**字节偏移量**，正文按需从文件读取、用完释放。
 * 接口不用变（[ParsedChapter] 已经用下标而不是内容来描述章节），
 * 只需要把 [text] 换成一个按需读取的 Reader。
 */
data class ParsedBook(
    val title: String,
    val author: String?,
    val format: BookFormat,
    /** 实际探测到的编码名，如 "UTF-8" / "GB18030"。排查乱码问题时必看 */
    val charsetName: String,
    /** 规范化后的全文：换行统一成 \n，BOM 已去掉 */
    val text: String,
    val chapters: List<ParsedChapter>,
)