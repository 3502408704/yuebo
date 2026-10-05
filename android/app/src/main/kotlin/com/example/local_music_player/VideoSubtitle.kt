package com.example.local_music_player

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/** 字幕来源：本地外挂、B 站在线字幕，或用户显式请求的 AI 定时字幕。 */
enum class VideoSubtitleSource { External, Bilibili, AiTranscript }

data class VideoSubtitleTrack(
    val id: String,
    val label: String,
    val language: String = "",
    val url: String = "",
    val source: VideoSubtitleSource = VideoSubtitleSource.External,
    val isAiGenerated: Boolean = false,
    val isLocked: Boolean = false,
    val authorName: String = "",
) {
    val sourceLabel: String
        get() = when (source) {
            VideoSubtitleSource.External -> "外挂字幕"
            VideoSubtitleSource.Bilibili -> "B站字幕"
            VideoSubtitleSource.AiTranscript -> "AI转录"
        }
}

data class BilibiliSubtitleTrackList(
    val tracks: List<VideoSubtitleTrack> = emptyList(),
    val needLoginSubtitle: Boolean = false,
    val allowSubmit: Boolean = false,
)

data class VideoSubtitleCueItem(
    val id: String,
    val text: String,
    val positionMs: Long,
    val endPositionMs: Long,
)

/** 一条外部视频字幕，时间单位为毫秒。 */
data class VideoSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** 解析 B 站 /x/player/v2 或 /x/player/wbi/v2 返回的字幕轨道。 */
internal fun parseBilibiliSubtitleTracks(json: String): BilibiliSubtitleTrackList {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return BilibiliSubtitleTrackList()
    if (root.optInt("code", -1) != 0) return BilibiliSubtitleTrackList()
    val data = root.optJSONObject("data") ?: return BilibiliSubtitleTrackList()
    val subtitle = data.optJSONObject("subtitle")
    val subtitles = subtitle?.optJSONArray("subtitles")
        ?: data.optJSONArray("subtitles")
        ?: JSONArray()
    val tracks = buildList {
        for (index in 0 until subtitles.length()) {
            val item = subtitles.optJSONObject(index) ?: continue
            val url = normalizeVideoSubtitleUrl(item.jsonString("subtitle_url").orEmpty())
            if (url.isBlank()) continue
            val language = item.jsonString("lan").orEmpty()
            val id = item.jsonString("id_str").orEmpty()
                .ifBlank { item.optLong("id", 0L).takeIf { it > 0 }?.toString().orEmpty() }
                .ifBlank { url }
            val aiType = item.optInt("ai_type", 0)
            add(
                VideoSubtitleTrack(
                    id = "bili:$id",
                    label = item.jsonString("lan_doc").orEmpty().ifBlank { language.ifBlank { "字幕" } },
                    language = language,
                    url = url,
                    source = VideoSubtitleSource.Bilibili,
                    isAiGenerated = aiType > 0 || language.startsWith("ai-", ignoreCase = true) ||
                        url.contains("/ai_subtitle/", ignoreCase = true),
                    isLocked = item.optBoolean("is_lock", false),
                    authorName = item.optJSONObject("author")?.jsonString("name").orEmpty(),
                ),
            )
        }
    }
    return BilibiliSubtitleTrackList(
        tracks = tracks
            .groupBy { baseVideoSubtitleLanguage(it.language) }
            .flatMap { (_, sameLanguage) ->
                val human = sameLanguage.filterNot(VideoSubtitleTrack::isAiGenerated)
                if (human.isNotEmpty()) human else sameLanguage
            }
            .distinctBy(VideoSubtitleTrack::id),
        needLoginSubtitle = data.optBoolean("need_login_subtitle", false),
        allowSubmit = subtitle?.optBoolean("allow_submit", false) == true,
    )
}

/** 解析 B 站字幕 JSON：body 的 from/to 单位是秒。 */
internal fun parseBilibiliSubtitleDocument(json: String): List<VideoSubtitleCue> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val body = root.optJSONArray("body") ?: return emptyList()
    return buildList {
        for (index in 0 until body.length()) {
            val item = body.optJSONObject(index) ?: continue
            val start = item.number("from") ?: continue
            val end = item.number("to") ?: continue
            val text = item.jsonString("content").orEmpty().toVideoSubtitleText()
            if (text.isNotBlank() && end > start) {
                add(VideoSubtitleCue((start * 1_000).toLong(), (end * 1_000).toLong(), text))
            }
        }
    }.sortedBy(VideoSubtitleCue::startMs)
}

/** 解析 B 站 AI 总结接口内嵌的定时字幕。 */
internal fun parseBilibiliAiSubtitle(json: String): List<VideoSubtitleCue> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    if (root.optInt("code", -1) != 0) return emptyList()
    val model = root.optJSONObject("data")?.optJSONObject("model_result") ?: return emptyList()
    val sections = model.optJSONArray("subtitle") ?: return emptyList()
    return buildList {
        for (sectionIndex in 0 until sections.length()) {
            val section = sections.optJSONObject(sectionIndex) ?: continue
            val parts = section.optJSONArray("part_subtitle") ?: continue
            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                val start = part.number("start_timestamp") ?: continue
                val end = part.number("end_timestamp") ?: continue
                val text = part.jsonString("content").orEmpty().toVideoSubtitleText()
                if (text.isNotBlank() && end > start) {
                    add(VideoSubtitleCue((start * 1_000).toLong(), (end * 1_000).toLong(), text))
                }
            }
        }
    }.sortedBy(VideoSubtitleCue::startMs)
}

