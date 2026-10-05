package com.example.local_music_player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 系统 TTS 引擎信息，用于列表展示与选择。 */
data class TtsEngineInfo(
    val packageName: String,
    val label: String,
)

internal fun chooseTtsEnginePackage(savedPackage: String?, engines: List<TtsEngineInfo>): String? =
    engines.firstOrNull { it.packageName == savedPackage }?.packageName
        ?: engines.firstOrNull()?.packageName

internal fun ttsEnginesFromProbe(status: Int, engines: List<TtsEngineInfo>): List<TtsEngineInfo> =
    engines.takeIf { status == TextToSpeech.SUCCESS }.orEmpty()

internal enum class LyricTtsSpeakResult {
    Queued,
    NotReady,
    Failed,
}

internal fun shouldRetryLyricTtsFailure(attempt: Int): Boolean = attempt < 1

/** TTS 朗读输出通道；决定音频属性，不影响 BASS 音乐音量。 */
enum class LyricTtsChannel(val value: String) {
    Media("media"),
    Ringtone("ringtone"),
    Accessibility("accessibility"),
}

internal fun lyricTtsChannelFromValue(value: String?): LyricTtsChannel =
    LyricTtsChannel.values().firstOrNull { it.value == value } ?: LyricTtsChannel.Media

internal fun audioAttributesForChannel(channel: LyricTtsChannel): AudioAttributes {
    val builder = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    when (channel) {
        LyricTtsChannel.Media -> builder.setUsage(AudioAttributes.USAGE_MEDIA)
        LyricTtsChannel.Ringtone -> builder.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        LyricTtsChannel.Accessibility -> builder.setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
    }
    return builder.build()
}

/**
 * 歌词跟唱 TTS 封装。
 * 用独立的 ASSISTANT/SPEECH 音频属性输出，与音乐（USAGE_MEDIA）并行，不改变 BASS 音量。
 * 引擎枚举通过独立的临时 TTS 实例（probe）完成，与朗读用 TTS 互不干扰；
 * 朗读用 TTS 由 [selectEngine] 按需创建。切歌/退出时 stop，避免残留朗读。
 */
class LyricTtsEngine(
    context: Context,
    private val onEnginesReady: (List<TtsEngineInfo>) -> Unit = {},
    private val onEngineReady: (String, Boolean) -> Unit = { _, _ -> },
    private val onUtteranceResult: (String, Boolean) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var enginePackage: String? = null
    /** 当前输出通道；切换后即时重设音频属性，无需重建 TTS。 */
    var channel: LyricTtsChannel = LyricTtsChannel.Media
        set(value) {
            field = value
            if (initReady) runCatching { tts?.setAudioAttributes(audioAttributesForChannel(value)) }
        }
    private var initReady = false
    private var initPending = false
    private var initGeneration = 0
    private var closed = false

    /** 引擎枚举结果；由独立 probe 实例填充。 */
    @Volatile private var cachedEngines: List<TtsEngineInfo> = emptyList()

    init {
        // 独立的临时 TTS 实例（默认引擎），仅用于枚举系统已装引擎；init 回调里读取 engines 后释放。
        // 部分设备（如华为）getEngines() 返回空，需以 PackageManager 查询已安装 TTS 服务包名兜底枚举。
        var probe: TextToSpeech? = null
        probe = TextToSpeech(appContext, { status ->
            if (!closed) {
                val available = if (status == TextToSpeech.SUCCESS) runCatching {
                    val pm = appContext.packageManager
                    val fromApi = probe?.engines.orEmpty().map { it.name }.filter { it.isNotBlank() }
                    val packages = if (fromApi.isNotEmpty()) fromApi else installedTtsPackages(pm)
                    packages.map { pkg ->
                        TtsEngineInfo(
                            packageName = pkg,
                            label = runCatching {
                                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                            }.getOrDefault(pkg),
                        )
                    }
                }.getOrDefault(emptyList()) else emptyList()
                cachedEngines = ttsEnginesFromProbe(status, available)
                onEnginesReady(cachedEngines)
            }
            runCatching { probe?.shutdown() }
        })
    }

    /** 查询系统已安装的 TTS 服务包名；用于 getEngines() 返回空的设备兜底。 */
    private fun installedTtsPackages(pm: PackageManager): List<String> {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        return runCatching {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** 枚举系统已安装的 TTS 引擎；返回空列表表示系统无可用引擎。 */
    fun engines(): List<TtsEngineInfo> = cachedEngines

    /** 选择引擎；首次初始化或在引擎变更时重建实际朗读用的 TTS。 */
    fun selectEngine(packageName: String?) {
        if (enginePackage == packageName && tts != null && (initPending || initReady)) {
            if (initReady && packageName != null) onEngineReady(packageName, true)
            return
        }
        val generation = ++initGeneration
        enginePackage = packageName
        initReady = false
        initPending = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (packageName == null) return
        initPending = true
        tts = TextToSpeech(
            appContext,
            { status ->
                if (closed || generation != initGeneration || enginePackage != packageName) return@TextToSpeech
                initPending = false
                initReady = status == TextToSpeech.SUCCESS
                if (initReady) configure(tts)
                else {
                    runCatching { tts?.shutdown() }
                    tts = null
                }
                onEngineReady(packageName, initReady)
            },
            packageName,
        )
    }

    private fun configure(instance: TextToSpeech?) {
        instance ?: return
        runCatching {
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { onUtteranceResult(it, true) }
                }

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { onUtteranceResult(it, false) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { onUtteranceResult(it, false) }
                }
            })
            instance.setAudioAttributes(audioAttributesForChannel(channel))
            instance.setLanguage(Locale.getDefault())
            if (instance.isLanguageAvailable(Locale.getDefault()) !in AVAILABLE_LANGUAGES) {
                instance.setLanguage(Locale.CHINESE)
            }
        }
    }

    fun isReady(): Boolean = initReady && tts != null && enginePackage != null

    /** 朗读一句歌词；中断上一句（QUEUE_FLUSH）。 */
    internal fun speak(text: String, utteranceId: String): LyricTtsSpeakResult {
        if (!isReady()) return LyricTtsSpeakResult.NotReady
        val spoken = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)
        return if (spoken == TextToSpeech.SUCCESS) LyricTtsSpeakResult.Queued else LyricTtsSpeakResult.Failed
    }

    /** 立即停止当前朗读。 */
    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        closed = true
        initGeneration += 1
        runCatching { tts?.shutdown() }
        tts = null
        initReady = false
        initPending = false
    }

    private companion object {
        val AVAILABLE_LANGUAGES = setOf(
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
        )
    }
}
