package com.example.myapplication.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.tts.AndroidTtsReader
import com.example.myapplication.data.tts.TtsEngine
import com.example.myapplication.data.tts.TtsLanguage
import com.example.myapplication.domain.model.Book
import com.example.myapplication.domain.model.ParsedChapter
import com.example.myapplication.domain.model.ReadingProgress
import com.example.myapplication.domain.reader.Paginator
import com.example.myapplication.domain.reader.ReaderSettings
import com.example.myapplication.domain.reader.ReadingUnits
import com.example.myapplication.domain.tts.TextSegmenter
import com.example.myapplication.ui.components.tvFocusable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

// ══ 长按连续滚动的两个参数 ═══════════════════════════════════════
//
// 【为什么需要"长按判定延迟"】
// 遥控器只会告诉我们"按下"和"松开"，没有"按住"这个概念。
// 只能自己判断：按下后等 400ms，期间没松手就认为用户想连续滚动。
//
//   太短（100ms）→ 想滚一行时稍慢点松手就变成滚一大段
//   太长（800ms）→ 按住后要等很久才开始滚，感觉迟钝
// 400ms 接近大多数遥控器/手柄的"长按"标准。
//
// 【为什么 50ms 滚一次】
// 50ms = 每秒 20 行 ≈ 每秒 520 字，比阅读速度快，
// 所以它适合"快速翻找位置"而不是"跟着读"。这个取舍是对的：
// 想慢慢读就用短按，想快速跳转就用长按。
private const val LONG_PRESS_DELAY_MS = 400L
private const val AUTO_SCROLL_INTERVAL_MS = 50L

/** firstLine 的"尚未定位"标记 */
private const val LINE_UNSET = -1

/**
 * 一次"跳转到指定位置"的请求。
 *
 * ══ 【为什么需要这条独立通道】═══════════════════════════════════
 * PagedReader 的阅读位置是它【自己的内部状态】（那个叫 firstLine 的整数）。
 * 外面把 absoluteOffset 改掉，它是不知情的 ——
 * 因为正常情况下 absoluteOffset 恰恰是【由它算出来再报上去的】。
 * 如果它一看到参数变化就重新定位，就会变成"自己触发自己"的死循环。
 *
 * 所以必须明确区分两种位移：
 *   · 用户按方向键滚动   → firstLine 变 → 报给外层存进度（单向：内 → 外）
 *   · 用户从目录点了某章 → 外层发请求   → firstLine 重定位（单向：外 → 内）
 *
 * [token] 每次递增。作用是：连续两次跳到【同一章】时，
 * 光看 offset 分不出"这是新的一次请求"，加个序号才能可靠触发。
 * ═══════════════════════════════════════════════════════════════
 */
private data class JumpRequest(val token: Int, val offsetInUnit: Int)

