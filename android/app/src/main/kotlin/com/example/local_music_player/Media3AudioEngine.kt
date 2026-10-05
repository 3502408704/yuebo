package com.example.local_music_player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Media3/ExoPlayer 音频主引擎，由 HybridAudioEngine 调度长尾格式回退。
 *
 * 目标（迁移方案改判：Media3 统一路线）：
 * 1. 本地主流格式 + 在线流由 ExoPlayer 解码（Android MediaCodec 软件/硬件编解码器）；
 * 2. 变速不变调走 [PlaybackParameters]（pitch=1，Sonic time-stretch）；
 * 3. 输出跟随系统默认路由（无单设备点选；AirPlay 发送端已随 BASS 下线）。
 *
 * 已知边界（记录在案，见 docs/handoff/2026-09-06-player-engine-libvlc-migration-plan.md §5 对照）：
 * - EQ/效果/静音跳过：采样级 DSP 随 BASS 下线，设置项仅记录（no-op）；
 * - ape/wv/dsf/dff/alac 等无系统解码器的本地格式：由 FFmpeg 兜底接管（v1 无变速/淡出）；
 * - 内嵌 LRC 歌词：返回 null。
 *
 * 事件协议（沿用 BASS 时代约定）：`isStopped()` 表达「无活动会话」；自然播完/错误后为 true，
 * 由 VM 轮询触发切歌，本引擎不主动回调 [setPlaybackCompleteListener]。
 */
internal class Media3AudioEngine(context: Context) : AudioEngine {
    /** 混合路由收到 Media3 解码器不支持事件后，仅对可由 FFmpeg 打开的媒体回退。 */
    var onUnsupported: ((String, Long) -> Unit)? = null
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 与 BASS 一致的浏览器 UA：上游按 UA 门控时（如汽水）避免拿到非音频响应。 */
    private val userAgent = "Mozilla/5.0 (Linux; Android 13) Mobile"