internal fun normalizeVideoSubtitleUrl(value: String): String = when {
    value.startsWith("//") -> "https:$value"
    value.startsWith("http://") || value.startsWith("https://") -> value
    value.isBlank() -> ""
    else -> "https://$value"
}

internal fun pickDefaultVideoSubtitleTrack(tracks: List<VideoSubtitleTrack>): VideoSubtitleTrack? =
    tracks.firstOrNull { !it.isLocked && !it.isAiGenerated }
        ?: tracks.firstOrNull { !it.isLocked }

/** 语言主干：去掉 AI 前缀与地区后缀，使 ai-zh、zh-CN、zh-Hans 视为同一种语言。 */
internal fun baseVideoSubtitleLanguage(language: String): String =
    language.lowercase().removePrefix("ai-").substringBefore('-')

internal fun pickDefaultVideoSecondarySubtitleTrack(
    tracks: List<VideoSubtitleTrack>,
    primaryId: String?,
): VideoSubtitleTrack? {
    val primaryLanguage = tracks.firstOrNull { it.id == primaryId }?.language?.let(::baseVideoSubtitleLanguage)
    return tracks.firstOrNull {
        !it.isLocked && it.id != primaryId &&
            (primaryLanguage == null || baseVideoSubtitleLanguage(it.language) != primaryLanguage)
    }
}

internal fun videoSubtitleCueId(trackId: String, index: Int, cue: VideoSubtitleCue): String =
    "$trackId:$index:${cue.startMs}"

internal fun videoSubtitleCueItems(trackId: String, cues: List<VideoSubtitleCue>): List<VideoSubtitleCueItem> =
    cues.mapIndexedNotNull { index, cue ->
        val text = cue.text.toVideoSubtitleText()
        text.takeIf(String::isNotBlank)?.let {
            VideoSubtitleCueItem(videoSubtitleCueId(trackId, index, cue), it, cue.startMs, cue.endMs)
        }
    }

private fun JSONObject.jsonString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.number(name: String): Double? = when (val value = opt(name)) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

private fun String.toVideoSubtitleText(): String =
    replace(Regex("\\{[^}]*}"), "")
        .replace(Regex("<[^>]+>"), "")
        .replace("\\N", " ")
        .replace("\\n", " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

private val subtitleTimeRegex = Regex(
    """(\d{1,2}:)?(\d{1,3}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,3}):(\d{2})[,.](\d{1,3})""",
)
private val assDialogueRegex = Regex("""^Dialogue:\s*(.*)$""", RegexOption.IGNORE_CASE)

/** 按文件扩展名解析常见外挂字幕格式；不识别的格式返回空列表。 */
fun parseVideoSubtitles(fileName: String, raw: String): List<VideoSubtitleCue> = when (fileName.substringAfterLast('.', "").lowercase()) {
    "srt", "vtt" -> parseSrtLike(raw)
    "ass", "ssa" -> parseAss(raw)
    "lrc" -> parseLrc(raw)
    "ttml", "xml" -> parseTtml(raw)
    else -> emptyList()
}

private fun parseSrtLike(raw: String): List<VideoSubtitleCue> {
    val cues = mutableListOf<VideoSubtitleCue>()
    val lines = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()
    var index = 0
    while (index < lines.size) {
        val match = subtitleTimeRegex.find(lines[index])
        if (match == null) {
            index++
            continue
        }
        val start = parseSubtitleTime(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
        val end = parseSubtitleTime(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
        val text = buildList {
            var next = index + 1
            while (next < lines.size && lines[next].isNotBlank()) add(lines[next].trim())
                .also { next++ }
            index = next
        }.joinToString("\n").cleanVideoSubtitleText(preserveLineBreaks = true, removeAssTags = false)
        if (start >= 0 && end > start && text.isNotBlank()) cues += VideoSubtitleCue(start, end, text)
        index++
    }
    return cues.sortedBy(VideoSubtitleCue::startMs)
}

private fun parseAss(raw: String): List<VideoSubtitleCue> = raw.lineSequence()
    .mapNotNull { line ->
        val body = assDialogueRegex.matchEntire(line.trim())?.groupValues?.get(1) ?: return@mapNotNull null
        val fields = body.split(',', limit = 10)
        if (fields.size < 10) return@mapNotNull null
        val start = parseAssTime(fields[1]) ?: return@mapNotNull null
        val end = parseAssTime(fields[2]) ?: return@mapNotNull null
        val text = fields[9]
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .cleanVideoSubtitleText(preserveLineBreaks = true)
        if (end <= start || text.isBlank()) null else VideoSubtitleCue(start, end, text)
    }
    .sortedBy(VideoSubtitleCue::startMs)
    .toList()

private fun parseLrc(raw: String): List<VideoSubtitleCue> {
    val lines = parseLyrics(raw)
    return lines.mapIndexedNotNull { index, line ->
        val next = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 5_000)
        if (next <= line.timeMs || line.text.isBlank()) null
        else VideoSubtitleCue(line.timeMs, next, line.text)
    }
}

/** 解析常见 TTML/XML 字幕，支持 p(begin/end) 与 p(begin/dur)。 */
private fun parseTtml(raw: String): List<VideoSubtitleCue> = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
    val namespacedNodes = document.getElementsByTagNameNS("*", "p")
    val nodes = if (namespacedNodes.length > 0) namespacedNodes else document.getElementsByTagName("p")
    buildList {
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) ?: continue
            val start = node.attribute("begin")?.let(::parseTtmlTime) ?: continue
            val end = node.attribute("end")?.let(::parseTtmlTime)
            val duration = node.attribute("dur")?.let(::parseTtmlTime)
            val endMs = end ?: duration?.let { start + it } ?: continue
            val text = node.ttmlText().cleanVideoSubtitleText(preserveLineBreaks = true, removeAssTags = false)
            if (text.isNotBlank() && endMs > start) add(VideoSubtitleCue(start, endMs, text))
        }
    }.sortedBy(VideoSubtitleCue::startMs)
}.getOrDefault(emptyList())

