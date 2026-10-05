package com.example.local_music_player

import java.io.ByteArrayOutputStream

/**
 * 有声书脚本域纯函数（角色轨道工作流，移植 onniVoice）：
 * 脚本按「角色：台词」逐行解析（无冒号归旁白、角色（情绪）、角色【情绪=…；场景=…】），
 * 每个角色一条稳定音色轨道：预置音色 / 音色设计（先合成校准音频再降级为克隆保证角色
 * 全程同音）/ 声音克隆；逐段合成（朗读正文放 assistant、表演指导放 instructions，
 * 朗读正文与控制字段严格分离），片段由 TtsAudioAssembler 解码为统一 PCM、压缩过长边界
 * 静音后按脚本顺序拼出「每角色一条」WAV 长音频。
 * 界面层在 TtsWorkbench.kt（工作台主页）与 TtsVoicePages.kt（角色语音/音色列表/合成参数子页）。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，不参与编译。
 */

internal data class TtsAudiobookSegment(
    val roleName: String,
    val text: String,
    val emotion: String = "",
    val tone: String = "",
    val scene: String = "",
    val pause: String = "",
    val tags: String = "",
)

/** 每行「角色：台词」解析（onniVoice AudiobookScriptParser 同口径）；空行跳过。 */
internal fun parseTtsAudiobookScript(script: String): List<TtsAudiobookSegment> {
    val result = mutableListOf<TtsAudiobookSegment>()
    val paren = Regex("[（(]([^（）()]+)[）)]$")
    for (rawLine in script.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val separator = line.indexOfFirst { it == '：' || it == ':' }
        if (separator == line.length - 1) continue  // 「角色：」单独成行：只是角色头，无台词
        var rolePart: String
        var textPart: String
        if (separator in 1 until line.length) {
            rolePart = line.substring(0, separator).trim()
            textPart = line.substring(separator + 1).trim()
        } else {
            rolePart = "旁白"
            textPart = line
        }
        var emotion = ""
        var tone = ""
        var scene = ""
        var pause = ""
        var tags = ""
        // 说话人名内【键=值；键=值】（分隔符兼容中英文分号/逗号；键值分隔兼容 =/：/:/＝）
        val bracketStart = rolePart.indexOf('【')
        if (bracketStart >= 0) {
            val bracketEnd = rolePart.indexOf('】', bracketStart)
            if (bracketEnd > bracketStart) {
                val inner = rolePart.substring(bracketStart + 1, bracketEnd)
                for (pair in inner.split('；', ';', '，', ',')) {
                    if (pair.isBlank()) continue
                    val keyValue = pair.split('=', '＝', '：', ':', limit = 2)
                    if (keyValue.size != 2) continue
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    when (key) {
                        "情绪" -> emotion = value
                        "语气" -> tone = value
                        "场景" -> scene = value
                        "停顿" -> pause = value
                        "音频标签", "标签" -> tags = value
                    }
                }
                rolePart = rolePart.substring(0, bracketStart).trim()
            }
        }
        // 角色名行尾（情绪）
        paren.find(rolePart)?.let { match ->
            if (emotion.isBlank()) emotion = match.groupValues[1].trim()
            rolePart = rolePart.substring(0, match.range.first).trim()
        }
        if (rolePart.isEmpty()) rolePart = "旁白"
        // 台词行尾（情绪）——作者标注式情绪
        paren.find(textPart)?.let { match ->
            if (match.range.last == textPart.length - 1) {
                if (emotion.isBlank()) emotion = match.groupValues[1].trim()
                textPart = textPart.substring(0, match.range.first).trim()
            }
        }
        if (textPart.isEmpty()) continue
        result += TtsAudiobookSegment(rolePart, textPart, emotion, tone, scene, pause, tags)
    }
    return result
}

/** 行级角色识别（与解析器的角色名剥壳同口径，只取角色名不取标注字段）；空行/纯角色头返回 null。 */
internal fun ttsScriptLineRole(rawLine: String): String? {
    val line = rawLine.trim()
    if (line.isEmpty()) return null
    val separator = line.indexOfFirst { it == '：' || it == ':' }
    if (separator == line.length - 1) return null
    var rolePart = if (separator in 1 until line.length) {
        line.substring(0, separator).trim()
    } else {
        return "旁白"
    }
    val bracketStart = rolePart.indexOf('【')
    if (bracketStart >= 0) {
        val bracketEnd = rolePart.indexOf('】', bracketStart)
        if (bracketEnd > bracketStart) rolePart = rolePart.substring(0, bracketStart).trim()
    }
    val paren = Regex("[（(]([^（）()]+)[）)]$")
    paren.find(rolePart)?.let { match -> rolePart = rolePart.substring(0, match.range.first).trim() }
    return rolePart.ifBlank { "旁白" }
}

