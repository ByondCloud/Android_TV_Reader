package com.example.myapplication.ui.importer

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
import com.example.myapplication.domain.source.SourceFile
import com.example.myapplication.domain.usecase.ImportResult
import com.example.myapplication.ui.components.TvButton
import com.example.myapplication.ui.components.tvFocusable
import com.example.myapplication.ui.theme.ReaderFocus

/**
 * 导入页。
 *
 * ══ 为什么这一页要明确告诉用户"把文件放到哪个目录" ══════════════
 * 因为 Android TV 上【没有系统文件选择器】（实测确认，见 BookSource 注释）。
 * "点击选择文件"这个在手机上理所当然的动作，在电视上根本不存在。
 *
 * 所以必须换一种交互范式，并且把它讲清楚：
 *   「你先想办法把文件放进这个目录，我负责读。」
 *
 * 不要指望用户自己猜到这一点 —— 界面上直接把完整路径写出来，
 * 他能照着用 adb push 或者 U 盘拷进去。
 * ═══════════════════════════════════════════════════════════════
 *
 * 同样是无状态设计：数据从参数进，操作通过回调出。
 */
@Composable
fun ImportScreen(
    dropDirPath: String,
    files: List<SourceFile>?,
    isImporting: Boolean,
    results: List<ImportResult>?,
    onRescan: () -> Unit,
    onImportAll: () -> Unit,
    onImportOne: (SourceFile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {

        // ── 顶部：标题 + 两个按钮 ────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "导入书籍",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            TvButton(text = "重新扫描", onClick = onRescan)
            Spacer(Modifier.width(12.dp))
            TvButton(text = "返回书架", onClick = onBack)
        }

        Spacer(Modifier.height(16.dp))

        // ── 说明：文件该放哪里 ──────────────────────────────
        Text(
            text = "把 .txt 文件放进下面这个目录，然后点「重新扫描」：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = dropDirPath,
            style = MaterialTheme.typography.bodySmall,
            color = ReaderFocus,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(16.dp))

        // ── 上一次的导入结果 ────────────────────────────────
        results?.let {
            ImportResultBanner(it)
            Spacer(Modifier.height(12.dp))
        }

        // ── 文件列表 ────────────────────────────────────────
        when {
            files == null -> CenteredMessage("正在扫描目录…")

            files.isEmpty() -> CenteredMessage(
                "这个目录里没有找到可导入的文件。\n\n" +
                    "用命令行放一个进去试试：\n" +
                    "adb push 你的小说.txt $dropDirPath/"
            )

            else -> FileList(
                files = files,
                isImporting = isImporting,
                onImportAll = onImportAll,
                onImportOne = onImportOne,
            )
        }
    }
}

/**
 * 导入结果提示条。
 *
 * 【为什么最多只列 3 条失败原因】
 * 电视屏幕就这么大。一次导入 20 本全失败的话，
 * 把 20 条原因全列出来会挤爆界面，而且用户也读不过来。
 * 列出前 3 条 + 一句"还有 N 本失败"，信息量足够，
 * 剩下的细节可以看日志。
 */
@Composable
private fun ImportResultBanner(results: List<ImportResult>) {
    val successCount = results.count { it is ImportResult.Success }
    val failures = results.filterIsInstance<ImportResult.Failure>()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildString {
                append("导入完成：成功 ")
                append(successCount)
                append(" 本")
                if (failures.isNotEmpty()) {
                    append("，失败 ")
                    append(failures.size)
                    append(" 本")
                }
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (failures.isEmpty()) ReaderFocus else MaterialTheme.colorScheme.onSurface,
        )

        failures.take(3).forEach { failure ->
            Text(
                text = "· ${failure.fileName}：${failure.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        if (failures.size > 3) {
            Text(
                text = "…… 还有 ${failures.size - 3} 本失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun FileList(
    files: List<SourceFile>,
    isImporting: Boolean,
    onImportAll: () -> Unit,
    onImportOne: (SourceFile) -> Unit,
) {
    val firstRow = remember { FocusRequester() }

    // 和书架页同样的道理：进页面必须主动给焦点，否则遥控器按了没反应
    LaunchedEffect(files.isNotEmpty()) {
        if (files.isNotEmpty()) {
            runCatching { firstRow.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "找到 ${files.size} 个文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(16.dp))
            TvButton(
                text = if (isImporting) "正在导入…" else "全部导入",
                // 导入过程中禁止重复点击 —— 否则会并发写同一个目录。
                // 【注意】更好的做法是在按钮上显示一个"禁用"的视觉状态，
                // 这里先用最简单的"点了也不响应"。
                onClick = { if (!isImporting) onImportAll() },
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(files, key = { _, f -> f.file.absolutePath }) { index, file ->
                FileRow(
                    file = file,
                    onClick = { if (!isImporting) onImportOne(file) },
                    modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                )
            }
        }
    }
}

/**
 * 一行文件。
 *
 * 注意这里直接用了 [tvFocusable] 而不是 [com.example.myapplication.ui.components.TvCard]——
 * 因为这一行是"横向铺满的一整条"，而不是"一张卡片"，
 * 自己控制内边距和内部布局更合适。
 * 这正说明把焦点逻辑抽成【修饰符】比抽成【组件】更灵活：
 * 修饰符可以被任意形状复用。
 */
@Composable
private fun FileRow(file: SourceFile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = file.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = file.readableSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(700.dp),
        )
    }
}
