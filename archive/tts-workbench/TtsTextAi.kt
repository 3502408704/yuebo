package com.example.local_music_player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI 文本赋能（服务端 /v1/ai/text，vivo 蓝心 BlueLM；密钥只进服务端）：
 * - script 有声书脚本化：任意文本 → 「角色（情绪）：台词」脚本（对齐 TtsAudiobook 解析语法）；
 * - direct AI 导演：文本 → 导演面板 8 字段表演指导 + 语速建议（确认后回填，不静默覆盖）；
 * - voice AI 音色设计：一句话描述 → 音色设计面板 13 字段（对齐 MiMo voice_design 官方口径）。
 * 请求/响应均为 JSON（服务端聚合 SSE），AI 按次配额——不重试、读超时放宽。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，不参与编译。
 */

internal const val TTS_AI_MODE_SCRIPT = "script"
internal const val TTS_AI_MODE_EMOTION = "emotion"
internal const val TTS_AI_MODE_DIRECT = "direct"
internal const val TTS_AI_MODE_VOICE = "voice"

/** AI 音色设计的两种工作模式：优化用户提示词 / 朗读轨道台词自动分析生成。 */
internal const val TTS_VOICE_AI_MODE_OPTIMIZE = "optimize"
internal const val TTS_VOICE_AI_MODE_ANALYZE = "analyze"

/** 按模式取 AI 音色设计的输入：优化=用户描述；分析=该角色台词上下文（截断防超长）。 */
internal fun buildVoiceDesignAiInput(mode: String, prompt: String, trackText: String): String =
    (if (mode == TTS_VOICE_AI_MODE_ANALYZE) trackText else prompt).trim().take(600)

// ==== 请求构造与响应解析（纯函数，可 JVM 单测） ====

/** [emotionVocab] 非空时随 emotion 模式下发（引擎感知：限制 AI 只选可映射的情绪词）。 */
internal fun buildTtsAiTextRequest(mode: String, text: String, emotionVocab: String = ""): JSONObject {
    val body = JSONObject().put("mode", mode).put("text", text)
    if (mode == TTS_AI_MODE_EMOTION && emotionVocab.isNotBlank()) {
        body.put("emotion_vocab", emotionVocab)
    }
    return body
}

/** script 模式响应：text 为生成的脚本；fallback=true 表示 AI 输出无效已回退原文。 */
internal data class TtsAiTextResult(val text: String, val fallback: Boolean)

internal fun decodeTtsAiTextResult(payload: JSONObject): TtsAiTextResult? {
    val text = payload.optString("text", "").takeIf { it != "null" } ?: return null
    if (text.isBlank()) return null
    return TtsAiTextResult(text, payload.optBoolean("fallback"))
}

/** direct/voice 模式响应：params 为字段→值（只含非空建议），suggestedSpeed 仅导演模式可能有。 */
internal data class TtsAiParamsResult(
    val params: Map<String, String>,
    val suggestedSpeed: Float?,
    val fallback: Boolean,
)

/**
 * 模式感知字段白名单（2026-09-23）：direct 结果只进导演面板、voice 结果只进音色设计面板。
 * 服务端已做白名单收口，客户端再滤一层——旧服务端或异常输出里的跨模式字段（导演模式混出
 * 音色字段、反之亦然）不会污染面板。mode 未知/为空时不过滤（保持旧行为兼容）。
 */
internal fun ttsAiParamsFieldsFor(mode: String): Set<String> = when (mode) {
    TTS_AI_MODE_DIRECT -> TTS_DIRECTOR_FIELDS.map { it.key }.toSet()
    TTS_AI_MODE_VOICE -> TTS_VOICE_DESCRIPTION_FIELDS.map { it.key }.toSet()
    else -> emptySet()
}

