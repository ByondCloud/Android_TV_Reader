package com.cloud.androidtvreader.data.parser

import com.cloud.androidtvreader.domain.model.BookFormat
import com.cloud.androidtvreader.domain.model.ParsedBook
import com.cloud.androidtvreader.domain.parser.BookParseException
import com.cloud.androidtvreader.domain.parser.BookParser

/**
 * 纯文本（txt）解析器。
 *
 * 它自己不干具体的活儿，只是把前面三步串起来：
 *   探测编码 → 规范化 → 切章节 → 抽元信息
 *
 * 这正是"每个类只做一件事"的好处：这里的代码几乎不需要逻辑，
 * 因为每一步的复杂度都被关在自己的类里了。
 */
class TxtBookParser : BookParser {

    override val id: String = "txt"
    override val displayName: String = "纯文本"
    override val extensions: Set<String> = setOf("txt")

    override fun canParse(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in extensions

    override fun parse(fileName: String, bytes: ByteArray): ParsedBook {
        // ── 前置校验，错误消息写成人话 ──────────────────────
        if (bytes.isEmpty()) throw BookParseException("文件是空的")
        if (bytes.size > MAX_BYTES) {
            throw BookParseException("文件太大（约 ${bytes.size / 1024 / 1024} MB），目前最多支持 ${MAX_BYTES / 1024 / 1024} MB")
        }

        // ── 第一步：探测编码 ────────────────────────────────
        val detected = CharsetDetector.detect(bytes)

        // ── 第二步：解码（跳过 BOM）─────────────────────────
        val raw = try {
            String(bytes, detected.bomLength, bytes.size - detected.bomLength, detected.charset)
        } catch (e: Exception) {
            throw BookParseException("按 ${detected.charset.name()} 解码失败（${detected.reason}）", e)
        }

        // ── 第三步：规范化 ──────────────────────────────────
        val text = TextNormalizer.normalize(raw)
        if (text.isBlank()) throw BookParseException("文件里没有可读内容")

        // ── 第四步：切章节 ──────────────────────────────────
        val chapters = ChapterSplitter.split(text)

        // ── 第五步：抽元信息 ────────────────────────────────
        val meta = BookMetaExtractor.extract(fileName, text)

        return ParsedBook(
            title = meta.title,
            author = meta.author,
            format = BookFormat.TXT,
            charsetName = detected.charset.name(),
            text = text,
            chapters = chapters,
        )
    }

    private companion object {
        /** 20 MB。超过这个体积，整本放内存就不合适了（见 ParsedBook 的升级点注释） */
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}