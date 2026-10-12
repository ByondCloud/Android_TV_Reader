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
 * 因为 Reader 需要带数据（书 id），而 enum 的每个常量不能带不同的参数集。
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

/**
 * 待写入的阅读进度快照。
 *
 * 【注意 charOffset 的语义变了】
 * 它现在表示【全书绝对字符偏移】，而不是"第几章里的第几个字"。
 *
 * 为什么要改：用户可以在设置里切换"是否按章节分页"。
 * 一拨开关，同一本书的阅读单元划分就完全不同了。
 * 如果进度存的是"第几个单元 + 单元内偏移"，切换后位置就全乱了。
 * 绝对偏移从书的第一页算起，和怎么切单元无关 —— 切法变了，
 * 只需要重新算出"这个偏移落在哪个单元里"。
 */
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
 * 而读文件、写文件、解析文本都是阻塞操作，直接在主线程做会卡界面。
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

    /**
     * 阅读设置。首次组合时从磁盘读一次。
     *
     * 【为什么敢在主线程上读】
     * SharedPreferences 首次加载后会把内容缓存到内存，
     * 之后每次读都是内存操作。而且我们只在 App 启动时读这一次。
     * 真正高频的操作（阅读进度）走的是 BookRepository 的文件写入，
     * 那个是老老实实放在 IO 线程的。
     */
    var readerSettings by remember {
        mutableStateOf(container.settingsRepository.loadReaderSettings())
    }

    /** 打开中的书。null 表示还在加载 */
    var readerContent by remember { mutableStateOf<ReaderContent?>(null) }

    /** 最新的阅读位置（还没落盘） */
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

    /**
     * 导入一批文件，然后回到书架。
     *
     * ══ 【为什么导入完就直接跳回书架】══════════════════════════════
     * 用户的意图是"我要看书"，导入只是中间步骤。
     * 导完还停在导入页，他得自己再按一次「返回书架」——
     * 这一步没有任何信息量，纯属多余。
     *
     * 【一个例外】如果这一批【一本都没导进去】（全是重复或失败），
     * 就留在导入页 —— 否则用户被弹回书架，完全不知道发生了什么，
     * 只会觉得"我明明点了导入，怎么什么都没变"。
     * 留在原地才能看到那条结果提示。
     * ═══════════════════════════════════════════════════════════════
     */
    fun importThenBack(list: List<SourceFile>) {
        if (list.isEmpty() || isImporting) return
        isImporting = true
        lastResults = null
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                container.bookImporter.importAll(list)
            }
            lastResults = results
            reloadBooks()
            isImporting = false

            if (results.any { it is ImportResult.Success }) {
                screen = Screen.Shelf
            }
        }
    }

    // 首次进入时加载书库
    LaunchedEffect(Unit) {
        isLoading = true
        reloadBooks()
        isLoading = false
    }

    // ══ 设置变化时落盘 ══════════════════════════════════════
    // 首次组合也会写一次（写的是刚读出来的值），看起来有点多余，
    // 但省掉了一个"是否首次"的标志位 —— 而 SharedPreferences 的
    // apply() 是异步提交，这次多余写入的代价可以忽略。
    LaunchedEffect(readerSettings) {
        withContext(Dispatchers.IO) {
            container.settingsRepository.saveReaderSettings(readerSettings)
        }
    }

    // ══ 阅读进度的防抖落盘 ══════════════════════════════════
    // 【为什么需要防抖】
    // 用户快速连按翻页键时，位置每几十毫秒就变一次。
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
    // 阅读页需要按键做翻页和打开设置，所以它的返回键是在 ReaderScreen
    // 内部用 onKeyEvent 处理的。如果这里也开一个 BackHandler，
    // 按一次返回键可能同时触发两处逻辑，导致"越过书架直接退出 App"。
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
            onRenameBook = { book, newTitle ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { container.bookRepository.renameBook(book.id, newTitle) }
                    }
                    reloadBooks()
                }
            },
            onDeleteBook = { book ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { container.bookRepository.deleteBook(book.id) }
                    }
                    reloadBooks()
                }
            },
            onDeleteBooks = { ids ->
                // 批量删除。
                //
                // 【只删应用私有书库里的副本，不动投递目录里的源文件】
                // 用户放的原始 txt 在
                //   /sdcard/Android/data/<包名>/files/books/
                // 这里删掉的是
                //   /data/data/<包名>/files/books/<书 id>/
                // 两者完全独立。所以误删了书，原始文件还在，重新导入即可。
                // 这是刻意的设计：阅读器的删除不该有"毁掉用户文件"的风险。
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ids.forEach { id ->
                            runCatching { container.bookRepository.deleteBook(id) }
                        }
                    }
                    reloadBooks()
                }
            },
        )

        Screen.Import -> ImportScreen(
            dropDirPath = container.dropDir.absolutePath,
            files = files,
            // 已导入的来源文件名，导入页用它标出"已在书架"并跳过重复勾选
            importedNames = remember(books) {
                books.map { it.sourceFileName }.filter { it.isNotBlank() }.toSet()
            },
            isImporting = isImporting,
            results = lastResults,
            onRescan = { scope.launch { scanFiles() } },
            // 三种入口走的是同一条流程：导入 → 回书架
            onImportAll = { importThenBack(files.orEmpty()) },
            onImportBatch = { picked -> importThenBack(picked) },
            onImportOne = { sourceFile -> importThenBack(listOf(sourceFile)) },
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
                LoadingScreen("正在打开…")
            } else {
                ReaderScreen(
                    book = content.book,
                    text = content.text,
                    chapters = content.chapters,
                    // 有进度就从进度开始，没读过就从全书开头
                    initialProgress = content.book.progress,
                    settings = readerSettings,
                    onProgressChange = { absoluteOffset ->
                        // 章号是为了书架页显示"续读 · 第 N 章"。
                        // 整本阅读模式下单元不是章节，但 chapters 数据仍在，
                        // 所以照样能算出当前位置属于哪一章。
                        val chapterIndex = content.chapters
                            .indexOfLast { it.startOffset <= absoluteOffset }
                            .coerceAtLeast(0)
                        liveProgress = ProgressSnapshot(
                            bookId = content.book.id,
                            chapterIndex = chapterIndex,
                            charOffset = absoluteOffset,
                        )
                    },
                    onSettingsChange = { readerSettings = it },
                    onBack = {
                        // 【退出时立即写一次】防抖窗口是 800ms，
                        // 如果用户改完设置立刻退出，那次防抖可能还没执行。
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
