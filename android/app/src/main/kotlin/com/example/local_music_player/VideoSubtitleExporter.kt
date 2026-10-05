package com.example.local_music_player

import java.util.Locale

/** 字幕导出只依赖数据模型，便于在 JVM 单测中验证格式。 */
object VideoSubtitleExporter {
    fun toWebVtt(cues: List<VideoSubtitleCue>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        validCues(cues).forEachIndexed { index, cue ->
            if (index > 0) appendLine()
            append(formatTimestamp(cue.startMs))
            append(" --> ")
            appendLine(formatTimestamp(cue.endMs))
            appendLine(escapeCueText(cue.text))
        }
    }

    fun toPlainText(cues: List<VideoSubtitleCue>): String =
        validCues(cues).joinToString("\n") { normalizeText(it.text) }

    private fun validCues(cues: List<VideoSubtitleCue>): List<VideoSubtitleCue> =
        cues.asSequence()
            .filter { it.endMs > it.startMs }
            .map { it.copy(text = normalizeText(it.text)) }
            .filter { it.text.isNotBlank() }
            .sortedBy(VideoSubtitleCue::startMs)
            .toList()

    private fun formatTimestamp(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val hours = safeMs / 3_600_000L
        val minutes = (safeMs % 3_600_000L) / 60_000L
        val seconds = (safeMs % 60_000L) / 1_000L
        val millis = safeMs % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun escapeCueText(text: String): String =
        normalizeText(text).replace("-->", "-\u003e")

    private fun normalizeText(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map(String::trim)
            .joinToString("\n")
            .trim()
}
