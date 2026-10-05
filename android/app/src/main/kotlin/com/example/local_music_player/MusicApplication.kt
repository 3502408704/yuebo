package com.example.local_music_player

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class MusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppErrorRecorder.initialize(this)
        val parent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            AppErrorRecorder.record("线程-${thread.name}", ex)
            parent?.uncaughtException(thread, ex)
        }
    }

    /** 本地音频引擎：Media3/ExoPlayer 唯一内核（BASS 已整体下线；冷门格式由引擎内 FFmpeg 兜底）。 */
    val player: AudioEngine
        get() = audioEngine ?: synchronized(engineLock) {
            audioEngine ?: createAudioEngine().also { audioEngine = it }
        }

    @Volatile private var audioEngine: AudioEngine? = null
    private val engineLock = Any()

    /**
     * Media3 构造与调用都要求主线程，而调用方不守线程纪律（Dispatchers.IO 里直接调
     * player.playUrl），因此统一包 [MainThreadAudioEngine] 转发；首次懒创建也从任意
     * 线程同步切到主线程，避免 ExoPlayer 在无 Looper 的后台线程构造。
     */
    private fun createAudioEngine(): AudioEngine = runOnMain {
        MainThreadAudioEngine(
            HybridAudioEngine(
                primary = Media3AudioEngine(this),
                fallback = FfmpegAudioEngine(this),
            ),
        )
    }

    private fun <T> runOnMain(create: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return create()
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        engineHandler.post {
            try {
                result.set(create())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error.get()?.let { throw it }
        return result.get()
    }

    private val engineHandler by lazy { Handler(Looper.getMainLooper()) }

    val videoPlayer by lazy { NativeVideoPlayer(this) }
    val ffmpegVideoPlayer by lazy { FfmpegFallbackPlayer(this) }
    val mediaServer by lazy { LocalMediaServer(this) }
    val audioOutputs by lazy { AudioOutputController(this) }
    val dlnaDiscovery by lazy { DlnaDiscovery(this) }
    /** 插件音源管理器（MusicFree 插件协议）：源列表、插件运行时与解析的唯一入口。 */
    val pluginManager by lazy { MusicFreePluginManager(this) }
    val downloadDatabase by lazy { DownloadTaskDatabase.create(this) }
    val downloads by lazy { DownloadRepository(this, contentResolver, downloadDatabase.tasks()) }
    internal val panApi by lazy {
        BaiduPanApi(
            apiKey = BuildConfig.BAIDU_PAN_API_KEY,
            secretKey = BuildConfig.BAIDU_PAN_SECRET_KEY,
            oauthRedirectUri = BuildConfig.BAIDU_PAN_REDIRECT_URI,
        )
    }
    internal val panAccountStore by lazy { BaiduPanAccountStore(this) }
    internal val panStreamProxy by lazy {
        BaiduPanStreamProxy(panApi) { panAccountStore.accessToken() }
    }
    internal val quarkApi by lazy { QuarkPanApi() }
    internal val quarkAccountStore by lazy { QuarkPanAccountStore(this) }
    internal val quarkStreamProxy by lazy { QuarkPanStreamProxy(quarkApi) }
    internal val panDownloadDatabase by lazy { BaiduPanDownloadDatabase.create(this) }
    internal val panDownloads by lazy {
        BaiduPanDownloadRepository(
            context = this,
            accessTokenProvider = { panAccountStore.accessToken() },
            dao = panDownloadDatabase.downloads(),
        )
    }
    internal val quarkDownloadDatabase by lazy { QuarkPanDownloadDatabase.create(this) }
    internal val quarkDownloads by lazy {
        QuarkPanDownloadRepository(
            context = this,
            dao = quarkDownloadDatabase.downloads(),
        )
    }
    internal val downloadEngine by lazy {
        DownloadEngine(
            context = this,
            downloads = downloads,
            panDownloads = panDownloads,
            quarkDownloads = quarkDownloads,
            pluginManager = pluginManager,
            panApi = panApi,
            quarkApi = quarkApi,
            accessTokenProvider = { panAccountStore.accessToken() },
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            OnlineArtworkCache.clear()
            LocalArtworkCache.clear()
        }
    }

    override fun onTerminate() {
        audioEngine?.release()
        videoPlayer.release()
        mediaServer.close()
        panStreamProxy.close()
        quarkStreamProxy.close()
        super.onTerminate()
    }
}
