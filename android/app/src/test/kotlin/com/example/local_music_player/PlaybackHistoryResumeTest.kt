package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 历史续播落点：重拉专辑/整季后按记录曲目定位那一首/那集，并决定是否带进度开播。 */
class PlaybackHistoryResumeTest {

    private fun onlineTrack(sequence: Int, platformId: String) = OnlineTrack(
        pluginId = "plugin-ximalaya",
        platform = "喜马拉雅",
        sourceName = "喜马拉雅",
        platformId = platformId,
        title = "第${sequence + 1}集",
        artist = "作者",
        album = "专辑",
        artworkUrl = null,
        durationMs = null,
    )

    private fun entry(track: OnlineTrack, trackIndex: Int, positionMs: Long) = AlbumPlaybackHistoryEntry(
        albumKey = "plugin-ximalaya:album",
        albumTitle = "专辑",
        artist = "作者",
        source = "online",
        albumId = "album",
        query = "album",
        trackReference = MediaReference(
            source = "online",
            key = track.key,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = 0L,
            format = "mp3",
            mimeType = "audio/mpeg",
            onlineTrack = track,
        ),
        trackIndex = trackIndex,
        positionMs = positionMs,
        durationMs = 0L,
        lastPlayedAtMs = 0L,
    )

    @Test
    fun landsOnRecordedTrackByKeyAndKeepsPosition() {
        val tracks = List(5) { onlineTrack(it, "id-$it") }
        val recorded = entry(onlineTrack(2, "id-2"), trackIndex = 2, positionMs = 90_000L)
        assertEquals(2 to 90_000L, historyResumeLanding(tracks, recorded))
    }

    @Test
    fun landsByStablePlatformIdWhenAlbumSequenceShifts() {
        // 专辑改版后曲目键整体漂移，但平台 ID 稳定：仍应命中并带进度。
        val tracks = List(5) { onlineTrack(it + 1, "id-$it") }
        val recorded = entry(onlineTrack(0, "id-3"), trackIndex = 0, positionMs = 60_000L)
        assertEquals(3 to 60_000L, historyResumeLanding(tracks, recorded))
    }

    @Test
    fun fallsBackToRecordedIndexWithoutPositionWhenTrackMissing() {
        val tracks = List(5) { onlineTrack(it, "other-$it") }
        val recorded = entry(onlineTrack(2, "id-2"), trackIndex = 2, positionMs = 90_000L)
        // 内容身份不确定：落到同一下标但从头播，不把旧进度套在别的条目上。
        assertEquals(2 to 0L, historyResumeLanding(tracks, recorded))
    }

    @Test
    fun returnsNullWhenLandingImpossible() {
        val tracks = List(2) { onlineTrack(it, "other-$it") }
        val recorded = entry(onlineTrack(9, "id-9"), trackIndex = 9, positionMs = 90_000L)
        assertNull(historyResumeLanding(tracks, recorded))
        assertNull(historyResumeLanding(emptyList(), recorded))
    }

    @Test
    fun negativeRecordedPositionIsClamped() {
        val tracks = List(3) { onlineTrack(it, "id-$it") }
        val recorded = entry(onlineTrack(1, "id-1"), trackIndex = 1, positionMs = -5L)
        assertEquals(1 to 0L, historyResumeLanding(tracks, recorded))
    }

    @Test
    fun missingTrackIdentityKeepsPositionOnIndexLanding() {
        // onlineTrack 还原不出来的历史条目：退用记录下标并保留进度（断点续播优先）。
        val tracks = List(5) { onlineTrack(it, "id-$it") }
        val recorded = entry(onlineTrack(2, "id-2"), trackIndex = 2, positionMs = 90_000L)
            .let { invalidIdentityEntry(it) }
        assertEquals(2 to 90_000L, historyResumeLanding(tracks, recorded))
    }

    /** 模拟 onlineTrack 缺失（音源被删除后无法还原）的历史条目。 */
    private fun invalidIdentityEntry(source: AlbumPlaybackHistoryEntry) = source.copy(
        trackReference = source.trackReference.copy(onlineTrack = null),
    )
}
