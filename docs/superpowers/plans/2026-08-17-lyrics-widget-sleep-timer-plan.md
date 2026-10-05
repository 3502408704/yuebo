# 歌词无障碍、桌面小组件与睡眠定时实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 QQ 歌词、把歌词朗读偏移改成秒数步进和 TalkBack 可调节控件，完善桌面小组件无障碍与样式，并增加按时间/按曲目的睡眠定时。

**Architecture:** 继续让 `NativeMusicViewModel` 作为播放、歌词、组件广播和定时状态的唯一协调点。将偏移、组件摘要和定时递减放入小型纯 Kotlin 辅助函数，Compose 与 RemoteViews 只负责展示和调用状态，不引入新依赖。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Android `TextToSpeech`、`MediaSession`、`RemoteViews`、`kotlin.test`、现有 Gradle/Android 工具链。

## Global Constraints

- 所有 shell 命令必须以 `rtk` 开头。
- Android 构建必须从 ASCII 联接路径 `D:\musicplayer\android` 执行。
- 文件修改使用 `apply_patch`，不创建 worktree、不自动提交、不删除用户文件。
- 不新增第三方依赖；不改变 QQ 音频播放的同平台回退规则。
- 所有 UI 文本和无障碍语义使用简体中文；可操作目标至少 48dp。
- 每个操作只保留一个无障碍语义节点；状态变化必须有可见文本或 live region。
- 先写失败测试并确认失败，再写最小生产实现。
- 不回退工作树中已有的用户改动或构建产物。

## File Map

- Create: `android/app/src/main/kotlin/com/example/local_music_player/LyricOffset.kt` — 秒/毫秒偏移边界、步进和中文播报。
- Create: `android/app/src/main/kotlin/com/example/local_music_player/SleepTimerLogic.kt` — 时间剩余值和曲目递减纯逻辑。
- Create: `android/app/src/test/kotlin/com/example/local_music_player/LyricOffsetTest.kt`.
- Create: `android/app/src/test/kotlin/com/example/local_music_player/SleepTimerLogicTest.kt`.
- Create: `android/app/src/test/kotlin/com/example/local_music_player/PlayerWidgetProviderTest.kt`.
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt` — TTS、QQ 歌词、组件广播、睡眠定时。
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt` — 偏移步进控件和睡眠定时面板/动画。
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/LyricTtsEngine.kt` — 引擎初始化时序和不可用回退。
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt` — QQ 歌词匹配和歌词专用跨平台回退。
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/PlayerWidgetProvider.kt` — 分离组件字段、摘要和统一刷新。
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MediaPlaybackService.kt` — 适配组件刷新参数。
- Modify: `android/app/src/main/res/layout/widget_player.xml` — 分层布局和无障碍节点。
- Modify: `android/app/src/main/res/drawable/widget_player_bg.xml` — 静态玻璃背景。
- Modify: `android/app/src/main/res/values/strings.xml` — 组件文案。

---

### Task 1: 建立纯逻辑失败测试

**Files:**
- Create: `android/app/src/test/kotlin/com/example/local_music_player/LyricOffsetTest.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/SleepTimerLogicTest.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/PlayerWidgetProviderTest.kt`

**Interfaces under test:** `stepLyricOffset`, `lyricOffsetAnnouncement`, `remainingSleepTimerMs`, `consumeTrackSleepTimer`, `widgetAccessibilitySummary`.

- [ ] **Step 1: Write the failing offset tests**

```kotlin
class LyricOffsetTest {
    @Test
    fun steps_by_one_second_and_clamps_to_two_seconds() {
        assertEquals(1_000L, stepLyricOffset(0L, 1))
        assertEquals(-1_000L, stepLyricOffset(0L, -1))
        assertEquals(2_000L, stepLyricOffset(2_000L, 1))
        assertEquals(-2_000L, stepLyricOffset(-2_000L, -1))
    }

    @Test
    fun announces_direction_and_zero() {
        assertEquals("不调整", lyricOffsetAnnouncement(0L))
        assertEquals("提前 1 秒", lyricOffsetAnnouncement(1_000L))
        assertEquals("延后 2 秒", lyricOffsetAnnouncement(-2_000L))
    }
}
```

