package com.example.local_music_player

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class TrackMetadataOverride(
    val title: String,
    val artist: String,
    val album: String,
)

data class ImportedAlbumTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val mimeType: String,
    val cueStartMs: Long = 0,
    val isCueTrack: Boolean = false,
)

data class ImportedAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val treeUri: String,
    val tracks: List<ImportedAlbumTrack>,
    val frontCoverUri: String? = null,
    val backCoverUri: String? = null,
    val description: String? = null,
    val descriptionEdited: Boolean = false,
)

internal data class LibraryAlbum<T>(
    val key: String,
    val name: String,
    val artist: String,
    val tracks: List<T>,
    val importedAlbum: ImportedAlbum?,
)

internal data class LibraryAlbumTrack<T>(
    val value: T,
    val metadataKey: String,
    val title: String,
    val artist: String,
    val album: String,
)

internal data class LibraryAlbumSource<T>(
    val name: String,
    val artist: String,
    val tracks: List<LibraryAlbumTrack<T>>,
    val importedAlbum: ImportedAlbum? = null,
)

internal data class LibraryAlbumRemoval(
    val importedAlbums: List<ImportedAlbum>,
    val hiddenKeys: Set<String>,
)

internal data class ExternalTrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

object ImportedAlbumJson {
    fun encode(albums: List<ImportedAlbum>): String = JSONArray().apply {
        albums.forEach { album -> put(album.toJson()) }
    }.toString()

    fun decode(value: String?): List<ImportedAlbum> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val albums = JSONArray(value)
            List(albums.length()) { index ->
                runCatching { albums.getJSONObject(index).toImportedAlbum() }.getOrNull()
            }.filterNotNull()
        }.getOrDefault(emptyList())
    }

    fun encodeTracks(tracks: List<ImportedAlbumTrack>): String = JSONArray().apply {
        tracks.forEach { track -> put(track.toJson()) }
    }.toString()

    fun decodeTracks(value: String?): List<ImportedAlbumTrack> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val tracks = JSONArray(value)
            List(tracks.length()) { index ->
                runCatching { tracks.getJSONObject(index).toImportedAlbumTrack() }.getOrNull()
            }.filterNotNull()
        }.getOrDefault(emptyList())
    }
}

fun importedAlbumId(treeUri: String): String = sha256(treeUri).toHex().take(16)

internal fun metadataKeyFor(uri: String, cueStartMs: Long): String = "$uri#$cueStartMs"

internal fun metadataAfterOverride(
    title: String,
    artist: String,
    album: String,
    override: TrackMetadataOverride?,
): TrackMetadataOverride = override ?: TrackMetadataOverride(title, artist, album)

fun NativeTrack.metadataKey(): String = metadataKeyFor(uri.toString(), cueStartMs)

fun NativeTrack.withMetadataOverride(override: TrackMetadataOverride?): NativeTrack {
    val metadata = metadataAfterOverride(title, artist, album, override)
    return copy(title = metadata.title, artist = metadata.artist, album = metadata.album)
}

fun NativeTrack.toImportedAlbumTrack(): ImportedAlbumTrack = ImportedAlbumTrack(
    uri = uri.toString(),
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    format = format,
    mimeType = mimeType,
    cueStartMs = cueStartMs,
    isCueTrack = isCueTrack,
)

fun ImportedAlbumTrack.toNativeTrack(): NativeTrack = NativeTrack(
    id = importedTrackId(uri, cueStartMs),
    uri = Uri.parse(uri),
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    format = format,
    mimeType = mimeType,
    folderPath = "",
    cueStartMs = cueStartMs,
    isCueTrack = isCueTrack,
)

fun ImportedAlbum.matches(query: String): Boolean {
    val term = query.trim()
    return name.contains(term, ignoreCase = true) ||
        artist.contains(term, ignoreCase = true) ||
        tracks.any { it.title.contains(term, ignoreCase = true) }
}

internal fun libraryAlbumKey(name: String): String = name.trim().lowercase(Locale.ROOT)

