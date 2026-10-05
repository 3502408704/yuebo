package com.example.local_music_player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「工具」页第一个工具：AI TTS 工作台（工具页子页面，无底栏；内部页面 key 与窗口标题由
 * MusicApp 托管，本文件只管页面内容）。
 *
 * 主页 = 脚本工作流：左上「导入脚本」（txt 文本）、右上「合成历史」（长按删除/另存到本地）、
 * 中间脚本编辑框（右侧 清除 + AI 脚本生成）；解析出的角色轨道卡显示角色名、段数与已合成状态，
 * 点按或读屏 customActions 给出三个动作：编辑文本 / 编辑语音 / 合成轨道。
 * 「编辑语音」进角色语音子页（TtsVoicePages.kt）：音色来源与引擎都是下拉菜单（目录动态生成，
 * 云加载零发版）、台词卡（该角色轨道文本）+ AI 情感控制（逐行情绪标签，合成参数不进文本）、
 * 导演面板顶部置顶 AI 导演、「合成参数」独立子页（参数随引擎异步加载）、
 * 音色列表是语言分类子页（每个分类专属入口，分类内发音人支持试听——长按菜单与 customActions）。
 * AI 赋能走服务端 /v1/ai/text（vivo 蓝心，密钥只进服务端，见 TtsTextAi.kt）：AI 导演、
 * AI 音色设计（优化提示词 或 朗读轨道台词自动分析）、AI 脚本化；角色轨道合成细节见 TtsAudiobook.kt。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，
 * 不参与编译；依赖的 YueboAccount/RemoteOnlineCatalog 等服务端客户端代码已删除。
 */

/** 参考音频解码后上限：MiMo 要求 base64 后 ≤10MB，7MB→约 9.3MB。 */
internal const val TTS_MAX_REFERENCE_BYTES = 7 * 1024 * 1024

/** 合成链路日志标签（logcat 过滤用：失败/重试均落一条，便于弱网定位）。 */
private const val TTS_LOG_TAG = "TtsSynth"

/** org.json 的 optString 对显式 null 返回字面量 "null"，统一收口。 */
private fun optText(obj: JSONObject, key: String): String {
    if (obj.isNull(key)) return ""
    return obj.optString(key, "").takeIf { it != "null" } ?: ""
}

// ==== 目录与工具清单（纯函数，可 JVM 单测） ====

internal data class TtsVoice(
    val id: String,
    val label: String,
    val lang: String,
    val gender: String,
    val sample: String,
    /** 引擎真实可用的情感风格（英文 style 名）；仅支持风格控制的音色下发，其余为空。 */
    val styles: List<String> = emptyList(),
    /** 声音特质（服务端目录翻译）；空串=未提供。 */
    val traits: String = "",
    /** 适合朗读的内容（服务端目录翻译）；空串=未提供。 */
    val suitable: String = "",
)

internal data class TtsParamSpec(
    val name: String,
    val label: String,
    val type: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: String,
    val options: List<TtsToolOption> = emptyList(),
)

internal data class TtsModelInfo(
    val id: String,
    val label: String,
    val engine: String,
    val mode: String,
    val price: String,
    val voices: List<TtsVoice>,
    val maxChars: Int,
    val paramsSchema: List<TtsParamSpec>,
    val supportsInstructions: Boolean,
    val supportsVoiceDesign: Boolean,
    val supportsReferenceAudio: Boolean,
    val supportsTemperature: Boolean,
    val supportsSpeed: Boolean,
    val supportsStyle: Boolean,
    val formats: List<String> = emptyList(),
    /** 目录默认输出格式（微软=mp3、MiMo=mp3、design=wav）；旧目录为空串时回退 formats 首位。 */
    val defaultFormat: String = "",
    /** 是否支持客户端直连官方端点（微软=false：统一走服务器转发器；旧目录缺省=true 保持兼容）。 */
    val supportsDirect: Boolean = true,
)

internal fun decodeTtsModels(payload: JSONObject): List<TtsModelInfo> {
    val models = payload.optJSONArray("models") ?: return emptyList()
    return (0 until models.length()).mapNotNull { index ->
        val item = models.optJSONObject(index) ?: return@mapNotNull null
        val id = optText(item, "id")
        if (id.isBlank()) return@mapNotNull null
        val params = item.optJSONObject("params") ?: JSONObject()
        val voices = item.optJSONArray("voices")?.let { array ->
            (0 until array.length()).mapNotNull { voiceIndex ->
                val voice = array.optJSONObject(voiceIndex) ?: return@mapNotNull null
                val voiceId = optText(voice, "id")
                if (voiceId.isBlank()) return@mapNotNull null
                TtsVoice(
                    id = voiceId,
                    label = optText(voice, "label").ifBlank { voiceId },
                    lang = optText(voice, "lang"),
                    gender = optText(voice, "gender"),
                    sample = optText(voice, "sample"),
                    styles = voice.optJSONArray("styles")?.let { styles ->
                        (0 until styles.length()).mapNotNull { styleIndex ->
                            styles.optString(styleIndex).ifBlank { null }
                        }
                    }.orEmpty(),
                    traits = optText(voice, "traits"),
                    suitable = optText(voice, "suitable"),
                )
            }
        }.orEmpty()
        val schema = item.optJSONArray("params_schema")?.let { array ->
            (0 until array.length()).mapNotNull { specIndex ->
                val spec = array.optJSONObject(specIndex) ?: return@mapNotNull null
                val name = optText(spec, "name")
                if (name.isBlank()) return@mapNotNull null
                TtsParamSpec(
                    name = name,
                    label = optText(spec, "label").ifBlank { name },
                    type = optText(spec, "type").ifBlank { "text" },
                    min = spec.optDouble("min", 0.0).toFloat(),
                    max = spec.optDouble("max", 1.0).toFloat(),
                    step = spec.optDouble("step", 0.05).toFloat(),
                    default = optText(spec, "default"),
                    options = spec.optJSONArray("options")?.let { optionArray ->
                        (0 until optionArray.length()).mapNotNull { optionIndex ->
                            val option = optionArray.optJSONObject(optionIndex) ?: return@mapNotNull null
                            val value = optText(option, "value")
                            if (value.isBlank()) return@mapNotNull null
                            TtsToolOption(value, optText(option, "label").ifBlank { value })
                        }
                    }.orEmpty(),
                )
            }
        }.orEmpty()
        TtsModelInfo(
            id = id,
            label = optText(item, "label").ifBlank { id },
            engine = optText(item, "engine").ifBlank { id },
            mode = optText(item, "mode").ifBlank { "preset" },
            price = optText(item, "price"),
            voices = voices,
            maxChars = item.optInt("max_chars", 10000),
            paramsSchema = schema,
            supportsInstructions = params.optBoolean("instructions"),
            supportsVoiceDesign = params.optBoolean("voice_design"),
            supportsReferenceAudio = params.optBoolean("reference_audio"),
            supportsTemperature = params.optBoolean("temperature"),
            supportsSpeed = params.optBoolean("speed"),
            supportsStyle = params.optBoolean("style"),
            formats = item.optJSONArray("formats")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }.orEmpty(),
            defaultFormat = optText(item, "default_format"),
            supportsDirect = if (item.has("supports_direct")) item.optBoolean("supports_direct") else true,
        )
    }
}

internal data class TtsToolOption(val value: String, val label: String)

/** 云工具参数 schema（服务端 /v1/tools 下发；type=text/textarea/select/image/audio）。 */
internal data class TtsToolParam(
    val name: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val default: String,
    val placeholder: String,
    val maxLength: Int,
    val visibleWhenField: String,
    val visibleWhenValue: String,
    val options: List<TtsToolOption>,
)

internal data class TtsToolEntry(
    val id: String,
    val label: String,
    val description: String,
    val resultType: String,
    val params: List<TtsToolParam>,
)

internal fun decodeTools(payload: JSONObject): List<TtsToolEntry> {
    val tools = payload.optJSONArray("tools") ?: return emptyList()
    return (0 until tools.length()).mapNotNull { index ->
        val item = tools.optJSONObject(index) ?: return@mapNotNull null
        val id = optText(item, "id")
        if (id.isBlank()) return@mapNotNull null
        val params = item.optJSONArray("params")?.let { array ->
            (0 until array.length()).mapNotNull { paramIndex ->
                val param = array.optJSONObject(paramIndex) ?: return@mapNotNull null
                val name = optText(param, "name")
                if (name.isBlank()) return@mapNotNull null
                val visibleWhen = param.optJSONObject("visible_when")
                TtsToolParam(
                    name = name,
                    label = optText(param, "label").ifBlank { name },
                    type = optText(param, "type").ifBlank { "text" },
                    required = param.optBoolean("required"),
                    default = optText(param, "default"),
                    placeholder = optText(param, "placeholder"),
                    maxLength = param.optInt("maxLength"),
                    visibleWhenField = optText(visibleWhen ?: JSONObject(), "field"),
                    visibleWhenValue = optText(visibleWhen ?: JSONObject(), "value"),
                    options = param.optJSONArray("options")?.let { optionArray ->
                        (0 until optionArray.length()).mapNotNull { optionIndex ->
                            val option = optionArray.optJSONObject(optionIndex) ?: return@mapNotNull null
                            val value = optText(option, "value")
                            if (value.isBlank()) return@mapNotNull null
                            TtsToolOption(value, optText(option, "label").ifBlank { value })
                        }
                    }.orEmpty(),
                )
            }
        }.orEmpty()
        TtsToolEntry(
            id = id,
            label = optText(item, "label").ifBlank { id },
            description = optText(item, "description"),
            resultType = optText(item, "result_type").ifBlank { "text" },
            params = params,
        )
    }
}

// ==== 聚合端点请求构造（纯函数，可 JVM 单测） ====

/**
 * 构造 POST /v1/audio/speech 请求体。参数值来自动态 schema 面板（按字段名取已知项），
 * response_format 由调用方决定（单发默认 mp3；角色轨道按 ttsTrackFormatPlan：
 * 压缩选择走 mp3 片段，无损选择走 wav）。
 * [style] 为微软情感风格（mstts:express-as），仅 supportsStyle 的引擎下发；
 * style_degree 取动态参数面板的值。
 */
