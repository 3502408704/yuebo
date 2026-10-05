package com.example.local_music_player

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DLNA 投屏内核的纯函数层（2026-09-28 A 路线升级，全部可 JVM 单测）。
 *
 * 覆盖四件事：
 * 1. 设备描述 XML 解析（DOM + 禁用外部实体，替代原先的正则口径）；
 * 2. 接收端能力探测：ConnectionManager GetProtocolInfo 的 Sink 列表 → 直投/代理判定；
 * 3. GENA 事件：NOTIFY 通知体里 TransportState 的解析（LastChange 双层转义兼容）；
 * 4. SetAVTransportURI 的 DIDL-Lite 元数据补全（条目类型/专辑/封面/时长）。
 *
 * 所有解析都按「尽力而为」：任何畸形输入返回 null/空集，由调用方回退旧行为。
 */

// —— 设备描述解析（发现阶段） ——

internal data class DlnaServiceInfo(
    val serviceType: String,
    val serviceId: String,
    val controlUrl: String,
    val eventSubUrl: String,
)

internal data class DlnaDeviceDescription(
    val udn: String,
    val friendlyName: String,
    val services: List<DlnaServiceInfo>,
) {
    fun serviceOf(type: String): DlnaServiceInfo? =
        services.firstOrNull { it.serviceType.startsWith("urn:schemas-upnp-org:service:$type:") }
}

/** 解析设备描述 XML（root/device/serviceList）。畸形或无 AVTransport 的描述返回 null。 */
internal fun parseDlnaDeviceDescription(xml: String): DlnaDeviceDescription? = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // 网络来源的 XML：关闭外部实体/DOCTYPE（XXE），特性按实现支持程度尽力开启
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    fun textOf(tag: String): String? =
        (document.getElementsByTagName(tag).item(0) as? Element)?.textContent?.trim()

    val services = mutableListOf<DlnaServiceInfo>()
    val serviceNodes = document.getElementsByTagName("service")
    for (index in 0 until serviceNodes.length) {
        val node = serviceNodes.item(index) as? Element ?: continue
        fun child(tag: String): String? = (node.getElementsByTagName(tag).item(0) as? Element)?.textContent?.trim()
        val serviceType = child("serviceType") ?: continue
        if (!serviceType.startsWith("urn:schemas-upnp-org:service:")) continue
        services += DlnaServiceInfo(
            serviceType = serviceType,
            serviceId = child("serviceId").orEmpty(),
            controlUrl = child("controlURL") ?: continue,
            eventSubUrl = child("eventSubURL").orEmpty(),
        )
    }
    val udn = textOf("UDN") ?: return null
    if (services.none { it.serviceType.startsWith("urn:schemas-upnp-org:service:AVTransport") }) return null
    DlnaDeviceDescription(
        udn = udn,
        friendlyName = textOf("friendlyName") ?: "未知 DLNA 设备",
        services = services,
    )
}.getOrNull()

// —— 接收端能力探测（直投/代理判定） ——

/** HLS 的 contentFormat 家族：不同厂商清单任意一种都算支持。 */
internal val DLNA_HLS_CONTENT_FORMATS = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "video/mp2t",
    "mpegurl",
)

/** 接收端未声明 Sink 时仍可直投的通用音频 MIME；FLAC/APE/OGG 等走 WAV 兼容层。 */
internal val DLNA_COMMON_AUDIO_MIMES = setOf(
    "audio/mpeg",
    "audio/mp3",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/l16",
    "audio/mp4",
    "audio/aac",
    "audio/aacp",
)

/**
 * 解析 ConnectionManager GetProtocolInfo 应答中的 Sink 列表。
 * 返回 contentFormat 集合（如 video/mp4、application/vnd.apple.mpegurl；`*` 表示全接受）。
 */
internal fun parseDlnaSinkMimes(responseXml: String): Set<String> {
    val sink = Regex("<Sink[^>]*>([^<]*)</Sink>", RegexOption.IGNORE_CASE)
        .find(responseXml)?.groupValues?.get(1)?.trim().orEmpty()
    if (sink.isEmpty()) return emptySet()
    return sink.split(',')
        .mapNotNull { entry ->
            val fields = entry.trim().split(':')
            // protocolInfo 四段式：protocol:network:contentFormat:additional
            fields.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        }
        .toSet()
}

