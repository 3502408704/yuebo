package com.example.local_music_player

/** 一条带时间轴（毫秒）的歌词。 */
data class LyricLine(val timeMs: Long, val text: String)

private val timeTagRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?(?:-\d+)?]""")
private val offsetRegex = Regex("""\[offset:\s*([+-]?\d+)]""")
private val qrcTagRegex = Regex("""\[(\d+),(\d+)]""")
private val jsonLyricKeys = listOf("lyric", "lyrics", "lrc", "content")

/**
 * 解析 LRC 歌词为按时间升序的时间轴行。
 * 支持 [mm:ss] / [mm:ss.xx] / [mm:ss.xxx]，一行多时间戳展开，忽略 [ti:]/[ar:]/[al:]/[by:] 等元信息，
 * 应用 [offset:±ms] 全局偏移（下限 0）。无时间戳的纯文本行与空文本（纯时间戳）行跳过。
 */
fun parseLyrics(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val source = unwrapLyricPayload(raw)
    val offsetMs = offsetRegex.find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val lines = mutableListOf<LyricLine>()
    for (sourceLine in source.lineSequence()) {
        val line = sourceLine.removeSuffix("\r").trimStart()
        val times = mutableListOf<Long>()
        var lastEnd = 0
        while (true) {
            val next = line.indexOfFirstNonWhitespace(lastEnd)
            if (next < 0) break
            val match = timeTagRegex.find(line, next)
            if (match == null || match.range.first != next) break
            times += parseStandardTime(match)
            lastEnd = match.range.last + 1
        }
        if (times.isEmpty()) {
            while (true) {
                val next = line.indexOfFirstNonWhitespace(lastEnd)
                if (next < 0) break
                val match = qrcTagRegex.find(line, next)
                if (match == null || match.range.first != next) break
                times += match.groupValues[1].toLongOrNull() ?: 0L
                lastEnd = match.range.last + 1
            }
        }
        if (times.isEmpty()) continue
        val text = line.substring(lastEnd).trim()
        if (text.isBlank()) continue
        for (time in times) {
            lines += LyricLine((time + offsetMs).coerceAtLeast(0L), text)
        }
    }
    return lines.sortedBy(LyricLine::timeMs).distinct()
}

private fun String.indexOfFirstNonWhitespace(start: Int): Int {
    for (index in start until length) if (!this[index].isWhitespace()) return index
    return -1
}

private fun parseStandardTime(match: MatchResult): Long {
    val minutes = match.groupValues[1].toLongOrNull() ?: return 0L
    val seconds = match.groupValues[2].toLongOrNull() ?: return 0L
    val fractionMs = when (match.groupValues[3].length) {
        1 -> match.groupValues[3].toInt() * 100
        2 -> match.groupValues[3].toInt() * 10
        3 -> match.groupValues[3].toInt()
        else -> 0
    }
    return minutes * 60_000 + seconds * 1_000 + fractionMs
}

private fun unwrapLyricPayload(raw: String): String {
    val text = raw.removePrefix("\uFEFF").trim()
    if (!text.startsWith("{")) return text
    return runCatching {
        val json = org.json.JSONObject(text)
        jsonLyricKeys.asSequence().mapNotNull { key ->
            json.optString(key).takeIf { !json.isNull(key) && it.isNotBlank() }
        }.firstOrNull()
    }.getOrNull() ?: text
}

/** 返回播放到 [positionMs] 时应高亮的行下标；早于第一句或为空时返回 -1。 */
fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty() || positionMs < lines.first().timeMs) return -1
    var low = 0
    var high = lines.size - 1
    var result = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

private val wordTagRegex = Regex("""<(\d{1,4}?)(?:[.,](\d{1,3}))?,(?:\d{1,4}?)(?:[.,](\d{1,3}))?>""")

/**
 * 计算一句歌词的“可见字符 + 起始时间戳”配对。
 * - 若文本含增强 LRC 的 `<开始,时长>字` 片段，则按标记解析，每字取标记开始时间；
 * - 否则按可见字符数等分本句时长（[nextLineStartMs] - 本句 [line.timeMs]）估算，无下句时按句内每字 500ms 兜底。
 * 空白字符会被跳过（不参与高亮）；空句返回空列表。返回的绝对时间戳与可见字符一一对应。
 */
fun wordChars(line: LyricLine, nextLineStartMs: Long?): List<Pair<Char, Long>> {
    val text = line.text
    if (text.isBlank()) return emptyList()
    val base = line.timeMs
    if (wordTagRegex.containsMatchIn(text)) {
        val words = mutableListOf<Pair<Char, Long>>()
        var index = 0
        var matcher = wordTagRegex.find(text, index)
        while (matcher != null) {
            // 标记前的普通文字：沿用上一个时间（或 0）
            val before = text.substring(index, matcher.range.first)
            val beforeTime = words.lastOrNull()?.second ?: base
            before.forEach { ch -> if (!ch.isWhitespace()) words += (ch to beforeTime) }
            val start = matcher.groupValues[1].toLongOrNull() ?: 0L
            val frac = when (matcher.groupValues[2].length) {
                1 -> matcher.groupValues[2].toInt() * 100
                2 -> matcher.groupValues[2].toInt() * 10
                3 -> matcher.groupValues[2].toInt()
                else -> 0
            }
            val startMs = base + start * 1000 + frac
            // 标记后的文字直到下一个标记
            val nextTag = wordTagRegex.find(text, matcher.range.last + 1)
            val fragmentEnd = nextTag?.range?.first ?: text.length
            val fragment = text.substring(matcher.range.last + 1, fragmentEnd)
            for (ch in fragment) {
                if (!ch.isWhitespace()) words += (ch to startMs)
            }
            index = fragmentEnd
            matcher = nextTag
        }
        return words
    }
    // 普通 LRC：按可见字符等分本句时长
    val visible = text.filterNot(Char::isWhitespace)
    if (visible.isEmpty()) return emptyList()
    val durationMs = when {
        nextLineStartMs != null && nextLineStartMs > base -> nextLineStartMs - base
        else -> visible.length * 500L
    }
    val step = durationMs.coerceAtLeast(1L) / visible.length
    return visible.mapIndexed { i, ch -> ch to base + i * step }
}

/**
 * 计算一句歌词逐字起始时间戳（绝对毫秒），与可见字符一一对应。
 * 供需要纯时间序列的调用方使用；UI 渲染推荐直接用 [wordChars]。
 */
fun wordTimings(line: LyricLine, nextLineStartMs: Long?): List<Long> =
    wordChars(line, nextLineStartMs).map { it.second }