internal fun decodeTtsAiParamsResult(payload: JSONObject, mode: String = ""): TtsAiParamsResult {
    val allowed = ttsAiParamsFieldsFor(mode)
    val params = mutableMapOf<String, String>()
    payload.optJSONObject("params")?.let { json ->
        json.keys().forEach { key ->
            if (key == "suggested_speed") return@forEach  // 语速建议单列，不进面板字段
            if (allowed.isNotEmpty() && key !in allowed) return@forEach  // 跨模式字段丢弃
            val value = json.optString(key, "").takeIf { it != "null" }?.trim().orEmpty()
            if (value.isNotEmpty()) params[key] = value
        }
    }
    val speed = if (payload.has("params")) payload.optJSONObject("params")?.optDouble("suggested_speed") else null
    return TtsAiParamsResult(
        params = params,
        suggestedSpeed = speed?.takeIf { it in 0.5..2.0 }?.toFloat(),
        fallback = payload.optBoolean("fallback"),
    )
}

internal fun ttsAiModeLabel(mode: String): String = when (mode) {
    TTS_AI_MODE_SCRIPT -> "AI 脚本化"
    TTS_AI_MODE_EMOTION -> "AI 情感控制"
    TTS_AI_MODE_VOICE -> "AI 音色设计"
    else -> "AI 导演"
}

/**
 * AI 情感控制的输出契约：剥掉角色名后的（情绪）再逐行比对，台词文字必须与原文一致——
 * 模型改写了台词就拒绝应用（避免静默替换用户脚本）。
 */
internal fun emotionTagsPreserveLines(original: String, generated: String): Boolean {
    fun stripTag(line: String): String {
        val trimmed = line.trim()
        val separator = trimmed.indexOfFirst { it == '：' || it == ':' }
        if (separator !in 1 until trimmed.length) return trimmed
        val rolePart = Regex("[（(][^（）()]*[）)]").replace(trimmed.substring(0, separator), "").trim()
        return "$rolePart：${trimmed.substring(separator + 1).trim()}"
    }
    val normalize = { line: String -> stripTag(line).replace("：", ":").replace(" ", "") }
    return original.lines().map(normalize).filter { it.isNotBlank() } ==
        generated.lines().map(normalize).filter { it.isNotBlank() }
}

/**
 * AI 情感控制输出（逐行「角色（情绪）：台词」）→ 与角色台词逐行对齐的情绪标签。
 * 标签是合成参数（MiMo 走表演指导、微软走情感风格），不回写脚本文本。
 * 行数必须一致，且剥掉「角色（情绪）：」前缀后的台词必须与原文一致（防静默改写），否则返回 null。
 * 未标注的行返回空串标签（合成时回退脚本内标注）。
 */
internal fun parseEmotionTags(originalLines: List<String>, generated: String): List<String>? {
    val originals = originalLines.map { it.trim() }.filter { it.isNotEmpty() }
    val generatedLines = generated.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (generatedLines.size != originals.size) return null
    val paren = Regex("[（(]([^（）()]+)[）)]")
    val lineEndParen = Regex("[（(]([^（）()]+)[）)]$")
    val normalize = { text: String -> text.replace("：", ":").replace(" ", "") }
    return originals.indices.map { index ->
        val line = generatedLines[index]
        val separator = line.indexOfFirst { it == '：' || it == ':' }
        var tag = ""
        var body = line
        if (separator in 1 until line.length) {
            tag = paren.find(line.substring(0, separator))?.groupValues?.get(1)?.trim().orEmpty()
            body = line.substring(separator + 1).trim()
            if (normalize(body) != normalize(originals[index])) {
                // 台词自带冒号且模型把情绪标在行尾（「他说：快跑！（紧张）」）：回退行尾解释
                lineEndParen.find(line)?.let { match ->
                    val fallbackBody = line.substring(0, match.range.first).trim()
                    if (normalize(fallbackBody) == normalize(originals[index])) {
                        tag = match.groupValues[1].trim()
                        body = fallbackBody
                    }
                }
            }
        } else {
            // 容忍无「角色：」前缀的行尾（情绪）写法
            lineEndParen.find(line)?.let { match ->
                tag = match.groupValues[1].trim()
                body = line.substring(0, match.range.first).trim()
            }
        }
        if (normalize(body) != normalize(originals[index])) return null
        tag
    }
}

