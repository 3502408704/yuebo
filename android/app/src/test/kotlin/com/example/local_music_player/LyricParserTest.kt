package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricParserTest {

    @Test
    fun parses_minute_second_with_centiseconds() {
        val lines = parseLyrics("[00:12.34]hello")
        assertEquals(listOf(LyricLine(12_340, "hello")), lines)
    }

    @Test
    fun parses_without_fraction() {
        val lines = parseLyrics("[00:12]hello")
        assertEquals(listOf(LyricLine(12_000, "hello")), lines)
    }

    @Test
    fun parses_millisecond_precision() {
        val lines = parseLyrics("[00:12.345]hello")
        assertEquals(listOf(LyricLine(12_345, "hello")), lines)
    }

    @Test
    fun parses_single_digit_fraction_as_centiseconds() {
        val lines = parseLyrics("[00:12.3]hello")
        assertEquals(listOf(LyricLine(12_300, "hello")), lines)
    }

    @Test
    fun parses_minutes_beyond_one() {
        val lines = parseLyrics("[01:02.03]hello")
        assertEquals(listOf(LyricLine(62_030, "hello")), lines)
    }

    @Test
    fun parses_netease_credit_line_with_suffix() {
        val lines = parseLyrics("[00:00.00-1] 作曲 : 方文山")
        assertEquals(listOf(LyricLine(0, "作曲 : 方文山")), lines)
    }

    @Test
    fun expands_multiple_timestamps_on_one_line() {
        val lines = parseLyrics("[00:10.00][00:45.00]repeat")
        assertEquals(
            listOf(LyricLine(10_000, "repeat"), LyricLine(45_000, "repeat")),
            lines,
        )
    }

    @Test
    fun ignores_metadata_tags_without_numeric_time() {
        val lines = parseLyrics("[ti:标题]\n[ar:歌手]\n[al:专辑]\n[by:编辑]\n[00:12.34]歌词")
        assertEquals(listOf(LyricLine(12_340, "歌词")), lines)
    }

    @Test
    fun applies_positive_offset() {
        val lines = parseLyrics("[offset:500]\n[00:10.00]a")
        assertEquals(listOf(LyricLine(10_500, "a")), lines)
    }

    @Test
    fun applies_negative_offset_but_not_below_zero() {
        val lines = parseLyrics("[offset:-3000]\n[00:02.00]a\n[00:05.00]b")
        assertEquals(
            listOf(LyricLine(0, "a"), LyricLine(2_000, "b")),
            lines,
        )
    }

    @Test
    fun skips_plain_text_lines_without_timestamps() {
        val lines = parseLyrics("纯文本行\n[00:12.34]歌词")
        assertEquals(listOf(LyricLine(12_340, "歌词")), lines)
    }

    @Test
    fun skips_timestamp_only_lines() {
        val lines = parseLyrics("[00:50.17]\n[00:52.00]music")
        assertEquals(listOf(LyricLine(52_000, "music")), lines)
    }

    @Test
    fun trims_whitespace_around_text() {
        val lines = parseLyrics("[00:12.34]  歌词  ")
        assertEquals(listOf(LyricLine(12_340, "歌词")), lines)
    }

    @Test
    fun sorts_lines_by_time() {
        val lines = parseLyrics("[00:30.00]c\n[00:10.00]a\n[00:20.00]b")
        assertEquals(
            listOf(
                LyricLine(10_000, "a"),
                LyricLine(20_000, "b"),
                LyricLine(30_000, "c"),
            ),
            lines,
        )
    }

    @Test
    fun deduplicates_identical_lines() {
        val lines = parseLyrics("[00:10.00]a\n[00:10.00]a")
        assertEquals(listOf(LyricLine(10_000, "a")), lines)
    }

    @Test
    fun empty_and_blank_input_yield_no_lines() {
        assertTrue(parseLyrics(null).isEmpty())
        assertTrue(parseLyrics("").isEmpty())
        assertTrue(parseLyrics("   \n  ").isEmpty())
    }

    @Test
    fun handles_crlf_line_endings() {
        val lines = parseLyrics("[00:10.00]a\r\n[00:20.00]b")
        assertEquals(
            listOf(LyricLine(10_000, "a"), LyricLine(20_000, "b")),
            lines,
        )
    }

    @Test
    fun accepts_bom_json_wrapped_lyrics() {
        val lines = parseLyrics("\uFEFF{\"lyric\":\"[00:01.20]hello\"}")
        assertEquals(listOf(LyricLine(1_200, "hello")), lines)
    }

    @Test
    fun accepts_qrc_millisecond_tags() {
        val lines = parseLyrics("[120,500]hello\n[900,400]world")
        assertEquals(
            listOf(LyricLine(120, "hello"), LyricLine(900, "world")),
            lines,
        )
    }

    @Test
    fun accepts_leading_spaces_before_multiple_timestamps() {
        val lines = parseLyrics("  [00:01.00] [00:02.00]hello")
        assertEquals(
            listOf(LyricLine(1_000, "hello"), LyricLine(2_000, "hello")),
            lines,
        )
    }

    @Test
    fun lyric_bar_text_follows_the_current_timed_line() {
        val lyrics = "[00:10.00]第一句\n[00:20.00]第二句"
        assertEquals(null, lyricBarText(lyrics, 5_000))
        assertEquals("第一句", lyricBarText(lyrics, 15_000))
        assertEquals("第二句", lyricBarText(lyrics, 25_000))
    }

    @Test
    fun lyric_bar_text_keeps_plain_text_and_hides_blank_input() {
        assertEquals("纯文本歌词", lyricBarText("  纯文本歌词  ", 0))
        assertEquals(null, lyricBarText("   ", 0))
    }

    @Test
    fun active_index_before_first_line_is_negative() {
        val lines = listOf(LyricLine(10_000, "a"), LyricLine(20_000, "b"))
        assertEquals(-1, activeLyricIndex(lines, 5_000))
        assertEquals(-1, activeLyricIndex(emptyList(), 5_000))
    }

    @Test
    fun active_index_at_and_after_line_times() {
        val lines = listOf(LyricLine(10_000, "a"), LyricLine(20_000, "b"), LyricLine(30_000, "c"))
        assertEquals(0, activeLyricIndex(lines, 10_000))
        assertEquals(0, activeLyricIndex(lines, 15_000))
        assertEquals(1, activeLyricIndex(lines, 20_000))
        assertEquals(1, activeLyricIndex(lines, 25_000))
        assertEquals(2, activeLyricIndex(lines, 99_000))
    }
}
