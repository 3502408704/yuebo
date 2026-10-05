package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoudnessNormalizationTest {
    @Test
    fun r128GainUsesItsMinus23LufsReference() {
        assertEquals(
            12f,
            loudnessGainDb(listOf("R128_TRACK_GAIN=1280"), targetLufs = -16f)!!,
            1e-3f,
        )
    }

    @Test
    fun replayGainUsesItsMinus18LufsReference() {
        assertEquals(
            -5.5f,
            loudnessGainDb(listOf("REPLAYGAIN_TRACK_GAIN=-7.5 dB"), targetLufs = -16f)!!,
            1e-3f,
        )
    }

    @Test
    fun peakMetadataCapsGainBelowTheSafetyCeiling() {
        assertEquals(0f, limitLoudnessGainDb(6f, peak = 0.8912509f), 1e-3f)
        assertEquals(0f, limitLoudnessGainDb(6f, peak = null), 1e-3f)
    }

    @Test
    fun missingLoudnessMetadataDoesNotChangeTheTrack() {
        assertNull(loudnessGainDb(emptyList(), targetLufs = -16f))
    }

    @Test
    fun integratedLoudnessIgnoresBlocksBelowTheAbsoluteGate() {
        assertEquals(-0.691f, integratedLufs(listOf(1.0, 1e-10))!!, 1e-3f)
    }

    @Test
    fun analyzedLoudnessUsesTheSamePeakSafetyLimit() {
        assertEquals(6f, analyzedLoudnessGainDb(-22f, targetLufs = -16f, peak = 0.8912509f), 1e-3f)
        assertEquals(12f, normalizationGainDb(20f), 1e-3f)
    }

    @Test
    fun migratesLegacyPeakSettingToAReasonableLufsTarget() {
        assertEquals(-160, migrateLoudnessTarget(-23, alreadyLufs = false))
        assertEquals(-23, migrateLoudnessTarget(-23, alreadyLufs = true))
        assertEquals(0, migrateLoudnessTarget(0, alreadyLufs = false))
    }
}
