package com.example.local_music_player

import android.net.Uri

/** 导入和文件浏览共享清单，防止解码器已支持、入口却仍过滤文件。 */
internal val LOCAL_AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "ogg", "oga", "wav", "aiff", "aif", "mp2", "mp1", "m4a", "aac", "m4b",
    "ape", "opus", "wv", "dsf", "dff", "webm", "mka", "tta", "wma", "tak", "mpc", "mpc8",
    "ac3", "eac3", "dts", "truehd", "mlp", "caf",
)

private val FFMPEG_AUDIO_EXTENSIONS = setOf(
    "ape",
    "wv",
    "wavpack",
    "dsf",
    "dff",
    "dsd",
    "tta",
    "wma",
    "asf",
    "alac",
    "tak",
    "mpc",
    "mpc8",
    "ac3",
    "eac3",
    "dts",
    "truehd",
    "mlp",
    "caf",
)

private val FFMPEG_AUDIO_MIME_TYPES = setOf(
    "audio/ape",
    "audio/x-ape",
    "audio/wavpack",
    "audio/x-wavpack",
    "audio/dsf",
    "audio/x-dsf",
    "audio/dff",
    "audio/x-dff",
    "audio/dsd",
    "audio/x-dsd",
    "audio/tta",
    "audio/x-tta",
    "audio/x-ms-wma",
    "audio/wma",
    "audio/alac",
    "audio/x-alac",
)

internal fun isFfmpegAudioTrack(track: NativeTrack): Boolean {
    if (track.isVideo) return false
    return isFfmpegAudioType(track.format) ||
        isFfmpegAudioType(track.mimeType) ||
        isFfmpegAudioUri(track.uri)
}

internal fun isFfmpegAudioUrl(uri: Uri): Boolean = isFfmpegAudioUri(uri)

internal fun canOpenWithFfmpeg(uri: Uri): Boolean = canOpenWithFfmpeg(uri.scheme, uri.path)

internal fun canOpenWithFfmpeg(scheme: String?, path: String?): Boolean =
    inferStreamMimeType(scheme, path) == null && when (scheme?.lowercase()) {
    // https 需与原生构建对齐：FFmpeg 须编入 tls/https 协议（见 android/vendor/ffmpeg/build.sh）。
    "file", "content", "http", "https" -> true
    else -> false
}

private fun isFfmpegAudioUri(uri: Uri): Boolean =
    uri.path.orEmpty().substringAfterLast(".", "").lowercase() in FFMPEG_AUDIO_EXTENSIONS

internal fun isFfmpegAudioType(value: String): Boolean {
    val normalized = value.substringBefore(';').trim().lowercase()
    if (normalized in FFMPEG_AUDIO_EXTENSIONS || normalized in FFMPEG_AUDIO_MIME_TYPES) return true
    return normalized.substringAfterLast("/", "") in FFMPEG_AUDIO_EXTENSIONS
}
