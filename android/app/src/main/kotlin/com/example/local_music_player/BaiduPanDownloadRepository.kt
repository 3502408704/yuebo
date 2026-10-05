package com.example.local_music_player

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
import java.io.IOException

data class BaiduPanDownload(
    val fsId: Long,
    val path: String,
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

/** 目标目录下已有同名文件时追加 " (1)"、" (2)" 后缀。 */
internal fun uniqueDownloadName(existingNames: Set<String>, name: String): String {
    if (name !in existingNames) return name
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "")
        .let { if (it.isEmpty() || it == name) "" else ".$it" }
    var index = 1
    while (true) {
        val candidate = "$base ($index)$extension"
        if (candidate !in existingNames) return candidate
        index++
    }
}

/** 百度网盘下载任务数据层：Room 是任务事实来源，实际传输由 [DownloadEngine] 负责。 */
internal class BaiduPanDownloadRepository(
    private val context: Context,
    private val accessTokenProvider: () -> String,
    private val dao: BaiduPanDownloadDao,
) {
    private val resolver = context.contentResolver
    private val taskMutex = Mutex()

    val downloads: Flow<List<BaiduPanDownload>> =
        dao.observeAll().map { entries -> entries.map(::toDownload) }

    suspend fun enqueue(file: BaiduPanFile, subPath: String = ""): Boolean = taskMutex.withLock {
        val existing = dao.findByFsId(file.fsId)
        if (existing != null) {
            if (existing.status != DownloadTaskStatus.COMPLETED.name) return false
            dao.deleteByFsId(file.fsId)
            tempFile(file.fsId).delete()
        }
        dao.upsert(
            BaiduPanDownloadEntity(
                fsId = file.fsId,
                path = file.path,
                name = file.serverFilename,
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

    suspend fun find(fsId: Long): BaiduPanDownload? = dao.findByFsId(fsId)?.let(::toDownload)

    suspend fun pause(fsId: Long) {
        taskMutex.withLock {
            val entity = dao.findByFsId(fsId) ?: return@withLock
            if (entity.status in setOf(
                    DownloadTaskStatus.QUEUED.name,
                    DownloadTaskStatus.DOWNLOADING.name,
                )
            ) {
                dao.upsert(entity.copy(status = DownloadTaskStatus.PAUSED.name))
            }
        }
    }

    suspend fun resume(fsId: Long) {
        taskMutex.withLock {
            val entity = dao.findByFsId(fsId) ?: return@withLock
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

    suspend fun cancel(fsId: Long) {
        taskMutex.withLock {
            val entity = dao.findByFsId(fsId) ?: return@withLock
            if (entity.status == DownloadTaskStatus.COMPLETED.name) return@withLock
            tempFile(fsId).delete()
            dao.deleteByFsId(fsId)
        }
    }

    suspend fun removeRecord(fsId: Long) {
        taskMutex.withLock {
            if (dao.findByFsId(fsId)?.status == DownloadTaskStatus.COMPLETED.name) {
                dao.deleteByFsId(fsId)
            }
        }
    }

    suspend fun unfinished(): List<BaiduPanDownload> = taskMutex.withLock {
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

    suspend fun markDownloading(fsId: Long): Boolean = taskMutex.withLock {
        val entity = dao.findByFsId(fsId) ?: return false
        if (entity.status != DownloadTaskStatus.QUEUED.name) return false
        dao.upsert(entity.copy(status = DownloadTaskStatus.DOWNLOADING.name, error = null))
        true
    }

    suspend fun markProgress(fsId: Long, downloadedBytes: Long) {
        taskMutex.withLock {
            val entity = dao.findByFsId(fsId) ?: return@withLock
            if (entity.status == DownloadTaskStatus.DOWNLOADING.name) {
                dao.upsert(entity.copy(downloadedBytes = downloadedBytes))
            }
        }
    }

    suspend fun markFailure(fsId: Long, message: String) {
        taskMutex.withLock {
            val entity = dao.findByFsId(fsId) ?: return@withLock
            if (entity.status == DownloadTaskStatus.DOWNLOADING.name) {
                dao.upsert(entity.copy(status = DownloadTaskStatus.FAILED.name, error = message))
            }
        }
    }

    /** 传输完成后把缓存临时文件发布到媒体库并标记完成；失败则标记失败。 */
    suspend fun publishAndComplete(fsId: Long, temp: File): Boolean = taskMutex.withLock {
        val entity = dao.findByFsId(fsId) ?: return false
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

    private fun publishToMediaStore(file: File, entity: BaiduPanDownloadEntity): Uri {
        val relativePath = if (entity.subPath.isBlank()) {
            "$DOWNLOAD_BASE_RELATIVE_PATH/百度网盘/"
        } else {
            "$DOWNLOAD_BASE_RELATIVE_PATH/百度网盘/${entity.subPath}/"
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

    fun tempFile(fsId: Long): File =
        File(context.cacheDir, "baidu_pan/$fsId.part")

    private fun toDownload(entity: BaiduPanDownloadEntity): BaiduPanDownload = BaiduPanDownload(
        fsId = entity.fsId,
        path = entity.path,
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

internal fun panDownloadMimeType(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "opus" -> "audio/opus"
        "ape" -> "audio/x-ape"
        "wv" -> "audio/x-wavpack"
        "webm" -> "audio/webm"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "lrc" -> "text/plain"
        else -> "application/octet-stream"
    }
}
