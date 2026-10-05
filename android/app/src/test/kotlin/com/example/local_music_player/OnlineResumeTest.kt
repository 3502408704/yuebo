package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineResumeTest {
    @Test
    fun tracks_played_under_one_minute_do_not_enter_resume_range() {
        assertFalse(shouldSaveResume(0L, 300_000L))
        assertFalse(shouldSaveResume(59_999L, 300_000L))
    }

    @Test
    fun tracks_played_at_least_one_minute_enter_resume_range() {
        assertTrue(shouldSaveResume(60_000L, 300_000L))
        assertTrue(shouldSaveResume(120_000L, 300_000L))
    }

    @Test
    fun near_end_position_is_not_saved_as_resume() {
        assertTrue(shouldSaveResume(298_000L, 300_000L))
        assertFalse(shouldSaveResume(299_000L, 300_000L))
        assertFalse(shouldSaveResume(300_000L, 300_000L))
    }

    @Test
    fun unknown_duration_can_still_save_resume() {
        assertTrue(shouldSaveResume(60_000L, 0L))
    }

    @Test
    fun online_resume_track_round_trips_identity() {
        val tracks = listOf(
            OnlineTrack(
                pluginId = "plugin-qq",
                platform = "QQ音乐",
                sourceName = "QQ音乐",
                platformId = "001n4C3p1yv0FU",
                title = "晴天",
                artist = "周杰伦",
                album = "叶惠美",
                artworkUrl = "https://pic/1",
                durationMs = 269_000L,
            ),
            OnlineTrack(
                pluginId = "plugin-wy",
                platform = "网易云音乐",
                sourceName = "网易云音乐",
                platformId = "809795749",
                title = "《怯富贵》郭德纲x于谦",
                artist = "德云社郭德纲相声VIP",
                album = "郭德纲21年相声精选",
                artworkUrl = null,
                durationMs = 2_256_000L,
            ),
        )
        tracks.forEach { track ->
            val decoded = decodeOnlineResumeTrack(encodeOnlineResumeTrack(track))
            assertEquals(track, decoded)
        }
    }

    @Test
    fun invalid_or_unknown_resume_track_decodes_to_null() {
        assertNull(decodeOnlineResumeTrack("not json"))
        assertNull(decodeOnlineResumeTrack("""{"pluginId":"p","title":"t"}"""))
        assertNull(
            decodeOnlineResumeTrack(
                """{"pluginId":"p","platform":"QQ","sourceName":"QQ","platformId":"","title":"t","artist":"a","album":"al"}""",
            ),
        )
    }

    @Test
    fun online_resume_record_never_contains_play_url() {
        val encoded = encodeOnlineResumeTrack(
            OnlineTrack(
                pluginId = "plugin-qq",
                platform = "QQ音乐",
                sourceName = "QQ音乐",
                platformId = "MID",
                title = "晴天",
                artist = "周杰伦",
                album = "叶惠美",
                artworkUrl = null,
            ),
        )
        assertFalse(encoded.contains("http"))
        assertTrue(encoded.contains("晴天"))
        assertTrue(encoded.contains("QQ音乐"))
    }
}
