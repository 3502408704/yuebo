package com.example.local_music_player

internal const val LYRIC_OFFSET_MIN_MS = -2_000L
internal const val LYRIC_OFFSET_MAX_MS = 2_000L

internal fun normalizeLyricOffset(offsetMs: Long): Long {
    val clamped = offsetMs.coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)
    val seconds = if (clamped >= 0) (clamped + 500L) / 1_000L else (clamped - 500L) / 1_000L
    return seconds.coerceIn(-2L, 2L) * 1_000L
}

internal fun stepLyricOffset(currentMs: Long, deltaSeconds: Int): Long =
    (normalizeLyricOffset(currentMs) + deltaSeconds * 1_000L)
        .coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)

internal fun lyricOffsetAnnouncement(offsetMs: Long): String = when (val normalized = normalizeLyricOffset(offsetMs)) {
    0L -> "不调整"
    in 1..LYRIC_OFFSET_MAX_MS -> "提前 ${normalized / 1_000} 秒"
    else -> "延后 ${-normalized / 1_000} 秒"
}
