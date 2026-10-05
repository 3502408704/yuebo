package com.example.local_music_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFreeSourceTest {

    @Test
    fun disablingSourceKeepsItsInstalledIdentityAndOnlyChangesEnabled() {
        val original = MusicFreeSource(
            id = "qq-source",
            name = "QQ",
            url = "https://example.test/qq.js",
            version = "1",
            enabled = true,
            localFile = "qq.js",
        )

        val disabled = setMusicFreeSourceEnabled(listOf(original), original.id, false).single()

        assertFalse(disabled.enabled)
        assertEquals(original.copy(enabled = false), disabled)
        assertEquals(original.id, disabled.id)
        assertEquals(original.url, disabled.url)
        assertEquals(original.localFile, disabled.localFile)

        val enabled = setMusicFreeSourceEnabled(listOf(disabled), original.id, true).single()
        assertTrue(enabled.enabled)
        assertEquals(original, enabled)
    }

    @Test
    fun nativeQualityNamesFollowPlatformVocabulary() {
        assertEquals("黑胶", streamQualityLabel("netease", "flac24bit"))
        assertEquals("母带", streamQualityLabel("qq", "hires"))
        assertEquals("无损 FLAC", streamQualityLabel("qq", "flac"))
    }
}
