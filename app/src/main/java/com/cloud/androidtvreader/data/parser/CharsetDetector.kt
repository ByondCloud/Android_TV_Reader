package com.cloud.androidtvreader.data.parser

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 字符编码探测。
 *
 * 【为什么中文 txt 必须做这一步】
 * txt 文件**不记录自己是什么编码**。同一本小说从不同网站下载，
 * 可能是 UTF-8、GBK、GB18030、UTF-16 中的任意一种。
 * 用错编码的后果就是满屏乱码（"锟斤拷"那种）。
 * 这是中文阅读类 App 最经典的翻车点。
 *
 * 【探测策略：从"铁证"到"试探"】
 *   1. 有 BOM → 直接采信。BOM 是文件开头的几个特殊字节，是编码的"身份证"。
 *   2. 没 BOM → 按**最严格**的方式试解 UTF-8，解不开就说明不是。
 *   3. 还不行 → 按 GB18030 处理。它是 GBK/GB2312 的超集。
 */
object CharsetDetector {

    private val UTF8_BOM    = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** GB18030 在 Android / 标准 JDK 里都有内置实现，不需要额外依赖 */
    val GB18030: Charset = Charset.forName("GB18030")

    /** 探测结果 */
    data class Result(
        val charset: Charset,
        /** BOM 占用的字节数，解码时要跳过。没有 BOM 就是 0 */
        val bomLength: Int,
        /** 判定依据。写日志、写测试断言都用得上，排查乱码时非常有用 */
        val reason: String,
    )

    fun detect(bytes: ByteArray): Result {
        // ── 第 1 步：BOM 是铁证 ──────────────────────────────
        if (bytes.startsWith(UTF8_BOM))    return Result(Charsets.UTF_8,    3, "UTF-8 BOM")
        if (bytes.startsWith(UTF16LE_BOM)) return Result(Charsets.UTF_16LE, 2, "UTF-16LE BOM")
        if (bytes.startsWith(UTF16BE_BOM)) return Result(Charsets.UTF_16BE, 2, "UTF-16BE BOM")

        // ── 第 2 步：严格试解 UTF-8 ─────────────────────────
        if (isValidUtf8(bytes)) return Result(Charsets.UTF_8, 0, "无 BOM，通过严格 UTF-8 校验")

        // ── 第 3 步：兜底 GB18030 ───────────────────────────
        return Result(GB18030, 0, "不是合法 UTF-8，按 GB18030 处理")
    }

    /**
     * 严格校验是不是合法 UTF-8。
     *
     * 【这是本节最关键的一个坑，务必看懂】
     *
     * 千万不要用 `String(bytes, Charsets.UTF_8)` 来做这个判断！
     * 那个构造函数遇到非法字节**不会报错**，而是悄悄替换成 '\uFFFD'（就是那个 �）。
     * 结果是"任何字节数组"都能"成功"解码 —— 你永远测不出它不是 UTF-8。
     *
     * 必须用 CharsetDecoder，并且把错误动作设成 REPORT（报告错误）。
     * 这样遇到非法字节会抛异常，我们才能据此判断"它不是 UTF-8"。
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /** 小的字节数组前缀比较。不用库，因为这几行本身就是最好的注释 */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}