private fun parseSubtitleTime(hour: String, minute: String, second: String, fraction: String): Long {
    val hours = hour.removeSuffix(":").toLongOrNull() ?: 0L
    val minutes = minute.toLongOrNull() ?: return -1L
    val seconds = second.toLongOrNull() ?: return -1L
    val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: return -1L
    return ((hours * 60 + minutes) * 60 + seconds) * 1_000 + millis
}

private fun parseAssTime(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size != 3) return null
    val seconds = parts[2].replace(',', '.').toDoubleOrNull() ?: return null
    return ((parts[0].toLongOrNull() ?: return null) * 60_000L) +
        ((parts[1].toLongOrNull() ?: return null) * 1_000L) + (seconds * 1_000).toLong()
}

private fun String.stripSubtitleTags(): String =
    cleanVideoSubtitleText(preserveLineBreaks = true, removeAssTags = false)

private val ttmlClockTimeRegex = Regex(
    """^(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?$""",
)
private val ttmlOffsetTimeRegex = Regex(
    """^([0-9]+(?:\.[0-9]+)?)\s*(ms|h|m|s)$""",
    RegexOption.IGNORE_CASE,
)

private fun parseTtmlTime(value: String): Long? {
    val normalized = value.trim()
    ttmlClockTimeRegex.matchEntire(normalized)?.let { match ->
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millis = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000L + millis
    }
    ttmlOffsetTimeRegex.matchEntire(normalized)?.let { match ->
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "ms" -> 1.0
            "h" -> 3_600_000.0
            "m" -> 60_000.0
            else -> 1_000.0
        }
        return (amount * multiplier).roundToLong().takeIf { it >= 0L }
    }
    return normalized.toDoubleOrNull()?.times(1_000.0)?.roundToLong()?.takeIf { it >= 0L }
}

private fun Node.attribute(name: String): String? =
    attributes?.getNamedItem(name)?.nodeValue?.takeIf { it.isNotBlank() }

private fun Node.ttmlText(): String = buildString {
    fun appendNode(node: Node) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> append(node.nodeValue.orEmpty())
            Node.ELEMENT_NODE -> {
                if (node.nodeName.substringAfterLast(':').equals("br", ignoreCase = true)) {
                    append('\n')
                } else {
                    for (index in 0 until node.childNodes.length) appendNode(node.childNodes.item(index))
                }
            }
        }
    }
    for (index in 0 until childNodes.length) appendNode(childNodes.item(index))
}

private val numericHtmlEntityRegex = Regex("&#(?:x([0-9a-fA-F]+)|([0-9]+));")

private fun String.cleanVideoSubtitleText(
    preserveLineBreaks: Boolean = false,
    removeAssTags: Boolean = true,
): String {
    val normalized = replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .let { if (removeAssTags) it.replace(Regex("\\{[^}]*}"), "") else it }
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(numericHtmlEntityRegex) { match ->
            val codePoint = match.groupValues[1].toIntOrNull(16)
                ?: match.groupValues[2].toIntOrNull()
            codePoint?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
        }
    val lines = normalized.lines().map { it.trim().replace(Regex("[ \\t]+"), " ") }
    return if (preserveLineBreaks) lines.joinToString("\n").trim() else lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
}

/** 返回当前时间点生效的字幕，早于第一句或字幕为空时返回 -1。 */
fun activeVideoSubtitleIndex(cues: List<VideoSubtitleCue>, positionMs: Long): Int {
    var low = 0
    var high = cues.lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (cues[middle].startMs <= positionMs) {
            if (positionMs < cues[middle].endMs) result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result
}
