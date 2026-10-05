package com.example.local_music_player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerRoutingTest {
    @Test fun stale_online_detail_returns_to_library_after_now_playing() {
        assertEquals(
            Screen.Library,
            screenAfterNowPlayingReturn(Screen.OnlineCollectionDetail, hasOnlineDetail = false),
        )
    }

    @Test fun valid_online_detail_and_other_origins_keep_their_return_target() {
        assertEquals(
            Screen.OnlineCollectionDetail,
            screenAfterNowPlayingReturn(Screen.OnlineCollectionDetail, hasOnlineDetail = true),
        )
        assertEquals(
            Screen.Album,
            screenAfterNowPlayingReturn(Screen.Album, hasOnlineDetail = false),
        )
    }

    @Test fun streamingMimeAliasesAndParametersAreRecognized() {
        for (mime in listOf("m3u8", "audio/x-mpegurl", "Application/Vnd.Apple.MpegURL; charset=UTF-8")) {
            assertEquals(MimeTypes.APPLICATION_M3U8, normalizeVideoMimeType(mime))
        }
        assertEquals(MimeTypes.APPLICATION_MPD, normalizeVideoMimeType("MPD"))
        assertEquals(MimeTypes.APPLICATION_RTSP, inferStreamMimeType("RTSP", "/camera"))
        assertEquals(MimeTypes.APPLICATION_M3U8, inferStreamMimeType("https", "/live/INDEX.M3U8"))
        assertEquals(MimeTypes.APPLICATION_MPD, inferStreamMimeType("https", "/manifest.mpd"))
        assertNull(normalizeVideoMimeType("application/octet-stream"))
        assertNull(inferStreamMimeType("https", "/video.mp4"))
    }

    @Test fun hlsCachesOnlyMediaRegardlessOfUrlSuffix() {
        assertFalse(cacheHlsDataType(C.DATA_TYPE_MANIFEST))
        assertFalse(cacheHlsDataType(C.DATA_TYPE_DRM))
        assertTrue(cacheHlsDataType(C.DATA_TYPE_MEDIA))
        assertTrue(cacheHlsDataType(C.DATA_TYPE_MEDIA_INITIALIZATION))
    }

    @Test fun longTailAudioAndProtocolBoundariesMatchNativeBuild() {
        for (format in listOf("APE", "wv", "dsf", "dff", "tta", "audio/x-ms-wma; charset=utf-8", "audio/alac")) {
            assertTrue(isFfmpegAudioType(format), format)
        }
        assertFalse(isFfmpegAudioType("audio/mpeg"))
        assertTrue(canOpenWithFfmpeg("file", "/song.ape"))
        assertTrue(canOpenWithFfmpeg("content", "/media/123"))
        assertTrue(canOpenWithFfmpeg("http", "/song.wv"))
        // https 已随原生构建编入 tls/https 协议（2026-09-18 起），RMVB/PS 在 https 直链可兜底。
        assertTrue(canOpenWithFfmpeg("https", "/song.ape"))
        assertFalse(canOpenWithFfmpeg("rtsp", "/camera"))
        assertFalse(canOpenWithFfmpeg("http", "/live.m3u8"))
        assertFalse(canOpenWithFfmpeg("https", "/live.m3u8"))
        assertFalse(canOpenWithFfmpeg("http", "/manifest.mpd"))
        assertFalse(canOpenWithFfmpeg("online", "/track"))
    }

    @Test fun importsExposeNewNativeFormats() {
        for (format in listOf("tta", "wma", "tak", "mpc", "mpc8", "ac3", "eac3", "dts", "truehd", "mlp", "caf")) {
            assertTrue(format in LOCAL_AUDIO_EXTENSIONS, format)
            assertTrue(isFfmpegAudioType(format), format)
        }
    }
}
