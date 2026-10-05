package com.example.local_music_player

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedAlbumTest {
    @Test
    fun albumJsonRoundTripKeepsArtworkDescriptionAndCueTrack() {
        val album = sampleAlbum()

        assertEquals(listOf(album), ImportedAlbumJson.decode(ImportedAlbumJson.encode(listOf(album))))
    }

    @Test
    fun trackListJsonRoundTripKeepsCueMetadata() {
        val tracks = sampleAlbum().tracks

        assertEquals(tracks, ImportedAlbumJson.decodeTracks(ImportedAlbumJson.encodeTracks(tracks)))
        assertEquals(emptyList(), ImportedAlbumJson.decodeTracks("not json"))
    }

    @Test
    fun structuredQueueItemRestoresCueTrackAndOriginalIdForResume() {
        val original = sampleAlbum().tracks.single()
        val originalId = -8_765_432_101L
        val folderPath = "导入专辑/第一张"
        val sizeBytes = 123_456_789L
        val modifiedTimeMs = 1_725_000_000_000L
        val bitrateKbps = 1_024

        val persisted = encodeExternalQueueItem(
            track = original,
            id = originalId,
            folderPath = folderPath,
            sizeBytes = sizeBytes,
            modifiedTimeMs = modifiedTimeMs,
            bitrateKbps = bitrateKbps,
        )
        val restored = decodeExternalQueueItem(persisted)
        val restoredQueue = listOf(restored!!)
        val queueIndex = 0
        val currentId = originalId

        assertEquals(originalId, persisted.getLong("externalTrackId"))
        assertTrue(persisted.has("externalTrack"))
        assertEquals(original, restored.track)
        assertEquals(folderPath, restored.folderPath)
        assertEquals(sizeBytes, restored.sizeBytes)
        assertEquals(modifiedTimeMs, restored.modifiedTimeMs)
        assertEquals(bitrateKbps, restored.bitrateKbps)
        assertEquals(restored, restoredQueue[queueIndex])
        assertEquals(restored, restoredQueue.firstOrNull { it.id == currentId })
    }

    @Test
    fun decodeSkipsMalformedAlbumAndTrackItems() {
        val album = sampleAlbum()
        val encodedAlbum = ImportedAlbumJson.encode(listOf(album)).removePrefix("[").removeSuffix("]")
        val mixedTracksAlbum = """
            {
              "id":"mixed-tracks",
              "name":"混合曲目",
              "artist":"",
              "treeUri":"content://tree/mixed",
              "tracks":[
                {"uri":"content://document/invalid.flac","title":"","format":"FLAC","mimeType":"audio/flac"},
                {"uri":"content://document/valid.flac","title":"有效曲目","artist":"","album":"","format":"FLAC","mimeType":"audio/flac"}
              ]
            }
        """.trimIndent()
        val expectedMixedTracksAlbum = ImportedAlbum(
            id = "mixed-tracks",
            name = "混合曲目",
            artist = "",
            treeUri = "content://tree/mixed",
            tracks = listOf(
                ImportedAlbumTrack(
                    uri = "content://document/valid.flac",
                    title = "有效曲目",
                    artist = "",
                    album = "",
                    durationMs = 0,
                    format = "FLAC",
                    mimeType = "audio/flac",
                ),
            ),
        )

        assertEquals(
            listOf(album, expectedMixedTracksAlbum),
            ImportedAlbumJson.decode("[$encodedAlbum,\"broken\",$mixedTracksAlbum]"),
        )
        assertEquals(emptyList(), ImportedAlbumJson.decode("not json"))
    }

    @Test
    fun decodeSkipsAlbumsWithoutValidTrackArrays() {
        val validAlbum = sampleAlbum()
        val encodedValidAlbum = ImportedAlbumJson.encode(listOf(validAlbum)).removePrefix("[").removeSuffix("]")
        val albums = """
            [
              {"id":"missing","treeUri":"content://tree/missing"},
              {"id":"object","treeUri":"content://tree/object","tracks":{}},
              {"id":"empty","treeUri":"content://tree/empty","tracks":[]},
              {"id":"invalid","treeUri":"content://tree/invalid","tracks":[{"uri":"content://document/a.flac","title":"","format":"FLAC","mimeType":"audio/flac"}]},
              $encodedValidAlbum
            ]
        """.trimIndent()

        assertEquals(listOf(validAlbum), ImportedAlbumJson.decode(albums))
    }

    @Test
    fun importedAlbumIdUsesStableUriSha256Prefix() {
        val treeUri = "content://tree/album"

        assertEquals(sha256Prefix(treeUri), importedAlbumId(treeUri))
        assertEquals(importedAlbumId(treeUri), importedAlbumId(treeUri))
    }

    @Test
    fun metadataKeyDiffersForDifferentCueStartsWithTheSameUri() {
        val uri = "content://document/source.flac"

        assertEquals(metadataKeyFor(uri, 0), metadataKeyFor(uri, 0))
        assertTrue(metadataKeyFor(uri, 0) != metadataKeyFor(uri, 60_000))
    }

    @Test
    fun metadataOverrideReplacesAllTextsAndMissingOverrideKeepsOriginals() {
        val original = metadataAfterOverride("原标题", "原艺术家", "原专辑", null)
        val overridden = metadataAfterOverride(
            "原标题",
            "原艺术家",
            "原专辑",
            TrackMetadataOverride("新标题", "新艺术家", "新专辑"),
        )

        assertEquals(TrackMetadataOverride("原标题", "原艺术家", "原专辑"), original)
        assertEquals(TrackMetadataOverride("新标题", "新艺术家", "新专辑"), overridden)
    }

    @Test
    fun trackMetadataOverridesRoundTripThroughJson() {
        val overrides = mapOf(
            "content://document/a.flac#0" to TrackMetadataOverride("标题", "艺术家", "专辑"),
        )

        assertEquals(overrides, decodeTrackMetadataOverrides(encodeTrackMetadataOverrides(overrides)))
    }

    @Test
    fun corruptTrackMetadataOverridesFallBackToEmpty() {
        assertEquals(emptyMap(), decodeTrackMetadataOverrides("not json"))
    }

    @Test
    fun albumsAreMatchedByNameArtistOrTrackTitle() {
        val album = sampleAlbum()

        assertTrue(album.matches("专辑"))
        assertTrue(album.matches("艺术家"))
        assertTrue(album.matches("第一首"))
        assertFalse(album.matches("不存在"))
    }

    @Test
    fun albumListOrderIsCaseInsensitiveAndStable() {
        val albums = listOf(
            sampleAlbum().copy(id = "first", name = "zebra"),
            sampleAlbum().copy(id = "second", name = "Apple"),
            sampleAlbum().copy(id = "third", name = "apple"),
        )

        assertEquals(listOf("second", "third", "first"), sortedImportedAlbums(albums).map { it.id })
    }

    @Test
    fun libraryAlbumsMergeSameNameAndPreferImportedPresentation() {
        val automatic = albumSource(
            name = " 夜航 ",
            artist = "自动艺术家",
            tracks = listOf(albumTrack("automatic", "自动曲目", "自动艺术家", " 夜航 ")),
        )
        val imported = sampleAlbum().copy(name = "夜航", artist = "导入艺术家")
        val importedSource = albumSource(
            name = imported.name,
            artist = imported.artist,
            tracks = listOf(albumTrack("imported", "导入曲目", "导入艺术家", "夜航")),
            importedAlbum = imported,
        )

        val albums = mergeLibraryAlbumSources(listOf(automatic, importedSource), emptySet())

        assertEquals(1, albums.size)
        assertEquals("夜航", albums.single().name)
        assertEquals("导入艺术家", albums.single().artist)
        assertEquals(imported, albums.single().importedAlbum)
        assertEquals(2, albums.single().tracks.size)
    }

    @Test
    fun libraryAlbumsDeduplicateTracksAndHonorHiddenKey() {
        val imported = sampleAlbum().copy(
            name = "夜航",
            tracks = sampleAlbum().tracks.map { it.copy(album = "夜航") },
        )
        val automatic = albumSource(
            name = "夜航",
            artist = "艺术家",
            tracks = listOf(albumTrack("shared", "第一首", "艺术家", "夜航")),
        )
        val importedSource = albumSource(
            name = imported.name,
            artist = imported.artist,
            tracks = listOf(albumTrack("shared", "第一首", "艺术家", "夜航")),
            importedAlbum = imported,
        )

        val shown = mergeLibraryAlbumSources(listOf(automatic, importedSource), emptySet())
        val hidden = mergeLibraryAlbumSources(listOf(automatic, importedSource), setOf(libraryAlbumKey("夜航")))

        assertEquals(1, shown.single().tracks.size)
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun libraryAlbumsUseSavedTrackOverridesBeforeGrouping() {
        val source = albumSource(
            name = "新专辑",
            artist = "新艺术家",
            tracks = listOf(albumTrack("track", "新题", "新艺术家", "新专辑")),
        )

        val albums = mergeLibraryAlbumSources(listOf(source), emptySet())

        assertEquals("新专辑", albums.single().name)
        assertEquals("新艺术家", albums.single().artist)
    }

    @Test
    fun removingLibraryAlbumHidesKeyAndRemovesOnlyItsImportedRecord() {
        val imported = sampleAlbum()
        val remaining = sampleAlbum().copy(id = "remaining", name = "保留专辑")
        val selected = mergeLibraryAlbumSources(
            listOf(
                albumSource(
                    name = imported.name,
                    artist = imported.artist,
                    tracks = listOf(albumTrack("selected", "第一首", imported.artist, imported.name)),
                    importedAlbum = imported,
                ),
            ),
            emptySet(),
        ).single()

        val result = removeLibraryAlbumRecord(listOf(imported, remaining), emptySet(), selected)

        assertEquals(setOf(libraryAlbumKey(imported.name)), result.hiddenKeys)
        assertEquals(listOf(remaining), result.importedAlbums)
    }

    @Test
    fun externalTrackMetadataPrefersTagsAndFallsBackToDisplayName() {
        assertEquals(
            ExternalTrackMetadata("标签标题", "标签艺术家", "标签专辑", 12_345),
            externalTrackMetadata("文件名.flac", " 标签标题 ", " 标签艺术家 ", " 标签专辑 ", "12345"),
        )
        assertEquals(
            ExternalTrackMetadata("文件名", "未知艺术家", "", 0),
            externalTrackMetadata("文件名.flac", " ", null, null, "not-a-number"),
        )
    }

    @Test
    fun automaticAlbumsRejectFolderNamePseudoAlbumsEvenWithDifferentCase() {
        val folderTrackCounts = mapOf("Music/WeiXin" to 3, "Music/周杰伦" to 2)

        assertNull(libraryAlbumNameOf("weixin", "Music/WeiXin", folderTrackCounts))
        assertEquals("叶惠美", libraryAlbumNameOf("叶惠美", "Music/周杰伦", folderTrackCounts))
    }

    @Test
    fun automaticAlbumsRejectFilesDirectlyInImportRoot() {
        val folderTrackCounts = mapOf(
            "Music/月播" to 3,
            "Music/汪汪播放器" to 3,
        )

        assertNull(libraryAlbumNameOf("夜航", "Music/月播", folderTrackCounts))
        // 旧版汪汪播放器时期的导入根目录仍按导入根目录对待
        assertNull(libraryAlbumNameOf("夜航", "Music/汪汪播放器", folderTrackCounts))
    }

    @Test
    fun importFolderWithTwoTracksFormsAlbumEvenWithoutAlbumTag() {
        val folderTrackCounts = mapOf(
            "Music/月播/夜航" to 2,
            "Music/汪汪播放器/夜航" to 2,
        )

        assertEquals("夜航", libraryAlbumNameOf("", "Music/月播/夜航", folderTrackCounts))
        assertEquals("夜航", libraryAlbumNameOf("夜航", "Music/月播/夜航", folderTrackCounts))
        // 旧版根目录下的已导入专辑改名后继续识别
        assertEquals("夜航", libraryAlbumNameOf("", "Music/汪汪播放器/夜航", folderTrackCounts))
        assertEquals("夜航", libraryAlbumNameOf("夜航", "Music/汪汪播放器/夜航", folderTrackCounts))
    }

    @Test
    fun importFolderWithSingleTrackDoesNotFormAlbum() {
        val folderTrackCounts = mapOf(
            "Music/月播/夜航" to 1,
            "Music/汪汪播放器/夜航" to 1,
        )

        assertNull(libraryAlbumNameOf("", "Music/月播/夜航", folderTrackCounts))
        assertNull(libraryAlbumNameOf("", "Music/汪汪播放器/夜航", folderTrackCounts))
    }

    private fun sampleAlbum() = ImportedAlbum(
        id = importedAlbumId("content://tree/album"),
        name = "专辑",
        artist = "艺术家",
        treeUri = "content://tree/album",
        tracks = listOf(
            ImportedAlbumTrack(
                uri = "content://document/source.flac",
                title = "第一首",
                artist = "艺术家",
                album = "专辑",
                durationMs = 1_234,
                format = "FLAC",
                mimeType = "audio/flac",
                cueStartMs = 60_000,
                isCueTrack = true,
            ),
        ),
        frontCoverUri = "content://document/front.jpg",
        backCoverUri = "content://document/back.jpg",
        description = "简介",
        descriptionEdited = true,
    )

    private fun albumSource(
        name: String,
        artist: String,
        tracks: List<LibraryAlbumTrack<String>>,
        importedAlbum: ImportedAlbum? = null,
    ): LibraryAlbumSource<String> = LibraryAlbumSource(
        name = name,
        artist = artist,
        tracks = tracks,
        importedAlbum = importedAlbum,
    )

    private fun albumTrack(
        key: String,
        title: String,
        artist: String,
        album: String,
    ): LibraryAlbumTrack<String> = LibraryAlbumTrack(
        value = key,
        metadataKey = key,
        title = title,
        artist = artist,
        album = album,
    )

    private fun sha256Prefix(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(16)
}