/** 接收端能否直接播放该媒体（用于决定直投还是经本机代理转封装地址）。 */
internal fun dlnaReceiverSupports(sinkMimes: Set<String>, mimeType: String, isHls: Boolean): Boolean {
    val normalized = { value: String -> value.substringBefore(';').trim().lowercase() }
    val wanted = normalized(mimeType)
    val sinks = sinkMimes.map(normalized)
    if (sinks.isEmpty()) return false
    if (sinks.any { it == "*" || it == "*/*" }) return true
    if (wanted.isNotEmpty() && sinks.any { it == wanted || it == "${wanted.substringBefore('/')}/*" }) return true
    if (isHls && sinks.any { it in DLNA_HLS_CONTENT_FORMATS }) return true
    return false
}

/** Sink 探测为空/失败时按通用 DLNA 音频格式保守处理，避免不支持的本地格式无声直投。 */
internal fun dlnaShouldTranscodeAudio(sinkMimes: Set<String>?, mimeType: String): Boolean {
    val normalized = mimeType.substringBefore(';').trim().lowercase()
    if (!normalized.startsWith("audio/")) return false
    if (sinkMimes != null && sinkMimes.isNotEmpty()) {
        return !dlnaReceiverSupports(sinkMimes, normalized, isHls = false)
    }
    return normalized !in DLNA_COMMON_AUDIO_MIMES
}

/** 地址/MIME 是否为 HLS 清单（直投判定的输入之一）。 */
internal fun dlnaIsHlsSource(url: String, mimeType: String): Boolean =
    mimeType.substringBefore(';').trim().lowercase() in DLNA_HLS_CONTENT_FORMATS ||
        url.substringBefore('?').let { it.endsWith(".m3u8") || it.endsWith(".mpls") }

/**
 * 直投判定：地址公开（无签名/时效）、无需附加请求头、且接收端声明支持该格式时，
 * 才把原始地址直接交给 DLNA 设备；否则一律经本机代理（签名不出本机、代理带 UA/Referer）。
 */
internal fun dlnaCanDirectCast(urlIsPublic: Boolean, headersRequired: Boolean, receiverSupported: Boolean?): Boolean =
    urlIsPublic && !headersRequired && receiverSupported == true

// —— GENA 事件（NOTIFY 通知体解析） ——

/**
 * 从 NOTIFY 通知体提取 TransportState。
 * 兼容两种形态：AVTransport:1 的 LastChange（内层 XML 被 XML 转义一层）与
 * 直接以独立属性变量出现的 `<TransportState val="PLAYING"/>`。
 */
internal fun parseDlnaTransportState(notifyBody: String): String? {
    val lastChange = Regex("<LastChange[^>]*>([^<]*)</LastChange>", RegexOption.IGNORE_CASE)
        .find(notifyBody)?.groupValues?.get(1)
    if (lastChange != null && lastChange.isNotBlank()) {
        val unescaped = unescapeXml(lastChange)
        Regex("<TransportState[^>]*val=\"([A-Za-z_]+)\"").find(unescaped)?.let { return it.groupValues[1] }
        Regex("<TransportState>([A-Za-z_]+)</TransportState>").find(unescaped)?.let { return it.groupValues[1] }
    }
    Regex("<TransportState[^>]*val=\"([A-Za-z_]+)\"").find(notifyBody)?.let { return it.groupValues[1] }
    Regex("<TransportState>([A-Za-z_]+)</TransportState>").find(notifyBody)?.let { return it.groupValues[1] }
    return null
}

/** 投屏内核关心的「会话已停」状态口径（与 GetTransportInfo 的 isStopped 一致）。 */
internal fun dlnaStateIsStopped(state: String?): Boolean =
    state == "STOPPED" || state == "NO_MEDIA_PRESENT"

