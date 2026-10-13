package com.example.myapplication.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.tts.TtsEngine
import com.example.myapplication.data.tts.TtsLanguage
import com.example.myapplication.fullScreenOverlay
import com.example.myapplication.ui.components.TvButton
import com.example.myapplication.ui.components.requestFocusWithRetry
import com.example.myapplication.ui.components.tvFocusable

/**
 * 语音朗读的选择面板。
 *
 * ══ 【这个界面存在的首要目的：把"能不能用"变成看得见的事实】══════
 * Android 的 TTS 引擎是设备上装了才有，电视盒子尤其可能一个都没有。
 * 用户点"朗读"时最糟的体验是"没反应，也不知道为什么"。
 *
 * 所以这个面板一打开就先探测，然后把结果【如实】呈现：
 *   · 探测中   → 说明在查什么
 *   · 有引擎   → 列出来让用户选
 *   · 没引擎   → 直接说清楚"这台设备没有可用的语音引擎"
 *
 * 宁可让用户看到"不支持"，也不要让他对着一个不响的按钮反复按。
 *
 * ══ 【为什么引擎和语言要分两级选】══════════════════════════════
 * 因为不同引擎支持的语言完全不同 —— 这台设备上 Google 引擎可能
 * 只装了英文数据，而另一个引擎反而有中文。
 * 让用户先选引擎、再看到该引擎真正支持的语言，比给一个
 * 混在一起的大列表要清楚得多。
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun TtsPanel(
    /** null 表示还在探测 */
    engines: List<TtsEngine>?,
    selectedEngine: TtsEngine?,
    selectedLanguage: TtsLanguage?,
    isSpeaking: Boolean,
    /** 准备失败时的提示（比如"这个引擎不支持该语言"） */
    errorMessage: String?,
    onSelectEngine: (TtsEngine) -> Unit,
    onSelectLanguage: (TtsLanguage) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }

    // 面板出现就把焦点给「开始朗读」按钮。
    //
    // 【为什么要带重试】
    // 那个按钮的 enabled 依赖"引擎和语言都选好了"，而选择是在
    // 探测完成后才发生的。直接在 engines 变化的那一刻请求焦点，
    // 按钮可能还是禁用状态（禁用元素拿不到焦点），请求会静默失败，
    // 焦点就掉到别处去了。
    // requestFocusWithRetry 会一直重试到成功，正好覆盖这段窗口。
    LaunchedEffect(engines) {
        if (engines != null) {
            firstFocus.requestFocusWithRetry()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
        // 遮罩单独一层：扩展铺满窗口，但不影响面板的居中。
        // （和书架菜单、阅读设置、目录面板是同样的结构 ——
        //   TvSafeArea 的 padding 会把 fillMaxSize 的遮罩裁在安全区内，
        //   外面那圈露出页面背景色，看起来就是"安全区颜色和显示区不符"。）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .fullScreenOverlay()
                .background(Color(0xDD000000))
        )

        Column(
            modifier = Modifier
                .width(760.dp)
                .fillMaxHeight(0.88f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(14.dp))
                // 语言列表可能很长（有些引擎支持几十种），必须能滚
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                text = "语音朗读",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "从你当前读到的位置开始念 · 返回键关闭",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(Modifier.height(14.dp))

            // ══ 【按钮为什么放在顶部，而不是列表底部】══════════════
            // 实测发现：Google 的 TTS 引擎支持大约 50 种语言。
            // 如果按钮在语言列表下面，用户要按几十下方向键才能滚到它 ——
            // 这是很糟糕的操作成本，而且他可能根本不知道下面还有按钮。
            //
            // 放顶部之后，"选语言"和"开始朗读"各在一个方向：
            //   往上 → 到按钮
            //   往下 → 浏览语言
            // ═══════════════════════════════════════════════════════
            if (!engines.isNullOrEmpty()) {
                // 【这里刻意只有一个按钮】
                // 第一版放了「开始朗读」和「关闭」两个并排按钮，
                // 实测发现：从下面的列表往上按时，焦点系统的几何搜索
                // 会把焦点送到右边的「关闭」—— 用户想开始朗读，
                // 结果把面板关掉了。两个并排按钮互相抢焦点是电视上的常见坑。
                //
                // 去掉「关闭」之后：往上 = 开始朗读，往下 = 浏览语言，
                // 退出统一交给返回键（上面已经写了提示）。
                // 一个动作一个入口，焦点永远不会歧义。
                TvButton(
                    // 朗读会话活着时（正在念或已暂停），这个按钮的语义是
                    // "结束朗读"——按了之后右下角的圆形指示会消失，
                    // 确定键也恢复成"打开菜单"。
                    //
                    // 【为什么在阅读区按确定只能暂停，而这里才能结束】
                    // 用户在听书时按确定，意图几乎总是"我想歇一下"。
                    // 如果那一下就把朗读会话彻底结束，他再想继续
                    // 就得重新进面板、重新选引擎语言 —— 代价太大。
                    // 所以"结束"这个动作放在一个要刻意走进来的地方。
                    text = if (isSpeaking) "■ 暂停朗读" else "▶ 开始朗读",
                    onClick = { if (isSpeaking) onStop() else onStart() },
                    enabled = selectedEngine != null && selectedLanguage != null,
                    modifier = Modifier.focusRequester(firstFocus),
                )

                // 把当前选择摘要写在按钮下面，用户按之前能确认一下
                Spacer(Modifier.height(10.dp))
                Text(
                    text = buildString {
                        append("将使用：")
                        append(selectedEngine?.label ?: "未选引擎")
                        append(" · ")
                        append(selectedLanguage?.displayName ?: "未选语言")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )

                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(20.dp))
            }

            when {
                // ── 还在探测 ────────────────────────────────
                engines == null -> {
                    Text(
                        text = "正在检测这台设备上的语音引擎…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "每个引擎要单独问一次它支持哪些语言，可能要几秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                // ── 一个引擎都没有 ──────────────────────────
                engines.isEmpty() -> {
                    Text(
                        text = "这台设备上没有可用的语音引擎",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Android 系统本身不带朗读引擎，需要设备预装或者另外安装一个。" +
                            "电视盒子比较常见这种情况。\n\n" +
                            "这不是 App 的故障 —— 换一台装了语音引擎的设备就能用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                // ── 正常：列出引擎和语言 ────────────────────
                else -> {
                    Text(
                        text = "引擎",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))

                    engines.forEachIndexed { index, engine ->
                        EngineRow(
                            engine = engine,
                            selected = engine.name == selectedEngine?.name,
                            onClick = { onSelectEngine(engine) },
                            // 【这里刻意不挂初始焦点】
                            // 最初把 firstFocus 挂在第一个引擎上，结果打开面板
                            // 焦点落在列表里，用户要往上按好几次才够到「开始朗读」。
                            // 而"进这个面板"的意图十有八九就是"我要听书" ——
                            // 所以焦点应该直接落在那个按钮上。
                            modifier = Modifier,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "语言",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))

                    val languages = selectedEngine?.languages.orEmpty()
                    if (languages.isEmpty()) {
                        Text(
                            text = "这个引擎没有报告任何可用语言。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        Text(
                            text = "共 ${languages.size} 种 · 中文排在最前",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        languages.forEach { language ->
                            LanguageRow(
                                language = language,
                                selected = language.locale == selectedLanguage?.locale,
                                onClick = { onSelectLanguage(language) },
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 一个引擎 */
@Composable
private fun EngineRow(
    engine: TtsEngine,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = engine.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = engine.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (selected) "✓ 已选" else "${engine.languages.size} 种语言",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
    }
}

/** 一种语言 */
@Composable
private fun LanguageRow(
    language: TtsLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "✓ 已选",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
