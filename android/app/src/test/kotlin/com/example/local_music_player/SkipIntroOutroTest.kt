package com.example.local_music_player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跳过片头片尾总开关：关闭时不得下发任何跳过秒数（关闭即清零生效），开启时按存储值下发。
 */
class SkipIntroOutroTest {

    @Test fun disabledForcesZeroEvenWhenStored() {
        assertEquals(0f to 0f, gatedSkipSeconds(enabled = false, headSeconds = 30f, tailSeconds = 45f))
    }

    @Test fun enabledPassesStoredValuesThrough() {
        assertEquals(30f to 45f, gatedSkipSeconds(enabled = true, headSeconds = 30f, tailSeconds = 45f))
    }

    @Test fun enabledKeepsHalfSecondPrecision() {
        assertEquals(4.5f to 90.5f, gatedSkipSeconds(enabled = true, headSeconds = 4.5f, tailSeconds = 90.5f))
    }

    @Test fun negativeValuesAreClampedWhenEnabled() {
        assertEquals(0f to 0f, gatedSkipSeconds(enabled = true, headSeconds = -1f, tailSeconds = -2f))
    }

    @Test fun enabledZeroStaysZero() {
        assertEquals(0f to 0f, gatedSkipSeconds(enabled = true, headSeconds = 0f, tailSeconds = 0f))
    }
}