// ==== 网络客户端 ====

internal class TtsAiClient(private val account: YueboAccount) {
    /** 长脚本分块生成可能分钟级；AI 按次配额，网络层不重试（防双重扣次数）。读超时给足 5 分钟。 */
    fun process(mode: String, text: String, emotionVocab: String = ""): JSONObject =
        account.requestAi("POST", "/v1/ai/text", buildTtsAiTextRequest(mode, text, emotionVocab), readTimeoutMs = 300_000)
}

/** 一次 AI 请求的 UI 状态（运行中/提示/参数结果/脚本预览）。 */
internal class TtsAiUiState {
    var running by mutableStateOf(false)
    var message by mutableStateOf("")
    var params by mutableStateOf<TtsAiParamsResult?>(null)
    var scriptPreview by mutableStateOf<String?>(null)

    fun reset() {
        running = false
        message = ""
        params = null
        scriptPreview = null
    }
}

/** 启动一次 AI 文本请求：结果落 [state]，失败消息落 message（供 liveRegion 播报）。
 *  [emotionVocab] 仅 emotion 模式有意义（引擎感知词表，见 ttsEmotionVocabFor）。 */
internal fun launchTtsAiRequest(
    scope: CoroutineScope,
    client: TtsAiClient,
    mode: String,
    text: String,
    state: TtsAiUiState,
    emotionVocab: String = "",
    onDone: () -> Unit = {},
) {
    if (state.running) return
    scope.launch {
        state.reset()
        state.running = true
        state.message = "${ttsAiModeLabel(mode)}正在处理…"
        try {
            val payload = withContext(Dispatchers.IO) { client.process(mode, text, emotionVocab) }
            if (mode == TTS_AI_MODE_SCRIPT || mode == TTS_AI_MODE_EMOTION) {
                val result = decodeTtsAiTextResult(payload)
                val rejected = result != null && mode == TTS_AI_MODE_EMOTION &&
                    !emotionTagsPreserveLines(text, result.text)
                if (result == null || rejected) {
                    state.message = when {
                        rejected -> "AI 改动了台词文字，已保留原脚本，请重试"
                        mode == TTS_AI_MODE_EMOTION -> "AI 未能生成情感标注，请稍后重试"
                        else -> "AI 未能生成脚本，请稍后重试"
                    }
                } else {
                    state.scriptPreview = result.text
                    state.message = if (result.fallback) "AI 输出无效，已保留原文" else "已生成，请确认后替换"
                }
            } else {
                val result = decodeTtsAiParamsResult(payload, mode)
                state.params = result
                state.message = if (result.fallback || result.params.isEmpty()) {
                    "AI 未能给出有效建议，请稍后重试"
                } else {
                    "建议已生成，请确认后应用"
                }
            }
            onDone()
        } catch (error: RemoteOnlineException) {
            state.message = error.message ?: "AI 服务暂不可用，请稍后重试"
        } catch (error: Exception) {
            state.message = "网络请求失败，请检查网络后重试"
        } finally {
            state.running = false
        }
    }
}

// ==== AI 建议确认对话框（AI 建议、用户确认应用；读屏逐行朗读字段与值） ====

/**
 * 参数类 AI 建议流程对话框（AI 导演 / AI 音色设计共用）：
 * [inputLabel] 非空时先填一句话描述（音色设计）；为空则直接用工作台文本分析（导演）。
 * [designModes] 为真时提供两种工作模式：「优化提示词」（用户描述润色成完整设计）与
 * 「按台词分析」（朗读 [trackText] 对应轨道文本自动生成描述），AI 建议、用户确认应用。
 */
