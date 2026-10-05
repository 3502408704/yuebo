package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTaskTest {
    @Test
    fun persisted_download_failure_message_never_contains_exception_text_or_url() {
        val network = downloadFailureMessage(java.io.IOException("https://audio.example/expired"))
        val api = downloadFailureMessage(IllegalStateException("https://api.example/error"))
        val other = downloadFailureMessage(IllegalStateException("disk at C:/secret"))

        assertEquals("网络错误，请稍后重试", network)
        assertEquals("下载失败，请重试", api)
        assertEquals("下载失败，请重试", other)
        assertFalse(listOf(network, api, other).any { it.contains("http") || it.contains("secret") })
    }

    @Test
    fun scheduled_or_running_transfer_failure_can_reach_a_terminal_state() {
        assertTrue(canFinalizeDownloadFailure(DownloadTaskStatus.QUEUED))
        assertTrue(canFinalizeDownloadFailure(DownloadTaskStatus.DOWNLOADING))
        assertTrue(canFinalizeDownloadFailure(DownloadTaskStatus.FINALIZING))
        assertFalse(canFinalizeDownloadFailure(DownloadTaskStatus.PAUSED))
    }

    @Test
    fun completed_task_rejects_late_pause_transition() {
        assertFalse(canTransitionDownloadTask(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.PAUSED))
        assertTrue(canTransitionDownloadTask(DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.PAUSED))
    }

    @Test
    fun finalizing_state_is_active_pausable_and_failure_capable() {
        // 分集合成阶段属于活动任务：仅可从下载中进入，可暂停重试、可失败终结。
        assertTrue(canTransitionDownloadTask(DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.FINALIZING))
        assertFalse(canTransitionDownloadTask(DownloadTaskStatus.QUEUED, DownloadTaskStatus.FINALIZING))
        assertFalse(canTransitionDownloadTask(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.FINALIZING))
        assertTrue(canTransitionDownloadTask(DownloadTaskStatus.FINALIZING, DownloadTaskStatus.PAUSED))
    }

    @Test
    fun only_completed_download_has_an_accessibility_announcement() {
        assertEquals("下载完成：晴天", downloadTaskAnnouncement("晴天", DownloadTaskStatus.COMPLETED))
        assertEquals(null, downloadTaskAnnouncement("晴天", DownloadTaskStatus.FAILED))
        assertEquals(null, downloadTaskAnnouncement("晴天", DownloadTaskStatus.PAUSED))
        assertEquals(null, downloadTaskAnnouncement("晴天", DownloadTaskStatus.DOWNLOADING))
    }

    @Test
    fun mine_summary_uses_active_count_or_empty_copy() {
        assertEquals("暂无任务", mineDownloadSummary(emptyList()))
        assertEquals(
            "2 项进行中",
            mineDownloadSummary(
                listOf(
                    DownloadTaskSnapshot("a", DownloadTaskStatus.DOWNLOADING),
                    DownloadTaskSnapshot("b", DownloadTaskStatus.QUEUED),
                ),
            ),
        )
    }

    @Test
    fun album_folder_name_sanitizes_and_truncates() {
        assertEquals("周杰伦 - 七里香", albumDownloadFolderName("周杰伦 - 七里香"))
        assertEquals("a_b_c", albumDownloadFolderName("a/b\\c"))
        assertEquals("在线专辑", albumDownloadFolderName("   "))
        assertEquals(80, albumDownloadFolderName("长".repeat(200)).length)
    }

    @Test
    fun album_download_name_prefixes_zero_padded_track_number() {
        assertEquals("歌手 - 歌名.mp3", downloadDisplayName("歌手", "歌名", "mp3"))
        assertEquals("01 - 歌手 - 歌名.mp3", downloadDisplayName("歌手", "歌名", "mp3", 1))
        assertEquals("10 - 歌手 - 歌名.mp3", downloadDisplayName("歌手", "歌名", "mp3", 10))
        assertEquals("歌手 - 歌名.mp3", downloadDisplayName("歌手", "歌名", "mp3", 0))
        assertEquals("01 - 歌手 - 歌名.jpg", downloadDisplayName("歌手", "歌名", "jpg", 1))
    }

    @Test
    fun flac_download_uses_flac_extension_and_mime() {
        assertEquals("audio/flac", downloadMimeType("flac"))
        assertEquals("歌手 - 歌名.flac", downloadDisplayName("歌手", "歌名", "flac"))
        assertEquals("flac", downloadDisplayName("歌手", "歌名", "flac").substringAfterLast('.'))
        assertEquals("audio/mpeg", downloadMimeType("mp3"))
    }

    @Test
    fun download_speed_is_formatted_in_b_kb_mb() {
        assertEquals("0 B/s", formatDownloadSpeed(0))
        assertEquals("512 B/s", formatDownloadSpeed(512))
        assertEquals("2.0 KB/s", formatDownloadSpeed(2048))
        assertEquals("5.0 MB/s", formatDownloadSpeed(5 * 1024 * 1024))
    }

    @Test
    fun remaining_time_is_formatted_in_chinese_units() {
        assertEquals("计算中", formatRemainingTime(0))
        assertEquals("计算中", formatRemainingTime(-1))
        assertEquals("约 5 秒", formatRemainingTime(5_000))
        assertEquals("约 1 分 20 秒", formatRemainingTime(80_000))
        assertEquals("约 2 小时 5 分", formatRemainingTime(7_500_000))
    }
}
