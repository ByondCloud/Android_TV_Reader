package com.example.myapplication.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * LocalFolderSource 的单元测试。
 *
 * ══ 这里用到了 JUnit 的 TemporaryFolder 规则 ═══════════════════
 * `@get:Rule val tmp = TemporaryFolder()` 会在【每个测试方法执行前】
 * 创建一个全新的空临时目录，并在方法结束后自动删掉。
 *
 * 为什么不用硬编码的路径？因为测试必须能重复跑、能并行跑、
 * 不在你机器上留垃圾。用临时目录是文件系统测试的标准做法。
 *
 * 【注意 @get:Rule 这个写法】
 * JUnit 4 的 @Rule 注解在 Java 里是打在【字段】上的。
 * 但 Kotlin 的属性会编译成"私有字段 + getter 方法"，
 * 所以必须用 @get: 明确告诉编译器"把注解打在 getter 上"。
 * 写成 @Rule val tmp 会编译错误 —— 这是 Kotlin 写 JUnit 4 测试的经典坑。
 *
 * 【为什么这个测试完全不需要 Android】
 * 因为 LocalFolderSource 收的是 java.io.File，是纯 JVM 类型。
 * 所以它跑在你的电脑上，毫秒级出结果，不用启动模拟器。
 * ══════════════════════════════════════════════════════════════
 */
class LocalFolderSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val extensions = setOf("txt")

    /** 在临时目录里造一个文件。返回 File 方便进一步断言 */
    private fun write(name: String, content: String = "内容"): File =
        tmp.newFile(name).apply { writeText(content, Charsets.UTF_8) }

    private fun namesOf(source: LocalFolderSource): List<String> =
        source.listFiles().map { it.displayName }

    // ══ 扩展名过滤 ══════════════════════════════════════════

    @Test
    fun `只列出受支持的扩展名`() {
        write("剑与灯.txt")
        write("说明.md")
        write("封面.jpg")

        val source = LocalFolderSource(tmp.root, extensions)
        assertEquals(listOf("剑与灯.txt"), namesOf(source))
    }

    /**
     * 真实世界里用户从各种渠道拿到的文件，扩展名大小写很乱。
     * File.extension 返回的是原始大小写（"TXT"），
     * 所以代码里必须先 lowercase() 再比较。
     */
    @Test
    fun `大写扩展名也能识别`() {
        write("剑与灯.TXT")
        val source = LocalFolderSource(tmp.root, extensions)
        assertEquals(listOf("剑与灯.TXT"), namesOf(source))
    }

    // ══ 边界情况 ════════════════════════════════════════════

    /**
     * 目录不存在时（比如用户还没往手机里拷过书），
     * 必须返回空列表 + isAvailable() 为 false，【不能抛异常】。
     * 界面靠 isAvailable() 决定要不要显示这个入口。
     */
    @Test
    fun `目录不存在时返回空列表且不可用`() {
        val source = LocalFolderSource(File(tmp.root, "不存在的目录"), extensions)
        assertFalse(source.isAvailable())
        assertTrue(source.listFiles().isEmpty())
    }

    @Test
    fun `目录存在时 isAvailable 为 true`() {
        val source = LocalFolderSource(tmp.root, extensions)
        assertTrue(source.isAvailable())
    }

    /** 子目录要被忽略：只关心平铺在根目录下的文件 */
    @Test
    fun `子目录被忽略`() {
        tmp.newFolder("某个子目录")
        write("剑与灯.txt")

        val source = LocalFolderSource(tmp.root, extensions)
        assertEquals(listOf("剑与灯.txt"), namesOf(source))
    }

    /**
     * 0 字节的文件要过滤掉。
     * 这类文件通常是"下载失败留下的空壳"，列出来只会让用户困惑
     * （点进去必然报"文件是空的"）。
     */
    @Test
    fun `空文件被过滤掉`() {
        write("空书.txt", "")
        write("正常.txt", "内容")

        val source = LocalFolderSource(tmp.root, extensions)
        assertEquals(listOf("正常.txt"), namesOf(source))
    }

    /**
     * 排序必须稳定，否则每次刷新列表顺序都在变，用户会觉得"界面在乱跳"。
     * 这里按文件名排序。
     */
    @Test
    fun `按文件名排序，结果稳定`() {
        write("c.txt")
        write("a.txt")
        write("b.txt")

        val source = LocalFolderSource(tmp.root, extensions)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), namesOf(source))
    }

    // ══ SourceFile 的展示辅助 ═══════════════════════════════

    /**
     * 体积要变成人能看懂的单位。
     * 直接显示 "1283746 B" 对用户毫无意义。
     */
    @Test
    fun `体积被格式化成可读文本`() {
        write("大书.txt", "x".repeat(2048))

        val sf = LocalFolderSource(tmp.root, extensions).listFiles().single()
        assertEquals(2048L, sf.sizeBytes)
        assertTrue("期望以 KB 结尾，实际是 ${sf.readableSize}", sf.readableSize.endsWith("KB"))
    }

    @Test
    fun `小文件的体积用字节显示`() {
        write("小书.txt", "abc")

        val sf = LocalFolderSource(tmp.root, extensions).listFiles().single()
        assertEquals("3 B", sf.readableSize)
    }
}
