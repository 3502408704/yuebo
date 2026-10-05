package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BaiduPanStreamProxyTest {
    @Test
    fun parseRangeAcceptsOpenEndedRange() {
        val range = parseBaiduPanProxyRange("bytes=100-", 1000)
        assertEquals(100L, range?.start)
        assertEquals(999L, range?.end)
    }

    @Test
    fun parseRangeAcceptsClosedRange() {
        val range = parseBaiduPanProxyRange("bytes=0-499", 1000)
        assertEquals(0L to 499L, range?.start to range?.end)
    }

    @Test
    fun parseRangeClampsEndToTotal() {
        val range = parseBaiduPanProxyRange("bytes=900-2000", 1000)
        assertEquals(900L to 999L, range?.start to range?.end)
    }

    @Test
    fun parseRangeRejectsInvalidOrUnsatisfiable() {
        assertNull(parseBaiduPanProxyRange("bytes=abc", 1000))
        assertNull(parseBaiduPanProxyRange("bytes=100-50", 1000))
        assertNull(parseBaiduPanProxyRange("bytes=2000-", 1000))
        assertNull(parseBaiduPanProxyRange(null, 1000))
    }

    @Test
    fun contentRangeFormat() {
        assertEquals("bytes 100-199/1000", baiduPanContentRange(100, 199, 1000))
    }

    @Test
    fun redirectUrlResolvesAbsoluteLocation() {
        assertEquals("https://d.pcs.baidu.com/file/abc?sign=x", resolveBaiduPanRedirectUrl("https://pan.baidu.com/rest/2.0/xpan/multimedia?dlink=1", "https://d.pcs.baidu.com/file/abc?sign=x"))
    }

    @Test
    fun redirectUrlResolvesRelativeLocation() {
        assertEquals("https://pan.baidu.com/file/abc", resolveBaiduPanRedirectUrl("https://pan.baidu.com/s/1?a=b", "/file/abc"))
    }
}
