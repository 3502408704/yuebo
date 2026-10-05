package com.example.local_music_player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.io.File

/**
 * 视频播放引擎抽象：主引擎为 ExoPlayer（MediaCodec 系统解码），
 * 旧格式（WMV/RMVB/MPG 等）由 [NativeVideoPlayer.fallbackEngine]（FFmpeg，第 2 步接入）兜底。
 */
interface VideoEngine {
    /** 播放到结尾时回调。 */
    var onComplete: (() -> Unit)?

    /** 解码/IO 失败时回调，message 为简体中文提示。 */
    var onError: ((String) -> Unit)?

    /**
     * 直播快速换源：true 时缓冲停滞超时不做同址重连，直接 [onError] 交给上层
     * 切下一个信号源（多源直播里对死源重连是白等）；点播保持 false 走重连恢复。
     */
    var failFastStall: Boolean

    /** 引擎无法解码/解析当前格式时回调（用于自动回退 FFmpeg 兜底）。 */
    var onUnsupported: ((String) -> Unit)?

    /** 播放状态（开始/暂停/缓冲/结束）变化时回调，用于驱动 UI 状态。 */
    var onStateChanged: (() -> Unit)?

    fun play(uri: Uri, positionMs: Long)

    fun pause()

    fun resume()

    fun toggle()

    fun seekTo(ms: Long)

    fun positionMs(): Long

    fun durationMs(): Long

    fun isPlaying(): Boolean

    /** 正在缓冲（网络流供给跟不上码率）；UI 应显示「正在缓冲」而非「已暂停」。 */
    fun isBuffering(): Boolean = false

    fun setVideoEnabled(enabled: Boolean) {}

    fun setVolume(value: Float)

    /**
     * 设置倍速（0.5–2.0）。引擎不支持时静默忽略（如 FFmpeg 兜底引擎无变速能力）；
     * 支持的引擎应记忆该值并在每次 [play] 后重放，保证跨集/重开保持倍速。
     */
    fun setSpeed(value: Double) {}

    /** 停止并清空媒体项，用于关闭播放器；不会释放单例播放器本身。 */
    fun stopAndClear()

    fun release()
}

/** 视频可用清晰度档位（最终版仅保留数据形状；服务端多码率清单移除后通常为空，界面显示「暂无切换的其他码率」）。 */
data class VideoQualityOption(val code: String, val label: String)

/**
 * ExoPlayer 视频引擎：系统解码优先，HLS/DASH 清单直连、媒体分片按现有策略缓存。
 * 网络流（百度网盘代理地址）与本地 content:// URI 均通过 [play] 统一处理。
 */
