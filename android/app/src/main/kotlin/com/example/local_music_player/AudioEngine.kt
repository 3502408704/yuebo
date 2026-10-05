package com.example.local_music_player

import android.media.AudioDeviceInfo

/**
 * 本地音频播放引擎统一契约（Media3/ExoPlayer 唯一内核；BASS 已整体下线）。
 *
 * 背景：主 app 全部音频播放（本地库/在线流/投屏回本机）经 [NativeMusicViewModel] 通过
 * `MusicApplication.player`（[MainThreadAudioEngine] 包装 [Media3AudioEngine]）驱动，
 * VM 对播放器的方法调用全集即本接口。
 *
 * 调用方约定（源自 BASS 时代，所有实现都要遵守）：
 * - VM 通过主线程轮询 `positionMs()/isPlaying()/isStopped()`（100ms tick）感知播放状态；
 *   自然播完的判定 = `isStopped()==true`（播放器不再处于活动会话），与 `_state.playing` 配合触发切歌。
 * - 播放结束**不**触发 [setPlaybackCompleteListener]（该回调仅淡出转场用），切歌统一走轮询。
 * - 所有方法在主线程调用（[MainThreadAudioEngine] 负责转发）。
 *
 * 能力现状（对照迁移方案 §5 DSP 取舍清单）：
 * | 能力 | 现状 |
 * |---|---|
 * | 变速不变调 | `PlaybackParameters(rate, pitch=1f)`（Sonic time-stretch） |
 * | 淡入淡出 | 引擎内音量斜坡近似（粒度 0..1f） |
 * | 焦点 | 引擎内 AudioFocus |
 * | 10 段 EQ / DX8 效果 / 静音跳过 | 采样级 DSP 随 BASS 下线，设置项仅记录（no-op） |
 * | AirPlay PCM 发送端 | 随 BASS 下线（依赖裸 PCM 出口；如需恢复以自研 PCM 通道补回） |
 * | 输出设备点选 | 不可用（跟随系统默认路由） |
 * | ape/wv/dsf/dff/alac 等无系统解码器格式 | FFmpeg 兜底接管（v1 无变速/淡出） |
 * | 内嵌歌词 | 暂返回 null |
 */
interface AudioEngine {
    fun play(track: NativeTrack, positionMs: Long = 0)

    /**
     * 播放网络流。[headers] 是个别直播源要求的附加请求头（Referer/UA 等，服务端解析结果
     * 透传）；[isLive] 标记直播流（无进度语义），引擎据此启用断流自动重连（退避同址重连）。
     */
    fun playUrl(url: String, durationMs: Long, positionMs: Long = 0,
                headers: Map<String, String> = emptyMap(), isLive: Boolean = false)

    fun pause(): Boolean
    fun resume(): Boolean
    fun seek(positionMs: Long): Boolean
    fun positionMs(): Long
    fun durationMs(): Long
    fun lyrics(): String?
    fun isPlaying(): Boolean

    /** 当前不存在活动播放会话（含自然播完/错误终止后），配合轮询触发切歌。 */
    fun isStopped(): Boolean

    fun setVolume(value: Float): Boolean
    fun setSpeed(value: Double): Boolean
    fun setFadeEnabled(enabled: Boolean)
    fun setSilenceSkipping(enabled: Boolean, mode: SilenceSkipMode, thresholdMs: Long)
    fun setPlaybackCompleteListener(listener: () -> Unit)
    fun setFocusLossListener(listener: () -> Unit)
    fun fadeOut(onComplete: () -> Unit): Boolean

    fun setPreferredDevice(device: AudioDeviceInfo?): Boolean
    fun hasAudioTrack(): Boolean
    fun routedDevice(): AudioDeviceInfo?
    fun setRouteListener(listener: (AudioDeviceInfo?) -> Unit)

    fun setEq(bands: FloatArray, preamp: Float, enabled: Boolean)
    fun setEffects(effects: AudioEffects)

    fun release()
}
