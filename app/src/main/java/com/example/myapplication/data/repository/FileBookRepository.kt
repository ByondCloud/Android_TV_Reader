package com.example.myapplication.data.repository

import com.example.myapplication.data.parser.ChapterSplitter
import com.example.myapplication.domain.model.Book
import com.example.myapplication.domain.model.BookFormat
import com.example.myapplication.domain.model.ParsedBook
import com.example.myapplication.domain.model.ParsedChapter
import com.example.myapplication.domain.model.ReadingProgress
import com.example.myapplication.domain.repository.BookRepository
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Properties

/**
 * 基于文件系统的书库实现。
 *
 * ══ 磁盘布局 ═══════════════════════════════════════════════════
 * <booksDir>/
 *   ├── 剑与灯/
 *   │   ├── meta.properties   ← 书名、作者、编码、阅读进度
 *   │   └── content.txt       ← 规范化后的全文（统一存 UTF-8）
 *   └── 另一本书/
 *       └── ...
 * ═══════════════════════════════════════════════════════════════
 *
 * ══ 为什么一本书一个目录，而不是一个大 JSON 文件 ═════════════════
 * 1. 【故障隔离】一本书的数据写坏了，不会毁掉整个书库。
 *    单个大文件更糟：写到一半断电，整库全丢。
 * 2. 【删除是原子的】删书 = 删目录，一行代码，不会留下孤儿数据。
 * 3. 【正文和元数据分离】列书库只需要读几十字节的 meta.properties，
 *    不用把几 MB 的正文读进内存。
 *
 * ══ 为什么用 Properties 而不是 JSON ═════════════════════════════
 * 因为 java.util.Properties 是 JDK 自带、Android 也有的，零依赖。
 * 它天然处理"键值对 + 转义"，比手写序列化安全得多。
 * （本项目刻意不引入 kotlinx-serialization，就是为了不让构建配置
 *   在这个超新的工具链上多一个出问题的点。）
 *
 * 【编码注意】必须用 Reader/Writer 版本的 load/store 并显式指定 UTF-8。
 * 如果用了 InputStream/OutputStream 版本，Properties 会按 ISO-8859-1 处理，
 * 中文书名会变成一串 \uXXXX 甚至乱码。
 *
 * ══ v1 的取舍：章节不落盘 ══════════════════════════════════════
 * [loadChapters] 是【现场重新切分】的。理由：
 * 章节是从正文推导出来的数据。如果磁盘上再存一份，
 * 就必然存在"正文改了、章节没改"的不一致状态 —— 这是 bug 的温床。
 * 切分一本 5MB 的书只要几百毫秒，代价可以接受。
 * 将来嫌慢可以加【内存】缓存，但不要在磁盘上存第二份真相。
 * ═══════════════════════════════════════════════════════════════
 */