private const val IMPORT_ROOT = "Music/月播"

/** 旧版（汪汪播放器时期）导入根目录：改版月播后继续识别老用户已下载/导入的内容。 */
private const val LEGACY_IMPORT_ROOT = "Music/汪汪播放器"
private val importRoots = listOf(IMPORT_ROOT, LEGACY_IMPORT_ROOT)

/** 自动专辑名；null 表示该曲目不构成专辑（MediaStore 文件夹名伪专辑、导入根目录单曲）。 */
internal fun libraryAlbumNameOf(
    album: String,
    folderPath: String,
    folderTrackCounts: Map<String, Int>,
): String? {
    val albumName = album.trim()
    val folder = folderPath.trim('/')
    // 导入根目录是单音频导入的落点，不成专辑
    if (importRoots.any { folder == it }) return null
    // 导入专辑：位于 导入根目录/<X>/ 且 album 为空或等于 X；该文件夹只有一首时视为单曲导入，不成专辑
    for (root in importRoots) {
        if (folder.startsWith("$root/")) {
            val importAlbum = folder.removePrefix("$root/").substringBefore('/')
            if (importAlbum.isNotBlank() && (albumName.isEmpty() || albumName == importAlbum)) {
                return if ((folderTrackCounts[folder] ?: 0) >= 2) importAlbum else null
            }
        }
    }
    if (albumName.isNotEmpty()) {
        // MediaStore 对无标签音频会用父文件夹名填充 ALBUM（如 Music、WeiXin），视为无专辑
        val innermost = folder.substringAfterLast('/')
        if (innermost.isNotEmpty() && albumName.equals(innermost, ignoreCase = true)) return null
        return albumName
    }
    return null
}

internal fun externalTrackMetadata(
    displayName: String,
    embeddedTitle: String?,
    embeddedArtist: String?,
    embeddedAlbum: String?,
    embeddedDurationMs: String?,
): ExternalTrackMetadata = ExternalTrackMetadata(
    title = embeddedTitle?.trim().takeUnless { it.isNullOrBlank() }
        ?: displayName.substringBeforeLast('.', displayName),
    artist = embeddedArtist?.trim().takeUnless { it.isNullOrBlank() } ?: "未知艺术家",
    album = embeddedAlbum?.trim().orEmpty(),
    durationMs = embeddedDurationMs?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
)