internal fun buildTtsSpeechRequest(
    model: TtsModelInfo,
    text: String,
    voiceId: String?,
    instructions: String,
    voiceDesign: String,
    referenceDataUri: String?,
    values: Map<String, String>,
    responseFormat: String = "mp3",
    style: String = "",
): JSONObject {
    val body = JSONObject()
    body.put("model", model.id)
    body.put("input", text)
    body.put("response_format", responseFormat)
    if (!voiceId.isNullOrBlank() && model.voices.isNotEmpty()) body.put("voice", voiceId)
    if (model.supportsInstructions && instructions.isNotBlank()) body.put("instructions", instructions.trim())
    if (model.supportsVoiceDesign && voiceDesign.isNotBlank()) body.put("voice_design", voiceDesign.trim())
    if (model.supportsReferenceAudio && !referenceDataUri.isNullOrBlank()) body.put("reference_audio", referenceDataUri)
    if (model.supportsTemperature) {
        values["temperature"]?.toFloatOrNull()?.let { body.put("temperature", it.toDouble()) }
        values["top_p"]?.toFloatOrNull()?.let { body.put("top_p", it.toDouble()) }
        values["seed"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { body.put("seed", it.toInt()) }
    }
    if (model.supportsSpeed) {
        values["speed"]?.toFloatOrNull()?.let { body.put("speed", it.toDouble()) }
    }
    if (model.supportsStyle && style.isNotBlank()) {
        body.put("style", style.trim())
        values["style_degree"]?.toFloatOrNull()?.let {
            body.put("style_degree", it.toDouble().coerceIn(0.5, 2.0))
        }
    }
    return body
}

/** 台词情绪词 → 微软 TTS 情感风格（mstts:express-as 的真实 style 名，未列的不猜）。
 *  与服务端目录的风格清单共用同一套 style 值；反向查询见 [ttsEdgeEmotionLabel]。 */
private val TTS_EDGE_STYLE_TABLE = listOf(
    listOf("高兴", "开心", "快乐", "欢快", "兴奋", "雀跃", "喜悦") to "cheerful",
    listOf("悲伤", "难过", "伤心", "低落", "哀伤", "悲痛") to "sad",
    listOf("愤怒", "生气", "恼怒", "暴怒", "恼火") to "angry",
    listOf("害怕", "恐惧", "紧张", "惊恐", "慌张") to "fearful",
    listOf("温柔", "慈爱", "安慰", "亲切", "柔和", "温和") to "gentle",
    listOf("严肃", "庄重", "坚定") to "serious",
    listOf("平静", "沉稳", "冷静", "镇定") to "calm",
    listOf("友好", "友善") to "friendly",
    listOf("低语", "悄悄", "轻声") to "whisper",
    listOf("播报", "新闻") to "newscast",
    listOf("抒情", "朗诵") to "lyrical",
)

internal fun ttsEdgeStyle(emotion: String): String {
    val text = normalizeTtsEmotion(emotion)
    if (text.isEmpty()) return ""
    val negations = listOf("不要", "并非", "不是", "不", "未", "没", "无", "非", "别")
    var selectedStyle = ""
    var selectedIndex = Int.MAX_VALUE
    var selectedLength = 0
    TTS_EDGE_STYLE_TABLE.forEach { (words, style) ->
        words.forEach { word ->
            var index = text.indexOf(word)
            while (index >= 0) {
                val prefix = text.substring(0, index).trimEnd()
                val negated = negations.any { prefix.endsWith(it) }
                if (!negated && (word.length > selectedLength ||
                        word.length == selectedLength && index < selectedIndex)) {
                    selectedStyle = style
                    selectedIndex = index
                    selectedLength = word.length
                }
                index = text.indexOf(word, index + word.length)
            }
        }
    }
    return selectedStyle
}

/** style → 中文情绪词（目录下发真实风格集的反向映射）；未收录返回空串。 */
internal fun ttsEdgeEmotionLabel(style: String): String =
    TTS_EDGE_STYLE_TABLE.firstOrNull { it.second == style }?.first?.first() ?: ""

/** 情感标签的引擎通道：「风格映射」引擎（如微软，支持 style、无 instructions）返回可映射词表，
 *  其余（MiMo 等，情感走表演指导 instructions）返回空串=自由情绪词。
 *  词表=目录下发的选中音色真实风格集（[voice].styles）的中文标签——AI 选词必中该音色
 *  真实可用的 style；音色无风格清单时返回空串，调用方停用 AI 情感入口。 */
/** 情绪→AI 情感控制的词表：只来自当前音色的真实风格清单（目录下发）。
 *  音色没有风格清单时返回空串——通用词表会产生该音色不支持的 style，服务端只能丢弃，
 *  情感标注形同虚设；调用方应据此停用 AI 情感入口（2026-09-29 修复「风格控制不可用」。）。 */
internal fun ttsEmotionVocabFor(model: TtsModelInfo?, voice: TtsVoice? = null): String {
    if (model != null && model.supportsStyle && !model.supportsInstructions) {
        val labels = voice?.styles.orEmpty()
            .mapNotNull { style -> ttsEdgeEmotionLabel(style).ifBlank { null } }
            .distinct()
        if (labels.isNotEmpty()) return labels.joinToString("、")
    }
    return ""
}

/**
 * 合成请求的重试决策：429（限流）、502/503/504（网关与上游瞬断）与 IOException（移动网络
 * 丢包/波动，服务器侧常为 200 只是响应没送达——2026-09-22 生产日志实证）都可自动重试；
 * [attempt] 从 0 起，最多四次重试机会（退避递增，429 优先用服务端 Retry-After，封顶 30 秒）；
 * 返回等待毫秒；次数用尽或不可重试（如 403 配额/401 登录）返回 null。
 * 502 必须可重试：微软转发器的上游（Azure 握手 429 / WSS 断流）以 30~60 秒为窗口成批失败
 * （2026-09-29 生产日志），502 直接放弃会把窗口内的整段——往往是整轨最后几段——报废。
 */
internal fun ttsSynthRetryDelayMs(
    statusCode: Int,
    retryAfterSeconds: Long,
    attempt: Int,
    errorCode: String = "",
): Long? {
    if (attempt < 0 || attempt > 3) return null
    if (statusCode != 429 && statusCode != 502 && statusCode != 503 && statusCode != 504 && statusCode != 0) return null
    val seconds = when (statusCode) {
        429 -> maxOf(retryAfterSeconds.takeIf { it > 0 } ?: 0L, 4L shl attempt)
        else -> listOf(3L, 8L, 15L, 30L)[attempt]  // 5xx 网关类与 statusCode==0：传输层失败
    }
    return (seconds * 1000).coerceAtMost(30000L)
}

/** 服务端 X-TTS-Format / Content-Type → 客户端文件扩展名（未知一律 mp3，可播）。 */
internal fun ttsFormatOrMp3(xTtsFormat: String?, contentType: String?): String {
    val candidate = (xTtsFormat?.trim()?.lowercase()).takeUnless { it.isNullOrEmpty() }
        ?: contentType?.substringAfter('/')?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() }
        ?: return "mp3"
    return if (candidate in setOf("mp3", "mpeg", "wav", "x-wav", "wave")) {
        if (candidate == "mpeg") "mp3" else if (candidate == "x-wav" || candidate == "wave") "wav" else candidate
    } else {
        "mp3"
    }
}

// ==== 合成任务查询（2026-09-30：任务 ID = 幂等键，轮询代替盲目重发） ====

/** 任务轮询单步间隔：查询是轻请求（不排队不扣费），间隔过密只浪费电量。 */
internal const val TTS_TASK_POLL_INTERVAL_MS = 8_000L

/** 断网/退避期间的等待检查步长。 */
internal const val TTS_TASK_OFFLINE_POLL_MS = 3_000L

/**
 * 任务轮询的落定形态：[Done]=服务端已完成，音频可直接复用（结果缓存回放，不双扣不重合）；
 * [Resend]=任务失败/已过期/查无此任务，同键重发安全（失败已退款、未知未扣费）。
 * decodeTtsTaskWait 返回 null 表示任务仍在执行，调用方继续轮询。
 */
internal sealed class TtsTaskWait {
    class Done(val audio: YueboAccount.TtsAudio) : TtsTaskWait()
    object Resend : TtsTaskWait()
}

/**
 * 任务查询响应 → 落定判定（纯函数，可 JVM 单测）：
 * Content-Type 为音频（audio/ 前缀）→ [TtsTaskWait.Done]（回放音频）；JSON `{"state": …}` →
 * running=null（继续等）；failed/expired/unknown → [TtsTaskWait.Resend]；
 * 解析不了的响应按 unknown 处理（重发只会 409/重执行一次，安全）。
 */
internal fun decodeTtsTaskWait(contentType: String, bytes: ByteArray): TtsTaskWait? {
    val type = contentType.substringBefore(';').trim().lowercase()
    if (type.startsWith("audio/")) {
        if (bytes.isEmpty()) return TtsTaskWait.Resend
        return TtsTaskWait.Done(YueboAccount.TtsAudio(bytes, ttsFormatOrMp3(null, contentType)))
    }
    val state = runCatching { JSONObject(String(bytes)).optString("state") }.getOrDefault("")
    return when (state) {
        "running" -> null
        else -> TtsTaskWait.Resend
    }
}

/** 当前是否有可用网络：断网期间重试只等网络恢复，不向死网络发注定失败的请求。 */
internal fun ttsNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true  // 拿不到连接服务时保守放行，不阻塞合成
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// ==== 合成路线与链路日志（纯函数，可 JVM 单测） ====

/** 同一角色轨道的并发合成上限：2 段并行已能吃满上游单角色配额，更多只会招 429。 */
internal const val TTS_ROLE_CONCURRENCY = 2

/** 409 幂等冲突（同键原任务仍在服务端执行）的累计等待预算：与 420s 读超时同量级，覆盖慢段。 */
internal const val IDEMPOTENCY_CONFLICT_BUDGET_MS = 150_000L

/** 单段合成产物：音频字节 + 实际格式 + 实际路线（日志与对账用）。 */
internal class TtsSegmentAudio(val bytes: ByteArray, val format: String, val route: String)

/**
 * 路线决策：微软和 MiMo 默认都走服务器中转。MiMo 官方直连在移动网络/地区网络上
 * 经常先拿到票据再连接失败，稳定性不值得让每个片段白等一次超时；保留服务端票据
 * 接口给旧客户端和诊断使用，但工作台的正常轨道合成不再主动走它。
 */
internal fun ttsRouteFor(model: TtsModelInfo?, directDisabled: Boolean): String =
    if (model == null || !model.supportsDirect || model.id.startsWith("msedge") ||
        model.id.startsWith("mimo") || directDisabled) {
        "relay"
    } else {
        "mimo-direct"
    }

/**
 * 合成链路结构化日志（logcat 过滤 TtsSynth）：角色/片段（label）、路线、票据耗时、
 * 上游+下载耗时、重试次数、响应格式、字节数、通道/缓存、失败类型与 HTTP 状态码。
 * 成功一条 info、失败一条 warn，弱网定位与「客户端耗时 vs 转发器上游耗时」对账都靠它。
 */
internal fun ttsSynthLog(label: String, route: String, ticketMs: Long, fetchMs: Long, retries: Int,
                         format: String, bytes: Int, provider: String = "", cached: Boolean = false,
                         failure: String? = null, httpStatus: Int = 0) {
    val detail = buildString {
        append("route=$route ticketMs=$ticketMs fetchMs=$fetchMs retries=$retries fmt=$format bytes=$bytes")
        if (provider.isNotBlank()) append(" provider=$provider")
        if (cached) append(" cached=true")
        if (failure != null) append(" failure=$failure")
        if (httpStatus != 0) append(" http=$httpStatus")
    }
    if (failure == null) Log.i(TTS_LOG_TAG, "$label $detail") else Log.w(TTS_LOG_TAG, "$label $detail")
}

// ==== 音色按语言分组（纯函数，可 JVM 单测） ====