internal class ExoPlayerVideoEngine(context: Context) : VideoEngine {
    /** 网络流请求头可动态设置（夸克视频直连需 Cookie/Referer/UA）。
     * 超时取直链/CDN 友好值（10s/15s）：直链抽风或死链时能更快暴露错误触发换源重试，
     * 而不是让用户对着 20–30s 的静止缓冲干等。 */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    companion object {
        /** 影视 HLS 分段磁盘缓存上限；LRU 淘汰，超容自动清最旧分段。 */
        private const val VIDEO_CACHE_BYTES = 512L * 1024 * 1024

        /** 进程级单例：SimpleCache 同一目录全局只允许一个实例。 */
        @Volatile
        private var sharedCache: SimpleCache? = null

        private fun videoCache(context: Context): SimpleCache {
            sharedCache?.let { return it }
            synchronized(this) {
                sharedCache?.let { return it }
                return SimpleCache(
                    File(context.cacheDir, "video_hls"),
                    LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_BYTES),
                    StandaloneDatabaseProvider(context),
                ).also { sharedCache = it }
            }
        }
    }

    /** 带宽估计器：LoadControl 与自适应选档共用；随实测吞吐滑动更新（每秒数据量）。 */
    private val bandwidthMeter: DefaultBandwidthMeter = DefaultBandwidthMeter.Builder(context)
        .setInitialBitrateEstimate(2_500_000L)
        .build()

    // 缓冲策略：影视直链 CDN 慢且不稳定。基础前向缓冲 30–90 秒，并叠加 [AdaptiveLoadControl]——
    // 它按近端实测吞吐（每秒数据量）动态放大/收缩前向缓冲，保证缓冲余量始终覆盖「按当前网速
    // 解码所消耗的时间」，慢速或不稳的 CDN 下不会边播边停。
    // 起播门槛 1.0 秒保证秒开（清单/探测耗时都在这之外，起播水位是少数能再砍的固定项）；
    // 重缓冲恢复门槛 5 秒（ExoPlayer 默认）——恢复太快会在带宽勉强跟上码率时立刻再饿，
    // 造成 BUFFERING↔READY 振荡（画面反复空白、状态乱跳），有意不动。
    private val loadControl: LoadControl = AdaptiveLoadControl(
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 1_000, 5_000)
            .setTargetBufferBytes(12 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(30_000, true)
            .build(),
        bandwidthMeter,
    )

    /** 倍速记忆：setMediaItem/prepare 不重放播放参数，但每次起播显式重放一次以保证跨集一致。 */
    private var speed = 1.0
    private var videoEnabled = true
    private var recoveredLiveWindow = false
    private var stallRecoveryUsed = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val directDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
    private val mediaDataSourceFactory = DefaultDataSource.Factory(
        context,
        CacheableHttpDataSource(
            cachedFactory = CacheDataSource.Factory()
                .setCache(videoCache(context))
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            directFactory = httpDataSourceFactory,
        ),
    )
    private val mediaSourceFactory = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(mediaDataSourceFactory)
    private val hlsSourceFactory = HlsMediaSource.Factory(HlsDataSourceFactory { dataType ->
        // 清单/密钥可能没有后缀，必须按请求类型分流，防止缓存冻结直播窗口或保留旧密钥。
        (if (cacheHlsDataType(dataType)) mediaDataSourceFactory else directDataSourceFactory).createDataSource()
    })
    private val dashSourceFactory = DashMediaSource.Factory(
        DefaultDashChunkSource.Factory(mediaDataSourceFactory), directDataSourceFactory,
    )

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setLoadControl(loadControl)
        .setBandwidthMeter(bandwidthMeter)
        .setMediaSourceFactory(mediaSourceFactory)
        // 锁屏/后台播放（画中画/后台听剧）时持有唤醒锁与网络锁，防 CPU 深睡/省电掐断流读取
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
        }

    override var onComplete: (() -> Unit)? = null

    override var onError: ((String) -> Unit)? = null

    override var failFastStall: Boolean = false

    override var onUnsupported: ((String) -> Unit)? = null

    override var onStateChanged: (() -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d("QuarkPan", "video playback state: ${playbackStateName(playbackState)}")
                if (playbackState == Player.STATE_READY) stallRecoveryUsed = false
                scheduleStallWatchdog(playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED) onComplete?.invoke()
                onStateChanged?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && !recoveredLiveWindow) {
                    recoveredLiveWindow = true
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                Log.e("QuarkPan", "video player error: ${error.errorCodeName}", error)
                val message = videoErrorMessage(error)
                // 容器/解码不支持 → 只走 onUnsupported（VM 切 FFmpeg 用已解析直链重放）；
                // 不再同时触发 onError 的换源重试，避免 ExoPlayer 重试与 FFmpeg 兜底双轨竞态。
                val media = player.currentMediaItem?.localConfiguration
                if (shouldFallbackToFfmpeg(error.errorCode) && media != null &&
                    media.mimeType !in setOf(MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD, MimeTypes.APPLICATION_RTSP) &&
                    canOpenWithFfmpeg(media.uri)) {
                    onUnsupported?.invoke(message)
                } else {
                    onError?.invoke(message)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d("QuarkPan", "video isPlaying: $isPlaying")
                onStateChanged?.invoke()
            }
        })
    }

    private val watchdogToken = Any()

    /**
     * 停滞看门狗：卡在 BUFFERING 超过 [PlaybackRetryPolicy.STALL_TIMEOUT_MS] 视为断流，
     * 同址重连一次（seek+prepare）；重连后仍停滞（再等一个周期）则上报 onError 走 VM 换源链，
     * 不做无限缓冲——上游 CDN 慢/不可达时用户必须「等得到错误」而不是永远转圈。
     */
    private fun scheduleStallWatchdog(buffering: Boolean) {
        mainHandler.removeCallbacksAndMessages(watchdogToken)
        if (!buffering) return
        val timeoutMs = if (failFastStall) PlaybackRetryPolicy.LIVE_STALL_TIMEOUT_MS else PlaybackRetryPolicy.STALL_TIMEOUT_MS
        mainHandler.postAtTime({
            if (player.playbackState != Player.STATE_BUFFERING) return@postAtTime
            if (failFastStall) {
                // 直播多源：死源重连是白等，直接上报换链切下一个信号源。
                Log.w("QuarkPan", "直播缓冲停滞 ${timeoutMs}ms，快速换源")
                onError?.invoke("直播信号源缓冲停滞，正在切换信号源")
                return@postAtTime
            }
            if (!stallRecoveryUsed) {
                stallRecoveryUsed = true
                Log.w("QuarkPan", "视频缓冲停滞 ${timeoutMs}ms，同址重连一次")
                runCatching {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                }
                // 重连后再次武装：仍停滞则进入上报分支。
                scheduleStallWatchdog(true)
            } else {
                Log.w("QuarkPan", "视频缓冲停滞重连无效，上报换源")
                onError?.invoke("视频缓冲停滞，正在尝试其他信号源")
            }
        }, watchdogToken, android.os.SystemClock.uptimeMillis() + timeoutMs)
    }

    override fun play(uri: Uri, positionMs: Long) {
        play(uri, positionMs, emptyMap())
    }

    fun play(uri: Uri, positionMs: Long, requestHeaders: Map<String, String>, mimeType: String? = null) {
        recoveredLiveWindow = false
        stallRecoveryUsed = false
        httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)
        // 播放列表直链常无扩展名（查询串/路径），显式 MIME 才能选到对应 MediaSource。
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType?.let(::normalizeVideoMimeType) ?: inferVideoMimeType(uri))
            .build()
        val source = when (mediaItem.localConfiguration?.mimeType) {
            MimeTypes.APPLICATION_M3U8 -> hlsSourceFactory.createMediaSource(mediaItem)
            MimeTypes.APPLICATION_MPD -> dashSourceFactory.createMediaSource(mediaItem)
            else -> mediaSourceFactory.createMediaSource(mediaItem)
        }
        player.setMediaSource(source)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled)
            .build()
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.setPlaybackSpeed(speed.toFloat())
        player.play()
    }

    override fun pause() {
        if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        }
    }

    override fun resume() {
        // ENDED 状态下 play() 不会自动重播，必须先回到起点，否则状态与实际播放不同步。
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }
        player.play()
    }

    override fun toggle() {
        if (player.isPlaying) pause() else resume()
    }

    override fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    override fun positionMs(): Long = player.currentPosition.coerceAtLeast(0)

    override fun durationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0

    override fun isPlaying(): Boolean = player.isPlaying

    override fun isBuffering(): Boolean = player.playbackState == Player.STATE_BUFFERING

    override fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    override fun setVolume(value: Float) {
        player.volume = value.coerceIn(0f, 1f)
    }

    override fun setSpeed(value: Double) {
        speed = value.coerceIn(0.5, 2.0)
        player.setPlaybackSpeed(speed.toFloat())
    }

    override fun stopAndClear() {
        player.stop()
        player.clearMediaItems()
    }

    override fun release() {
        player.release()
    }
}