/**
 * 「编辑文本」回写：把脚本中属于 [role] 的台词行整体替换为 [newLines]（每行自动补「角色：」前缀，
 * 旁白行不加前缀）。其余行（含其它角色的标注写法）原样保留；脚本里没有该角色时原样返回。
 */
internal fun replaceRoleLinesInScript(script: String, role: String, newLines: List<String>): String {
    val lines = script.lines()
    val roleLineIndexes = lines.withIndex().filter { ttsScriptLineRole(it.value) == role }.map { it.index }
    if (roleLineIndexes.isEmpty()) return script
    val prefix = if (role == "旁白") "" else "$role："
    val replacement = newLines.map { it.trim() }.filter { it.isNotEmpty() }.map { "$prefix$it" }
    // 行数一致（最常见的原位编辑）：逐行原位替换——交错脚本不再把该角色后续行折叠丢失。
    if (replacement.size == roleLineIndexes.size) {
        val result = lines.toMutableList()
        roleLineIndexes.forEachIndexed { position, index -> result[index] = replacement[position] }
        return result.joinToString("\n")
    }
    // 行数有增删：新台词块整体放在第一处，其余该角色的旧位置移除；其它角色行原样保留
    val result = mutableListOf<String>()
    var inserted = false
    for (line in lines) {
        if (ttsScriptLineRole(line) == role) {
            if (!inserted) {
                result.addAll(replacement)
                inserted = true
            }
        } else {
            result.add(line)
        }
    }
    return result.joinToString("\n")
}

/** 角色列表（按出场顺序去重）。 */
internal fun audiobookRoles(segments: List<TtsAudiobookSegment>): List<String> =
    segments.map { it.roleName }.distinct()

/** 某角色的全部台词（编辑文本对话框初值 / AI 按台词分析音色的输入）。 */
internal fun roleTrackText(segments: List<TtsAudiobookSegment>, role: String): String =
    segments.filter { it.roleName == role }.joinToString("\n") { it.text }

/** 段级表演指导（user 侧控制指令；角色身份在角色级，段级只叠加当场表演）。
 *  首行边界声明明确要求模型把本段视为控制信息：不朗读字段名、标签或说明文字——
 *  防止表演指令被当成正文读出来（2026-09-23 情感泄露修复的指令侧收口）。 */
internal fun stripTtsEmotionDirectives(baseStyle: String): String {
    if (baseStyle.isBlank()) return ""
    val pieces = baseStyle.split(Regex("[\\n；;，,]"))
    return pieces
        .map { it.trim() }
        .filter { piece ->
            if (piece.isBlank()) return@filter false
            val separator = piece.indexOfFirst { it == ':' || it == '：' || it == '=' || it == '＝' }
            if (separator <= 0) return@filter true
            val key = piece.substring(0, separator).trim().lowercase()
            !key.contains("情绪") && !key.contains("情感") && key != "emotion"
        }
        .joinToString("；")
}

internal fun buildTtsAudiobookSegmentPrompt(roleName: String, baseStyle: String, segment: TtsAudiobookSegment): String {
    val scriptEmotion = normalizeTtsEmotion(segment.emotion)
    val roleStyle = if (scriptEmotion.isNotBlank()) stripTtsEmotionDirectives(baseStyle) else baseStyle.trim()
    val fields = listOfNotNull(
        "角色轨道：$roleName",
        roleStyle.takeIf { it.isNotBlank() }?.let { "角色基调：$it" },
        scriptEmotion.takeIf { it.isNotBlank() }?.let { "情绪：$it" },
        segment.tone.takeIf { it.isNotBlank() }?.let { "语气：$it" },
        segment.scene.takeIf { it.isNotBlank() }?.let { "场景：$it" },
        segment.pause.takeIf { it.isNotBlank() }?.let { "停顿：$it" },
    ).joinToString("\n")
    val priority = if (scriptEmotion.isNotBlank()) {
        "本段脚本情绪是最高优先级；将其转换为当前引擎支持的等价表现，忽略与之冲突的角色基调情绪，不要叠加互斥情绪。"
    } else {
        "没有脚本情绪时才使用默认情绪描述。"
    }
    return "以下为表演控制指令，只用于把握情绪与节奏，不是朗读内容：不要朗读字段名、标签或任何说明文字。\n$priority\n$fields"
}

/** 段级朗读正文：只传原始台词。情感、语气、场景、停顿、音频标签等一律不进 input
 *  （它们经 buildTtsAudiobookSegmentPrompt 走表演指令通道）——MiMo 曾把标签/情绪读出来，
 *  2026-09-23 起朗读正文与表演指令严格分离。 */
