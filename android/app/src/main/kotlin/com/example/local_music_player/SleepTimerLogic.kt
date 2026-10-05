package com.example.local_music_player

internal fun remainingSleepTimerMs(
    totalMs: Long,
    startedElapsedMs: Long,
    nowElapsedMs: Long,
): Long = (totalMs - (nowElapsedMs - startedElapsedMs)).coerceAtLeast(0L)

internal sealed interface TrackSleepResult {
    data object Stop : TrackSleepResult
    data class Continue(val remainingTracks: Int) : TrackSleepResult
}

internal fun consumeTrackSleepTimer(remainingTracks: Int): TrackSleepResult =
    if (remainingTracks <= 1) TrackSleepResult.Stop
    else TrackSleepResult.Continue(remainingTracks - 1)

internal fun shouldStopTrackSleepTimerAfterPlaybackFailure(
    wasAutoAdvancing: Boolean,
    mode: SleepTimerMode,
): Boolean = wasAutoAdvancing && mode == SleepTimerMode.Tracks

internal fun shouldPauseForSleepTimer(
    capturedGeneration: Long,
    currentGeneration: Long,
    playing: Boolean,
): Boolean = capturedGeneration == currentGeneration && !playing
