package com.example.local_music_player

/**
 * 插件音质档位（MusicFree 插件协议的 quality 枚举）：档位随插件能力动态可用——
 * 播放/下载时按请求档位解析，插件不支持的档位沿 [fallbackOrder] 自动降档。
 */
enum class StreamQuality(val pluginValue: String, val label: String) {
    Low("low", "省流"),
    Standard("standard", "标准"),
    High("high", "高音质"),
    Super("super", "最高音质"),
}

/** A plugin's native quality value plus the name shown to the user. */
data class StreamQualityOption(
    val value: String,
    val label: String,
    val tier: StreamQuality,
)

/** Human readable names for the quality values used by MusicFree plugins. */
fun streamQualityLabel(platform: String?, value: String): String {
    val normalizedPlatform = platform.orEmpty().lowercase()
    return when (value.trim().lowercase()) {
        "128k" -> "128 kbps"
        "192k" -> "192 kbps"
        "320k" -> "320 kbps"
        "flac" -> "无损 FLAC"
        "flac24bit", "24bit" -> if (normalizedPlatform.contains("netease") || normalizedPlatform.contains("网易")) "黑胶" else "24-bit 无损"
        "hires", "hi-res" -> if (normalizedPlatform.contains("qq") || normalizedPlatform.contains("tencent") || normalizedPlatform.contains("qq音乐") || normalizedPlatform.contains("腾讯")) "母带" else "Hi-Res"
        "dolby" -> "杜比全景声"
        "low" -> "省流"
        "standard" -> "标准"
        "high" -> "高音质"
        "super" -> "最高音质"
        else -> value
    }
}

fun StreamQualityOption.displayLabel(platform: String? = null): String =
    label.ifBlank { streamQualityLabel(platform, value) }

fun streamQualityTier(index: Int): StreamQuality =
    StreamQuality.entries[index.coerceIn(0, StreamQuality.entries.lastIndex)]

fun fallbackQualityOptions(): List<StreamQualityOption> =
    StreamQuality.entries.map { quality ->
        StreamQualityOption(quality.pluginValue, quality.label, quality)
    }

/** 降档顺序：请求档位 → 更低档位逐级回退（standard → low）。 */
fun StreamQuality.fallbackOrder(): List<StreamQuality> =
    StreamQuality.entries.take(ordinal + 1).reversed()