internal fun audiobookSpokenText(segment: TtsAudiobookSegment): String = segment.text

/** 单次合成请求的拆分上限（2026-09-30 从 1200 收紧到 200）：短请求合成更快返回、
 *  服务端排队更短、弱网下整段报废的代价更小；切分点仍优先落在句读标点。
 *  上限只需兜住超长手动行——AI 脚本化的单行上限（服务端 80 字）本来就低于它。 */
internal const val TTS_ROLE_REQUEST_MAX_CHARS = 200

internal fun splitTtsAudiobookText(text: String, maxChars: Int = TTS_ROLE_REQUEST_MAX_CHARS): List<String> {
    val normalized = text.trim()
    if (normalized.isEmpty()) return emptyList()
    if (maxChars < 1 || normalized.length <= maxChars) return listOf(normalized)
    val result = mutableListOf<String>()
    var start = 0
    while (start < normalized.length) {
        val hardEnd = minOf(start + maxChars, normalized.length)
        if (hardEnd == normalized.length) {
            result += normalized.substring(start).trim()
            break
        }
        val searchFrom = minOf(hardEnd - 1, start + maxChars / 2)
        var cut = hardEnd
        for (index in hardEnd - 1 downTo searchFrom) {
            if (normalized[index] in "。！？!?；;，,、\n") {
                cut = index + 1
                break
            }
        }
        val chunk = normalized.substring(start, cut).trim()
        if (chunk.isNotEmpty()) result += chunk
        start = cut
        while (start < normalized.length && normalized[start].isWhitespace()) start++
    }
    return result
}

/**
 * 段级生效情绪（情感=参数，不进合成文本）：脚本显式情绪优先，只有脚本没有标注时才
 * 使用与当前分段数严格对齐的 AI 标签；这样 AI 不会覆盖作者已经写明的表演意图。
 */
internal fun ttsSegmentEmotion(tags: List<String>?, segmentCount: Int, index: Int, scriptEmotion: String): String {
    val script = normalizeTtsEmotion(scriptEmotion)
    if (script.isNotBlank()) return script
    if (tags != null && tags.size == segmentCount && index in tags.indices) {
        return normalizeTtsEmotion(tags[index])
    }
    return ""
}

/** 去掉 AI/脚本可能带出的字段前缀；只保留一个可传给引擎的表演描述。 */
internal fun normalizeTtsEmotion(value: String): String {
    var text = value.trim()
    for (prefix in listOf("情绪：", "情绪:", "emotion:", "emotion=")) {
        if (text.startsWith(prefix, ignoreCase = true)) {
            text = text.removePrefix(prefix).trim()
            break
        }
    }
    return text.trim('（', '）', '(', ')', '【', '】', '[', ']')
}

/** 音色设计角色的一致性校准文本（先合成该句作为参考音频，再降级为克隆）。 */
internal const val TTS_AUDIOBOOK_CALIBRATION_TEXT =
    "请用这个角色音色自然朗读：今天天气很好，我们正在进行有声书角色轨道声音一致性校准。"

/**
 * 多段 WAV 拼接：上游恒为 44 字节标准头布局，重写 RIFF/data 长度后顺序拼体。
 * 输入非法（非 RIFF/WAVE/data）抛 IllegalArgumentException。
 */
internal fun concatTtsWav(chunks: List<ByteArray>): ByteArray {
    require(chunks.isNotEmpty()) { "音频分段为空" }
    if (chunks.size == 1) return chunks[0]
    for (chunk in chunks) {
        require(ttsChunkIsWav(chunk)) { "WAV 文件头无效" }
    }
    val dataSize = chunks.sumOf { it.size - 44 }
    val stream = ByteArrayOutputStream(44 + dataSize)
    stream.write(chunks[0], 0, 44)
    chunks.forEach { stream.write(it, 44, it.size - 44) }
    val output = stream.toByteArray()
    writeIntLittleEndian(output, 4, output.size - 8)
    writeIntLittleEndian(output, 40, dataSize)
    return output
}

private fun ByteArray.decodeHeader(offset: Int): String =
    String(this, offset, 4, Charsets.US_ASCII)

