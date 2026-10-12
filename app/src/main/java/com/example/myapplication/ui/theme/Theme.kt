package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/*
  原模板里有两样东西我故意删掉了，说明一下原因：

  1. dynamicColor（动态取色）—— 它是从手机壁纸里抽颜色的。
     电视上这个值不可预测，阅读器需要稳定的配色，所以关掉。

  2. isSystemInDarkTheme() —— 跟随系统深浅色。
     阅读器场景我们要强制深色，不需要跟随。

  Material3 的核心思想：你不是在直接指定"这个按钮什么颜色"，
  而是在填充一组【颜色角色】(colorScheme)。组件自己去角色里取色。
  好处是换皮肤只需要换这一处。
*/
private val ReaderColorScheme = darkColorScheme(
    primary      = ReaderAccent,
    onPrimary    = ReaderBackground,   // "on Xxx" = 在 Xxx 底色上该用什么颜色的字
    secondary    = ReaderMuted,
    onSecondary  = ReaderBackground,
    background   = ReaderBackground,
    onBackground = ReaderText,
    surface      = ReaderSurface,
    onSurface    = ReaderText,
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ReaderColorScheme,
        typography = Typography,
        content = content
    )
}