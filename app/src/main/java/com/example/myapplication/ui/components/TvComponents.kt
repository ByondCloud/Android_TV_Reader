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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.ReaderFocus
import com.example.myapplication.ui.theme.ReaderSurface

// ── 焦点视觉的两个颜色 ──────────────────────────────────────────
// 故意放在文件顶层而不是 Color.kt，因为它们【只服务于焦点状态】，
// 是"焦点系统"这个概念的内部细节，不该污染全局配色表。

/** 获得焦点时的底色：比常态亮一档 */
private val FocusedBackground = Color(0xFF273046)

/** 未获得焦点时的边框：几乎看不见，只用来让卡片有轮廓 */
private val UnfocusedBorder = Color(0x22FFFFFF)

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
        .scale(if (isFocused) focusedScale else 1f)
        .clip(shape)
        .background(if (isFocused) FocusedBackground else ReaderSurface)
        .border(
            width = if (isFocused) focusedBorderWidth else 1.dp,
            color = if (isFocused) ReaderFocus else UnfocusedBorder,
            shape = shape,
        )
        .onFocusChanged { isFocused = it.isFocused }
        .clickable(
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
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .tvFocusable(onClick = onClick)
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
) {
    Box(
        modifier = modifier
            .tvFocusable(
                onClick = onClick,
                shape = RoundedCornerShape(24.dp),
                focusedScale = 1.08f,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