    /**
     * HTTP 源参数：显式超时（默认 8s/8s 太紧，弱网直链起播慢）；[headersForSession]
     * 按会话更新默认请求头，个别直播源的 Referer/UA 在 prepare 前生效。
     */
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    /**
     * 缓冲对齐视频策略（NativeVideoPlayer.AdaptiveLoadControl 的静态基线）：
     * 直播弱网下 30-60s 前向缓冲 + 30s 回退缓冲能吸收短暂抖动，不再一卡就断。
     */
    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false, // 焦点由本引擎显式管理，与 BASS 行为一致
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    appContext,
                    httpFactory,
                ),
            ),
        )
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                )
                .setBackBuffer(30_000, /* retainBackBufferFromKeyframe = */ true)
                .build(),
        )
        // 锁屏/后台时持有唤醒锁与网络锁：CPU 深睡/Wi-Fi 省电会掐断流读取（表现为锁屏几分钟直播断流）
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    // ---- 会话状态（对齐 BASS 轮询协议）----
    @Volatile private var sessionActive = false
    @Volatile private var currentCueStartMs = 0L
    @Volatile private var hintDurationMs = 0L
    @Volatile private var pendingStartLog = false
    @Volatile private var lastStartTimeMs = 0L
    @Volatile private var sessionIsLive = false
    private var liveRetryCount = 0

    private var playbackCompleteListener: (() -> Unit)? = null
    private var focusLossListener: (() -> Unit)? = null
    private var routeListener: ((AudioDeviceInfo?) -> Unit)? = null

    // ---- 设置状态（BASS 下放持久化；采样级 DSP 已下线，剩余 no-op 仅为承接 VM 调用）----
    @Volatile private var engineVolume = 1f
    @Volatile private var engineSpeed = 1.0
    @Volatile private var fadeEnabled = false
    @Volatile private var loggedSilenceSkip = false
    @Volatile private var loggedEq = false
    @Volatile private var loggedEffects = false

    // ---- 焦点（行为镜像 BASS：transient 暂停自恢复，permanent 触发 focusLossListener）----
    private var audioFocusRequest: Any? = null
    @Volatile private var resumeAfterTransientFocusLoss = false
    private var userPaused = false
    private var recoveredLiveWindow = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (sessionActive && player.playWhenReady && !userPaused) {
                    resumeAfterTransientFocusLoss = true
                    runCatching { player.pause() }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterTransientFocusLoss && !userPaused && sessionActive) {
                    resumeAfterTransientFocusLoss = false
                    runCatching { player.play() }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientFocusLoss = false
                userPaused = true
                runCatching { player.pause() }
                abandonAudioFocus()
                focusLossListener?.invoke()
            }
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        liveRetryCount = 0  // 连接成功即复位重连计数
                        if (pendingStartLog) {
                            pendingStartLog = false
                            val elapsed = SystemClock.elapsedRealtime() - lastStartTimeMs
                            Log.d("Media3Engine", "起播 READY 耗时 ${elapsed}ms duration=${player.duration}")
                            AppErrorRecorder.event("Media3", "起播就绪 durationMs=${player.duration} elapsedMs=$elapsed")
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (sessionActive) {
                            sessionActive = false
                            abandonAudioFocus()
                            Log.d("Media3Engine", "播放结束 ENDED")
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && !recoveredLiveWindow) {
                    recoveredLiveWindow = true
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // 直播断流自动重连（退避同址重连，会话保持 active 防止 VM 轮询误判切歌）：
                // 锁屏/弱网掐断直播流后不再直接哑掉；3 次仍失败才交回上层换源/报错。
                val delay = PlaybackRetryPolicy.liveReconnectDelayMs(error.errorCode, liveRetryCount)
                if (delay != null && sessionActive && sessionIsLive) {
                    liveRetryCount++
                    Log.w(TAG, "直播断流重连 第$liveRetryCount/3 次 code=${error.errorCodeName} 等 ${delay}ms")
                    AppErrorRecorder.event(TAG, "直播断流重连#$liveRetryCount code=${error.errorCodeName}")
                    mainHandler.postDelayed({
                        // 会话可能在等待期间被切歌/停止：只重连仍然存活的直播会话
                        if (sessionActive && sessionIsLive && player.playbackState != Player.STATE_IDLE) {
                            runCatching {
                                player.seekToDefaultPosition()
                                player.prepare()
                                player.playWhenReady = true
                            }
                        }
                    }, delay)
                    return
                }
                val message = "播放错误 code=${error.errorCode} type=${error.errorCodeName} msg=${error.message}"
                Log.e(TAG, message)
                AppErrorRecorder.event("Media3", message)
                val position = (player.currentPosition - currentCueStartMs).coerceAtLeast(0)
                sessionActive = false
                abandonAudioFocus()
                if (isAudioDecoderUnsupported(error.errorCode)) onUnsupported?.invoke(message, position)
            }
        })
    }

    override fun play(track: NativeTrack, positionMs: Long) {
        currentCueStartMs = track.cueStartMs.coerceAtLeast(0)
        hintDurationMs = track.durationMs.coerceAtLeast(0)
        startInternal(track.uri, positionMs + currentCueStartMs, title = track.title)
    }

    override fun playUrl(url: String, durationMs: Long, positionMs: Long,
                         headers: Map<String, String>, isLive: Boolean) {
        currentCueStartMs = 0
        hintDurationMs = durationMs.coerceAtLeast(0)
        // 默认请求头按会话整体替换：新会话无头时清空上一会话残留
        httpFactory.setDefaultRequestProperties(headers)
        android.util.Log.d("BiliDebug", "engine playUrl headers=$headers url=$url".take(180))
        startInternal(android.net.Uri.parse(url), positionMs, title = "stream", isLive = isLive)
    }

    private fun startInternal(uri: android.net.Uri, absoluteStartMs: Long, title: String,
                              isLive: Boolean = false) {
        stopInternal()
        recoveredLiveWindow = false
        sessionActive = true
        sessionIsLive = isLive
        liveRetryCount = 0
        userPaused = false
        resumeAfterTransientFocusLoss = false
        if (!requestAudioFocus()) {
            sessionActive = false
            throw IllegalStateException("未获得音频焦点，请稍后重试")
        }
        val item = MediaItem.Builder().setUri(uri).setMediaId(title)
            .setMimeType(inferVideoMimeType(uri)).build()
        player.setMediaItem(item)
        player.prepare()
        if (absoluteStartMs > 0) player.seekTo(absoluteStartMs)
        preferredAudioDevice?.let { device -> runCatching { player.setPreferredAudioDevice(device) } }
        lastStartTimeMs = SystemClock.elapsedRealtime()
        pendingStartLog = true
        player.playWhenReady = true
        Log.d("Media3Engine", "play uri=${uri.scheme ?: "?"}:/${uri.host ?: ""} startMs=$absoluteStartMs")
    }

    /** 停止当前会话但不释放播放器；新内容由 [startInternal] 重新装载。 */
    private fun stopInternal() = stop()

    internal fun stop() {
        sessionActive = false
        sessionIsLive = false  // 停止后等待中的重连定时器不再动作
        pendingStartLog = false
        resumeAfterTransientFocusLoss = false
        fadeGeneration++
        player.stop()
        player.clearMediaItems()
        player.volume = engineVolume
        abandonAudioFocus()
    }

    override fun pause(): Boolean {
        if (!sessionActive || player.playbackState == Player.STATE_IDLE) return false
        userPaused = true
        resumeAfterTransientFocusLoss = false
        abandonAudioFocus()
        runCatching { player.pause() }
        return true
    }

    override fun resume(): Boolean {
        if (!sessionActive || player.playbackState == Player.STATE_IDLE) return false
        if (!requestAudioFocus()) return false
        userPaused = false
        runCatching { player.play() }
        return true
    }

    override fun seek(positionMs: Long): Boolean {
        if (!sessionActive || player.playbackState == Player.STATE_IDLE) return false
        player.seekTo(positionMs.coerceAtLeast(0) + currentCueStartMs)
        return true
    }

    override fun positionMs(): Long {
        if (!sessionActive) return 0
        val pos = player.currentPosition
        return (pos - currentCueStartMs).coerceAtLeast(0)
    }

    override fun durationMs(): Long {
        if (!sessionActive) return 0
        val duration = player.duration
        return if (duration > 0) (duration - currentCueStartMs).coerceAtLeast(0)
        else hintDurationMs
    }

    override fun lyrics(): String? = null

    override fun isPlaying(): Boolean = sessionActive && player.isPlaying

    override fun isStopped(): Boolean = !sessionActive || player.playbackState == Player.STATE_ENDED

    override fun setVolume(value: Float): Boolean {
        engineVolume = value.coerceIn(0f, 1f)
        player.volume = engineVolume
        return true
    }

    override fun setSpeed(value: Double): Boolean {
        engineSpeed = value.coerceIn(0.5, 2.0)
        player.playbackParameters = PlaybackParameters(engineSpeed.toFloat(), 1f)
        return true
    }

    override fun setFadeEnabled(enabled: Boolean) {
        fadeEnabled = enabled
        if (!enabled) {
            fadeGeneration++
            player.volume = engineVolume
        }
    }

    override fun setSilenceSkipping(enabled: Boolean, mode: SilenceSkipMode, thresholdMs: Long) {
        if (enabled && !loggedSilenceSkip) {
            loggedSilenceSkip = true
            AppErrorRecorder.event("Media3", "静音跳过已下线（采样级能力随 BASS 移除，设置仅记录）")
        }
    }

    override fun setPlaybackCompleteListener(listener: () -> Unit) {
        playbackCompleteListener = listener
    }

    override fun setFocusLossListener(listener: () -> Unit) {
        focusLossListener = listener
    }

    private var fadeGeneration = 0L

    override fun fadeOut(onComplete: () -> Unit): Boolean {
        if (!fadeEnabled || !isPlaying()) return false
        val generation = ++fadeGeneration
        val startTime = SystemClock.uptimeMillis()
        val startVolume = player.volume
        fun step() {
            if (generation != fadeGeneration) return
            val progress = ((SystemClock.uptimeMillis() - startTime).toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
            runCatching { player.volume = startVolume * (1f - progress) }
            if (progress >= 1f) {
                runCatching { player.pause() }
                onComplete()
            } else {
                mainHandler.postDelayed(::step, FADE_STEP_MS)
            }
        }
        mainHandler.post(::step)
        return true
    }

    /** 用户定向的输出设备；每次新会话装载后重申一次（系统可能在会话间重置路由）。 */
    private var preferredAudioDevice: android.media.AudioDeviceInfo? = null

    override fun setPreferredDevice(device: android.media.AudioDeviceInfo?): Boolean {
        preferredAudioDevice = device
        // ExoPlayer 支持输出定向：setPreferredAudioDevice 对当前与后续音频会话生效；
        // null = 交还系统默认路由。个别设备/机型可能忽略（UI 已注明「以系统实际输出为准」）。
        return runCatching {
            player.setPreferredAudioDevice(device)
            if (device != null) AppErrorRecorder.event("Media3", "输出设备定向已下发 type=${device.type}")
            true
        }.getOrDefault(false)
    }

    override fun hasAudioTrack(): Boolean = sessionActive && player.playbackState != Player.STATE_IDLE

    override fun routedDevice(): AudioDeviceInfo? = null

    override fun setRouteListener(listener: (AudioDeviceInfo?) -> Unit) {
        routeListener = listener
        // 无法感知 ExoPlayer 内部 AudioTrack 的路由变化，仅在注册时回调一次 null。
        listener(null)
    }

    override fun setEq(bands: FloatArray, preamp: Float, enabled: Boolean) {
        if (enabled && !loggedEq) {
            loggedEq = true
            AppErrorRecorder.event("Media3", "均衡器已下线（采样级 DSP 随 BASS 移除）")
        }
    }

    override fun setEffects(effects: AudioEffects) {
        if (effects != AudioEffects() && !loggedEffects) {
            loggedEffects = true
            AppErrorRecorder.event("Media3", "混响/合唱等效果已下线（采样级 DSP 随 BASS 移除）")
        }
    }

    override fun release() {
        fadeGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        sessionActive = false
        runCatching { player.release() }
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = (audioFocusRequest as? AudioFocusRequest) ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!granted) Log.w("Media3Engine", "音频焦点请求未获准")
        return granted
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest
        if (request != null) {
            audioFocusRequest = null
            runCatching { audioManager.abandonAudioFocusRequest(request as AudioFocusRequest) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
    }

    companion object {
        private const val TAG = "Media3Engine"
        private const val FADE_DURATION_MS = 450L
        private const val FADE_STEP_MS = 30L
    }
}

internal fun isAudioDecoderUnsupported(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    -> true
    else -> false
}
