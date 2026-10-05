package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingCastSourceTest {
    @Test
    fun keepsContentUrisOnTheLocalMediaServerPath() {
        assertFalse(isRemoteCastSource("content", false))
        assertFalse(isRemoteCastSource("file", false))
    }

    @Test
    fun acceptsLocalAndOnlineSourcesForDlnaGateway() {
        assertTrue(isDlnaCastSource("content", hasOnlineTrack = false))
        assertTrue(isDlnaCastSource("baidupan", hasOnlineTrack = false))
        assertTrue(isDlnaCastSource("quarkpan", hasOnlineTrack = false))
        assertTrue(isDlnaCastSource("wwnetease", hasOnlineTrack = true))
        assertTrue(isDlnaCastSource("https", hasOnlineTrack = true))
        // file:// 仅用于应用自产文件（TTS 成品）经本机代理转投（2026-09-28 起）
        assertTrue(isDlnaCastSource("file", hasOnlineTrack = false))
    }

    @Test
    fun remoteSourceSchemeIsRecognizedForCasting() {
        // 插件音源解析出的在线流（online）保持可投送。
        assertTrue(isRemoteCastSource("online", hasOnlineTrack = false))
        assertTrue(isDlnaCastSource("https", hasOnlineTrack = true))
    }

    @Test
    fun videoCastCoversLocalAndCloudVideo() {
        assertTrue(isVideoCastSource("content"))
        assertTrue(isVideoCastSource("baidupan"))
        assertTrue(isVideoCastSource("quarkpan"))
        // B 站与在线剧集源已随服务端/B 站模块移除：不再出现在投送白名单。
        assertFalse(isVideoCastSource("onlinevideo"))
        assertFalse(isVideoCastSource("bilibili"))
        assertFalse(isVideoCastSource("mv"))
        assertFalse(isVideoCastSource("https"))
        assertFalse(isVideoCastSource(null))
    }

    @Test
    fun onlyRefreshableRemoteSourcesRetryOnce() {
        assertTrue(shouldRefreshCastSource(refreshable = true, attempt = 0))
        assertFalse(shouldRefreshCastSource(refreshable = true, attempt = 1))
        assertFalse(shouldRefreshCastSource(refreshable = false, attempt = 0))
    }

    @Test
    fun remotePlaybackRateStaysWithinThePublicDeviceRange() {
        assertTrue(remotePlaybackRate(0.1) == 0.5)
        assertTrue(remotePlaybackRate(1.25) == 1.25)
        assertTrue(remotePlaybackRate(3.0) == 2.0)
    }

    @Test
    fun identifiesPrematureStopsForAllRemotePlayback() {
        assertTrue(isPrematureRemoteStop(true, 5_000L, 1_000L))
        assertFalse(isPrematureRemoteStop(true, 5_000L, 5_000L))
        assertFalse(isPrematureRemoteStop(false, 5_000L, 1_000L))
    }

    @Test
    fun stale_remote_completion_cannot_advance_a_new_playback() {
        assertTrue(isCurrentPlaybackCompletion(7L, 7L))
        assertFalse(isCurrentPlaybackCompletion(7L, 8L))
    }

    @Test
    fun initialStreamingSeekKeepsPositionWhenDurationIsUnknown() {
        assertEquals(42_000L, streamSeekPosition(42_000L, 0L))
        assertEquals(5_000L, streamSeekPosition(42_000L, 5_000L))
    }

    @Test
    fun mediaStepKeepsBoundsForFastForwardAndRewind() {
        assertEquals(25_000L, seekStepPosition(15_000L, 10_000L, 200_000L, forward = true))
        assertEquals(200_000L, seekStepPosition(195_000L, 10_000L, 200_000L, forward = true))
        assertEquals(52_000L, seekStepPosition(42_000L, 10_000L, 0L, forward = true))
        assertEquals(5_000L, seekStepPosition(15_000L, 10_000L, 200_000L, forward = false))
        assertEquals(0L, seekStepPosition(3_000L, 10_000L, 200_000L, forward = false))
    }

    @Test
    fun unknownDurationNeverCountsAsReachedEnd() {
        assertFalse(hasReachedTrackEnd(0L, 0L))
        assertFalse(hasReachedTrackEnd(4_000L, 0L))
        assertTrue(hasReachedTrackEnd(5_000L, 5_000L))
    }

    @Test
    fun remoteStopWithUnknownDurationDoesNotAdvanceQueue() {
        assertFalse(shouldAdvanceAfterRemoteStop(true, 0L))
        assertTrue(shouldAdvanceAfterRemoteStop(true, 5_000L))
        assertTrue(shouldAdvanceAfterRemoteStop(false, 0L))
    }

    @Test
    fun remoteStopOnlyAdvancesWhenTheTrackReachedItsEnd() {
        assertTrue(isConfirmedRemoteCompletion(60_000L, 60_000L, stopped = false))
        assertTrue(isConfirmedRemoteCompletion(59_100L, 60_000L, stopped = true))
        assertFalse(isConfirmedRemoteCompletion(10_000L, 60_000L, stopped = true))
        assertFalse(isConfirmedRemoteCompletion(60_000L, 0L, stopped = true))
    }

    @Test
    fun returnPositionPreservesUnknownDuration() {
        assertEquals(7_000L, remoteResumePosition(8_000L, 1_000L, 0L))
        assertEquals(5_000L, remoteResumePosition(8_000L, 1_000L, 5_000L))
    }

    @Test
    fun castingAlwaysUsesTheRemoteSeekPath() {
        assertTrue(shouldUseRemoteSeek(true))
        assertFalse(shouldUseRemoteSeek(false))
    }

    @Test
    fun videoAutoAdvanceFiresOncePerEpisodeOnConfirmedEnd() {
        // 播完且有下一集：自动连播
        assertTrue(
            shouldAutoAdvanceVideo(
                videoId = 1L, advancedVideoId = -1L, reachedEnd = true,
                isLive = false, hasNextEpisode = true,
            )
        )
        // 同一集只推进一次：切换失败/重复上报不再连环切集
        assertFalse(
            shouldAutoAdvanceVideo(
                videoId = 1L, advancedVideoId = 1L, reachedEnd = true,
                isLive = false, hasNextEpisode = true,
            )
        )
    }

    @Test
    fun videoAutoAdvanceSkipsLiveStreamsPrematureStopsAndQueueEnd() {
        // 直播断流不是「播完」：不换台
        assertFalse(
            shouldAutoAdvanceVideo(
                videoId = 2L, advancedVideoId = -1L, reachedEnd = true,
                isLive = true, hasNextEpisode = true,
            )
        )
        // 提前停止（未到片尾）：不算播完
        assertFalse(
            shouldAutoAdvanceVideo(
                videoId = 3L, advancedVideoId = -1L, reachedEnd = false,
                isLive = false, hasNextEpisode = true,
            )
        )
        // 末集播完：保持停止态
        assertFalse(
            shouldAutoAdvanceVideo(
                videoId = 4L, advancedVideoId = -1L, reachedEnd = true,
                isLive = false, hasNextEpisode = false,
            )
        )
    }
}
