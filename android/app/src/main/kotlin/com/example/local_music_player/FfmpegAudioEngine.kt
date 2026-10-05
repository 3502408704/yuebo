package com.example.local_music_player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri

/** FFmpeg 音频长尾格式适配器；普通音频和在线自适应流仍由 Media3 处理。 */
internal class FfmpegAudioEngine(context: Context) : AudioEngine {
    private val delegate = FfmpegFallbackPlayer(context, requireVideoTrack = false)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active = false
    private var cueStartMs = 0L
    private var hintedDurationMs = 0L
    private var volume = 1f
    private var userPaused = false
    private var resumeAfterTransientLoss = false
    private var focusRequest: AudioFocusRequest? = null
    private var fadeGeneration = 0L
    private var fadeEnabled = false
    private var cueTrack = false
    private var focusLossListener: (() -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (active && !userPaused) {
                    resumeAfterTransientLoss = true
                    delegate.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterTransientLoss && !userPaused && active) {
                    resumeAfterTransientLoss = false
                    delegate.resume()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientLoss = false
                userPaused = true
                delegate.pause()
                abandonAudioFocus()
                focusLossListener?.invoke()
            }
        }
    }

    init {
        delegate.onComplete = {
            active = false
            abandonAudioFocus()
        }
        delegate.onError = { message ->
            AppErrorRecorder.event("FFmpegAudio", message)
            active = false
            abandonAudioFocus()
        }
    }

    override fun play(track: NativeTrack, positionMs: Long) {
        cueTrack = track.isCueTrack
        cueStartMs = track.cueStartMs.coerceAtLeast(0)
        hintedDurationMs = track.durationMs.coerceAtLeast(0)
        start(track.uri, positionMs + cueStartMs)
    }

    override fun playUrl(url: String, durationMs: Long, positionMs: Long,
                         headers: Map<String, String>, isLive: Boolean) {
        // FFmpeg JNI 通道暂不支持自定义请求头（本地/直链长尾格式场景，无需 Referer/UA）
        cueTrack = false
        cueStartMs = 0
        hintedDurationMs = durationMs.coerceAtLeast(0)
        start(Uri.parse(url), positionMs)
    }

    private fun start(uri: Uri, positionMs: Long) {
        stopInternal()
        active = true
        userPaused = false
        resumeAfterTransientLoss = false
        if (!requestAudioFocus()) {
            active = false
            throw IllegalStateException("未获得音频焦点，请稍后重试")
        }
        delegate.play(uri, positionMs)
        delegate.setVolume(volume)
    }

    override fun pause(): Boolean {
        if (!active) return false
        fadeGeneration++
        delegate.setVolume(volume)
        userPaused = true
        resumeAfterTransientLoss = false
        delegate.pause()
        abandonAudioFocus()
        return true
    }

    override fun resume(): Boolean {
        if (!active) return false
        if (!requestAudioFocus()) return false
        userPaused = false
        fadeGeneration++
        delegate.setVolume(volume)
        delegate.resume()
        return true
    }

    override fun seek(positionMs: Long): Boolean {
        if (!active) return false
        delegate.seekTo(positionMs.coerceAtLeast(0) + cueStartMs)
        return true
    }

    override fun positionMs(): Long {
        if (!active) return 0
        return (delegate.positionMs() - cueStartMs).coerceAtLeast(0)
    }

    override fun durationMs(): Long {
        if (!active) return 0
        if (cueTrack && hintedDurationMs > 0) return hintedDurationMs
        val duration = delegate.durationMs()
        return if (duration > 0) (duration - cueStartMs).coerceAtLeast(0) else hintedDurationMs
    }

    override fun lyrics(): String? = null
    override fun isPlaying(): Boolean = active && delegate.isPlaying()
    override fun isStopped(): Boolean {
        if (active && cueTrack && hintedDurationMs > 0 && positionMs() >= hintedDurationMs) stop()
        return !active
    }

    override fun setVolume(value: Float): Boolean {
        volume = value.coerceIn(0f, 1f)
        delegate.setVolume(volume)
        return true
    }

    override fun setSpeed(value: Double): Boolean = false
    override fun setFadeEnabled(enabled: Boolean) {
        fadeEnabled = enabled
        if (!enabled) {
            fadeGeneration++
            delegate.setVolume(volume)
        }
    }
    override fun setSilenceSkipping(enabled: Boolean, mode: SilenceSkipMode, thresholdMs: Long) {}
    override fun setPlaybackCompleteListener(listener: () -> Unit) {}
    override fun setFocusLossListener(listener: () -> Unit) { focusLossListener = listener }

    override fun fadeOut(onComplete: () -> Unit): Boolean {
        if (!fadeEnabled || !isPlaying()) return false
        val generation = ++fadeGeneration
        val startedAt = SystemClock.uptimeMillis()
        fun step() {
            if (generation != fadeGeneration) return
            val progress = ((SystemClock.uptimeMillis() - startedAt).toFloat() / 450f).coerceIn(0f, 1f)
            delegate.setVolume(volume * (1f - progress))
            if (progress >= 1f) {
                delegate.pause()
                onComplete()
            } else {
                mainHandler.postDelayed(::step, 30L)
            }
        }
        mainHandler.post(::step)
        return true
    }

    override fun setPreferredDevice(device: android.media.AudioDeviceInfo?): Boolean = false
    override fun hasAudioTrack(): Boolean = active
    override fun routedDevice(): android.media.AudioDeviceInfo? = null
    override fun setRouteListener(listener: (android.media.AudioDeviceInfo?) -> Unit) {
        listener(null)
    }
    override fun setEq(bands: FloatArray, preamp: Float, enabled: Boolean) {}
    override fun setEffects(effects: AudioEffects) {}

    override fun release() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        delegate.release()
        abandonAudioFocus()
    }

    private fun stopInternal() = stop()

    internal fun stop() {
        active = false
        resumeAfterTransientLoss = false
        fadeGeneration++
        delegate.stopAndClear()
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return result
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
    }
}
