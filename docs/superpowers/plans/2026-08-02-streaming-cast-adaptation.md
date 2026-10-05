# 流媒体投送适配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让网易云、QQ、酷我、酷狗及后续统一 `OnlineTrack` 平台直接投送远程播放地址，并能在地址失效时单次刷新后回退本机。

**Architecture:** 在 `NativeMusicViewModel` 中把本地媒体源和在线 URL 统一成投送源决策；在线源复用现有进程内缓存，缓存失败只刷新一次，只有本地 `content://` 源启动 `LocalMediaServer`。DLNA 和 Chromecast 控制器继续负责协议细节，回切本机按同一决策调用 `playUrl` 或 `play`。

**Tech Stack:** Kotlin, Jetpack ViewModel/coroutines, existing BASS player, existing DLNA SOAP controller, Google Cast Framework 22.3.1, kotlin.test.

## Global Constraints

- 所有 shell 命令以 `rtk` 开头；Android 构建从 `D:\musicplayer\android` 执行。
- 修改文件使用 `apply_patch`；不创建 worktree、不提交、不删除用户文件。
- 不新增依赖、不写入或输出曜狐密钥；UI 不改动。
- 在线 URL 仅存活于应用进程；队列持久化不得保存或恢复 URL。
- 投送失败不得推进队列；必须停止已启动服务并恢复 BASS。

---

### Task 1: 建立投送源决策的失败测试

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt` (add the small source-kind helper next to existing top-level state helpers)
- Create: `android/app/src/test/kotlin/com/example/local_music_player/StreamingCastSourceTest.kt`

**Interfaces:**
- Produces: `isRemoteCastSource(uriScheme: String?, hasOnlineTrack: Boolean, hasLegacyStream: Boolean): Boolean` for later source resolution.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingCastSourceTest {
    @Test
    fun treatsOnlineMappingsAndVirtualSchemesAsRemote() {
        assertTrue(isRemoteCastSource("wwnetease", false, false))
        assertTrue(isRemoteCastSource("yaohu", true, false))
        assertTrue(isRemoteCastSource("wwstream", false, true))
    }

    @Test
    fun keepsContentUrisOnTheLocalMediaServerPath() {
        assertFalse(isRemoteCastSource("content", false, false))
        assertFalse(isRemoteCastSource("file", false, false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.StreamingCastSourceTest"
```

Expected: FAIL because `isRemoteCastSource` is not defined.

- [ ] **Step 3: Add the minimal helper**

```kotlin
internal fun isRemoteCastSource(uriScheme: String?, hasOnlineTrack: Boolean, hasLegacyStream: Boolean): Boolean =
    hasOnlineTrack || hasLegacyStream || uriScheme.equals("wwnetease", true) ||
        uriScheme.equals("yaohu", true) || uriScheme.equals("wwstream", true)

internal fun shouldRefreshCastSource(refreshable: Boolean, attempt: Int): Boolean = refreshable && attempt == 0
```

- [ ] **Step 4: Run the focused test**

Run the same command; expected: PASS.

---

