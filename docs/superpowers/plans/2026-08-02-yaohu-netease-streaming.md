# 曜狐网易云流媒体重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用曜狐网易云直连搜索、播放和下载替换已删除的音源插件路线，同时将导航固定为本地媒体、在线媒体和设置。

**Architecture:** `YaohuApiClient` 集中处理构建密钥与 HTTP 请求；`NeteaseCatalog` 仅映射网易云搜索和单曲解析。`NativeMusicViewModel` 保留在线临时队列并把直连 URL 交给 BASS，下载通过 `MediaStore.Audio` 入库。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Android MediaStore、BASS、`HttpURLConnection`、kotlin.test。

## Global Constraints

- 所有用户可见文案、状态和语义使用简体中文；点击才发起搜索、解析或下载。
- 不新增第三方依赖，不恢复 QuickJS、MusicFree、LX 或本地流媒体代理。
- 密钥由未提交的 `local.properties` 注入 `BuildConfig`；不得写入源码、测试固件、日志或 Git。
- 所有下载写入 `Music/汪汪播放器/`；成功后刷新本地 MediaStore 曲库。
- 各平台分别声明播放/下载音质和 API 参数；当前只实现网易云。
- 不创建 worktree、不提交；所有 shell 命令以 `rtk` 开头，Android 构建从 `D:\musicplayer\android` 运行。

---

### Task 1: 曜狐网易云 API 与质量模型

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/YaohuNeteaseApi.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/YaohuNeteaseApiTest.kt`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/local.properties` (local-only key, never add to Git)

**Interfaces:**
- Produces `MusicPlatform.Netease`, `PlatformQuality`, `NeteaseSearchResult`, `NeteaseResolvedTrack`, `YaohuApiClient`, and `NeteaseCatalog`.
- `NeteaseCatalog.search(query, quality): List<NeteaseSearchResult>` and `resolve(result, quality): NeteaseResolvedTrack` run only on a background dispatcher.

- [ ] **Step 1: Write failing JSON mapping tests**

```kotlin
@Test fun search_maps_server_sequence_and_cover() {
    val results = NeteaseCatalog(FakeHttp(searchJson)).search("洛天依", NETEASE_EXHIGH)
    assertEquals(1, results.single().sequence)
    assertEquals("光与影的对白", results.single().title)
}

@Test fun resolve_prefers_vip_direct_url_and_reports_actual_level() {
    val track = NeteaseCatalog(FakeHttp(resolveJson)).resolve(result, NETEASE_LOSSLESS)
    assertEquals("https://m7.music.126.net/audio.mp3", track.playUrl)
    assertEquals("无损", track.actualQuality.label)
}
```

- [ ] **Step 2: Run the focused unit test and verify it fails because the catalog is absent**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.YaohuNeteaseApiTest"` from `D:\musicplayer\android`.

- [ ] **Step 3: Add the minimal API implementation**

```kotlin
enum class MusicPlatform(val displayName: String) { Netease("网易云") }
data class PlatformQuality(val apiValue: String, val label: String)
data class NeteaseSearchResult(val query: String, val sequence: Int, val title: String, val artist: String, val album: String, val artworkUrl: String?)
data class NeteaseResolvedTrack(val playUrl: String, val actualQuality: PlatformQuality, val lyrics: String?, val mimeType: String)
```

Use `HttpURLConnection` with timeouts, URL-encode `key`, `msg`, `n`, and `level`, validate JSON `code == 200`, and never include the key in thrown messages. Parse `vipmusic.url` first and top-level `url` only as a fallback.

- [ ] **Step 4: Inject the local key through BuildConfig**

Read `YAOHU_API_KEY` from `android/local.properties`, fail the debug/release build with a local message when absent, and create `BuildConfig.YAOHU_API_KEY`. Do not add the property value to a tracked file.

- [ ] **Step 5: Re-run the focused unit test and verify it passes**

Run the command in Step 2; expected result: all `YaohuNeteaseApiTest` tests pass.

### Task 2: View-model online state, platform preferences, playback and download

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/PlatformQualityPreferenceTest.kt`

**Interfaces:**
- Consumes Task 1 catalog and quality types.
- Produces `searchNetease`, `playNetease`, `downloadNetease`, `setPlatformPlaybackQuality`, `setPlatformDownloadQuality`, and online state required by Compose.

- [ ] **Step 1: Write failing preference and resolution tests**

```kotlin
@Test fun playback_and_download_quality_are_saved_independently_per_platform() {
    store.setPlayback(MusicPlatform.Netease, NETEASE_EXHIGH)
    store.setDownload(MusicPlatform.Netease, NETEASE_LOSSLESS)
    assertEquals(NETEASE_EXHIGH, store.playback(MusicPlatform.Netease))
    assertEquals(NETEASE_LOSSLESS, store.download(MusicPlatform.Netease))
}
```

- [ ] **Step 2: Run focused tests and verify they fail**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.PlatformQualityPreferenceTest"` from `D:\musicplayer\android`.

