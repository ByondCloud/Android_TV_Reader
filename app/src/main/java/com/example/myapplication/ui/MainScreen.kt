package com.example.myapplication.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.myapplication.data.AppContainer
import com.example.myapplication.domain.model.Book
import com.example.myapplication.domain.model.ParsedChapter
import com.example.myapplication.domain.reader.ReaderSettings
import com.example.myapplication.domain.source.SourceFile
import com.example.myapplication.domain.usecase.ImportResult
import com.example.myapplication.ui.importer.ImportScreen
import com.example.myapplication.ui.reader.ReaderScreen
import com.example.myapplication.ui.shelf.BookShelfScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用里的三个页面。
 *
 * 【为什么用 sealed interface 而不是 enum】
 * 因为 Reader 需要带数据（书 id、书名），而 enum 的每个常量不能带不同的参数集。
 * sealed interface 既能表达"就这几种"，又能让每种带自己的数据。
 */
private sealed interface Screen {
    data object Shelf : Screen
    data object Import : Screen
    data class Reader(val bookId: String) : Screen
}

/** 打开一本书所需要的全部内容 */
private data class ReaderContent(
    val book: Book,
    val text: String,
    val chapters: List<ParsedChapter>,
)

/** 待写入的阅读进度快照 */
private data class ProgressSnapshot(
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
)

/**
 * 主界面 —— 整个 App 唯一持有"界面状态"的地方。
 *
 * 它承担了本该由 ViewModel 承担的三件事：
 *   1. 页面切换
 *   2. 加载数据
 *   3. 执行用户操作并把结果反映到界面
 *
 * 【线程纪律】所有磁盘操作都在 Dispatchers.IO 上。
 * LaunchedEffect / rememberCoroutineScope 拿到的协程跑在【主线程】，
 * 而读文件、写文件、解析文本都是阻塞操作，直接在主线程做会卡界面
 * （电视上表现为按键延迟一两秒才响应）。
 *
 * 这也正是我们把 BookParser / BookRepository 设计成阻塞式的原因：
 * 它们不关心线程，线程由调用方决定 —— 这里就是那个调用方。
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

    // ── 阅读相关状态 ────────────────────────────────────────
    var readerSettings by remember { mutableStateOf(ReaderSettings()) }

    /** 打开中的书。null 表示还在加载 */
    var readerContent by remember { mutableStateOf<ReaderContent?>(null) }

    /** 最新的阅读位置（还没落盘）。用 debounce 方式写入，避免翻页时每页都写磁盘 */
    var liveProgress by remember { mutableStateOf<ProgressSnapshot?>(null) }

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
        if (list.isEmpty() || isImporting) return
        isImporting = true
        lastResults = null
        scope.launch {
            val results = withContext(Dispatchers.IO) { container.bookImporter.importAll(list) }
            lastResults = results
            reloadBooks()
            isImporting = false
        }
    }

    // 首次进入时加载书库
    LaunchedEffect(Unit) {
        isLoading = true
        reloadBooks()
        isLoading = false
    }

    // ══ 阅读进度的防抖落盘 ══════════════════════════════════
    // 【为什么需要防抖】
    // 用户快速连按翻页键时，位置每几百毫秒就变一次。
    // 如果每次变化都立刻写磁盘，一次连续翻页会产生几十次 IO ——
    // 既浪费电（电视盒子是 24 小时开着的设备），也会让界面卡顿。
    //
    // LaunchedEffect(key) 的语义正好就是防抖：
    // key 变化时【自动取消上一次的协程】，所以 delay 期间又来了新变化，
    // 旧的这次就不会执行到写盘那一步。
    LaunchedEffect(liveProgress) {
        val snapshot = liveProgress ?: return@LaunchedEffect
        delay(800)
        withContext(Dispatchers.IO) {
            runCatching {
                container.bookRepository.saveProgress(
                    snapshot.bookId, snapshot.chapterIndex, snapshot.charOffset
                )
            }
        }
    }

    // ══ 遥控器返回键 ════════════════════════════════════════
    // 【注意这里为什么只处理导入页】
    // 阅读页需要按键做翻页，所以它的返回键是在 ReaderScreen 内部用
    // onKeyEvent 处理的。如果这里也开一个 BackHandler，按一次返回键
    // 可能同时触发两处逻辑，导致"越过书架直接退出 App"这种诡异行为。
    // 一个返回键，只能有一个负责人。
    BackHandler(enabled = screen is Screen.Import) {
        screen = Screen.Shelf
    }

    // ── 页面路由 ────────────────────────────────────────────
    when (val current = screen) {

        Screen.Shelf -> BookShelfScreen(
            books = books,
            isLoading = isLoading,
            emptyHint = "书架还是空的。\n点右上角「导入书籍」开始。",
            onOpenBook = { book ->
                readerContent = null
                screen = Screen.Reader(book.id)
            },
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

        is Screen.Reader -> {
            // 打开书：读正文 + 切章节
            LaunchedEffect(current.bookId) {
                readerContent = withContext(Dispatchers.IO) {
                    val book = container.bookRepository.findBook(current.bookId)
                        ?: return@withContext null
                    val text = runCatching { container.bookRepository.loadText(current.bookId) }
                        .getOrDefault("")
                    val chapters = runCatching { container.bookRepository.loadChapters(current.bookId) }
                        .getOrDefault(emptyList())
                    ReaderContent(book = book, text = text, chapters = chapters)
                }
            }

            val content = readerContent
            if (content == null) {
                LoadingScreen("正在打开《${current.bookId}》…")
            } else {
                ReaderScreen(
                    book = content.book,
                    text = content.text,
                    chapters = content.chapters,
                    // 有进度就从进度开始，没读过就从第一章开头
                    initialProgress = content.book.progress,
                    settings = readerSettings,
                    onProgressChange = { chapterIndex, charOffset ->
                        // 只更新内存里的快照，真正的写盘交给上面的防抖逻辑
                        liveProgress = ProgressSnapshot(content.book.id, chapterIndex, charOffset)
                    },
                    onToggleMode = {
                        readerSettings = readerSettings.copy(mode = readerSettings.mode.toggled())
                    },
                    onBack = {
                        // 【退出时立即写一次】防抖窗口是 800ms，
                        // 如果用户翻完最后一页立刻退出，那次防抖可能还没执行。
                        // 这里补一刀，保证进度一定被保存。
                        val snapshot = liveProgress
                        scope.launch {
                            if (snapshot != null) {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        container.bookRepository.saveProgress(
                                            snapshot.bookId, snapshot.chapterIndex, snapshot.charOffset
                                        )
                                    }
                                }
                            }
                            reloadBooks()      // 刷新书架上"续读 · 第 N 章"的显示
                        }
                        readerContent = null
                        screen = Screen.Shelf
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
