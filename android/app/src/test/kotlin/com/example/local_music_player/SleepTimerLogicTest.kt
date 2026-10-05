package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepTimerLogicTest {
    @Test
    fun time_remaining_never_goes_below_zero() {
        assertEquals(3_000L, remainingSleepTimerMs(10_000L, 1_000L, 8_000L))
        assertEquals(0L, remainingSleepTimerMs(10_000L, 1_000L, 11_000L))
    }

    @Test
    fun current_track_counts_as_first_track() {
        assertEquals(TrackSleepResult.Stop, consumeTrackSleepTimer(0))
        assertEquals(TrackSleepResult.Stop, consumeTrackSleepTimer(1))
        assertEquals(TrackSleepResult.Continue(2), consumeTrackSleepTimer(3))
        assertEquals(TrackSleepResult.Continue(9), consumeTrackSleepTimer(10))
    }

    @Test
    fun auto_advance_failure_stops_only_track_based_timers() {
        assertTrue(shouldStopTrackSleepTimerAfterPlaybackFailure(true, SleepTimerMode.Tracks))
        assertFalse(shouldStopTrackSleepTimerAfterPlaybackFailure(false, SleepTimerMode.Tracks))
        assertFalse(shouldStopTrackSleepTimerAfterPlaybackFailure(true, SleepTimerMode.Minutes))
    }

    @Test
    fun stale_remote_pause_is_not_sent_after_playback_resumes_or_changes() {
        assertTrue(shouldPauseForSleepTimer(4L, 4L, playing = false))
        assertFalse(shouldPauseForSleepTimer(4L, 5L, playing = false))
        assertFalse(shouldPauseForSleepTimer(4L, 4L, playing = true))
    }
}
