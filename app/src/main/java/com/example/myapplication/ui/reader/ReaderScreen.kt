package com.example.myapplication.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
// 【注意】scrollBy 是 ScrollableState 的【扩展函数】，不是成员方法，
// 所以必须显式 import，否则会报 Unresolved reference。
// 这是 Compose 里很常见的坑：方法看起来"应该"在对象上，其实是扩展。
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.domain.model.Book
import com.example.myapplication.domain.model.ParsedChapter
import com.example.myapplication.domain.model.ReadingProgress
import com.example.myapplication.domain.reader.Paginator
import com.example.myapplication.domain.reader.ReaderMode
import com.example.myapplication.domain.reader.ReaderSettings
import com.example.myapplication.domain.reader.ReadingUnit
import com.example.myapplication.domain.reader.ReadingUnits
import com.example.myapplication.ui.components.tvFocusable
import com.example.myapplication.ui.theme.ReaderFocus
import com.example.myapplication.ui.theme.ReaderSurface
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
 * 阅读页 —— 支持「翻页」和「上下连续滚动」两种模式。
 *
 * ══ 遥控器按键约定 ══════════════════════════════════════════════
 *
 *                 翻页模式                    滚动模式
 *   ←  /  →    上一页 / 下一页            上一个单元 / 下一个单元
 *   ↑  /  ↓    向上滚 1 行              向上滚 1 行
 *              向下滚 1 行              向下滚 1 行
 *   （长按）    连续滚动                   连续滚动
 *   确定键      切换阅读模式              切换阅读模式
 *   设置键/菜单键  打开设置面板            打开设置面板
 *   返回键      回到书架                  回到书架
 *
 * 【设置键为什么同时支持两个键码】
 * 不同厂家的遥控器，那个"设置/菜单"按钮发出的键码不一样：
 * 有的是 KEYCODE_SETTINGS，有的是 KEYCODE_MENU。
 * 两个都监听，用户按哪个都能打开面板。
 *
 * 【为什么上下键是"滚 1 行"而不是"跳单元"】
 * 一个单元可能有几十屏。如果上下键一按就跳整个单元，用户想微调位置时
 * 就只能靠左右键一屏一屏翻，很笨。改成行级滚动之后：
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
                mode = settings.mode,
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
                // 设置面板打开时禁用阅读区的焦点 ——
                // 否则方向键会把焦点从面板挪回正文，用户就没法操作设置了。
                val inputEnabled = !settingsVisible

                when (settings.mode) {
                    ReaderMode.Paged -> PagedReader(
                        body = body,
                        settings = settings,
                        initialOffset = offsetInUnit,
                        inputEnabled = inputEnabled,
                        onOffsetChange = { absoluteOffset = unit.startOffset + it },
                        onPrevUnit = { goToUnit(unitIndex - 1, toEnd = true) },
                        onNextUnit = { goToUnit(unitIndex + 1, toEnd = false) },
                        onPageInfoChange = { current, total -> pageInfo = "第 $current / $total 页" },
                        onToggleMode = {
                            onSettingsChange(settings.copy(mode = settings.mode.toggled()))
                        },
                        onOpenSettings = { settingsVisible = true },
                        onBack = onBack,
                        modifier = Modifier.fillMaxSize(),
                    )

                    ReaderMode.Scroll -> ScrollReader(
                        body = body,
                        settings = settings,
                        initialOffset = offsetInUnit,
                        inputEnabled = inputEnabled,
                        onOffsetChange = { absoluteOffset = unit.startOffset + it },
                        onPrevUnit = { goToUnit(unitIndex - 1, toEnd = true) },
                        onNextUnit = { goToUnit(unitIndex + 1, toEnd = false) },
                        onToggleMode = {
                            onSettingsChange(settings.copy(mode = settings.mode.toggled()))
                        },
                        onOpenSettings = { settingsVisible = true },
                        onBack = onBack,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
                onToggleChapterSplit = {
                    onSettingsChange(
                        settings.copy(chapterSplitEnabled = !settings.chapterSplitEnabled)
                    )
                },
                onDismiss = { settingsVisible = false },
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
            // 内层的开关行不处理返回键，事件会冒泡上来。
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
                .background(ReaderSurface, RoundedCornerShape(14.dp))
                .border(1.dp, ReaderFocus, RoundedCornerShape(14.dp))
                .padding(24.dp),
        ) {
            Text(
                text = "阅读设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(20.dp))

            SettingSwitchRow(
                label = "按章节分页",
                description = if (settings.chapterSplitEnabled) {
                    "每章独立排版，章与章之间会断开。能显示章节名。"
                } else {
                    "全文连续排版，忽略章节边界。断开的地方少得多。"
                },
                checked = settings.chapterSplitEnabled,
                onClick = onToggleChapterSplit,
                modifier = Modifier.focusRequester(focusRequester),
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
 * 一行"开关"设置项。
 *
 * 用文字「✓ 开 / ✗ 关」而不是真的做一个 Switch 组件，原因：
 * TV 上的 Switch 是给手指拨的，用遥控器操作时"确定键切换"更自然，
 * 而且文字状态在三米外也看得清。
 */
@Composable
private fun SettingSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
            text = if (checked) "✓ 开" else "✗ 关",
            style = MaterialTheme.typography.titleMedium,
            color = if (checked) ReaderFocus else MaterialTheme.colorScheme.secondary,
        )
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
    onOffsetChange: (Int) -> Unit,
    onPrevUnit: () -> Unit,
    onNextUnit: () -> Unit,
    onPageInfoChange: (current: Int, total: Int) -> Unit,
    onToggleMode: () -> Unit,
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

        // 和"切换模式"一样，这几个键在任何时候都要能用
        when (event.key) {
            Key.DirectionCenter, Key.Enter -> { onToggleMode(); return true }
            Key.Settings, Key.Menu -> { onOpenSettings(); return true }
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
//  上下连续滚动模式
// ══════════════════════════════════════════════════════════════════

/** 滚动模式里的一个段落，带它在单元内的起始下标 */
private data class Paragraph(val text: String, val startOffset: Int)

/**
 * 上下连续阅读。
 *
 * ══ 为什么用 LazyColumn 而不是一个大 Text 加垂直滚动 ══════════════
 * 一段可能有几百行。如果用一个 Text 一次性渲染，
 * Compose 要在内存里为整段创建排版对象，还要把它全部画出来 ——
 * 在电视这种低配设备上会明显卡顿，滚动时掉帧。
 *
 * LazyColumn 只组合【屏幕上可见】的那几段，滚动时动态创建和回收。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun ScrollReader(
    body: String,
    settings: ReaderSettings,
    initialOffset: Int,
    inputEnabled: Boolean,
    onOffsetChange: (Int) -> Unit,
    onPrevUnit: () -> Unit,
    onNextUnit: () -> Unit,
    onToggleMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val textStyle = remember(settings.fontSizeSp, settings.lineHeightSp) {
        TextStyle(
            fontSize = settings.fontSizeSp.sp,
            lineHeight = settings.lineHeightSp.sp,
        )
    }

    // 按 \n 拆段落，同时记录每段的起始下标。
    // 跳过空行 —— 空行在滚动模式里只表现为段间距，不需要单独占一个 item。
    val paragraphs = remember(body) {
        val result = ArrayList<Paragraph>()
        var start = 0
        var i = 0
        while (i <= body.length) {
            if (i == body.length || body[i] == '\n') {
                if (i > start) {
                    result.add(Paragraph(body.substring(start, i), start))
                }
                start = i + 1
            }
            i++
        }
        result
    }

    /**
     * 每次按上下键滚动的距离：正好一行。
     * 用行高（sp）换算成像素，这样它和字号设置联动 ——
     * 用户把字号调大，滚动的"一行"也会跟着变大。
     */
    val lineStep = with(density) { settings.lineHeightSp.sp.toPx() }

    // 首次进入（或换单元）时，滚动到进度记录的位置。
    // 用 indexOfLast（最后一个起始位置 <= 目标偏移的段落）找最接近的那一段。
    LaunchedEffect(paragraphs, initialOffset) {
        if (paragraphs.isEmpty()) return@LaunchedEffect
        val target = paragraphs.indexOfLast { it.startOffset <= initialOffset }
        listState.scrollToItem(if (target < 0) 0 else target)
    }

    // 把"当前看到的第一个段落"报给外层，用来记录阅读进度。
    // snapshotFlow 把"状态读取"变成可观察的数据流，
    // 这样就能在非 Composable 的协程里监听状态变化。
    LaunchedEffect(listState, paragraphs) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            paragraphs.getOrNull(index)?.let { onOffsetChange(it.startOffset) }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(inputEnabled) {
        if (inputEnabled) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    /** 长按连续滚动。参数是【每 tick 滚动的像素数】，正数向下。 */
    fun startAutoScroll(stepPx: Float) {
        stopAutoScroll()
        autoScrollJob = scope.launch {
            delay(LONG_PRESS_DELAY_MS)
            while (isActive) {
                listState.scrollBy(stepPx)
                delay(AUTO_SCROLL_INTERVAL_MS)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopAutoScroll() }
    }

    fun handleKey(event: KeyEvent): Boolean {
        val isRepeat = event.nativeKeyEvent.repeatCount > 0
        val isVertical = event.key == Key.DirectionUp || event.key == Key.DirectionDown

        if (event.type == KeyEventType.KeyUp) {
            if (isVertical) {
                stopAutoScroll()
                return true
            }
            return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        when (event.key) {
            Key.DirectionCenter, Key.Enter -> { onToggleMode(); return true }
            Key.Settings, Key.Menu -> { onOpenSettings(); return true }
            Key.Back -> { onBack(); return true }
        }

        // 按住不放产生的重复事件交给自动滚动协程，这里不重复触发
        if (isRepeat && isVertical) return true

        return when (event.key) {
            // ── 上下：滚一行 / 长按连续滚 ────────────────────
            Key.DirectionDown -> {
                scope.launch { listState.scrollBy(lineStep) }
                startAutoScroll(lineStep)
                true
            }

            Key.DirectionUp -> {
                // 已经在最顶端时按上键 → 认为用户想回上一个单元。
                // 这是"滚到边界自动跳"的落地：不给它一个出口的话，
                // 用户滚到开头就会觉得卡住了。
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    onPrevUnit()
                } else {
                    scope.launch { listState.scrollBy(-lineStep) }
                    startAutoScroll(-lineStep)
                }
                true
            }

            // ── 左右：切单元 ────────────────────────────────
            Key.DirectionLeft -> { onPrevUnit(); true }
            Key.DirectionRight -> { onNextUnit(); true }

            else -> false
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onKeyEvent { handleKey(it) }
            .focusable(enabled = inputEnabled)
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy((settings.lineHeightSp / 2).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(paragraphs) { _, paragraph ->
                Text(
                    text = paragraph.text,
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // 末尾留一块空白，这样最后一段也能滚到屏幕上方，
            // 不会尴尬地卡在底部。
            item {
                Spacer(Modifier.height(200.dp))
            }
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
    mode: ReaderMode,
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
            text = "${mode.displayName}模式 · 上下滚动 · 左右翻${if (mode == ReaderMode.Paged) "页" else "段"} · 确定键切换 · 设置键设置",
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
