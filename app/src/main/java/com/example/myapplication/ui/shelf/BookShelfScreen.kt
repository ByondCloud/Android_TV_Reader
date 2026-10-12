package com.example.myapplication.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.model.Book
import com.example.myapplication.ui.components.TvButton
import com.example.myapplication.ui.components.TvCard
import com.example.myapplication.ui.components.tvFocusable
import com.example.myapplication.ui.theme.ReaderFocus
import com.example.myapplication.ui.theme.ReaderSurface

/** 危险操作（删除）用的红色。只在书架页用，所以不放进全局配色表 */
private val DangerRed = Color(0xFFE06A6A)

/**
 * 书架页。
 *
 * ══ 设计原则：这个 Composable 是【无状态】的 ═══════════════════
 * 它不自己去读数据库，也不自己保存业务状态 ——
 * 数据从参数进来，用户操作通过回调出去。
 * 这叫 "state hoisting"（状态提升），是 Compose 的核心设计模式。
 *
 * 唯一例外是"哪个面板打开着"这类**纯界面状态**，那属于本页自己管。
 * ═══════════════════════════════════════════════════════════════
 *
 * ══ 设置键的交互流程 ═══════════════════════════════════════════
 *   1. 用方向键把焦点移到某本书上
 *   2. 按【设置键 / 菜单键】→ 弹出这本书的操作菜单
 *   3. 选「修改书名」→ 弹出输入框（会调起电视上的输入法）
 *      选「删除这本书」→ 弹出确认框（默认焦点在"取消"上，防误删）
 *   4. 返回键逐层关闭
 *
 * 【为什么用"先聚焦再按设置键"而不是每张卡片都放按钮】
 * 电视屏幕就这么大，每张卡片上塞按钮会又挤又难看，
 * 而且按钮会参与焦点遍历，方向键移动时要在按钮之间绕，很烦。
 * "聚焦 + 设置键"是最省地方、也最符合电视习惯的做法。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun BookShelfScreen(
    books: List<Book>,
    isLoading: Boolean,
    emptyHint: String,
    onOpenBook: (Book) -> Unit,
    onImportClick: () -> Unit,
    onRenameBook: (Book, String) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onDeleteBooks: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    /** 当前有焦点的书。设置键要对它生效 */
    var focusedBook by remember { mutableStateOf<Book?>(null) }

    /** 操作菜单针对的书 */
    var menuBook by remember { mutableStateOf<Book?>(null) }

    /** 正在改名的书 */
    var renamingBook by remember { mutableStateOf<Book?>(null) }

    /** 正在确认删除的书 */
    var deletingBook by remember { mutableStateOf<Book?>(null) }

    // ══ 批量删除模式 ════════════════════════════════════════
    /** 是否处于批量勾选模式 */
    var batchMode by remember { mutableStateOf(false) }

    /** 已勾选的书 id。用 Set 而不是 List：判重和增删都是 O(1) */
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val panelOpen = menuBook != null || renamingBook != null || deletingBook != null

    // 顶部两个按钮的焦点引用。
    // 长按上键要能一步跳回【批量删除】—— 书多的时候，
    // 从网格底部一格格往上按可能要按十几下。
    val importRequester = remember { FocusRequester() }
    val batchRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 【按键处理放在根容器上】
            // Compose 的按键事件会从焦点元素向上冒泡。
            // 卡片只消费"确定键"，设置键它不关心，于是冒泡到这里被我们接住。
            // 这样就不需要给每张卡片都写一遍按键逻辑。
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                // 批量模式下按返回键退出（并清空勾选）
                if (batchMode && event.key == Key.Back) {
                    batchMode = false
                    selectedIds = emptySet()
                    return@onKeyEvent true
                }

                if ((event.key == Key.Settings || event.key == Key.Menu) &&
                    !panelOpen && !batchMode
                ) {
                    val target = focusedBook
                    if (target != null) {
                        menuBook = target
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            // ══ 【长按上键 → 跳到顶部的「批量删除」按钮】══════════════
            // 短按必须【放行】给焦点系统，否则焦点根本不会移动；
            // 长按（系统按住时会重复发送 KeyDown 并递增 repeatCount）才拦截。
            //
            // 【为什么用 onPreviewKeyEvent 而不是 onKeyEvent】
            // preview 是下发阶段，我们在焦点系统之前看到事件，
            // 才能"选择性拦截"；用 onKeyEvent（冒泡阶段）的话，
            // 短按还没轮到我们就已经被列表消费了。
            //
            // 【为什么不再判断 batchMode】
            // 这个按钮现在常驻在顶部，所以任何模式下长按上跳过去都有意义。
            // ═══════════════════════════════════════════════════════
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.repeatCount <= 0) return@onPreviewKeyEvent false

                if (event.key == Key.DirectionUp) {
                    runCatching { batchRequester.requestFocus() }
                    true
                } else {
                    false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            ShelfHeader(
                count = books.size,
                enabled = !panelOpen,
                batchMode = batchMode,
                selectedCount = selectedIds.size,
                onImportClick = onImportClick,
                onBatchClick = {
                    if (batchMode) {
                        // 已经是批量模式 → 这个按钮就是"执行删除"
                        if (selectedIds.isNotEmpty()) {
                            val ids = selectedIds.toList()
                            batchMode = false
                            selectedIds = emptySet()
                            onDeleteBooks(ids)
                        }
                    } else {
                        // 普通模式 → 进入批量勾选
                        selectedIds = emptySet()
                        batchMode = true
                    }
                },
                importRequester = importRequester,
                batchRequester = batchRequester,
            )

            Spacer(Modifier.height(20.dp))

            when {
                isLoading -> CenteredMessage("正在读取书库…")
                books.isEmpty() -> CenteredMessage(emptyHint)
                else -> BookGrid(
                    books = books,
                    enabled = !panelOpen,
                    batchMode = batchMode,
                    selectedIds = selectedIds,
                    onOpenBook = onOpenBook,
                    onToggleSelect = { book ->
                        selectedIds = if (book.id in selectedIds) {
                            selectedIds - book.id
                        } else {
                            selectedIds + book.id
                        }
                    },
                    onFocusBook = { focusedBook = it },
                )
            }
        }

        // ── 批量模式：左下角的纯文字提示 ────────────────────
        // 【注意这里没有按钮】
        // 执行删除的按钮是顶部那个常驻按钮（批量模式下它变成
        // "删除选中的 N 本"）。这里只负责告诉用户怎么操作。
        if (batchMode) {
            BatchHint(
                selectedCount = selectedIds.size,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        // ── 操作菜单 ────────────────────────────────────────
        menuBook?.let { book ->
            BookActionPanel(
                book = book,
                onRename = {
                    menuBook = null
                    renamingBook = book
                },
                onDelete = {
                    menuBook = null
                    deletingBook = book
                },
                onBatchDelete = {
                    menuBook = null
                    selectedIds = emptySet()
                    batchMode = true
                },
                onDismiss = { menuBook = null },
            )
        }

        // ── 修改书名 ────────────────────────────────────────
        renamingBook?.let { book ->
            RenamePanel(
                book = book,
                onConfirm = { newTitle ->
                    onRenameBook(book, newTitle)
                    renamingBook = null
                },
                onDismiss = { renamingBook = null },
            )
        }

        // ── 删除确认 ────────────────────────────────────────
        deletingBook?.let { book ->
            DeleteConfirmPanel(
                book = book,
                onConfirm = {
                    onDeleteBook(book)
                    deletingBook = null
                },
                onDismiss = { deletingBook = null },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  书架主体
// ══════════════════════════════════════════════════════════════════

@Composable
private fun ShelfHeader(
    count: Int,
    enabled: Boolean,
    batchMode: Boolean,
    selectedCount: Int,
    onImportClick: () -> Unit,
    onBatchClick: () -> Unit,
    importRequester: FocusRequester,
    batchRequester: FocusRequester,
) {
    // ══ 空书架时的初始焦点 ══════════════════════════════════════
    // 书架为空时没书可删，【批量删除】是禁用状态、也拿不到焦点，
    // 所以焦点必须给「导入书籍」——
    // 否则页面上没有任何元素被选中，用户按方向键毫无反应，
    // 看起来跟 App 卡死了没区别。
    LaunchedEffect(count, enabled, batchMode) {
        if (count == 0 && enabled && !batchMode) {
            runCatching { importRequester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "我的书架",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "共 $count 本",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.weight(1f))

        // ══ 【批量删除】按钮：常驻，位置在【导入书籍】左边 ═════════
        // 【一个按钮两种身份】
        //   普通模式：显示"批量删除"，按了进入勾选模式
        //   勾选模式：显示"删除选中的 N 本"，按了真的删
        //
        // 这样页面右下角就不需要再放执行按钮了 ——
        // 少一个按钮、少一处焦点死角，用户也不用记"删除在哪"。
        // 和导入页的「批量导入」按钮是完全一样的思路。
        //
        // 【为什么一本没选时禁用】
        // 空删除没有意义，而且会让用户以为"我按了怎么没反应"。
        // 灰掉的按钮本身就在说"现在还不能按"。
        // ═══════════════════════════════════════════════════════════
        TvButton(
            text = if (batchMode) "删除选中的 $selectedCount 本" else "批量删除",
            onClick = onBatchClick,
            enabled = enabled && if (batchMode) selectedCount > 0 else count > 0,
            modifier = Modifier.focusRequester(batchRequester),
        )

        Spacer(Modifier.width(12.dp))

        TvButton(
            text = "导入书籍",
            onClick = onImportClick,
            enabled = enabled,
            modifier = Modifier.focusRequester(importRequester),
        )
    }
}

/**
 * 图书网格。
 *
 * 【为什么只有 3 列】
 * TV 的逻辑画布固定是 960×540 dp。3 列时每张卡片约 290dp 宽，
 * 书名一行能放下 10~14 个汉字，是"看得清"和"一屏放得下"的平衡点。
 */
@Composable
private fun BookGrid(
    books: List<Book>,
    enabled: Boolean,
    batchMode: Boolean,
    selectedIds: Set<String>,
    onOpenBook: (Book) -> Unit,
    onToggleSelect: (Book) -> Unit,
    onFocusBook: (Book) -> Unit,
) {
    val firstCard = remember { FocusRequester() }

    // 【TV 必备】主动设置初始焦点。
    // 电视上如果页面上没有任何元素被选中，用户按方向键【毫无反应】。
    LaunchedEffect(books.isNotEmpty(), enabled) {
        if (books.isNotEmpty() && enabled) {
            runCatching { firstCard.requestFocus() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
            BookCard(
                book = book,
                enabled = enabled,
                batchMode = batchMode,
                selected = book.id in selectedIds,
                // 批量模式下"确定键"变成"勾选/取消勾选"，而不是打开这本书
                onClick = { if (batchMode) onToggleSelect(book) else onOpenBook(book) },
                onFocus = { onFocusBook(book) },
                // 只给第一张卡片挂 FocusRequester
                //
                // ══ 【这个 padding 是给"聚焦放大"留空间的】══════════════
                // 聚焦时卡片会 scale(1.05f) 放大，而这是【绘制变换】，
                // 不参与布局测量 —— 也就是说放大后的部分会超出格子边界。
                // 网格会把它裁掉，表现就是"黄色焦点框的顶部被上面的内容切掉了"。
                //
                // 给格子留 8dp 内边距，放大后（水平约 7.6dp、垂直约 3.3dp）
                // 就仍然落在格子内部，不会被裁。
                // ═══════════════════════════════════════════════════════
                modifier = (if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                    .padding(8.dp),
            )
        }
    }
}

/** 书架上的一本书 */
@Composable
private fun BookCard(
    book: Book,
    enabled: Boolean,
    batchMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 外层 Box 是为了把"勾选框"叠在卡片右上角。
    // 注意 focusRequester / onFocusChanged 都挂在 TvCard 上（它才是可聚焦的元素），
    // 不是挂在这个 Box 上 —— 挂在不可聚焦的容器上是不起作用的。
    Box {
        TvCard(
            onClick = onClick,
            enabled = enabled,
            // defaultMinSize 保证即使书名很短，卡片也是统一高度的矩形，
            // 否则网格会变得参差不齐。
            modifier = modifier
                // ══ 【fillMaxWidth 是必需的】══════════════════════════
                // Column 默认是 wrap content，只按文字宽度撑开（实测约 210px），
                // 而网格格子有 545px 宽。后果是卡片只占格子左边一小条，
                // 右边一大片空白。
                //
                // 这个 bug 一直存在，但没有勾选框时肉眼看不出来 ——
                // 直到我加了 align(TopEnd) 的勾选框，它直接飘到卡片外面去了。
                // 换句话说：是"勾选框位置不对"这个现象，暴露了"卡片没填满"这个根因。
                .fillMaxWidth()
                .defaultMinSize(minHeight = 132.dp)
                // 【焦点跟踪】外层再挂一个 onFocusChanged。
                // 它观察的是内层 clickable 创建的那个焦点节点，
                // 所以能正确收到"我这张卡片被选中了"的通知。
                // 设置键就是靠这个知道"当前该对哪本书动手"。
                .onFocusChanged { if (it.isFocused) onFocus() },
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = book.subtitle.ifBlank { "未知作者" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // weight(1f) 吃掉剩余空间，把下面这行进度推到卡片底部
            Spacer(Modifier.weight(1f))

            Text(
                text = if (book.progress.isStarted) {
                    // chapterIndex 从 0 开始，显示给用户要 +1
                    "续读 · 第 ${book.progress.chapterIndex + 1} 章"
                } else {
                    "未读"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (book.progress.isStarted) ReaderFocus else MaterialTheme.colorScheme.secondary,
            )
        }

        // 批量模式下才显示勾选框
        if (batchMode) {
            SelectionMark(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            )
        }
    }
}

/**
 * 右上角的勾选框。
 *
 * 【为什么不用 Material3 的 Checkbox】
 * 那个组件是为触摸屏设计的小控件（约 20dp），在电视上既看不清，
 * 它的内部焦点行为也会和卡片本身抢焦点。
 * 我们自己画一个大方块：三米外能看清，而且不参与焦点遍历
 * （它是卡片的一部分，勾选状态由卡片的确定键切换）。
 */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .size(30.dp)
            .background(
                color = if (selected) ReaderFocus else Color(0x66000000),
                shape = shape,
            )
            .border(
                width = 2.dp,
                color = if (selected) ReaderFocus else Color(0xAAFFFFFF),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.background,
            )
        }
    }
}

/**
 * 批量模式下的操作提示（左下角【纯文字，不可聚焦】）。
 *
 * 【为什么这里故意不放按钮】
 * 执行删除的是顶部那个常驻的「删除选中的 N 本」。
 * 如果这里再放一个，就变成两个按钮做同一件事 ——
 * 用户会犹豫该按哪个，焦点遍历也多一个岔路。
 *
 * 一个动作只给一个入口，是让界面变简单最有效的办法。
 */
@Composable
private fun BatchHint(selectedCount: Int, modifier: Modifier = Modifier) {
    Text(
        text = if (selectedCount > 0) {
            "已选 $selectedCount 本 · 按顶部「删除选中的 $selectedCount 本」执行 · 返回键退出"
        } else {
            "确定键勾选要删除的书 · 长按上键跳到「批量删除」按钮 · 返回键退出"
        },
        style = MaterialTheme.typography.titleSmall,
        color = if (selectedCount > 0) ReaderFocus else MaterialTheme.colorScheme.secondary,
        modifier = modifier.padding(8.dp),
    )
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(600.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════════════
//  弹出面板
// ══════════════════════════════════════════════════════════════════

/**
 * 通用弹出面板：半透明遮罩 + 居中卡片 + 返回键关闭。
 *
 * 【为什么不用 Dialog】
 * Compose 的 Dialog 会新建一个系统窗口，在 Android TV 上会带来
 * 焦点、过扫描（Overscan）、返回键归属、IME 避让等一堆额外问题。
 * 直接在当前 Compose 树里画覆盖层更可控，而且自动继承 TvSafeArea 的边距。
 */
@Composable
private fun ModalPanel(
    onDismiss: () -> Unit,
    width: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            // imePadding：输入法弹出时把面板顶上去，否则输入框会被键盘挡住。
            // 这是电视上输入文字最容易忽略的一个细节。
            .imePadding()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
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
                .width(width)
                .background(ReaderSurface, RoundedCornerShape(14.dp))
                .border(1.dp, ReaderFocus, RoundedCornerShape(14.dp))
                // ══ 【必须加垂直滚动】════════════════════════════════════
                // 电视上的软键盘会占掉半屏，配合上面的 imePadding，
                // 面板的可用高度会骤减。如果内容不能滚动，
                // 底部的东西（比如重命名面板的「保存 / 取消」按钮）
                // 就会被挤到可视区域外面 —— 我实测踩到过这个：
                // 键盘弹出时那两个按钮只剩下空边框，文字全被裁掉。
                //
                // 加滚动之后还有一层好处：Compose 的焦点系统在方向键
                // 移到屏幕外的元素时，会自动把它滚进视野（bringIntoView）。
                // 所以用户不需要手动滚动，方向键一路按下去就能看到按钮。
                // ═══════════════════════════════════════════════════════
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            content = content,
        )
    }
}

/** 操作菜单：修改书名 / 删除 / 取消 */
@Composable
private fun BookActionPanel(
    book: Book,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBatchDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstRow = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstRow.requestFocus() }
    }

    ModalPanel(onDismiss = onDismiss) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = book.subtitle.ifBlank { "未知作者" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        Spacer(Modifier.height(20.dp))

        ActionRow(
            text = "修改书名",
            onClick = onRename,
            modifier = Modifier.focusRequester(firstRow),
        )
        Spacer(Modifier.height(10.dp))
        ActionRow(text = "删除这本书", onClick = onDelete, isDanger = true)
        Spacer(Modifier.height(10.dp))
        ActionRow(text = "批量删除", onClick = onBatchDelete, isDanger = true)

        // 【这里刻意不放"取消"项】
        // 面板本来就能用返回键关闭（ModalPanel 里已经处理了），
        // 再占一行"取消"只会让用户多按一次方向键，没有实际价值。
        // 少一个选项 = 少一次按键 = 少一次犹豫。
    }
}

/**
 * 修改书名。
 *
 * ══ 关于电视上的文字输入 ═══════════════════════════════════════
 * 用 OutlinedTextField 就会调起系统输入法。这台 TV 上装的是
 * TV 版 Gboard —— 它显示为大格子键盘，专门为方向键操作设计。
 *
 * 输入方式有两种，用户用哪个都行：
 *   1. 遥控器方向键在屏幕键盘上选字母，按确定键输入
 *   2. （模拟器里）直接用电脑键盘打字
 *
 * 【为什么主动调 keyboard.show()】
 * 手机上点一下输入框，键盘会自己弹出来。但电视上不是这样 ——
 * 输入框获得焦点只显示光标，用户还得再按一次确定键才弹键盘。
 * 主动调 show() 省掉这一步，意图更明确。
 *
 * 【为什么要 imePadding】
 * 电视上的软键盘能占掉半屏。不避让的话输入框正好被挡住，
 * 用户看不见自己在打什么字。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
private fun RenamePanel(
    book: Book,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 用 book.id 作为 key：切换到另一本书改名时，输入框要重置成那本书的书名，
    // 而不是残留上一本的内容。
    var text by remember(book.id) { mutableStateOf(book.title) }

    val inputRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 「保存」按钮的焦点引用。
    // 用它把"输入框按下键"的落点【显式】指到保存按钮上 ——
    // 否则焦点系统会按几何距离就近选，实测会跳到右边的「取消」，
    // 而"保存"才是这个面板的主操作，应该最先够得着。
    val saveRequester = remember { FocusRequester() }

    val canSave = text.isNotBlank()

    fun save() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) onConfirm(trimmed)
    }

    LaunchedEffect(book.id) {
        runCatching { inputRequester.requestFocus() }
        // 电视上输入框获得焦点不会自动弹键盘，必须主动请求
        keyboard?.show()
    }

    ModalPanel(onDismiss = onDismiss, width = 680.dp) {
        Text(
            text = "修改书名",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputRequester)
                // 显式声明：这个输入框"往下走"的目标是保存按钮
                .focusProperties { down = saveRequester }
                // ══ 【必须用 onPreviewKeyEvent，不能用 onKeyEvent】══════
                // 我实测踩到过：焦点进了输入框之后，按方向键下就再也
                // 出不来了，底下的「保存 / 取消」按钮根本走不到。
                //
                // 原因有两层：
                //   1. TextField 内部会处理方向键（光标和文本导航）
                //   2. onKeyEvent 是【冒泡阶段】的回调 —— 从焦点元素向上传，
                //      内层先收到。所以 TextField 消费掉之后，外层根本收不到。
                //
                // onPreviewKeyEvent 是【下发阶段】：从根往焦点元素传，
                // 我们先于 TextField 拿到按键，才能把它改写成"移动焦点"。
                //
                // 直接返回 moveFocus 的结果：找不到可移动的目标时返回 false，
                // 事件继续正常传递，不会把按键吞掉。
                // （左右键故意不拦 —— 那是在输入框里挪光标用的，要保持。）
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                    ) {
                        focusManager.moveFocus(
                            if (event.key == Key.DirectionDown) {
                                FocusDirection.Down
                            } else {
                                FocusDirection.Up
                            }
                        )
                    } else {
                        false
                    }
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            label = { Text("书名") },
            // 键盘右下角显示"完成"，按它直接保存 —— 比用方向键挪到"保存"按钮快得多
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "遥控器：方向键选字母。模拟器：可直接用电脑键盘。" +
                "输入完按键盘上的「完成」键即可保存。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                text = "保存",
                onClick = { save() },
                enabled = canSave,
                modifier = Modifier.focusRequester(saveRequester),
            )
            ActionButton(text = "取消", onClick = onDismiss)
        }
    }
}

/**
 * 删除确认。
 *
 * 【默认焦点刻意放在「取消」上】
 * 删除是不可逆操作。如果焦点默认落在「确认删除」上，
 * 用户按设置键弹出的流程里很容易多按一下就误删了。
 * 把这个"危险按钮离手最远"的习惯用上，能省掉很多后悔。
 */
@Composable
private fun DeleteConfirmPanel(
    book: Book,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { cancelRequester.requestFocus() }
    }

    ModalPanel(onDismiss = onDismiss) {
        Text(
            text = "删除这本书？",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "《${book.title}》",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "书籍文件和阅读进度都会被永久删除，此操作无法撤销。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelRequester),
            )
            ActionButton(
                text = "确认删除",
                onClick = onConfirm,
                isDanger = true,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  面板里的小组件
// ══════════════════════════════════════════════════════════════════

/** 菜单里的一行 */
@Composable
private fun ActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (isDanger) DangerRed else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 面板底部的按钮 */
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDanger: Boolean = false,
) {
    Box(
        modifier = modifier
            .tvFocusable(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(22.dp),
            )
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.secondary
                isDanger -> DangerRed
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
