package com.example.myapplication.domain.parser

/**
 * 解析器注册表：按文件名把活儿分给合适的解析器。
 *
 * ══ 这个类为什么属于 domain 层 ══════════════════════════════════
 * 它只认识 [BookParser] 这个【接口】，不认识 TxtBookParser / EpubBookParser
 * 这些具体实现。
 *
 * 这是分层的铁律：**外层可以认识内层，内层绝不能认识外层。**
 *   - domain（内层）：业务概念、接口。纯 Kotlin，不 import 任何 Android 或 data 的东西
 *   - data  （外层）：具体实现。可以 import domain
 *
 * 违反这条铁律的后果很具体：如果让下面这个类自己 `new TxtBookParser()`，
 * domain 就得 import data。以后想把解析逻辑拆成独立 module 时，
 * 你会发现 domain 根本搬不走 —— 因为它粘死在 data 上了。
 *
 * 所以"到底有哪些解析器"这件事，交给 data 层的 BookParsers（组合根）去决定。
 * ═══════════════════════════════════════════════════════════════
 *
 * 【为什么是 class 而不是 object】
 * 把解析器列表当构造参数传进来，单元测试就能塞一个【假的】解析器，
 * 专门验证"分发逻辑本身"对不对，完全不碰真实文件。
 * 这叫依赖注入（DI），是最基础也最实用的解耦手段。
 */
class BookParserRegistry(private val parsers: List<BookParser>) {

    /**
     * 当前注册表支持的全部扩展名（小写、不带点）。
     * 模块 3 的书架页会用它来过滤目录里"能打开的文件"。
     */
    val supportedExtensions: Set<String> = parsers.flatMapTo(linkedSetOf()) { it.extensions }

    /**
     * 找一个能处理这个文件的解析器；没有就返回 null。
     * 注意这里用的是 `firstOrNull` + `canParse`，而不是 switch/if-else。
     * 好处：新增格式时这个方法一个字都不用改。
     */
    fun parserFor(fileName: String): BookParser? = parsers.firstOrNull { it.canParse(fileName) }

    /** 这个文件我们能不能打开？UI 用它来提示"暂不支持此格式" */
    fun isSupported(fileName: String): Boolean = parserFor(fileName) != null
}
