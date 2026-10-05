package com.example.local_music_player

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** 在线音乐下载任务数据层：Room 是任务事实来源，实际传输由 [DownloadEngine] 负责。 */
class DownloadRepository(
    private val context: Context,
    private val resolver: ContentResolver,
    private val dao: DownloadTaskDao,
) {
    private val taskMutex = Mutex()

    val tasks: Flow<List<DownloadTask>> = dao.observeAll().map { entries -> entries.mapNotNull(::toTask) }

    suspend fun enqueue(track: OnlineTrack, qualityApiValue: String, folderName: String? = null, trackNo: Int? = null): DownloadTask = taskMutex.withLock {
        // Room 列沿用旧存储：platform=插件平台名、query=源 id（pluginId）、sequence 恒 0（不再参与键）。
        val unfinished = dao.findUnfinishedOnlineTask(track.platform, track.pluginId, 0, track.platformId)
        unfinished?.let(::toTask)?.let { return it }
        if (folderName != null) {
            dao.findCompletedOnlineTask(track.platform, track.pluginId, 0, track.platformId)
                ?.takeIf { it.folderName == folderName }?.let(::toTask)?.let { return it }
        }
        val id = UUID.randomUUID().toString()
        val extension = "mp3" // 解析到真实地址后由 updateFormat 按实际格式修正
        val entity = DownloadTaskEntity(
            id = id,
            platform = track.platform,
            query = track.pluginId,
            sequence = 0,
            platformId = track.platformId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            folderName = folderName,
            trackNo = trackNo,
            lyrics = null,
            mvId = null,
            artworkUrl = track.artworkUrl,
            requestedQuality = qualityApiValue,
            mimeType = downloadMimeType(extension),
            extension = extension,
            mediaStoreUri = null,
            downloadedBytes = 0,
            totalBytes = 0,
            etag = null,
            status = DownloadTaskStatus.QUEUED.name,
            error = null,
            retryCount = 0,
            nextRetryAtMs = null,
            createdAtMs = System.currentTimeMillis(),
            completedAtMs = null,
        )
        dao.upsert(entity)
        return requireNotNull(toTask(entity))
    }

    suspend fun pause(id: String) = taskMutex.withLock { updateStatusLocked(id, DownloadTaskStatus.PAUSED) }

    suspend fun resume(id: String) = taskMutex.withLock { updateStatusLocked(id, DownloadTaskStatus.QUEUED, resetRetry = true) }

    suspend fun retry(id: String) = taskMutex.withLock { updateStatusLocked(id, DownloadTaskStatus.QUEUED, resetRetry = true) }

    suspend fun find(id: String): DownloadTask? = dao.findById(id)?.let(::toTask)

    fun tempFile(id: String): File = File(context.cacheDir, "online_downloads/$id.part")

    suspend fun unfinished(): List<DownloadTask> = dao.findByStatuses(
        listOf(
            DownloadTaskStatus.QUEUED.name,
            DownloadTaskStatus.DOWNLOADING.name,
            DownloadTaskStatus.WAITING_NETWORK.name,
        ),
    ).mapNotNull(::toTask)

    suspend fun recoverAfterServiceRestart() = taskMutex.withLock {
        dao.findByStatuses(listOf(DownloadTaskStatus.DOWNLOADING.name)).forEach { entity ->
            dao.upsert(entity.copy(status = DownloadTaskStatus.QUEUED.name, error = null))
        }
    }

    suspend fun markDownloading(id: String): Boolean = taskMutex.withLock {
        updateStatusLocked(id, DownloadTaskStatus.DOWNLOADING)
    }

    /** 解析到真实下载地址后，按真实格式修正扩展名与 MIME（未完成的任务；不影响传输中的临时文件）。 */
    suspend fun updateFormat(id: String, extension: String, mimeType: String): Unit = taskMutex.withLock {
        val entity = dao.findById(id) ?: return
        if (entity.status == DownloadTaskStatus.COMPLETED.name) return
        val ext = extension.lowercase().ifBlank { entity.extension }
        val mime = mimeType.ifBlank { downloadMimeType(ext) }
        if (ext == entity.extension && mime == entity.mimeType) return
        dao.upsert(entity.copy(extension = ext, mimeType = mime))
    }

    /** 解析到真实地址后保存歌词，发布时与音频一起写入 .lrc。 */
    suspend fun updateLyrics(id: String, lyrics: String?): Unit = taskMutex.withLock {
        val entity = dao.findById(id) ?: return
        if (entity.status == DownloadTaskStatus.COMPLETED.name) return
        dao.upsert(entity.copy(lyrics = lyrics))
    }

    suspend fun markProgress(id: String, downloadedBytes: Long, totalBytes: Long, etag: String?): Unit = taskMutex.withLock {
        val entity = dao.findById(id) ?: return
        if (entity.status != DownloadTaskStatus.DOWNLOADING.name) return
        dao.upsert(entity.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes, etag = etag))
    }

    suspend fun markFailure(id: String, message: String): Unit = taskMutex.withLock {
        val entity = dao.findById(id) ?: return
        if (!canFinalizeDownloadFailure(DownloadTaskStatus.valueOf(entity.status))) return
        dao.upsert(entity.copy(status = DownloadTaskStatus.FAILED.name, error = message, nextRetryAtMs = null))
    }

    /** 传输完成后把缓存临时文件发布到媒体库并标记完成；失败则标记失败。 */
    suspend fun publishAndComplete(id: String, temp: File): Boolean = taskMutex.withLock {
        val entity = dao.findById(id) ?: return false
        if (entity.status != DownloadTaskStatus.DOWNLOADING.name) return false
        entity.mediaStoreUri?.let { runCatching { deletePendingMedia(Uri.parse(it)) } }
        val uri = runCatching { publishOnline(temp, entity) }.getOrElse { error ->
            dao.upsert(entity.copy(status = DownloadTaskStatus.FAILED.name, error = downloadFailureMessage(error), nextRetryAtMs = null))
            return false
        }
        dao.upsert(entity.copy(
            mediaStoreUri = uri.toString(),
            downloadedBytes = temp.length().coerceAtLeast(entity.downloadedBytes),
            status = DownloadTaskStatus.COMPLETED.name,
            error = null,
            nextRetryAtMs = null,
            completedAtMs = System.currentTimeMillis(),
        ))
        true
    }

    suspend fun cancel(id: String): Unit = taskMutex.withLock {
        val entity = dao.findById(id) ?: return
        if (entity.status == DownloadTaskStatus.COMPLETED.name) return
        entity.mediaStoreUri?.let { runCatching { deletePendingMedia(Uri.parse(it)) } }
        tempFile(id).delete()
        dao.deleteById(id)
    }

    suspend fun removeCompletedRecord(id: String) = taskMutex.withLock {
        if (dao.findById(id)?.status == DownloadTaskStatus.COMPLETED.name) dao.deleteById(id)
    }

    private fun publishOnline(temp: File, entity: DownloadTaskEntity): Uri {
        val folder = entity.folderName?.trim().orEmpty()
        val relativePath = if (folder.isEmpty()) "$DOWNLOAD_BASE_RELATIVE_PATH/" else "$DOWNLOAD_BASE_RELATIVE_PATH/$folder/"
        val mimeType = entity.mimeType.ifBlank { downloadMimeType(entity.extension) }
        val displayName = downloadDisplayName(entity.artist, entity.title, entity.extension, entity.trackNo)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStorageDirectory(), relativePath)
            dir.mkdirs()
            val destination = File(dir, displayName)
            if (!temp.renameTo(destination)) throw IllegalStateException("无法完成下载文件")
            entity.lyrics?.takeIf(String::isNotBlank)?.let { lyrics ->
                File(dir, displayName.substringBeforeLast('.').trim() + ".lrc").writeText(lyrics, Charsets.UTF_8)
            }
            return resolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, destination.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, destination.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                },
            ) ?: Uri.fromFile(destination)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入媒体库")
        resolver.openOutputStream(uri, "w")?.use { output ->
            temp.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法写入媒体库")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        entity.lyrics?.takeIf(String::isNotBlank)?.let { lyrics ->
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName.substringBeforeLast('.').trim() + ".lrc")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val lrcUri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                lrcUri?.let { u ->
                    resolver.openOutputStream(u, "w")?.use { it.write(lyrics.toByteArray(Charsets.UTF_8)) }
                    resolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
            }
        }
        return uri
    }

    /** MV 任务发布路径已随在线 MV 一并移除（最终版仅保留在线音频下载）。 */

    private fun deletePendingMedia(uri: Uri) {
        if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path?.let(::File)?.delete() else resolver.delete(uri, null, null)
    }

    private suspend fun updateStatusLocked(id: String, status: DownloadTaskStatus, resetRetry: Boolean = false): Boolean {
        val entity = dao.findById(id) ?: return false
        val currentStatus = DownloadTaskStatus.valueOf(entity.status)
        if (!canTransitionDownloadTask(currentStatus, status)) return false
        dao.upsert(entity.copy(
            status = status.name,
            error = null,
            retryCount = if (resetRetry) 0 else entity.retryCount,
            nextRetryAtMs = null,
        ))
        return true
    }

    private fun toTask(entity: DownloadTaskEntity): DownloadTask? {
        // 旧库行缺源 id（pluginId 存 query 列）或缺平台内 id 时无法回溯音源，丢弃失效任务。
        val pluginId = entity.query
        val trackId = entity.platformId
        if (pluginId.isBlank() || trackId.isNullOrBlank()) return null
        return DownloadTask(
            id = entity.id,
            track = OnlineTrack(
                pluginId = pluginId,
                platform = entity.platform,
                sourceName = entity.platform,
                platformId = trackId,
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                artworkUrl = entity.artworkUrl,
                durationMs = null,
            ),
            qualityApiValue = entity.requestedQuality,
            mimeType = entity.mimeType,
            extension = entity.extension,
            folderName = entity.folderName,
            trackNo = entity.trackNo,
            lyrics = entity.lyrics,
            mediaStoreUri = entity.mediaStoreUri?.let(Uri::parse),
            downloadedBytes = entity.downloadedBytes,
            totalBytes = entity.totalBytes,
            etag = entity.etag,
            status = DownloadTaskStatus.valueOf(entity.status),
            error = entity.error,
            retryCount = entity.retryCount,
            nextRetryAtMs = entity.nextRetryAtMs,
            createdAtMs = entity.createdAtMs,
            completedAtMs = entity.completedAtMs,
        )
    }
}