- [ ] **Step 2: Write the failing timer tests**

```kotlin
class SleepTimerLogicTest {
    @Test
    fun time_remaining_never_goes_below_zero() {
        assertEquals(3_000L, remainingSleepTimerMs(10_000L, 1_000L, 8_000L))
        assertEquals(0L, remainingSleepTimerMs(10_000L, 1_000L, 11_000L))
    }

    @Test
    fun current_track_counts_as_first_track() {
        assertEquals(TrackSleepResult.Stop, consumeTrackSleepTimer(1))
        assertEquals(TrackSleepResult.Continue(2), consumeTrackSleepTimer(3))
    }
}
```

- [ ] **Step 3: Write the failing widget summary tests**

```kotlin
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
}
```

- [ ] **Step 4: Verify the red state**

Run:

```powershell
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:testDebugUnitTest --tests com.example.local_music_player.LyricOffsetTest --tests com.example.local_music_player.SleepTimerLogicTest --tests com.example.local_music_player.PlayerWidgetProviderTest --console=plain"
```

Expected: compilation fails because the five helper symbols do not exist yet.

---

### Task 2: Implement pure helpers and turn the tests green

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/LyricOffset.kt`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/SleepTimerLogic.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/PlayerWidgetProvider.kt`
- Test: the three Task 1 test files

**Interfaces:**

```kotlin
internal const val LYRIC_OFFSET_MIN_MS = -2_000L
internal const val LYRIC_OFFSET_MAX_MS = 2_000L
internal fun stepLyricOffset(currentMs: Long, deltaSeconds: Int): Long
internal fun lyricOffsetAnnouncement(offsetMs: Long): String
internal fun remainingSleepTimerMs(totalMs: Long, startedElapsedMs: Long, nowElapsedMs: Long): Long
internal sealed interface TrackSleepResult
internal fun consumeTrackSleepTimer(remainingTracks: Int): TrackSleepResult
internal fun widgetAccessibilitySummary(title: String, artist: String, lyric: String): String
```

- [ ] **Step 1: Implement offset helpers**

Clamp the current value and result to `-2000..2000ms`; each delta unit is exactly `1000ms`. Positive values announce “提前”，negative values announce “延后”，zero announces “不调整”。

- [ ] **Step 2: Implement timer helpers**

Use `(totalMs - (nowElapsedMs - startedElapsedMs)).coerceAtLeast(0L)`. `consumeTrackSleepTimer(1)` and all nonpositive values return `Stop`; larger values return `Continue(remaining - 1)`.

- [ ] **Step 3: Implement the widget summary helper**

Use explicit fallback labels `未知歌曲`、`未知歌手`、`暂无歌词`, and return exactly `歌曲：…，歌手：…，歌词：…`.

- [ ] **Step 4: Run the focused tests**

Run the Task 1 Gradle command again. Expected: all new tests pass.

---

