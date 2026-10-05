package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkSeekRetryPolicyTest {
    @Test
    fun retriesBufferedSeekForFiveSeconds() {
        assertEquals(100L, networkSeekRetryDelayMs(0))
        assertEquals(100L, networkSeekRetryDelayMs(49))
        assertNull(networkSeekRetryDelayMs(50))
    }
}
