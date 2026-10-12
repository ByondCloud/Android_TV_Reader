package com.example.myapplication.domain.usecase

import com.example.myapplication.domain.model.Book
import com.example.myapplication.domain.parser.BookParseException
import com.example.myapplication.domain.parser.BookParserRegistry
import com.example.myapplication.domain.repository.BookRepository
import com.example.myapplication.domain.source.SourceFile

/**
 * 一次导入的结果。
 *
 * ══ 为什么用 sealed interface 表示"成功或失败"，而不是抛异常 ══════
 * 因为导入是【批量】操作：用户可能一次选 10 本书，其中 2 本有问题。
 *
 * 如果失败靠抛异常，调用方就得写 try/catch 包住循环，
 * 一本失败整个循环就断了 —— 用户得反复重试才知道哪几本坏。
 *
 * 把失败变成一个【正常的返回值】，调用方就能：
 *   - 把成功的书加进书架
 *   - 把失败的列出来告诉用户"这 2 本没导入成功，原因分别是……"
 *
 * 记住这条经验：**"可以预期的失败"用返回值，"不该发生的意外"用异常。**
 * 文件格式不对、文件是空的 —— 这些完全可预期，属于前者。
 * ═══════════════════════════════════════════════════════════════
 */
sealed interface ImportResult {
    data class Success(val book: Book) : ImportResult
    data class Failure(val fileName: String, val reason: String) : ImportResult
}

/**
 * 导入用例：把"一堆字节"变成"书架上的一本书"。
 *
 * ══ 它存在的意义 ═══════════════════════════════════════════════
 * 这个流程有 4 步：
 *   ① 从 SourceFile 读出字节
 *   ② 找合适的解析器（可能找不到 → 不支持的格式）
 *   ③ 解析（可能失败 → 文件损坏/为空/太大）
 *   ④ 存进书库（可能失败 → 磁盘满/权限问题）
 *
 * 如果把这 4 步写在界面代码里，那么"导入"这个功能的逻辑就和按钮、
 * 焦点、进度条搅在一起了，既不好测也不好复用。
 *
 * 抽成用例类之后：
 *   - 它不依赖任何 Android 类（只用 File / ByteArray / 领域接口）→ 能秒级单测
 *   - 界面只需要调 `importer.importAll(files)` 然后展示结果
 *   - 将来要做"从网络下载后导入"，也是调同一个方法
 * ═══════════════════════════════════════════════════════════════
 *
 * 【术语】这层通常叫 "use case" 或 "interactor"，是"领域逻辑"的一部分。
 * 它比单个实体（Book）大，比整个应用小 —— 正好对应"一个用户能说出来的操作"：
 * "导入这些书"。命名成"用户会说的话"是划分用例的好办法。
 */
class BookImporter(
    private val registry: BookParserRegistry,
    private val repository: BookRepository,
) {

    /**
     * 导入单个文件。
     *
     * 注意：这个方法【不抛异常】给调用方（除了极端情况），
     * 所有可预期的失败都变成 [ImportResult.Failure]。
     */
    fun import(source: SourceFile): ImportResult {
        val name = source.displayName

        // ① 找解析器。找不到就是"格式不支持"，这不是错误，是正常情况
        val parser = registry.parserFor(name)
            ?: return ImportResult.Failure(name, "暂不支持这种格式")

        return try {
            // ② 读字节。这里读整个文件进内存 ——
            //    因为 BookParser 的接口就是这么设计的（见它的注释）。
            //    TxtBookParser 里对超过 20MB 的文件会拒绝，所以不会撑爆内存。
            val bytes = source.file.readBytes()

            // ③ 解析
            val parsed = parser.parse(name, bytes)

            // ④ 入库
            ImportResult.Success(repository.importBook(parsed, name))
        } catch (e: BookParseException) {
            // 解析器主动报告的问题（文件为空、太大、编码不对……）
            // 它的 message 就是我们写给人看的中文，可以直接展示
            ImportResult.Failure(name, e.message ?: "解析失败")
        } catch (e: Exception) {
            // 兜底：磁盘满、文件被删除、权限不足……
            // 【为什么这里要 catch 所有异常】
            // 因为这是批量导入的一环，任何一本书出问题都不该让整个
            // 导入流程崩掉。代价是可能吞掉真正的 bug ——
            // 所以失败原因里要带上异常类名，方便排查。
            ImportResult.Failure(name, "导入失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    /**
     * 批量导入。
     *
     * 【为什么不用 map { import(it) } 一行搞定】
     * 其实可以。这里写成显式循环是为了将来好加东西：
     * 比如进度回调（让界面显示"正在导入 3/10"）、
     * 或者遇到重名书时询问用户。留个明显的扩展点。
     */
    fun importAll(sources: List<SourceFile>): List<ImportResult> {
        val results = ArrayList<ImportResult>(sources.size)
        for (source in sources) {
            results.add(import(source))
        }
        return results
    }
}
