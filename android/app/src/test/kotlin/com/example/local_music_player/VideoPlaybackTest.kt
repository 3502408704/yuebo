package com.example.local_music_player

import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPlaybackTest {
    @Test
    fun isPanVideoFileNameRecognizesCommonFormats() {
        assertTrue(isPanVideoFileName("movie.mp4"))
        assertTrue(isPanVideoFileName("show.MKV"))
        assertTrue(isPanVideoFileName("clip.mov"))
        assertTrue(isPanVideoFileName("episode.webm"))
        assertTrue(isPanVideoFileName("record.avi"))
        assertTrue(isPanVideoFileName("live.ts"))
        assertTrue(isPanVideoFileName("raw.flv"))
        assertTrue(isPanVideoFileName("old.rmvb"))
        assertTrue(isPanVideoFileName("real.rm"))
    }

    @Test
    fun isPanVideoFileNameRejectsAudioAndNoExtension() {
        assertFalse(isPanVideoFileName("song.mp3"))
        assertFalse(isPanVideoFileName("album.flac"))
        assertFalse(isPanVideoFileName("folder"))
        assertFalse(isPanVideoFileName("archive.zip"))
        assertFalse(isPanVideoFileName(""))
    }

    @Test
    fun videoNextIndexAdvancesUntilLast() {
        assertEquals(1, videoNextIndex(0, 5))
        assertEquals(5, videoNextIndex(4, 5))
        assertNull(videoNextIndex(5, 5))
        assertNull(videoNextIndex(-1, 5))
        assertNull(videoNextIndex(0, -1))
    }

    @Test
    fun videoPreviousIndexStopsAtFirst() {
        assertEquals(0, videoPreviousIndex(1))
        assertNull(videoPreviousIndex(0))
        assertNull(videoPreviousIndex(-1))
    }

    @Test
    fun videoQueueUpdateOnlyAcceptsTheCurrentVideoAtTheTargetIndex() {
        assertTrue(canUpdateVideoQueue(1, listOf(1, 2), 0))
        assertFalse(canUpdateVideoQueue(1, listOf(2, 1), 0))
        assertFalse(canUpdateVideoQueue(1, listOf(1), 1))
        assertFalse(canUpdateVideoQueue(null, listOf(1), 0))
    }

    @Test
    fun episodePanelIsAvailableForEveryVideoQueue() {
        assertFalse(shouldShowVideoEpisodes(0))
        assertFalse(shouldShowVideoEpisodes(1))
        assertTrue(shouldShowVideoEpisodes(2))
    }

    @Test
    fun liveAndUnknownDurationVideosDoNotExposeSeekProgress() {
        assertTrue(isLiveVideoSource("bilibili", "live"))
        assertFalse(isLiveVideoSource("content", "media"))
        assertFalse(videoHasSeekableDuration(null, 0))
        assertTrue(videoHasSeekableDuration(null, 30_000))
    }

    @Test
    fun videoProgressPollingUsesFastPlayingAndSlowIdleIntervals() {
        assertEquals(250L, videoProgressPollingDelayMs(isPlaying = true))
        assertEquals(1_000L, videoProgressPollingDelayMs(isPlaying = false))
    }

    @Test
    fun systemBackgroundKeepsAudioWhileExplicitExitPauses() {
        assertEquals(VideoBackgroundMode.KeepAudioPlaying, videoBackgroundMode(systemLifecycleEvent = true))
        assertEquals(VideoBackgroundMode.PausePlayback, videoBackgroundMode(systemLifecycleEvent = false))
    }

    @Test
    fun backgroundPlaybackUsesEitherUiOrEnginePlayingState() {
        assertTrue(shouldKeepVideoAudioPlaying(statePlaying = true, enginePlaying = false))
        assertTrue(shouldKeepVideoAudioPlaying(statePlaying = false, enginePlaying = true))
        assertFalse(shouldKeepVideoAudioPlaying(statePlaying = false, enginePlaying = false))
    }

    @Test
    fun fallbackPositionKeepsTheFarthestKnownPositionAndClampsAtEnd() {
        assertEquals(12_000L, videoResumePosition(10_000, 12_000))
        assertEquals(12_000L, videoResumePosition(12_000, 10_000, durationMs = 20_000))
        assertEquals(19_999L, videoResumePosition(19_000, 25_000, durationMs = 20_000))
    }

    @Test
    fun isFfmpegFallbackFormatMatchesLegacyFormats() {
        assertTrue(isFfmpegFallbackFormat("clip.wmv"))
        assertTrue(isFfmpegFallbackFormat("old.asf"))
        assertTrue(isFfmpegFallbackFormat("episode.rmvb"))
        assertTrue(isFfmpegFallbackFormat("real.rm"))
        assertTrue(isFfmpegFallbackFormat("movie.mpg"))
        assertTrue(isFfmpegFallbackFormat("movie.MPEG"))
        assertFalse(isFfmpegFallbackFormat("movie.ts"))
        assertFalse(isFfmpegFallbackFormat("movie.m2ts"))
        assertFalse(isFfmpegFallbackFormat("movie.flv"))
        assertFalse(isFfmpegFallbackFormat("movie.mp4"))
        assertFalse(isFfmpegFallbackFormat("show.mkv"))
        assertFalse(isFfmpegFallbackFormat(""))
    }

    @Test
    fun isRmvbLikeVideoFileNameMatchesRealMedia() {
        assertTrue(isRmvbLikeVideoFileName("episode.rmvb"))
        assertTrue(isRmvbLikeVideoFileName("real.rm"))
        assertTrue(isRmvbLikeVideoFileName("old.RM"))
        assertTrue(isRmvbLikeVideoFileName("stream.rmm"))
        assertFalse(isRmvbLikeVideoFileName("movie.mp4"))
        assertFalse(isRmvbLikeVideoFileName("show.mkv"))
        assertFalse(isRmvbLikeVideoFileName(""))
        assertFalse(isRmvbLikeVideoFileName("archive.rar"))
    }

    @Test
    fun isExternalVideoFileNameRecognizesPlayableFormats() {
        assertTrue(isExternalVideoFileName("movie.mp4"))
        assertTrue(isExternalVideoFileName("show.MKV"))
        assertTrue(isExternalVideoFileName("clip.mov"))
        assertTrue(isExternalVideoFileName("record.avi"))
        assertTrue(isExternalVideoFileName("live.ts"))
        assertTrue(isExternalVideoFileName("raw.flv"))
        assertTrue(isExternalVideoFileName("episode.rmvb"))
        assertTrue(isExternalVideoFileName("real.rm"))
        assertTrue(isExternalVideoFileName("stream.rmm"))
        assertTrue(isExternalVideoFileName("legacy.wmv"))
        assertTrue(isExternalVideoFileName("old.asf"))
        assertTrue(isExternalVideoFileName("movie.mpg"))
        assertTrue(isExternalVideoFileName("movie.MPEG"))
        assertTrue(isExternalVideoFileName("phone.3gp"))
    }

    @Test
    fun isExternalVideoFileNameRejectsAudioAndNoExtension() {
        // webm 可能是纯音频，路由交给 MIME 兜底，不按扩展名直接判视频。
        assertFalse(isExternalVideoFileName("song.webm"))
        assertFalse(isExternalVideoFileName("song.mp3"))
        assertFalse(isExternalVideoFileName("album.flac"))
        assertFalse(isExternalVideoFileName("folder"))
        assertFalse(isExternalVideoFileName("archive.zip"))
        assertFalse(isExternalVideoFileName(""))
    }

    @Test
    fun shouldFallbackToFfmpegMatchesUnsupportedCodes() {
        assertEquals("application/x-mpegURL", normalizeVideoMimeType("m3u8"))
        assertTrue(shouldFallbackToFfmpeg(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertTrue(shouldFallbackToFfmpeg(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertTrue(shouldFallbackToFfmpeg(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(shouldFallbackToFfmpeg(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK))
        assertFalse(shouldFallbackToFfmpeg(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(shouldFallbackToFfmpeg(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun ffmpegOpenErrorsDistinguishMissingVideoTrackFromUnsupportedFormat() {
        assertEquals("该文件没有视频轨道，无法作为影视播放", ffmpegOpenErrorMessage(opened = true, hasVideoTrack = false))
        assertEquals("该视频格式暂不支持播放", ffmpegOpenErrorMessage(opened = false, hasVideoTrack = false))
    }

    @Test
    fun miniPlayerVisibilityUsesSharedCurrentItem() {
        assertTrue(shouldShowMiniPlayer(Screen.Library, true))
        // 浏览页：迷你播放器保持可见（仅全屏播放控制页隐藏）
        assertTrue(shouldShowMiniPlayer(Screen.Album, true))
        assertFalse(shouldShowMiniPlayer(Screen.VideoPlayer, true))
        assertFalse(shouldShowMiniPlayer(Screen.NowPlaying, true))
        assertFalse(shouldShowMiniPlayer(Screen.Lyrics, true))
        assertFalse(shouldShowMiniPlayer(Screen.Library, false))
        assertFalse(shouldShowMiniPlayer(Screen.Library, true, dismissed = true))
    }

    @Test
    fun gestureSeekTargetStartsAtTenSecondsAndGrowsWithSqrtCurve() {
        val duration = 60L * 60_000L
        val slop = 8f
        // 阈值处=起步 10s 偏移；480px 处=10s+45s；上限 120s
        assertEquals(40_000L, gestureSeekTargetMs(30_000L, slop, slop, duration))
        assertEquals(55_000L, gestureSeekTargetMs(0L, 480f + slop, slop, duration))
        assertEquals(130_000L, gestureSeekTargetMs(10_000L, 10_000f, slop, duration))
    }

    @Test
    fun gestureSeekTargetClampsToDurationAndZero() {
        val duration = 30_000L
        assertEquals(30_000L, gestureSeekTargetMs(10_000L, 999f, 8f, duration))
        assertEquals(0L, gestureSeekTargetMs(5_000L, -999f, 8f, duration))
        // 未知时长不设上限
        assertEquals(130_000L, gestureSeekTargetMs(10_000L, 10_000f, 8f, 0L))
    }

    @Test
    fun videoPlaybackRateClampsToHalfAndTripleSpeed() {
        assertEquals(0.5, videoPlaybackRate(0.1))
        assertEquals(1.25, videoPlaybackRate(1.25))
        assertEquals(3.0, videoPlaybackRate(9.9))
        // 音频倍速钳制保持不变
        assertEquals(2.0, remotePlaybackRate(3.0))
    }

    @Test
    fun videoSpeedOptionsMatchBilibiliTiers() {
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0), VideoSpeedOptions)
        assertEquals(2.0, VIDEO_HOLD_SPEED)
        assertEquals(listOf(15, 30, 45, 60), VideoSleepTimerOptions)
    }

    @Test
    fun formatSpeedLabelDropsTrailingZero() {
        assertEquals("2x", formatSpeedLabel(2.0))
        assertEquals("1.5x", formatSpeedLabel(1.5))
        assertEquals("0.5x", formatSpeedLabel(0.5))
    }
}
