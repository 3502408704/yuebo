package com.example.local_music_player

import android.net.Uri
import java.util.Locale

const val DOWNLOAD_BASE_RELATIVE_PATH = "Music/月播"

enum class DownloadTaskStatus { QUEUED, DOWNLOADING, WAITING_NETWORK, PAUSED, FAILED, COMPLETED, FINALIZING }

data class DownloadTaskSnapshot(
    val id: String,
    val status: DownloadTaskStatus,
    val downloadedBytes: Long = 0,
)

data class DownloadTask(
    val id: String,
    val track: OnlineTrack,
    val qualityApiValue: String,
    val mimeType: String,
    val extension: String,
    val folderName: String? = null,
    val trackNo: Int? = null,
    val lyrics: String? = null,
    val mediaStoreUri: Uri?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etag: String?,
    val status: DownloadTaskStatus,
    val error: String?,
    val retryCount: Int,
    val nextRetryAtMs: Long?,
    val createdAtMs: Long,
    val completedAtMs: Long?,
) {
    val onlineKey: String get() = track.key
}

internal fun downloadFailureMessage(error: Throwable): String =
    when (error) {
        is java.io.IOException -> "网络错误，请稍后重试"
        else -> "下载失败，请重试"
    }

internal fun canFinalizeDownloadFailure(status: DownloadTaskStatus): Boolean =
    status == DownloadTaskStatus.QUEUED || status == DownloadTaskStatus.DOWNLOADING ||
        status == DownloadTaskStatus.FINALIZING

fun canTransitionDownloadTask(from: DownloadTaskStatus, to: DownloadTaskStatus): Boolean = when (to) {
    DownloadTaskStatus.DOWNLOADING -> from == DownloadTaskStatus.QUEUED
    DownloadTaskStatus.FINALIZING -> from == DownloadTaskStatus.DOWNLOADING
    DownloadTaskStatus.PAUSED -> from in setOf(
        DownloadTaskStatus.QUEUED,
        DownloadTaskStatus.DOWNLOADING,
        DownloadTaskStatus.WAITING_NETWORK,
        DownloadTaskStatus.FINALIZING,
    )
    DownloadTaskStatus.QUEUED -> from in setOf(DownloadTaskStatus.PAUSED, DownloadTaskStatus.FAILED)
    else -> false
}

fun downloadTaskAnnouncement(title: String, status: DownloadTaskStatus): String? = when (status) {
    DownloadTaskStatus.COMPLETED -> "下载完成：$title"
    else -> null
}

fun downloadMimeType(extension: String): String = when (extension.lowercase()) {
    "flac" -> "audio/flac"
    "aac" -> "audio/aac"
    "m4a", "m4b" -> "audio/mp4"
    "mp4" -> "video/mp4"
    "ogg", "oga" -> "audio/ogg"
    "wav" -> "audio/wav"
    "opus" -> "audio/opus"
    else -> "audio/mpeg"
}

fun downloadDisplayName(artist: String, title: String, extension: String, trackNo: Int? = null): String {
    val prefix = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "在线音乐" }
    val numbered = if (trackNo != null && trackNo > 0) "%02d - %s".format(trackNo, prefix) else prefix
    return numbered.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180) + ".${extension.ifBlank { "mp3" }}"
}

fun albumDownloadFolderName(albumName: String): String =
    albumName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "在线专辑" }

/** 下载速度展示，如 "2.3 MB/s"、"512 KB/s"、"120 B/s"。 */
fun formatDownloadSpeed(bytesPerSecond: Long): String {
    val speed = bytesPerSecond.coerceAtLeast(0)
    return when {
        speed >= 1_048_576 -> String.format(Locale.US, "%.1f MB/s", speed / 1_048_576f)
        speed >= 1_024 -> String.format(Locale.US, "%.1f KB/s", speed / 1_024f)
        else -> "$speed B/s"
    }
}

/** 剩余时间展示，如 "约 1 分 20 秒"、"约 2 小时 5 分"。 */
fun formatRemainingTime(etaMs: Long): String {
    if (etaMs <= 0) return "计算中"
    val totalSeconds = (etaMs / 1000).coerceAtLeast(1)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "约 $hours 小时 $minutes 分"
        minutes > 0 -> "约 $minutes 分 $seconds 秒"
        else -> "约 $seconds 秒"
    }
}