### Task 2: Resolve direct online URLs for both cast protocols

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt:210-221, 1590-2030` (source resolver, queue cast path, start/return cast path)

**Interfaces:**
- Consumes: `NeteaseResolutionCache`, `onlineResolutionCache`, `onlineTrackById`, `streamUrlByTrackId`, `resolveOnline`, `NeteaseEndpoint.playbackAttempts`.
- Produces: a private `CastSource(url, mimeType, durationMs, refreshable)` decision and one retry for refreshable remote sources.

- [ ] **Step 1: Add the failing retry-policy assertion**

Extend `StreamingCastSourceTest` with the policy used by both protocols:

```kotlin
@Test
fun onlyRefreshableRemoteSourcesRetryOnce() {
    assertTrue(shouldRefreshCastSource(refreshable = true, attempt = 0))
    assertFalse(shouldRefreshCastSource(refreshable = true, attempt = 1))
    assertFalse(shouldRefreshCastSource(refreshable = false, attempt = 0))
}
```

Run the focused test and verify it fails because `shouldRefreshCastSource` is not defined; keep `NeteaseResolutionCacheTest` as the cache regression test.

- [ ] **Step 2: Implement the smallest resolver**

Add `shouldRefreshCastSource` beside `isRemoteCastSource`, then add a private `CastSource` data class and resolver in `NativeMusicViewModel`:

```kotlin
private data class CastSource(
    val url: String,
    val mimeType: String,
    val durationMs: Long,
    val refreshable: Boolean,
    val localServer: Boolean,
)
```

The resolver must:

1. Use the Netease cache and existing endpoint retry order for Netease.
2. Use `onlineResolutionCache` and `resolveOnline` for other `OnlineTrack` platforms.
3. Use an existing nonblank `streamUrlByTrackId` for legacy `StreamTrack`.
4. Start `LocalMediaServer` only when no online mapping exists.
5. On `forceRefresh`, remove only the matching cache entry before resolving.

- [ ] **Step 3: Replace both `playFromQueue` cast branches**

Use the resolver before `load`, pass `track.copy(mimeType = source.mimeType, durationMs = source.durationMs)` to protocol metadata, and retry once with `forceRefresh = true` only for `refreshable` sources. Keep existing state updates and failure recovery; do not call `advanceAfterStop`.

- [ ] **Step 4: Update `startDlna` and `startChromecast`**

Resolve before pausing BASS, load the direct remote URL for online tracks, and stop `mediaServer` only on failure/return as cleanup. For online load failure, invalidate and resolve once; for local source, preserve the single existing attempt.

- [ ] **Step 5: Run focused regression tests and compile**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.StreamingCastSourceTest --tests com.example.local_music_player.NeteaseResolutionCacheTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Expected: both test classes pass and Kotlin compilation exits 0.

---

### Task 3: Make remote-to-local return use the resolved URL

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt:2240-2310` (return-to-local callbacks and local source helper)
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt:2637-2715` (queue persistence)

**Interfaces:**
- Consumes: `CastSource` resolver and current remote position.
- Produces: local resume through `player.playUrl` for all online mappings; `player.play` for local tracks.

- [ ] **Step 1: Write the failing decision test**

Add to `StreamingCastSourceTest`:

```kotlin
@Test
fun onlineQueueEntriesAreNotPersistableEvenWhenTheyHaveAnOldUrl() {
    assertFalse(shouldPersistQueueTrack(-20L, setOf(-20L), emptySet()))
    assertFalse(shouldPersistQueueTrack(-21L, emptySet(), setOf(-21L)))
}
```

Run the focused test and observe the expected failure if the helper contract is changed; retain the existing `OnlinePlatformStateTest` assertions.

- [ ] **Step 2: Implement local resume helper**

Add a suspend helper that resolves cached online source, calls `player.playUrl`, retries once after cache removal if creation fails, and otherwise calls `player.play`. Update the current track’s MIME/format/duration from the resolved source before applying `localPlaybackState`.

- [ ] **Step 3: Wire both DLNA and Chromecast return callbacks**

After obtaining remote position, call the helper, then stop `mediaServer` and `streamingProxy`, restore state, and restart progress polling. On failure leave the remote session/state intact and show the existing retry status.

- [ ] **Step 4: Remove persisted URL use**

Stop writing `streamUrlByTrackId` into `queue_items`; `restoreStreamTrack` must ignore any legacy `url` field and start with an empty in-process cache. This keeps old preference data harmless and ensures URLs die with the process.

- [ ] **Step 5: Run tests**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.StreamingCastSourceTest --tests com.example.local_music_player.OnlinePlatformStateTest"
```

Expected: PASS.

---

### Task 4: Route playback speed to remote devices

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/ChromecastController.kt:92-105` (public playback-rate operation)
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt:2180-2205` (speed routing)

**Interfaces:**
- Consumes: existing `DlnaRendererController.play(speed)` and Cast SDK `RemoteMediaClient.setPlaybackRate(double)`.
- Produces: state/preferences update only after the remote request succeeds; paused DLNA stores the value without unexpectedly starting playback.

- [ ] **Step 1: Add the minimal controller method**

```kotlin
fun setPlaybackRate(rate: Double, onResult: (Result<Unit>) -> Unit) =
    withClient(onResult) { client -> client.setPlaybackRate(rate.coerceIn(0.5, 2.0)) }
```

- [ ] **Step 2: Route `NativeMusicViewModel.setSpeed`**

When casting, call DLNA `play(target)` only if currently playing; call Chromecast `setPlaybackRate(target)`. On success update `playbackSpeed`, persist it, and update the media session; on failure keep the old value and set the existing retry status. Keep the current BASS path for local playback.

- [ ] **Step 3: Compile and run speed-related unit tests**

```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.NetworkStreamPlaybackTest"
```

Expected: both commands exit 0.

---

### Task 5: Full verification and device install

**Files:**
- Modify: none beyond tasks above.

- [ ] **Step 1: Run repository gates**

```powershell
rtk git diff --check
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
rtk cmd /c "gradlew.bat :app:assembleRelease"
```

Expected: diff check has no output and all Gradle commands exit 0.

- [ ] **Step 2: Install the release APK**

Use the existing connected device workflow after locating the generated APK under `D:\musicplayer\build\app\outputs\apk\release\app-release.apk`; install with the repository’s `rtk adb` command and launch the app.

- [ ] **Step 3: Perform the smoke path**

On device, play an online track, open the device page, explicitly search and select a DLNA/Chromecast target, verify direct URL playback, pause/continue, seek, volume, speed, and return to local. Repeat with a local track to confirm `LocalMediaServer` behavior is unchanged. Record any device-unavailable limitation rather than claiming it was verified.
