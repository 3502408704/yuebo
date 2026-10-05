# 网易云流媒体控制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 网易云在线曲目在网络缓冲时不误跳歌，并保留定位、快进快退、倍速和 A-B 循环。

**Architecture:** BASS URL 源流始终以非分块解码流创建，并由 Tempo 流提供变速。输出线程依据 BASS 的活动状态区分“暂时缺数据”和“真正结束”；所有 BASS 读写通过同一短时锁串行化。ViewModel 在可恢复的缓冲不足定位时有限重试，而地址解析失败只停止当前曲目。

**Tech Stack:** Kotlin、Jetpack Compose、BASS 2.4、BASS_FX、kotlin.test、Android Gradle。

## Global Constraints

- 所有命令以 `rtk` 开头；Gradle 从 `D:\musicplayer\android` 执行。
- 文件只通过 `apply_patch` 修改；不创建 worktree、不提交、不删除用户文件。
- 不新增依赖；不输出或提交 `YAOHU_API_KEY`。
- UI 保留中文语义、48dp 目标，不能因无障碍焦点移动发起网络请求。
- 仅修复流媒体播放控制，不改变其他平台接入边界。

---

### Task 1: 网络缓冲结束判定

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeBassPlayer.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/NetworkStreamPlaybackTest.kt`

**Interfaces:**
- Produces: `internal fun shouldWaitForNetworkData(isStreaming: Boolean, channelState: Int): Boolean`
- Consumes: `BASS.BASS_ACTIVE_PLAYING` and `BASS.BASS_ACTIVE_STOPPED`.

- [ ] **Step 1: Write the failing test**

```kotlin
class NetworkStreamPlaybackTest {
    @Test fun streamingDecoderWithNoPcmWaitsInsteadOfEnding() {
        assertTrue(shouldWaitForNetworkData(true, BASS.BASS_ACTIVE_PLAYING))
    }

    @Test fun stoppedDecoderEndsNormally() {
        assertFalse(shouldWaitForNetworkData(true, BASS.BASS_ACTIVE_STOPPED))
    }

