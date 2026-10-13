package com.cloud.androidtvreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/*
  原模板里有两样东西我故意删掉了，说明一下原因：

  1. dynamicColor（动态取色）—— 它是从手机壁纸里抽颜色的。
     电视上这个值不可预测，阅读器需要稳定的配色，所以关掉。

  2. isSystemInDarkTheme() —— 跟随系统深浅色。
     阅读器的昼夜模式应该由用户自己决定（我们把开关放在了
     书架页右上角），不该被系统设置覆盖 ——
     用户在系统里选了深色，不代表他此刻读书时要深色底。

  Material3 的核心思想：你不是在直接指定"这个按钮什么颜色"，
  而是在填充一组【颜色角色】(colorScheme)。组件自己去角色里取色。
  好处是换皮肤只需要换这一处 —— 这一轮加白天主题，
  界面代码一行都没改，只是多填了一套角色值。
*/

/**
 * ══ 【焦点色借用 tertiary 这个角色】══════════════════════════════
 * Material3 的 ColorScheme 里没有"焦点色"这个槽位。
 * 我们借 tertiary（第三强调色）来承载它。
 *
 * 这是 Compose 项目里的常见做法：角色名只是槽位，
 * 具体表达什么由项目自己定义，只要全局一致就行。
 *
 * 使用时统一写 MaterialTheme.colorScheme.tertiary，
 * 于是昼夜两套主题各给一个值，所有焦点样式自动跟着变 ——
 * 这正是"用角色而不是用具体色号"的价值所在。
 * ═══════════════════════════════════════════════════════════════
 */
private val NightColorScheme = darkColorScheme(
    primary      = ReaderAccent,
    onPrimary    = ReaderBackground,   // "on Xxx" = 在 Xxx 底色上该用什么颜色的字
    secondary    = ReaderMuted,
    onSecondary  = ReaderBackground,
    background   = ReaderBackground,
    onBackground = ReaderText,
    surface      = ReaderSurface,
    onSurface    = ReaderText,
    tertiary     = ReaderFocus,        // 焦点高亮
    onTertiary   = ReaderBackground,
    // 另外两个借用槽位，理由同上：ColorScheme 里没有"焦点底色"和
    // "淡边框"这两种角色，借 surfaceVariant / outlineVariant 来承载。
    surfaceVariant = ReaderFocusSurface,
    outlineVariant = ReaderOutlineSubtle,
)

private val DayColorScheme = lightColorScheme(
    primary      = ReaderAccentLight,
    onPrimary    = ReaderSurfaceLight,
    secondary    = ReaderMutedLight,
    onSecondary  = ReaderSurfaceLight,
    background   = ReaderBackgroundLight,
    onBackground = ReaderTextLight,
    surface      = ReaderSurfaceLight,
    onSurface    = ReaderTextLight,
    tertiary     = ReaderFocusLight,   // 焦点高亮
    onTertiary   = ReaderSurfaceLight,
    surfaceVariant = ReaderFocusSurfaceLight,
    outlineVariant = ReaderOutlineSubtleLight,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightColorScheme else DayColorScheme,
        typography = Typography,
        content = content
    )
}
