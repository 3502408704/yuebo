package com.example.local_music_player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class QuarkPanDownload(
    val fid: String,
    val pdirFid: String,
    val name: String,
    val size: Long,
    val downloadedBytes: Long,
    val status: DownloadTaskStatus,
    val error: String?,
    val mediaStoreUri: Uri?,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val subPath: String = "",
)

/** 夸克网盘下载任务数据层：Room 是任务事实来源，实际传输由 [DownloadEngine] 负责。 */
internal class QuarkPanDownloadRepository(
    private val context: Context,
    private val dao: QuarkPanDownloadDao,
) {
    private val resolver = context.contentResolver
    private val taskMutex = Mutex()

    val downloads: Flow<List<QuarkPanDownload>> =
        dao.observeAll().map { entries -> entries.map(::toDownload) }

    suspend fun enqueue(file: QuarkPanFile, subPath: String = ""): Boolean = taskMutex.withLock {
        val existing = dao.findByFid(file.fid)
        if (existing != null) {
            if (existing.status != DownloadTaskStatus.COMPLETED.name) return false
            dao.deleteByFid(file.fid)
            tempFile(file.fid).delete()
        }
        dao.upsert(
            QuarkPanDownloadEntity(
                fid = file.fid,
                pdirFid = file.pdirFid,
                name = file.fileName,
                size = file.size,
                downloadedBytes = 0,
                status = DownloadTaskStatus.QUEUED.name,
                error = null,
                mediaStoreUri = null,
                createdAtMs = System.currentTimeMillis(),
                completedAtMs = null,
                subPath = subPath,
            ),
        )
        true
    }

    suspend fun find(fid: String): QuarkPanDownload? = dao.findByFid(fid)?.let(::toDownload)

    suspend fun pause(fid: String) {
        taskMutex.withLock {
            val entity = dao.findByFid(fid) ?: return@withLock
            if (entity.status in setOf(
                    DownloadTaskStatus.QUEUED.name,
                    DownloadTaskStatus.DOWNLOADING.name,
                )
            ) {
                dao.upsert(entity.copy(status = DownloadTaskStatus.PAUSED.name))
            }
        }
    }

    suspend fun resume(fid: String) {
        taskMutex.withLock {
            val entity = dao.findByFid(fid) ?: return@withLock
            if (entity.status in setOf(
                    DownloadTaskStatus.PAUSED.name,
                    DownloadTaskStatus.FAILED.name,
                )
            ) {
                dao.upsert(
                    entity.copy(
                        status = DownloadTaskStatus.QUEUED.name,
                        error = null,
                    ),
                )
            }
        }
    }

    suspend fun cancel(fid: String) {
        taskMutex.withLock {
            val entity = dao.findByFid(fid) ?: return@withLock
            if (entity.status == DownloadTaskStatus.COMPLETED.name) return@withLock
            tempFile(fid).delete()
            dao.deleteByFid(fid)
        }
    }

    suspend fun removeRecord(fid: String) {
        taskMutex.withLock {
            if (dao.findByFid(fid)?.status == DownloadTaskStatus.COMPLETED.name) {
                dao.deleteByFid(fid)
            }
        }
    }

    suspend fun unfinished(): List<QuarkPanDownload> = taskMutex.withLock {
        dao.findByStatuses(
            listOf(
                DownloadTaskStatus.QUEUED.name,
                DownloadTaskStatus.DOWNLOADING.name,
            ),
        ).map(::toDownload)
    }

    suspend fun recoverAfterServiceRestart() = taskMutex.withLock {
        dao.findByStatuses(listOf(DownloadTaskStatus.DOWNLOADING.name)).forEach { entity ->
            dao.upsert(entity.copy(status = DownloadTaskStatus.QUEUED.name, error = null))
        }
    }

    suspend fun markDownloading(fid: String): Boolean = taskMutex.withLock {
        val entity = dao.findByFid(fid) ?: return false
        if (entity.status != DownloadTaskStatus.QUEUED.name) return false
        dao.upsert(entity.copy(status = DownloadTaskStatus.DOWNLOADING.name, error = null))
        true
    }

    suspend fun markProgress(fid: String, downloadedBytes: Long) {
        taskMutex.withLock {
            val entity = dao.findByFid(fid) ?: return@withLock
            if (entity.status == DownloadTaskStatus.DOWNLOADING.name) {
                dao.upsert(entity.copy(downloadedBytes = downloadedBytes))
            }
        }
    }

    suspend fun markFailure(fid: String, message: String) {
        taskMutex.withLock {
            val entity = dao.findByFid(fid) ?: return@withLock
            if (entity.status == DownloadTaskStatus.DOWNLOADING.name) {
                dao.upsert(entity.copy(status = DownloadTaskStatus.FAILED.name, error = message))
            }
        }
    }

    /** 传输完成后把缓存临时文件发布到媒体库并标记完成；失败则标记失败。 */
    suspend fun publishAndComplete(fid: String, temp: File): Boolean = taskMutex.withLock {
        val entity = dao.findByFid(fid) ?: return false
        if (entity.status != DownloadTaskStatus.DOWNLOADING.name) return false
        val uri = runCatching { publishToMediaStore(temp, entity) }.getOrElse { error ->
            dao.upsert(entity.copy(status = DownloadTaskStatus.FAILED.name, error = error.message ?: "下载失败，请重试"))
            return false
        }
        dao.upsert(
            entity.copy(
                status = DownloadTaskStatus.COMPLETED.name,
                downloadedBytes = entity.size,
                mediaStoreUri = uri.toString(),
                error = null,
                completedAtMs = System.currentTimeMillis(),
            ),
        )
        true
    }

    private fun publishToMediaStore(file: File, entity: QuarkPanDownloadEntity): Uri {
        val relativePath = if (entity.subPath.isBlank()) {
            "$DOWNLOAD_BASE_RELATIVE_PATH/夸克网盘/"
        } else {
            "$DOWNLOAD_BASE_RELATIVE_PATH/夸克网盘/${entity.subPath}/"
        }
        val mimeType = panDownloadMimeType(entity.name)
        val contentUri = if (isPanAudioFileName(entity.name)) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStorageDirectory(), relativePath)
            dir.mkdirs()
            val existing = dir.list()?.toSet().orEmpty()
            val destination = File(dir, uniqueDownloadName(existing, entity.name))
            if (!file.renameTo(destination)) throw IOException("无法完成下载文件")
            return resolver.insert(
                contentUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, destination.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, destination.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                },
            ) ?: Uri.fromFile(destination)
        }
        val displayName = uniqueDownloadName(existingNamesIn(relativePath), entity.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(contentUri, values) ?: throw IOException("无法写入媒体库")
        resolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("无法写入媒体库")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    private fun existingNamesIn(relativePath: String): Set<String> = resolver.query(
        MediaStore.Files.getContentUri("external"),
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(relativePath),
        null,
    )?.use { cursor ->
        buildSet {
            while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
        }
    } ?: emptySet()

    fun tempFile(fid: String): File =
        File(context.cacheDir, "quark_pan/$fid.part")

    private fun toDownload(entity: QuarkPanDownloadEntity): QuarkPanDownload = QuarkPanDownload(
        fid = entity.fid,
        pdirFid = entity.pdirFid,
        name = entity.name,
        size = entity.size,
        downloadedBytes = entity.downloadedBytes,
        status = runCatching { DownloadTaskStatus.valueOf(entity.status) }
            .getOrDefault(DownloadTaskStatus.FAILED),
        error = entity.error,
        mediaStoreUri = entity.mediaStoreUri?.let(Uri::parse),
        createdAtMs = entity.createdAtMs,
        completedAtMs = entity.completedAtMs,
        subPath = entity.subPath,
    )
}
