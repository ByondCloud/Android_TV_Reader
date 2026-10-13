package com.cloud.androidtvreader.data.parser

// 【这行 import 必须有】BookParserRegistry 在 domain 层，和本文件不同包。
// 注意：Android Studio 曾经把它自动删掉过 —— 因为当时它指向的包路径确实不存在，
// IDE 判定"这是个没用的 import"就清理了。删掉之后错误并不会消失，
// 只是从"导入失败"变成了"根本没导入"。
import com.cloud.androidtvreader.domain.parser.BookParserRegistry


/**
 * 【组合根 Composition Root】
 *
 * 整个 App 里，**唯一一处**"知道所有具体解析器存在"的地方。
 *
 * 【为什么不让 BookParserRegistry 自己 new TxtBookParser】
 * 如果把 `default()` 写在 BookParserRegistry 里，domain 包就得 import data 包 ——
 * 依赖方向反了。后果是：以后要把解析逻辑挪到独立 module 时，
 * 你会发现 domain 根本搬不走，因为它粘在了 data 上。
 *
 * 记住这个方向：**外层可以认识内层，内层绝不能认识外层。**
 *
 * 【以后加 epub / pdf】就在这里加一行，别处都不用改。
 */
object BookParsers {

    val registry: BookParserRegistry by lazy {
        BookParserRegistry(
            listOf(
                TxtBookParser(),
                // EpubBookParser(),   // 模块 8
                // PdfBookParser(),    // 模块 8
            )
        )
    }
}