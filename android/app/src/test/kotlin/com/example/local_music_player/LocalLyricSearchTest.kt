package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLyricSearchTest {

    @Test
    fun strips_file_extension_from_local_display_name() {
        assertEquals("龙魂-艾米尔", localLyricSearchTitle("龙魂-艾米尔.flac"))
        assertEquals("My Song 2024", localLyricSearchTitle("My Song 2024.mp3"))
        assertEquals("name.with.dots", localLyricSearchTitle("name.with.dots.m4a"))
    }

    @Test
    fun strips_track_number_and_known_artist_prefix() {
        assertEquals("最伟大的作品", localLyricSearchTitle("11 - 周杰伦 - 最伟大的作品.flac", "周杰伦"))
        assertEquals("Mojito", localLyricSearchTitle("08 - 周杰伦 - Mojito.flac", "周杰伦"))
        assertEquals("白云长在天", localLyricSearchTitle("002.费玉清 - 白云长在天.wav", "费玉清"))
        assertEquals("A Matter of Time", localLyricSearchTitle("Alexandra Lilly - A Matter of Time.flac", "Alexandra Lilly"))
    }

    @Test
    fun leaves_title_untouched_when_artist_is_unknown_or_blank() {
        assertEquals("费玉清 - 白云长在天", localLyricSearchTitle("002.费玉清 - 白云长在天.wav", "<unknown>"))
        assertEquals("Annie's Wonderland-Bandari", localLyricSearchTitle("Annie's Wonderland-Bandari.mp3"))
        assertEquals("My Song", localLyricSearchTitle("My Song"))
    }

    @Test
    fun keeps_extension_free_title_and_trims_whitespace() {
        assertEquals("song", localLyricSearchTitle(" song.mp3 "))
        assertEquals("", localLyricSearchTitle(""))
    }
}