@Composable
internal fun TtsAiParamsDialog(
    title: String,
    intro: String,
    fields: List<TtsPromptField>,
    showSpeed: Boolean,
    inputLabel: String?,
    inputInitial: String,
    state: TtsAiUiState,
    onStart: (String) -> Unit,
    onApply: (Map<String, String>, Float?) -> Unit,
    onDismiss: () -> Unit,
    designModes: Boolean = false,
    trackText: String = "",
) {
    var input by rememberSaveable { mutableStateOf(inputInitial) }
    var designMode by rememberSaveable { mutableStateOf(TTS_VOICE_AI_MODE_OPTIMIZE) }
    val result = state.params
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(intro, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (inputLabel != null && result == null && !state.running) {
                    if (designModes) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = designMode == TTS_VOICE_AI_MODE_OPTIMIZE,
                                onClick = { designMode = TTS_VOICE_AI_MODE_OPTIMIZE },
                                label = { Text("优化提示词") },
                            )
                            FilterChip(
                                selected = designMode == TTS_VOICE_AI_MODE_ANALYZE,
                                onClick = { designMode = TTS_VOICE_AI_MODE_ANALYZE },
                                enabled = trackText.isNotBlank(),
                                label = { Text("按台词分析") },
                            )
                        }
                    }
                    if (!designModes || designMode == TTS_VOICE_AI_MODE_OPTIMIZE) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(inputLabel) },
                            supportingText = { Text("示例：想要一个温柔的年轻女声") },
                            minLines = 2,
                        )
                    } else {
                        Text(
                            "将朗读该角色台词，按内容自动生成音色描述。",
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (state.running) {
                    Text(
                        state.message.ifBlank { "正在处理…" },
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (result != null) {
                    if (result.params.isEmpty()) {
                        Text(
                            "AI 未能给出有效建议，请调整后重试",
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            state.message,
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        fields.forEach { field ->
                            val value = result.params[field.key] ?: return@forEach
                            Text(
                                "${field.label}：$value",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        if (showSpeed && result.suggestedSpeed != null) {
                            Text(
                                "语速建议：${result.suggestedSpeed} 倍",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        state.message,
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            when {
                result != null && result.params.isNotEmpty() -> TextButton(onClick = {
                    onApply(result.params, result.suggestedSpeed.takeIf { showSpeed })
                }) { Text("应用建议") }
                result == null && !state.running -> TextButton(
                    enabled = when {
                        designModes -> if (designMode == TTS_VOICE_AI_MODE_ANALYZE) {
                            trackText.isNotBlank()
                        } else {
                            input.isNotBlank()
                        }
                        inputLabel == null -> true
                        else -> input.isNotBlank()
                    },
                    onClick = {
                        onStart(
                            if (designModes) buildVoiceDesignAiInput(designMode, input, trackText)
                            else input.trim()
                        )
                    },
                ) { Text(if (designModes) "开始生成" else if (inputLabel == null) "开始分析" else "AI 生成") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (result != null && result.params.isNotEmpty()) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 有声书 AI 脚本化 / AI 情感控制对话框：生成 → 可滚动预览 → 确认替换（原文可一键还原，由调用方保存）。 */
@Composable
internal fun TtsAiScriptDialog(
    state: TtsAiUiState,
    onStart: () -> Unit,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "AI 生成有声书脚本",
    intro: String = "把当前内容整理成「角色：台词」脚本，自动标注角色与情绪；生成后可先预览再替换，原脚本可一键还原。",
) {
    val preview = state.scriptPreview
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    intro,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    state.running -> Text(
                        state.message.ifBlank { "正在生成脚本…" },
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    preview != null -> {
                        Text(
                            state.message,
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(preview, style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Text(
                        state.message.ifBlank { "将分析当前脚本框中的全部内容。" },
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            when {
                preview != null -> TextButton(onClick = { onApply(preview) }) { Text("替换脚本") }
                !state.running -> TextButton(onClick = onStart) { Text("开始生成") }
                else -> TextButton(onClick = onDismiss) { Text("后台等待") }
            }
        },
        dismissButton = {
            if (preview != null) TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