internal data class TtsLanguageGroup(val label: String, val voices: List<TtsVoice>)

private val TTS_LANGUAGE_NAMES = mapOf(
    "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语",
    "de" to "德语", "fr" to "法语", "es" to "西班牙语", "ru" to "俄语",
    "pt" to "葡萄牙语", "it" to "意大利语", "ar" to "阿拉伯语", "hi" to "印地语",
    "th" to "泰语", "vi" to "越南语", "id" to "印尼语", "ms" to "马来语",
    "nl" to "荷兰语", "pl" to "波兰语", "tr" to "土耳其语", "sv" to "瑞典语",
    "da" to "丹麦语", "fi" to "芬兰语", "nb" to "挪威语", "no" to "挪威语",
    "cs" to "捷克语", "el" to "希腊语", "he" to "希伯来语", "hu" to "匈牙利语",
    "ro" to "罗马尼亚语", "uk" to "乌克兰语", "bg" to "保加利亚语", "af" to "南非荷兰语",
)

/** locale/语言名 → 展示名；常见 locale 翻成中文，其余回退原始代码。 */
internal fun voiceLanguageLabel(lang: String): String {
    val normalized = lang.trim()
    if (normalized.isEmpty()) return "未标注语言"
    val lower = normalized.lowercase()
    if (lower.startsWith("zh-cn") || normalized == "中文") return "中文（普通话）"
    if (lower.startsWith("zh-hk")) return "粤语"
    if (lower.startsWith("zh-tw")) return "中文（台湾）"
    if (lower.startsWith("zh")) return "中文（其他）"
    if (normalized == "英文") return "英语"
    if (normalized == "中/英") return "中英双语"
    val primary = lower.substringBefore('-')
    return TTS_LANGUAGE_NAMES[primary] ?: primary.uppercase()
}

/** 分组 + 排序：常用语言优先（中文（普通话）置顶），组内按音色名排序。 */
internal fun groupVoicesByLanguage(voices: List<TtsVoice>): List<TtsLanguageGroup> {
    val priority = listOf("中文（普通话）", "粤语", "中文（台湾）", "中文（其他）", "中英双语", "英语")
    val grouped = voices.groupBy { voiceLanguageLabel(it.lang) }
        .map { (label, group) ->
            TtsLanguageGroup(label, group.sortedBy { it.label })
        }
    return grouped.sortedWith(
        compareBy({ group ->
            priority.indexOf(group.label).let { if (it >= 0) it else priority.size }
        }, { group ->
            if (group.label in priority) "" else group.label
        }),
    )
}

// ==== 提示词构造器（移植 onniVoice PromptBuilders：`标签：值。`全角拼接，空字段跳过） ====

internal data class TtsPromptField(val key: String, val label: String)

internal val TTS_DIRECTOR_FIELDS = listOf(
    TtsPromptField("role", "角色"),
    TtsPromptField("scene", "场景"),
    TtsPromptField("goal", "表演目标"),
    TtsPromptField("emotion", "情绪"),
    TtsPromptField("speed", "语速"),
    TtsPromptField("pause", "停顿"),
    TtsPromptField("stress", "重音"),
    TtsPromptField("taboo", "禁忌"),
)

internal val TTS_VOICE_DESCRIPTION_FIELDS = listOf(
    TtsPromptField("age", "年龄"),
    TtsPromptField("gender", "性别"),
    TtsPromptField("texture", "音色质感"),
    TtsPromptField("speed", "语速"),
    TtsPromptField("accent", "口音/方言"),
    TtsPromptField("role", "适合角色"),
    TtsPromptField("mood", "情绪底色"),
    TtsPromptField("tone", "整体语调"),
    TtsPromptField("persona", "人设腔调"),
    TtsPromptField("position", "发声位置"),
    TtsPromptField("rhythm", "节奏停顿"),
    TtsPromptField("breath", "气息表现"),
    TtsPromptField("taboo", "禁忌"),
)

internal fun buildTtsPrompt(fields: List<TtsPromptField>, values: Map<String, String>): String =
    fields.mapNotNull { field ->
        values[field.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { "${field.label}：$it。" }
    }.joinToString("")

// ==== 历史记录（filesDir 本地索引 + 音频文件；纯函数序列化可 JVM 单测） ====

internal data class TtsHistoryEntry(
    val id: String,
    val text: String,
    val voiceLabel: String,
    val modelLabel: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
)

internal fun encodeTtsHistory(entries: List<TtsHistoryEntry>): String {
    val array = JSONArray()
    entries.forEach { entry ->
        array.put(JSONObject()
            .put("id", entry.id)
            .put("text", entry.text)
            .put("voice", entry.voiceLabel)
            .put("model", entry.modelLabel)
            .put("file", entry.fileName)
            .put("size", entry.sizeBytes)
            .put("time", entry.createdAtMillis))
    }
    return array.toString()
}

internal fun decodeTtsHistory(raw: String): List<TtsHistoryEntry> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val fileName = optText(item, "file")
        if (fileName.isBlank()) return@mapNotNull null
        TtsHistoryEntry(
            id = optText(item, "id").ifBlank { fileName },
            text = optText(item, "text"),
            voiceLabel = optText(item, "voice"),
            modelLabel = optText(item, "model"),
            fileName = fileName,
            sizeBytes = item.optLong("size"),
            createdAtMillis = item.optLong("time"),
        )
    }
}.getOrDefault(emptyList())

internal class TtsHistoryStore(context: Context) {
    private val dir = File(context.filesDir, "tts_workbench").apply { mkdirs() }
    private val indexFile = File(dir, "history.json")

    init { runCatching { indexFile.writeText(encodeTtsHistory(decodeTtsHistory(indexFile.readText()))) } }

    fun list(): List<TtsHistoryEntry> = decodeTtsHistory(runCatching { indexFile.readText() }.getOrDefault(""))

    /** 落盘音频 + 写索引（新的在前）；写失败抛 IOException 由调用方提示。 */
    fun save(bytes: ByteArray, text: String, voiceLabel: String, modelLabel: String, format: String): TtsHistoryEntry {
        val now = System.currentTimeMillis()
        val entry = TtsHistoryEntry(
            id = UUID.randomUUID().toString(),
            text = text.take(200),
            voiceLabel = voiceLabel,
            modelLabel = modelLabel,
            fileName = "tts-$now-${UUID.randomUUID()}.$format",
            sizeBytes = bytes.size.toLong(),
            createdAtMillis = now,
        )
        File(dir, entry.fileName).writeBytes(bytes)
        writeIndex(listOf(entry) + list())
        return entry
    }

    /** 只保存临时试听音频（不进历史），顺带清理上一批试听文件。 */
    fun tempFile(bytes: ByteArray, format: String): File {
        dir.listFiles { file -> file.name.startsWith("preview-") }?.forEach { it.delete() }
        return File(dir, "preview-${UUID.randomUUID()}.$format").also { it.writeBytes(bytes) }
    }

    fun delete(entry: TtsHistoryEntry) {
        File(dir, entry.fileName).delete()
        writeIndex(list().filter { it.id != entry.id })
    }

    fun fileOf(entry: TtsHistoryEntry): File = File(dir, entry.fileName)

    private fun writeIndex(entries: List<TtsHistoryEntry>) {
        runCatching { indexFile.writeText(encodeTtsHistory(entries)) }
    }
}

internal fun formatTtsHistoryTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

internal fun formatTtsSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

// ==== 网络客户端 ====

internal class TtsClient(private val account: YueboAccount) {
    fun models(): List<TtsModelInfo> = decodeTtsModels(account.request("GET", "/v1/tts/models"))

    fun tools(): List<TtsToolEntry> = decodeTools(account.request("GET", "/v1/tools"))

    /**
     * 合成（段落为脚本行级、服务端秒级~分钟级返回）：客户端读超时与服务端排队/上游
     * 预算对齐，避免请求仍在服务端排队时手机先断开并触发重复合成。
     * 幂等键：同键重试在服务端命中「已完成结果缓存」原样返回，网络抖动重试不再
     * 双重扣费/重复合成（服务端 2026-09-28 起支持）。
     * 失败抛 RemoteOnlineException。
     */
    fun synthesize(body: JSONObject, idempotencyKey: String = ""): YueboAccount.TtsAudio =
        account.requestAudio("POST", "/v1/audio/speech", body, readTimeoutMs = 420_000, idempotencyKey = idempotencyKey)

    /**
     * 任务状态查询（任务 ID = 提交时的幂等键）：completed 回放音频，
     * running/failed/expired/unknown 返回状态 JSON（decodeTtsTaskWait 判定）。
     */
    fun task(taskId: String): YueboAccount.TtsTaskResponse = account.requestTtsTask(taskId)
}

// ==== 「工具」页：服务端下发的动态清单（全部云加载——tts 进专属工作台，其余进云工具页） ====

