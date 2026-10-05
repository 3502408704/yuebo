package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicFreePluginCompatibilityTest {

    @Test
    fun collectionDetailsReadMusicList() {
        val items = musicFreePageItems(
            mapOf("musicList" to listOf(mapOf("id" to "song-1"), "invalid")),
        )

        assertEquals(listOf("song-1"), items.map { it["id"] })
    }

    @Test
    fun missingPageListIsEmpty() {
        assertTrue(musicFreePageItems(mapOf("isEnd" to true)).isEmpty())
    }

    @Test
    fun qualityUsesPluginDeclaredValues() {
        val qualities = listOf("128k", "192k", "320k", "hires", "dolby")

        assertEquals("128k", musicFreeQualityValue(qualities, StreamQuality.Low))
        assertEquals("192k", musicFreeQualityValue(qualities, StreamQuality.Standard))
        assertEquals("320k", musicFreeQualityValue(qualities, StreamQuality.High))
        assertEquals("dolby", musicFreeQualityValue(qualities, StreamQuality.Super))
    }

    @Test
    fun qualityFallsBackToMusicFreeTierWithoutMetadata() {
        assertEquals("super", musicFreeQualityValue(emptyList(), StreamQuality.Super))
    }
}
