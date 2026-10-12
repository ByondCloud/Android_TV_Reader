package com.example.myapplication.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.AppContainer
import com.example.myapplication.domain.model.Book
import com.example.myapplication.domain.source.SourceFile
import com.example.myapplication.domain.usecase.ImportResult
import com.example.myapplication.ui.components.TvButton
import com.example.myapplication.ui.importer.ImportScreen
import com.example.myapplication.ui.shelf.BookShelfScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用里的三个页面。
 *
 * 【为什么用 sealed interface 而不是 enum】
 * 因为 Reader 需要带数据（书名、书 id），而 enum 的每个常量不能带不同的参数集。
 * sealed interface 既能表达"就这几种"，又能让每种带自己的数据。
 *
 * 【为什么不用 Navigation Compose 库】
 * 现在只有 3 个页面、层级只有一层。手写一个 when 分支完全够用，
 * 而且你能一眼看清"页面之间怎么跳"。
 * 等页面多到需要深链接、返回栈、转场动画时，再上导航库。
 * ═══════════════════════════════════════════════════════════════
 */
private sealed interface Screen {
    /** 书架（首页） */
    data object Shelf : Screen

    /** 导入页 */
    data object Import : Screen

    /** 阅读页。模块 5 会把 ReaderPlaceholder 换成真正的阅读器 */
    data class Reader(val bookId: String, val title: String) : Screen
}

/**
 * 主界面 —— 整个 App 唯一持有"界面状态"的地方。
 *
 * ══ 它承担了本该由 ViewModel 承担的三件事 ══════════════════════
 *   1. 页面切换（screen）
 *   2. 加载数据（books / files）
 *   3. 执行用户操作（导入）并把结果反映到界面
 *
 * 【为什么现在不用 ViewModel】
 * ViewModel 要额外引入 lifecycle-viewmodel-compose 依赖，而它主要解决
 * 两个问题：屏幕旋转时保住状态、把数据和生命周期绑在一起。
 * 电视不会旋转，页面也简单，所以先用手写的 remember 状态。
 * 模块 7 会引入 ViewModel，那时你能直观对比出它解决了什么问题 ——
 * 比一上来就用框架、却不知道为什么用要好。
 *
 * ══ 线程：所有磁盘操作都在 IO 线程 ═════════════════════════════
 * LaunchedEffect 和 rememberCoroutineScope 拿到的协程跑在【主线程】。
 * 而读文件、写文件、解析文本都是阻塞操作，直接在主线程做会卡住界面
 * （电视上表现为遥控器按键延迟一两秒才响应，体验极差）。
 *
 * 所以每一处 IO 都用 withContext(Dispatchers.IO) 包起来 ——
 * 这也正是我们把 BookParser / BookRepository 设计成阻塞式的原因：
 * **它们不关心线程，线程由调用方决定**，这里就是那个调用方。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun MainScreen(container: AppContainer) {

    // ── 界面状态 ────────────────────────────────────────────
    var screen by remember { mutableStateOf<Screen>(Screen.Shelf) }

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    /** null 表示"还没扫描过"，用来区分"扫描中"和"扫出来是空的" */
    var files by remember { mutableStateOf<List<SourceFile>?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var lastResults by remember { mutableStateOf<List<ImportResult>?>(null) }

    val scope = rememberCoroutineScope()

    // ── 数据操作 ────────────────────────────────────────────

    suspend fun reloadBooks() {
        books = withContext(Dispatchers.IO) { container.bookRepository.listBooks() }
    }

    suspend fun scanFiles() {
        files = withContext(Dispatchers.IO) {
            container.availableSources().flatMap { source -> source.listFiles() }
        }
    }

    fun startImport(list: List<SourceFile>) {
        // 空列表直接返回；导入中重复点击也忽略（防止并发写同一个目录）
        if (list.isEmpty() || isImporting) return
        isImporting = true
        lastResults = null
        scope.launch {
            val results = withContext(Dispatchers.IO) { container.bookImporter.importAll(list) }
            lastResults = results
            reloadBooks()          // 导入完刷新书架，用户能立刻看到新书
            isImporting = false
        }
    }

    // 首次进入时加载书库
    LaunchedEffect(Unit) {
        isLoading = true
        reloadBooks()
        isLoading = false
    }

    // ── 遥控器返回键 ────────────────────────────────────────
    // 在书架页按返回键应该退出 App（交给系统处理），
    // 在子页面按返回键应该回到书架。
    BackHandler(enabled = screen !is Screen.Shelf) {
        screen = Screen.Shelf
    }

    // ── 页面路由 ────────────────────────────────────────────
    when (val current = screen) {

        Screen.Shelf -> BookShelfScreen(
            books = books,
            isLoading = isLoading,
            emptyHint = "书架还是空的。\n点右上角「导入书籍」开始。",
            onOpenBook = { book -> screen = Screen.Reader(book.id, book.title) },
            onImportClick = {
                screen = Screen.Import
                lastResults = null
                scope.launch { scanFiles() }
            },
        )

        Screen.Import -> ImportScreen(
            dropDirPath = container.dropDir.absolutePath,
            files = files,
            isImporting = isImporting,
            results = lastResults,
            onRescan = { scope.launch { scanFiles() } },
            onImportAll = { startImport(files.orEmpty()) },
            onImportOne = { sourceFile -> startImport(listOf(sourceFile)) },
            onBack = { screen = Screen.Shelf },
        )

        is Screen.Reader -> ReaderPlaceholder(
            title = current.title,
            onBack = { screen = Screen.Shelf },
        )
    }
}

/**
 * 阅读页的临时占位。
 *
 * 存在的意义：让"点击一本书"有【明确反馈】。
 * 如果什么都不发生，用户会以为界面坏了 ——
 * 这种"沉默的失败"比报错还糟糕，因为它不告诉你哪里有问题。
 *
 * 模块 5 会把它换成真正的翻页阅读器。
 */
@Composable
private fun ReaderPlaceholder(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "《$title》",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            TvButton(text = "返回书架", onClick = onBack)
        }

        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "阅读器还没做。\n\n模块 5 实现「翻页」，模块 6 实现「上下连续阅读」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(600.dp),
            )
        }
    }
}
