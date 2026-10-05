package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimpleLruCacheTest {
    @Test
    fun evictsLeastRecentlyUsedWhenOverCapacity() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")
        cache.put("c", 3)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun evictsOldestWhenOverWeight() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 10, maxWeight = 10, weightOf = { it.toLong() })
        cache.put("a", 6)
        cache.put("b", 5)
        assertNull(cache.get("a"))
        assertEquals(5, cache.get("b"))
    }

    @Test
    fun replacingSameKeyKeepsSingleEntry() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 2, weightOf = { it.toLong() })
        cache.put("a", 5)
        cache.put("a", 3)
        assertEquals(1, cache.size)
        cache.put("b", 1)
        assertEquals(2, cache.size)
    }

    @Test
    fun removeAndClearReleaseWeight() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 10, maxWeight = 10, weightOf = { it.toLong() })
        cache.put("a", 6)
        cache.put("b", 4)
        cache.remove("a")
        cache.put("c", 5)
        assertEquals(5, cache.get("c"))
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("b"))
    }
}
