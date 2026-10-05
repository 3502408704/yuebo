package com.example.local_music_player

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URL

data class DlnaDevice(
    val id: String,
    val name: String,
    val location: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String? = null,
    val renderingControlServiceType: String? = null,
    val avTransportServiceType: String = "urn:schemas-upnp-org:service:AVTransport:1",
    // —— 2026-09-28 A 路线升级：能力探测与 GENA 事件所需的描述信息（旧记录可缺省） ——
    val connectionManagerControlUrl: String? = null,
    val avTransportEventSubUrl: String? = null,
)

class DlnaDiscovery(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    suspend fun scan(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val multicastLock = wifiManager.createMulticastLock("local-music-player-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val locations = linkedSetOf<String>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 3_000
                // MediaRenderer 1/2/3 三代设备类型逐一探测（部分新型号只应答所声明的版本），
                // 每个目标重发一次抗 UDP 丢包；不发 ssdp:all 防止非 renderer 设备的响应洪泛。
                val requests = DLNA_RENDERER_SEARCH_TARGETS.flatMap { target ->
                    listOf(ssdpSearchPacket(target, 2), ssdpSearchPacket(target, 2))
                }
                val group = InetAddress.getByName("239.255.255.250")
                requests.forEach { request ->
                    socket.send(DatagramPacket(request, request.size, group, 1900))
                }
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        String(packet.data, 0, packet.length).lineSequence()
                            .firstOrNull { it.startsWith("location:", true) }
                            ?.substringAfter(':')?.trim()?.let(locations::add)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
            }
            locations.mapNotNull(::describe).distinctBy { it.id }
        } finally {
            multicastLock.release()
        }
    }

    private fun describe(location: String): DlnaDevice? {
        val description = runCatching {
            val xml = URL(location).openConnection().apply { connectTimeout = 3_000; readTimeout = 3_000 }.getInputStream()
                .bufferedReader().use { it.readText() }
            parseDlnaDeviceDescription(xml)
        }.getOrNull() ?: return null
        val base = runCatching { URI(location) }.getOrNull() ?: return null
        fun resolve(path: String): String = try {
            base.resolve(path).toString()
        } catch (_: IllegalArgumentException) {
            path
        }
        val transport = description.serviceOf("AVTransport") ?: return null
        val rendering = description.serviceOf("RenderingControl")
        return DlnaDevice(
            id = description.udn,
            name = description.friendlyName,
            location = location,
            avTransportControlUrl = resolve(transport.controlUrl),
            renderingControlUrl = rendering?.let { resolve(it.controlUrl) },
            renderingControlServiceType = rendering?.serviceType,
            avTransportServiceType = transport.serviceType,
            connectionManagerControlUrl = description.serviceOf("ConnectionManager")?.let { resolve(it.controlUrl) },
            avTransportEventSubUrl = transport.eventSubUrl.takeIf { it.isNotBlank() }?.let { resolve(it) },
        )
    }
}
