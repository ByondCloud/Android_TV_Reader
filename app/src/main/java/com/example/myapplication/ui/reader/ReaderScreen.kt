package com.example.myapplication.ui.reader

import androidx.compose.foundation.focusable
// 【注意】scrollBy / animateScrollBy 都是 ScrollableState 的【扩展函数】，
// 不是成员方法，所以必须显式 import，否则会报 Unresolved reference。
// 这是 Compose 里很常见的坑：方法看起来"应该"在对象上，其实是扩展。
import androidx.compose.foundation.gestures.animateScrollBy
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
import com.example.myapplication.domain.reader.PageRange
import com.example.myapplication.domain.reader.Paginator
import com.example.myapplication.domain.reader.ReaderMode
import com.example.myapplication.domain.reader.ReaderSettings
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 阅读页 —— 支持「翻页」和「上下连续滚动」两种模式。
 *
 * ══ 遥控器按键约定（两种模式统一）═══════════════════════════════
 *
 *                   翻页模式              滚动模式
 *   ←  /  →     上一页 / 下一页        上一章 / 下一章
 *   ↑  /  ↓     上一章 / 下一章        向上滚 / 向下滚
 *   确定键       切换阅读模式           切换阅读模式
 *   返回键       回到书架                 回到书架
 *
 * 【为什么翻页用左右、滚动用上下】
 * 因为要贴合用户的直觉：翻页是"横向的"动作（像翻书页），
 * 连续阅读是"纵向的"动作（像卷轴）。如果反过来，
 * 用户每次按方向键都要想一想，这种微小的别扭累积起来很影响体验。
 *
 * 【为什么翻到章节边界时自动跳到下一章】
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
    // 【单一状态源原则】"当前在第几页"不单独存，
    // 而是从 currentOffset 推导出来。这样改字号、切模式时
    // 位置能自动重新映射，不会出现"页码和实际内容对不上"。
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
     * @param toEnd 往前翻（上一章）时要停在最后一页，所以先塞一个极大的偏移，
     *              由 PagedReader 把它映射成"最后一页"。
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
    // 上面为了表示"跳到这里章末尾"用了 Int.MAX_VALUE 作为哨兵值。
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
 * ══ 分页是怎么做出来的（三步）═══════════════════════════════════
 *   ① 用 TextMeasurer 把整章测量一次，得到每一行的起止字符下标
 *   ② 用 Paginator 把行按"每页能放多少行"分组，得到每页的字符范围
 *   ③ 渲染时直接 body.substring(页起始, 页结束)
 *
 * ══ 为什么测量一次就够，不需要逐页测量 ═════════════════════════
 * 文本换行是【贪心】的：从左到右填，填不下才换行。
 * 所以只要从某个【行首】开始渲染，后面的换行位置和整章渲染时完全一致。
 * 换言之，"第 N 行到第 M 行"这段文字，单独拿出来渲染时，
 * 排版结果和它在整章里时一模一样 —— 不会因为少看了前面的内容而变样。
 *
 * 这条性质让分页变成了纯粹的算术，也就成了可以被单元测试覆盖的逻辑
 * （见 PaginatorTest）。
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
    val density = LocalDensity.current

    // 【关键】测量用的 style 必须和渲染用的 style【完全一致】，
    // 否则测出来的行数和实际排版的行数不一样，分页就会错位。
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

    val pages: List<PageRange> = remember(layout, areaSize.height, body) {
        if (layout == null || areaSize.height <= 0) {
            emptyList()
        } else {
            // ══ 数出"一屏到底能放几行" ═══════════════════════════════
            // 【不要用「可用高度 ÷ 行高」这种估算】
            // 它会把行数多算半行到一行，结果是每页的最后一行被屏幕底部裁掉。
            // 我第一版就是这么写的，截图里能看到正文的最后一行是断的 ——
            // 而且这种 bug 很隐蔽：大部分页看起来正常，只有底部那一行出问题。
            //
            // 正确做法：直接用测量结果里【每一行的实际底部像素位置】判断。
            // 第 i 行能放下  <=>  getLineBottom(i) <= 可用高度。
            // 这样用的是真实排版数据，不是估算。
            //
            // 循环最多跑 linesPerPage + 1 次就退出，
            // 不会因为章节很长而变慢（不是遍历所有行）。
            var linesPerPage = 0
            while (linesPerPage < layout.lineCount &&
                layout.getLineBottom(linesPerPage) <= areaSize.height.toFloat()
            ) {
                linesPerPage++
            }
            linesPerPage = max(1, linesPerPage)
            Paginator.paginate(
                // 注意用 getLineEnd(it) 的默认版本（visibleEnd = false），
                // 这样行尾【包含换行符】，相邻两页首尾严格相接，一个字符都不漏。
                lineStarts = IntArray(layout.lineCount) { layout.getLineStart(it) },
                lineEnds = IntArray(layout.lineCount) { layout.getLineEnd(it) },
                linesPerPage = linesPerPage,
                textLength = body.length,
            )
        }
    }

    // 从"字符偏移"推导出"第几页"。
    // 这就是"单一状态源"的好处：位置只存偏移，页码是算出来的。
    val pageIndex = if (pages.isEmpty()) {
        0
    } else {
        // 找第一个"结束位置在偏移之后"的页，那就是当前页。
        // 找不到（偏移超出末尾，例如切上一章时的哨兵值）就落到最后一页。
        pages.indexOfFirst { currentOffset < it.endOffset }
            .let { if (it < 0) pages.lastIndex else it }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // 把当前页信息报给外层（底栏显示），并顺手把哨兵偏移纠正成真实值。
    LaunchedEffect(pageIndex, pages) {
        pages.getOrNull(pageIndex)?.let { page ->
            onPageInfoChange(pageIndex + 1, pages.size)
            if (currentOffset != page.startOffset) {
                onOffsetChange(page.startOffset)
            }
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false

        // 还没测量完（pages 为空）时什么都不做 ——
        // 否则按右键会因为 lastIndex = -1 而误判成"翻到章末"，直接跳章。
        if (pages.isEmpty()) {
            return when (event.key) {
                Key.DirectionLeft -> { onPrevChapter(); true }
                Key.DirectionRight, Key.DirectionDown -> { onNextChapter(); true }
                Key.DirectionUp -> { onPrevChapter(); true }
                Key.DirectionCenter, Key.Enter -> { onToggleMode(); true }
                Key.Back -> { onBack(); true }
                else -> false
            }
        }

        return when (event.key) {
            Key.DirectionLeft -> {
                if (pageIndex > 0) onOffsetChange(pages[pageIndex - 1].startOffset)
                else onPrevChapter()          // 已在第一页 → 翻到上一章
                true
            }

            Key.DirectionRight -> {
                if (pageIndex < pages.lastIndex) onOffsetChange(pages[pageIndex + 1].startOffset)
                else onNextChapter()          // 已在最后一页 → 进入下一章
                true
            }

            // 上下键留给"跳章"，让用户不必翻完几十页才能换章
            Key.DirectionUp -> { onPrevChapter(); true }
            Key.DirectionDown -> { onNextChapter(); true }

            Key.DirectionCenter, Key.Enter -> { onToggleMode(); true }
            Key.Back -> { onBack(); true }

            else -> false
        }
    }

    Box(
        modifier = modifier
            // 量出实际可用的文本区域尺寸。它决定了每页能放多少行。
            .onSizeChanged { areaSize = it }
            .focusRequester(focusRequester)
            // 【修饰符顺序】onKeyEvent 必须写在 focusable 之前（外层），
            // 这样按键事件才能从焦点节点冒泡到我们的处理函数。
            .onKeyEvent { handleKey(it) }
            .focusable()
    ) {
        val page = pages.getOrNull(pageIndex)
        if (page != null) {
            Text(
                text = body.substring(page.startOffset, page.endOffset),
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
 *
 * ══ 为什么按段落而不是按行拆 ═══════════════════════════════════
 * 每个 item 是一个段落（原文里的一行），这样：
 *   - item 数量合理（一章通常几十到几百段）
 *   - 每段单独排版，改字号时只影响自己
 *   - 段落是中文小说的天然单位，缩进也保留得完好
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

    /** 每次按上下键滚动的距离。大约 3 行文字，让阅读有连续性。 */
    val scrollStep = with(density) { (settings.lineHeightSp * 3).dp.toPx() }

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

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            // 上下键滚动。用 animateScrollBy 而不是 scrollBy，
            // 滚动会有个短促的缓动，比生硬地跳一屏舒服得多。
            Key.DirectionDown -> {
                scope.launch { listState.animateScrollBy(scrollStep) }
                true
            }
            Key.DirectionUp -> {
                // 已经在顶部时按上键 → 认为用户想回上一章
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    onPrevChapter()
                } else {
                    scope.launch { listState.animateScrollBy(-scrollStep) }
                }
                true
            }

            // 左右键跳章
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

            // 末尾留一块空白，这样最后一段也能滚到屏幕中间，
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
            text = "${mode.displayName}模式 · 确定键切换 · 返回键回书架",
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
