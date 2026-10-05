package com.example.local_music_player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.Executors

/**
 * FFmpeg 兜底播放引擎：实现 [VideoEngine]，用于 ExoPlayer/系统解码器不支持的旧格式
 * （WMV/RMVB/MPG 等）。demux+decode 在原生 libffmpeg_player 完成；视频渲染到
 * [setSurface] 提供的 Surface（ANativeWindow），音频 PCM（立体声 16bit）写入本类创建的 [AudioTrack]。
 */
class FfmpegFallbackPlayer(
    private val context: Context,
    private val requireVideoTrack: Boolean = true,
) : VideoEngine {
    /** 串行化 handle/audioTrack 生命周期：主线程 closeHandle 与播放线程 setup 互斥。 */
    private val handleLock = Any()
    @Volatile private var handle: Long = 0
    private var openingHandle: Long = 0
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "FfmpegOpenClose").apply { isDaemon = true } } }
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var pendingSurface: Surface? = null
    @Volatile private var videoEnabled = true
    private var volume = 1f
    @Volatile private var paused = false
    @Volatile private var requestedPositionMs = 0L
    /** 递增令牌：快速切集时让旧播放线程安全退出，避免并发 close/open 导致崩溃。 */
    @Volatile private var playToken = 0L
    @Volatile private var caFileReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** ffmpeg 的 tls_mbedtls 校验证书需要 CA 文件：把 assets 内置的 ISRG 根证书
     *  拷到 cacheDir 并注入 native 层（https 源没有它会在 open 阶段以 EIO 失败）。 */
    private fun ensureCaFile() {
        if (caFileReady) return
        try {
            val out = File(context.cacheDir, "ff-ca.pem")
            if (!out.isFile || out.length() == 0L) {
                context.assets.open("ssl/isrg-roots.pem").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            FfmpegPlayerJni.nativeSetCaFile(out.absolutePath)
            caFileReady = true
        } catch (e: Exception) {
            AppErrorRecorder.event("FFmpeg", "CA 初始化失败: ${e.javaClass.simpleName}")
        }
    }

    override var onComplete: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override var failFastStall: Boolean = false
    override var onUnsupported: ((String) -> Unit)? = null
    override var onStateChanged: (() -> Unit)? = null

    /**
     * 按句柄注册当前会话回调；回调切回主线程执行：原生解码线程在回调里可能立即 close
     * （join 自身线程会死锁）。句柄关闭前必须 [unregisterCallbacks]。
     */
    private fun registerCallbacks(handle: Long, token: Long) {
        FfmpegPlayerJni.register(
            handle,
            FfmpegPlayerJni.Callbacks(
                onComplete = {
                    mainHandler.post {
                        if (token != playToken || handle != this.handle) return@post
                        AppErrorRecorder.event("FFmpeg", "原生解码完成")
                        onComplete?.invoke()
                        onStateChanged?.invoke()
                    }
                },
                onError = { message ->
                    mainHandler.post {
                        if (token != playToken || handle != this.handle) return@post
                        AppErrorRecorder.event("FFmpeg", "原生解码错误: $message")
                        onError?.invoke(message)
                        onStateChanged?.invoke()
                    }
                },
                onDiagnostic = { message ->
                    AppErrorRecorder.event("FFmpeg", message)
                },
            ),
        )
    }

    private fun unregisterCallbacks(handle: Long) {
        FfmpegPlayerJni.unregister(handle)
    }

    /** UI 提供渲染 Surface（SurfaceView/TextureView 的 holder.surface）；可为空（仅音频）。 */
    fun setSurface(surface: Surface?) {
        pendingSurface = surface
        synchronized(handleLock) {
            if (handle != 0L) runCatching { FfmpegPlayerJni.nativeSetSurface(handle, surface) }
            .onFailure { AppErrorRecorder.record("FFmpeg 设置画面", it) }
        }
    }

    override fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        synchronized(handleLock) {
            if (handle != 0L) runCatching { FfmpegPlayerJni.nativeSetVideoEnabled(handle, enabled) }
        }
    }

    override fun play(uri: Uri, positionMs: Long) {
        val token = ++playToken
        paused = false
        requestedPositionMs = positionMs.coerceAtLeast(0)
        AppErrorRecorder.event("FFmpeg", "开始播放 token=$token uri=${diagnosticUri(uri)} positionMs=$positionMs")
        closeHandle()
        // 打开/复制在后台线程执行，避免 content:// 打开或大文件复制阻塞主线程（ANR）。
        worker.execute {
            if (token != playToken) return@execute
            val h = openHandle(uri, token)
            if (h == 0L) {
                if (token != playToken) return@execute
                mainHandler.post {
                    if (token == playToken) {
                        onError?.invoke("无法打开该媒体，格式不支持、文件已损坏或连接超时。")
                        onStateChanged?.invoke()
                    }
                }
                return@execute
            }
            if (requireVideoTrack && !FfmpegPlayerJni.nativeHasVideoTrack(h)) {
                runCatching { FfmpegPlayerJni.nativeClose(h) }
                if (token != playToken) return@execute
                mainHandler.post {
                    if (token == playToken) {
                        onError?.invoke(ffmpegOpenErrorMessage(opened = true, hasVideoTrack = false))
                        onStateChanged?.invoke()
                    }
                }
                return@execute
            }
            // 句柄尚未发布前完成 AudioTrack 创建；快速切集只能使本线程丢弃 h，不能并发关闭它。
            val track = buildAudioTrack(h)
            if ((!requireVideoTrack || FfmpegPlayerJni.nativeAudioSampleRate(h) > 0) && track == null) {
                FfmpegPlayerJni.nativeClose(h)
                mainHandler.post {
                    if (token == playToken) onError?.invoke("无法创建音频输出，或该媒体没有可解码的音轨。")
                }
                return@execute
            }
            var proceed = true
            synchronized(handleLock) {
                if (token != playToken) {
                    track?.let { t -> runCatching { t.release() } }
                    runCatching { FfmpegPlayerJni.nativeClose(h) }
                    proceed = false
                } else {
                    handle = h
                    registerCallbacks(h, token)
                    releaseAudioTrackInternal()
                    audioTrack = track
                    if (track != null) runCatching { FfmpegPlayerJni.nativeSetAudioTrack(h, track) }
                        .onFailure { AppErrorRecorder.record("FFmpeg 设置音频", it) }
                    pendingSurface?.let { runCatching { FfmpegPlayerJni.nativeSetSurface(h, it) } }
                    runCatching { FfmpegPlayerJni.nativeSetVideoEnabled(h, videoEnabled) }
                    runCatching { FfmpegPlayerJni.nativeSetVolume(h, volume) }
                    if (requestedPositionMs > 0) runCatching { FfmpegPlayerJni.nativeSeek(h, requestedPositionMs) }
                    runCatching { FfmpegPlayerJni.nativeStart(h, !paused) }
                        .onFailure { AppErrorRecorder.record("FFmpeg 启动", it) }
                    if (paused) {
                        FfmpegPlayerJni.nativePause(h)
                        track?.pause()
                    } else track?.play()
                }
            }
            if (!proceed) return@execute
            mainHandler.post {
                if (token == playToken) onStateChanged?.invoke()
            }
        }
    }

    override fun pause() {
        synchronized(handleLock) {
            paused = true
            audioTrack?.let { runCatching { it.pause() } }
            withHandle { FfmpegPlayerJni.nativePause(it) }
        }
    }

    override fun resume() {
        synchronized(handleLock) {
            paused = false
            audioTrack?.let { runCatching { it.play() } }
            withHandle { FfmpegPlayerJni.nativeResume(it) }
        }
    }

    override fun toggle() {
        if (isPlaying()) pause() else resume()
    }

    override fun seekTo(ms: Long) {
        requestedPositionMs = ms.coerceAtLeast(0)
        withHandle { FfmpegPlayerJni.nativeSeek(it, ms.coerceAtLeast(0)) }
    }

    override fun positionMs(): Long {
        return withHandle(0L) { FfmpegPlayerJni.nativePositionMs(it).coerceAtLeast(0) }
    }

    override fun durationMs(): Long {
        return withHandle(0L) { FfmpegPlayerJni.nativeDurationMs(it).coerceAtLeast(0) }
    }

    override fun isPlaying(): Boolean {
        return withHandle(false) { FfmpegPlayerJni.nativeIsPlaying(it) }
    }

    override fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        withHandle { FfmpegPlayerJni.nativeSetVolume(it, volume) }
    }

    override fun stopAndClear() {
        playToken++
        paused = true
        closeHandle()
    }

    override fun release() {
        stopAndClear()
    }

    private fun closeHandle() {
        synchronized(handleLock) {
            if (openingHandle != 0L) FfmpegPlayerJni.nativeStop(openingHandle)
            if (handle != 0L) {
                AppErrorRecorder.event("FFmpeg", "关闭原生解码 handle=$handle")
                unregisterCallbacks(handle)
                val closing = handle
                val output = audioTrack
                FfmpegPlayerJni.nativeStop(closing)
                output?.let { runCatching { it.pause(); it.flush() } }
                handle = 0L
                audioTrack = null
                // join/网络清理不能阻塞主线程；句柄分离后只由串行 worker 释放。
                worker.execute {
                    runCatching { FfmpegPlayerJni.nativeClose(closing) }
                        .onFailure { AppErrorRecorder.record("FFmpeg 关闭", it) }
                    output?.let { runCatching { it.release() } }
                }
            }
        }
    }

    private fun openPath(path: String, token: Long): Long {
        if (path.startsWith("http")) ensureCaFile()
        val h = FfmpegPlayerJni.nativeCreate()
        if (h == 0L) return 0L
        synchronized(handleLock) {
            if (token == playToken) openingHandle = h else FfmpegPlayerJni.nativeStop(h)
        }
        val opened = try {
            token == playToken && FfmpegPlayerJni.nativeOpen(h, path, !requireVideoTrack)
        } catch (e: Exception) {
            AppErrorRecorder.event("FFmpeg", "原生打开失败: ${e.javaClass.simpleName}")
            false
        } finally {
            synchronized(handleLock) { if (openingHandle == h) openingHandle = 0L }
        }
        if (opened && token == playToken) return h
        FfmpegPlayerJni.nativeClose(h)
        return 0L
    }

    private fun openHandle(uri: Uri, token: Long): Long {
        if (uri.scheme == "content") {
            // 优先取 MediaStore 文件路径直接打开，绕开 MediaProvider 的 fd（pipe/空流）问题。
            queryDataPath(uri)?.let { path ->
                if (File(path).isFile) {
                    val h = openPath(path, token)
                    if (h != 0L) return h
                }
            }
            // 兜底：复制到缓存再用普通文件路径打开。
            var temp: File? = null
            return try {
                // content:// 的 fd 可能是 pipe（阻塞读），先复制到缓存再用普通文件路径打开。
                temp = File.createTempFile("ffmpeg_media_", ".tmp", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (token == playToken) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: run {
                    temp.delete()
                    return 0L
                }
                val h = if (token == playToken) openPath(temp.absolutePath, token) else 0L
                temp.delete()  // Linux 下已打开的文件句柄仍可读，立即清理临时文件
                if (h == 0L) Log.w("FfmpegFallback", "nativeOpen failed for cached file")
                h
            } catch (e: Exception) {
                AppErrorRecorder.event("FFmpeg", "打开文件失败: ${e.javaClass.simpleName}")
                0L
            } finally {
                temp?.delete()
            }
        }
        return openPath(if (uri.scheme == "file") uri.path.orEmpty() else uri.toString(), token)
    }

    /** 查询 MediaStore 视频的文件路径（_data）；不可用返回 null。 */
    private fun queryDataPath(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            AppErrorRecorder.record("FFmpeg 查询媒体路径", e)
            null
        }
    }

    /** 创建暂停的音频输出；绑定会话后根据用户当前状态开始输出。 */
    private fun buildAudioTrack(h: Long): AudioTrack? {
        val sampleRate = FfmpegPlayerJni.nativeAudioSampleRate(h)
        if (sampleRate <= 0) return null  // 无音轨（纯视频）
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf * 4, sampleRate) // 约 250ms PCM，避免固定堆积 2s 音频。
        return runCatching {
            AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        }.onFailure { AppErrorRecorder.record("FFmpeg 创建音频输出", it) }.getOrNull()
    }

    /** 释放音频输出；必须在 handleLock 内调用。 */
    private fun releaseAudioTrackInternal() {
        audioTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
    }

    private inline fun withHandle(action: (Long) -> Unit) {
        synchronized(handleLock) {
            if (handle != 0L) runCatching { action(handle) }.onFailure { AppErrorRecorder.record("FFmpeg JNI", it) }
        }
    }

    private inline fun <T> withHandle(default: T, action: (Long) -> T): T = synchronized(handleLock) {
        if (handle == 0L) default else runCatching { action(handle) }
            .onFailure { AppErrorRecorder.record("FFmpeg JNI", it) }
            .getOrDefault(default)
    }

    private fun diagnosticUri(uri: Uri): String = buildString {
        append(uri.scheme ?: "未知协议")
        uri.lastPathSegment?.takeIf(String::isNotBlank)?.let { append(":").append(it) }
    }
}

internal fun ffmpegOpenErrorMessage(opened: Boolean, hasVideoTrack: Boolean): String = when {
    opened && !hasVideoTrack -> "该文件没有视频轨道，无法作为影视播放"
    !opened -> "该视频格式暂不支持播放"
    else -> "无法打开该视频文件，文件可能已损坏或已失效。"
}
