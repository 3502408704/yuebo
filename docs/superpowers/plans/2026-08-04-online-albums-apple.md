# 在线专辑与 Apple Music Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with checkpoints. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Apple Music and shared song/album browsing, album details, full-album playback, and serial full-album downloads to all five online platforms.

**Architecture:** Each platform keeps its own Catalog and JSON mapping. All search results become `OnlineTrack`; a pure grouping function creates `OnlineAlbum` values from the merged paginated results. `NativeMusicViewModel` owns album state, temporary album queues, URL resolution, and serial MediaStore downloads; Compose only displays state and sends explicit actions.

**Tech Stack:** Kotlin, Android Jetpack Compose Material 3, `org.json`, `HttpURLConnection`, Kotlin coroutines, MediaStore, existing BASS playback and `YaohuApiClient`.

## Global Constraints

- API root is `https://api.yaohud.cn`; Apple endpoint is `GET /api/music/apple` with documented `msg`, `n`, `g`, `loc`, and `type` parameters.
- The API key is read only from uncommitted `android/local.properties` as `YAOHU_API_KEY`; never place the real key in Kotlin, tests, logs, screenshots, or committed documents.
- Album groups are built from the current platform and query's paginated song results by nonblank `album`; no undocumented album endpoint or guessed parameter is added.
- Online URLs remain process-local and are never written to SharedPreferences, persistent queues, playlists, logs, or Cast history.
- Reuse `YaohuApiClient`, `OnlineTrack`, existing BASS playback, existing MediaStore download behavior, and existing online error handling; do not create a second player or proxy.
- All user-facing text and status feedback is Simplified Chinese.
- All focus, TalkBack browsing, tab selection, and list focus changes must not start network requests, parsing, playback, or downloads; only explicit actions may do so.
- Interactive targets are at least `48dp`; important status, progress, and error changes are visible and exposed through a polite live region.
- All shell commands start with `rtk`; Android builds run from `D:\musicplayer\android`; file edits use `apply_patch`; do not create a worktree or commit automatically.
- Before claiming completion run `rtk git diff --check`, `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, and `:app:assembleRelease`.

---

## Files and Responsibilities

- Create `android/app/src/main/kotlin/com/example/local_music_player/YaohuAppleMusicApi.kt` for Apple-only request models, response mapping, and Catalog behavior.
- Modify `android/app/src/main/kotlin/com/example/local_music_player/YaohuNeteaseApi.kt` only to add `MusicPlatform.Apple` to the existing shared platform enum and preserve existing Netease behavior.
- Modify `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt` for the shared `OnlineAlbum` model and grouping function beside `OnlineTrack`.
- Modify `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt` for Apple dispatch, album state, album playback, and serial album download coordination.
- Modify `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt` for the song/album tabs and shared album list/detail composables.
- Modify `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt` for album-detail navigation, return focus state, and page title wiring.
- Create `android/app/src/test/kotlin/com/example/local_music_player/YaohuAppleMusicApiTest.kt` for Apple request and JSON mapping tests.
- Create `android/app/src/test/kotlin/com/example/local_music_player/OnlineAlbumTest.kt` for platform-isolated album grouping and ordering tests.
- Modify `android/app/src/test/kotlin/com/example/local_music_player/OnlinePlatformStateTest.kt` for the Apple platform order and shared state contract.

### Task 1: Add Apple Music Catalog

**Files:**

- Create: `android/app/src/test/kotlin/com/example/local_music_player/YaohuAppleMusicApiTest.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/YaohuNeteaseApi.kt:9-14`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/YaohuAppleMusicApi.kt`

**Interfaces:**

- Consumes: existing `YaohuHttp`, `YaohuApiException`, `YaohuQuality`, and `OnlineTrack`.
- Produces: `MusicPlatform.Apple`, `AppleCatalog`, `AppleSearchResult`, `AppleResolvedTrack`, `AppleDefaultResolution`, and `AppleSearchResult.toOnlineTrack()`.

- [ ] **Step 1: Write the failing Apple Catalog tests**

Add this fixture client and tests:

