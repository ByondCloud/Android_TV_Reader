package com.cloud.androidtvreader.domain.parser

import com.cloud.androidtvreader.domain.model.ParsedBook

/**
 * 书籍解析器。**这是整个 App 最重要的扩展点。**
 *
 * 加一种新格式（epub / pdf / mobi）只需要：
 *   1. 新建一个类实现这个接口
 *   2. 在 BookParserRegistry 里注册
 * 阅读器、书架、书库存储全都不用改。
 *
 * ── 为什么入参是 ByteArray，而不是 File 或 Uri？────────────────
 * 这是刻意设计，有两条理由：
 *
 *  1. **可测试**：单元测试可以直接在代码里构造字节数组，
 *     不需要往 src/test/resources 里塞二进制文件。
 *     2.2 的 GBK 测试就是这么写的 —— 用 Charset 现场把字符串编成 GBK 字节。
 *
 *  2. **不依赖 Android**：这个接口只用纯 Kotlin 类型，
 *     所以单元测试跑在电脑 JVM 上，不用启动模拟器。
 *
 * ── 为什么不是 suspend？────────────────────────────────────
 * 解析是纯计算，它不该关心自己跑在哪个线程上。
 * 线程由调用方决定（在 ViewModel 里用 withContext(Dispatchers.IO) 包起来）。
 * **让纯函数保持纯** —— 这样它才好测，也好复用。
 */
interface BookParser {

    /** 唯一标识，例如 "txt" */
    val id: String

    /** 给用户看的名字，例如 "纯文本" */
    val displayName: String

    /** 支持的扩展名（小写，不带点），例如 setOf("txt") */
    val extensions: Set<String>

    /** 能不能解析这个文件？注册表靠它分发 */
    fun canParse(fileName: String): Boolean

    /**
     * 解析。
     * @param fileName 只用来推断书名、判断格式，不用于读文件
     * @param bytes    文件的原始字节
     * @throws BookParseException 失败时抛出，消息是可直接显示给用户的中文
     */
    fun parse(fileName: String, bytes: ByteArray): ParsedBook
}

/** 解析失败。消息写成人话，UI 可以直接弹出来给用户看 */
class BookParseException(message: String, cause: Throwable? = null) : Exception(message, cause)