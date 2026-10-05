package com.example.local_music_player

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TTS 直连执行器（2026-09-22）：服务器带宽不足以转发音频，改为「服务器发票据、客户端
 * 直连官方端点拉音频」。票据（/v1/audio/speech + direct=true）携带官方端点与鉴权材料，
 * 密钥只在票据里经鉴权/版本门禁下发，不进 APK。通道：
 * - mimo：POST 官方 chat/completions，响应 JSON 内 base64 音频按 [TtsDirectTicket.audioPaths] 提取。
 *   （微软 Edge 朗读 WSS 通道按用户决定于 2026-09-28 移除；微软语音一律走服务器中转。）
 * 直连失败由调用方回退服务器中转（synthesatWithRetry 外层仍负责重试）。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，不参与编译。
 */

/** 直连票据：服务端 /v1/audio/speech（direct=true）的 JSON 响应。 */
internal data class TtsDirectTicket(
    val kind: String,
    val format: String,
    val url: String,
    val headers: Map<String, String>,
    val endpoint: String,
    val body: JSONObject?,
    val audioPaths: List<String>,
)

internal fun decodeTtsDirectTicket(payload: JSONObject): TtsDirectTicket? {
    if (!payload.optBoolean("direct")) return null
    val kind = payload.optString("kind").takeIf { it != "null" && it.isNotBlank() } ?: return null
    val headers = mutableMapOf<String, String>()
    payload.optJSONObject("headers")?.let { json ->
        json.keys().forEach { key ->
            json.optString(key).takeIf { it != "null" && it.isNotEmpty() }?.let { headers[key] = it }
        }
    }
    val audioPaths = mutableListOf<String>()
    payload.optJSONArray("audio_paths")?.let { array ->
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it != "null" && it.isNotEmpty() }?.let { audioPaths.add(it) }
        }
    }
    return TtsDirectTicket(
        kind = kind,
        format = payload.optString("format").takeIf { it != "null" && it.isNotBlank() } ?: "mp3",
        url = payload.optString("url").takeIf { it != "null" } ?: "",
        headers = headers,
        endpoint = payload.optString("endpoint").takeIf { it != "null" } ?: "",
        body = payload.optJSONObject("body"),
        audioPaths = audioPaths,
    )
}

/** 按点分路径取 JSON 值（数字段=数组下标），如 choices.0.message.audio.data；缺失返回 null。 */
internal fun walkJsonPath(node: Any, path: String): Any? {
    var current: Any? = node
    for (segment in path.split('.')) {
        current = when (current) {
            is JSONObject -> if (current.has(segment)) current.opt(segment) else return null
            is JSONArray -> segment.toIntOrNull()
                ?.takeIf { it in 0 until current.length() }?.let { current.get(it) } ?: return null
            else -> return null
        }
    }
    return current
}

internal object TtsDirect {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * 应用级共享 OkHttp 客户端（2026-09-23）：所有直连请求复用同一个连接池/线程池，
     * 避免每个片段新建客户端（TCP+TLS 握手重来，弱网下每段白付 1~2 秒）。
     * 读超时给足 30 秒；单次请求的快速预算由调用方 withTimeout 控制。
     */
    internal val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)  // 死连接 30 秒内暴露（无 pong 即 onFailure）
            .build()
    }

    /** 直连硬超时预算：官方端点在手机网络上可能被静默黑洞（无失败无响应），没有预算就会卡死无回退。 */
    suspend fun execute(ticket: TtsDirectTicket, timeoutMs: Long): ByteArray = when (ticket.kind) {
        "mimo" -> withTimeout(timeoutMs) { executeMimo(ticket) }
        else -> throw RemoteOnlineException("未知直连通道：${ticket.kind}", 502, "tts_direct_kind")
    }

    /** MiMo 官方端点：POST 完整请求体，从响应 JSON 提取 base64 音频。 */
    private suspend fun executeMimo(ticket: TtsDirectTicket): ByteArray {
        if (ticket.endpoint.isBlank() || ticket.body == null) {
            throw RemoteOnlineException("直连票据缺少官方端点信息", 502, "tts_direct_ticket")
        }
        val request = Request.Builder()
            .url(ticket.endpoint)
            .post(ticket.body.toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia))
            .apply { ticket.headers.forEach { (key, value) -> header(key, value) } }
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RemoteOnlineException("官方合成端点返回错误（${response.code}）", response.code, "tts_direct_upstream")
                }
                val json = runCatching { JSONObject(text) }.getOrElse {
                    throw RemoteOnlineException("官方合成端点响应无效", 502, "tts_direct_invalid")
                }
                for (path in ticket.audioPaths) {
                    val value = walkJsonPath(json, path) as? String
                    if (!value.isNullOrBlank()) {
                        return@withContext Base64.decode(value, Base64.NO_WRAP)
                    }
                }
                throw RemoteOnlineException("官方合成端点未返回音频", 502, "tts_direct_empty")
            }
        }
    }
}
