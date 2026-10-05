package com.example.local_music_player

/** 轻量内存 LRU：超过条数或总重量上限时按最久未访问顺序逐出。线程安全。 */
internal class SimpleLruCache<K, V>(
    private val maxEntries: Int,
    private val maxWeight: Long = Long.MAX_VALUE,
    private val weightOf: (V) -> Long = { 1L },
) {
    private val values = object : LinkedHashMap<K, V>(0, 0.75f, true) {}
    private var totalWeight = 0L
    private val lock = Any()

    val size: Int get() = synchronized(lock) { values.size }

    fun get(key: K): V? = synchronized(lock) { values[key] }

    fun put(key: K, value: V) {
        synchronized(lock) {
            values.put(key, value)?.let { totalWeight -= weightOf(it) }
            totalWeight += weightOf(value)
            evict()
        }
    }

    fun remove(key: K) {
        synchronized(lock) {
            values.remove(key)?.let { totalWeight -= weightOf(it) }
        }
    }

    fun clear() {
        synchronized(lock) {
            values.clear()
            totalWeight = 0L
        }
    }

    private fun evict() {
        val iterator = values.entries.iterator()
        while (iterator.hasNext() && (values.size > maxEntries || totalWeight > maxWeight)) {
            val entry = iterator.next()
            totalWeight -= weightOf(entry.value)
            iterator.remove()
        }
    }
}