```kotlin
package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class AppleFakeHttp(private val bodies: ArrayDeque<String>) : YaohuHttp {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    override fun get(path: String, parameters: Map<String, String>): String {
        requests += path to parameters
        return bodies.removeFirst()
    }
}

class YaohuAppleMusicApiTest {
    @Test
    fun search_reads_songs_and_forwards_count() {
        val http = AppleFakeHttp(ArrayDeque(listOf(
            """{"code":200,"data":{"songs":[{"n":1,"name":"晴天","singer":"周杰伦","album":"叶惠美","pay":""}]}}""",
        )))

        val result = AppleCatalog(http).search("周杰伦", limit = 45).single()

        assertEquals(1, result.sequence)
        assertEquals("晴天", result.title)
        assertEquals("叶惠美", result.album)
        assertEquals("/api/music/apple", http.requests.single().first)
        assertEquals("周杰伦", http.requests.single().second["msg"])
        assertEquals("45", http.requests.single().second["g"])
    }

    @Test
    fun resolve_reads_music_url_and_apple_metadata() {
        val http = AppleFakeHttp(ArrayDeque(listOf(
            """{"code":200,"data":{"songname":"晴天","singername":"周杰伦","album":"叶惠美","cover":"https://img","music_url":"https://audio/song.m4a"}}""",
        )))
        val source = AppleSearchResult("q", 1, "晴天", "周杰伦", "叶惠美", null)

        val resolved = AppleCatalog(http).resolve(source)

        assertEquals("https://audio/song.m4a", resolved.playUrl)
        assertEquals("m4a", resolved.extension)
        assertEquals("audio/mp4", resolved.mimeType)
        assertEquals(mapOf("msg" to "q", "n" to "1"), http.requests.single().second)
    }

    @Test
    fun invalid_business_code_and_empty_url_are_rejected_without_raw_response() {
        val error = assertFailsWith<YaohuApiException> {
            AppleCatalog(AppleFakeHttp(ArrayDeque(listOf("""{"code":403,"msg":"无权限"}""")))).search("q")
        }
        assertEquals("无权限", error.message)

        assertFailsWith<YaohuApiException> {
            AppleCatalog(AppleFakeHttp(ArrayDeque(listOf("""{"code":200,"data":{"music_url":""}}"""))))
                .resolve(AppleSearchResult("q", 1, "t", "a", "al", null))
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.YaohuAppleMusicApiTest"
```

Expected: FAIL because `MusicPlatform.Apple` and `AppleCatalog` do not exist yet.

- [ ] **Step 3: Implement the smallest Apple Catalog**

Add `Apple("Apple Music")` after `Netease` in the platform enum and create the new file with these contracts:

```kotlin
package com.example.local_music_player

import org.json.JSONArray
import org.json.JSONObject

object AppleDefaultResolution : YaohuQuality {
    override val apiValue = "default"
    override val label = "默认"
}

data class AppleSearchResult(
    val query: String,
    val sequence: Int,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
)

data class AppleResolvedTrack(
    val playUrl: String,
    val mimeType: String,
    val extension: String,
    val lyrics: String? = null,
)

class AppleCatalog(private val http: YaohuHttp = YaohuApiClient()) {
    fun search(query: String, limit: Int = 30): List<AppleSearchResult> {
        val data = request(mapOf("msg" to query, "g" to limit.toString()))
            .optJSONObject("data") ?: throw YaohuApiException("服务响应无效，请稍后重试")
        val songs = data.optJSONArray("songs") ?: JSONArray()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val sequence = song.optInt("n", 0)
                val title = song.optString("name").trim()
                if (sequence <= 0 || title.isBlank()) continue
                add(AppleSearchResult(
                    query = query,
                    sequence = sequence,
                    title = title,
                    artist = song.optString("singer").trim(),
                    album = song.optString("album").trim(),
                    artworkUrl = song.optString("cover").trim().ifBlank { null },
                ))
            }
        }
    }

    fun resolve(result: AppleSearchResult): AppleResolvedTrack {
        val data = request(mapOf("msg" to result.query, "n" to result.sequence.toString()))
            .optJSONObject("data") ?: throw YaohuApiException("服务响应无效，请稍后重试")
        val url = data.optString("music_url").trim()
        if (url.isBlank()) throw YaohuApiException("未获取到可播放的音频")
        return AppleResolvedTrack(url, "audio/mp4", "m4a")
    }

    private fun request(parameters: Map<String, String>): JSONObject {
        val root = try { JSONObject(http.get("/api/music/apple", parameters)) }
        catch (error: YaohuApiException) { throw error }
        catch (_: Exception) { throw YaohuApiException("服务响应无效，请稍后重试") }
        if (root.optInt("code") != 200) {
            throw YaohuApiException(root.optString("msg").trim().ifBlank { "服务请求失败，请稍后重试" })
        }
        return root
    }
}

fun AppleSearchResult.toOnlineTrack() = OnlineTrack(
    platform = MusicPlatform.Apple,
    query = query,
    sequence = sequence,
    platformId = null,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    format = "m4a",
)
```

