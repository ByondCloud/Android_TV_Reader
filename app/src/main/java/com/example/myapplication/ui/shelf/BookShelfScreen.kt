package com.example.myapplication.ui.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.model.Book
import com.example.myapplication.ui.components.TvButton
import com.example.myapplication.ui.components.TvCard
import com.example.myapplication.ui.theme.ReaderFocus

/**
 * 书架页。
 *
 * ══ 设计原则：这个 Composable 是【无状态】的 ═══════════════════
 * 它不自己去读数据库，也不自己保存任何业务状态 ——
 * 数据从参数进来，用户操作通过回调出去。
 *
 * 这叫 "state hoisting"（状态提升），是 Compose 的核心设计模式。
 * 好处很实在：
 *   - 它变得【可预览】：给一组假数据就能在 Android Studio 的
 *     Preview 面板里看效果，不用连数据库、不用开模拟器
 *   - 它变得【可复用】：换一个数据来源（网络、缓存）不用改这里
 *   - 它变得【可测试】：Compose 的 UI 测试可以直接喂数据
 *
 * 数据加载的活儿交给上层的 MainScreen（见 ui/MainScreen.kt）。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun BookShelfScreen(
    books: List<Book>,
    isLoading: Boolean,
    emptyHint: String,
    onOpenBook: (Book) -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {

        ShelfHeader(count = books.size, onImportClick = onImportClick)

        Spacer(Modifier.height(20.dp))

        when {
            isLoading -> CenteredMessage("正在读取书库…")
            books.isEmpty() -> CenteredMessage(emptyHint)
            else -> BookGrid(books = books, onOpenBook = onOpenBook)
        }
    }
}

/**
 * 顶部栏：标题 + 数量 + 导入按钮。
 *
 * Row 里用 `Spacer(Modifier.weight(1f))` 把"导入书籍"按钮推到最右边 ——
 * weight 是 Compose 里做"弹性占位"的标准手法，
 * 比手动算宽度可靠得多（屏幕尺寸、字号变了都不会错位）。
 */
@Composable
private fun ShelfHeader(count: Int, onImportClick: () -> Unit) {
    val importButton = remember { FocusRequester() }

    // ══ 空书架时的初始焦点 ══════════════════════════════════════
    // 【这是一个很容易漏掉的坑】
    // BookGrid 只在"有书"时才渲染，所以书架为空时，
    // 全页面只有"导入书籍"这一个可聚焦元素。
    // 如果我们不管，就没有任何元素被选中 ——
    // 用户按遥控器方向键毫无反应，看起来跟卡死一样。
    //
    // 所以：书架为空（含加载中）时，把焦点放在导入按钮上。
    LaunchedEffect(count) {
        if (count == 0) {
            runCatching { importButton.requestFocus() }
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
        TvButton(
            text = "导入书籍",
            onClick = onImportClick,
            modifier = Modifier.focusRequester(importButton),
        )
    }
}

/**
 * 图书网格。
 *
 * 【为什么用 LazyVerticalGrid 而不是自己排 Row】
 * Lazy 版本只组合屏幕上【可见】的那几个元素。
 * 你以后有 500 本书时，它们不会被一次性全部创建出来 ——
 * 这是列表性能的关键。
 *
 * 【为什么只有 3 列】
 * TV 的逻辑画布固定是 960×540 dp。3 列时每张卡片约 290dp 宽，
 * 书名一行能放下 10~14 个汉字，是"看得清"和"一屏放得下"的平衡点。
 * 4 列会显得挤，2 列则浪费横向空间。
 */
@Composable
private fun BookGrid(books: List<Book>, onOpenBook: (Book) -> Unit) {
    val firstCard = remember { FocusRequester() }

    // ══ 【TV 必备】主动设置初始焦点 ══════════════════════════════
    // 这一点和手机开发完全不同：
    //   手机上"没有焦点"无所谓，用户用手指点就行。
    //   电视上如果页面上没有任何元素被选中，用户按方向键【毫无反应】——
    //   看起来就像 App 卡死了。
    //
    // 所以每个页面出现后，都必须主动把焦点放到一个合理的元素上。
    // 这里选第一本书。
    //
    // 【为什么要 runCatching】
    // requestFocus() 在目标元素还没完成布局时会抛异常。
    // 数据刚加载完的那一帧就属于这种情况。
    // 包起来，让 Compose 的下一帧重新执行即可 —— 没必要让 App 崩掉。
    LaunchedEffect(books.isNotEmpty()) {
        if (books.isNotEmpty()) {
            runCatching { firstCard.requestFocus() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // itemsIndexed 同时给出下标和元素。
        // key 用 book.id：让 Lazy 列表在增删元素时能正确识别"同一个元素"，
        // 从而复用已有的组合，而不是把后面的全部重建。
        itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
            BookCard(
                book = book,
                onClick = { onOpenBook(book) },
                // 只给第一张卡片挂 FocusRequester
                modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
            )
        }
    }
}

/** 书架上的一本书 */
@Composable
private fun BookCard(book: Book, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvCard(
        onClick = onClick,
        // defaultMinSize 保证即使书名很短，卡片也是统一高度的矩形，
        // 否则网格会变得参差不齐。
        modifier = modifier.defaultMinSize(minHeight = 132.dp),
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

        // weight(1f) 吃掉剩余空间，把下面这行进度推到卡片底部。
        // 这样不同长度的书名也不会让进度文字上下乱跳。
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
