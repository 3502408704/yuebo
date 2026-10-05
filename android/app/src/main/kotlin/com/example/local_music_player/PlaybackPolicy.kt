package com.example.local_music_player

import kotlin.math.log10
import kotlin.math.max

/**
 * 播放策略纯函数与音效设置（自 BASS 内核下线后，保留仍被 VM/引擎/测试使用的部分）。
 * 采样级能力（静音跳过、响度实时估算等）随 BASS 移除；标签级响度换算与 seek 步进策略继续使用。
 */

internal fun streamSeekPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)

internal fun seekStepPosition(positionMs: Long, stepMs: Long, durationMs: Long, forward: Boolean): Long =
    if (forward) {
        if (durationMs > 0) (positionMs + stepMs).coerceAtMost(durationMs) else positionMs + stepMs
    } else {
        (positionMs - stepMs).coerceAtLeast(0)
    }

internal fun networkSeekRetryDelayMs(attempt: Int): Long? = if (attempt in 0 until 50) 100L else null

private const val REPLAY_GAIN_REFERENCE_LUFS = -18f
private const val R128_REFERENCE_LUFS = -23f
private const val LOUDNESS_SAFETY_CEILING_DB = -1f

internal fun loudnessGainDb(tags: List<String>, targetLufs: Float): Float? {
    fun value(name: String): String? = tags.firstOrNull {
        it.substringBefore('=').substringAfterLast(':').equals(name, ignoreCase = true)
    }?.substringAfter('=')

    val number = Regex("[-+]?\\d+(?:\\.\\d+)?")
    value("R128_TRACK_GAIN")?.let { raw ->
        raw.trim().toFloatOrNull()?.let { return it / 256f + (targetLufs - R128_REFERENCE_LUFS) }
    }
    return value("REPLAYGAIN_TRACK_GAIN")
        ?.let(number::find)
        ?.value
        ?.toFloatOrNull()
        ?.plus(targetLufs - REPLAY_GAIN_REFERENCE_LUFS)
}

internal fun limitLoudnessGainDb(requestedDb: Float, peak: Float?): Float {
    if (peak == null || peak !in 0f..1f || peak <= 0f) return requestedDb.coerceAtMost(0f)
    return minOf(requestedDb, LOUDNESS_SAFETY_CEILING_DB - 20f * log10(peak))
}

internal fun integratedLufs(blockEnergies: List<Double>): Float? {
    val audible = blockEnergies.filter { it > 0.0 && -0.691 + 10.0 * log10(it) >= -70.0 }
    if (audible.isEmpty()) return null
    fun lufs(values: List<Double>) = (-0.691 + 10.0 * log10(values.average())).toFloat()
    val ungated = lufs(audible)
    val gated = audible.filter { -0.691 + 10.0 * log10(it) >= max(-70f, ungated - 10f) }
    return gated.takeIf { it.isNotEmpty() }?.let(::lufs)
}

internal fun analyzedLoudnessGainDb(lufs: Float, targetLufs: Float, peak: Float): Float =
    (targetLufs - lufs).coerceIn(-24f, 12f)

internal fun normalizationGainDb(requestedDb: Float): Float = requestedDb.coerceIn(-24f, 12f)

internal fun migrateLoudnessTarget(loudness: Int, alreadyLufs: Boolean): Int =
    if (alreadyLufs || loudness == 0) loudness else -160

/**
 * 音效设置。
 * - reverb/chorus/echo/flanger：0..100 强度，0 表示关闭。
 * - loudness：响度标准化目标（LUFS 的 10 倍负值），范围 -200..-1（对应 -20.0..-0.1 LUFS），0 表示关闭。
 *   使用 ReplayGain/R128 标签或播放中的实时估算；最大增益为 +12 dB，软限幅器保护峰值不削波。
 * - loudnessCompress：是否启用额外压缩（默认关）。压低响度大的段落、抬高低响度段落使整曲响度更一致，
 *   但会损失部分动态范围。开启时 UI 必须提示该后果。
 */
data class AudioEffects(
    val reverb: Int = 0,
    val chorus: Int = 0,
    val echo: Int = 0,
    val flanger: Int = 0,
    val loudness: Int = 0,
    val loudnessCompress: Boolean = false,
) {
    val enabled: Boolean get() = reverb > 0 || chorus > 0 || echo > 0 || flanger > 0 || loudness != 0
}
