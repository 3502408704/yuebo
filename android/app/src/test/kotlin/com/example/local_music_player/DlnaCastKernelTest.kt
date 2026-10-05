package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 投屏内核纯函数层（DlnaCastKernel）：设备描述解析/能力匹配/GENA 事件/DIDL/探测包。 */
class DlnaCastKernelTest {
    private val deviceXml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>客厅电视</friendlyName>
    <UDN>uuid:4c494e4e-1234-5e9f-ae16-0c9d0c1e2f3a</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/dev/1/AVTransport/control</controlURL>
        <eventSubURL>/dev/1/AVTransport/event</eventSubURL>
        <SCPDURL>/dev/1/AVTransport.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/dev/1/RCS/control</controlURL>
        <eventSubURL>/dev/1/RCS/event</eventSubURL>
        <SCPDURL>/dev/1/RCS.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/dev/1/CM/control</controlURL>
        <eventSubURL>/dev/1/CM/event</eventSubURL>
        <SCPDURL>/dev/1/CM.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""

    @Test
    fun parsesDeviceDescriptionWithAllServices() {
        val description = parseDlnaDeviceDescription(deviceXml)
        assertNotNull(description)
        assertEquals("uuid:4c494e4e-1234-5e9f-ae16-0c9d0c1e2f3a", description.udn)
        assertEquals("客厅电视", description.friendlyName)
        val transport = description.serviceOf("AVTransport")
        assertNotNull(transport)
        assertEquals("/dev/1/AVTransport/control", transport.controlUrl)
        assertEquals("/dev/1/AVTransport/event", transport.eventSubUrl)
        assertNotNull(description.serviceOf("RenderingControl"))
        assertNotNull(description.serviceOf("ConnectionManager"))
    }

    @Test
    fun rejectsDescriptionWithoutAvTransportOrGarbage() {
        val noTransport = deviceXml.replace(Regex("<service>.*?</service>", RegexOption.DOT_MATCHES_ALL), "")
        assertNull(parseDlnaDeviceDescription(noTransport))
        assertNull(parseDlnaDeviceDescription("not xml at all <"))
    }

    @Test
    fun parsesSinkMimeList() {
        val response = """<u:GetProtocolInfoResponse><Sink>http-get:*:video/mp4:*,http-get:*:audio/mpeg:DLNA.ORG_PN=MP3,http-get:*:application/vnd.apple.mpegurl:*</Sink></u:GetProtocolInfoResponse>"""
        val mimes = parseDlnaSinkMimes(response)
        assertEquals(setOf("video/mp4", "audio/mpeg", "application/vnd.apple.mpegurl"), mimes)
        assertTrue(dlnaReceiverSupports(mimes, "video/mp4", isHls = false))
        assertTrue(dlnaReceiverSupports(mimes, "application/vnd.apple.mpegurl", isHls = true))
        assertFalse(dlnaReceiverSupports(mimes, "video/x-matroska", isHls = false))
    }

    @Test
    fun hlsSupportMatchesAnyVariantWhenHlsRequested() {
        val mimes = parseDlnaSinkMimes("<Sink>http-get:*:application/x-mpegurl:*</Sink>")
        assertTrue(dlnaReceiverSupports(mimes, "application/vnd.apple.mpegurl", isHls = true))
        assertFalse(dlnaReceiverSupports(mimes, "video/mp4", isHls = false))
    }

    @Test
    fun wildcardSinkAcceptsEverythingEmptyAcceptsNothing() {
        assertTrue(dlnaReceiverSupports(setOf("*"), "video/mp4", isHls = false))
        assertFalse(dlnaReceiverSupports(emptySet(), "video/mp4", isHls = true))
    }

    @Test
    fun sinkMimeWildcardsAndUnknownAudioKeepTranscodeDecisionSafe() {
        assertTrue(dlnaReceiverSupports(setOf("audio/*"), "audio/flac", isHls = false))
        assertFalse(dlnaReceiverSupports(setOf("video/*"), "audio/flac", isHls = false))
        assertTrue(dlnaShouldTranscodeAudio(null, "audio/flac"))
        assertFalse(dlnaShouldTranscodeAudio(null, "audio/mpeg"))
        assertTrue(dlnaShouldTranscodeAudio(setOf("audio/mpeg"), "audio/flac"))
        assertFalse(dlnaShouldTranscodeAudio(setOf("audio/*"), "audio/flac"))
    }