### Task 3: Repair TTS lifecycle and replace the offset slider

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/LyricTtsEngine.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/LyricOffsetTest.kt`

- [ ] **Step 1: Preserve the sign contract with a regression test**

Keep a test asserting `lyricOffsetAnnouncement(1000L) == "提前 1 秒"`; this prevents the existing `position + offset` behavior from being inverted during UI changes.

- [ ] **Step 2: Make engine initialization deterministic**

Keep a temporary probe only for enumerating installed engines and shut it down after the callback. Add a ready/unavailable signal for the actual selected `TextToSpeech` instance. In `NativeMusicViewModel`, select the persisted package only after enumeration completes; if it is missing, select the first available package and set a visible Chinese fallback status. Never call `speak` until the selected instance reports `SUCCESS`.

- [ ] **Step 3: Replace the visible slider**

In `LyricTtsPanel`, replace the offset `Slider` with a stable 48dp decrease button, current-value text, and increase button. Invoke `stepLyricOffset` and persist milliseconds through the existing ViewModel method. Put `progressBarRangeInfo` and `setProgress` semantics on the single current-value node with a whole-second range `-2f..2f`; TalkBack swipe adjustments must invoke the same callback. Add a polite live-region announcement containing `lyricOffsetAnnouncement`.

Keep the parent toggle row as the only switch node and clear the visual `Switch` child semantics. Keep the engine selector as one named button with one node per menu item. Reset the spoken-line guard after an engine or offset change.

- [ ] **Step 4: Verify compile and focused tests**

```powershell
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests com.example.local_music_player.LyricOffsetTest --console=plain"
```

Expected: compile succeeds and offset tests pass.

---

### Task 4: Add QQ lyric fallback without changing audio playback

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/ShySourceFallbackTest.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/YaohuOtherMusicApiTest.kt`

- [ ] **Step 1: Add failing QQ lyric tests**

Cover a QQ result whose artist is `周杰伦/现场版` while the requested artist is `周杰伦`, and cover an empty Shy `qq_lrc` followed by a Yaohu `lrctxt` result. Use the existing fake `ShyMusicHttp` and `YaohuHttp` test helpers. Run both affected classes and verify the new fallback assertion fails before production changes.

- [ ] **Step 2: Make only lyric resolution lenient**

Keep `resolve()` and `resolveSkippingShy()` strict for audio. In the lyric-only path, use normalized title equality and artist containment, then read the existing QQ `QqResolvedTrack.lyrics` (`lrctxt`). Empty or malformed lyric fields count as no result.

- [ ] **Step 3: Add lyric-only cross-platform fallback**

Expose a resolver method that, only after QQ attempts fail, searches Netease, Kugou and Kuwo with the same title/artist and returns a nonblank lyrics field from a normalized match. It must not return a URL, alter the track platform, or populate the audio cache. Call it from `ensureLyrics` only for a QQ track, under the existing IO context and current-track ID guard.

- [ ] **Step 4: Run QQ regression tests**

```powershell
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:testDebugUnitTest --tests com.example.local_music_player.ShySourceFallbackTest --tests com.example.local_music_player.YaohuOtherMusicApiTest --console=plain"
```

Expected: existing audio fallback tests and new QQ lyric tests pass.

---

### Task 5: Make the desktop widget readable and restyle RemoteViews

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/PlayerWidgetProvider.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MediaPlaybackService.kt`
- Modify: `android/app/src/main/res/layout/widget_player.xml`
- Modify: `android/app/src/main/res/drawable/widget_player_bg.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/PlayerWidgetProviderTest.kt`

- [ ] **Step 1: Add the separate-field regression test**

Assert that blank title, artist and lyric produce one explicit summary without empty gaps. Run the widget test class before changing the renderer and confirm the test is red if the helper contract is not present.

- [ ] **Step 2: Separate broadcast payload fields**

Add title, artist, lyric and playing extras. Update `broadcastUpdate`, `broadcastLyric`, `MediaPlaybackService.show`, and ViewModel `broadcastWidgetLyric` to pass separate values. Keep a temporary default lyric argument only where needed to preserve existing call sites during the local patch.

`refreshAll` must set visible text, set the title node content description to `widgetAccessibilitySummary`, set artist/lyric display-only semantics, and keep the existing immutable PendingIntents. No widget instance means no work.

- [ ] **Step 3: Replace the XML layout locally**

Use a `FrameLayout` root with title, artist, current lyric and a bottom row of three 48dp controls. Keep previous/play-next functionality. Use a static rounded dark background with a 1dp translucent stroke, 16sp title, 12sp artist, 13sp lyric, ellipsis for overflow, and `importantForAccessibility="no"` on display-only artist/lyric nodes. Do not add bitmap processing.

- [ ] **Step 4: Compile and run widget tests**

```powershell
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests com.example.local_music_player.PlayerWidgetProviderTest --console=plain"
```

Expected: resource compilation, Kotlin compilation and widget tests pass.

---

### Task 6: Implement time and track sleep timers with an animated status

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/SleepTimerLogicTest.kt`

