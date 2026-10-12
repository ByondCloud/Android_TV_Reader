package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color

// ── 阅读器配色 ────────────────────────────────────────────────
// 深色为底、文字用暖白。纯白(#FFFFFF)配纯黑在电视上会"发晕"，
// 暖白（带一点黄）长时间阅读更舒服，这也是 Kindle 等阅读器的做法。

val ReaderBackground = Color(0xFF10131A)   // 页面底色，接近墨黑
val ReaderSurface    = Color(0xFF1A1F2B)   // 卡片、面板底色
val ReaderText       = Color(0xFFE6E1D8)   // 正文颜色，暖白
val ReaderAccent     = Color(0xFFD8B26A)   // 强调色，暗金
val ReaderMuted      = Color(0xFF8A93A6)   // 次要文字
val ReaderFocus      = Color(0xFFFFC857)   // 焦点高亮（模块 4 会用到）