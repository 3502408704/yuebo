package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualizerTest {
    @Test
    fun legacy_five_band_eq_maps_to_ten_band_positions() {
        val legacy = listOf(5f, 4f, 3f, 2f, 1f)

        val migrated = migrateEqBands(legacy)

        assertEquals(10, migrated.size)
        assertEquals(5f, migrated[1])  // 60 -> 62
        assertEquals(4f, migrated[3])  // 230 -> 250
        assertEquals(3f, migrated[5])  // 910 -> 1k
        assertEquals(2f, migrated[7])  // 3.6k -> 4k
        assertEquals(1f, migrated[9])  // 14k -> 16k
        assertEquals(0f, migrated[0])
        assertEquals(0f, migrated[2])
    }

    @Test
    fun short_or_empty_legacy_is_safe() {
        assertEquals(10, migrateEqBands(emptyList()).size)
        assertEquals(listOf(0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 0f, 0f), migrateEqBands(listOf(0f, 3f)))
    }

    @Test
    fun audio_effects_enabled_only_when_any_effect_above_zero() {
        assertFalse(AudioEffects().enabled)
        assertFalse(AudioEffects(reverb = 0, chorus = 0, echo = 0, flanger = 0).enabled)
        assertTrue(AudioEffects(reverb = 1).enabled)
        assertTrue(AudioEffects(flanger = 50).enabled)
        assertFalse(AudioEffects(reverb = -1).enabled)
    }
}