    @Test fun localDecoderWithNoPcmEndsNormally() {
        assertFalse(shouldWaitForNetworkData(false, BASS.BASS_ACTIVE_PLAYING))
    }
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.NetworkStreamPlaybackTest"
```

Expected: compile failure because `shouldWaitForNetworkData` does not exist.

- [ ] **Step 3: Implement the minimal classification and use it in the output loop**

```kotlin
internal fun shouldWaitForNetworkData(isStreaming: Boolean, channelState: Int) =
    isStreaming && channelState == BASS.BASS_ACTIVE_PLAYING
```

In the `count <= 0` branch of `startOutputLoop`, continue after a short sleep when this helper is true. Only set `naturallyEnded` and invoke the completion listener when the channel is stopped.

- [ ] **Step 4: Run the focused test and confirm GREEN**

Run the command from Step 2.

Expected: three passing tests.

### Task 2: 恢复 Tempo、串行化 BASS 控制与可恢复定位

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeBassPlayer.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/NetworkSeekRetryPolicyTest.kt`

**Interfaces:**
- Produces: `internal fun networkSeekRetryDelayMs(attempt: Int): Long?`
- Consumes: `NativeBassPlayer.seek(positionMs): Boolean`.

- [ ] **Step 1: Write the failing retry-policy test**

```kotlin
class NetworkSeekRetryPolicyTest {
    @Test fun retriesBufferedSeekForFiveSeconds() {
        assertEquals(100L, networkSeekRetryDelayMs(0))
        assertEquals(100L, networkSeekRetryDelayMs(49))
        assertNull(networkSeekRetryDelayMs(50))
    }
}
```

- [ ] **Step 2: Run it and confirm RED**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.NetworkSeekRetryPolicyTest"
```

Expected: compile failure because `networkSeekRetryDelayMs` does not exist.

- [ ] **Step 3: Make the smallest BASS changes**

In `playUrl`, create the URL source with `BASS_STREAM_DECODE | BASS_STREAM_STATUS`, wrap it with:

```kotlin
stream = BASS_FX.BASS_FX_TempoCreate(
    source,
    BASS_FX.BASS_FX_FREESOURCE or BASS.BASS_STREAM_DECODE,
)
check(stream != 0) { "无法创建在线播放流：${BASS.BASS_ErrorGetCode()}" }
setSpeed(playbackSpeed)
```

Add one private monitor shared by output decoding, `seek`, `setSpeed`, `positionMs` and `releaseStream`. Keep lock hold times limited to BASS calls; never hold it during `AudioTrack.write` or a delay. Remove the streaming early returns from `seek` and `setSpeed`.

Add the policy:

```kotlin
internal fun networkSeekRetryDelayMs(attempt: Int): Long? = if (attempt < 50) 100L else null
```

In `NativeMusicViewModel.seek`, call `player.seek` for streaming tracks. When it fails with the normal “target has not downloaded yet” result, retry using this policy in a single cancellable job; update state only after success, and retain the prior position after timeout. A new seek or track change cancels the old job.

- [ ] **Step 4: Run focused tests and inspect compilation**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.NetworkStreamPlaybackTest --tests com.example.local_music_player.NetworkSeekRetryPolicyTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Expected: all focused tests pass and Kotlin compiles.

### Task 3: 恢复播放器控件与单曲失败停留

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/NeteaseEndpointRetryTest.kt`

**Interfaces:**
- Consumes: `NativeMusicViewModel.seek`, `NativeMusicViewModel.setSpeed`, `NeteaseEndpoint.playbackAttempts`.
- Produces: enabled streaming progress, long-press seek, speed and A-B controls.

- [ ] **Step 1: Add the failing retry assertion**

```kotlin
@Test fun hasExactlyFourAddressAttemptsForOneExplicitPlay() {
    assertEquals(4, NeteaseEndpoint.playbackAttempts().size)
}
```

- [ ] **Step 2: Run it before UI changes**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.NeteaseEndpointRetryTest"
```

Expected: the existing retry contract passes; retain it while changing failure handling.

- [ ] **Step 3: Re-enable the controls and stop automatic follow-up requests**

Remove `isStreaming` from the progress slider `enabled` predicate, long-press callbacks, `PlaybackSpeedMenu` enabled predicate, and `AbLoopMenu` eligibility. Retain their existing labels and semantic state descriptions.

In the terminal failure branch of `startNeteasePlaybackWithFallback`, set `suppressNeteaseAutoAdvance = true`, leave the current queue item selected, set `playing = false`, and use one visible Chinese failure message. Do not call `playFromQueue` or `advanceAfterStop` from that path.

- [ ] **Step 4: Run unit tests**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
```

Expected: all unit tests pass.

### Task 4: 验证、设备冒烟和安装

**Files:**
- No source changes expected.

- [ ] **Step 1: Check whitespace and build release**

```powershell
rtk git diff --check
rtk cmd /c "gradlew.bat :app:assembleRelease"
```

Expected: no diff whitespace errors; release build succeeds.

- [ ] **Step 2: Install to the existing device**

```powershell
rtk adb -s UGAA9P4XVSOR4LGU install -r D:\musicplayer\build\app\outputs\apk\release\app-release.apk
```

Expected: `Success`.

- [ ] **Step 3: Manual device QA**

Search and play a 网易云 track. During initial buffering, wait at least 15 seconds and confirm it does not advance to another queue item. Drag the slider forward and backward, long-press both track buttons, set 0.5x and 2.0x speed, and set/clear an A-B loop. Disconnect/reconnect network during playback and confirm no crash or automatic unknown-song jump. Trigger one unplayable track and confirm four address attempts occur at most and queue index remains unchanged.

- [ ] **Step 4: Inspect logcat**

```powershell
rtk adb -s UGAA9P4XVSOR4LGU logcat -d -v brief
```

Expected: no `FATAL EXCEPTION` or `ANR` attributed to `com.example.local_music_player`.
