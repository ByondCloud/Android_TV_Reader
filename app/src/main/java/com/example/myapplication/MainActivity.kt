package com.example.myapplication

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
import androidx.compose.ui.res.dimensionResource
import com.example.myapplication.ui.MainScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

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

        // 从 Application 取出全局依赖容器。
        // appContainer 是定义在 NovelReaderApp.kt 里的 Context 扩展属性。
        //
        // 【为什么能直接写 appContainer】
        // Activity 本身就是一个 Context，所以这个扩展属性可以直接用。
        // 它内部做的是 (applicationContext as NovelReaderApp).container。
        val container = appContainer

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // 所有页面都套在安全区里 —— 这条约定从模块 1 立到现在没变过
                    TvSafeArea {
                        MainScreen(container)
                    }
                }
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
