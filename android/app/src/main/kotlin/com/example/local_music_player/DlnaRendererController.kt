package com.example.local_music_player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * DLNA 渲染端会话控制（AVTransport/RenderingControl/ConnectionManager SOAP），
 * 实现 [CastRemote] 统一接口供 VM 投送链路使用。
 *
 * 2026-09-28 第一梯队加固：
 * - Stop 先于 SetAVTransportURI（部分旧固件直接 Set 会拒绝）；
 * - Seek Unit 回退：REL_TIME 被拒后按 ABS_TIME 重试（两者对整轨播放语义一致）；
 * - SOAP fault 解析：设备拒绝时给出可读原因（格式不支持/设备忙等）。
 */
internal class DlnaRendererController(private val device: DlnaDevice) : CastRemote {
    override val deviceName: String = device.name

    private var eventChannel: DlnaEventChannel? = null
    @Volatile private var cachedSinkMimes: Set<String>? = null
    @Volatile private var seekUnit: String = "REL_TIME"

    /**
     * 订阅设备 AVTransport 的 GENA 事件：暂停/停止/播放状态变化即时回调（主线程），
     * 不再依赖 500ms 轮询发现。订阅失败返回 false，调用方继续轮询兜底。
     */
    override suspend fun attachEvents(onState: (String) -> Unit): Boolean {
        val subUrl = device.avTransportEventSubUrl ?: return false
        if (eventChannel != null) return true
        val channel = DlnaEventChannel(subUrl)
        val subscribed = channel.subscribe(onState = onState)
        if (subscribed) eventChannel = channel else runCatching { channel.close() }
        return subscribed
    }

    /** 释放事件订阅等会话资源；投送会话结束（返回本机/掉线/切换）时必须调用。 */
    override fun dispose() {
        eventChannel?.close()
        eventChannel = null
    }

    override suspend fun load(source: String, track: NativeTrack, isVideo: Boolean) {
        // Stop 先于 Set：部分设备在活动传输上直接 SetAVTransportURI 会返回 705/701 类错误
        runCatching { call("Stop", "") }
        call(
            "SetAVTransportURI",
            "<CurrentURI>${xml(source)}</CurrentURI><CurrentURIMetaData>${xml(buildDlnaDidl(source, track, isVideo))}</CurrentURIMetaData>",
        )
    }

    /** gapless 预注册：把下一首地址预写进设备（设备不支持时返回 false，VM 静默降级）。 */
    override suspend fun preloadNext(url: String, track: NativeTrack): Boolean = runCatching {
        call(
            "SetNextAVTransportURI",
            "<NextURI>${xml(url)}</NextURI><NextURIMetaData>${xml(buildDlnaDidl(url, track, isVideo = false))}</NextURIMetaData>",
        )
        true
    }.getOrDefault(false)
    /**
     * 接收端声明的可播格式（ConnectionManager GetProtocolInfo 的 Sink 列表）。
     * 设备未提供 ConnectionManager 或探测失败返回 null（未知，按不支持处理但不缓存）。
     */
    override suspend fun receiverSinkMimes(): Set<String>? {
        cachedSinkMimes?.let { return it }
        val controlUrl = device.connectionManagerControlUrl ?: return null
        val response = runCatching {
            call("GetProtocolInfo", "", controlUrl, "urn:schemas-upnp-org:service:ConnectionManager:1")
        }.getOrNull() ?: return null
        val mimes = parseDlnaSinkMimes(response)
        if (mimes.isEmpty()) return null
        cachedSinkMimes = mimes
        return mimes
    }

    override suspend fun play(speed: Double) {
        // Speed 形态容错（2026-09-29）：不少固件（实测移动高清盒/TCL）只认标准速度枚举
        // 「1」，收到「1.0」直接拒整个 Play——整数速度按整数下发；仍被拒则回退无参数 Play
        // （UPnP 允许 Play 省略 Speed），变速降级为 1 倍速而不是投送整体失败。
        try {
            call("Play", "<Speed>${formatPlaySpeed(speed)}</Speed>")
        } catch (error: Exception) {
            runCatching { call("Play", "") }.getOrElse { throw error }
        }
    }

