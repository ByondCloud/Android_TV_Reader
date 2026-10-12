package com.example.myapplication.data.source

import com.example.myapplication.domain.source.BookSource
import com.example.myapplication.domain.source.SourceFile
import java.io.File

/**
 * 本机目录来源：扫描一个本地文件夹，找出里面所有能打开的电子书。
 *
 * ══ 为什么这是 Android TV 上的主通道 ══════════════════════════
 * Android TV 系统里【没有文件选择器】。我用 adb 实测确认过（见 BookSource 的注释）。
 * 所以 TV 上的做法只能是：App 自己扫一个目录，把文件列出来给用户挑。
 *
 * 这个类扫描的目录由外部传入 —— App 里会传
 * context.getExternalFilesDir("books")，也就是：
 *   /sdcard/Android/data/com.example.myapplication/files/books
 * 那是应用自己的外部私有目录，【不需要任何存储权限】，
 * 用 adb push 或者 USB 拷进去就能用。
 * ═══════════════════════════════════════════════════════════════
 *
 * 【为什么要传 supportedExtensions 进来】
 * 让它知道"哪些文件值得列出来"。
 * 传一个集合，而不是传整个 BookParserRegistry —— 这叫接口隔离：
 * 一个类只应该依赖它【真正用到】的东西。依赖越窄，越容易被复用和测试。
 *
 * 【为什么构造函数收 File 而不是 Context】
 * 这样它就成了一个纯 JVM 类，单元测试用临时目录就能测，不用启动模拟器。
 * 把 Android 依赖挡在最外层构造的地方 —— 这条原则后面会反复出现。
 */
class LocalFolderSource(
    private val rootDir: File,
    private val supportedExtensions: Set<String>,
) : BookSource {

    override val id: String = "local_folder"

    override val displayName: String = "本机书籍目录"

    override fun isAvailable(): Boolean = rootDir.isDirectory

    override fun listFiles(): List<SourceFile> {
        if (!rootDir.isDirectory) return emptyList()

        // 【经典坑】File.listFiles() 在目录无法读取时返回的是 null，
        // 不是空数组。直接对 null 调 .filter 会抛 NullPointerException。
        // 这是 Java 互操作里最常见的崩溃来源之一。
        val children = rootDir.listFiles() ?: return emptyList()

        return children
            .asSequence()
            .filter { it.isFile }                            // 跳过子目录
            .filter { it.extension.lowercase() in supportedExtensions }
            .filter { it.length() > 0 }                      // 0 字节的空文件没必要列出来
            .map {
                SourceFile(
                    file = it,
                    displayName = it.name,
                    sizeBytes = it.length(),
                    lastModified = it.lastModified(),
                )
            }
            .sortedBy { it.displayName }                     // 按文件名排序，保证每次结果顺序一致
            .toList()
    }
}
