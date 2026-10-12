package com.example.myapplication.data

import android.content.Context
import com.example.myapplication.data.parser.BookParsers
import com.example.myapplication.data.repository.FileBookRepository
import com.example.myapplication.data.source.LocalFolderSource
import com.example.myapplication.domain.parser.BookParserRegistry
import com.example.myapplication.domain.repository.BookRepository
import com.example.myapplication.domain.source.BookSource
import com.example.myapplication.domain.usecase.BookImporter
import java.io.File

/**
 * 【组合根 Composition Root】+【手写依赖注入】
 *
 * ══ 这个类是干什么的 ══════════════════════════════════════════
 * 整个 App 里，只有这一个地方知道"到底用哪个实现"：
 *   - 书库用 FileBookRepository（而不是将来可能换的 Room 版）
 *   - 书籍来源有 LocalFolderSource（以后还会加 SafSource / UsbSource）
 *   - 解析器注册表来自 BookParsers
 *
 * 别的地方（界面、ViewModel）只接收【接口】：
 *   BookRepository、BookSource、BookImporter……
 *
 * ══ 为什么不用 Hilt / Koin 这些依赖注入框架 ═════════════════════
 * 对一个练手项目来说，手写 DI 更值得学：
 *   1. 你能看清"对象是在哪里、什么时候被创建的"
 *   2. 不引入注解处理器（KSP），少一个构建环节出问题的可能
 *   3. 框架能做的事，在 50 行的 AppContainer 里都做到了
 *
 * 等你真的遇到"手写太累"的规模（几十个依赖），再上框架也不迟。
 * **不要为了用一个工具而去制造需要它的复杂度。**
 * ═══════════════════════════════════════════════════════════════
 *
 * 【为什么构造时立刻取 applicationContext】
 * 因为容器可能被 Activity 或别的短命对象创建。
 * 只要手里握着 Activity 的 Context，那个 Activity 就永远无法被回收 → 内存泄漏。
 * 取 applicationContext 是安全的长生命周期 Context。
 * 这是 Android 里最常见的内存泄漏来源之一，务必养成习惯。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // ══ 解析层 ══════════════════════════════════════════════

    /** 解析器注册表（目前只有 TxtBookParser，模块 8 会加 epub/pdf） */
    val parserRegistry: BookParserRegistry = BookParsers.registry

    // ══ 存储层 ══════════════════════════════════════════════

    /**
     * 书库根目录：应用私有的【内部】目录 /data/data/<包名>/files/books
     *
     * 为什么用内部目录而不是外部目录：
     *   - 用户看不见、也改不坏（外部目录用文件管理器能删）
     *   - 卸载 App 时系统会自动清掉，不留垃圾
     *   - 完全不需要任何存储权限
     * 代价是用户没法直接用数据线往里拷书 ——
     * 所以我们另外准备了一个"投递目录"（见下）。
     */
    private val libraryDir: File = File(appContext.filesDir, "books")

    val bookRepository: BookRepository = FileBookRepository(libraryDir)

    // ══ 用例层 ══════════════════════════════════════════════

    val bookImporter: BookImporter = BookImporter(parserRegistry, bookRepository)

    // ══ 导入来源 ════════════════════════════════════════════

    /**
     * 用户往这里放书：应用私有的【外部】目录
     *   /sdcard/Android/data/com.example.myapplication/files/books
     *
     * 它不需要任何存储权限，用 adb push 或者 USB 传输就能往里放文件。
     * 对 Android TV 来说这是最实际的加书方式 —— 因为电视上
     * 根本没有文件选择器可用（见 BookSource 的注释）。
     *
     * 【注意 getExternalFilesDir 可能返回 null】
     * 外部存储没挂载时（比如 SD 卡被拔出、或者 USB 存储模式占用中）
     * 它会返回 null。必须兜底，否则构造容器时就崩溃。
     */
    val dropDir: File =
        appContext.getExternalFilesDir("books")
            ?: File(appContext.filesDir, "drop")     // 兜底：退回内部目录

    /** 所有可用的导入来源。以后加 SAF、U 盘，就在这个列表里加一项 */
    val bookSources: List<BookSource> = listOf(
        LocalFolderSource(
            rootDir = dropDir,
            supportedExtensions = parserRegistry.supportedExtensions,
        )
    )

    /**
     * 过滤出【当前设备上真正可用】的来源。
     * 界面用这个而不是 bookSources —— 不可用的入口不该让用户看到。
     */
    fun availableSources(): List<BookSource> = bookSources.filter { it.isAvailable() }
}