/**
 * 阅读页。
 *
 * ══ 遥控器按键约定 ══════════════════════════════════════════════
 *   ↑ / ↓        逐行滚动（短按 1 行，长按连续滚）
 *   ← / →        上一屏 / 下一屏；到单元边界则跳到相邻单元
 *   确定键       打开设置菜单
 *   设置键/菜单键  打开设置菜单
 *   返回键       回到书架（面板打开时则关闭面板）
 *
 * 【设置键为什么同时支持两个键码】
 * 不同厂家的遥控器，那个"设置/菜单"按钮发出的键码不一样：
 * 有的是 KEYCODE_SETTINGS，有的是 KEYCODE_MENU。
 * 两个都监听，用户按哪个都能打开面板。
 *
 * 【为什么上下键是"滚 1 行"而不是"跳单元"】
 * 一个单元可能有几十屏。如果上下键一按就跳整个单元，用户想微调位置时
 * 就只能靠左右键一屏一屏翻，很笨。行级滚动之后：
 *   - 短按：滚一行，可以精细地看到被屏幕切断的那一行
 *   - 长按：连续滚动，快速找到想看的位置
 * 而"整屏移动"交给左右键，两者各司其职。
 *
 * 【为什么读到单元边界就自动跳到下一个】
 * 用户不该关心"章"这个内部概念。读到本章最后一页再按右键，
 * 自然应该继续读下一章 —— 而不是什么都不发生，让用户以为卡住了。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun ReaderScreen(
    book: Book,
    text: String,
    chapters: List<ParsedChapter>,
    initialProgress: ReadingProgress,
    settings: ReaderSettings,
    /**
     * 语音朗读器。
     *
     * 【为什么直接依赖这个具体类，而不抽一个接口】
     * 因为它目前只有一个实现，而且只会有一个 —— 抽接口是"为不存在的
     * 变化做准备"。等真的要做"云端 TTS"时再抽也不迟，
     * 那时接口该长什么样也会更清楚。
     * （对比一下：BookParser 之所以是接口，是因为 epub/pdf 是确定要加的。）
     */
    ttsReader: AndroidTtsReader,
    onProgressChange: (absoluteOffset: Int) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ══ 阅读单元 ════════════════════════════════════════════
    // 【这一层抽象的关键作用】
    // 阅读器只认识 ReadingUnit（一段文本区间），不认识"章节"。
    // 至于这段区间是"一章"还是"按大小切的八千字"，由 ReadingUnits 决定。
    // 所以"按章节分页"这个开关，在阅读器代码里【只影响这一次调用】，
    // 翻页、滚动、进度那些逻辑一个字都不用改。
    val units = remember(text, chapters, settings.chapterSplitEnabled) {
        ReadingUnits.build(
            text = text,
            chapters = chapters,
            chapterSplitEnabled = settings.chapterSplitEnabled,
        )
    }

    if (units.isEmpty()) {
        EmptyReader(onBack = onBack, modifier = modifier)
        return
    }

    // ══ 阅读位置 ════════════════════════════════════════════
    // 【关键设计：用"全书绝对字符偏移"作为唯一的位置坐标】
    //
    // 为什么不用 (第几单元, 单元内偏移)？
    //   因为"单元怎么切"是会变的 —— 用户一拨"按章节分页"开关，
    //   同样一本书的单元划分就完全不同了。如果进度存的是单元号，
    //   切换开关后位置就全乱了。
    //
    //   而绝对偏移是从书的第一页算起的，和怎么切单元无关。
    //   切法变了，只需要重新算出"这个偏移落在哪个单元里"。
    var absoluteOffset by remember {
        mutableIntStateOf(initialProgress.charOffset.coerceAtLeast(0))
    }

    // 从绝对偏移推导出：现在在哪个单元、单元内偏移多少
    val unitIndex = units.indexOfLast { it.startOffset <= absoluteOffset }
        .let { if (it < 0) 0 else it }
    val unit = units[unitIndex]
    val offsetInUnit = (absoluteOffset - unit.startOffset).coerceIn(0, unit.length)

    // 当前单元的正文
    val body = remember(text, unit) {
        text.substring(unit.startOffset, unit.endOffset)
    }

    // 设置面板是否打开
    var settingsVisible by remember { mutableStateOf(false) }

    // 字号/间距弹窗是否打开
    var fontSpacingVisible by remember { mutableStateOf(false) }

    // 目录是否打开
    var tocVisible by remember { mutableStateOf(false) }

    // ══ 语音朗读的状态 ══════════════════════════════════════
    // 这些状态刻意留在 ReaderScreen 里，没有往上提升到 MainScreen：
    // 朗读是"阅读"这件事的一部分，离开阅读页就该停；
    // 而且引擎探测结果只对这次阅读有意义，提升上去反而要额外清理。
    var ttsVisible by remember { mutableStateOf(false) }

    /** null = 还没探测过（面板会显示"正在检测…"） */
    var ttsEngines by remember { mutableStateOf<List<TtsEngine>?>(null) }
    var selectedEngine by remember { mutableStateOf<TtsEngine?>(null) }
    var selectedLanguage by remember { mutableStateOf<TtsLanguage?>(null) }
    var isSpeaking by remember { mutableStateOf(false) }
    var ttsError by remember { mutableStateOf<String?>(null) }

    val ttsScope = rememberCoroutineScope()

    // 待处理的跳转请求。null = 没有
    var jumpRequest by remember { mutableStateOf<JumpRequest?>(null) }

    // 当前落在第几章。目录列表用它高亮"你正在读的这章"。
    // 整本连续模式下单元不是章节，但 chapters 数据仍在，照样能算出来。
    val currentChapterIndex = chapters
        .indexOfLast { it.startOffset <= absoluteOffset }
        .coerceIn(0, max(0, chapters.size - 1))

    // ══ 引擎探测：朗读面板一打开就查 ════════════════════════
    // 【为什么要探测，不能直接假定有引擎】
    // Android 系统本身不含 TTS 引擎，引擎是设备上另外装的。
    // 电视盒子尤其可能一个中文引擎都没有 —— 那时候"朗读"按钮
    // 按下去毫无反应，用户只会觉得 App 坏了。
    // 先把结果查出来，界面才能如实告诉用户到底行不行。
    LaunchedEffect(ttsVisible) {
        if (!ttsVisible) return@LaunchedEffect
        if (ttsEngines != null) return@LaunchedEffect     // 查过就不重复查

        val found = ttsReader.probeEngines()
        ttsEngines = found

        // 自动选一套默认值，省掉用户两次按键：
        // 引擎取第一个，语言优先中文（这毕竟是个中文阅读器）
        val engine = found.firstOrNull()
        selectedEngine = engine
        selectedLanguage = engine?.languages
            ?.firstOrNull { it.locale.language == "zh" }
            ?: engine?.languages?.firstOrNull()
    }

    // 引擎读完最后一段会回调 onFinished，用它把状态收回来
    DisposableEffect(Unit) {
        ttsReader.onFinished = { isSpeaking = false }
        onDispose {
            // 【离开阅读页必须停】否则用户都回书架了，书还在念
            ttsReader.stop()
            ttsReader.onFinished = null
            ttsReader.onSegmentStart = null
        }
    }

    /**
     * 从【当前阅读位置】开始朗读。
     *
     * 【为什么起点是 absoluteOffset】
     * 那是用户此刻正在看的位置。朗读如果从全书开头念起，
     * 读到一个中间位置的人会被直接拽回第一章 —— 完全没法用。
     */
    fun startSpeaking() {
        val engine = selectedEngine ?: return
        val language = selectedLanguage ?: return

        ttsScope.launch {
            val ready = ttsReader.prepare(engine.name, language.locale)
            if (!ready) {
                ttsError = "这个引擎不支持所选语言，换一个再试"
                return@launch
            }

            val segments = TextSegmenter.split(text, absoluteOffset)
            if (segments.isEmpty()) {
                ttsError = "当前位置往后没有可朗读的内容"
                return@launch
            }

            ttsError = null
            ttsReader.speak(segments)
            isSpeaking = true
        }
    }

    fun stopSpeaking() {
        ttsReader.stop()
        isSpeaking = false
    }

    /** 切到相邻单元。往前翻时停在末尾，所以传入一个极大的偏移。 */
    fun goToUnit(index: Int, toEnd: Boolean) {
        val target = index.coerceIn(0, units.lastIndex)
        if (target == unitIndex) return
        absoluteOffset = if (toEnd) units[target].endOffset else units[target].startOffset
    }

    // 页/滚动信息，只用于底栏显示
    var pageInfo by remember { mutableStateOf("") }

    // ══ 保存阅读进度 ════════════════════════════════════════
    LaunchedEffect(absoluteOffset, units) {
        // clamp 一次：单元末尾的哨兵值不能原样存进书库，
        // 否则下次打开会定位到一个不存在的偏移。
        onProgressChange(absoluteOffset.coerceIn(0, text.length))
    }

    Box(modifier = modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {

            // ── 顶部信息栏 ──────────────────────────────────
            ReaderTopBar(
                bookTitle = book.title,
                unitIndex = unitIndex,
                unitCount = units.size,
                unitTitle = unit.title,
                unitLabel = if (settings.chapterSplitEnabled) "章" else "段",
            )

            Spacer(Modifier.height(12.dp))

            // ── 阅读区 ──────────────────────────────────────
            // 注意：阅读区是这一页【唯一】的可聚焦元素。
            // 这样方向键不会被焦点系统"抢走"去移动焦点，
            // 而是稳定地冒泡到我们的 onKeyEvent 里。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = settings.contentPaddingDp.dp)
            ) {
                // 设置面板或字号弹窗打开时禁用阅读区的焦点 ——
                // 否则方向键会把焦点从面板挪回正文，用户就没法操作设置了。
                val inputEnabled = !settingsVisible && !fontSpacingVisible

                // 【这里曾经是 when (settings.mode) 两个分支】
                // 现在只剩这一种阅读方式了，原因见 ReaderSettings 顶部的说明：
                // 滚动模式在遥控器上会抖动、会卡住，而"翻页 + 上下逐行滚动"
                // 这一套既稳又够用 —— 上下键本来就是逐行滚动。
                PagedReader(
                    body = body,
                    settings = settings,
                    initialOffset = offsetInUnit,
                    inputEnabled = inputEnabled,
                    jumpRequest = jumpRequest,
                    onOffsetChange = { absoluteOffset = unit.startOffset + it },
                    onPrevUnit = { goToUnit(unitIndex - 1, toEnd = true) },
                    onNextUnit = { goToUnit(unitIndex + 1, toEnd = false) },
                    onPageInfoChange = { current, total -> pageInfo = "第 $current / $total 页" },
                    onOpenSettings = { settingsVisible = true },
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 底部状态栏 ──────────────────────────────────
            ReaderBottomBar(
                info = pageInfo,
                progressPercent = if (text.isEmpty()) 0 else {
                    (absoluteOffset.coerceIn(0, text.length) * 100 / text.length)
                },
            )
        }

        // ── 设置面板（覆盖在阅读内容之上）────────────────────
        if (settingsVisible) {
            ReaderSettingsPanel(
                settings = settings,
                chapterCount = chapters.size,
                isSpeaking = isSpeaking,
                onOpenToc = {
                    settingsVisible = false
                    tocVisible = true
                },
                onOpenTts = {
                    settingsVisible = false
                    ttsVisible = true
                },
                onFontSpacing = {
                    // 【为什么先关菜单再开弹窗】
                    // 两个浮层叠在一起会让用户搞不清"现在按返回键会关掉哪个"。
                    // 一次只显示一层，层级关系永远清晰。
                    settingsVisible = false
                    fontSpacingVisible = true
                },
                onToggleChapterSplit = {
                    onSettingsChange(
                        settings.copy(chapterSplitEnabled = !settings.chapterSplitEnabled)
                    )
                },
                onDismiss = { settingsVisible = false },
            )
        }

        // ── 语音朗读面板 ───────────────────────────────────
        if (ttsVisible) {
            TtsPanel(
                engines = ttsEngines,
                selectedEngine = selectedEngine,
                selectedLanguage = selectedLanguage,
                isSpeaking = isSpeaking,
                errorMessage = ttsError,
                onSelectEngine = { engine ->
                    selectedEngine = engine
                    // 换引擎时语言也要跟着换 —— 旧语言在新引擎上未必支持
                    selectedLanguage = engine.languages
                        .firstOrNull { it.locale.language == "zh" }
                        ?: engine.languages.firstOrNull()
                    ttsError = null
                },
                onSelectLanguage = { language ->
                    selectedLanguage = language
                    ttsError = null
                },
                onStart = { startSpeaking() },
                onStop = { stopSpeaking() },
                onDismiss = { ttsVisible = false },
            )
        }

        // ── 目录（章节列表）────────────────────────────────
        if (tocVisible) {
            ChapterListPanel(
                chapters = chapters,
                currentIndex = currentChapterIndex,
                onSelect = { chapter ->
                    // ══ 跳章的三步 ══════════════════════════════════
                    // ① 改绝对偏移 —— 这会自动让外层重新算出"现在在哪个单元"
                    absoluteOffset = chapter.startOffset

                    // ② 发一个跳转请求。
                    //    这一步不能省：如果目标章和当前章恰好落在【同一个单元】里
                    //    （打开"整本连续"时很常见），单元的 body 没变，
                    //    PagedReader 就不会重置自己的位置 —— 它需要被明确告知。
                    //
                    //    请求里的偏移要换算成【单元内偏移】，
                    //    因为 PagedReader 只认识自己那一段文本的坐标系。
                    val targetUnit = units.indexOfLast { it.startOffset <= chapter.startOffset }
                        .coerceAtLeast(0)
                    jumpRequest = JumpRequest(
                        token = (jumpRequest?.token ?: 0) + 1,
                        offsetInUnit = (chapter.startOffset - units[targetUnit].startOffset)
                            .coerceAtLeast(0),
                    )

                    // ③ 关掉目录，让用户立刻看到跳过去的效果
                    tocVisible = false
                },
                onDismiss = { tocVisible = false },
            )
        }

        // ── 字号 / 间距弹窗（底部浮层）──────────────────────
        // 【为什么这个弹窗【不加】半透明遮罩】
        // 用户调字号/间距时最想看的就是"正文变成了什么样"。
        // 盖一层黑上去，等于让他盲调 —— 调完关掉才发现不合适，再打开调。
        // 所以这里只画一个小浮层，正文完全可见、实时跟着变。
        if (fontSpacingVisible) {
            FontSpacingPanel(
                settings = settings,
                onFontSizeDelta = { delta ->
                    val next = (settings.fontSizeSp + delta).coerceIn(
                        ReaderSettings.MIN_FONT_SIZE_SP,
                        ReaderSettings.MAX_FONT_SIZE_SP,
                    )
                    onSettingsChange(settings.copy(fontSizeSp = next))
                },
                onLineSpacingDelta = { delta ->
                    val next = (settings.lineSpacingLevel + delta).coerceIn(
                        ReaderSettings.MIN_LINE_SPACING,
                        ReaderSettings.MAX_LINE_SPACING,
                    )
                    onSettingsChange(settings.copy(lineSpacingLevel = next))
                },
                onDismiss = { fontSpacingVisible = false },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  设置面板
// ══════════════════════════════════════════════════════════════════

/**
 * 阅读设置面板。
 *
 * ══ 为什么不用 Dialog ═══════════════════════════════════════════
 * Compose 的 Dialog 会新建一个系统窗口，在 Android TV 上会带来
 * 焦点、过扫描（Overscan）、返回键归属等一堆额外问题。
 *
 * 直接在当前 Compose 树里画一个覆盖层更可控：
 *   - 焦点只在同一棵树内转移，行为可预测
 *   - 自动继承 TvSafeArea 的安全边距，不会被电视裁掉
 *   - 返回键由我们自己处理，不会和外层冲突
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun ReaderSettingsPanel(
    settings: ReaderSettings,
    chapterCount: Int,
    isSpeaking: Boolean,
    onOpenToc: () -> Unit,
    onOpenTts: () -> Unit,
    onFontSpacing: () -> Unit,
    onToggleChapterSplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    // 面板一出现就把焦点拿过来，否则用户按确定键会作用到下面的阅读区上
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 半透明遮罩：让用户一眼看出"现在是设置状态"
            .background(Color(0xDD000000))
            // 返回键关闭面板。这个 onKeyEvent 在外层，
            // 内层的设置行不处理返回键，事件会冒泡上来。
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Settings || event.key == Key.Menu)
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(14.dp))
                // 电视上的软键盘、以及以后设置项变多时，都可能让内容超高，
                // 加滚动能保证任何情况下都够得着所有选项
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                text = "阅读设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(20.dp))

            // ① 目录 —— 放在【最上面】
            // 【为什么它排第一】
            // 它是这里唯一的"跳转"类操作，比调字号更常用：
            // 用户打开设置，十有八九是想找上次读到哪、或者跳到某一章。
            // 把最常用的放最上面，能省掉一次方向键。
            SettingRow(
                label = "目录",
                description = if (chapterCount > 0) {
                    "点进去可以跳到任意一章"
                } else {
                    "这本书没有识别出章节"
                },
                value = if (chapterCount > 0) "共 $chapterCount 章" else "—",
                onClick = onOpenToc,
                modifier = Modifier.focusRequester(focusRequester),
                enabled = chapterCount > 0,
            )

            Spacer(Modifier.height(10.dp))

            // ② 语音朗读
            SettingRow(
                label = "语音朗读",
                description = "让设备念出正文，从你当前读到的位置开始",
                value = if (isSpeaking) "朗读中…" else "打开",
                onClick = onOpenTts,
                highlightValue = isSpeaking,
            )

            Spacer(Modifier.height(10.dp))

            // ③ 字号间距 —— 点进去打开底部弹窗实时调
            SettingRow(
                label = "字号间距",
                description = "调整正文字号和行间松紧",
                value = "字号 ${settings.fontSizeSp.toInt()} · 间距 ${settings.lineSpacingLevel}",
                onClick = onFontSpacing,
            )

            Spacer(Modifier.height(10.dp))

            // ④ 按章节分页
            SettingRow(
                label = "按章节分页",
                description = if (settings.chapterSplitEnabled) {
                    "每章独立排版，章与章之间会断开。能显示章节名。"
                } else {
                    "全文连续排版，忽略章节边界。断开的地方少得多。"
                },
                value = if (settings.chapterSplitEnabled) "✓ 开" else "✗ 关",
                highlightValue = settings.chapterSplitEnabled,
                onClick = onToggleChapterSplit,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "确定键切换 · 返回键或设置键关闭",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * 设置面板里的一行。
 *
 * 【为什么用文字值（"✓ 开" / "翻页"）而不是真的做 Switch、下拉框】
 * TV 上的 Switch 是给手指拨的，用遥控器操作时"确定键直接切换"更自然，
 * 而且文字状态在三米外也看得清 —— 一个开关图形在电视上根本看不出开还是关。
 *
 * 【为什么 value 和 description 分开】
 * value 是"现在是什么"（一眼要看到），description 是"这是什么意思"
 * （需要时才细看）。字号和颜色都不同，信息层级才清楚。
 */
@Composable
private fun SettingRow(
    label: String,
    description: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightValue: Boolean = true,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                // 不可用时整体压暗，让用户一眼看出"这项现在点不了"
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(Modifier.width(16.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlightValue && enabled) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
    }
}

/**
 * 目录：章节列表。
 *
 * ══ 【两件让它好用的小事】═══════════════════════════════════════
 * 1. 打开时自动滚到【当前章】。
 *    一本几百章的书，如果每次都从第 1 章开始列，
 *    用户要按几十下方向键才找得到自己在读的地方。
 *
 * 2. 当前章右边标一个「正在读」。
 *    滚动之后用户可能已经分不清焦点在哪，一个文字标记最直接。
 *
 * 【这里可以放心用 LazyColumn】
 * 它不涉及逐像素滚动（只有焦点在整行之间移动），
 * 所以不会出现刚被删掉的那个滚动模式的抖动问题。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun ChapterListPanel(
    chapters: List<ParsedChapter>,
    currentIndex: Int,
    onSelect: (ParsedChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 挂在"当前章"那一行上的焦点引用
    val currentRowRequester = remember { FocusRequester() }

    // 【打开目录时必须主动把焦点放到列表上】
    // 否则页面上没有任何元素被选中，用户按方向键毫无反应 ——
    // 电视上的通用规则：新出现的界面一定要主动设置初始焦点。
    //
    // 【顺序：先滚动，再聚焦】
    // 反过来的话，焦点会请求给一个还在屏幕外的元素，
    // 聚焦可能失败或者导致列表跳动。
    LaunchedEffect(currentIndex) {
        if (chapters.isNotEmpty()) {
            runCatching { listState.scrollToItem(currentIndex) }
            runCatching { currentRowRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Settings || event.key == Key.Menu)
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                // 不铺满全屏，留出上下边距让人一眼看出"这是个浮层"
                .fillMaxHeight(0.85f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(14.dp))
                .padding(24.dp),
        ) {
            Text(
                text = "目录",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "共 ${chapters.size} 章 · 确定键跳转 · 返回键关闭",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // key 用 chapter.index（章在书里的序号），
                // 而不是列表下标 —— 这样即使将来支持"只显示某几章"，
                // Compose 也能正确复用节点。
                itemsIndexed(chapters, key = { _, c -> c.index }) { index, chapter ->
                    ChapterRow(
                        index = index,
                        chapter = chapter,
                        isCurrent = index == currentIndex,
                        onClick = { onSelect(chapter) },
                        // 只给当前章挂焦点引用
                        modifier = if (index == currentIndex) {
                            Modifier.focusRequester(currentRowRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

/** 目录里的一行 */
@Composable
private fun ChapterRow(
    index: Int,
    chapter: ParsedChapter,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 章号固定宽度，右边的标题才能对齐成一列
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(52.dp),
        )

        Text(
            // 有些书的章节标题就是空的（只有分隔符），兜底显示章号
            text = chapter.title.ifBlank { "第 ${index + 1} 章" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (isCurrent) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "正在读",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/**
 * 字号 / 间距 弹窗。
 *
 * ══ 为什么是这个形状 ═══════════════════════════════════════════
 * 用户的原话是"弹窗只需要2个信息"，所以这里【只有一行】：
 *
 *     字号 : 20  |  间距 : 3
 *
 * 不放减号加号按钮、不放滑杆、不放预览文字 —— 那些都是"看起来更完整"
 * 但实际让用户多按好几次方向键才能到达真正想按的地方。
 * 按键直接映射成操作，比"移动焦点到按钮再按确定"快一倍。
 *
 * ══ 按键映射 ═══════════════════════════════════════════════════
 *   左 / 右 → 字号 −2 / +2
 *   上 / 下 → 间距档位 +1 / −1（向上更松，符合"往上 = 更多空间"的直觉）
 *   确定 / 返回 / 设置 → 关闭
 *
 * 【间距为什么是 1~5 档而不是连续值】
 * 见 ReaderSettings 里 lineSpacingLevel 的注释 ——
 * 简单说就是"松一点"这种感受本来就没有 0.1 的精度，
 * 5 档既够用又不用输数字。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun FontSpacingPanel(
    settings: ReaderSettings,
    onFontSizeDelta: (Float) -> Unit,
    onLineSpacingDelta: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 【注意：这里没有半透明遮罩】
            // 用户调字号时最想看的就是"正文现在长什么样"。
            // 盖一层黑上去等于让他盲调，所以只画下面这个小浮层，
            // 正文完全可见、并且实时跟着设置变。
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onFontSizeDelta(-ReaderSettings.FONT_SIZE_STEP); true }
                    Key.DirectionRight -> { onFontSizeDelta(+ReaderSettings.FONT_SIZE_STEP); true }
                    // 向上 = 行距更松（更多空间），向下 = 更紧凑
                    Key.DirectionUp -> { onLineSpacingDelta(+1); true }
                    Key.DirectionDown -> { onLineSpacingDelta(-1); true }

                    Key.DirectionCenter, Key.Enter, Key.Back,
                    Key.Settings, Key.Menu,
                    -> { onDismiss(); true }

                    else -> false
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .focusRequester(focusRequester)
                .focusable()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(2.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(16.dp))
                .padding(horizontal = 44.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 就是这一行 —— 弹窗的全部内容
            Text(
                text = "字号 : ${settings.fontSizeSp.toInt()}  |  间距 : ${settings.lineSpacingLevel}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "左右调字号 · 上下调间距 · 确定键关闭",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  翻页模式
// ══════════════════════════════════════════════════════════════════

/**
 * 翻页阅读器。
 *
 * ══ 位置模型：一个整数搞定 ══════════════════════════════════════
 * 整个阅读位置只用一个状态表示：[firstLine] ——「当前屏幕从第几行开始显示」。
 *
 *   上下键：firstLine ± 1              → 滚一行
 *   左右键：firstLine ± linesPerPage   → 翻一屏
 *
 * 底栏显示的"第几页"是【算出来的】（firstLine / linesPerPage + 1），
 * 不是存起来的 —— 单一状态源的好处就在这里。
 *
 * ══ 为什么测量一次就够 ═════════════════════════════════════════
 * 文本换行是【贪心】的：从左到右填，填不下才换行。
 * 所以只要从某个【行首】开始渲染，后面的换行位置和整段渲染时完全一致。
 * （ReadingUnits 切分时特意对齐到换行符，就是为了保证这一点。）
 * 于是测量一次就能得到所有行的边界，任意位置都能直接用 substring 取出来。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun PagedReader(
    body: String,
    settings: ReaderSettings,
    initialOffset: Int,
    inputEnabled: Boolean,
    jumpRequest: JumpRequest?,
    onOffsetChange: (Int) -> Unit,
    onPrevUnit: () -> Unit,
    onNextUnit: () -> Unit,
    onPageInfoChange: (current: Int, total: Int) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()

    // 【关键】测量用的 style 必须和渲染用的 style【完全一致】，
    // 否则测出来的行数和实际排版的行数不一样，位置就会错位。
    val textStyle = remember(settings.fontSizeSp, settings.lineHeightSp) {
        TextStyle(
            fontSize = settings.fontSizeSp.sp,
            lineHeight = settings.lineHeightSp.sp,
        )
    }

    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    // 测量整段。只在正文 / 样式 / 可用尺寸变化时重算。
    val layout = remember(body, textStyle, areaSize) {
        if (areaSize.width <= 0 || body.isEmpty()) {
            null
        } else {
            measurer.measure(
                text = body,
                style = textStyle,
                // 宽度限制成实际可用宽度，高度不限制（Infinity）——
                // 我们要测的是"整段有多少行"，不是"屏幕能放多少行"。
                constraints = Constraints(maxWidth = areaSize.width),
            )
        }
    }

    val lineCount = layout?.lineCount ?: 0

    // 每一行的起止字符下标。渲染和分页都用它。
    // 注意 getLineEnd(i) 用默认版本（visibleEnd = false），
    // 这样行尾【包含换行符】，相邻两行首尾严格相接，一个字符都不漏。
    val lineStarts = remember(layout) {
        layout?.let { l -> IntArray(l.lineCount) { l.getLineStart(it) } }
    }
    val lineEnds = remember(layout) {
        layout?.let { l -> IntArray(l.lineCount) { l.getLineEnd(it) } }
    }

    // ══ 数出一屏能放几行 ════════════════════════════════════
    // 【不要用「可用高度 ÷ 行高」这种估算】
    // 它会把行数多算半行到一行，结果是屏幕最后一行被裁掉。
    // 正确做法：用测量结果里每行的【实际像素底部位置】判断 ——
    // 第 i 行能放下 <=> getLineBottom(i) <= 可用高度。
    val linesPerPage = remember(layout, areaSize.height) {
        if (layout == null || areaSize.height <= 0) {
            0
        } else {
            var n = 0
            while (n < layout.lineCount &&
                layout.getLineBottom(n) <= areaSize.height.toFloat()
            ) {
                n++
            }
            max(1, n)
        }
    }

    // 总页数（只用于底栏显示）。复用 Paginator ——
    // 它是纯逻辑且有 12 个单元测试守着，比自己再写一遍除法可靠。
    val totalPages = remember(lineStarts, lineEnds, linesPerPage, body) {
        if (lineStarts == null || lineEnds == null || linesPerPage <= 0) {
            1
        } else {
            Paginator.paginate(lineStarts, lineEnds, linesPerPage, body.length)
                .size
                .coerceAtLeast(1)
        }
    }

    // ★ 唯一的阅读位置：当前屏幕从第几行开始。
    // 用 remember(body) 让它在【换单元】时重置为"未定位"，
    // 等测量完成后再根据 initialOffset 重新定位。
    var firstLine by remember(body) { mutableIntStateOf(LINE_UNSET) }

    val scope = rememberCoroutineScope()
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    // ══ 长按连续滚动 ════════════════════════════════════════
    // 按下后先等 LONG_PRESS_DELAY_MS，如果这期间用户松手了，
    // 协程会被 stopAutoScroll() 取消，于是不会连续滚动 —— 这就是"短按"。
    // 如果没松手，就每隔 AUTO_SCROLL_INTERVAL_MS 滚一行 —— 这就是"长按"。
    fun startAutoScroll(delta: Int) {
        stopAutoScroll()
        autoScrollJob = scope.launch {
            delay(LONG_PRESS_DELAY_MS)
            while (isActive) {
                val next = firstLine + delta
                // 滚到边界就停下。不在这里跳单元 ——
                // 长按一路滚到末尾时突然跳章会让人失去位置感，
                // 让用户松手后自己再按一次更符合预期。
                if (next < 0 || next > lineCount - 1) break
                firstLine = next
                delay(AUTO_SCROLL_INTERVAL_MS)
            }
        }
    }

    // 页面销毁时确保协程停掉，否则它会一直跑下去（内存泄漏 + 白耗电）
    DisposableEffect(Unit) {
        onDispose { stopAutoScroll() }
    }

    // 测量就绪后（或换单元后）定位到 initialOffset 所在的行
    LaunchedEffect(layout, body) {
        if (firstLine == LINE_UNSET && layout != null && layout.lineCount > 0) {
            val target = initialOffset.coerceIn(0, body.length)
            firstLine = layout.getLineForOffset(target).coerceIn(0, layout.lineCount - 1)
        }
    }

    // ══ 目录跳转 ════════════════════════════════════════════
    // 【为什么需要 handledJumpToken 这个记录】
    // LaunchedEffect 在 layout 变化时也会重跑。如果只判断"有请求就跳"，
    // 那么用户跳完之后只要滚动一下、或者屏幕尺寸变一下（比如软键盘弹出），
    // 就会被打回跳转点 —— 一个很莫名其妙的"位置自己弹回去"。
    // 记住处理过的序号，同一个请求只执行一次。
    var handledJumpToken by remember { mutableIntStateOf(-1) }

    LaunchedEffect(jumpRequest, layout) {
        val req = jumpRequest ?: return@LaunchedEffect
        if (req.token == handledJumpToken) return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        if (l.lineCount <= 0) return@LaunchedEffect

        handledJumpToken = req.token
        firstLine = l.getLineForOffset(req.offsetInUnit.coerceIn(0, body.length))
            .coerceIn(0, l.lineCount - 1)
    }

    // 位置变化 → 报给外层保存进度
    LaunchedEffect(firstLine, lineStarts) {
        if (firstLine != LINE_UNSET) {
            lineStarts?.getOrNull(firstLine)?.let(onOffsetChange)
        }
    }

    // 页码变化 → 报给底栏
    LaunchedEffect(firstLine, linesPerPage, totalPages) {
        if (firstLine != LINE_UNSET && linesPerPage > 0) {
            onPageInfoChange(firstLine / linesPerPage + 1, totalPages)
        }
    }

    val focusRequester = remember { FocusRequester() }

    // 焦点获取放在 inputEnabled 变化时：
    // 设置面板关闭后（inputEnabled 从 false 变 true）阅读区要重新拿到焦点，
    // 否则用户按方向键会发现"没反应"。
    LaunchedEffect(inputEnabled) {
        if (inputEnabled) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        // 系统在按住方向键时会【重复发送 KeyDown】（repeatCount > 0）。
        // 我们靠它之外的方式做连续滚动，所以要把它识别出来并忽略，
        // 否则每次重复都会重启自动滚动协程，导致它永远等不到那 400ms。
        val isRepeat = event.nativeKeyEvent.repeatCount > 0
        val isVertical = event.key == Key.DirectionUp || event.key == Key.DirectionDown

        // 松手 → 停止连续滚动。区分"短按"和"长按"就靠这一对事件。
        if (event.type == KeyEventType.KeyUp) {
            if (isVertical) {
                stopAutoScroll()
                return true
            }
            return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        // 和"打开设置"一样，这两个键任何时候都要能用
        when (event.key) {
            // ══ 【确定键和设置键现在都打开设置面板】══════════════════
            // 确定键原先绑的是"切换翻页/滚动模式"，但实测下来
            // 用户根本猜不到它在干什么 —— 屏幕上没有任何提示能让人
            // 联想到"按确定键会换阅读模式"。
            //
            // 一个用户猜不到的按键，等于没有这个功能，反而添乱。
            // 所以先把它和设置键统一到菜单上（菜单是有视觉反馈的，
            // 按一下就知道发生了什么），等想清楚它该做什么再单独绑。
            //
            // 原来的"切换模式"没有丢 —— 移到了设置菜单里，
            // 见 ReaderSettingsPanel 的「阅读模式」那一项。
            // ═══════════════════════════════════════════════════════
            Key.DirectionCenter, Key.Enter, Key.Settings, Key.Menu -> {
                onOpenSettings()
                return true
            }
            Key.Back -> { onBack(); return true }
        }

        // 还没测量完（或还没定位）时，方向键先不处理 ——
        // 否则会因为边界值判断错误而误跳单元。
        if (firstLine == LINE_UNSET || lineCount == 0) return false

        // 按住不放产生的重复事件：交给已经在跑的自动滚动协程
        if (isRepeat && isVertical) return true

        return when (event.key) {
            // ── 上下：行级滚动 ──────────────────────────────
            Key.DirectionDown -> {
                if (firstLine < lineCount - 1) {
                    firstLine += 1
                    startAutoScroll(+1)
                } else {
                    onNextUnit()
                }
                true
            }

            Key.DirectionUp -> {
                if (firstLine > 0) {
                    firstLine -= 1
                    startAutoScroll(-1)
                } else {
                    onPrevUnit()
                }
                true
            }

            // ── 左右：整屏翻页 ──────────────────────────────
            Key.DirectionRight -> {
                val next = firstLine + linesPerPage
                if (next < lineCount) firstLine = next else onNextUnit()
                true
            }

            Key.DirectionLeft -> {
                val prev = firstLine - linesPerPage
                if (prev >= 0) firstLine = prev else onPrevUnit()
                true
            }

            else -> false
        }
    }

    Box(
        modifier = modifier
            // 量出实际可用的文本区域尺寸。它决定了每屏能放多少行。
            .onSizeChanged { areaSize = it }
            .focusRequester(focusRequester)
            // 【修饰符顺序】onKeyEvent 必须写在 focusable 之前（外层），
            // 这样按键事件才能从焦点节点冒泡到我们的处理函数。
            .onKeyEvent { handleKey(it) }
            // 设置面板打开时不可聚焦，焦点自然交给面板
            .focusable(enabled = inputEnabled)
    ) {
        val starts = lineStarts
        val ends = lineEnds
        if (starts != null && ends != null && firstLine != LINE_UNSET && linesPerPage > 0) {
            val startLine = firstLine.coerceIn(0, lineCount - 1)
            // 最后一屏可能不满，用 minOf 兜住
            val endLine = minOf(startLine + linesPerPage - 1, lineCount - 1)
            Text(
                text = body.substring(starts[startLine], ends[endLine]),
                style = textStyle,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}


// ══════════════════════════════════════════════════════════════════
//  公共组件
// ══════════════════════════════════════════════════════════════════

@Composable
private fun ReaderTopBar(
    bookTitle: String,
    unitIndex: Int,
    unitCount: Int,
    unitTitle: String,
    unitLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = bookTitle,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            // 整本模式下单元没有标题，就只显示序号
            text = buildString {
                append("第 ")
                append(unitIndex + 1)
                append('/')
                append(unitCount)
                append(' ')
                append(unitLabel)
                if (unitTitle.isNotBlank()) {
                    append(" · ")
                    append(unitTitle)
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "上下逐行滚动 · 左右整屏翻页 · 确定键/设置键打开菜单",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun ReaderBottomBar(info: String, progressPercent: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = info.ifBlank { "滚动模式" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "全书 $progressPercent%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun EmptyReader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back ||
                        event.key == Key.DirectionCenter ||
                        event.key == Key.Enter)
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // 这里刻意不放按钮 —— 阅读页只保留【一个】可聚焦元素，
        // 否则方向键会被焦点系统抢走去移动焦点，翻页就不灵了。
        Text(
            text = "这本书没有可显示的内容。\n按返回键回书架。",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
    }
}