internal fun unescapeXml(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&#39;", "'")
    .replace("&amp;", "&")

// —— SOAP fault 与 Seek 单位（互操作兼容包） ——

/** AVTransport Seek 的候选单位：先 REL_TIME，被拒后按 ABS_TIME 重试。 */
internal val DLNA_SEEK_UNITS = listOf("REL_TIME", "ABS_TIME")

/**
 * 解析 UPnP SOAP fault 为用户可读原因；无法识别时回退 errorDescription，全无则 null。
 * 错误码口径（AVTransport action-specific + UPnP 通用）：
 * 701 转状态中 / 705 需先停止 / 706 格式不支持 / 708 协议不支持 / 710-715 取流失败。
 */
internal fun parseDlnaFault(responseXml: String): String? {
    val description = Regex("<errorDescription>([^<]*)</errorDescription>", RegexOption.IGNORE_CASE)
        .find(responseXml)?.groupValues?.get(1)?.trim()
    val code = Regex("<errorCode>(\\d+)</errorCode>", RegexOption.IGNORE_CASE)
        .find(responseXml)?.groupValues?.get(1)?.toIntOrNull()
    val readable = when (code) {
        701 -> "设备正在切换状态，请稍后重试"
        705 -> "设备要求先停止播放（已自动重试）"
        706 -> "设备不支持该媒体格式"
        708 -> "设备不支持该播放地址的协议"
        710, 711, 712, 713, 714, 715 -> "设备无法获取媒体内容（地址可能已失效）"
        716 -> "该资源不在设备允许的播放策略内"
        717 -> "设备不支持该播放速度"
        else -> null
    }
    return when {
        readable != null -> readable
        !description.isNullOrBlank() -> "设备返回：$description"
        else -> null
    }
}

// —— M-SEARCH（多 ST 探测） ——

/** MediaRenderer 1/2/3 三代设备类型都探测；只认 renderer，不发 ssdp:all 防响应洪泛。 */
internal val DLNA_RENDERER_SEARCH_TARGETS = listOf(
    "urn:schemas-upnp-org:device:MediaRenderer:1",
    "urn:schemas-upnp-org:device:MediaRenderer:2",
    "urn:schemas-upnp-org:device:MediaRenderer:3",
)

internal fun ssdpSearchPacket(searchTarget: String, mxSeconds: Int): ByteArray =
    ("M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $mxSeconds\r\n" +
        "ST: $searchTarget\r\n\r\n").toByteArray()

// —— DIDL-Lite 元数据 ——

/**
 * SetAVTransportURI 的条目元数据：补全 upnp:class/专辑/封面/时长。
 * 不少电视的投屏界面靠 upnp:class 决定展示样式，靠 albumArtURI 显示封面。
 */
internal fun buildDlnaDidl(source: String, track: NativeTrack, isVideo: Boolean): String =
    buildDlnaDidl(
        source = source,
        title = track.title,
        artist = track.artist,
        album = track.album,
        artworkUrl = track.artworkUrl,
        mimeType = track.mimeType,
        durationMs = track.durationMs,
        isVideo = isVideo,
    )

internal fun buildDlnaDidl(
    source: String,
    title: String,
    artist: String,
    album: String,
    artworkUrl: String?,
    mimeType: String,
    durationMs: Long,
    isVideo: Boolean,
): String {
    val itemClass = if (isVideo) "object.item.videoItem.movie" else "object.item.audioItem.musicTrack"
    val duration = formatDlnaDuration(durationMs)
    val builder = StringBuilder()
    builder.append("<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
        .append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"")
        .append(" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">")
        .append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        .append("<dc:title>").append(xmlEscape(title)).append("</dc:title>")
        .append("<dc:creator>").append(xmlEscape(artist)).append("</dc:creator>")
        .append("<upnp:class>").append(itemClass).append("</upnp:class>")
    if (album.isNotBlank()) {
        builder.append("<upnp:album>").append(xmlEscape(album)).append("</upnp:album>")
    }
    artworkUrl?.takeIf { it.isNotBlank() }?.let { artwork ->
        builder.append("<upnp:albumArtURI>").append(xmlEscape(artwork)).append("</upnp:albumArtURI>")
    }
    builder.append("<res protocolInfo=\"http-get:*:").append(xmlEscape(mimeType.ifBlank { "application/octet-stream" })).append(":*\"")
    if (duration != null) builder.append(" duration=\"").append(duration).append("\"")
    builder.append(">").append(xmlEscape(source)).append("</res>")
        .append("</item></DIDL-Lite>")
    return builder.toString()
}

internal fun formatDlnaDuration(ms: Long): String? {
    if (ms <= 0) return null
    val totalSeconds = ms / 1000
    return "%d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
}

internal fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
