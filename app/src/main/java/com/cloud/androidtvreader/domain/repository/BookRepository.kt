package com.cloud.androidtvreader.domain.repository

import com.cloud.androidtvreader.domain.model.Book
import com.cloud.androidtvreader.domain.model.ParsedBook
import com.cloud.androidtvreader.domain.model.ParsedChapter

/**
 * 书库仓库：负责"书"的持久化与读取。
 *
 * ══ 为什么先定义接口，而不是直接写实现 ══════════════════════════
 * 因为"书存在哪里"这件事以后一定会变：
 *   v1（现在）   ：一本书一个目录，元数据存 properties 文件
 *   模块 8 以后  ：换成 Room 数据库（正文仍存文件，元数据入库）
 *
 * 只要界面依赖的是这个接口，换实现时界面一行都不用改。
 * 这是接口存在的【唯一】理由 —— 不是为了"看起来专业"。
 * ═══════════════════════════════════════════════════════════════
 *
 * ══ 为什么这些方法是【阻塞】的，而不是 suspend ══════════════════
 * 和 [com.cloud.androidtvreader.domain.parser.BookParser] 保持同一个原则：
 * **让纯函数保持纯，线程由调用方决定。**
 *
 * 仓库只负责读写文件，它不关心自己在哪个线程上跑。
 * 调用方（模块 4 的 ViewModel）用 withContext(Dispatchers.IO) 把它包起来。
 *
 * 这样做最直接的好处是可测：单元测试直接调这些方法就行，
 * 不需要引入 kotlinx-coroutines-test，也不需要 runBlocking。
 * 依赖越少，测试越稳。
 * ═══════════════════════════════════════════════════════════════
 */
interface BookRepository {

    /** 书架页用：列出书库里所有的书。按"最近阅读"倒序 */
    fun listBooks(): List<Book>

    /** 按 id 找一本书；不存在返回 null */
    fun findBook(bookId: String): Book?

    /**
     * 按【原始文件名】查一本书；不存在返回 null。
     * 导入时用来判断重复（依据见 [Book.sourceFileName] 的注释）。
     *
     * 【为什么单独开一个方法，而不是在界面层用 listBooks() 自己筛】
     * 因为"怎么判断重复"是业务规则，不是界面细节。
     * 将来如果改成用内容哈希查重，只需要改这里的实现，
     * 导入界面一行都不用动。
     */
    fun findBySourceName(sourceFileName: String): Book?

    /**
     * 导入一本书：把解析结果落盘，并在书库里建立记录。
     *
     * @param parsed 解析结果（来自 BookParser）
     * @param sourceFileName 用户导入时的原始文件名，用于显示和生成 id
     * @return 新建的书（带 id，可以直接跳去阅读页）
     */
    fun importBook(parsed: ParsedBook, sourceFileName: String): Book

    /** 读取一本书的全文（规范化后的 UTF-8 文本） */
    fun loadText(bookId: String): String

    /**
     * 读取一本书的章节列表。
     *
     * 注意：章节是【现场重新切分】出来的，不从磁盘读。
     * 为什么？见 FileBookRepository 里的详细说明 ——
     * 简单说就是"不要存储能推导出来的数据"，否则两份数据迟早不一致。
     */
    fun loadChapters(bookId: String): List<ParsedChapter>

    /** 保存阅读进度 */
    fun saveProgress(bookId: String, chapterIndex: Int, charOffset: Int)

    /**
     * 修改书名。
     *
     * 【为什么只改元数据，不动正文和目录名】
     * 书籍目录名（也就是 bookId）是内部标识，改它要连带迁移文件、
     * 更新所有引用，风险大收益小。而书名纯粹是给用户看的显示名称，
     * 改 meta.properties 里的一个字段就够了。
     *
     * 这也解释了一个现象：你在文件管理器里看到的中文目录名，
     * 和你后来改的书名可能不一样 —— 那是正常的。
     */
    fun renameBook(bookId: String, newTitle: String)

    /** 删除一本书（连同它的文件目录） */
    fun deleteBook(bookId: String)
}
