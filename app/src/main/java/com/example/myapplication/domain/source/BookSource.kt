package com.example.myapplication.domain.source

import java.io.File

/**
 * 一个"可供导入的文件"。
 *
 * 【为什么用 java.io.File，而不是 Android 的 Uri】
 * 这是刻意的选择。File 是纯 JVM 类型，所以基于它写的扫描逻辑
 * 可以在电脑上直接跑单元测试，不用启动模拟器。
 *
 * Android 的 Uri 只出现在【最外层】—— 也就是"把用户选中的文件
 * 复制进应用私有目录"那一步。核心逻辑完全不认识 Uri。
 * 这条原则你会在模块 4、5 反复看到：**把平台依赖挡在最外层**。
 */
data class SourceFile(
    /** 文件系统中的真实位置 */
    val file: File,
    /** 显示名（就是文件名） */
    val displayName: String,
    /** 字节数 */
    val sizeBytes: Long,
    /** 最后修改时间（毫秒） */
    val lastModified: Long,
) {
    /**
     * 人类可读的体积，例如 "1.2 MB"。
     * 直接显示字节数（如 "1283746 B"）对用户毫无意义。
     */
    val readableSize: String
        get() = when {
            sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
            sizeBytes >= 1024L -> "%.0f KB".format(sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
}

/**
 * 书籍来源 —— 【模块 3 的扩展点】。
 *
 * ══ 为什么要有这一层抽象 ══════════════════════════════════════
 * "把书弄进 App"这件事，在不同设备上做法完全不同：
 *
 *   - 手机上：系统文件选择器（SAF）可用
 *   - Android TV 上：系统【根本没有】文件选择器。
 *     我用 adb 实测确认过：
 *       adb shell cmd package query-activities \
 *         -a android.intent.action.OPEN_DOCUMENT -c android.intent.category.OPENABLE
 *     唯一的响应者是 com.android.tv.frameworkpackagestubs 里的
 *     Stubs$DocumentsStub —— 一个什么都不做的占位实现。
 *     它存在的目的只是"让这个意图能被解析成功，从而让 App 不崩溃"。
 *     所以 TV 上只能让 App 自己扫目录、把文件列出来给用户挑。
 *   - 以后还可以有：U 盘、WebDAV、局域网共享、网盘
 *
 * 如果把这些做法都写死在界面里，每加一种就要改一遍 UI。
 * 抽象成 BookSource 之后，"加一种来源" = "加一个实现类"，
 * 界面只需要遍历一个列表，把【可用的】来源显示出来。
 * ═══════════════════════════════════════════════════════════════
 *
 * 【为什么 isAvailable() 是方法而不是属性】
 * 因为它依赖运行时的设备状态（目录存不存在、外部存储挂没挂载、
 * 权限给没给）。每次调用都应该重新判断。
 * 写成 val 会在对象创建时算一次然后永远固定 ——
 * 典型的场景是：用户先打开界面（目录还不存在），
 * 然后通过别的方式创建了目录，界面却一直说"不可用"。
 */
interface BookSource {

    /** 唯一标识，例如 "local_folder" */
    val id: String

    /** 给用户看的名字，例如 "本机书籍目录" */
    val displayName: String

    /** 这个来源在当前设备上可用吗？不可用的来源不要出现在界面上 */
    fun isAvailable(): Boolean

    /** 列出这个来源里所有可导入的文件。目录不存在时返回空列表，不要抛异常 */
    fun listFiles(): List<SourceFile>
}
