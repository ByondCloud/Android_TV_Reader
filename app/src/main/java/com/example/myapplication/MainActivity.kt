package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 注意：原模板的 enableEdgeToEdge() 被删掉了。
        // 那是给手机做全面屏适配用的；电视没有状态栏，让内容顶到屏幕边缘
        // 反而正好把文字送进被裁掉的区域。
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TvSafeArea {
                        HomeScreen()
                    }
                }
            }
        }
    }
}

/**
 * TV 过扫描安全区。
 *
 * 【项目约定】以后每个页面的最外层都套这一层。
 * 这样"安全区"只需要维护一处，将来要调边距也只改 dimens.xml。
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

@Composable
private fun HomeScreen() {
    val config = LocalConfiguration.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 临时红框：用来肉眼确认安全区边界在哪。模块 4 做书架时删掉。
            .border(1.dp, Color(0x66FF5555)),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "TV 画布：${config.screenWidthDp} × ${config.screenHeightDp} dp",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "过扫描安全区：左右 48dp / 上下 27dp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}