    private fun formatPlaySpeed(speed: Double): String =
        if (speed == speed.toLong().toDouble()) speed.toLong().toString() else speed.toString()

    override suspend fun pause() {
        call("Pause", "")
    }

    override suspend fun stop() {
        call("Stop", "")
    }

    override suspend fun seek(positionMs: Long): Boolean {
        // 单位回退：REL_TIME 被个别设备拒绝时按 ABS_TIME 重试（对整轨播放语义等价），
        // 成功后记住该设备认可的口径，不再反复试错。
        val primary = seekUnit
        val units = listOf(primary) + DLNA_SEEK_UNITS.filter { it != primary }
        var lastError: Exception? = null
        for (unit in units) {
            try {
                call("Seek", "<Unit>$unit</Unit><Target>${formatTime(positionMs)}</Target>")
                seekUnit = unit
                return true
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("设备不支持时间定位")
    }

    override suspend fun setVolume(value: Float): Boolean {
        call(
            "SetVolume",
            "<Channel>Master</Channel><DesiredVolume>${(value.coerceIn(0f, 1f) * 100).toInt()}</DesiredVolume>",
            device.renderingControlUrl ?: return false,
            device.renderingControlServiceType ?: "urn:schemas-upnp-org:service:RenderingControl:1",
        )
        return true
    }

    override suspend fun volume(): Float? {
        val response = call(
            "GetVolume",
            "<Channel>Master</Channel>",
            device.renderingControlUrl ?: return null,
            device.renderingControlServiceType ?: "urn:schemas-upnp-org:service:RenderingControl:1",
        )
        return Regex("<CurrentVolume>([^<]+)")
            .find(response)?.groupValues?.get(1)?.toFloatOrNull()
            ?.div(100f)?.coerceIn(0f, 1f)
    }

    override suspend fun seekWhenReady(positionMs: Long) {
        var lastError: Exception? = null
        repeat(12) {
            delay(500)
            try {
                seek(positionMs)
                return
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("设备未就绪，无法断点续播")
    }

    override suspend fun isStopped(): Boolean = Regex("<CurrentTransportState>([^<]+)")
        .find(call("GetTransportInfo", ""))?.groupValues?.get(1) in setOf("STOPPED", "NO_MEDIA_PRESENT")

    override suspend fun positionMs(): Long {
        val response = call("GetPositionInfo", "")
        val value = Regex("<RelTime>([^<]+)").find(response)?.groupValues?.get(1) ?: return 0
        return parseTime(value)
    }

    private suspend fun call(action: String, body: String): String = call(
        action,
        body,
        device.avTransportControlUrl,
        device.avTransportServiceType,
    )

    private suspend fun call(action: String, body: String, controlUrl: String, serviceType: String): String = withContext(Dispatchers.IO) {
        val payload = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$serviceType"><InstanceID>0</InstanceID>$body</u:$action></s:Body></s:Envelope>"""
        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val ok = connection.responseCode in 200..299
        connection.disconnect()
        if (!ok) {
            // UPnP fault → 用户可读原因（设备拒绝原因不再只显示状态码）
            val detail = parseDlnaFault(response)
            throw java.io.IOException(detail ?: "DLNA 操作失败：${connection.responseCode}")
        }
        response
    }

    private fun xml(value: String) = xmlEscape(value)
    private fun formatTime(ms: Long): String { val s = ms / 1000; return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) }
    private fun parseTime(value: String): Long { val p = value.split(':'); return if (p.size == 3) ((p[0].toLongOrNull() ?: 0) * 3600 + (p[1].toLongOrNull() ?: 0) * 60 + (p[2].toLongOrNull() ?: 0)) * 1000 else 0 }
}