internal fun <T> mergeLibraryAlbumSources(
    sources: List<LibraryAlbumSource<T>>,
    hiddenKeys: Set<String>,
): List<LibraryAlbum<T>> {
    val sourcesByKey = sources.groupBy { libraryAlbumKey(it.name) }
    return sourcesByKey.asSequence().filter { (key, _) -> key !in hiddenKeys }.map { (key, sources) ->
        val primary = sources.firstOrNull { it.importedAlbum != null } ?: sources.first()
        LibraryAlbum(
            key = key,
            name = primary.name,
            artist = primary.artist,
            tracks = sources.flatMap { it.tracks }.distinctBy { it.metadataKey }.map { it.value },
            importedAlbum = primary.importedAlbum,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).toList()
}

internal fun <T> removeLibraryAlbumRecord(
    importedAlbums: List<ImportedAlbum>,
    hiddenKeys: Set<String>,
    album: LibraryAlbum<T>,
): LibraryAlbumRemoval = LibraryAlbumRemoval(
    importedAlbums = album.importedAlbum?.let { removed ->
        importedAlbums.filterNot { it.id == removed.id }
    } ?: importedAlbums,
    hiddenKeys = hiddenKeys + album.key,
)

internal fun buildLibraryAlbums(
    libraryTracks: List<NativeTrack>,
    importedAlbums: List<ImportedAlbum>,
    overrides: Map<String, TrackMetadataOverride>,
    hiddenKeys: Set<String>,
): List<LibraryAlbum<NativeTrack>> {
    val folderTrackCounts = libraryTracks.groupingBy { it.folderPath.trim('/') }.eachCount()
    val automaticSources = libraryTracks
        .mapNotNull { track -> libraryAlbumNameOf(track.album, track.folderPath, folderTrackCounts)?.let { name -> name to track } }
        .groupBy({ libraryAlbumKey(it.first) }, { it.second })
        .values.map { tracks ->
            val name = libraryAlbumNameOf(tracks.first().album, tracks.first().folderPath, folderTrackCounts)
                ?: tracks.first().album.trim()
            LibraryAlbumSource(
                name = name,
                artist = tracks.first().artist,
                tracks = tracks.map(NativeTrack::toLibraryAlbumTrack),
            )
        }
    val importedSources = importedAlbums.map { imported ->
        LibraryAlbumSource(
            name = imported.name,
            artist = imported.artist,
            tracks = imported.tracks.map(ImportedAlbumTrack::toNativeTrack)
                .map { track -> track.withMetadataOverride(overrides[track.metadataKey()]) }
                .map(NativeTrack::toLibraryAlbumTrack),
            importedAlbum = imported,
        )
    }
    return mergeLibraryAlbumSources(automaticSources + importedSources, hiddenKeys)
}

private fun NativeTrack.toLibraryAlbumTrack(): LibraryAlbumTrack<NativeTrack> = LibraryAlbumTrack(
    value = this,
    metadataKey = metadataKey(),
    title = title,
    artist = artist,
    album = album,
)

private fun ImportedAlbum.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("artist", artist)
    put("treeUri", treeUri)
    put("tracks", JSONArray().apply { tracks.forEach { track -> put(track.toJson()) } })
    put("frontCoverUri", frontCoverUri)
    put("backCoverUri", backCoverUri)
    put("description", description)
    put("descriptionEdited", descriptionEdited)
}

private fun ImportedAlbumTrack.toJson(): JSONObject = JSONObject().apply {
    put("uri", uri)
    put("title", title)
    put("artist", artist)
    put("album", album)
    put("durationMs", durationMs)
    put("format", format)
    put("mimeType", mimeType)
    put("cueStartMs", cueStartMs)
    put("isCueTrack", isCueTrack)
}

private fun JSONObject.toImportedAlbum(): ImportedAlbum {
    val id = optString("id")
    val treeUri = optString("treeUri")
    require(id.isNotBlank() && treeUri.isNotBlank())
    val tracks = requireNotNull(optJSONArray("tracks")) { "tracks must be an array" }.let { values ->
        List(values.length()) { index ->
            runCatching { values.getJSONObject(index).toImportedAlbumTrack() }.getOrNull()
        }.filterNotNull()
    }
    require(tracks.isNotEmpty())
    return ImportedAlbum(
        id = id,
        name = optString("name"),
        artist = optString("artist"),
        treeUri = treeUri,
        tracks = tracks,
        frontCoverUri = optionalString("frontCoverUri"),
        backCoverUri = optionalString("backCoverUri"),
        description = optionalString("description"),
        descriptionEdited = optBoolean("descriptionEdited", false),
    )
}

private fun JSONObject.toImportedAlbumTrack(): ImportedAlbumTrack {
    val uri = optString("uri")
    val title = optString("title")
    val format = optString("format")
    val mimeType = optString("mimeType")
    require(uri.isNotBlank() && title.isNotBlank() && format.isNotBlank() && mimeType.isNotBlank())
    return ImportedAlbumTrack(
        uri = uri,
        title = title,
        artist = optString("artist"),
        album = optString("album"),
        durationMs = optLong("durationMs", 0),
        format = format,
        mimeType = mimeType,
        cueStartMs = optLong("cueStartMs", 0),
        isCueTrack = optBoolean("isCueTrack", false),
    )
}

private fun JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

private fun importedTrackId(uri: String, cueStartMs: Long): Long {
    var value = 0L
    sha256("$uri#$cueStartMs").take(8).forEach { byte ->
        value = (value shl 8) or (byte.toLong() and 0xff)
    }
    return value or Long.MIN_VALUE
}

private fun sha256(value: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
