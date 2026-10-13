package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ── 焦点视觉的取色方式 ──────────────────────────────────────────
// 【为什么这些颜色不写死在这里，而是从 MaterialTheme 取】
// 因为项目有白天/夜晚两套主题，而且焦点颜色在两套主题里
// 方向是相反的：
//   · 焦点边框：深色底用明黄，浅色底必须换暗橙（明黄放在米白上等于没有）
//   · 焦点底色：深色主题里要比 surface 更【亮】，
//               浅色主题里要比 surface 更【暗】
// 直接从 colorScheme 的角色里取，切主题时这里一行都不用改。
//
// 具体色值和角色映射见 ui/theme/Theme.kt。

/**
 * ★★★ TV 开发最核心的一个修饰符 ★★★
 *
 * 把任意一个 Composable 变成"可以用遥控器选中并按下确定键"的元素。
 *
 * ══ 它做了 4 件事 ═══════════════════════════════════════════════
 *   1. 让元素【可获得焦点】（clickable 内部会注册焦点节点）
 *   2. 用 onFocusChanged 监听焦点变化
 *   3. 焦点变化时改变外观：放大 + 亮边框 + 亮底色
 *   4. 按下确定键（或鼠标点击）时回调 onClick
 * ═══════════════════════════════════════════════════════════════
 *
 * ══ 为什么"放大"是 TV 上必须的 ═════════════════════════════════
 * 手机上"选中"可以用手指按下去的水波纹表达，因为手指就在那儿。
 * 电视上用户离屏幕三米，只有【形状和颜色的明显变化】才看得清。
 * 放大 + 亮边框是目前业界最通用的做法（Netflix、YouTube TV 都这样）。
 *
 * 注意用的是 Modifier.scale 而不是真的改尺寸 ——
 * scale 只是【绘制时】的几何变换，不参与布局测量，
 * 所以卡片变大了也不会把旁边的元素挤走（否则焦点移动时整个界面会抖）。
 *
 * ══ 【修饰符顺序】为什么 onFocusChanged 必须写在 clickable 前面 ══
 * Compose 的修饰符链是"从外到内"的。
 * onFocusChanged 是一个【观察者】，它观察的是自己内层那个元素的焦点状态。
 * 而焦点节点是由 clickable 创建的 —— 所以 onFocusChanged 必须在外层。
 *
 * 写反了会怎样？不会编译报错，只是 isFocused 永远保持 false，
 * 焦点框永远不亮 —— 一个非常难查的"静默失败"。
 * ═══════════════════════════════════════════════════════════════
 *
 * ══ 为什么 indication = null ═══════════════════════════════════
 * clickable 默认会画一圈水波纹（ripple）。那是给手指触摸的反馈，
 * 在电视上既看不见也没意义，还可能在遥控器连按时糊成一片。
 * 关掉它，用我们自己的焦点高亮代替。
 */
@Composable
fun Modifier.tvFocusable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    focusedScale: Float = 1.05f,
    focusedBorderWidth: Dp = 3.dp,
): Modifier {
    // 这个状态只属于这一个元素实例。用 remember 保证重组之间不丢。
    var isFocused by remember { mutableStateOf(false) }

    // clickable 需要一个 InteractionSource 才能配合 indication；
    // 我们传 null 作为 indication，但还是要给它一个 source。
    val interactionSource = remember { MutableInteractionSource() }

    return this
        // ══ 【为什么不用 Modifier.scale 做"聚焦放大"】══════════════════
        // 试过用 scale(1.05f) 让聚焦元素放大一点，但在电视这种大尺寸
        // 元素上副作用很明显：元素宽约 900px 时，1.05 倍会让左右各扩
        // 约 22px，而文字是跟着一起放大的 ——
        // 用户看到的就是"选中的那一项，文字位置和别的不一样，像被挤出去了"。
        //
        // 去掉缩放之后，聚焦和未聚焦的排版完全一致，只有颜色和边框变化：
        //   · 背景：surface -> surfaceVariant
        //   · 边框：1dp 淡色 -> 3dp 主题色
        // 这两种变化都不影响内容位置，视觉上已经足够醒目。
        //
        // 【注意】以前不敢去掉 scale，是因为这台电视（联发科 MT9655 +
        // Mali）的图层缓存会让"去掉 scale 后焦点框完全不显示"。
        // 那个问题现在已经在 MainActivity 里用软件渲染
        // （setLayerType(LAYER_TYPE_SOFTWARE)）解决了，所以可以放心去掉。
        // ═══════════════════════════════════════════════════════════════
        .clip(shape)
        .background(
            if (isFocused) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
        .border(
            width = if (isFocused) focusedBorderWidth else 1.dp,
            color = if (isFocused) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
            shape = shape,
        )
        .onFocusChanged { state ->
            isFocused = state.isFocused
        }
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
}

/**
 * 可聚焦的卡片容器。书架上的一本书、导入列表里的一行，都用它。
 *
 * 【为什么把 content 声明成 ColumnScope.() -> Unit】
 * 这样调用方传进来的 lambda 里可以直接用 Column 的成员
 * （比如 Modifier.align()、weight()），不需要自己再套一层 Column。
 * 这是 Compose 里很常见的"作用域接收者"用法。
 */
@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .tvFocusable(onClick = onClick, enabled = enabled)
            // padding 写在 tvFocusable 【之后】，所以它在内层 ——
            // 结果是背景和边框包住内边距，而不是被内边距切掉。
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 可聚焦的按钮。
 * 圆角更大、水平内边距更宽，和卡片在视觉上区分开。
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .tvFocusable(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(24.dp),
                focusedScale = 1.08f,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
    }
}

/**
 * 带重试的焦点请求 —— TV 上做"初始焦点"必须用它。
 *
 * ══ 【为什么直接 requestFocus() 不行】═══════════════════════════
 * `LaunchedEffect` 在第一帧【布局挂载完成之前】就会执行。
 * 那一刻目标元素还没把自己的焦点节点注册到窗口的 focus tree 上，
 * 于是 `requestFocus()` 会失败 —— 而且失败是【静默】的：
 * 不抛异常、不打日志，只是焦点没落上去。
 *
 * 结果就是"开屏没有任何元素被选中"，用户按方向键毫无反应，
 * 只能乱按，直到焦点系统自己摸到第一个可聚焦元素。
 *
 * ══ 【一个很容易写错的地方】════════════════════════════════════
 * `requestFocus()` 本身【返回 Boolean】表示成功与否，
 * 必须用这个返回值判断，不能写成：
 *
 *     runCatching { requestFocus(); true }   // ❌ 永远返回 true
 *
 * 那样第一次就"成功"了，重试逻辑形同虚设。
 *
 * @param maxAttempts 最多试几次。20 次 × 16ms ≈ 320ms，
 *        足够覆盖低端电视上首帧布局的延迟，又不会无限等下去。
 * @param intervalMs 两次尝试之间的间隔，16ms 约一帧。
 */
suspend fun FocusRequester.requestFocusWithRetry(
    maxAttempts: Int = 20,
    intervalMs: Long = 16L,
) {
    repeat(maxAttempts) {
        // ★ 用 requestFocus() 自己的返回值判断成功与否
        val ok = runCatching { requestFocus() }.getOrDefault(false)
        if (ok) return
        delay(intervalMs)
    }
}
