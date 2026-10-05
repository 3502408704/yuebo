package com.example.local_music_player

import androidx.media3.common.PlaybackException

/**
 * 流媒体断流恢复策略（纯函数，可 JVM 单测；2026-09-28 起用于播放路径）。
 *
 * 背景：锁屏/切后台后网络抖动会掐断直播流（IPTV/电台），此前引擎对网络错误一律
 * 直接终止会话，表现为「锁屏一会儿直播就哑了」。策略分三层：
 * 1. [liveReconnectDelayMs]——直播网络错误同址退避重连（引擎内自动，会话保持）；
 * 2. 停滞看门狗——BUFFERING 超过 [STALL_TIMEOUT_MS] 视为断流，触发一次重连（视频）；
 * 3. 网络恢复重试——系统网络恢复后重试「因网络失败且用户仍在播」的当前曲目（VM 层）。
 */
internal object PlaybackRetryPolicy {
    /** 网络类错误码：连接失败/超时、HTTP 状态异常（网关抖动常见 502/504）、读超时。 */
    val NETWORK_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )

    fun isNetworkError(errorCode: Int): Boolean = errorCode in NETWORK_ERROR_CODES

    private val LIVE_RECONNECT_DELAYS_MS = longArrayOf(1_000L, 3_000L, 5_000L)

    /**
     * 直播断流重连退避：[retryCount] 从 0 起；返回重连前等待毫秒，
     * 非网络错误或次数用尽（3 次后）返回 null 交回上层（换源/报错）。
     */
    fun liveReconnectDelayMs(errorCode: Int, retryCount: Int): Long? {
        if (retryCount !in LIVE_RECONNECT_DELAYS_MS.indices) return null
        if (!isNetworkError(errorCode)) return null
        return LIVE_RECONNECT_DELAYS_MS[retryCount]
    }

    /** 缓冲停滞看门狗阈值：点播卡在 BUFFERING 超过该时长按断流处理（走同址重连恢复）。 */
    const val STALL_TIMEOUT_MS = 15_000L

    /** 直播多源停滞阈值：死源重连是白等，超过该时长直接换下一个信号源。 */
    const val LIVE_STALL_TIMEOUT_MS = 3_000L
}
