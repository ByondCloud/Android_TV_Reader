package com.example.myapplication.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.source.SourceFile
import com.example.myapplication.domain.usecase.ImportResult
import com.example.myapplication.ui.components.TvButton
import com.example.myapplication.ui.components.tvFocusable

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
 * ══ 三种导入方式 ═══════════════════════════════════════════════
 *   ① 直接点列表里的某一行 → 只导入那一本，然后回书架
 *   ② 【全部导入】         → 把扫到的全导进去，然后回书架
 *   ③ 【批量导入】         → 列表每行前面出现勾选框，
 *                            自己挑几本，再按一次【批量导入】执行
 *
 * 【为什么有了「全部导入」还要「批量导入」】
 * 因为"全导"和"挑几本导"是两个不同的意图，中间没有渐变：
 * 用户要么懒得挑，要么就是想跳过其中几本。
 * 让"全部导入"一步到位、让"批量导入"进入挑选模式，
 * 比只给一个按钮再弹窗问"确定全部导入吗"要少两步。
 * ═══════════════════════════════════════════════════════════════
 *
 * 无状态设计：数据从参数进，操作通过回调出。
 * 唯一自己管的是"批量模式开着没""勾了哪几行"这类纯界面状态。
 */
@Composable
fun ImportScreen(
    dropDirPath: String,
    files: List<SourceFile>?,
    /** 已经在书架上的来源文件名。用来在列表里标出"已导入" */
    importedNames: Set<String>,
    isImporting: Boolean,
    results: List<ImportResult>?,
    onRescan: () -> Unit,
    onImportAll: () -> Unit,
    onImportBatch: (List<SourceFile>) -> Unit,
    onImportOne: (SourceFile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ── 批量挑选模式的状态 ──────────────────────────────────
    var batchMode by remember { mutableStateOf(false) }

    /** 勾选的文件，用绝对路径做 key（文件名同一目录下唯一，但路径更保险） */
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    val allFiles = files.orEmpty()

    // 已导入的不参与勾选 —— 免得用户白勾一场
    val selectable = remember(allFiles, importedNames) {
        allFiles.filter { it.displayName !in importedNames }
    }
    val selectedFiles = allFiles.filter { it.file.absolutePath in selectedPaths }

    // 【批量导入】按钮的焦点引用。
    // 批量模式下长按上键要能一步跳回来 —— 用户挑完书之后，
    // 焦点在列表深处，一格格往回按可能要按十几下。
    val batchButtonRequester = remember { FocusRequester() }

    /** 退出批量模式并清空勾选 */
    fun exitBatch() {
        batchMode = false
        selectedPaths = emptySet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // ══ 【长按上键 → 跳回「批量导入」按钮】══════════════════
            // 和书架页是同一套做法：
            //   短按（repeatCount == 0）返回 false 放行给焦点系统，
            //   焦点才会正常逐行移动；
            //   长按（repeatCount > 0，系统重复发送）才拦截并跳转。
            //
            // 用 onPreviewKeyEvent 而不是 onKeyEvent ——
            // 必须在焦点系统之前看到事件，否则短按会被列表吞掉。
            // ═══════════════════════════════════════════════════════
            .onPreviewKeyEvent { event ->
                if (!batchMode || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                if (event.nativeKeyEvent.repeatCount <= 0) {
                    return@onPreviewKeyEvent false
                }
                if (event.key == Key.DirectionUp) {
                    runCatching { batchButtonRequester.requestFocus() }
                    true
                } else {
                    false
                }
            }
    ) {

        // ── 顶部：标题 + 两个导航按钮 ────────────────────────
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
            TvButton(
                text = "返回书架",
                onClick = {
                    exitBatch()
                    onBack()
                },
            )
        }

        Spacer(Modifier.height(12.dp))

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
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))

        // ── 上一次的导入结果 ────────────────────────────────
        results?.let {
            ImportResultBanner(it)
            Spacer(Modifier.height(10.dp))
        }

        // ── 操作栏：全部导入 / 批量导入 ──────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton(
                text = if (isImporting) "正在导入…" else "全部导入",
                // 导入过程中禁止重复点击 —— 否则会并发写同一个目录
                onClick = { if (!isImporting) onImportAll() },
            )
            Spacer(Modifier.width(12.dp))

            // 【这一个按钮有两种身份，靠 batchMode 切换】
            //   普通模式：进入挑选模式
            //   挑选模式：执行导入
            // 这样"长按上跳回批量导入按钮"才有意义 ——
            // 跳过去之后按确定就是执行，不用再找别的按钮。
            TvButton(
                text = when {
                    isImporting -> "正在导入…"
                    batchMode -> "导入选中的 ${selectedFiles.size} 本"
                    else -> "批量导入"
                },
                onClick = {
                    if (isImporting) return@TvButton
                    if (batchMode) {
                        if (selectedFiles.isNotEmpty()) {
                            val picked = selectedFiles
                            exitBatch()
                            onImportBatch(picked)
                        }
                    } else {
                        selectedPaths = emptySet()
                        batchMode = true
                    }
                },
                modifier = Modifier.focusRequester(batchButtonRequester),
            )

            if (batchMode) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "确定键勾选 · 长按上键回到本按钮 · 返回键退出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            Spacer(Modifier.weight(1f))
            Text(
                text = "找到 ${allFiles.size} 个文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 文件列表 ────────────────────────────────────────
        when {
            files == null -> CenteredMessage("正在扫描目录…")

            files.isEmpty() -> CenteredMessage(
                "这个目录里没有找到可导入的文件。\n\n" +
                    "用命令行放一个进去试试：\n" +
                    "adb push 你的小说.txt $dropDirPath/"
            )

            else -> FileList(
                files = allFiles,
                importedNames = importedNames,
                batchMode = batchMode,
                selectedPaths = selectedPaths,
                isImporting = isImporting,
                onToggleSelect = { file ->
                    val key = file.file.absolutePath
                    selectedPaths = if (key in selectedPaths) {
                        selectedPaths - key
                    } else {
                        selectedPaths + key
                    }
                },
                onImportOne = { file ->
                    // 已导入的不再重复导 —— 直接不响应，
                    // 行上已经写了"已导入"，用户能明白为什么没反应
                    if (!isImporting && file.displayName !in importedNames) {
                        onImportOne(file)
                    }
                },
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
    val duplicates = results.filterIsInstance<ImportResult.Duplicate>()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildString {
                append("导入完成：成功 ")
                append(successCount)
                append(" 本")
                if (duplicates.isNotEmpty()) {
                    append("，已在书架 ")
                    append(duplicates.size)
                    append(" 本")
                }
                if (failures.isNotEmpty()) {
                    append("，失败 ")
                    append(failures.size)
                    append(" 本")
                }
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (failures.isEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )

        // 「已在书架」用普通颜色而不是红色 —— 它不是错误，别吓用户
        duplicates.take(2).forEach { duplicate ->
            Text(
                text = "· ${duplicate.fileName}：书架上已有《${duplicate.existingTitle}》",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

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
    importedNames: Set<String>,
    batchMode: Boolean,
    selectedPaths: Set<String>,
    isImporting: Boolean,
    onToggleSelect: (SourceFile) -> Unit,
    onImportOne: (SourceFile) -> Unit,
) {
    val firstRow = remember { FocusRequester() }

    // 进页面必须主动给焦点，否则遥控器按了没反应（电视上的通用规则）
    LaunchedEffect(files.isNotEmpty()) {
        if (files.isNotEmpty()) {
            runCatching { firstRow.requestFocus() }
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(files, key = { _, f -> f.file.absolutePath }) { index, file ->
            val alreadyImported = file.displayName in importedNames
            FileRow(
                file = file,
                alreadyImported = alreadyImported,
                batchMode = batchMode,
                selected = file.file.absolutePath in selectedPaths,
                onClick = {
                    // 批量模式下"确定键"是勾选，不是导入
                    if (batchMode) {
                        if (!alreadyImported) onToggleSelect(file)
                    } else {
                        onImportOne(file)
                    }
                },
                modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
            )
        }
    }
}

/**
 * 一行文件。
 *
 * 注意这里直接用了 [tvFocusable] 而不是 TvCard ——
 * 因为这一行是"横向铺满的一整条"，而不是"一张卡片"，
 * 自己控制内边距和内部布局更合适。
 * 这正说明把焦点逻辑抽成【修饰符】比抽成【组件】更灵活：
 * 修饰符可以被任意形状复用。
 */
@Composable
private fun FileRow(
    file: SourceFile,
    alreadyImported: Boolean,
    batchMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // ══ 【这个 padding 是给"聚焦放大"留空间的】══════════════
            // LazyColumn 会裁剪超出 item 边界的内容，而 tvFocusable 的
            // scale 是【绘制变换】，放大后并不占布局空间 ——
            // 不加这一层的话，聚焦那一行的左右文字会被切掉。
            //
            // 实际现象：novel-2.txt 显示成 "ovel-2.txt"，
            // 右边的 "8 KB" 显示成 "8 KE"。
            // ═══════════════════════════════════════════════════════
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .tvFocusable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 批量模式下的勾选框
        if (batchMode) {
            SelectMark(selected = selected, disabled = alreadyImported)
            Spacer(Modifier.width(14.dp))
        }

        Text(
            text = file.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = if (alreadyImported) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(16.dp))

        // 已导入标记 —— 用户一眼就能看出哪些不用再导
        if (alreadyImported) {
            Text(
                text = "已在书架",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(16.dp))
        }

        Text(
            text = file.readableSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * 行首的勾选框。
 *
 * 【为什么不用 Material3 的 Checkbox】
 * 那是给触摸屏设计的小控件（约 20dp），电视上三米外看不清，
 * 而且它的内部焦点行为会和行本身抢焦点。
 * 自己画一个大方块，更符合电视的操作距离。
 */
@Composable
private fun SelectMark(selected: Boolean, disabled: Boolean) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(
                color = when {
                    disabled -> Color(0x22000000)
                    selected -> MaterialTheme.colorScheme.tertiary
                    else -> Color(0x66000000)
                },
                shape = shape,
            )
            .border(
                width = 2.dp,
                color = when {
                    disabled -> Color(0x33FFFFFF)
                    selected -> MaterialTheme.colorScheme.tertiary
                    else -> Color(0xAAFFFFFF)
                },
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && !disabled) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.background,
            )
        }
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
