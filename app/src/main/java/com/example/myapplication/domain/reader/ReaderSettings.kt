package com.example.myapplication.domain.reader

/**
 * 阅读模式。
 *
 * 【为什么这里只有两种】
 *   - [Paged]  翻页：像实体书一样，一次一屏，左右翻
 *   - [Scroll] 上下连续滚动：像网页一样，一滑到底
 * 将来想加"仿真翻页动画"、"双栏"之类，是新加【翻页效果】，
 * 不是新加阅读模式 —— 那属于表现层的事，不用动这个枚举。
 */
enum class ReaderMode {
    /** 翻页 */
    Paged,

    /** 上下连续滚动 */
    Scroll;

    /** 切换用：在两种模式之间来回 */
    fun toggled(): ReaderMode = if (this == Paged) Scroll else Paged

    /** 界面上显示的名字 */
    val displayName: String
        get() = when (this) {
            Paged -> "翻页"
            Scroll -> "滚动"
        }
}

/**
 * 阅读设置。
 *
 * ══ 为什么用 data class 而不是一堆散落的常量 ══════════════════════
 * 1. 它是一个整体：字号、行距、是否分章总是搭配着调的
 * 2. 用 copy() 改其中一项，其余自动保留 —— 比手动维护 5 个变量可靠
 * 3. 要持久化到磁盘时，只要序列化一个对象
 * 4. 它会作为参数传进 ReaderScreen，界面是"无状态"的，所以必须是不可变值
 *
 * ══ 默认值是怎么定的 ═════════════════════════════════════════════
 * Android TV 的逻辑画布固定 960×540 dp，用户坐在三米外。
 * 手机上 16sp 的正文，在电视上根本看不清。
 *
 *   fontSizeSp = 28sp —— 大约能在 960dp 宽度里放下 26 个汉字
 *   lineHeightSp = 44sp —— 行距约为字号的 1.57 倍。
 *                          中文阅读里 1.5~1.8 倍最舒服。
 */
data class ReaderSettings(
    /** 正文字号 */
    val fontSizeSp: Float = 28f,

    /** 行高。必须 >= 字号，否则行会重叠 */
    val lineHeightSp: Float = 44f,

    /** 阅读模式 */
    val mode: ReaderMode = ReaderMode.Paged,

    /**
     * 正文区域左右额外留白（dp）。
     * 这是在 TvSafeArea（48dp 过扫描安全区）【之上】再加的，
     * 目的是限制每行的长度 —— 一行上百个汉字的话，眼睛找回行首会很累。
     */
    val contentPaddingDp: Int = 24,

    /**
     * ★ 是否按章节分页。
     *
     *   true （默认）：按章节切分。每章独立排版，章与章之间会断开 ——
     *                  每章末尾那一屏填不满，剩下的空白浪费掉了。
     *                  好处是能显示"第 N 章"、将来可以做章节目录跳转。
     *
     *   false         ：忽略章节，把全文按固定大小切成等份（见
     *                  [ReadingUnits.DEFAULT_CHUNK_CHARS]）。
     *                  读起来是连贯的一整本，断开的地方少得多（约每 35 屏一次）。
     *                  代价是顶栏不再显示章节名。
     *
     * 【默认为什么是 true】
     * 因为"能看见自己在第几章"对读小说来说是很重要的信息，
     * 而且将来加章节目录、跳章功能都依赖它。
     * 想要连续阅读体验的用户可以自己在设置里关掉。
     */
    val chapterSplitEnabled: Boolean = true,
)
