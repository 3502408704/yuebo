package com.example.local_music_player

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 专辑封面下载：写入 <专辑文件夹>/covers/。
 * 封面一律使用 application/octet-stream MIME（配合 .nomedia），
 * 保证系统媒体库/相册绝不会把它们识别为可浏览的图片。
 */
internal object AlbumCoverDownloader {
    private const val COVERS_SUBFOLDER = "covers"

    suspend fun download(
        context: Context,
        albumName: String,
        albumCoverUrl: String?,
        tracks: List<OnlineTrack>,
    ) {
        val folderName = albumDownloadFolderName(albumName)
        if (folderName.isEmpty()) return
        val resolver = context.contentResolver
        val baseRelative = "$DOWNLOAD_BASE_RELATIVE_PATH/$folderName/$COVERS_SUBFOLDER/"
        ensureNomedia(context, baseRelative)
        val coverUrl = albumCoverUrl ?: tracks.firstOrNull { it.artworkUrl != null }?.artworkUrl
        val fetched = mutableMapOf<String, ByteArray?>()
        coverUrl?.let { url ->
            fetched.getOrPut(url) { fetch(url) }?.let { bytes -> writeIfMissing(resolver, baseRelative, "cover.jpg", bytes) }
        }
        tracks.forEachIndexed { index, track ->
            val url = track.artworkUrl ?: return@forEachIndexed
            val fileName = downloadDisplayName(track.artist, track.title, "jpg", index + 1)
            fetched.getOrPut(url) { fetch(url) }?.let { bytes -> writeIfMissing(resolver, baseRelative, fileName, bytes) }
        }
    }

    private fun ensureNomedia(context: Context, relativePath: String) {
        val canDirectWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        if (canDirectWrite) {
            runCatching {
                val dir = File(
                    Environment.getExternalStorageDirectory(),
                    relativePath.trimEnd('/'),
                ).apply { mkdirs() }
                val marker = File(dir, ".nomedia")
                if (!marker.exists()) marker.createNewFile()
            }
        } else {
            // API 29 或无“所有文件访问”：走 MediaStore，个别系统会把 .nomedia 改名，靠 octet-stream MIME 兜底。
            writeIfMissing(context.contentResolver, relativePath, ".nomedia", ByteArray(0))
        }
    }

    private fun writeIfMissing(resolver: ContentResolver, relativePath: String, displayName: String, bytes: ByteArray) {
        if (mediaFileExists(resolver, relativePath, displayName)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
                try {
                    resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                }
            } else {
                val dir = File(
                    Environment.getExternalStorageDirectory(),
                    relativePath.trimEnd('/'),
                ).apply { mkdirs() }
                val dest = File(dir, displayName)
                if (!dest.exists()) dest.writeBytes(bytes)
            }
        }
    }

    private fun mediaFileExists(resolver: ContentResolver, relativePath: String, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return File(
                Environment.getExternalStorageDirectory(),
                "$relativePath$displayName",
            ).exists()
        }
        return runCatching {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(displayName, relativePath),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