/**
 * 自适应缓冲控制：在 [DefaultLoadControl] 的固定前向缓冲区间（30–90 秒）内，按**每秒实测数据量**
 * （[BandwidthMeter] 的吞吐估计）与当前视频码率的比值动态调整：
 *
 * - 吞吐相对码率余量充裕（≥4 倍）时收缩到 30 秒——快进落点更快开始下载、内存占用更低；
 * - 余量紧张（≤1.2 倍，即网速刚够或不够解码）时放大到 90 秒——用更厚的缓冲吸收 CDN 抖动，
 *   避免边播边饿导致反复缓冲。
 *
 * 起播/重缓冲门槛、字节水位与磁盘缓存仍沿用 [delegate] 的既有策略（未改动），
 * 仅在两者之间按动态上/下界扩展或收缩继续加载的区间。
 */
internal class AdaptiveLoadControl(
    private val delegate: LoadControl,
    private val bandwidthMeter: DefaultBandwidthMeter,
) : LoadControl {
    // 这里**不能**用 `LoadControl by delegate`：LoadControl 的多数方法带 Java 默认实现，
    // 而其中废弃的无参重载默认体是 `throw IllegalStateException("… not implemented")`，
    // Kotlin 委托只为抽象方法生成转发、会把这些默认体原样留下。ExoPlayer 内部调用的正是
    // `xxx(PlayerId)` 重载，它默认转发到无参重载 → 启动构建播放器时直接崩溃
    // （v20260915 的闪退根因）。因此像 DefaultLoadControl 一样显式实现 ExoPlayer 实际调用的
    // 每个 PlayerId 重载，其余废弃重载无人调用、保持默认即可。
    @Volatile private var selectedVideoBitrateBps: Long = 0L

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, selections: Array<out ExoTrackSelection?>) {
        delegate.onTracksSelected(parameters, trackGroups, selections)
        for (selection in selections) {
            // 视频轨以高度区分（音频/字幕 height 为 0，不会误取码率）。
            val format = selection?.selectedFormat ?: continue
            if (format.height <= 0) continue  // 非视频轨
            val bitrate = when {
                format.bitrate != Format.NO_VALUE && format.bitrate > 0 -> format.bitrate
                format.averageBitrate != Format.NO_VALUE && format.averageBitrate > 0 -> format.averageBitrate
                else -> 0
            }
            if (bitrate > 0) selectedVideoBitrateBps = bitrate.toLong()
        }
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val (minUs, maxUs) = dynamicBufferRangeUs()
        return when {
            parameters.bufferedDurationUs < minUs -> true
            parameters.bufferedDurationUs >= maxUs -> false
            // 区间内沿用默认策略（含字节水位与滞回），避免与既有起播/暂停行为分叉。
            else -> delegate.shouldContinueLoading(parameters)
        }
    }

    /** 依据吞吐/码率比值给出动态前向缓冲区间（微秒）。 */
    private fun dynamicBufferRangeUs(): Pair<Long, Long> {
        val (minSeconds, maxSeconds) = adaptiveBufferRangeSeconds(bandwidthMeter.bitrateEstimate, selectedVideoBitrateBps)
        return (minSeconds * 1_000_000).toLong() to (maxSeconds * 1_000_000).toLong()
    }
}

