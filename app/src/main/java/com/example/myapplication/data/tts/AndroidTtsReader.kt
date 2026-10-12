package com.example.myapplication.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.myapplication.domain.tts.SpeechSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 设备上的一个 TTS 引擎。
 *
 * [name] 是引擎的完整服务名（比如 `com.google.android.tts`），
 * 初始化 [TextToSpeech] 时要把它传进去指定用哪个引擎。
 */
data class TtsEngine(
    val name: String,
    /** 给用户看的名字，取不到就退回用 [name] */
    val label: String,
    val languages: List<TtsLanguage>,
)

/**
 * 引擎支持的一种语言。
 *
 * [isReady] 区分两种"支持"：
 *   · true  —— 语音数据已经装在设备上，现在就能念
 *   · false —— 引擎声称支持，但数据包没下载（需要用户去系统设置里下）
 *
 * 这个区分很重要：用户看到十几种语言全列出来，
 * 兴冲冲选了个没数据的，结果按了没反应 —— 那体验很差。
 * 界面上应该把没数据的标出来。
 */
data class TtsLanguage(
    val locale: Locale,
    val displayName: String,
    val isReady: Boolean,
)

/**
 * 基于 Android 系统 TTS 的朗读器。
 *
 * ══ 【为什么这个类要放在 data 层】═══════════════════════════════
 * 它依赖 `android.speech.tts`，是纯 Android 的东西 ——
 * 和 BookParser、BookSource 一样属于"外部世界的适配器"。
 * 领域层只认识 [SpeechSegment] 这种纯数据，不认识 TextToSpeech。
 *
 * ══ 【设备上没有引擎怎么办】═════════════════════════════════════
 * 这不是异常情况，而是**完全可能发生的正常情况**（尤其电视盒子）。
 * 所以 [probeEngines] 返回空列表是合法结果，界面必须处理这个分支，
 * 而不是假定"一定有引擎"。
 * ═══════════════════════════════════════════════════════════════
 */
class AndroidTtsReader(private val context: Context) {

    /** 当前正在使用的引擎实例。为 null 表示还没准备好 */
    private var tts: TextToSpeech? = null

    /** 正在朗读的分段列表，用于把回调里的 utteranceId 映射回序号 */
    private var currentSegments: List<SpeechSegment> = emptyList()

    /** 每段开始朗读时的回调，参数是段序号 */
    var onSegmentStart: ((Int) -> Unit)? = null

    /** 全部读完时的回调 */
    var onFinished: (() -> Unit)? = null

    // ══ 探测器 ══════════════════════════════════════════════

    /**
     * 探测设备上所有可用的 TTS 引擎，以及每个引擎支持的语言。
     *
     * ══ 【为什么这么慢，以及为什么值得】════════════════════════════
     * 每个引擎都要单独创建一个 [TextToSpeech] 实例才能问它"你支持哪些语言"，
     * 而创建实例是异步的（几百毫秒）。所以整体耗时 ≈ 引擎数 × 300ms。
     *
     * 但这笔开销只花一次（用户点"朗读"的时候），换来的是
     * 把"设备到底行不行"这个最大的未知项，变成界面上看得见的事实。
     * 对电视这种设备差异极大的平台，这个探测非常有价值。
     *
     * @return 只返回"至少支持一种语言"的引擎。全都不可用就是空列表。
     */
    suspend fun probeEngines(): List<TtsEngine> {
        // 第一步：先随便建一个实例，只为拿到"系统里有哪些引擎"
        val probe = createTts(engineName = null) ?: return emptyList()
        val infos = try {
            probe.engines.orEmpty()
        } catch (e: Exception) {
            emptyList()
        } finally {
            probe.shutdown()
        }

        if (infos.isEmpty()) return emptyList()

        // 第二步：逐个引擎单独建实例，问它支持哪些语言
        return infos.mapNotNull { info ->
            val instance = createTts(info.name) ?: return@mapNotNull null
            val languages = try {
                listReadyLanguages(instance)
            } catch (e: Exception) {
                emptyList()
            } finally {
                instance.shutdown()
            }
            if (languages.isEmpty()) return@mapNotNull null
            TtsEngine(
                name = info.name,
                label = runCatching { info.label }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: info.name,
                languages = languages,
            )
        }
    }

