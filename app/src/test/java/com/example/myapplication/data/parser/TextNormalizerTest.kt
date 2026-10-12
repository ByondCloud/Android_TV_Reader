package com.example.myapplication.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `CRLF 被统一成 LF`() {
        assertEquals("第一行\n第二行\n", TextNormalizer.normalize("第一行\r\n第二行\r\n"))
    }

    @Test
    fun `单独的 CR 也被统一成 LF`() {
        assertEquals("第一行\n第二行\n", TextNormalizer.normalize("第一行\r第二行\r"))
    }

    @Test
    fun `开头的 BOM 字符被去掉`() {
        assertEquals("第一章 风起\n", TextNormalizer.normalize("\uFEFF第一章 风起\n"))
    }

    @Test
    fun `连续多个空行被压成一个空行`() {
        assertEquals("甲\n\n乙\n", TextNormalizer.normalize("甲\n\n\n\n\n乙\n"))
    }

    @Test
    fun `一个空行保持原样`() {
        assertEquals("甲\n\n乙\n", TextNormalizer.normalize("甲\n\n乙\n"))
    }

    @Test
    fun `段首的全角缩进被完整保留`() {
        // 这条很重要：全角空格(　)是中文小说的段落缩进，
        // 它和"脏数据"长得很像，但绝对不能清掉。
        val raw = "第一章 风起\n　　剑与灯。\n"
        assertEquals(raw, TextNormalizer.normalize(raw))
    }

    @Test
    fun `普通文本不被改动`() {
        val raw = "第一章 风起\n\n　　正文。\n"
        assertEquals(raw, TextNormalizer.normalize(raw))
    }
}