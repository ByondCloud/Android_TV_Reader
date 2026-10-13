package com.cloud.androidtvreader.data.parser

/**
 * 文本规范化：把"脏"原文变成一套统一、可预测的形式。
 *
 * 【为什么必须有这一步】
 * 换行符在这三种文件里都不一样：
 *   - Windows 文件：\r\n
 *   - 老 Mac 文件  ：\r
 *   - Linux/网络  ：\n
 * 如果不在入口处统一，那么"按行切分"的代码就得同时处理三种情况，
 * 而且每一处用到行的地方都要重复处理 —— 迟早会漏。
 *
 * 统一在入口做一次，后面所有代码都可以假设："换行就是 \n"。
 */
object TextNormalizer {

    /** 3 个及以上的连续换行 → 压缩成 2 个（即最多保留一个空行） */
    private val MANY_BLANK_LINES = Regex("\n{3,}")

    fun normalize(raw: String): String {
        var s = raw

        // ① 去掉解码后残留的 BOM 字符。
        //    UTF-8 的 BOM 如果没被 CharsetDetector 的 bomLength 跳过，
        //    会变成 U+FEFF 这个不可见字符留在正文开头，导致第一行判断出错。
        if (s.startsWith('\uFEFF')) s = s.substring(1)

        // ② 统一换行符。注意顺序：必须先处理 \r\n，再处理单独的 \r，
        //    否则 \r\n 会被拆成两个 \n。
        s = s.replace("\r\n", "\n").replace('\r', '\n')

        // ③ 压缩连续空行。
        //    【注意这里为什么不能直接删掉所有空行】
        //    章节切分依赖"标题行前面必须是一个空行"这条规则（守卫 2）。
        //    如果把空行全删了，那个守卫就废了，误判率会暴涨。
        //    所以是"压缩到最多一个空行"，不是"删除空行"。
        s = MANY_BLANK_LINES.replace(s, "\n\n")

        return s
    }
}