- [ ] **Step 1: Add edge-case tests**

Cover `consumeTrackSleepTimer(0) == Stop` and `consumeTrackSleepTimer(3) == Continue(2)`. Keep the current-track-is-first rule explicit in the test name.

- [ ] **Step 2: Expand ViewModel timer state**

Add a small `SleepTimerMode` enum (`None`, `Minutes`, `Tracks`) and state fields for mode, total milliseconds, remaining milliseconds and remaining tracks. Replace the wall-clock loop with `SystemClock.elapsedRealtime()` plus `remainingSleepTimerMs`; use `while (currentCoroutineContext().isActive)` and a 500ms update interval. Keep `cancelSleepTimer()` idempotent and add `startTrackSleepTimer(count)`.

- [ ] **Step 3: Integrate natural completion**

At the natural-completion point in `advanceAfterStop`, after remote premature-stop guards and before `nextIndex()`, consume the track timer. `Stop` clears the timer, sets `playing = false`, and reports the pause; `Continue(n)` updates the remaining count and enters the existing next-track path. Do not decrement in `next()` or `previous()`. Do not consume a track on a remote/network failure.

- [ ] **Step 4: Replace `SleepPanel`**

Add one mode selector, preserve the seven time presets, and add “播完本曲”“播完 3 曲”“播完 5 曲”“播完 10 曲”. Disable track options without a current track with a visible reason. When active, show a stable-size ring (`Canvas` or `CircularProgressIndicator`) with center text for remaining time/count, a polite live-region status, and a 48dp cancel action. Use a small `rememberInfiniteTransition` rotation/pulse only while active; animation must not be the only status signal.

- [ ] **Step 5: Run timer tests and compile**

```powershell
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests com.example.local_music_player.SleepTimerLogicTest --console=plain"
```

Expected: timer tests pass and Compose compiles.

---

### Task 7: Full verification and accessibility review

**Files:**
- Modify only if verification exposes a defect in the files from Tasks 1-6.
- Check: `scripts/smoke-checklist.md`, `docs/references/android-native-ui-design.md`, `android-accessibility-standard-zh.md`.

- [ ] **Step 1: Run all unit tests**

```powershell
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:testDebugUnitTest --console=plain"
```

Expected: all existing and new tests pass.

- [ ] **Step 2: Run the required Kotlin compilation and release build**

```powershell
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:compileDebugKotlin --console=plain"
rtk cmd /c "cd /d D:\musicplayer\android && C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat :app:assembleRelease --console=plain"
```

Expected: both commands exit successfully; existing deprecation warnings are non-blocking.

- [ ] **Step 3: Run the repository whitespace check**

```powershell
rtk git diff --check
```

Record whether pre-existing dirty files contribute errors; do not rewrite unrelated files solely to make this check clean.

- [ ] **Step 4: Perform the accessibility self-review**

Check one semantic node for the TTS switch, engine selector, offset stepper and timer mode; 48dp targets; readable Chinese labels; polite live-region feedback; no duplicate widget text nodes; stable focus after menus close; and no work triggered by focus movement.

- [ ] **Step 5: Perform device/smoke verification where a device is available**

Use the existing checklist to verify startup, local playback, lyric offset adjustment, TTS engine switch, QQ lyric fallback, widget TalkBack reading, widget controls, time timer, current-track timer and ten-track timer. Inspect `logcat` for app FATAL/ANR. If no device is available, report the exact skipped checks rather than claiming them complete.
