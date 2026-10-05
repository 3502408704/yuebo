package com.example.local_music_player

import android.media.AudioDeviceInfo
import android.net.Uri

/** Media3 主路径 + FFmpeg 长尾格式回退，不改变 ViewModel 的 AudioEngine 契约。 */
internal class HybridAudioEngine(
    private val primary: Media3AudioEngine,
    private val fallback: FfmpegAudioEngine,
) : AudioEngine {
    private var active: AudioEngine = primary
    private var currentTrack: NativeTrack? = null
    private var currentUrl: String? = null
    private var currentDurationMs = 0L
    private var currentHeaders: Map<String, String> = emptyMap()
    private var userPaused = false

    init {
        primary.onUnsupported = { message, position ->
            val track = currentTrack
            val uri = track?.uri ?: currentUrl?.let(Uri::parse)
            if (uri != null && canOpenWithFfmpeg(uri) && active === primary) {
                AppErrorRecorder.event("AudioRouter", "Media3 解码失败，回退 FFmpeg: " + message)
                primary.stop()
                active = fallback
                runCatching {
                    if (track != null) fallback.play(track, position)
                    else fallback.playUrl(currentUrl.orEmpty(), currentDurationMs, position, currentHeaders)
                    if (userPaused) fallback.pause()
                }.onFailure {
                    fallback.stop()
                    AppErrorRecorder.event("AudioRouter", "FFmpeg 回退失败: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    override fun play(track: NativeTrack, positionMs: Long) {
        stopEngines()
        currentTrack = track
        currentUrl = null
        currentHeaders = emptyMap()
        active = if (isFfmpegAudioTrack(track) && canOpenWithFfmpeg(track.uri)) fallback else primary
        active.play(track, positionMs)
    }

    override fun playUrl(url: String, durationMs: Long, positionMs: Long,
                         headers: Map<String, String>, isLive: Boolean) {
        stopEngines()
        currentDurationMs = durationMs
        currentTrack = null
        currentUrl = url
        currentHeaders = headers
        val uri = Uri.parse(url)
        // FFmpeg JNI 直开网络流不带自定义请求头，需 Referer/UA 的直播源只走 Media3
        active = if (!isLive && isFfmpegAudioUrl(uri) && canOpenWithFfmpeg(uri)) fallback else primary
        active.playUrl(url, durationMs, positionMs, headers, isLive)
    }

    private fun stopEngines() {
        userPaused = false
        primary.stop()
        fallback.stop()
    }

    override fun pause(): Boolean {
        userPaused = true
        return active.pause()
    }
    override fun resume(): Boolean {
        val resumed = active.resume()
        if (resumed) userPaused = false
        return resumed
    }
    override fun seek(positionMs: Long): Boolean = active.seek(positionMs)
    override fun positionMs(): Long = active.positionMs()
    override fun durationMs(): Long = active.durationMs()
    override fun lyrics(): String? = active.lyrics()
    override fun isPlaying(): Boolean = active.isPlaying()
    override fun isStopped(): Boolean = active.isStopped()

    override fun setVolume(value: Float): Boolean {
        primary.setVolume(value)
        fallback.setVolume(value)
        return true
    }

    override fun setSpeed(value: Double): Boolean {
        return active.setSpeed(value)
    }

    override fun setFadeEnabled(enabled: Boolean) {
        primary.setFadeEnabled(enabled)
        fallback.setFadeEnabled(enabled)
    }

    override fun setSilenceSkipping(enabled: Boolean, mode: SilenceSkipMode, thresholdMs: Long) {
        primary.setSilenceSkipping(enabled, mode, thresholdMs)
        fallback.setSilenceSkipping(enabled, mode, thresholdMs)
    }

    override fun setPlaybackCompleteListener(listener: () -> Unit) {
        primary.setPlaybackCompleteListener(listener)
        fallback.setPlaybackCompleteListener(listener)
    }

    override fun setFocusLossListener(listener: () -> Unit) {
        primary.setFocusLossListener(listener)
        fallback.setFocusLossListener(listener)
    }

    override fun fadeOut(onComplete: () -> Unit): Boolean = active.fadeOut(onComplete)
    override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = active.setPreferredDevice(device)
    override fun hasAudioTrack(): Boolean = active.hasAudioTrack()
    override fun routedDevice(): AudioDeviceInfo? = active.routedDevice()
    override fun setRouteListener(listener: (AudioDeviceInfo?) -> Unit) {
        primary.setRouteListener(listener)
        fallback.setRouteListener(listener)
    }

    override fun setEq(bands: FloatArray, preamp: Float, enabled: Boolean) {
        primary.setEq(bands, preamp, enabled)
        fallback.setEq(bands, preamp, enabled)
    }

    override fun setEffects(effects: AudioEffects) {
        primary.setEffects(effects)
        fallback.setEffects(effects)
    }

    override fun release() {
        currentTrack = null
        currentUrl = null
        primary.onUnsupported = null
        primary.release()
        fallback.release()
    }
}