    @Test
    fun directCastNeedsPublicUrlNoHeadersAndKnownSupport() {
        assertTrue(dlnaCanDirectCast(urlIsPublic = true, headersRequired = false, receiverSupported = true))
        assertFalse(dlnaCanDirectCast(urlIsPublic = false, headersRequired = false, receiverSupported = true))
        assertFalse(dlnaCanDirectCast(urlIsPublic = true, headersRequired = true, receiverSupported = true))
        // 能力未知时保守走代理
        assertFalse(dlnaCanDirectCast(urlIsPublic = true, headersRequired = false, receiverSupported = null))
    }

    @Test
    fun parsesTransportStateFromLastChangeAndPlainProperty() {
        val genaLastChange = """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">""" +
            "<e:property><LastChange>&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/AVT/&quot;&gt;" +
            "&lt;InstanceID val=&quot;0&quot;&gt;&lt;TransportState val=&quot;PLAYING&quot;/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;</LastChange></e:property></e:propertyset>"
        assertEquals("PLAYING", parseDlnaTransportState(genaLastChange))

        val plainProperty = """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">""" +
            "<e:property><TransportState val=\"PAUSED_PLAYBACK\"/></e:property></e:propertyset>"
        assertEquals("PAUSED_PLAYBACK", parseDlnaTransportState(plainProperty))
        assertNull(parseDlnaTransportState("<e:propertyset/>"))
        assertTrue(dlnaStateIsStopped("STOPPED"))
        assertTrue(dlnaStateIsStopped("NO_MEDIA_PRESENT"))
        assertFalse(dlnaStateIsStopped("PLAYING"))
    }

    @Test
    fun searchPacketsCoverRendererGenerations() {
        assertEquals(DLNA_RENDERER_SEARCH_TARGETS.size, 3)
        val packet = String(ssdpSearchPacket("urn:schemas-upnp-org:device:MediaRenderer:2", 2))
        assertTrue(packet.contains("M-SEARCH * HTTP/1.1"))
        assertTrue(packet.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:2"))
        assertTrue(packet.contains("MX: 2"))
    }

    @Test
    fun didlCarriesClassAlbumArtworkAndDuration() {
        val didl = buildDlnaDidl(
            source = "http://example.com/a.mp3", title = "歌名 & <副标题>", artist = "歌手",
            album = "专辑", artworkUrl = "http://img.example/cover.jpg?size=1",
            mimeType = "audio/mpeg", durationMs = 3_661_000, isVideo = false,
        )
        assertTrue(didl.contains("object.item.audioItem.musicTrack"))
        assertTrue(didl.contains("<upnp:album>专辑</upnp:album>"))
        assertTrue(didl.contains("<upnp:albumArtURI>http://img.example/cover.jpg?size=1</upnp:albumArtURI>"))
        assertTrue(didl.contains("duration=\"1:01:01\""))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:audio/mpeg:*\""))
        // 元数据里的特殊字符必须转义
        assertTrue(didl.contains("歌名 &amp; &lt;副标题&gt;"))
        val videoDidl = buildDlnaDidl(
            source = "http://example.com/v.mp4", title = "片名", artist = "导演", album = "",
            artworkUrl = null, mimeType = "video/mp4", durationMs = 0, isVideo = true,
        )
        assertTrue(videoDidl.contains("object.item.videoItem.movie"))
        assertFalse(videoDidl.contains("albumArtURI"))
    }

    @Test
    fun durationFormattingBounds() {
        assertNull(formatDlnaDuration(0))
        assertNull(formatDlnaDuration(-1))
        assertEquals("0:00:05", formatDlnaDuration(5_000))
        assertEquals("1:01:01", formatDlnaDuration(3_661_000))
    }

    @Test
    fun soapFaultIsMappedToReadableReason() {
        val upnpFault = "<UPnPError><errorCode>706</errorCode><errorDescription>Invalid Media</errorDescription></UPnPError>"
        assertEquals("设备不支持该媒体格式", parseDlnaFault(upnpFault))
        val unknownCode = "<UPnPError><errorCode>999</errorCode><errorDescription>boom</errorDescription></UPnPError>"
        assertEquals("设备返回：boom", parseDlnaFault(unknownCode))
        assertNull(parseDlnaFault("<html>gateway junk</html>"))
    }

    @Test
    fun seekUnitsFallBackInOrder() {
        assertEquals(listOf("REL_TIME", "ABS_TIME"), DLNA_SEEK_UNITS)
    }
}
