package com.example.myapplication.ui.reader

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

// ══ 长按连续滚动的两个参数 ═══════════════════════════════════════
//
// 【为什么需要"长按判定延迟"】
// 遥控器的按键手势只有两种：短按、长按。系统没法直接告诉我们用户"按住不放"，
// 我们只能自己判断：按下之后等 400ms，如果期间没有松手，就认为用户想连续滚动。
//
// 这 400ms 是体验上的取舍：
//   太短（比如 100ms）→ 用户想按一下滚一行，稍微慢一点松手就变成"滚一大段"
//   太长（比如 800ms）→ 用户按住后要等很久才开始连续滚，感觉迟钝
// 400ms 接近大多数遥控器/游戏手柄的"长按"标准，用起来最顺手。
//
// 【为什么是 50ms 滚一次】
// 50ms = 每秒 20 行。中文阅读的舒适速度大约是每分钟 300~500 字，
// 一屏 9 行、每行 26 字，20 行/秒 大约相当于 520 字/秒 —— 明显比阅读速度快，
// 所以更适合"快速翻找位置"而不是"跟着读"。这个取舍是对的：
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
 *   ←  /  →    上一页 / 下一页            上一章 / 下一章
 *   ↑  /  ↓    向上滚 1 行              向上滚 1 行
 *              向下滚 1 行              向下滚 1 行
 *   （长按上下）连续滚动                  连续滚动
 *   确定键      切换阅读模式              切换阅读模式
 *   返回键      回到书架                  回到书架
 *
 * 【为什么上下键是"滚 1 行"而不是"跳章"】
 * 一章可能有几十屏。如果上下键一按就跳整章，用户想微调位置时
 * 就只能靠左右键一屏一屏翻，很笨。改成行级滚动之后：
 *   - 短按：滚一行，可以精细地看被切断的那一行
 *   - 长按：连续滚动，快速找到想看的位置
 * 而"整屏移动"交给左右键，两者各司其职。
 *
 * 【为什么滚到章节边界就自动跳章】
 * 用户不该关心"章"这个内部概念。滚到本章第一行再往上按，
 * 自然应该回到上一章 —— 而不是什么都不发生，让用户以为卡住了。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun ReaderScreen(
    book: Book,
    text: String,
    chapters: List<ParsedChapter>,
    initialProgress: ReadingProgress,
    settings: ReaderSettings,
    onProgressChange: (chapterIndex: Int, charOffset: Int) -> Unit,
    onToggleMode: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chapters.isEmpty()) {
        // 理论上 BookParser 保证至少一章，但界面对异常数据也要有兜底 ——
        // 总比读数组越界崩溃好。
        EmptyReader(onBack = onBack, modifier = modifier)
        return
    }

    // ══ 状态：只有两个 ══════════════════════════════════════
    // 【单一状态源原则】"当前在第几页 / 第几行"都不单独存，
    // 而是从 currentOffset 推导出来。这样改字号、切模式时
    // 位置能自动重新映射，不会出现"位置和实际内容对不上"。
    var chapterIndex by remember {
        mutableIntStateOf(initialProgress.chapterIndex.coerceIn(0, chapters.lastIndex))
    }
    var currentOffset by remember {
        mutableIntStateOf(initialProgress.charOffset.coerceAtLeast(0))
    }

    val chapter = chapters[chapterIndex]

    // 当前章的正文。全书文本存在 book 里，章节只记录起止下标 ——
    // 所以这里 substring 一下即可，不用重新读文件。
    val body = remember(text, chapter) {
        text.substring(
            chapter.startOffset.coerceIn(0, text.length),
            chapter.endOffset.coerceIn(0, text.length),
        )
    }

    /**
     * 切章节。
     * @param toEnd 往前翻（上一章）时要停在最后一行，所以先塞一个极大的偏移，
     *              由子组件把它映射成"最后一行"。
     */
    fun goToChapter(index: Int, toEnd: Boolean) {
        val target = index.coerceIn(0, chapters.lastIndex)
        if (target == chapterIndex) return
        chapterIndex = target
        currentOffset = if (toEnd) Int.MAX_VALUE else 0
    }

    // 页/滚动信息，只用于底栏显示
    var pageInfo by remember { mutableStateOf("") }

    // ══ 保存阅读进度 ════════════════════════════════════════
    // 【为什么这里要 clamp 一次】
    // 上面为了表示"跳到本章末尾"用了 Int.MAX_VALUE 作为哨兵值。
    // 如果把哨兵值原样存进书库，下次打开就会定位到一个不存在的偏移。
    // 在这里统一收敛成合法范围，比在每个使用点判断要可靠。
    LaunchedEffect(chapterIndex, currentOffset, body.length) {
        val safeOffset = currentOffset.coerceIn(0, body.length)
        onProgressChange(chapterIndex, safeOffset)
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── 顶部信息栏 ──────────────────────────────────────
        ReaderTopBar(
            bookTitle = book.title,
            chapterIndex = chapterIndex,
            chapterCount = chapters.size,
            chapterTitle = chapter.title,
            mode = settings.mode,
        )

        Spacer(Modifier.height(12.dp))

        // ── 阅读区 ──────────────────────────────────────────
        // 注意：阅读区是这一页【唯一】的可聚焦元素。
        // 这样方向键不会被焦点系统"抢走"去移动焦点，
        // 而是稳定地冒泡到我们的 onKeyEvent 里。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = settings.contentPaddingDp.dp)
        ) {
            when (settings.mode) {
                ReaderMode.Paged -> PagedReader(
                    body = body,
                    settings = settings,
                    currentOffset = currentOffset,
                    onOffsetChange = { currentOffset = it },
                    onPrevChapter = { goToChapter(chapterIndex - 1, toEnd = true) },
                    onNextChapter = { goToChapter(chapterIndex + 1, toEnd = false) },
                    onPageInfoChange = { current, total -> pageInfo = "第 $current / $total 页" },
                    onToggleMode = onToggleMode,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )

                ReaderMode.Scroll -> ScrollReader(
                    body = body,
                    settings = settings,
                    initialOffset = currentOffset.coerceIn(0, body.length),
                    onOffsetChange = { currentOffset = it },
                    onPrevChapter = { goToChapter(chapterIndex - 1, toEnd = true) },
                    onNextChapter = { goToChapter(chapterIndex + 1, toEnd = false) },
                    onToggleMode = onToggleMode,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 底部状态栏 ──────────────────────────────────────
        ReaderBottomBar(
            info = pageInfo,
            progressPercent = if (body.isEmpty()) 0 else {
                (currentOffset.coerceIn(0, body.length) * 100 / body.length)
            },
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
 * 这个模型比"维护页码 + 页内偏移"要简单得多，而且天然支持任意粒度的移动。
 * 底栏显示的"第几页"是【算出来的】（firstLine / linesPerPage + 1），
 * 不是存起来的 —— 单一状态源的好处就在这里。
 *
 * ══ 为什么测量一次就够 ═════════════════════════════════════════
 * 文本换行是【贪心】的：从左到右填，填不下才换行。
 * 所以只要从某个【行首】开始渲染，后面的换行位置和整章渲染时完全一致。
 * 于是测量一次就能得到所有行的边界，任意位置都能直接用 substring 取出来，
 * 不需要逐屏反复测量。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun PagedReader(
    body: String,
    settings: ReaderSettings,
    currentOffset: Int,
    onOffsetChange: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPageInfoChange: (current: Int, total: Int) -> Unit,
    onToggleMode: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()

    // 【关键】测量用的 style 必须和渲染用的 style【完全一致】，
    // 否则测出来的行数和实际排版的行数不一样，位置就会错位。
    // 所以这里抽成一个变量，两处共用。
    val textStyle = remember(settings.fontSizeSp, settings.lineHeightSp) {
        TextStyle(
            fontSize = settings.fontSizeSp.sp,
            lineHeight = settings.lineHeightSp.sp,
        )
    }

    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    // 测量整章。只在正文 / 样式 / 可用尺寸变化时重算。
    val layout = remember(body, textStyle, areaSize) {
        if (areaSize.width <= 0 || body.isEmpty()) {
            null
        } else {
            measurer.measure(
                text = body,
                style = textStyle,
                // 宽度限制成实际可用宽度，高度不限制（Infinity）——
                // 我们要测的是"整章有多少行"，不是"屏幕能放多少行"。
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

    // 总页数（只用于底栏显示）。这里复用 Paginator ——
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
    // 用 remember(body) 让它在【切章】时重置为"未定位"，
    // 等测量完成后再根据 currentOffset 重新定位。
    var firstLine by remember(body) { mutableIntStateOf(LINE_UNSET) }

    val scope = rememberCoroutineScope()
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    // ══ 长按连续滚动 ════════════════════════════════════════
    // 按下后先等 LONG_PRESS_DELAY_MS，如果这期间用户松手了，
    // 协程会被 stopAutoScroll() 取消，于是不会发生连续滚动 —— 这就是"短按"。
    // 如果没松手，就每隔 AUTO_SCROLL_INTERVAL_MS 滚一行 —— 这就是"长按"。
    fun startAutoScroll(delta: Int) {
        stopAutoScroll()
        autoScrollJob = scope.launch {
            delay(LONG_PRESS_DELAY_MS)
            while (isActive) {
                val next = firstLine + delta
                // 滚到边界就停下来。不在这里跳章 ——
                // 长按一路滚到章末时突然跳章会让人失去位置感，
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

    // 测量就绪后（或切章后）定位到 currentOffset 所在的行
    LaunchedEffect(layout, body) {
        if (firstLine == LINE_UNSET && layout != null && layout.lineCount > 0) {
            val target = currentOffset.coerceIn(0, body.length)
            firstLine = layout.getLineForOffset(target).coerceIn(0, layout.lineCount - 1)
        }
    }

    // 位置变化 → 报给外层保存进度。
    // 注意报的是【这一屏第一行的起始字符位置】，和滚动模式用的是同一套坐标。
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
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
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

        // 还没测量完（或还没定位）时，方向键先不处理 ——
        // 否则会因为边界值判断错误而误跳章。
        if (firstLine == LINE_UNSET || lineCount == 0) {
            return when (event.key) {
                Key.DirectionCenter, Key.Enter -> { onToggleMode(); true }
                Key.Back -> { onBack(); true }
                else -> false
            }
        }

        // 按住不放产生的重复事件：交给已经在跑的自动滚动协程，这里什么都不做
        if (isRepeat && isVertical) return true

        return when (event.key) {
            // ── 上下：行级滚动 ──────────────────────────────
            Key.DirectionDown -> {
                if (firstLine < lineCount - 1) {
                    firstLine += 1
                    startAutoScroll(+1)
                } else {
                    onNextChapter()
                }
                true
            }

            Key.DirectionUp -> {
                if (firstLine > 0) {
                    firstLine -= 1
                    startAutoScroll(-1)
                } else {
                    onPrevChapter()
                }
                true
            }

            // ── 左右：整屏翻页 ──────────────────────────────
            Key.DirectionRight -> {
                val next = firstLine + linesPerPage
                if (next < lineCount) firstLine = next else onNextChapter()
                true
            }

            Key.DirectionLeft -> {
                val prev = firstLine - linesPerPage
                if (prev >= 0) firstLine = prev else onPrevChapter()
                true
            }

            Key.DirectionCenter, Key.Enter -> { onToggleMode(); true }
            Key.Back -> { onBack(); true }

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
            .focusable()
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

/** 滚动模式里的一个段落，带它在章内的起始下标 */
private data class Paragraph(val text: String, val startOffset: Int)

/**
 * 上下连续阅读。
 *
 * ══ 为什么用 LazyColumn 而不是一个大 Text 加垂直滚动 ══════════════
 * 一章可能有几千行。如果用一个 Text 一次性渲染，
 * Compose 要在内存里为整章创建排版对象，还要把它全部画出来 ——
 * 在电视这种低配设备上会明显卡顿，滚动时掉帧。
 *
 * LazyColumn 只组合【屏幕上可见】的那几段，滚动时动态创建和回收。
 * 这是列表性能的关键，和书架页用 LazyVerticalGrid 是同一个道理。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun ScrollReader(
    body: String,
    settings: ReaderSettings,
    initialOffset: Int,
    onOffsetChange: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToggleMode: () -> Unit,
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
     * 用行高（sp）换算成像素，这样它和字号设置是联动的 ——
     * 用户把字号调大，滚动的"一行"也会跟着变大，视觉上始终是一行。
     */
    val lineStep = with(density) { settings.lineHeightSp.sp.toPx() }

    // 首次进入时，滚动到进度记录的位置。
    // 用 indexOfLast（最后一个起始位置 <= 目标偏移的段落）找最接近的那一段。
    LaunchedEffect(paragraphs, initialOffset) {
        if (paragraphs.isEmpty()) return@LaunchedEffect
        val target = paragraphs.indexOfLast { it.startOffset <= initialOffset }
        listState.scrollToItem(if (target < 0) 0 else target)
    }

    // 把"当前看到的第一个段落"报给外层，用来记录阅读进度。
    // snapshotFlow 是 Compose 提供的机制：把"状态读取"变成一个可观察的数据流，
    // 这样就能在非 Composable 的协程里监听状态变化。
    LaunchedEffect(listState, paragraphs) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            paragraphs.getOrNull(index)?.let { onOffsetChange(it.startOffset) }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
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
                // 已经在最顶端时按上键 → 认为用户想回上一章。
                // 这是"滚到边界自动跳章"的落地：不给它一个出口的话，
                // 用户滚到章节开头就会觉得卡住了。
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    onPrevChapter()
                } else {
                    scope.launch { listState.scrollBy(-lineStep) }
                    startAutoScroll(-lineStep)
                }
                true
            }

            // ── 左右：跳章 ──────────────────────────────────
            Key.DirectionLeft -> { onPrevChapter(); true }
            Key.DirectionRight -> { onNextChapter(); true }

            Key.DirectionCenter, Key.Enter -> { onToggleMode(); true }
            Key.Back -> { onBack(); true }

            else -> false
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onKeyEvent { handleKey(it) }
            .focusable()
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
    chapterIndex: Int,
    chapterCount: Int,
    chapterTitle: String,
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
            text = "第 ${chapterIndex + 1}/$chapterCount 章 · $chapterTitle",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${mode.displayName}模式 · 上下滚动 · 左右翻${if (mode == ReaderMode.Paged) "页" else "章"} · 确定键切换",
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
            text = "本章 $progressPercent%",
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
        // 回书架用返回键（顶栏有提示）。
        Text(
            text = "这本书没有可显示的内容。\n按返回键回书架。",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
