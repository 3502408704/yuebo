package com.example.local_music_player

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val albumFolder: String?,
)

data class PasswordRequest(
    val uri: Uri,
    val displayName: String,
    val errorMessage: String? = null,
)

sealed interface ImportOutcome {
    data class Success(val result: ImportResult) : ImportOutcome
    data class NeedsPassword(val request: PasswordRequest) : ImportOutcome
    data class Failure(val message: String) : ImportOutcome
}

class ImportException(message: String) : Exception(message)
class NeedsPasswordException(val wrongPassword: Boolean) : Exception()
class EncryptedZipException : Exception()

object ArchiveImporter {
    private val audioExtensions = LOCAL_AUDIO_EXTENSIONS
    private const val MAX_FILES = 5000
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    private fun isCueFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".cue") || lower.endsWith(".cue.txt")
    }

    private fun isImportedNonAudio(name: String): Boolean =
        isCueFileName(name) || name.lowercase().endsWith(".lrc") ||
            name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") ||
            name.lowercase().endsWith(".png") || name.lowercase().endsWith(".webp")

    private enum class Format { ZIP, RAR, GZIP_TAR, GZIP, TAR, UNKNOWN }
    private enum class FileImport { Imported, Duplicate, Error }

    suspend fun import(
        context: Context,
        sourceUri: Uri,
        password: String?,
        onProgress: (Int, Int) -> Unit,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val archiveName = uriFileName(resolver, sourceUri)
        runCatching {
            val tempRoot = File(context.cacheDir, "import/${System.currentTimeMillis()}")
                .apply { mkdirs() }
            try {
                val format = detectFormat(resolver, sourceUri, archiveName)
                when (format) {
                    Format.ZIP -> extractZip(resolver, sourceUri, tempRoot)
                    Format.GZIP_TAR -> extractGzipTar(resolver, sourceUri, tempRoot)
                    Format.GZIP -> extractGzipSingle(resolver, sourceUri, archiveName, tempRoot)
                    Format.TAR -> extractTarStream(resolver.openInputStream(sourceUri)!!, tempRoot)
                    Format.RAR -> {
                        val archiveFile = File(tempRoot, "archive.rar")
                        resolver.openInputStream(sourceUri)!!.use { input ->
                            archiveFile.outputStream().use { input.copyTo(it) }
                        }
                        val code = runCatching {
                            UnrarNative.extract(archiveFile.absolutePath, tempRoot.absolutePath, password)
                        }.getOrElse {
                            throw ImportException("RAR 解压组件不可用：${it.message ?: "未知错误"}")
                        }
                        when (code) {
                            0 -> Unit
                            22 -> throw NeedsPasswordException(wrongPassword = false)
                            24 -> throw NeedsPasswordException(wrongPassword = true)
                            else -> throw ImportException("解压失败（错误码 $code）。")
                        }
                    }
                    Format.UNKNOWN -> throw ImportException("无法识别的压缩格式。")
                }
                val albumName = chooseAlbumName(tempRoot, archiveName)
                ImportOutcome.Success(importTree(resolver, tempRoot, albumName, onProgress))
            } finally {
                tempRoot.deleteRecursively()
            }
        }.getOrElse { error ->
            when (error) {
                is NeedsPasswordException -> ImportOutcome.NeedsPassword(
                    PasswordRequest(sourceUri, archiveName, if (error.wrongPassword) "密码错误，请重试。" else null),
                )
                is EncryptedZipException -> ImportOutcome.Failure("暂不支持加密的 ZIP 文件。")
                else -> ImportOutcome.Failure("导入失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun uriFileName(resolver: ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "压缩包"

    private fun detectFormat(resolver: ContentResolver, uri: Uri, name: String): Format {
        val lower = name.lowercase()
        val magic = resolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(8)
            val n = input.read(head)
            head.copyOf(n.coerceAtLeast(0))
        } ?: ByteArray(0)
        if (magic.size >= 4 &&
            magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2] == 3.toByte() && magic[3] == 4.toByte()
        ) return Format.ZIP
        if (magic.size >= 7 &&
            magic[0] == 'R'.code.toByte() && magic[1] == 'a'.code.toByte() &&
            magic[2] == 'r'.code.toByte() && magic[3] == '!'.code.toByte() &&
            magic[4] == 0x1A.toByte() && magic[5] == 0x07.toByte() &&
            (magic[6] == 0.toByte() || magic[6] == 1.toByte())
        ) return Format.RAR
        if (magic.size >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) {
            return if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) Format.GZIP_TAR
            else Format.GZIP
        }
        return if (lower.endsWith(".tar")) Format.TAR else Format.UNKNOWN
    }

    private fun archiveStem(name: String): String = name.lowercase()
        .replace(Regex("\\.(tar\\.gz|tgz|tar|zip|rar|gz)$"), "")
        .trim()
        .ifBlank { "导入专辑" }

    private fun chooseAlbumName(root: File, archiveName: String): String {
        val top = root.listFiles().orEmpty()
        if (top.size == 1 && top[0].isDirectory &&
            top[0].walkTopDown().any { it.isFile && it.extension.lowercase() in audioExtensions }
        ) {
            return top[0].name
        }
        return archiveStem(archiveName)
    }

    private fun sanitizeEntryName(name: String): String? {
        val normalized = name.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (normalized.startsWith("/") || normalized.contains(":")) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun extractZip(resolver: ContentResolver, sourceUri: Uri, tempRoot: File) {
        resolver.openInputStream(sourceUri)!!.use { raw ->
            ZipInputStream(raw).use { zip ->
                var count = 0
                var total = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    val safe = if (entry.isDirectory) null else sanitizeEntryName(entry.name)
                    if (safe != null) {
                        val target = File(tempRoot, safe).apply { parentFile?.mkdirs() }
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                if (total > MAX_TOTAL_BYTES) throw ImportException("压缩包内容过大。")
                            }
                        }
                        count++
                        if (count > MAX_FILES) throw ImportException("文件数量过多。")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun extractGzipTar(resolver: ContentResolver, sourceUri: Uri, tempRoot: File) {
        resolver.openInputStream(sourceUri)!!.use { raw ->
            GZIPInputStream(raw).use { gz -> extractTarStream(gz, tempRoot) }
        }
    }

    private fun extractGzipSingle(
        resolver: ContentResolver,
        sourceUri: Uri,
        archiveName: String,
        tempRoot: File,
    ) {
        val targetName = archiveStem(archiveName)
        resolver.openInputStream(sourceUri)!!.use { raw ->
            GZIPInputStream(raw).use { gz ->
                val target = File(tempRoot, targetName).apply { parentFile?.mkdirs() }
                FileOutputStream(target).use { out -> gz.copyTo(out) }
            }
        }
    }

    private fun extractTarStream(input: InputStream, tempRoot: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var count = 0
        var total = 0L
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val name = tarString(header, 0, 100)
            val prefix = tarString(header, 345, 155)
            val size = tarOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            if (type == 'L') {
                if (size > 1024L * 1024) {
                    skipBlocks(input, size)
                    pendingLongName = null
                    continue
                }
                val longName = ByteArray(size.toInt())
                readFully(input, longName)
                skipPadding(input, size)
                pendingLongName = decodeTarName(longName).trimEnd('\u0000', '\n')
                continue
            }
            val entryName = pendingLongName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            pendingLongName = null
            val safe = sanitizeEntryName(entryName)
            if (safe == null) {
                skipBlocks(input, size)
                continue
            }
            if (type == '5') {
                File(tempRoot, safe).mkdirs()
                skipBlocks(input, size)
                continue
            }
            if (type == '0' || type == '\u0000') {
                val target = File(tempRoot, safe).apply { parentFile?.mkdirs() }
                FileOutputStream(target).use { out ->
                    var remaining = size
                    val buf = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                        total += n
                        if (total > MAX_TOTAL_BYTES) throw ImportException("压缩包内容过大。")
                    }
                }
                count++
                if (count > MAX_FILES) throw ImportException("文件数量过多。")
                skipPadding(input, size)
            } else {
                skipBlocks(input, size)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return offset == 0
            offset += n
        }
        return true
    }

    private fun skipBlocks(input: InputStream, size: Long) {
        var remaining = (size + 511) / 512 * 512
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun tarString(buffer: ByteArray, offset: Int, length: Int): String =
        decodeTarName(buffer.copyOfRange(offset, offset + length)).substringBefore('\u0000').trim()

    private fun decodeTarName(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
        } else {
            utf8
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        var remaining = ((512 - size % 512) % 512).toInt()
        val buf = ByteArray(512)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size))
            if (n < 0) break
            remaining -= n
        }
    }

    private fun tarOctal(buffer: ByteArray, offset: Int, length: Int): Long =
        tarString(buffer, offset, length).trim().toLongOrNull(8) ?: 0L

    private fun importTree(
        resolver: ContentResolver,
        root: File,
        albumName: String,
        onProgress: (Int, Int) -> Unit,
    ): ImportResult {
        val candidates = root.walkTopDown()
            .filter { it.isFile && (it.extension.lowercase() in audioExtensions || isImportedNonAudio(it.name)) }
            .toList()
        val relativeDir = "Music/月播/$albumName"
        val existingAudio = existingAudioKeys(resolver, relativeDir)
        var skipped = 0
        var failed = 0
        var importedAudio = 0
        candidates.forEachIndexed { index, file ->
            val isAudio = file.extension.lowercase() in audioExtensions
            val outcome = if (isAudio) {
                importAudioFile(resolver, file, relativeDir, existingAudio)
            } else {
                importNonAudioFile(resolver, file, relativeDir)
            }
            when (outcome) {
                FileImport.Imported -> if (isAudio) importedAudio++
                FileImport.Duplicate -> skipped++
                FileImport.Error -> failed++
            }
            onProgress(index + 1, candidates.size)
        }
        val cueTrackCount = candidates.filter { isCueFileName(it.name) }.sumOf { countCueTracks(it) }
        val songCount = if (cueTrackCount > 0) cueTrackCount else importedAudio
        return ImportResult(songCount, skipped, failed, albumName)
    }

    private fun countCueTracks(file: File): Int {
        val text = runCatching { decodeCueText(file.readBytes()) }.getOrNull() ?: return 0
        val trackPattern = Regex("^\\s*TRACK\\s+\\d+\\s+AUDIO\\s*$", RegexOption.IGNORE_CASE)
        return text.lineSequence().count { trackPattern.matches(it.trim()) }
    }

    private fun decodeCueText(bytes: ByteArray): String {
        var offset = 0
        var charset: Charset = Charsets.UTF_8
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                offset = 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                offset = 2
                charset = Charsets.UTF_16LE
            }
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                offset = 2
                charset = Charsets.UTF_16BE
            }
        }
        val decoded = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        }
        if (decoded.isSuccess) return decoded.getOrThrow()
        return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun importAudioFile(
        resolver: ContentResolver,
        file: File,
        relativeDir: String,
        existingAudio: Set<Pair<String, Long>>,
    ): FileImport {
        val name = file.name
        val size = file.length()
        val albumName = relativeDir.substringAfterLast('/').trim().ifBlank { "未知专辑" }
        return try {
            if (isDuplicate(resolver, relativeDir, name, size, existingAudio)) {
                FileImport.Duplicate
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.extension))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.SIZE, size)
                    put(MediaStore.Audio.Media.ALBUM, albumName)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return FileImport.Error
                try {
                    resolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
                FileImport.Imported
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    relativeDir,
                ).apply { mkdirs() }
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = false)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, dest.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.extension))
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.ALBUM, albumName)
                }
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                FileImport.Imported
            }
        } catch (error: Throwable) {
            FileImport.Error
        }
    }

    private fun importNonAudioFile(resolver: ContentResolver, file: File, relativeDir: String): FileImport {
        return try {
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "lrc" -> "audio/x-lrc"
                "cue" -> "application/x-cue"
                else -> "text/plain"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: return FileImport.Error
                try {
                    resolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
                FileImport.Imported
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    relativeDir,
                ).apply { mkdirs() }
                val dest = File(dir, file.name)
                if (dest.exists()) FileImport.Duplicate else {
                    file.copyTo(dest)
                    FileImport.Imported
                }
            }
        } catch (error: Throwable) {
            FileImport.Error
        }
    }

    private fun isDuplicate(
        resolver: ContentResolver,
        relativeDir: String,
        name: String,
        size: Long,
        existingAudio: Set<Pair<String, Long>>,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            name to size in existingAudio
        } else {
            val path = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "$relativeDir/$name",
            ).absolutePath
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { it.moveToFirst() } ?: false
        }
    }

    private fun existingAudioKeys(resolver: ContentResolver, relativeDir: String): Set<Pair<String, Long>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        return runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("$relativeDir/"),
                null,
            )?.use { cursor ->
                buildSet {
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) add(cursor.getString(nameCol) to cursor.getLong(sizeCol))
                }
            } ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "mp2", "mp1" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        "m4a", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> "audio/*"
    }
}