The implementation must not send an undocumented `type` or quality value. Keep all failure messages free of the API key and raw authenticated URL.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same focused Gradle command. Expected: all `YaohuAppleMusicApiTest` tests pass.

### Task 2: Add Shared Album Grouping

**Files:**

- Create: `android/app/src/test/kotlin/com/example/local_music_player/OnlineAlbumTest.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt` after `OnlineTrack`

**Interfaces:**

- Consumes: `OnlineTrack` from QQ, Kuwo, Kugou, Netease, and Apple.
- Produces: `OnlineAlbum`, `OnlineAlbum.key`, and `groupOnlineAlbums(List<OnlineTrack>)`.

- [ ] **Step 1: Write failing grouping tests**

```kotlin
package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals

class OnlineAlbumTest {
    private fun track(platform: MusicPlatform, album: String, sequence: Int, title: String) =
        OnlineTrack(platform, "query", sequence, "id-$sequence", title, "artist-$sequence", album, null)

    @Test
    fun groups_nonblank_albums_in_first_seen_order_and_preserves_track_order() {
        val result = groupOnlineAlbums(listOf(
            track(MusicPlatform.Apple, " Album A ", 2, "second"),
            track(MusicPlatform.Apple, "Album B", 3, "third"),
            track(MusicPlatform.Apple, "Album A", 1, "first"),
            track(MusicPlatform.Apple, "", 4, "no album"),
        ))

        assertEquals(listOf("Album A", "Album B"), result.map { it.name })
        assertEquals(listOf("second", "first"), result[0].tracks.map { it.title })
    }

    @Test
    fun keeps_same_named_albums_separate_between_platforms_and_removes_duplicate_tracks() {
        val first = track(MusicPlatform.Apple, "Same", 1, "one")
        val duplicate = first.copy(title = "updated")
        val qq = track(MusicPlatform.QQ, "Same", 1, "qq")

        val result = groupOnlineAlbums(listOf(first, duplicate, qq))

        assertEquals(2, result.size)
        assertEquals(listOf("one"), result.first { it.platform == MusicPlatform.Apple }.tracks.map { it.title })
        assertEquals(MusicPlatform.QQ, result[1].platform)
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlineAlbumTest"
```

Expected: FAIL because `OnlineAlbum` and `groupOnlineAlbums` do not exist.

- [ ] **Step 3: Implement the pure grouping function**

Add the following model and function beside `OnlineTrack`:

```kotlin
data class OnlineAlbum(
    val platform: MusicPlatform,
    val query: String,
    val name: String,
    val tracks: List<OnlineTrack>,
) {
    val key: String get() = "${platform.name}:$name"
    val artists: String get() = tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().joinToString("、")
    val artworkUrl: String? get() = tracks.firstNotNullOfOrNull { it.artworkUrl }
}

fun groupOnlineAlbums(tracks: List<OnlineTrack>): List<OnlineAlbum> = tracks
    .filter { it.album.trim().isNotBlank() }
    .groupBy { it.platform to it.album.trim() }
    .map { (identity, values) ->
        OnlineAlbum(
            platform = identity.first,
            query = values.first().query,
            name = identity.second,
            tracks = values.distinctBy { it.key },
        )
    }
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same `OnlineAlbumTest` command. Expected: all grouping tests pass.

### Task 3: Integrate Apple, Album State, and Serial Download in the ViewModel

**Files:**

- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/OnlinePlatformStateTest.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/OnlineSearchPaginationTest.kt`

**Interfaces:**

- Consumes: `AppleCatalog`, `AppleSearchResult.toOnlineTrack()`, `OnlineAlbum`, `groupOnlineAlbums`, and existing `playOnline`, `downloadOnline`, and `resolveOnline` flows.
- Produces: `MusicUiState.onlineAlbums`, `MusicUiState.onlineAlbumDownloadKey`, `MusicUiState.onlineAlbumDownloadProgress`, `playOnlineAlbum(OnlineAlbum)`, and `downloadOnlineAlbum(OnlineAlbum)`.

- [ ] **Step 1: Add failing album-download state assertions**

Extend `OnlinePlatformStateTest` with:

```kotlin
@Test
fun online_album_download_state_starts_idle() {
    val state = MusicUiState()

    assertTrue(state.onlineAlbums.isEmpty())
    assertNull(state.onlineAlbumDownloadKey)
    assertNull(state.onlineAlbumDownloadProgress)
}
```

Add `import kotlin.test.assertNull` to that test file.

