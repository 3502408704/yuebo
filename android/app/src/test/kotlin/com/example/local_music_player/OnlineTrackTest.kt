package com.example.local_music_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTrackTest {

    @Test
    fun jsonRoundTripKeepsPlaylistTrackIdentityAndMetadata() {
        val track = OnlineTrack(
            pluginId = "netease",
            platform = "netease",
            sourceName = "网易云音乐",
            platformId = "song-123",
            title = "夜航",
            artist = "月播",
            album = "终点",
            artworkUrl = "https://cdn.example.test/cover.jpg",
            durationMs = 198_000,
        )

        val restored = OnlineTrack.fromJson(track.toJson())

        assertEquals(track, restored)
        assertEquals("netease:netease:song-123", restored?.key)
    }

    @Test
    fun malformedJsonIsRejectedWithoutCreatingAnUnplayableTrack() {
        val malformed = org.json.JSONObject()
            .put("pluginId", "netease")
            .put("platformId", "song-123")

        assertNull(OnlineTrack.fromJson(malformed))
    }

    @Test
    fun onlinePlaylistFolderExposesOnlyAllOnlineSongReferences() {
        val track = OnlineTrack(
            pluginId = "qq",
            platform = "qq",
            sourceName = "QQ 音乐",
            platformId = "song-9",
            title = "流光",
            artist = "月播",
            album = "现场",
            artworkUrl = null,
            durationMs = 210_000,
        )
        val folder = FavoriteFolder(
            id = "online-playlist",
            name = "在线歌单",
            createdAtMs = 1L,
            scope = MediaScope.Music,
            items = listOf(
                MediaReference(
                    source = "online",
                    key = track.key,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs ?: 0L,
                    format = "mp3",
                    mimeType = "audio/mpeg",
                    onlineTrack = track,
                ),
            ),
        )

        assertEquals(listOf(track), onlineTracksForFavoriteFolder(folder))
        assertNull(onlineTracksForFavoriteFolder(folder.copy(items = folder.items + folder.items)))
    }
}
