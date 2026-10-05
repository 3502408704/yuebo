package com.example.local_music_player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chromecast 投送（2026-09-28 第一梯队 2，音频会话优先）。
 *
 * - [YueboCastOptionsProvider]：cast-framework 装配入口（manifest meta-data 指向）；
 * - [ChromecastDiscovery]：经 MediaRouter 的 Cast 路由做设备枚举与选择；
 *   设备无 Google Play 服务时 available()=false，功能整体隐藏，零回归风险；
 * - [ChromecastRemote]：[CastPlayer] 包装成 [CastRemote]——VM 投送链路不感知协议差异。
 *   解码/转码在接收端完成，Sink 探测保持未知（接口默认 null），与本机转码决策天然互斥。
 */

/** cast-framework 装配入口：AndroidManifest 的 OPTIONS_PROVIDER_CLASS_NAME 指向本类。 */
class YueboCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Chromecast 设备发现（MediaRouter Cast 路由）与选路。 */
internal class ChromecastDiscovery(context: Context) {
    private val router: MediaRouter? = runCatching { MediaRouter.getInstance(context) }.getOrNull()
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()

    fun available(): Boolean = router != null

    /** 当前可见的 Cast 路由名（非默认路由、已启用）。无 GMS 时恒为空。 */
    fun routes(): List<String> = runCatching {
        router?.routes
            ?.filter { it.isEnabled && !it.isDefault && !it.description.isNullOrBlank() && it.matchesSelector(selector) }
            ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun selectRouteByName(name: String): Boolean = runCatching {
        val route = router?.routes
            ?.firstOrNull { it.name == name && it.isEnabled && it.matchesSelector(selector) }
            ?: return false
        route.select()
        true
    }.getOrDefault(false)
}

/** [CastPlayer] 包装成 [CastRemote]：加载/控制/音量/状态映射，全部操作切主线程。 */
internal class ChromecastRemote private constructor(
    override val deviceName: String,
    private val castContext: CastContext,
    private val player: CastPlayer,
) : CastRemote {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playerListener: Player.Listener? = null

    companion object {
        /** 选路并等待 Cast 会话建立；无 GMS/选路失败/超时返回 null。 */
        suspend fun connect(context: Context, routeName: String, timeoutMs: Long = 10_000): ChromecastRemote? =
            withContext(Dispatchers.Main) {
                val castContext = runCatching { CastContext.getSharedInstance(context) }.getOrNull()
                    ?: return@withContext null
                val router = runCatching { MediaRouter.getInstance(context) }.getOrNull()
                    ?: return@withContext null
                val discovery = ChromecastDiscovery(context)
                if (!discovery.selectRouteByName(routeName)) return@withContext null
                val player = CastPlayer(castContext)
                val connected = withTimeoutOrNull(timeoutMs) {
                    while (!player.isCastSessionAvailable) delay(200)
                    true
                } ?: false
                if (!connected) {
                    runCatching { player.release() }
                    return@withContext null
                }
                ChromecastRemote(routeName, castContext, player)
            }
    }

    override suspend fun load(url: String, track: NativeTrack, isVideo: Boolean) = onMain {
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(track.mimeType.ifBlank { null })
                .build(),
        )
        player.prepare()
        player.playWhenReady = true
    }

    override suspend fun play(speed: Double) = onMain {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(speed.coerceIn(0.5, 2.0).toFloat())
        player.play()
    }

    override suspend fun pause() = onMain { player.pause() }
    override suspend fun stop() = onMain { player.stop() }

    override suspend fun seekWhenReady(positionMs: Long) {
        var lastError: Exception? = null
        repeat(12) {
            delay(500)
            try {
                if (seek(positionMs)) return
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("设备未就绪，无法断点续播")
    }

    override suspend fun seek(positionMs: Long): Boolean = onMain {
        player.seekTo(positionMs.coerceAtLeast(0))
        true
    }

    override suspend fun positionMs(): Long = onMain { player.currentPosition }

    override suspend fun isStopped(): Boolean = onMain {
        !player.isCastSessionAvailable ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
    }

    override suspend fun setVolume(value: Float): Boolean = onMain {
        // CastPlayer.setVolume 映射为接收端设备音量；不支持时抛错由 runCatching 兜底
        runCatching {
            player.volume = value.coerceIn(0f, 1f)
            true
        }.getOrDefault(false)
    }

    override suspend fun volume(): Float? = onMain {
        runCatching { player.volume }.getOrNull()
    }

    override suspend fun preloadNext(url: String, track: NativeTrack): Boolean = false

    override suspend fun attachEvents(onState: (String) -> Unit): Boolean = onMain {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onState(if (isPlaying) "PLAYING" else "PAUSED_PLAYBACK")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    onState("STOPPED")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onState("STOPPED")
            }
        }
        player.addListener(listener)
        playerListener = listener
        true
    }

    override fun dispose() {
        val listener = playerListener
        playerListener = null
        // CastPlayer 在主线程：会话由系统随路由断开收尾，这里仅移除监听并释放播放器
        mainHandler.post {
            listener?.let { runCatching { player.removeListener(it) } }
            runCatching { player.release() }
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
}