- [ ] **Step 3: Replace old stream state and source lifecycle with direct 网易云 state**

Remove `StreamTrack`, MusicFree source selection/import/test state, proxy URLs, source plugin calls, and old global `stream_quality` handling. Keep a temporary online queue made from current search results; do not serialize it into local queue persistence. Resolve on explicit play using that platform's playback quality and call `player.playUrl` directly. Show the server-returned quality in state. Reject online playback while casting with a Chinese status.

- [ ] **Step 4: Implement download with MediaStore cleanup**

Resolve using the platform download quality, insert a pending `MediaStore.Audio` item under `Music/汪汪播放器/`, copy the direct URL on `Dispatchers.IO`, clear `IS_PENDING`, then call `refresh(true)`. On every failure, delete the inserted URI and expose a Chinese error. Disable a result's download action while its sequence is active.

- [ ] **Step 5: Re-run focused tests and existing view-model tests**

Run the commands from Steps 2 and 1 plus `rtk cmd /c "gradlew.bat :app:testDebugUnitTest"`; expected result: all unit tests pass.

### Task 3: Remove the failed stream route from application assembly

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApplication.kt`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeBassPlayer.kt`

**Interfaces:**
- Consumes direct URL playback from Task 2.
- Produces an application graph without MusicFree/QuickJS/proxy objects.

- [ ] **Step 1: Delete obsolete application properties and shutdown calls**

Remove `MusicFreeSourceStore`, `MusicFreePluginManager`, and `StreamingProxyServer` construction and cleanup. Remove the QuickJS Gradle dependency. Keep `NativeBassPlayer.playUrl` direct; update only its comment to describe direct URL playback.

- [ ] **Step 2: Compile Kotlin to expose stale imports or references**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"` from `D:\musicplayer\android`.

- [ ] **Step 3: Remove each reported stale old-route reference without changing local playback, import, or casting code**

Re-run the command in Step 2 until it succeeds.

### Task 4: Compose navigation, online search/download, settings and player playlist

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes Task 2 online state and actions.
- Produces three root tabs and a player-owned playlist route.

- [ ] **Step 1: Replace root navigation**

Change root tabs to `Library`, `Online`, and `Settings`; remove `MineSection`, the “我的” home and source manager paths. Render settings directly in its root tab. Do not search or resolve during tab navigation, focus changes, or TalkBack exploration. Keep the mini player above the bottom navigation for all three roots.

- [ ] **Step 2: Implement the minimal online result list**

Provide a labeled search field and explicit 48dp search button. Result rows expose title, artist and album; click invokes play. Long-press only changes that row to reveal a 48dp download icon button. Add `CustomAccessibilityAction("下载")` to the row, invoke download only on that action, and give progress/error text `liveRegion = Polite`.

- [ ] **Step 3: Add player-owned playlist navigation**

Add a concise “播放列表” control on `NowPlayingScreen` that navigates to the existing `PlaylistScreen`. System Back first returns to the player and announces the player pane title. Preserve queue, history and saved playlist actions.

- [ ] **Step 4: Replace the global quality menu with per-platform settings**

Render “网易云默认播放音质” and “网易云默认下载音质” menus with only `NeteaseCatalog` supported qualities. Wire them to Task 2 preference setters. Their initial values are 极高 and 无损.

- [ ] **Step 5: Compile Kotlin and manually inspect semantics in the UI tree or on device**

Run `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"` from `D:\musicplayer\android`; verify every new action has one concise Chinese semantic name, is at least 48dp, and focus alone performs no network or file action.

### Task 5: Final regression verification

**Files:**
- Modify only files required by defects found in verification.

- [ ] **Step 1: Run whitespace and test gates**

Run `rtk git diff --check` from `D:\本地音乐播放器` and `rtk cmd /c "gradlew.bat :app:testDebugUnitTest"` from `D:\musicplayer\android`.

- [ ] **Step 2: Build the release APK**

Run `rtk cmd /c "gradlew.bat :app:assembleRelease"` from `D:\musicplayer\android`.

- [ ] **Step 3: Perform device smoke test when an Android device is available**

Verify 网易云 explicit search, result playback, long-press download, TalkBack “下载”, both quality menus, local library admission, player playlist back navigation, local playback, DLNA/Chromecast and Bluetooth. Check Logcat has no app FATAL/ANR.