- [ ] **Step 2: Run the affected tests and verify they fail**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlinePlatformStateTest"
```

Expected: FAIL because the three album-download state properties are not yet available.

- [ ] **Step 3: Add Apple dispatch and album state**

Add an `AppleCatalog` property beside the other Catalog instances. Extend `MusicUiState` with:

```kotlin
val onlineAlbums: List<OnlineAlbum> = emptyList(),
val onlineAlbumDownloadKey: String? = null,
val onlineAlbumDownloadProgress: String? = null,
```

In both the initial search and pagination merge paths, set:

```kotlin
onlineAlbums = groupOnlineAlbums(merged)
```

Extend the search dispatch:

```kotlin
MusicPlatform.Apple -> appleCatalog.search(query, limit).map(AppleSearchResult::toOnlineTrack)
```

Extend `onlineQuality` with `MusicPlatform.Apple -> AppleDefaultResolution`, and extend `resolveOnline` with:

```kotlin
MusicPlatform.Apple -> appleCatalog.resolve(track.toAppleSearchResult()).let {
    OnlineResolvedTrack(it.playUrl, "默认", it.mimeType, it.extension, it.lyrics)
}
```

Add the exact conversion helper:

```kotlin
private fun OnlineTrack.toAppleSearchResult() = AppleSearchResult(
    query = query,
    sequence = sequence,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
)
```

Ensure every `when (platform)` in `NativeMusicViewModel.kt` has an Apple branch. Do not add Apple quality controls to settings because the API contract does not document a quality value.

- [ ] **Step 4: Add explicit album playback**

Implement:

```kotlin
fun playOnlineAlbum(album: OnlineAlbum) {
    if (album.tracks.isEmpty()) return
    playOnline(album.tracks, 0)
}
```

The method must not resolve or play on focus, tab changes, or album list composition. It must use the existing temporary online queue mapping so album URLs remain non-persistent.

- [ ] **Step 5: Refactor single-track download into a reusable suspend operation**

Extract the current `downloadOnline` MediaStore body into a suspend helper with this contract:

```kotlin
private suspend fun downloadOnlineTrack(track: OnlineTrack): Result<OnlineResolvedTrack>
```

The helper must resolve the current download quality, insert an `IS_PENDING` MediaStore row, stream via `HttpURLConnection`, clear pending only after the copy succeeds, delete the row on every failure, and return the resolved track without changing UI state. Keep `downloadOnline(track)` as the explicit single-track wrapper that sets/clears its per-track busy state and calls `refresh` after success.

- [ ] **Step 6: Implement serial full-album download**

Add:

```kotlin
fun downloadOnlineAlbum(album: OnlineAlbum) {
    if (album.tracks.isEmpty() || _state.value.onlineAlbumDownloadKey != null) return
    viewModelScope.launch {
        var succeeded = 0
        val failed = mutableListOf<String>()
        _state.value = _state.value.copy(
            onlineAlbumDownloadKey = album.key,
            onlineAlbumDownloadProgress = "正在下载专辑《${album.name}》 0/${album.tracks.size}",
        )
        album.tracks.forEachIndexed { index, track ->
            _state.value = _state.value.copy(
                onlineAlbumDownloadProgress = "正在下载专辑《${album.name}》 ${index + 1}/${album.tracks.size}：${track.title}",
            )
            downloadOnlineTrack(track).onSuccess { succeeded++ }.onFailure { failed += track.title }
        }
        refresh(_state.value.permissionGranted)
        _state.value = _state.value.copy(
            onlineAlbumDownloadKey = null,
            onlineAlbumDownloadProgress = null,
            status = if (failed.isEmpty()) {
                "专辑下载完成：${album.name}，共 ${succeeded} 首"
            } else {
                "专辑下载完成：成功 ${succeeded} 首，失败 ${failed.size} 首"
            },
        )
    }
}
```

Keep exceptions local to each track so one failed URL does not prevent later tracks. The live-region string must be visible in `MusicUiState` and must not contain URLs or exceptions.

- [ ] **Step 7: Run the affected unit tests and compile**

Run:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlinePlatformStateTest --tests com.example.local_music_player.OnlineSearchPaginationTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Expected: focused tests pass and Kotlin compilation exits with code 0.

### Task 4: Add Song/Album Tabs and Album Detail Navigation

**Files:**

- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**

- Consumes: `MusicUiState.onlineAlbums`, `OnlineAlbum`, `playOnlineAlbum`, `downloadOnlineAlbum`, existing `onPlay`, and existing `onDownload`.
- Produces: `OnlineViewMode`, `OnlineScreen` tab callbacks, `OnlineAlbumDetailScreen`, and `Screen.OnlineAlbumDetail` navigation.

- [ ] **Step 1: Add pure tab-state tests before UI changes**

Add a small pure enum/helper test in `OnlineAlbumTest.kt`:

```kotlin
@Test
fun album_tab_does_not_change_the_search_request_contract() {
    assertEquals(listOf(OnlineViewMode.Songs, OnlineViewMode.Albums), OnlineViewMode.entries)
}
```

Define `OnlineViewMode` only after observing this test fail.

- [ ] **Step 2: Implement the minimal tab state and album list**

Add:

```kotlin
internal enum class OnlineViewMode { Songs, Albums }
```

Extend `OnlineScreen` with `viewMode`, `albums`, `onViewModeChange`, `onOpenAlbum`, and `onDownloadAlbum`. Render Material 3 tabs labeled exactly `歌曲` and `专辑`; tab selection only updates local/display state and never invokes search. Keep the current song list behavior unchanged when `Songs` is selected.

Each album row must be one clickable semantic container with a concise description containing album name, artist text, and song count. Use `combinedClickable` for the visible long-press affordance, expose a visible `Download` action after long press, and add `CustomAccessibilityAction("下载整张专辑")` while an album is not downloading. Disable the row's download action when `state.onlineAlbumDownloadKey == album.key`. Do not expose child text or icons as duplicate accessible nodes.

- [ ] **Step 3: Implement the detail screen**

Add these composable contracts in `OnlineScreen.kt`:

```kotlin
@Composable
fun OnlineAlbumDetailScreen(
    album: OnlineAlbum,
    state: MusicUiState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrack: (List<OnlineTrack>, Int) -> Unit,
    onDownloadTrack: (OnlineTrack) -> Unit,
)
```

It must show a page title/pane title, an explicit `播放全部` control with a minimum `48dp` height, the album's tracks, and the existing single-track long-press/custom download behavior. The detail screen must not make a network request during composition or focus changes.

- [ ] **Step 4: Wire navigation and focus restoration**

Add `Screen.OnlineAlbumDetail` in `MusicApp.kt`, keep `selectedOnlineAlbumKey` and the preceding album-list focus key in the parent `MusicApp`, and route system Back through the existing app-level navigation. Opening an album stores its key; returning selects the online albums view and targets the original row or the next stable row if it no longer exists. Pass `viewModel::playOnlineAlbum`, `viewModel::playOnline`, `viewModel::downloadOnline`, and `viewModel::downloadOnlineAlbum` to the correct screen actions.

- [ ] **Step 5: Expose progress and result status**

In `OnlineScreen`, render `state.onlineAlbumDownloadProgress` as visible text with `liveRegion = LiveRegionMode.Polite`, disable repeat album download while it is non-null, and leave the existing single-track status behavior intact. Keep tab labels, album names, song counts, errors, and completion messages in Simplified Chinese.

- [ ] **Step 6: Run compile and inspect the UI tree**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Expected: exit code 0. On a connected Android device, launch the app and use the repository smoke checklist to verify that tabs, album rows, detail navigation, `播放全部`, visible download buttons, and custom accessibility actions appear without duplicate unnamed nodes.

### Task 5: Full Verification and Regression Checkpoint

**Files:**

- Modify only the test files or source files required by failures discovered during verification; do not revert unrelated dirty-worktree files.

- [ ] **Step 1: Run all unit tests**

From `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
```

Expected: exit code 0 with no failed tests.

- [ ] **Step 2: Check whitespace and compile**

From `D:\本地音乐播放器` run:

```powershell
rtk git diff --check
```

Then from `D:\musicplayer\android` run:

```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Expected: no diff-check output and successful compilation.

- [ ] **Step 3: Build the release APK**

Run:

```powershell
rtk cmd /c "gradlew.bat :app:assembleRelease"
```

Expected: `BUILD SUCCESSFUL`. Do not update version metadata or release files unless the user separately requests a release.

- [ ] **Step 4: Perform device smoke checks**

Use `scripts/smoke-checklist.md` and the accessibility checklist to verify:

- Search and playback on QQ, Kuwo, Kugou, Netease, and Apple Music.
- Song/album tab behavior and album grouping for an album-name query.
- Album detail, play all, single download, album long-press download, progress, partial failure, and duplicate-action disabling.
- TalkBack names, custom actions, 48dp targets, live regions, Back behavior, and detail return focus.
- Local playback, DLNA, Chromecast, Bluetooth routing, and logcat free of this app's FATAL/ANR.

- [ ] **Step 5: Report evidence without a completion claim until every required gate passes**

Record the actual command results, any device limitations, and any remaining verification gap. Do not claim completion if a required build, test, diff check, or device path was not run successfully.
