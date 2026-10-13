package com.cloud.androidtvreader.ui.theme

import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════════
//  阅读器配色 —— 两套主题
// ══════════════════════════════════════════════════════════════════
//
// 【一条通用原则：不用纯黑纯白】
//   · 夜晚不用 #000000 做底、不用 #FFFFFF 做字。
//     纯白配纯黑对比度达到 21:1，在电视这种高亮度屏幕上会"发晕"，
//     看久了眼睛累。带一点黄/褐的暖色能把对比度压到舒服的区间。
//   · 白天也不用 #FFFFFF 做底。纯白背景会凸显屏幕的背光，
//     而且暖色的正文放在纯白上会显得"发灰"。
//
// 这是 Kindle、微信读书、Apple Books 等阅读器日间/夜间模式的通行做法。
//
// 【为什么焦点色要单独给】
// 焦点高亮必须和背景有足够对比，否则用户看不出遥控器选中了哪里。
// 深色底上要用亮色（明黄），浅色底上就得换成暗色（暖橙）——
// 同一个黄色放在米白背景上几乎看不见。
// ══════════════════════════════════════════════════════════════════

// ── 夜晚主题（默认）────────────────────────────────────────────

/** 页面底色，接近墨黑但带一点蓝，比纯黑柔和 */
val ReaderBackground = Color(0xFF10131A)

/** 卡片、面板底色。比页面底色亮一档，形成层次 */
val ReaderSurface = Color(0xFF1A1F2B)

/** 正文颜色，暖白（带一点黄） */
val ReaderText = Color(0xFFE6E1D8)

/** 强调色，暗金。用于标题、"我的书架"这类文字 */
val ReaderAccent = Color(0xFFD8B26A)

/** 次要文字：文件名、页数、提示语 */
val ReaderMuted = Color(0xFF8A93A6)

/** 焦点高亮。深色底上用明黄，三米外也看得清 */
val ReaderFocus = Color(0xFFFFC857)

/** 获得焦点的元素底色。比 surface 再亮一档，和焦点边框一起构成"选中"的视觉 */
val ReaderFocusSurface = Color(0xFF273046)

/** 未获得焦点时的淡边框：几乎看不见，只用来给卡片一个轮廓 */
val ReaderOutlineSubtle = Color(0x33FFFFFF)

// ── 白天主题 ───────────────────────────────────────────────────

/** 页面底色，米白"纸色"。模仿纸张而不是屏幕 */
val ReaderBackgroundLight = Color(0xFFF5EFE3)

/** 卡片、面板底色。比页面底色【更亮】，在浅色主题下这样才能形成层次 */
val ReaderSurfaceLight = Color(0xFFFDFAF4)

/** 正文颜色。深褐黑而不是纯黑，对比度更柔和 */
val ReaderTextLight = Color(0xFF2E2A24)

/** 强调色，深金。浅底上金色必须压暗，否则看不清 */
val ReaderAccentLight = Color(0xFF9A6B12)

/** 次要文字 */
val ReaderMutedLight = Color(0xFF6B6459)

/** 焦点高亮。浅底上必须用暗色 —— 明黄放在米白上等于没有 */
val ReaderFocusLight = Color(0xFFB96D0A)

/**
 * 获得焦点的元素底色。
 * 【注意方向反了】：深色主题里焦点底色比 surface 【更亮】，
 * 浅色主题里要比 surface 【更暗】——
 * "更突出"在两种主题下的方向是相反的。
 */
val ReaderFocusSurfaceLight = Color(0xFFEBE0CC)

/** 未获得焦点时的淡边框。浅底上要用黑色系，白色系等于没有 */
val ReaderOutlineSubtleLight = Color(0x33000000)
