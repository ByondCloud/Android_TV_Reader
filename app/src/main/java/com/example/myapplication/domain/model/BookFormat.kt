package com.example.myapplication.domain.model

/**
 * 支持的书籍格式。
 *
 * 【怎么加一种新格式】
 *   1. 这里加一个枚举值
 *   2. 在 BookParserRegistry 里注册对应的解析器
 * 除这两处，其他代码都不用改。
 */
enum class BookFormat(val id: String, val displayName: String) {
    TXT("txt", "纯文本"),
    EPUB("epub", "EPUB"),   // 预留：模块 8 实现
    PDF("pdf", "PDF"),      // 预留：模块 8 实现
    UNKNOWN("unknown", "未知格式"),
}