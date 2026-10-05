package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals

class BaiduPanDownloadTest {
    @Test
    fun uniqueNameKeepsOriginalWhenFree() {
        assertEquals("a.mp3", uniqueDownloadName(emptySet(), "a.mp3"))
        assertEquals("a.mp3", uniqueDownloadName(setOf("b.mp3"), "a.mp3"))
    }

    @Test
    fun uniqueNameAppendsSuffixForConflicts() {
        assertEquals("a (1).mp3", uniqueDownloadName(setOf("a.mp3"), "a.mp3"))
        assertEquals("a (2).mp3", uniqueDownloadName(setOf("a.mp3", "a (1).mp3"), "a.mp3"))
    }

    @Test
    fun uniqueNameHandlesNoExtension() {
        assertEquals("a (1)", uniqueDownloadName(setOf("a"), "a"))
    }

    @Test
    fun isPanAudioNameRecognizesCommonFormats() {
        assertEquals(true, isPanAudioFileName("song.flac"))
        assertEquals(true, isPanAudioFileName("song.MP3"))
        assertEquals(true, isPanAudioFileName("song.m4a"))
        assertEquals(false, isPanAudioFileName("song.pdf"))
        assertEquals(false, isPanAudioFileName("folder"))
    }
}
