package com.example.local_music_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 断点续合磁盘层：同指纹恢复、异指纹不串、清理解耦于内存缓存。
 * 【2026-10-05 归档】随 TTS 工作台源码一并存档，不参与编译。 */
class TtsChunkStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveThenRestoreRoundtrip() {
        val store = TtsChunkStore(tmp.newFolder())
        val memory = TtsRoleChunks(configHash = 11, textsHash = 22, chunkCount = 3)
        memory.put(1, TtsSegmentAudio(byteArrayOf(1, 2, 3), "wav", "relay"))
        store.save("小狼", memory, 1)

        val restored = TtsRoleChunks(configHash = 11, textsHash = 22, chunkCount = 3)
        store.restore("小狼", restored)
        assertEquals(byteArrayOf(1, 2, 3).toList(), restored.chunks[1]?.toList())
        assertEquals("wav", restored.formats[1])
        assertNull(restored.chunks[0])
    }

    @Test
    fun restoreIgnoresDifferentFingerprintAndForeignFiles() {
        // 与手工构造的磁盘路径对齐：store 直接用 tmp.root 作为缓存根
        val store = TtsChunkStore(tmp.root)
        val memory = TtsRoleChunks(configHash = 1, textsHash = 2, chunkCount = 1)
        memory.put(0, TtsSegmentAudio(byteArrayOf(9), "wav", "relay"))
        store.save("role", memory, 0)

        val otherFingerprint = TtsRoleChunks(configHash = 3, textsHash = 4, chunkCount = 1)
        store.restore("role", otherFingerprint)
        assertNull(otherFingerprint.chunks[0])

        val dir = java.io.File(tmp.root, "tts-chunks/role/1-2")
        dir.mkdirs()
        java.io.File(dir, "seg_0.exe").writeBytes(byteArrayOf(8))
        val target = TtsRoleChunks(configHash = 1, textsHash = 2, chunkCount = 1)
        store.restore("role", target)
        // 只恢复有效格式（wav/mp3），陌生扩展名忽略
        assertEquals(byteArrayOf(9).toList(), target.chunks[0]?.toList())
        assertEquals("wav", target.formats[0])
    }

    @Test
    fun retainOnlyDropsStaleFingerprints() {
        val store = TtsChunkStore(tmp.newFolder())
        val old = TtsRoleChunks(configHash = 1, textsHash = 1, chunkCount = 1)
        old.put(0, TtsSegmentAudio(byteArrayOf(1), "wav", "relay"))
        store.save("role", old, 0)
        store.retainOnly("role", configHash = 5, textsHash = 6)

        val stale = TtsRoleChunks(configHash = 1, textsHash = 1, chunkCount = 1)
        store.restore("role", stale)
        assertNull(stale.chunks[0])
        val fresh = TtsRoleChunks(configHash = 5, textsHash = 6, chunkCount = 1)
        fresh.put(0, TtsSegmentAudio(byteArrayOf(7), "wav", "relay"))
        store.save("role", fresh, 0)
        val restored = TtsRoleChunks(configHash = 5, textsHash = 6, chunkCount = 1)
        store.restore("role", restored)
        assertTrue(restored.completedCount == 1)
    }

    @Test
    fun clearRoleRemovesDirectory() {
        val store = TtsChunkStore(tmp.newFolder())
        val memory = TtsRoleChunks(configHash = 1, textsHash = 2, chunkCount = 1)
        memory.put(0, TtsSegmentAudio(byteArrayOf(1), "mp3", "relay"))
        store.save("role", memory, 0)
        store.clearRole("role", 1, 2)
        val after = TtsRoleChunks(configHash = 1, textsHash = 2, chunkCount = 1)
        store.restore("role", after)
        assertFalse(after.isComplete)
    }
}