class FileBookRepository(
    /**
     * 书库根目录。
     * App 里传 context.filesDir（应用私有内部目录）——
     * 好处是用户卸载 App 时，系统会连同这些书一起清掉，不留垃圾。
     */
    private val booksDir: File,
) : BookRepository {

    // ══ 读 ══════════════════════════════════════════════════

    override fun listBooks(): List<Book> {
        // 同样要防 listFiles() 返回 null 这个坑
        val children = booksDir.listFiles() ?: return emptyList()
        return children
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { readMeta(it) }        // 元数据坏掉的目录被静默跳过，不让整个书架崩掉
            // 排序：读过的按最后阅读时间，没读过的按导入时间，都是倒序。
            // takeIf 让"从未读过"(updatedAt == 0) 回退到 importedAt。
            .sortedByDescending { book ->
                book.progress.updatedAt.takeIf { it > 0 } ?: book.importedAt
            }
            .toList()
    }

    override fun findBook(bookId: String): Book? = readMeta(File(booksDir, bookId))

    /**
     * 按原始文件名查重。
     *
     * 书库规模是"几百本"量级，listBooks() 只读几十字节一个的 meta.properties，
     * 直接线性扫一遍完全够用，不值得为它建索引。
     * （真到了几千本，再在这里加一个文件名到 id 的内存缓存即可，
     *   界面上因为调的是这个接口方法，不需要跟着改。）
     */
    override fun findBySourceName(sourceFileName: String): Book? =
        listBooks().firstOrNull { it.sourceFileName == sourceFileName }

    override fun loadText(bookId: String): String {
        val f = File(File(booksDir, bookId), CONTENT_FILE)
        if (!f.isFile) throw NoSuchElementException("书籍正文文件不存在：$bookId")
        return f.readText(Charsets.UTF_8)
    }

    override fun loadChapters(bookId: String): List<ParsedChapter> =
        ChapterSplitter.split(loadText(bookId))

    // ══ 写 ══════════════════════════════════════════════════

    override fun importBook(parsed: ParsedBook, sourceFileName: String): Book {
        booksDir.mkdirs()

        val id = makeUniqueBookId(sourceFileName)
        val dir = File(booksDir, id)
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("无法创建书籍目录：$dir")
        }

        // ① 正文落盘。注意存的是【规范化之后】的文本：
        //    换行已经统一成 \n，BOM 也去掉了。
        //    所以以后读的时候不用再猜编码、不用再规范化一遍 ——
        //    规范化只做一次，就在导入时。
        File(dir, CONTENT_FILE).writeText(parsed.text, Charsets.UTF_8)

        // ② 元数据落盘
        val book = Book(
            id = id,
            title = parsed.title,
            author = parsed.author,
            format = parsed.format,
            charsetName = parsed.charsetName,
            sourceFileName = sourceFileName,
            importedAt = System.currentTimeMillis(),
            progress = ReadingProgress.EMPTY,
        )
        writeMeta(dir, book)
        return book
    }

    override fun saveProgress(bookId: String, chapterIndex: Int, charOffset: Int) {
        val dir = File(booksDir, bookId)
        // 【关键】先把已有的元数据读出来，用 copy() 只改进度字段。
        // 千万不能 new 一个只带进度的 Book 然后覆盖 —— 那样书名、
        // 作者、编码全会被抹掉。（下面有个测试专门守这条。）
        val existing = readMeta(dir) ?: throw NoSuchElementException("书籍不存在：$bookId")
        writeMeta(
            dir = dir,
            book = existing.copy(
                progress = ReadingProgress(
                    chapterIndex = chapterIndex,
                    charOffset = charOffset,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
    }

    override fun renameBook(bookId: String, newTitle: String) {
        val dir = File(booksDir, bookId)
        // 和 saveProgress 一样：先读出来再 copy 改一个字段。
        // 直接 new 一个只带书名的 Book 覆盖文件，会把作者、编码、进度全抹掉。
        val existing = readMeta(dir) ?: throw NoSuchElementException("书籍不存在：$bookId")
        writeMeta(dir, existing.copy(title = newTitle))
    }

    override fun deleteBook(bookId: String) {
        // 注意：这里传进来的 bookId 必须是我们自己生成的目录名。
        // 如果它来自用户输入且含有 "../"，deleteRecursively 就可能删到书库外面去
        // —— 这叫【路径穿越】漏洞。我们用 makeUniqueBookId 做过字符白名单，
        // 所以 id 里不可能出现 / 和 ..，是安全的。
        File(booksDir, bookId).deleteRecursively()
    }

    // ══ 内部实现 ════════════════════════════════════════════

    private fun readMeta(dir: File): Book? {
        val f = File(dir, META_FILE)
        if (!f.isFile) return null
        return try {
            val p = Properties()
            // 用 Reader 版本 + 显式 UTF-8，中文才不会坏
            f.inputStream().use { ins ->
                InputStreamReader(ins, Charsets.UTF_8).use { reader -> p.load(reader) }
            }

            Book(
                id = p.getProperty(KEY_ID) ?: dir.name,
                title = p.getProperty(KEY_TITLE) ?: dir.name,
                // 空字符串转成 null：Properties 没法存 null，我们存的是 ""
                author = p.getProperty(KEY_AUTHOR)?.takeIf { it.isNotBlank() },
                format = BookFormat.entries.firstOrNull { it.id == p.getProperty(KEY_FORMAT) }
                    ?: BookFormat.UNKNOWN,
                charsetName = p.getProperty(KEY_CHARSET).orEmpty(),
                sourceFileName = p.getProperty(KEY_SOURCE_NAME).orEmpty(),
                importedAt = p.getProperty(KEY_IMPORTED_AT)?.toLongOrNull() ?: 0L,
                progress = ReadingProgress(
                    chapterIndex = p.getProperty(KEY_CHAPTER)?.toIntOrNull() ?: 0,
                    charOffset = p.getProperty(KEY_OFFSET)?.toIntOrNull() ?: 0,
                    updatedAt = p.getProperty(KEY_PROGRESS_AT)?.toLongOrNull() ?: 0L,
                ),
            )
        } catch (e: Exception) {
            // 单本书的元数据坏了，不应该让整个书架打不开。
            // 返回 null，由 listBooks() 的 mapNotNull 跳过它。
            // 【但注意】这里是 catch 了所有异常并静默吞掉 ——
            // 对用户来说"少了一本书"比"App 崩溃"好，但如果将来要排查问题，
            // 这里应该加一行日志。
            null
        }
    }

    private fun writeMeta(dir: File, book: Book) {
        val p = Properties()
        p[KEY_ID] = book.id
        p[KEY_TITLE] = book.title
        p[KEY_AUTHOR] = book.author ?: ""          // Properties 存不了 null
        p[KEY_FORMAT] = book.format.id
        p[KEY_CHARSET] = book.charsetName
        p[KEY_SOURCE_NAME] = book.sourceFileName
        p[KEY_IMPORTED_AT] = book.importedAt.toString()
        p[KEY_CHAPTER] = book.progress.chapterIndex.toString()
        p[KEY_OFFSET] = book.progress.charOffset.toString()
        p[KEY_PROGRESS_AT] = book.progress.updatedAt.toString()

        dir.mkdirs()
        File(dir, META_FILE).outputStream().use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                p.store(writer, "小说阅读 - 书籍元数据")
            }
        }
    }

    /**
     * 生成一个既是合法目录名、又不会和已有书冲突的 id。
     *
     * 用文件名而不是 UUID，是为了能直接在文件管理器里看懂哪本书是哪个 ——
     * 调试的时候省很多事。代价是要处理重名和非法字符。
     *
     * 合法字符白名单还顺带挡住了路径穿越攻击（见 deleteBook 的注释）。
     */
    private fun makeUniqueBookId(sourceFileName: String): String {
        val base = sourceFileName.substringBeforeLast('.').ifBlank { sourceFileName }
        val safe = base
            // isLetterOrDigit() 对中文返回 true，所以中文书名会被完整保留
            .map { ch -> if (ch.isLetterOrDigit() || ch in SAFE_EXTRA_CHARS) ch else '_' }
            .joinToString("")
            .trim('_', ' ', '.')       // 去掉首尾可能残留的分隔符
            .ifBlank { "book" }        // 全是非法字符时的兜底
            .take(60)                  // 目录名别太长，给后面的 "-2" 留余地

        if (!File(booksDir, safe).exists()) return safe

        // 重名：依次尝试 "名字-2"、"名字-3"……
        var n = 2
        while (File(booksDir, "$safe-$n").exists()) n++
        return "$safe-$n"
    }

    private companion object {
        const val META_FILE = "meta.properties"
        const val CONTENT_FILE = "content.txt"

        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_FORMAT = "format"
        const val KEY_CHARSET = "charsetName"
        const val KEY_SOURCE_NAME = "sourceFileName"
        const val KEY_IMPORTED_AT = "importedAt"
        const val KEY_CHAPTER = "progress.chapterIndex"
        const val KEY_OFFSET = "progress.charOffset"
        const val KEY_PROGRESS_AT = "progress.updatedAt"

        /** 除字母数字外额外允许保留的字符，让目录名可读一点 */
        val SAFE_EXTRA_CHARS = setOf('_', '-', '(', ')', '（', '）', '【', '】', '·', ' ')
    }
}
