package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PanAccountStoreLogicTest {
    @Test
    fun removingNonActiveAccountKeepsActive() {
        assertEquals("a", resolveActiveAfterRemove("a", "b", listOf("a", "c")))
    }

    @Test
    fun removingActiveAccountSwitchesToFirstRemaining() {
        assertEquals("b", resolveActiveAfterRemove("a", "a", listOf("b", "c")))
    }

    @Test
    fun removingLastAccountClearsActive() {
        assertNull(resolveActiveAfterRemove("a", "a", emptyList()))
    }

    @Test
    fun removingAccountWithNoActiveStaysNull() {
        assertNull(resolveActiveAfterRemove(null, "b", listOf("a", "c")))
    }
}

