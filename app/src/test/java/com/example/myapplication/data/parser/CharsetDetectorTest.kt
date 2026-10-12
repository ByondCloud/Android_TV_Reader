package com.example.myapplication.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

/**
 * CharsetDetector 的单元测试。
 *
 * 【为什么不需要任何测试文件】
 * 因为 BookParser 的入参是 ByteArray ——
 * 我们在代码里用 Charset 现场把字符串编成目标编码的字节。
 * 这比往 src/test/resources 里放一堆二进制文件干净得多，也更容易维护。
 */
class CharsetDetectorTest {

    private val gb18030 = Charset.forName("GB18030")

    /** 一段典型的中文小说文本，含全角空格和标点 */
    private val sample = "第一章 风起\n　　剑与灯，龙与雪。\n"

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    // ── 有 BOM：走"铁证"分支 ────────────────────────────────

    @Test
    fun `UTF8 带 BOM 被识别`() {
        val r = CharsetDetector.detect(utf8Bom + sample.toByteArray(Charsets.UTF_8))
        assertEquals(Charsets.UTF_8, r.charset)
        assertEquals(3, r.bomLength)
    }

    @Test
    fun `UTF16LE 带 BOM 被识别`() {
        val r = CharsetDetector.detect(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        )
        assertEquals(Charsets.UTF_16LE, r.charset)
        assertEquals(2, r.bomLength)
    }

    @Test
    fun `UTF16BE 带 BOM 被识别`() {
        val r = CharsetDetector.detect(
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)
        )
        assertEquals(Charsets.UTF_16BE, r.charset)
        assertEquals(2, r.bomLength)
    }

    // ── 无 BOM：走"试探"分支 ────────────────────────────────

    @Test
    fun `无 BOM 的 UTF8 被识别为 UTF8`() {
        val r = CharsetDetector.detect(sample.toByteArray(Charsets.UTF_8))
        assertEquals(Charsets.UTF_8, r.charset)
        assertEquals(0, r.bomLength)
    }

    @Test
    fun `纯英文被识别为 UTF8`() {
        val bytes = "Chapter 1\nHello world.\n".toByteArray(Charsets.US_ASCII)
        assertEquals(Charsets.UTF_8, CharsetDetector.detect(bytes).charset)
    }

    @Test
    fun `无 BOM 的 GBK 被识别为 GB18030`() {
        val r = CharsetDetector.detect(sample.toByteArray(gb18030))
        assertEquals(CharsetDetector.GB18030, r.charset)
    }

    @Test
    fun `空文件不崩溃`() {
        assertEquals(Charsets.UTF_8, CharsetDetector.detect(ByteArray(0)).charset)
    }

    // ── 往返测试：证明"按探测结果解码"真的能还原原文 ──────────
    // 这是最有说服力的两个测试：不是只看"猜对编码没有"，
    // 而是验证端到端结果【等于原文】。

    @Test
    fun `GBK 文件按探测结果解码可还原原文`() {
        val bytes = sample.toByteArray(gb18030)
        val r = CharsetDetector.detect(bytes)
        val decoded = String(bytes, r.bomLength, bytes.size - r.bomLength, r.charset)
        assertEquals(sample, decoded)
    }

    @Test
    fun `UTF8 带 BOM 的文件解码后可还原原文`() {
        val bytes = utf8Bom + sample.toByteArray(Charsets.UTF_8)
        val r = CharsetDetector.detect(bytes)
        val decoded = String(bytes, r.bomLength, bytes.size - r.bomLength, r.charset)
        assertEquals(sample, decoded)
    }
}