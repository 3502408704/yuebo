package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerWidgetProviderTest {
    @Test
    fun summary_contains_song_artist_and_lyric_once() {
        assertEquals(
            "歌曲：晴天，歌手：周杰伦，歌词：故事的小黄花",
            widgetAccessibilitySummary("晴天", "周杰伦", "故事的小黄花"),
        )
    }

    @Test
    fun blank_fields_have_explicit_spoken_state() {
        assertEquals(
            "歌曲：未知歌曲，歌手：未知歌手，歌词：暂无歌词",
            widgetAccessibilitySummary("", "", ""),
        )
    }

    @Test
    fun refreshes_only_when_the_complete_widget_snapshot_changes() {
        val current = PlayerWidgetSnapshot("晴天", "周杰伦", "故事的小黄花", true)

        assertFalse(shouldRefreshWidget(current, current.copy()))
        assertTrue(shouldRefreshWidget(current, current.copy(title = "七里香")))
        assertTrue(shouldRefreshWidget(current, current.copy(artist = "其他歌手")))
        assertTrue(shouldRefreshWidget(current, current.copy(lyric = "新的歌词")))
        assertTrue(shouldRefreshWidget(current, current.copy(playing = false)))
    }
}