internal const val ADAPTIVE_MIN_THROUGHPUT_BPS = 150_000L
internal const val ADAPTIVE_FALLBACK_BITRATE_BPS = 1_500_000L

/**
 * 缓冲区间映射（纯函数，便于单测）：吞吐/码率余量越紧，前向缓冲越大。
 * margin=1（网速刚好够）→ 60~90 秒；margin≥4（4 倍余量）→ 15~30 秒；其间线性过渡。
 */
internal fun adaptiveBufferRangeSeconds(throughputBps: Long, bitrateBps: Long): Pair<Double, Double> {
    val throughput = throughputBps.coerceAtLeast(ADAPTIVE_MIN_THROUGHPUT_BPS)
    val bitrate = bitrateBps.takeIf { it > 0 } ?: ADAPTIVE_FALLBACK_BITRATE_BPS
    val margin = (throughput.toDouble() / bitrate).coerceIn(1.0, 4.0)
    val maxSeconds = 30.0 + (4.0 - margin) / 3.0 * 60.0
    val minSeconds = (maxSeconds * 0.5).coerceIn(15.0, 45.0)
    return minSeconds to maxSeconds
}

/**
 * 视频播放门面：默认走 [ExoPlayerVideoEngine]；[fallbackEngine] 为 FFmpeg 兜底插槽，
 * 接入后按格式/失败自动路由。对外方法与回调行为保持与旧版一致。
 */