@Composable
internal fun ToolsScreen(onOpenTts: () -> Unit, onOpenTool: (TtsToolEntry) -> Unit) {
    val context = LocalContext.current
    val account = (context.applicationContext as MusicApplication).yueboAccount
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf<List<TtsToolEntry>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { TtsClient(account).tools() }
                tools = loaded
                errorMessage = null
            } catch (error: RemoteOnlineException) {
                tools = emptyList()
                errorMessage = error.message
            } catch (error: Exception) {
                tools = emptyList()
                errorMessage = "无法连接在线服务，请检查网络后重试"
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppBar(title = "工具", onBack = null, onDevices = null)
        when {
            tools == null -> Text(
                "正在加载工具列表…",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                errorMessage?.let { message ->
                    item {
                        Column {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { tools = null; reload() }) { Text("重试") }
                        }
                    }
                }
                items(tools.orEmpty(), key = { it.id }) { tool ->
                    // 入口只显示工具名（描述等详情进对应工具页），读屏一行播报
                    Card(
                        Modifier.fillMaxWidth().sizeIn(minHeight = 72.dp)
                            .clickable(
                                onClickLabel = "进入",
                                onClick = {
                                    // 全部工具可用：tts 是专属工作台（原生界面），其余工具走云工具页
                                    //（表单按服务端 params schema 动态渲染）——加新工具零发版
                                    if (tool.id == "tts") onOpenTts() else onOpenTool(tool)
                                },
                            ),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                if (tool.id == "tts") "AI TTS 工作台" else tool.label,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==== AI TTS 工作台：单页脚本工作流（无底栏子页；页面 key/窗口标题由 MusicApp 托管） ====

/** 工作台内部页面：主页（脚本 + 角色轨道）/ 角色语音（编辑语音）/ 音色列表 / 合成参数。 */
internal const val TTS_PAGE_MAIN = "main"
internal const val TTS_PAGE_VOICE = "voice"
internal const val TTS_PAGE_VOICES = "voices"
internal const val TTS_PAGE_PARAMS = "params"

/** 工作台子页窗口标题（读屏 paneTitle；MusicApp 的 screenPaneTitle 调用）。 */
internal fun ttsWorkbenchPaneTitle(page: String, role: String, lang: String): String = when (page) {
    TTS_PAGE_VOICE -> if (role.isBlank()) "角色语音" else "角色语音·$role"
    TTS_PAGE_VOICES -> if (lang.isBlank()) "选择音色" else "音色·$lang"
    TTS_PAGE_PARAMS -> "合成参数"
    else -> "AI TTS 工作台"
}

// ==== 音色来源（动态生成：目录里有哪些模式就列哪些，不写死三选） ====

internal data class TtsModeOption(val mode: String, val label: String)

internal fun ttsModeLabel(mode: String): String = when (mode) {
    "preset" -> "预置音色"
    "design" -> "音色设计"
    "clone" -> "声音克隆"
    else -> mode  // 服务端新增的模式先按原名展示，客户端零改动
}

/** 目录里实际存在的音色来源（某来源没有任何引擎就不出现）；已知模式排前、未知模式按出现序殿后。 */
internal fun ttsModeOptions(models: List<TtsModelInfo>): List<TtsModeOption> {
    val knownOrder = listOf("preset", "design", "clone")
    return models.map { it.mode }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { mode -> knownOrder.indexOf(mode).let { if (it >= 0) it else knownOrder.size } }
        .map { TtsModeOption(it, ttsModeLabel(it)) }
}

internal const val TTS_STATUS_READY = "就绪"

/**
 * 工作台会话级状态（MusicApplication 单例）：角色轨道合成是分钟级长任务，绑定组合作用域
 * 会在用户退出工作台/切标签时被静默取消（表现为「任务丢失」）。运行状态、角色配置、
 * 参考音频与已合成产物都挂在这里——离开页面任务继续跑，返回时进度与结果仍在。
 */
internal class TtsWorkbenchState {
    var running by mutableStateOf(false)
    var status by mutableStateOf(TTS_STATUS_READY)
    var progress by mutableStateOf("")
    var generationJob: Job? = null

    /**
     * 脚本是全工作台唯一事实来源，必须挂应用级单例：子页（角色语音/音色列表/合成参数）
     * 是独立组合实例（AnimatedContent key 含角色名），实例局部状态各有一份——脚本若挂
     * 实例上，进「编辑语音」就变成空脚本，台词卡/AI 导演/合成都拿不到台词（52 批实测）。
     */
    var script by mutableStateOf("")
    var scriptBackup by mutableStateOf("")  // AI 替换/手动清除前的原文（一键还原）

    /** 引擎目录云加载（应用级缓存：子页切换不重复拉取，角色配置默认值也随之稳定）。 */
    var models by mutableStateOf<List<TtsModelInfo>?>(null)

    val roleConfigs = mutableStateMapOf<String, TtsRoleConfig>()
    val roleReferences = mutableStateMapOf<String, Pair<ByteArray, String>>()
    val roleOutputs = mutableStateMapOf<String, TtsHistoryEntry>()

    /**
     * AI 情感控制产物：角色 → 与其台词逐行对齐的情绪标签（合成参数，不写进脚本）。
     * 脚本改动导致分段数变化时按 ttsSegmentEmotion 的对齐校验自动失效。
     */
    val roleEmotions = mutableStateMapOf<String, List<String>>()

    /**
     * 断点续合缓存：角色 → 已完成段的音频字节（按片段索引存放，null=未完成）。
     * 配置/台词指纹一致才复用；段失败时保留已完成部分，再次点合成只补缺失片段；
     * 合成成功后清除。
     */
    val roleChunks = mutableStateMapOf<String, TtsRoleChunks>()

    /**
     * 直连通道本会话连续失败计数（应用级：退出工作台不清零）：连续 2 次失败即
     * 停用该通道直连，后续整轨直接走服务器中转，不再每段白等一次超时。
     */
    val directFailures = mutableStateMapOf<String, Int>()
}

/**
 * [TtsWorkbenchState.roleChunks] 的缓存条目：按片段索引存放可空结果（null=待合成）。
 * 并发完成与乱序返回都按索引写入，拼接永远按脚本顺序；指纹不一致（换配置/改台词）整组失效。
 */
internal class TtsRoleChunks(val configHash: Int, val textsHash: Int, chunkCount: Int) {
    val chunks: MutableList<ByteArray?> = MutableList(chunkCount) { null }
    val formats: MutableList<String?> = MutableList(chunkCount) { null }

    fun put(index: Int, audio: TtsSegmentAudio) {
        chunks[index] = audio.bytes
        formats[index] = audio.format
    }

    fun clear(index: Int) {
        if (index in chunks.indices) {
            chunks[index] = null
            formats[index] = null
        }
    }

    fun clearAll() {
        chunks.indices.forEach(::clear)
    }

    /** 台词分段数变化时对齐容量（保留已完成部分）。 */
    fun resize(size: Int) {
        while (chunks.size < size) {
            chunks.add(null)
            formats.add(null)
        }
        while (chunks.size > size) {
            chunks.removeAt(chunks.size - 1)
            formats.removeAt(formats.size - 1)
        }
    }

    val completedCount: Int get() = chunks.count { it != null }
    val isComplete: Boolean get() = chunks.isNotEmpty() && chunks.none { it == null }
}

@Composable
internal fun TtsWorkbenchScreen(
    page: String,
    role: String,
    lang: String,
    onPageChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onLangChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val account = (context.applicationContext as MusicApplication).yueboAccount
    val scope = rememberCoroutineScope()
    val historyStore = remember { TtsHistoryStore(context) }
    val aiClient = remember { TtsAiClient(account) }
    val client = remember { TtsClient(account) }

    // —— 会话级状态（应用单例）：退出工作台后合成任务继续，脚本/引擎目录/角色配置/
    //    参考音频/产物都在这里——子页是独立组合实例（key 含角色名），只有应用级状态
    //    才能跨实例共享（脚本挂实例上曾导致「编辑语音」里台词为空，见 TtsWorkbenchState 注释） ——
    val ws = (context.applicationContext as MusicApplication).ttsWorkbenchState
    val appScope = (context.applicationContext as MusicApplication).ttsSynthesisScope
    val roleConfigs = ws.roleConfigs
    val roleReferences = ws.roleReferences
    val roleOutputs = ws.roleOutputs
    val roleEmotions = ws.roleEmotions
    val roleChunks = ws.roleChunks
    // 直连熔断计数挂应用级单例（ws.directFailures）：退出工作台不清零，本会话有效

    // —— 引擎目录（云加载：应用级缓存，子页切换不重复拉取） ——
    var loadError by rememberSaveable { mutableStateOf<String?>(null) }
    fun reloadModels() {
        scope.launch {
            try {
                ws.models = withContext(Dispatchers.IO) { TtsClient(account).models() }
                loadError = null
            } catch (error: RemoteOnlineException) {
                loadError = error.message
            } catch (error: Exception) {
                loadError = "无法连接在线服务，请检查网络后重试"
            }
        }
    }
    LaunchedEffect(Unit) { if (ws.models == null) reloadModels() }
    val loadedModels = ws.models.orEmpty()

    // —— 脚本与角色轨道（应用级单例；台词是唯一事实来源，所有子页实例共享同一份） ——
    var script by ws::script
    var scriptBackup by ws::scriptBackup
    val segments = remember(script) { parseTtsAudiobookScript(script) }
    val roles = remember(segments) { audiobookRoles(segments) }
    fun configFor(target: String): TtsRoleConfig {
        roleConfigs[target]?.let { return it }
        val preset = loadedModels.firstOrNull { it.mode == "preset" }
        return TtsRoleConfig(
            mode = "preset",
            modelId = preset?.id.orEmpty(),
            voiceId = ttsDefaultVoiceId(preset),
            params = roleParamsFor(preset, emptyMap()),
            format = ttsDefaultFormatFor(preset),
        )
    }
    fun trackTextOf(target: String): String = roleTrackText(segments, target)

    // —— 运行状态（单行播报：进行中显示进度，否则显示状态；委托到应用级单例，读写即时落位） ——
    var running by ws::running
    var status by ws::status
    var progress by ws::progress
    var generationJob by ws::generationJob

    // —— 本地播放器（独立于主播放引擎：试听合成结果不打断/不改写音乐队列） ——
    val player = remember { ExoPlayer.Builder(context).build() }
    val playerCommands = remember { Handler(Looper.getMainLooper()) }
    var isPlaying by remember { mutableStateOf(false) }
    var playingEntryId by remember { mutableStateOf<String?>(null) }
    val playerReleased = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose {
            playerReleased.set(true)
            player.release()
        }
    }
    fun playFile(file: File) {
        // ExoPlayer 只能在创建线程（main）访问；合成完成后的自动试听从合成协程
        // （ttsSynthesisScope=IO）触发，直调会抛 "Player is accessed on the wrong thread"。
        playerCommands.post {
            if (playerReleased.get()) return@post // 页面已离开、player 已释放：产物仍在历史，跳过试听
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
    }
    fun playEntry(entry: TtsHistoryEntry) {
        val file = historyStore.fileOf(entry)
        if (file.exists()) {
            playingEntryId = entry.id
            playFile(file)
        } else status = "音频文件已丢失"
    }

    // —— 参考音频选择（角色语音页 clone 模式；选择器需在组合层常驻，目标角色经此中转） ——
    var pendingReferenceRole by remember { mutableStateOf<String?>(null) }
    val referenceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pendingReferenceRole ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { readReferenceAudio(context, uri) }
            when {
                loaded == null -> status = "无法读取参考音频，请换一个文件"
                loaded.first.size > TTS_MAX_REFERENCE_BYTES ->
                    status = "参考音频过大（超过 7MB），请截取 8~10 秒后重试"
                else -> {
                    roleReferences[target] = Pair(loaded.first, loaded.second)
                    status = "已为角色「$target」选择参考音频"
                }
            }
        }
    }
    fun pickReferenceFor(target: String) {
        pendingReferenceRole = target
        referenceLauncher.launch("audio/*")
    }

    // —— 音色试听（音色列表子页；只出临时文件不进历史） ——
    fun previewVoice(model: TtsModelInfo, voice: TtsVoice) {
        if (running) {
            status = "正在合成，请稍候再试听"
            return
        }
        scope.launch {
            running = true
            status = "正在试听「${voice.label}」…"
            try {
                val audio = withContext(Dispatchers.IO) {
                    client.synthesize(buildTtsSpeechRequest(
                        model = model,
                        text = voice.sample.ifBlank { "你好，这是${voice.label}的试听。" },
                        voiceId = voice.id.takeIf { model.voices.isNotEmpty() },
                        instructions = "", voiceDesign = "", referenceDataUri = null,
                        values = roleParamsFor(model, emptyMap()),
                    ))
                }
                val file = withContext(Dispatchers.IO) { historyStore.tempFile(audio.bytes, audio.format) }
                status = "试听播放中"
                playFile(file)
            } catch (error: RemoteOnlineException) {
                status = error.message ?: "试听失败：${error.errorCode}（HTTP ${error.statusCode}）"
            } catch (error: Exception) {
                status = "试听失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running = false
            }
        }
    }

    // —— 角色轨道合成（单轨 = 合成轨道；全部 = 有声书整本） ——

    /** 直连失败计数（会话级熔断）+ 结构化日志；连续 2 次后本会话不再尝试该通道直连。 */
    fun recordDirectFailure(kind: String, label: String, error: Exception, stage: String) {
        ws.directFailures[kind] = (ws.directFailures[kind] ?: 0) + 1
        val failures = ws.directFailures[kind] ?: 0
        val tripped = failures >= 2
        Log.w(TTS_LOG_TAG, "$label 直连$stage 失败 kind=$kind failure=${error.javaClass.simpleName}" +
            " msg=${error.message.orEmpty()} 连续失败=$failures" + if (tripped) "（本会话熔断直连）" else "")
        progress = if (tripped) "直连不畅，本会话改走服务器…" else "直连不畅，改走服务器…"
    }

    /** 服务器中转一次（转发器负责 Azure/Edge 双通道、16 连接池与音频缓存）。 */
    suspend fun synthesizeViaRelay(body: JSONObject, label: String, retries: Int, route: String,
                                   idempotencyKey: String = ""): TtsSegmentAudio {
        val started = SystemClock.elapsedRealtime()
        val audio = withContext(Dispatchers.IO) { client.synthesize(body, idempotencyKey) }
        val fetchMs = SystemClock.elapsedRealtime() - started
        ttsSynthLog(label, route, ticketMs = 0, fetchMs = fetchMs, retries = retries,
            format = audio.format, bytes = audio.bytes.size, provider = audio.provider, cached = audio.cached)
        return TtsSegmentAudio(audio.bytes, audio.format, route)
    }

    /**
     * 单段合成（重试外层的单次尝试）：工作台默认服务器中转；只有旧目录明确允许且
     * 路线决策未禁用时才使用直连票据。直连失败仍立即回落中转，避免把一次网络黑洞
     * 放大成整轨失败。票据/直连超时 25s/30s（原 15s/20s 偏紧，弱网误判率高）。
     */
    suspend fun synthesizeDirectFirst(body: JSONObject, model: TtsModelInfo?, label: String, retries: Int,
                                      idempotencyKey: String = ""): TtsSegmentAudio {
        val route = ttsRouteFor(model, (ws.directFailures["mimo"] ?: 0) >= 2)
        if (route == "relay") {
            return synthesizeViaRelay(body, label, retries, "relay", idempotencyKey)
        }
        val ticketStarted = SystemClock.elapsedRealtime()
        val ticket: TtsDirectTicket = try {
            withContext(Dispatchers.IO) {
                decodeTtsDirectTicket(account.requestAi("POST", "/v1/audio/speech",
                    JSONObject(body.toString()).put("direct", true), readTimeoutMs = 25_000))
            } ?: throw RemoteOnlineException("直连票据无效", 502, "tts_direct_ticket")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordDirectFailure("mimo", label, error, "票据")
            return synthesizeViaRelay(body, label, retries, "mimo-fallback", idempotencyKey)
        }
        val ticketMs = SystemClock.elapsedRealtime() - ticketStarted
        val fetchStarted = SystemClock.elapsedRealtime()
        val bytes: ByteArray = try {
            TtsDirect.execute(ticket, 30_000L)
        } catch (error: TimeoutCancellationException) {
            recordDirectFailure("mimo", label, error, "官方合成超时")
            return synthesizeViaRelay(body, label, retries, "mimo-fallback", idempotencyKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordDirectFailure("mimo", label, error, "官方合成")
            return synthesizeViaRelay(body, label, retries, "mimo-fallback", idempotencyKey)
        }
        ws.directFailures.remove("mimo")  // 成功即清零连续失败计数
        ttsSynthLog(label, "mimo-direct", ticketMs = ticketMs,
            fetchMs = SystemClock.elapsedRealtime() - fetchStarted,
            retries = retries, format = ticket.format, bytes = bytes.size)
        return TtsSegmentAudio(bytes, ticket.format, "mimo-direct")
    }

    /** 任务结果回放 → 段产物（route=relay-task：音频来自服务端任务结果缓存）。 */
    fun replayTaskAudio(audio: YueboAccount.TtsAudio, label: String): TtsSegmentAudio {
        ttsSynthLog(label, "relay-task", ticketMs = 0, fetchMs = 0, retries = 0,
            format = audio.format, bytes = audio.bytes.size,
            provider = audio.provider, cached = audio.cached)
        return TtsSegmentAudio(audio.bytes, audio.format, "relay-task")
    }

    /**
     * 按任务 ID 轮询到落定（[TtsTaskWait.Done]/[TtsTaskWait.Resend]）或预算耗尽（null）。
     * 查询失败按「仍未知」继续等；断网时不发查询，只等网络恢复（服务端任务照常推进）。
     */
    suspend fun awaitTaskOutcome(taskId: String, label: String, budgetMs: Long): TtsTaskWait? {
        var waited = 0L
        while (waited < budgetMs) {
            currentCoroutineContext().ensureActive()
            if (!ttsNetworkAvailable(context)) {
                progress = "网络已断开，等待网络恢复后继续「$label」…"
                val step = minOf(TTS_TASK_OFFLINE_POLL_MS, budgetMs - waited)
                delay(step)
                waited += step
                continue
            }
            val response = try {
                withContext(Dispatchers.IO) { client.task(taskId) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TTS_LOG_TAG, "$label 任务查询失败（继续轮询）: ${error.javaClass.simpleName}")
                null
            }
            response?.let { decodeTtsTaskWait(it.contentType, it.bytes) }?.let { return it }
            val step = minOf(TTS_TASK_POLL_INTERVAL_MS, budgetMs - waited)
            delay(step)
            waited += step
        }
        return null
    }

    /** 退避等待；期间断网则等网络恢复再继续（等待总时长仍以 [waitMs] 为上限）。 */
    suspend fun awaitRetryDelay(waitMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            if (ttsNetworkAvailable(context)) {
                delay(remaining)
                return
            }
            progress = "网络已断开，等待网络恢复后自动重试…"
            delay(minOf(remaining, TTS_TASK_OFFLINE_POLL_MS))
        }
    }

    /**
     * 单段合成：自动重试最多四次后退避失败——429/503/502/504 按 [ttsSynthRetryDelayMs]；
     * IOException（移动网络丢包/读超时：响应可能已丢失，而服务端任务仍在执行或已完成）
     * 先按任务 ID 查询结果再决定是否重发。其余错误原样抛出。[label] 用于日志定位。
     *
     * 任务完整性（2026-09-30）：幂等键即任务 ID，在重试外层生成一次，所有尝试共用。
     * 409（同键任务进行中）与传输层失败都不再盲目重发——重发每次都要重新过限流、排队，
     * 会把同一文本变成重复任务白烧配额；改为轮询 GET /v1/audio/tasks/{任务 ID}：
     * 已完成→直接取回音频（不双扣不重合）；仍在执行→继续等（服务端任务不依赖手机
     * 连接，断网期间照常推进，恢复联网后取结果）；失败/查无→同键重发（失败已退款、
     * 未知未扣费，均不双扣）。断网时只等网络恢复，不向死网络空发请求。
     */
    suspend fun synthesizeWithRetry(body: JSONObject, model: TtsModelInfo?, label: String): TtsSegmentAudio {
        var idempotencyKey = java.util.UUID.randomUUID().toString()
        var attempt = 0
        var keyRotated = false
        // 409（idempotency_in_progress）＝同键原任务还在服务端执行（服务端最长 900s）：
        // 等原任务出结果远比换键重做便宜（换键＝白烧一次配额）。累计等待预算 150s。
        var conflictWaitMs = 0L
        while (true) {
            try {
                return synthesizeDirectFirst(body, model, label, attempt, idempotencyKey)
            } catch (error: RemoteOnlineException) {
                if (error.errorCode == "idempotency_completed" && !keyRotated) {
                    keyRotated = true
                    conflictWaitMs = 0L
                    idempotencyKey = java.util.UUID.randomUUID().toString()
                    Log.w(TTS_LOG_TAG, "$label 幂等键已完成但结果已逐出，换新键重做一次")
                    continue
                }
                if (error.statusCode == 409) {
                    // 同键任务仍在服务端执行：轮询任务结果，不再重复 POST（重发只会再 409，
                    // 还要再过一次限流）。累计等待预算与旧实现一致（150s），超时按失败抛出。
                    progress = "上一次同段合成还在服务端进行，等待其结果…"
                    val budget = (IDEMPOTENCY_CONFLICT_BUDGET_MS - conflictWaitMs).coerceAtLeast(0L)
                    when (val outcome = awaitTaskOutcome(idempotencyKey, label, budget)) {
                        is TtsTaskWait.Done -> return replayTaskAudio(outcome.audio, label)
                        is TtsTaskWait.Resend -> {
                            conflictWaitMs = IDEMPOTENCY_CONFLICT_BUDGET_MS
                            continue  // 同键重发：失败已退款、查无未扣费，不双扣
                        }
                        null -> {
                            conflictWaitMs = IDEMPOTENCY_CONFLICT_BUDGET_MS
                            ttsSynthLog(label, "task-timeout", 0, 0, attempt, "", 0,
                                failure = error.errorCode.ifBlank { "idempotency_in_progress" },
                                httpStatus = error.statusCode)
                            throw error
                        }
                    }
                }
                val waitMs = ttsSynthRetryDelayMs(
                    error.statusCode, error.retryAfterSeconds, attempt, error.errorCode)
                if (waitMs == null) {
                    ttsSynthLog(label, "retry-exhausted", 0, 0, attempt, "", 0,
                        failure = error.errorCode.ifBlank { error.javaClass.simpleName },
                        httpStatus = error.statusCode)
                    throw error
                }
                attempt++
                Log.w(TTS_LOG_TAG, "合成重试 $label 第$attempt 次 status=${error.statusCode} 等 ${waitMs}ms")
                progress = "服务繁忙，${(waitMs + 999) / 1000} 秒后自动重试（第 $attempt 次）…"
                awaitRetryDelay(waitMs)
            } catch (error: IOException) {
                val waitMs = ttsSynthRetryDelayMs(0, 0, attempt)
                if (waitMs == null) {
                    ttsSynthLog(label, "retry-exhausted", 0, 0, attempt, "", 0,
                        failure = "IOException:${error.javaClass.simpleName}")
                    throw error
                }
                // 丢包/读超时：响应丢失时服务端任务可能已完成（配额已扣）——先按任务 ID
                // 查询：拿到音频即收尾；仍在执行就等它落定；确认失败/查无才退避重发。
                when (val outcome = awaitTaskOutcome(
                    idempotencyKey, label, IDEMPOTENCY_CONFLICT_BUDGET_MS)) {
                    is TtsTaskWait.Done -> return replayTaskAudio(outcome.audio, label)
                    is TtsTaskWait.Resend -> {}
                    null -> Log.w(TTS_LOG_TAG, "$label 任务查询预算耗尽，退避后重发同键任务")
                }
                attempt++
                Log.w(TTS_LOG_TAG, "合成重试 $label 第$attempt/4 次 transport=${error.javaClass.simpleName} 等 ${waitMs}ms")
                progress = "网络波动，${(waitMs + 999) / 1000} 秒后自动重试（第 $attempt/4 次）…"
                awaitRetryDelay(waitMs)
            }
        }
    }

    suspend fun synthesizeRoleNow(target: String): TtsHistoryEntry? {
        val roleSegments = segments.filter { it.roleName == target }
        if (roleSegments.isEmpty()) {
            status = "「$target」没有台词，已跳过"
            return null
        }
        val config = configFor(target)
        var referenceUri: String? = null
        // 断点续合指纹的音色成分：设计模式不能用校准音频的字节哈希——校准音频每次重新
        // 合成字节都不同，指纹每次都变会让磁盘续合永不命中、配额反复重烧。
        // 设计模式用「音色描述+基础风格」的稳定哈希；克隆模式参考音频固定，哈希其内容即可。
        var referenceFingerprint = 0
        val speakingModel: TtsModelInfo? = when (config.mode) {
            "design" -> {
                // 设计模式：先合成校准音频，再全程用克隆保持角色音色一致
                val designModel = loadedModels.firstOrNull { it.id == config.modelId && it.mode == "design" }
                    ?: loadedModels.firstOrNull { it.mode == "design" }
                val cloneModel = loadedModels.firstOrNull { it.mode == "clone" }
                if (designModel == null || cloneModel == null || config.voiceDescription.isBlank()) {
                    status = "角色「$target」的音色设计未配置完整，请在编辑语音里完成"
                    return null
                }
                progress = "正在校准「$target」的音色…"
                val calibration = synthesizeWithRetry(buildTtsSpeechRequest(
                    model = designModel, text = TTS_AUDIOBOOK_CALIBRATION_TEXT,
                    voiceId = null, instructions = config.baseStyle,
                    voiceDesign = config.voiceDescription, referenceDataUri = null,
                    values = roleParamsFor(designModel, config.params), responseFormat = "wav"),
                    designModel, "「$target」校准")
                referenceUri = "data:audio/wav;base64," +
                    android.util.Base64.encodeToString(calibration.bytes, android.util.Base64.NO_WRAP)
                referenceFingerprint = ("design:${config.voiceDescription}|${config.baseStyle}").hashCode()
                cloneModel
            }
            "clone" -> {
                val reference = roleReferences[target]
                if (reference == null) {
                    status = "角色「$target」还没有参考音频，请在编辑语音里选择"
                    return null
                }
                referenceUri = "data:${reference.second};base64," +
                    android.util.Base64.encodeToString(reference.first, android.util.Base64.NO_WRAP)
                referenceFingerprint = referenceUri.hashCode()
                loadedModels.firstOrNull { it.id == config.modelId && it.mode == "clone" }
                    ?: loadedModels.firstOrNull { it.mode == "clone" }
            }
            else -> ttsRoleModel(config, loadedModels)
        }
        val speaking = speakingModel ?: run {
            status = "角色「$target」没有可用语音引擎，已跳过"
            return null
        }
        val emotionTags = roleEmotions[target]
        // 片段格式跟随用户选择（无损=wav；压缩选择走 mp3，降低中转传输时间与断流概率），
        // 最终产物按 ttsTrackFormatPlan 决策落盘（m4a/wav），不再硬编码。
        val formatPlan = ttsTrackFormatPlan(config.format)
        val outputFormat = formatPlan.segmentFormat
        // 断点续合：情绪、语气、场景或音色变化都必须使旧片段失效，不能只比较正文。
        val textsHash = roleSegments.joinToString("\u0001") {
            listOf(it.roleName, it.text, it.emotion, it.tone, it.scene, it.pause, it.tags)
                .joinToString("\u0002")
        }.hashCode()
        val configHash = listOf(
            config,
            speaking.id,
            speaking.mode,
            formatPlan.deliverableFormat,
            referenceFingerprint,
            emotionTags?.hashCode() ?: 0,
        ).hashCode()
        val store = roleChunks[target]?.takeIf {
            it.configHash == configHash && it.textsHash == textsHash && it.completedCount > 0
        }?.also { it.resize(roleSegments.size) }
            ?: TtsRoleChunks(configHash, textsHash, roleSegments.size).also { roleChunks[target] = it }
        // 磁盘续合层：进程被杀后已完成段还在缓存目录里，恢复后同样只补缺失片段；
        // 旧指纹目录顺手清掉（换配置/改台词后不会无限积累）
        val chunkStore = TtsChunkStore(context.cacheDir)
        withContext(Dispatchers.IO) {
            chunkStore.retainOnly(target, configHash, textsHash)
            chunkStore.restore(target, store)
        }
        if (store.completedCount in 1 until roleSegments.size) {
            progress = "「$target」${store.completedCount}/${roleSegments.size} 段已完成，只补缺失片段"
        }
        val segmentValues = roleParamsFor(speaking, config.params)
        suspend fun synthesizeSegmentAudio(segment: TtsAudiobookSegment, index: Int): TtsSegmentAudio {
            val emotion = ttsSegmentEmotion(emotionTags, roleSegments.size, index, segment.emotion)
            val actingSegment = if (emotion == segment.emotion) segment else segment.copy(emotion = emotion)
            val segmentStyle = if (speaking.supportsStyle) {
                // 显式脚本/AI 情绪优先；有情绪但微软没有对应 style 时宁可不加
                // style，也不能偷偷套用角色默认风格造成互斥。
                if (emotion.isBlank()) segmentValues["style"].orEmpty() else ttsEdgeStyle(emotion)
            } else ""
            val parts = splitTtsAudiobookText(segment.text)
            val audios = parts.mapIndexed { partIndex, part ->
                val partLabel = if (parts.size == 1) {
                    "「$target」${index + 1}/${roleSegments.size}"
                } else {
                    "「$target」${index + 1}/${roleSegments.size}·${partIndex + 1}/${parts.size}"
                }
                if (parts.size > 1) {
                    progress = "正在合成长台词「$target」${index + 1}/${roleSegments.size}（${partIndex + 1}/${parts.size}）"
                }
                synthesizeWithRetry(buildTtsSpeechRequest(
                    model = speaking,
                    text = part,
                    voiceId = config.voiceId.takeIf { speaking.voices.isNotEmpty() },
                    instructions = buildTtsAudiobookSegmentPrompt(target, config.baseStyle, actingSegment),
                    voiceDesign = config.voiceDescription,
                    referenceDataUri = referenceUri,
                    values = segmentValues,
                    responseFormat = outputFormat,
                    style = segmentStyle,
                ), speaking, partLabel)
            }
            if (audios.size == 1) return audios[0]
            // 长台词拆分段：如实拼接为单段（WAV 重写头部长度顺接，其余字节直通，零转码）
            val bytes = withContext(Dispatchers.Default) { concatTtsTrackFaithful(audios.map { it.bytes }) }
            return TtsSegmentAudio(
                bytes = bytes,
                format = audios.first().format,
                route = "${audios.first().route}-split",
            )
        }
        suspend fun synthesizeSegmentWithValidation(segment: TtsAudiobookSegment, index: Int): TtsSegmentAudio {
            var decodeAttempt = 0
            while (true) {
                try {
                    val audio = synthesizeSegmentAudio(segment, index)
                    // HTTP 200 也可能只收到截断音频；先实际解码再写入断点缓存，
                    // 这样弱网坏包会自动重取，不会等到用户下一次点击才发现。
                    withContext(Dispatchers.Default) { decodeTtsChunkToPcm(context, audio.bytes) }
                    return audio
                } catch (failure: TtsAudioDecodeException) {
                    if (decodeAttempt >= 1) throw failure
                    decodeAttempt++
                    Log.w(TTS_LOG_TAG, "「$target」${index + 1} 段音频校验失败，自动重取")
                    progress = "音频包不完整，正在自动重取「$target」第 ${index + 1} 段…"
                    delay(1000L)
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        // 旧的断点缓存可能来自格式漂移或上次网络截断；先验证并清掉坏段，
        // 随后的缺段派发会自动补齐，不要求用户手动删除缓存。
        for (index in store.chunks.indices) {
            val cached = store.chunks[index] ?: continue
            try {
                withContext(Dispatchers.Default) { decodeTtsChunkToPcm(context, cached) }
            } catch (failure: TtsAudioDecodeException) {
                store.clear(index)
                Log.w(TTS_LOG_TAG, "「$target」缓存第 ${index + 1} 段无效，已清除并自动补合")
            }
        }
        // 同轨最多 2 并发（不跨角色并发，整本仍按角色顺序推进）：结果按索引写入，不按完成
        // 顺序拼接；任一片段失败停止派发新片段，已完成片段保留——下次只请求缺失片段
        val dispatchLock = Any()
        var failure: Exception? = null
        var cursor = 0
        val missing = roleSegments.indices.filter { store.chunks[it] == null }
        val trackStarted = SystemClock.elapsedRealtime()
        try {
            coroutineScope {
                repeat(minOf(TTS_ROLE_CONCURRENCY, missing.size)) {
                    launch {
                        while (true) {
                            val index = synchronized(dispatchLock) {
                                if (failure != null || cursor >= missing.size) -1 else missing[cursor++]
                            }
                            if (index < 0) return@launch
                            currentCoroutineContext().ensureActive()
                            val segment = roleSegments[index]
                            try {
                                val audio = synthesizeSegmentWithValidation(segment, index)
                                synchronized(dispatchLock) { store.put(index, audio) }
                                withContext(Dispatchers.IO) { chunkStore.save(target, store, index) }
                                progress = "正在合成「$target」${store.completedCount}/${roleSegments.size}"
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (cancel: Exception) {
                                synchronized(dispatchLock) { if (failure == null) failure = cancel }
                                return@launch
                            }
                        }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        }
        if (failure != null) {
            // 断点续合：已完成段留在索引缓存，状态行说明可续；按失败返回让整本合成继续其余轨道。
            // 直接透出底层失败原因（不自造错误文案，2026-09-29 用户裁决）。
            val reason = failure.message ?: failure.javaClass.simpleName
            status = "「$target」第 ${roleSegments.size - store.completedCount} 段待补（$reason），已完成 ${store.completedCount}/${roleSegments.size} 段已保留；再点「合成」只补缺失片段"
            return null
        }
        currentCoroutineContext().ensureActive()
        // 如实拼接（2026-09-29 用户裁决）：客户端零转码、零静音处理。WAV 族重写头部长度顺接，
        // 其余格式按字节直通；坏片段在合成/断点恢复时已被逐段解码校验拦截。
        val mergedBytes = withContext(Dispatchers.Default) {
            concatTtsTrackFaithful(store.chunks.mapIndexed { index, chunk ->
                chunk ?: throw TtsAudioDecodeException("第 ${index + 1} 段：片段缺失")
            })
        }
        // 产物容器以片段实际格式为准（如选 aac 时服务端实际产出 mp3，不把扩展名谎报成 aac）
        val deliverableFormat = store.formats.filterNotNull().firstOrNull() ?: formatPlan.deliverableFormat
        val entry = withContext(Dispatchers.IO) {
            historyStore.save(
                bytes = mergedBytes,
                text = "轨道·$target：" + roleSegments.firstOrNull()?.text.orEmpty(),
                voiceLabel = target,
                modelLabel = speaking.engine,
                format = deliverableFormat,
            )
        }
        Log.i(TTS_LOG_TAG, "「$target」整轨完成 segments=${roleSegments.size} " +
            "totalMs=${SystemClock.elapsedRealtime() - trackStarted} bytes=${mergedBytes.size} fmt=$deliverableFormat")
        roleChunks.remove(target)
        withContext(Dispatchers.IO) { chunkStore.clearRole(target, configHash, textsHash) }
        roleOutputs[target] = entry
        return entry
    }

    fun synthesizeRole(target: String) {
        if (running) {
            // 静默忽略会让「点了没反应」被误读成无法继续合成——给出明确反馈
            status = "正在合成中，请等当前任务结束，或先点「取消」"
            return
        }
        // 保活壳（前台服务+锁）：合成是分钟级多段请求，锁屏/切后台后系统掐网会整轨报废
        TtsSynthesisService.start(context)
        generationJob = appScope.launch {
            running = true
            try {
                val entry = synthesizeRoleNow(target)
                if (entry != null) {
                    status = "「$target」合成完成"
                    playEntry(entry)
                }
            } catch (error: CancellationException) {
                status = "已取消合成"
                throw error
            } catch (error: RemoteOnlineException) {
                // 直接透出服务端返回的信息（不自造错误文案，2026-09-29 用户裁决）
                status = error.message ?: "合成失败：${error.errorCode}（HTTP ${error.statusCode}）"
            } catch (error: IOException) {
                status = "网络传输失败：${error.message ?: error.javaClass.simpleName}"
            } catch (error: Exception) {
                status = "合成失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running = false
                progress = ""
            }
        }
    }

    fun synthesizeAll() {
        if (running || roles.isEmpty()) {
            if (running) status = "正在合成中，请等当前任务结束，或先点「取消」"
            return
        }
        // 保活壳（前台服务+锁）：整本合成时长更长，后台被掐网是重灾区
        TtsSynthesisService.start(context)
        generationJob = appScope.launch {
            running = true
            try {
                val done = mutableListOf<String>()
                val failed = mutableListOf<String>()
                val failureReasons = mutableListOf<String>()
                for (target in roles) {
                    ensureActive()
                    // 单轨失败（网络坏窗口/引擎偶发）不拖垮整本：记录原因后继续其余轨道，最后统一播报
                    try {
                        synthesizeRoleNow(target)?.let { done.add(target) }
                            ?: failed.add(target).also { failureReasons.add(target) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failed.add(target)
                        // 原因透传：配额/鉴权类失败不再被笼统报成「网络问题」。
                        failureReasons.add("$target：${error.message ?: error.javaClass.simpleName}")
                    }
                }
                when {
                    failed.isEmpty() -> {
                        status = "全部轨道合成完成：${done.joinToString("、")}"
                        done.firstOrNull()?.let { target -> roleOutputs[target]?.let { playEntry(it) } }
                    }
                    done.isEmpty() -> status = "轨道合成失败：${failureReasons.joinToString("；")}，可逐轨重试"
                    else -> {
                        status = "合成完成：${done.joinToString("、")}；失败：${failureReasons.joinToString("；")}"
                        done.firstOrNull()?.let { target -> roleOutputs[target]?.let { playEntry(it) } }
                    }
                }
            } catch (error: CancellationException) {
                status = "已取消合成，已完成的轨道保留在历史"
                throw error
            } catch (error: RemoteOnlineException) {
                status = error.message ?: "合成失败：${error.errorCode}（HTTP ${error.statusCode}）"
            } catch (error: IOException) {
                status = "网络传输失败：${error.message ?: error.javaClass.simpleName}"
            } catch (error: Exception) {
                status = "合成失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running = false
                progress = ""
            }
        }
    }

    // —— 导入脚本（左上角；txt 文本，超出脚本上限截断） ——
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        Pair(String(input.readBytes(), Charsets.UTF_8), queryDisplayName(context, uri))
                    }
                }.getOrNull()
            }
            when {
                loaded == null -> status = "无法读取所选文件，请换一个 txt 文本"
                else -> {
                    scriptBackup = script
                    script = loaded.first.take(20000)
                    status = "已导入脚本 ${loaded.second}（${script.length} 字）" +
                        if (loaded.first.length > 20000) "，超出 2 万字部分已截断" else ""
                }
            }
        }
    }

    // —— 历史另存到本地（右上角合成历史里触发） ——
    var pendingExport by remember { mutableStateOf<TtsHistoryEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        val entry = pendingExport
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(historyStore.fileOf(entry).readBytes())
                    } != null
                }.getOrDefault(false)
            }
            status = if (ok) "已保存到本地（${entry.fileName}）" else "保存失败，请重试"
        }
    }
    fun exportEntry(entry: TtsHistoryEntry) {
        pendingExport = entry
        exportLauncher.launch(entry.fileName)
    }

    var history by remember { mutableStateOf(historyStore.list()) }
    LaunchedEffect(Unit) { history = historyStore.list() }  // 离开期间完成的任务也进历史
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAiScript by rememberSaveable { mutableStateOf(false) }
    val aiState = remember { TtsAiUiState() }
    var actionRole by remember { mutableStateOf<String?>(null) }
    var editingRole by remember { mutableStateOf<String?>(null) }
    val displayStatus = progress.ifBlank { status }

    // —— 子页返回（系统返回与 AppBar 返回一致） ——
    BackHandler {
        when (page) {
            TTS_PAGE_VOICES -> { onLangChange(""); onPageChange(TTS_PAGE_VOICE) }
            TTS_PAGE_PARAMS -> onPageChange(TTS_PAGE_VOICE)
            TTS_PAGE_VOICE -> { onRoleChange(""); onPageChange(TTS_PAGE_MAIN) }
            else -> onBack()
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (page) {
            TTS_PAGE_VOICE -> TtsVoiceEditPage(
                role = role,
                models = loadedModels,
                aiClient = aiClient,
                config = configFor(role),
                referenceName = roleReferences[role]?.second.orEmpty(),
                trackText = trackTextOf(role),
                emotionTags = roleEmotions[role],
                status = displayStatus,
                running = running,
                onConfigChange = { roleConfigs[role] = it },
                onEmotionTagsChange = { tags ->
                    if (tags == null) {
                        roleEmotions.remove(role)
                        status = "已清除「$role」的 AI 情绪标签"
                    } else {
                        roleEmotions[role] = tags
                        status = "已为「$role」标注 ${tags.count { it.isNotBlank() }}/${tags.size} 行情绪（合成参数，脚本未改动）"
                    }
                },
                onPickReference = { pickReferenceFor(role) },
                onSynthesize = { synthesizeRole(role) },
                onOpenVoices = { onLangChange(""); onPageChange(TTS_PAGE_VOICES) },
                onOpenParams = { onPageChange(TTS_PAGE_PARAMS) },
                onBack = { onRoleChange(""); onPageChange(TTS_PAGE_MAIN) },
            )
            TTS_PAGE_VOICES -> {
                val cfg = configFor(role)
                val currentModel = ttsRoleModel(cfg, loadedModels)
                TtsVoicePickerPage(
                    lang = lang,
                    model = currentModel,
                    modeModels = loadedModels.filter { it.mode == (currentModel?.mode ?: cfg.mode) },
                    selectedModel = currentModel,
                    selectedVoiceId = cfg.voiceId,
                    status = displayStatus,
                    onEngineSelect = { model ->
                        val keepVoice = model.voices.firstOrNull { it.id == cfg.voiceId }?.id
                        roleConfigs[role] = cfg.copy(
                            modelId = model.id,
                            voiceId = keepVoice ?: ttsDefaultVoiceId(model),
                            format = ttsDefaultFormatFor(model),
                            params = roleParamsFor(model, cfg.params),
                        )
                    },
                    onLangChange = onLangChange,
                    onSelect = { voice ->
                        roleConfigs[role] = configFor(role).copy(voiceId = voice.id)
                        onLangChange("")
                        onPageChange(TTS_PAGE_VOICE)
                    },
                    onPreview = { voice ->
                        ttsRoleModel(configFor(role), loadedModels)?.let { previewVoice(it, voice) }
                    },
                    onBack = { onLangChange(""); onPageChange(TTS_PAGE_VOICE) },
                )
            }
            TTS_PAGE_PARAMS -> TtsParamsPage(
                model = ttsRoleModel(configFor(role), loadedModels),
                values = configFor(role).params,
                onChange = { name, value ->
                    roleConfigs[role] = configFor(role).copy(params = configFor(role).params + (name to value))
                },
                onBack = { onPageChange(TTS_PAGE_VOICE) },
            )
            else -> {
                // —— 主页：左上导入脚本 / 右上合成历史 / 中间脚本编辑框（右侧 清除 + AI 脚本生成） ——
                AppBar(
                    title = "AI TTS 工作台",
                    onBack = onBack,
                    onDevices = null,
                    leadingContent = {
                        IconButton(
                            onClick = { importLauncher.launch("*/*") },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) { Icon(Icons.Rounded.UploadFile, contentDescription = "导入脚本，支持 txt 文本") }
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { showHistory = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) { Icon(Icons.Rounded.History, contentDescription = "合成历史") }
                    },
                )
                when {
                    ws.models == null && loadError == null && roles.isEmpty() -> Text(
                        "正在加载语音引擎…",
                        Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    loadError != null -> Column(Modifier.padding(24.dp)) {
                        Text(loadError.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { reloadModels() }) { Text("重试") }
                    }
                    else -> LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                OutlinedTextField(
                                    value = script,
                                    onValueChange = { script = it.take(20000) },
                                    modifier = Modifier.weight(1f).heightIn(max = 240.dp),
                                    label = { Text("脚本") },
                                    supportingText = { Text("${script.length}/20000 · 每行「角色：台词」，无角色名归旁白") },
                                    minLines = 4,
                                )
                                Column(Modifier.padding(start = 4.dp)) {
                                    IconButton(
                                        onClick = {
                                            scriptBackup = script
                                            script = ""
                                            status = "已清空脚本，点「还原原脚本」可恢复"
                                        },
                                        enabled = script.isNotBlank(),
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    ) { Icon(Icons.Rounded.Clear, contentDescription = "清除脚本") }
                                    IconButton(
                                        onClick = { aiState.reset(); showAiScript = true },
                                        enabled = script.isNotBlank() && !aiState.running,
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    ) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "AI 脚本生成") }
                                }
                            }
                        }
                        if (scriptBackup.isNotBlank() || (aiState.message.isNotBlank() && !showAiScript)) {
                            item {
                                Column {
                                    if (aiState.message.isNotBlank() && !showAiScript) {
                                        Text(
                                            aiState.message,
                                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (scriptBackup.isNotBlank()) {
                                        TextButton(onClick = {
                                            script = scriptBackup
                                            scriptBackup = ""
                                            aiState.message = "已还原原脚本"
                                        }) { Text("还原原脚本") }
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                if (roles.isEmpty()) "输入脚本后自动识别角色轨道"
                                else "已识别 ${roles.size} 个角色轨道，共 ${segments.size} 段",
                                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        items(roles, key = { it }) { target ->
                            TtsTrackCard(
                                role = target,
                                summary = ttsRoleVoiceSummary(
                                    configFor(target),
                                    loadedModels,
                                    roleReferences[target]?.second.orEmpty(),
                                ),
                                segmentCount = segments.count { it.roleName == target },
                                output = roleOutputs[target],
                                onOpen = { actionRole = target },
                                onPlay = { roleOutputs[target]?.let { playEntry(it) } },
                                onEditText = { editingRole = target },
                                onEditVoice = {
                                    onRoleChange(target)
                                    onLangChange("")
                                    onPageChange(TTS_PAGE_VOICE)
                                },
                                onSynthesize = { synthesizeRole(target) },
                            )
                        }
                        if (roles.isNotEmpty()) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { synthesizeAll() }, enabled = !running) {
                                        Text(if (running) "正在合成…" else "合成全部轨道")
                                    }
                                    if (running) {
                                        OutlinedButton(onClick = { generationJob?.cancel() }) { Text("取消") }
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                displayStatus,
                                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    // —— 主页弹层（轨道操作菜单 / 编辑文本 / AI 脚本 / 合成历史） ——
    if (page == TTS_PAGE_MAIN) {
        actionRole?.let { target ->
            AlertDialog(
                onDismissRequest = { actionRole = null },
                title = { Text("「$target」") },
                text = {
                    Column {
                        TextButton(
                            onClick = { editingRole = target; actionRole = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("编辑文本") }
                        TextButton(
                            onClick = {
                                onRoleChange(target)
                                onLangChange("")
                                onPageChange(TTS_PAGE_VOICE)
                                actionRole = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("编辑语音") }
                        TextButton(
                            onClick = { synthesizeRole(target); actionRole = null },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("合成轨道") }
                    }
                },
                confirmButton = { TextButton(onClick = { actionRole = null }) { Text("取消") } },
            )
        }
        editingRole?.let { target ->
            TtsEditTextDialog(
                role = target,
                initial = trackTextOf(target),
                onApply = { lines ->
                    script = replaceRoleLinesInScript(script, target, lines)
                    status = "已更新「$target」的台词"
                    editingRole = null
                },
                onDismiss = { editingRole = null },
            )
        }
        if (showAiScript) {
            TtsAiScriptDialog(
                state = aiState,
                onStart = { launchTtsAiRequest(scope, aiClient, TTS_AI_MODE_SCRIPT, script, aiState) },
                onApply = { generated ->
                    scriptBackup = script
                    script = generated.take(20000)
                    showAiScript = false
                    aiState.message = "脚本已替换，点「还原原脚本」可恢复原文"
                },
                onDismiss = { showAiScript = false },
            )
        }
        if (showHistory) {
            TtsHistoryDialog(
                entries = history,
                playingEntryId = playingEntryId,
                isPlaying = isPlaying,
                onPlay = { playEntry(it) },
                onDelete = { entry ->
                    player.stop()
                    historyStore.delete(entry)
                    history = historyStore.list()
                    status = "已删除「${entry.voiceLabel}」的历史记录"
                },
                onExport = { exportEntry(it) },
                onDismiss = { showHistory = false },
            )
        }
    }
}

/** 角色轨道卡：角色名 + 段数 + 音色摘要 + 已合成状态；点按/长按/读屏 customActions 打开三动作。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TtsTrackCard(
    role: String,
    summary: String,
    segmentCount: Int,
    output: TtsHistoryEntry?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onEditText: () -> Unit,
    onEditVoice: () -> Unit,
    onSynthesize: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onOpen,
                onClickLabel = "打开操作菜单",
                onLongClickLabel = "打开操作菜单",
            )
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction("编辑文本") { onEditText(); true },
                    CustomAccessibilityAction("编辑语音") { onEditVoice(); true },
                    CustomAccessibilityAction("合成轨道") { onSynthesize(); true },
                )
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(role, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("$segmentCount 段 · $summary")
                        output?.let { append(" · 已合成 ${formatTtsSize(it.sizeBytes)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (output != null) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Icon(Icons.Default.PlayArrow, contentDescription = "播放 $role") }
            }
        }
    }
}

/** 编辑文本：显示该角色当前全部台词（每行一条），应用后回写脚本（其余行原样保留）。 */
@Composable
private fun TtsEditTextDialog(
    role: String,
    initial: String,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑「$role」的台词") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("台词，每行一条") },
                minLines = 5,
                maxLines = 10,
            )
        },
        confirmButton = { TextButton(onClick = { onApply(draft.lines()) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 音色来源下拉（月播通用控件）：选项由服务端目录动态生成（ttsModeOptions），非固定三选。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TtsModeDropdown(options: List<TtsModeOption>, selectedMode: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.mode == selectedMode }?.label ?: "暂无可用音色来源",
            onValueChange = {},
            readOnly = true,
            label = { Text("音色来源") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = options.isNotEmpty(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            if (option.mode == selectedMode) {
                                Text("当前来源", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    onClick = {
                        onSelect(option.mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 输出格式标签：wav/mp3（引擎原生支持，服务端透传不转码，音频解码由客户端负责）。 */
internal fun ttsFormatLabel(format: String): String = when (format) {
    "wav" -> "WAV（无损）"
    "mp3" -> "MP3（体积小）"
    else -> format.uppercase()
}

/** 输出格式下拉（月播通用控件）：选项由服务端目录按引擎原生支持集下发，同一轨道全程一致。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TtsFormatDropdown(options: List<TtsToolOption>, selectedFormat: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.value == selectedFormat }?.label ?: selectedFormat,
            onValueChange = {},
            readOnly = true,
            label = { Text("输出格式") },
            supportingText = { Text("同一轨道全程使用所选格式，合成后自动拼接") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = options.isNotEmpty(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            if (option.value == selectedFormat) {
                                Text("当前格式", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 引擎下拉菜单（月播通用控件）：引擎名 + 价格位（运营后续填写，空则不显示）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TtsEngineDropdown(models: List<TtsModelInfo>, selected: TtsModelInfo?, onSelect: (TtsModelInfo) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.label ?: "暂无可用引擎",
            onValueChange = {},
            readOnly = true,
            label = { Text("语音引擎") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled = models.isNotEmpty(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(if (model.price.isNotBlank()) "${model.label}（${model.price}）" else model.label)
                            if (model.id == selected?.id) {
                                Text("当前引擎", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 动态参数面板：schema 服务端下发，随引擎切换自动加载；select 渲染为下拉菜单。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TtsDynamicParams(model: TtsModelInfo, values: Map<String, String>, onChange: (String, String) -> Unit) {
    if (model.paramsSchema.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("合成参数（${model.engine}）", style = MaterialTheme.typography.titleSmall)
            model.paramsSchema.forEach { spec ->
                when (spec.type) {
                    "slider" -> {
                        val current = values[spec.name]?.toFloatOrNull() ?: spec.default.toFloatOrNull() ?: spec.min
                        Text("${spec.label}：%.2f".format(current), style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = current.coerceIn(spec.min, spec.max),
                            onValueChange = { newValue ->
                                val stepped = ((newValue - spec.min) / spec.step).toInt() * spec.step + spec.min
                                onChange(spec.name, "%.2f".format(stepped))
                            },
                            valueRange = spec.min..spec.max,
                            modifier = Modifier.semantics {
                                contentDescription = spec.label
                                stateDescription = "%.2f".format(current)
                            },
                        )
                    }
                    "select" -> {
                        val current = values[spec.name] ?: spec.default
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                        ) {
                            OutlinedTextField(
                                value = spec.options.firstOrNull { it.value == current }?.label
                                    ?: current.ifBlank { "默认" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(spec.label) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                spec.options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            onChange(spec.name, option.value)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = values[spec.name].orEmpty(),
                            onValueChange = { onChange(spec.name, it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(spec.label) },
                            singleLine = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsHistoryDialog(
    entries: List<TtsHistoryEntry>,
    playingEntryId: String?,
    isPlaying: Boolean,
    onPlay: (TtsHistoryEntry) -> Unit,
    onDelete: (TtsHistoryEntry) -> Unit,
    onExport: (TtsHistoryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("合成历史，共 ${entries.size} 条") },
        text = {
            if (entries.isEmpty()) {
                Text("暂无历史记录。合成的音频会自动保存在应用本地，并支持另存到手机。")
            } else {
                Column {
                    Text(
                        "点按播放，长按删除；「保存到本地」可另存到手机。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                        items(entries, key = { it.id }) { entry ->
                            TtsHistoryRow(
                                entry = entry,
                                playing = isPlaying && entry.id == playingEntryId,
                                onPlay = { onPlay(entry) },
                                onDelete = { onDelete(entry) },
                                onExport = { onExport(entry) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 历史行：点按播放、长按删除（读屏 customActions 等价），另存到本地走系统保存对话框。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TtsHistoryRow(
    entry: TtsHistoryEntry,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onDelete,
                onClickLabel = "播放",
                onLongClickLabel = "删除",
            )
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction("保存到本地") { onExport(); true },
                    CustomAccessibilityAction("删除") { onDelete(); true },
                )
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${entry.voiceLabel} · ${entry.modelLabel} · ${formatTtsHistoryTime(entry.createdAtMillis)} · ${formatTtsSize(entry.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // 播放/另存按钮只服务触控：读屏走整行的 customActions（下方），否则同一操作出现
            // 两个可滑动聚焦的重复入口（无障碍缺陷：customActions 已有等价操作）。
            IconButton(
                onClick = onPlay,
                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { invisibleToUser() },
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "暂停播放" else "播放 ${entry.voiceLabel}",
                )
            }
            IconButton(
                onClick = onExport,
                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { invisibleToUser() },
            ) {
                Icon(Icons.Rounded.Download, contentDescription = "保存到本地")
            }
        }
    }
}

@Composable
internal fun TtsPromptDialog(
    title: String,
    hint: String,
    fields: List<TtsPromptField>,
    initial: Map<String, String>,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = remember { mutableStateMapOf<String, String>().apply { putAll(initial) } }
    var preview by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                fields.forEach { field ->
                    OutlinedTextField(
                        value = draft[field.key].orEmpty(),
                        onValueChange = { draft[field.key] = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = { Text(field.label) },
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { preview = buildTtsPrompt(fields, draft) }) { Text("预览描述") }
                if (preview.isNotBlank()) {
                    Text(preview, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft.toMap()) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun readReferenceAudio(context: Context, uri: Uri): Pair<ByteArray, String>? = runCatching {
    val buffer = ByteArray(TTS_MAX_REFERENCE_BYTES + 1)
    val (bytes, mime) = context.contentResolver.openInputStream(uri)?.use { input ->
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        Pair(buffer.copyOf(offset), context.contentResolver.getType(uri).orEmpty())
    } ?: return null
    val normalized = when {
        mime.contains("mpeg") || mime.contains("mp3") -> "audio/mpeg"
        mime.contains("wav") || mime.isBlank() -> "audio/wav"
        mime.contains("mp4") || mime.contains("aac") || mime.contains("m4a") -> "audio/mp4"
        else -> mime
    }
    Pair(bytes, normalized)
}.getOrNull()

private fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: "参考音频"