private fun writeIntLittleEndian(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    target[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

/**
 * 角色轨道配置：mode=preset/design/clone（音色来源下拉动态列出）；引擎经下拉菜单选择；
 * format 为输出格式（引擎原生支持集，服务端透传不转码，同轨道全程一致保证可拼接）；
 * params 为当前引擎的动态参数值（换引擎时按 roleParamsFor 重置为默认，保留同名自定义值）。
 */
internal data class TtsRoleConfig(
    val mode: String = "preset",
    val modelId: String = "",
    val voiceId: String = "",
    val voiceDescription: String = "",
    val baseStyle: String = "",
    val params: Map<String, String> = emptyMap(),
    val format: String = "",
)

/** 输出格式默认值：目录 default_format 优先（微软=mp3、MiMo=mp3、design=wav），
 *  旧目录无该字段时回退 formats 首位，再兜底 wav。 */
internal fun ttsDefaultFormatFor(model: TtsModelInfo?): String =
    model?.defaultFormat?.takeIf { it.isNotBlank() }
        ?: model?.formats?.firstOrNull()?.takeIf { !it.isNullOrBlank() }
        ?: "wav"

/**
 * 角色轨道格式决策（2026-09-29 用户裁决：客户端零转码、零静音处理，如实拼接）。
 * - 无损选择（wav/pcm 族）：片段 wav，整轨按 WAV 头部重写顺接（concatTtsWav）。
 * - 压缩选择（mp3 族，两引擎目录默认）：片段即所选格式，整轨按字节直通顺接。
 * 产物容器以片段实际格式为准（目录选项已裁剪为 mp3/wav 两族，均可直接顺接且可播放）。
 */
internal data class TtsTrackFormatPlan(val segmentFormat: String, val deliverableFormat: String)

internal fun ttsTrackFormatPlan(selectedFormat: String?): TtsTrackFormatPlan {
    val normalized = selectedFormat.orEmpty().trim().lowercase()
    val lossless = normalized.startsWith("wav") || normalized.startsWith("pcm")
    return if (lossless) {
        TtsTrackFormatPlan(segmentFormat = "wav", deliverableFormat = "wav")
    } else {
        val format = normalized.ifBlank { "mp3" }
        TtsTrackFormatPlan(
            segmentFormat = format,
            deliverableFormat = if (format.startsWith("mp3")) "mp3" else format,
        )
    }
}

/** 段格式探测：RIFF/WAVE 头即 WAV（data 块可位于 LIST 等附加块之后），否则按 MP3 流处理。 */
internal fun ttsChunkIsWav(chunk: ByteArray): Boolean =
    chunk.size >= 12 && chunk.decodeHeader(0) == "RIFF" && chunk.decodeHeader(8) == "WAVE"

/** MP3 流顺接已下线（2026-09-23）：原始 MP3 字节直接顺接会在片段之间留编码间隙，
 *  最终轨道统一由 TtsAudioAssembler 解码为 PCM 裁剪边界静音后写出 WAV。 */

/** 当前引擎的动态参数值：schema 默认打底、同名自定义值保留（换引擎/补新参数都安全）。 */
internal fun roleParamsFor(model: TtsModelInfo?, existing: Map<String, String>): Map<String, String> {
    if (model == null) return emptyMap()
    return model.paramsSchema.associate { spec ->
        spec.name to (existing[spec.name]?.takeIf { it.isNotBlank() } ?: spec.default)
    }
}

/** 角色卡与角色语音页的音色摘要（一句话说清当前来源与音色）。 */
internal fun ttsRoleVoiceSummary(config: TtsRoleConfig, models: List<TtsModelInfo>, referenceName: String): String =
    when (config.mode) {
        "design" -> "设计·描述音色"
        "clone" -> if (referenceName.isBlank()) "克隆·未选参考音频" else "克隆·$referenceName"
        else -> {
            val model = models.firstOrNull { it.id == config.modelId }
            val voice = model?.voices?.firstOrNull { it.id == config.voiceId }?.label
            "预置·${voice ?: model?.label ?: "默认音色"}"
        }
    }

/** 角色合成请求里的 model 解析：按配置找已登记引擎，找不到回退该来源第一个；无效配置返回 null 由调用方提示。 */
internal fun ttsRoleModel(config: TtsRoleConfig, models: List<TtsModelInfo>): TtsModelInfo? {
    val ofMode: (String) -> List<TtsModelInfo> = { mode -> models.filter { it.mode == mode } }
    return when (config.mode) {
        "design" -> ofMode("design").firstOrNull { it.id == config.modelId } ?: ofMode("design").firstOrNull()
        "clone" -> ofMode("clone").firstOrNull { it.id == config.modelId } ?: ofMode("clone").firstOrNull()
        else -> ofMode("preset").firstOrNull { it.id == config.modelId } ?: ofMode("preset").firstOrNull()
    }
}

/** 切换引擎/音色来源时的默认音色：优先中文（剧本以中文为主，避免默认落在英文音色上），无中文取首个。 */
internal fun ttsDefaultVoiceId(model: TtsModelInfo?): String {
    model ?: return ""
    if (model.voices.isEmpty()) return ""
    return (model.voices.firstOrNull { it.lang.lowercase().startsWith("zh") } ?: model.voices.first()).id
}