class NativeVideoPlayer(context: Context) {
    private val exoEngine = ExoPlayerVideoEngine(context)
    private var videoEnabled = true

    /** 旧格式 FFmpeg 兜底引擎插槽；未接入时为 null（只走 ExoPlayer）。 */
    var fallbackEngine: VideoEngine? = null

    private val engine: VideoEngine
        get() = fallbackEngine ?: exoEngine

    /** 设置/清除 FFmpeg 兜底引擎，并把对外回调重新绑定到当前生效引擎。 */
    fun bindFallbackEngine(engine: VideoEngine?) {
        val previous = this.engine
        val target = engine ?: exoEngine
        if (previous !== target) {
            previous.onComplete = null
            previous.onError = null
            previous.onUnsupported = null
            previous.onStateChanged = null
            previous.stopAndClear()
        }
        fallbackEngine = engine
        target.onComplete = onComplete
        target.onError = onError
        target.onUnsupported = onUnsupported
        target.onStateChanged = onStateChanged
        target.setVideoEnabled(videoEnabled)
    }

    /** 兼容现有 UI：`VideoPlayerScreen` 直接取 ExoPlayer 渲染 Surface。 */
    val player: ExoPlayer
        get() = exoEngine.player

    var onComplete: (() -> Unit)? = null
        set(value) {
            field = value
            engine.onComplete = value
        }

    var onError: ((String) -> Unit)? = null
        set(value) {
            field = value
            engine.onError = value
        }

    /** 直播快速换源：见 [VideoEngine.failFastStall]。 */
    var failFastStall: Boolean
        set(value) {
            engine.failFastStall = value
        }
        get() = engine.failFastStall

    var onUnsupported: ((String) -> Unit)? = null
        set(value) {
            field = value
            engine.onUnsupported = value
        }

    var onStateChanged: (() -> Unit)? = null
        set(value) {
            field = value
            engine.onStateChanged = value
        }

    fun play(uri: Uri, positionMs: Long = 0) {
        val fb = fallbackEngine
        if (fb != null) {
            fb.play(uri, positionMs)
            return
        }
        engine.play(uri, positionMs)
    }

    fun playUrl(url: String, positionMs: Long = 0, requestHeaders: Map<String, String> = emptyMap(), mimeType: String? = null) {
        val target = engine as? ExoPlayerVideoEngine
        if (target != null) {
            target.play(Uri.parse(url), positionMs, requestHeaders, mimeType)
        } else {
            play(Uri.parse(url), positionMs)
        }
    }

    fun pause() = engine.pause()

    fun resume() = engine.resume()

    fun toggle() = engine.toggle()

    fun seekTo(ms: Long) = engine.seekTo(ms)

    fun positionMs(): Long = engine.positionMs()

    fun durationMs(): Long = engine.durationMs()

    fun isPlaying(): Boolean = engine.isPlaying()

    fun isBuffering(): Boolean = engine.isBuffering()

    fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        engine.setVideoEnabled(enabled)
    }

    fun setVolume(value: Float) = engine.setVolume(value)

    /** 倍速透传当前引擎；FFmpeg 兜底引擎不支持时由接口默认实现忽略。 */
    fun setSpeed(value: Double) = engine.setSpeed(value)

    fun stopAndClear() = engine.stopAndClear()

    fun release() {
        fallbackEngine?.release()
        exoEngine.release()
    }
}

/** 服务端 MIME → Media3 内容类型：播放列表必须显式标注，其余交回原值让 ExoPlayer 自行推断。 */
internal fun normalizeVideoMimeType(value: String): String? {
    val normalized = value.substringBefore(';').trim().lowercase()
    return when (normalized) {
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl", "m3u8", "m3u", "hls" -> MimeTypes.APPLICATION_M3U8
        "application/dash+xml", "application/mpd", "mpd" -> MimeTypes.APPLICATION_MPD
        "application/rtsp", "rtsp" -> MimeTypes.APPLICATION_RTSP
        "", "application/octet-stream", "video/*", "audio/*" -> null
        else -> normalized
    }
}

internal fun inferVideoMimeType(uri: Uri): String? {
    return inferStreamMimeType(uri.scheme, uri.path)
}

internal fun inferStreamMimeType(scheme: String?, rawPath: String?): String? {
    if (scheme.equals("rtsp", ignoreCase = true)) return MimeTypes.APPLICATION_RTSP
    val path = rawPath.orEmpty().lowercase()
    return when {
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}

internal fun cacheHlsDataType(dataType: Int): Boolean =
    dataType == C.DATA_TYPE_MEDIA || dataType == C.DATA_TYPE_MEDIA_INITIALIZATION

/** http(s) 视频数据源工厂：每次创建带分流路由的数据源（HLS 并发加载需要多个独立实例）。 */
private class CacheableHttpDataSource(
    private val cachedFactory: DataSource.Factory,
    private val directFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        RoutingDataSource(cachedFactory.createDataSource(), directFactory.createDataSource())
}

/** open(DataSpec) 时才知道目标地址，据此在缓存/直连两个子数据源间二选一并透传读取。 */
private class RoutingDataSource(
    private val cached: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.shouldCacheVideo()) cached else direct
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.getResponseHeaders() ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}

/** 仅公网 http(s) 进磁盘缓存：网盘本地代理（回环/内网地址）写缓存纯属磁盘损耗，还会挤掉 HLS 分段。 */
private fun Uri.shouldCacheVideo(): Boolean {
    if (scheme.equals("rtsp", ignoreCase = true)) return false
    if (scheme !in setOf("http", "https")) return false
    val host = host?.lowercase() ?: return false
    if (host == "localhost" || host == "::1" || host.startsWith("127.")) return false
    if (host.endsWith(".local") || host.startsWith("192.168.") || host.startsWith("10.")) return false
    if (host.startsWith("172.")) {
        val second = host.substringAfter("172.").substringBefore('.').toIntOrNull()
        if (second != null && second in 16..31) return false
    }
    val path = path.orEmpty().lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")) return false
    return true
}

private fun videoErrorMessage(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    -> "无法播放该视频格式，请尝试 MP4、WebM、MKV 等常见格式。"

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "网络连接失败，请检查网络后重试。"

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    -> "视频文件无法读取或已失效。"

    else -> "视频播放失败（${error.errorCodeName}）。"
}

private fun playbackStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "STATE_$state"
}

/** 需要 FFmpeg 兜底的旧式视频格式判定（ExoPlayer/系统解码器不支持）。 */
internal fun isFfmpegFallbackFormat(title: String): Boolean {
    val extension = title.substringAfterLast('.', "").lowercase()
    return extension in setOf("wmv", "asf", "rmvb", "rm", "rmm", "mpg", "mpeg", "vob")
}

/** 该错误码是否意味着 ExoPlayer 无法解码/解析当前格式（应自动回退 FFmpeg 兜底）。 */
internal fun shouldFallbackToFfmpeg(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
    -> true
    else -> false
}