    /**
     * 问一个引擎："你支持哪些语言？"
     *
     * availableLanguages 返回的是引擎【声称】支持的集合，
     * 但里面可能混着"没下数据包"的。所以逐个用 isLanguageAvailable
     * 复核一遍，把"支持"和"真的能用"分开标出来。
     */
    private fun listReadyLanguages(instance: TextToSpeech): List<TtsLanguage> {
        val all = instance.availableLanguages.orEmpty()
        return all.mapNotNull { locale ->
            val status = try {
                instance.isLanguageAvailable(locale)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            // 小于 LANG_AVAILABLE(0) 就是不可用（NOT_SUPPORTED / MISSING_DATA 等）
            val available = status >= TextToSpeech.LANG_AVAILABLE
            if (!available) return@mapNotNull null

            TtsLanguage(
                locale = locale,
                displayName = describeLocale(locale),
                // LANG_MISSING_DATA 的情况上面已经被 available 判掉了，
                // 能走到这里的都是数据就绪的
                isReady = true,
            )
        }.sortedWith(
            // 中文排最前（这是个中文阅读器），其余按语言标签排序
            compareBy(
                { if (it.locale.language == "zh") 0 else 1 },
                { it.locale.toLanguageTag() },
            )
        )
    }

    /** 把 Locale 变成"中文（中国） zh-CN"这种一眼能懂的名字 */
    private fun describeLocale(locale: Locale): String {
        val native = runCatching { locale.getDisplayName(Locale.CHINESE) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: locale.getDisplayName()
        val tag = locale.toLanguageTag()
        return if (native.isNullOrBlank() || native == tag) tag else "$native  $tag"
    }

    // ══ 朗读 ════════════════════════════════════════════════

    /**
     * 用指定引擎和语言准备朗读。
     *
     * @return 是否准备成功。false 说明这个"引擎 + 语言"组合实际不可用
     */
    suspend fun prepare(engineName: String, locale: Locale): Boolean =
        withContext(Dispatchers.Main) {
            shutdown()

            val instance = createTts(engineName) ?: return@withContext false

            val langResult = try {
                instance.setLanguage(locale)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }

            if (langResult < TextToSpeech.LANG_AVAILABLE) {
                instance.shutdown()
                return@withContext false
            }

            instance.setOnUtteranceProgressListener(progressListener)
            tts = instance
            true
        }

    /**
     * 开始朗读一串分段。
     *
     * 【为什么用 QUEUE_ADD 逐段入队，而不是拼成一大段】
     * 逐段入队让引擎能一段一段回调 onStart/onDone，
     * 我们才知道"现在念到第几段了"——这是做进度同步的前提。
     * 拼成一大段的话，中途就是个黑盒。
     */
    fun speak(segments: List<SpeechSegment>) {
        val instance = tts ?: return
        currentSegments = segments

        segments.forEachIndexed { index, seg ->
            instance.speak(
                seg.text,
                TextToSpeech.QUEUE_ADD,
                null,
                utteranceId(index),
            )
        }
    }

    /** 立即停止，并清空队列 */
    fun stop() {
        currentSegments = emptyList()
        runCatching { tts?.stop() }
    }

    /** 释放引擎。界面销毁时必须调，否则会漏一个系统服务连接 */
    fun shutdown() {
        currentSegments = emptyList()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    // ══ 内部实现 ════════════════════════════════════════════

    private fun utteranceId(index: Int) = "seg:$index"

    private fun indexOfUtterance(id: String?): Int =
        id?.removePrefix("seg:")?.toIntOrNull() ?: -1

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            val index = indexOfUtterance(utteranceId)
            if (index >= 0) onSegmentStart?.invoke(index)
        }

        override fun onDone(utteranceId: String?) {
            // 最后一段读完 → 通知"全部结束"
            val index = indexOfUtterance(utteranceId)
            if (index >= 0 && index == currentSegments.lastIndex) {
                onFinished?.invoke()
            }
        }

        @Deprecated("Android 的旧回调，新代码走 onError(String, Int)")
        override fun onError(utteranceId: String?) {
            // 某一段失败了，不打断后面的 —— 用户至少还能听到剩下的内容
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            // 同上：静默跳过。这里刻意不抛异常、也不弹提示，
            // 因为朗读失败的最常见原因就是"某段文本引擎处理不了"，
            // 为它中断整本书的朗读并不划算。
        }
    }

    /**
     * 创建一个 [TextToSpeech] 实例并等它初始化完。
     *
     * ══ 【这里有个容易写错的坑】══════════════════════════════════
     * 构造函数是【立即返回】的，初始化结果通过回调异步给出。
     * 而回调可能在构造函数返回【之前】就被触发 ——
     * 如果直接在回调里用 `val tts = TextToSpeech(...)` 这个变量，
     * 会拿到 null（Kotlin 会报"捕获前未初始化"或者拿到 null）。
     *
     * 所以这里用一个 [CompletableDeferred] 当信号：
     * 先在外面创建好实例、再 await 初始化结果，顺序就不会有歧义。
     *
     * 【为什么要加超时】
     * 有些设备上的引擎会卡住不回调。不加超时的话这个协程会永远挂着，
     * 用户的"朗读"按钮就一直转圈 —— 那比失败还糟糕。
     * ═══════════════════════════════════════════════════════════════
     */
    private suspend fun createTts(engineName: String?): TextToSpeech? =
        withContext(Dispatchers.Main) {
            val initResult = CompletableDeferred<Int>()

            val instance = try {
                if (engineName == null) {
                    TextToSpeech(context) { status -> initResult.complete(status) }
                } else {
                    TextToSpeech(context, { status -> initResult.complete(status) }, engineName)
                }
            } catch (e: Exception) {
                // 指定的引擎不存在时，构造函数可能直接抛
                return@withContext null
            }

            val status = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                runCatching { initResult.await() }.getOrNull()
            }

            if (status != TextToSpeech.SUCCESS) {
                runCatching { instance.shutdown() }
                null
            } else {
                instance
            }
        }

    private companion object {
        /**
         * 引擎初始化超时。
         * 3 秒是权衡：正常的引擎 200~500ms 就绪，
         * 而探测时如果一个引擎卡住，用户也不希望整个探测流程被它拖死。
         */
        const val INIT_TIMEOUT_MS = 3000L
    }
}
