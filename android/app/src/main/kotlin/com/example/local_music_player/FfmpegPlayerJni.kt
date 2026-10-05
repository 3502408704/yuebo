package com.example.local_music_player

import android.media.AudioTrack
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * FFmpeg 兜底播放器 JNI 绑定（libffmpeg_player.so）。
 *
 * 回调已 handle 化：原生解码线程按句柄回调 [nativeOnComplete]/[nativeOnError]/[nativeOnDiagnostic]，
 * 由 [register]/[unregister] 维护的 handle→回调映射分发，支持视频兜底与（随阶段 B 接入的）
 * 音频兜底并发实例，杜绝静态单例回调互相覆盖。
 */
object FfmpegPlayerJni {
    init {
        System.loadLibrary("ffmpeg_player")
    }

    /** 单个播放实例的回调组；open 成功拿到句柄后注册，close 前注销。 */
    class Callbacks(
        val onComplete: () -> Unit,
        val onError: (String) -> Unit,
        val onDiagnostic: (String) -> Unit,
    )

    private val callbacks = ConcurrentHashMap<Long, Callbacks>()

    /** 注册某 handle 的回调；handle==0（打开失败）时不注册。 */
    fun register(handle: Long, cb: Callbacks) {
        if (handle != 0L) callbacks[handle] = cb
    }

    /** 注销某 handle 的回调；应在 nativeClose 前调用。 */
    fun unregister(handle: Long) {
        callbacks.remove(handle)
    }

    // ---- 原生回调入口：C 端解码线程按句柄回调；未注册句柄（如 open 失败诊断）静默忽略 ----
    @JvmStatic
    fun nativeOnComplete(handle: Long) {
        callbacks[handle]?.onComplete?.invoke()
    }

    @JvmStatic
    fun nativeOnError(handle: Long, message: String) {
        callbacks[handle]?.onError?.invoke(message)
    }

    @JvmStatic
    fun nativeOnDiagnostic(handle: Long, message: String) {
        callbacks[handle]?.onDiagnostic?.invoke(message)
    }

    external fun nativeCreate(): Long
    external fun nativeSetCaFile(path: String)
    external fun nativeOpen(handle: Long, path: String, audioOnly: Boolean): Boolean
    external fun nativeHasVideoTrack(handle: Long): Boolean
    external fun nativeSetSurface(handle: Long, surface: Surface?): Boolean
    external fun nativeSetVideoEnabled(handle: Long, enabled: Boolean): Boolean
    external fun nativeSetAudioTrack(handle: Long, track: AudioTrack?): Boolean
    external fun nativeAudioSampleRate(handle: Long): Int
    external fun nativeAudioChannels(handle: Long): Int
    external fun nativeStart(handle: Long, playWhenReady: Boolean)
    external fun nativePause(handle: Long)
    external fun nativeResume(handle: Long)
    external fun nativeSeek(handle: Long, ms: Long)
    external fun nativeStop(handle: Long)
    external fun nativeClose(handle: Long)
    external fun nativePositionMs(handle: Long): Long
    external fun nativeDurationMs(handle: Long): Long
    external fun nativeIsPlaying(handle: Long): Boolean
    external fun nativeSetVolume(handle: Long, volume: Float)
    external fun nativeLastError(handle: Long): String?
}
