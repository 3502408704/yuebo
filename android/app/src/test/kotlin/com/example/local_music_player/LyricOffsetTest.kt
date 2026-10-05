package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricOffsetTest {
    @Test
    fun steps_by_one_second_and_clamps_to_two_seconds() {
        assertEquals(1_000L, stepLyricOffset(0L, 1))
        assertEquals(-1_000L, stepLyricOffset(0L, -1))
        assertEquals(2_000L, stepLyricOffset(2_000L, 1))
        assertEquals(-2_000L, stepLyricOffset(-2_000L, -1))
    }

    @Test
    fun announces_direction_and_zero() {
        assertEquals("不调整", lyricOffsetAnnouncement(0L))
        assertEquals("提前 1 秒", lyricOffsetAnnouncement(1_000L))
        assertEquals("延后 2 秒", lyricOffsetAnnouncement(-2_000L))
    }

    @Test
    fun legacy_fractional_offsets_snap_to_nearest_second() {
        assertEquals(1_000L, normalizeLyricOffset(600L))
        assertEquals(-1_000L, normalizeLyricOffset(-600L))
        assertEquals("提前 1 秒", lyricOffsetAnnouncement(1_499L))
        assertEquals(2_000L, stepLyricOffset(1_499L, 1))
    }
}
