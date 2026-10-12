package com.example.myapplication.data.settings

import android.content.Context
import com.example.myapplication.domain.reader.ReaderSettings
import com.example.myapplication.domain.reader.ReaderTheme

/**
 * 阅读设置的持久化。
 *
 * ══ 为什么用 SharedPreferences，而不是 DataStore 或数据库 ══════════
 * 我们要存的东西非常小：6 个键值对（字号、行距档位、模式、边距、是否分章、主题）。
 * SharedPreferences 是 Android 内置的、零依赖的、为这种场景设计的方案。
 *
 * DataStore 更好（异步、有类型安全、能监听变化），但需要引入
 * androidx.datastore 依赖和一个 Kotlin 协程 Flow。
 * 对这个项目来说，SharedPreferences 的收益/成本比更高。
 *
 * 【一个刻意的取舍：这里是阻塞 IO】
 * SharedPreferences 的读写在首次加载后是内存操作，非常快，
 * 所以这里没有包 withContext(Dispatchers.IO)。
 * 之所以能这么确定，是因为我们只在两处调用：
 *   - App 启动时读一次
 *   - 用户改设置时写一次
 * 都不是高频操作。
 *
 * 【什么时候不该这么写】
 * 如果要存的是"用户每翻一页都要写"的阅读进度，就绝对不能放在主线程 ——
 * 那种情况必须用 apply()（异步提交）或者干脆搬到 IO 线程。
 * 事实上我们的阅读进度就没有走这里，而是由 BookRepository 在 IO 线程写文件。
 * ═══════════════════════════════════════════════════════════════
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadReaderSettings(): ReaderSettings {
        // 用默认值兜底：首次启动时这些键都不存在，getXxx 会返回我们给的默认值。
        // 这样"第一次运行"和"读到了损坏的数据"都能退化到一套合理的设置，
        // 而不是崩溃或者显示成 0 号字。
        //
        // 枚举用 runCatching 包起来：磁盘上如果存了个已经删掉的枚举名
        // （比如以后去掉了某个主题），valueOf 会抛异常，
        // 这里要退化成默认值而不是让 App 打不开。
        val defaults = ReaderSettings()
        return ReaderSettings(
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, defaults.fontSizeSp),
            lineSpacingLevel = prefs.getInt(KEY_LINE_SPACING, defaults.lineSpacingLevel),
            contentPaddingDp = prefs.getInt(KEY_CONTENT_PADDING, defaults.contentPaddingDp),
            chapterSplitEnabled = prefs.getBoolean(KEY_CHAPTER_SPLIT, defaults.chapterSplitEnabled),
            theme = runCatching {
                ReaderTheme.valueOf(prefs.getString(KEY_THEME, null) ?: defaults.theme.name)
            }.getOrDefault(defaults.theme),
        )
    }

    fun saveReaderSettings(settings: ReaderSettings) {
        prefs.edit()
            .putFloat(KEY_FONT_SIZE, settings.fontSizeSp)
            .putInt(KEY_LINE_SPACING, settings.lineSpacingLevel)
            .putInt(KEY_CONTENT_PADDING, settings.contentPaddingDp)
            .putBoolean(KEY_CHAPTER_SPLIT, settings.chapterSplitEnabled)
            .putString(KEY_THEME, settings.theme.name)
            // apply() 是异步落盘，不阻塞调用线程 —— 改设置时界面不会卡一下
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "reader_settings"

        const val KEY_FONT_SIZE = "fontSizeSp"

        /**
         * 行距【档位】(1~5)，不是行高像素值。
         * 注意：早期版本存的是 lineHeightSp(float)，
         * 现在改存档位了。旧数据读不到这个键 → 自动用默认档位 3，
         * 不会崩，只是用户需要重新调一次行距。
         */
        const val KEY_LINE_SPACING = "lineSpacingLevel"

        const val KEY_MODE = "mode"   // 已废弃：阅读模式删掉了，只为兼容旧存档保留常量名记录
        const val KEY_CONTENT_PADDING = "contentPaddingDp"
        const val KEY_CHAPTER_SPLIT = "chapterSplitEnabled"
        const val KEY_THEME = "theme"
    }
}
