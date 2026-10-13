package com.cloud.androidtvreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.dimensionResource
import com.cloud.androidtvreader.ui.MainScreen
import com.cloud.androidtvreader.ui.theme.MyApplicationTheme

/**
 * 唯一的 Activity。
 *
 * ══ 为什么整个 App 只有一个 Activity ═══════════════════════════
 * 这是 Compose 时代的推荐做法："单 Activity + 多个 Composable 页面"。
 * 页面切换由 Compose 内部的状态管理完成，不再需要多个 Activity 和 Intent。
 *
 * 好处：
 *   - 页面间共享状态很容易（都在同一棵 Compose 树里）
 *   - 转场动画好做
 *   - 不用处理 Activity 之间的生命周期和 Intent 传参
 * ═══════════════════════════════════════════════════════════════
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ══ 【临时实验：强制软件渲染，验证 GPU 图层假设】══════════════
        // 背景：这台小米电视用的是联发科 MT9655 + Mali GPU。
        // 实测到一个现象：Compose 里凡是依赖 graphicsLayer 的东西
        // （Material3 的 Surface、Modifier.scale、LazyVerticalGrid、
        //   verticalScroll）都会导致焦点视觉【不重绘】——
        // 屏幕上看不出焦点在哪。
        //
        // 规律是：
        //   元素在图层里     -> 图层被缓存，内容变化不重绘
        //   元素不在图层里   -> 被合进父级缓存，也不重绘
        //   只有"图层参数变化"（scale 数值）能走到屏幕
        //
        // 这指向 Mali 驱动与 Compose 的 RenderNode 缓存不兼容。
        // 把绘制降级到软件路径可以绕开 GPU 图层，
        // 用来验证这个判断成立与否。
        //
        // 【注意】这是诊断手段，不是最终方案 —— 软件渲染会牺牲性能。
        // 如果验证成立，正确的做法是收窄影响面（只对需要的 View 降级），
        // 或者用不依赖 graphicsLayer 的写法重做焦点视觉。
        // ═══════════════════════════════════════════════════════════════
        window.decorView.setLayerType(
            android.view.View.LAYER_TYPE_SOFTWARE,
            null
        )

        // 从 Application 取出全局依赖容器。
        // appContainer 是定义在 NovelReaderApp.kt 里的 Context 扩展属性。
        //
        // 【为什么能直接写 appContainer】
        // Activity 本身就是一个 Context，所以这个扩展属性可以直接用。
        // 它内部做的是 (applicationContext as NovelReaderApp).container。
        val container = appContainer

        setContent {
            // ══ 【为什么这里只留一个"兜底"主题，不套 TvSafeArea】════════
            // 真正的昼夜主题在 MainScreen 里（主题是用户设置，设置状态在那儿）。
            //
            // 这一层只保证"任何情况下都有一个合法的 ColorScheme"，
            // 正常情况下会被 MainScreen 里那层立刻覆盖。
            //
            // 【TvSafeArea 为什么不在这里】
            // 它只加 padding、不画背景，所以 padding 区域露出来的是
            // 【它外面那个容器】的背景色。
            //
            // 如果放在这一层，它外面就是这个默认主题（深色）的 Surface ——
            // 于是白天模式下会出现"安全区是黑的、内容区是米白的"，
            // 一条很显眼的色带。
            //
            // 所以它必须待在【跟随用户设置的那个 Surface 内部】，
            // 也就是 MainScreen 里。里外同色，问题消失。
            // ═══════════════════════════════════════════════════════════
            MyApplicationTheme {
                MainScreen(container)
            }
        }
    }
}

/**
 * TV 过扫描（Overscan）安全区。
 *
 * 【项目约定】每个页面的最外层都套这一层。
 * 这样"安全区"只需要维护一处，要调边距也只改 dimens.xml。
 *
 * 老式电视机会把画面边缘裁掉约 5%。Android TV 的逻辑画布固定是
 * 960×540 dp，5% 换算下来就是左右 48dp、上下 27dp。
 * 内容不留这个边距，边缘的文字会被电视物理裁掉。
 *
 * （模块 1 里用来目测的那个红色调试边框已经删掉了 ——
 *   它的使命在确认安全区位置之后就完成了。）
 */
@Composable
fun TvSafeArea(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = dimensionResource(R.dimen.tv_safe_horizontal),
                vertical = dimensionResource(R.dimen.tv_safe_vertical),
            )
    ) {
        content()
    }
}

/**
 * 让"全屏遮罩"真正铺满整个窗口 —— 包括 TvSafeArea 留出的那圈边距。
 *
 * ══ 【为什么需要它】══════════════════════════════════════════════
 * TvSafeArea 是用 Modifier.padding 实现的，它把【整个内容区】缩小了一圈。
 * 所以画在它内部的 fillMaxSize() 遮罩，其实只能铺到安全区的边界。
 *
 * 结果就是：打开菜单 / 设置面板时，中间是暗色遮罩，
 * 外面那一圈却还是页面背景色 —— 一眼就能看出来的色差。
 * 用户的原话是"打开菜单，安全区的颜色与内容区的颜色不符"。
 *
 * ══ 【为什么不能简单用负 padding 抵消】══════════════════════════
 * 试过 `padding(horizontal = -48.dp, ...)`，直接崩溃：
 *     IllegalArgumentException: Padding must be non-negative
 * Compose 明确禁止负 padding。
 *
 * 所以改用 Modifier.layout 手动放宽测量约束：
 *   · 测量时把可用宽高各加上两倍的安全边距（于是能画到安全区之外）
 *   · 上报尺寸仍然只用原来的约束（不会撑破父容器）
 *   · 摆放时往左上偏移一个安全边距，正好覆盖到边界外
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun Modifier.fullScreenOverlay(): Modifier {
    val horizontal = dimensionResource(R.dimen.tv_safe_horizontal)
    val vertical = dimensionResource(R.dimen.tv_safe_vertical)
    return this.layout { measurable, constraints ->
        val hPx = horizontal.roundToPx()
        val vPx = vertical.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxWidth = constraints.maxWidth + hPx * 2,
                maxHeight = constraints.maxHeight + vPx * 2,
            )
        )
        // 上报原尺寸（不撑破父容器），把内容往左上挪，于是盖住安全区外那圈
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(-hPx, -vPx)
        }
    }
}
