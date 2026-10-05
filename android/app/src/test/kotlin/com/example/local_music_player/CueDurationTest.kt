package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals

class CueDurationTest {
    @Test
    fun cueSegmentDurationUsesNextStartOrSourceEndAndRejectsInvalidSegments() {
        assertEquals(30_000, cueSegmentDurationMs(120_000, 60_000, 90_000))
        assertEquals(60_000, cueSegmentDurationMs(120_000, 60_000, null))
        assertEquals(0, cueSegmentDurationMs(0, 0, null))
        assertEquals(0, cueSegmentDurationMs(120_000, 90_000, 90_000))
        assertEquals(0, cueSegmentDurationMs(120_000, 130_000, null))
    }
}
