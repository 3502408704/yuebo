package com.example.local_music_player

import java.io.File

/**
 * 断点续合的磁盘层（2026-09-28 起）：内存 [TtsRoleChunks] 只活到进程死，长篇合成中途
 * 被杀就要整轨重烧配额。这里按「角色 + 配置/台词指纹」把已完成段落落盘，进程重启后
 * 同指纹轨道恢复已完成段，只补缺失片段。
 *
 * 布局：`<root>/tts-chunks/<角色slug>/<configHash>-<textsHash>/seg_<index>.<fmt>`。
 * 写入先落 `.part` 再改名，避免半截文件被当成有效段（恢复时另有解码校验兜底）。
 * 只保留各角色当前指纹的目录（换配置/改台词自动清理旧指纹）；整轨拼接入库后清空。
 * 磁盘操作尽力而为：失败只影响本次续合能力，不影响合成主流程。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，不参与编译。
 */
internal class TtsChunkStore(cacheRoot: File) {
    private val rootDir = File(cacheRoot, "tts-chunks")

    fun restore(role: String, store: TtsRoleChunks) {
        val dir = roleDir(role, store.configHash, store.textsHash)
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (file in files) {
            if (!file.isFile || file.name.startsWith("seg_").not()) continue
            val index = file.nameWithoutExtension.removePrefix("seg_").toIntOrNull() ?: continue
            val format = file.extension.takeIf { it == "wav" || it == "mp3" } ?: continue
            if (index !in store.chunks.indices || store.chunks[index] != null) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            store.put(index, TtsSegmentAudio(bytes, format, "disk-resume"))
        }
    }

    fun save(role: String, store: TtsRoleChunks, index: Int) {
        val bytes = store.chunks[index] ?: return
        val format = store.formats[index] ?: return
        runCatching {
            val dir = roleDir(role, store.configHash, store.textsHash)
            dir.mkdirs()
            val target = File(dir, "seg_$index.$format")
            val part = File(dir, "seg_$index.$format.part")
            part.writeBytes(bytes)
            if (!part.renameTo(target)) part.delete()
        }
    }

    /** 只保留当前指纹目录：换配置/改台词后旧指纹磁盘段不再有用，清掉防缓存目录无限增长。 */
    fun retainOnly(role: String, configHash: Int, textsHash: Int) {
        runCatching {
            val roleDirectory = File(rootDir, slug(role))
            roleDirectory.listFiles()?.forEach { entry ->
                if (entry.name != "$configHash-$textsHash") entry.deleteRecursively()
            }
        }
    }

    fun clearRole(role: String, configHash: Int, textsHash: Int) {
        runCatching { roleDir(role, configHash, textsHash).deleteRecursively() }
    }

    private fun roleDir(role: String, configHash: Int, textsHash: Int): File =
        File(File(rootDir, slug(role)), "$configHash-$textsHash")

    private fun slug(role: String): String =
        role.filter { it.isLetterOrDigit() }.ifBlank { "role" }.take(64)
}
