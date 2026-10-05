package com.example.local_music_player

/**
 * 投送远端会话统一抽象（2026-09-28 第一梯队 2）。
 *
 * DLNA 与 Chromecast 的会话操作收敛到同一接口，VM 投送链路（加载/控制/状态/预载）
 * 不感知协议差异。状态事件沿用 DLNA 的 TransportState 词汇（PLAYING / PAUSED_PLAYBACK /
 * STOPPED），由各实现映射。
 *
 * 约定：
 * - 「不支持」与「失败」区分：能力类方法（volume/preloadNext/attachEvents）返回
 *   null/false 由 VM 静默降级，不抛异常；控制类方法失败可抛。
 * - 全部方法可从任意线程调用，实现自行切线程（DLNA 走 Dispatchers.IO，Cast 走主线程）。
 */
internal interface CastRemote {
    val deviceName: String

    /** 加载媒体到接收端（DLNA=SetAVTransportURI；Cast=setMediaItem+prepare）。 */
    suspend fun load(url: String, track: NativeTrack, isVideo: Boolean)

    suspend fun play(speed: Double)
    suspend fun pause()
    suspend fun stop()

    /** 断点续播：接收端未必立刻就绪，实现需自带重试。 */
    suspend fun seekWhenReady(positionMs: Long)
    suspend fun seek(positionMs: Long): Boolean
    suspend fun positionMs(): Long
    suspend fun isStopped(): Boolean
    suspend fun setVolume(value: Float): Boolean
    suspend fun volume(): Float?

    /** gapless 预注册；实现不支持时返回 false（VM 静默降级为换曲时整体加载）。 */
    suspend fun preloadNext(url: String, track: NativeTrack): Boolean

    /** 状态事件订阅；订阅失败返回 false，VM 继续轮询兜底。 */
    suspend fun attachEvents(onState: (String) -> Unit): Boolean

    /**
     * 接收端可播格式（Sink 列表）；null=未知。仅 DLNA 提供真实探测——
     * Chromecast 接收端自带解码，无需探测（保持 null 即可关闭转码决策）。
     */
    suspend fun receiverSinkMimes(): Set<String>? = null

    fun dispose()
}
