# 曜狐多平台在线媒体 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有曜狐网易云直连流媒体上增加 QQ、酷我、酷狗的搜索、播放和下载，并让在线媒体页通过平台下拉菜单选择站点。

**Architecture:** 保留 `YaohuApiClient` 作为唯一 HTTP 共享层，为 QQ、酷我、酷狗各增加独立 Catalog 和质量模型；ViewModel 将平台结果转换成统一的在线条目，复用现有 BASS 直连播放、解析缓存和 MediaStore 下载边界。旧 MusicFree/LX 兼容残留不在本计划中清理。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Android MediaStore、BASS、`HttpURLConnection`、`org.json`、kotlin.test。

## Global Constraints

- The only added dependency is test-only `org.json:json:20180813`; it is not packaged into the APK.

- 所有用户可见文案、状态和语义使用简体中文；平台菜单顺序固定为 `QQ`、`酷我`、`酷狗`、`网易云`。
- 不收集或发送 QQ/酷狗 Cookie，不新增生产依赖，不恢复 QuickJS、MusicFree、LX 或本地流媒体代理。
- 密钥仅由未提交的 `android/local.properties` 注入 `BuildConfig.YAOHU_API_KEY`；不得写入源码、测试固件、日志、截图或 UI。
- 下载写入 `Music/汪汪播放器/`；成功后刷新 MediaStore；失败时删除 pending 条目。
- URL 只保存在进程内，缓存键必须包含平台、曲目标识和请求质量；在线队列不写入持久化队列。
- 不改变 `NativeBassPlayer`、DLNA、Chromecast、蓝牙、导入、本地曲库和更新流程的既有行为。
- 所有 shell 命令以 `rtk` 开头；Android 命令从 `D:\musicplayer\android` 执行；不创建 worktree、不提交、不删除用户文件。
- 所有代码实现遵循 TDD：先新增会失败的测试，运行确认失败，再写最小生产代码。

---

### Task 1: Add QQ, 酷我 and 酷狗 API Catalogs

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/YaohuOtherMusicApiTest.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/YaohuNeteaseApi.kt:9-11`
- Modify: `android/app/build.gradle.kts:23-25` (test-only JSON implementation; not packaged in the APK)

**Interfaces:**
- Consumes: `YaohuHttp`, `YaohuApiException`, `MusicPlatform`, and the approved endpoint contracts.
- Produces: `YaohuQuality`, `QqQuality`, `KugouQuality`, `KuwoQuality`, `OnlineTrack`, `OnlineCacheKey`, `OnlineResolvedTrack`, `QqCatalog`, `KugouCatalog`, and `KuwoCatalog`.

- [ ] **Step 1: Write failing JSON mapping tests**

Add a `FakeYaohuHttp` that records the path and parameter map and returns fixture JSON without a real key. Add tests for one search and one resolve per platform, plus URL priority and business errors:

```kotlin
private class FakeYaohuHttp(private val responses: Map<String, ArrayDeque<String>>) : YaohuHttp {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    override fun get(path: String, parameters: Map<String, String>): String {
        requests += path to parameters
        return responses[path]?.removeFirstOrNull() ?: error("missing fixture: $path")
    }
}

@Test
fun qq_search_reads_data_songs_and_resolve_prefers_music_url() {
    val http = FakeYaohuHttp(
        mapOf(
            "/api/music/qq_plus" to ArrayDeque(listOf("""{
                "code":200,"data":{
                    "songs":[{"n":1,"name":"晴天","singer":"周杰伦","album":"叶惠美","mid":"qq-mid","image":"https://img"}]
                }
            }""", """{
                "code":200,"data":{
                    "name":"晴天","music_url":"https://music-url","musicurl":"https://musicurl",
                    "cached_size":"hq","format":"mp3","lrctxt":"[00:00.00]歌词"
                }
            }""")),
        ),
    )
    val catalog = QqCatalog(http)
    val result = catalog.search("周杰伦").single()
    val resolved = catalog.resolve(result, QqQuality.Hq)

    assertEquals("qq-mid", result.platformId)
    assertEquals("https://music-url", resolved.playUrl)
    assertEquals(QqQuality.Hq, resolved.actualQuality)
    assertEquals("周杰伦", http.requests.single { it.first == "/api/music/qq_plus" }.second["msg"])
}

@Test
fun kugou_search_and_resolve_read_data_fields() {
    val http = FakeYaohuHttp(
        mapOf(
            "/api/music/kg" to ArrayDeque(listOf("""{
                "code":200,"data":[{"n":1,"rid":"kg-rid","name":"晴天","singer":"周杰伦","album":"叶惠美","cover":"https://img"}]
            }""", """{
                "code":200,"data":{"play_url":"https://play","selected_quality":"flac","ext_name":"flac"}
            }""")),
        ),
    )
    val catalog = KugouCatalog(http)
    val result = catalog.search("周杰伦").single()
    val resolved = catalog.resolve(result, KugouQuality.Flac)

    assertEquals("kg-rid", result.platformId)
    assertEquals("https://play", resolved.playUrl)
    assertEquals(KugouQuality.Flac, resolved.actualQuality)
}

@Test
fun kuwo_search_uses_so_and_resolve_uses_song_and_id() {
    val http = FakeYaohuHttp(
        mapOf(
            "/api/music/kuwo" to ArrayDeque(listOf("""{
                "code":200,"data":[{"n":1,"rid":"kw-rid","name":"晴天","singer":"周杰伦","album":"叶惠美","picture":"https://img"}]
            }""", """{
                "code":200,"data":{"url":"https://play","level":"lossless","format":"flac"}
            }""")),
        ),
    )
    val catalog = KuwoCatalog(http)
    val result = catalog.search("周杰伦").single()
    val resolved = catalog.resolve(result, KuwoQuality.Lossless)
    val resolveRequest = http.requests.last().second

    assertEquals("kw-rid", result.platformId)
    assertEquals("https://play", resolved.playUrl)
    assertEquals("song", resolveRequest["action"])
    assertEquals("kw-rid", resolveRequest["id"])
}

@Test
fun catalogs_reject_non_200_and_empty_urls_without_exposing_credentials() {
    val error = assertFailsWith<YaohuApiException> {
        QqCatalog(FakeYaohuHttp(mapOf("/api/music/qq_plus" to ArrayDeque(listOf("""{"code":403,"msg":"无权限"}""")))) )
            .search("x")
    }
    assertEquals("无权限", error.message)
}
```

- [ ] **Step 2: Run the focused tests and verify the expected RED failure**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.YaohuOtherMusicApiTest"
```

Expected: compilation/test failure because `QqCatalog`, `KugouCatalog`, `KuwoCatalog` and the normalized types do not exist yet. Do not keep a passing test at this stage.

- [ ] **Step 3: Add the minimal platform models and Catalog implementations**

In `YaohuNeteaseApi.kt`, make `MusicPlatform` contain exactly `QQ("QQ")`, `Kuwo("酷我")`, `Kugou("酷狗")`, and `Netease("网易云")`; add `interface YaohuQuality { val apiValue: String; val label: String }` and let `NeteaseQuality` implement it.

In `YaohuOtherMusicApi.kt`, define the quality enums with these API values:

```kotlin
enum class QqQuality(override val apiValue: String, override val label: String) : YaohuQuality {
    Mp3("mp3", "普通"), Hq("hq", "高品质"), Flac("flac", "无损");
}

enum class KugouQuality(override val apiValue: String, override val label: String) : YaohuQuality {
    K128("128", "标准"), K320("320", "高品质"), Flac("flac", "无损");
}

enum class KuwoQuality(override val apiValue: String, override val label: String) : YaohuQuality {
    Standard("standard", "标准"), Exhigh("exhigh", "极高"), Sq("SQ", "超高品质"),
    Lossless("lossless", "无损"), Hires("hires", "高解析无损");
}
```

Define the UI-bound and cache-bound data:

```kotlin
data class OnlineTrack(
    val platform: MusicPlatform,
    val query: String,
    val sequence: Int,
    val platformId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val format: String = "mp3",
) {
    val key: String get() = listOf(platform.name, query, sequence, platformId.orEmpty()).joinToString(":")
}

data class OnlineCacheKey(val platform: MusicPlatform, val trackKey: String, val quality: String)

data class OnlineResolvedTrack(
    val playUrl: String,
    val actualQuality: String,
    val mimeType: String,
    val extension: String,
    val lyrics: String? = null,
)
```

Implement each Catalog with `YaohuHttp.get`, `code == 200` validation, platform-local JSON parsing, and `firstNonBlank` URL selection. Use `/api/music/qq_plus` with `msg`, `g` for search and `msg`, `n`, `size` for resolve; use `/api/music/kg` with `msg`, `g` for search and `msg`, `n`, `quality` for resolve; use `/api/music/kuwo` with `action=so`, `msg`, `g` for search and `action=song`, `id`, `size` for resolve. Use a small local `objectsFromData` helper that accepts an array, a `songs`/`results` array, or a single data object so the documented response variants do not crash parsing. Never add a `cookie` parameter.

Map the server formats to `audio/mpeg`, `audio/flac`, `audio/mp4`, `audio/ogg`, or `audio/wav`; unknown formats fall back to `audio/mpeg` and the corresponding `mp3` extension. QQ URL order is `music_url`, `musicurl`, `url`; QQ actual-quality order is `actual_size`, `cached_size`, `format`, `request_size`. 酷狗 reads `data.play_url`, `data.selected_quality`, `data.ext_name`; 酷我 reads `url`, `level`, and `format`.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the same focused command from Step 2. Expected: all `YaohuOtherMusicApiTest` tests pass. Then run the existing API tests to ensure the new `YaohuQuality` interface did not change网易云 behavior:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.NeteaseQualityTest --tests com.example.local_music_player.NeteaseResolutionCacheTest"
```

---

### Task 2: Add unified online state, preferences, playback and download coordination

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/OnlinePlatformStateTest.kt`

**Interfaces:**
- Consumes: Task 1 Catalogs and normalized `OnlineTrack`/`OnlineCacheKey`/`OnlineResolvedTrack`.
- Produces: `MusicUiState.onlinePlatform`, `onlineQuery`, `onlineTracks`, `onlineSearching`, `onlineActualQuality`, `downloadingOnlineKeys`, `selectOnlinePlatform`, `searchOnline`, `playOnline`, and `downloadOnline`.

- [ ] **Step 1: Write failing pure state tests**

Add tests for the platform order, distinct preference keys, and cache isolation:

```kotlin
@Test
fun online_platforms_keep_the_requested_display_order() {
    assertEquals(listOf("QQ", "酷我", "酷狗", "网易云"), MusicPlatform.entries.map { it.label })
}

@Test
fun online_preference_keys_are_independent_per_platform_and_purpose() {
    assertNotEquals(onlinePlaybackPreferenceKey(MusicPlatform.QQ), onlinePlaybackPreferenceKey(MusicPlatform.Kuwo))
    assertNotEquals(onlinePlaybackPreferenceKey(MusicPlatform.QQ), onlineDownloadPreferenceKey(MusicPlatform.QQ))
}

@Test
fun online_cache_key_does_not_cross_platform_or_quality() {
    val base = OnlineCacheKey(MusicPlatform.QQ, "q:1", "hq")
    assertNotEquals(base, base.copy(platform = MusicPlatform.Kugou))
    assertNotEquals(base, base.copy(quality = "flac"))
}
```

- [ ] **Step 2: Run the focused state tests and verify RED**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlinePlatformStateTest"
```

Expected: compilation failure because the new state helpers and fields are absent.

- [ ] **Step 3: Add state fields, Catalog instances and preference helpers**

Extend `MusicUiState` without deleting the existing legacy compatibility fields:

```kotlin
val onlinePlatform: MusicPlatform = MusicPlatform.Netease,
val onlineQuery: String = "",
val onlineTracks: List<OnlineTrack> = emptyList(),
val onlineSearching: Boolean = false,
val onlineActualQuality: String? = null,
val downloadingOnlineKeys: Set<String> = emptySet(),
val qqPlaybackQuality: QqQuality = QqQuality.Hq,
val qqDownloadQuality: QqQuality = QqQuality.Flac,
val kugouPlaybackQuality: KugouQuality = KugouQuality.K320,
val kugouDownloadQuality: KugouQuality = KugouQuality.Flac,
val kuwoPlaybackQuality: KuwoQuality = KuwoQuality.Exhigh,
val kuwoDownloadQuality: KuwoQuality = KuwoQuality.Lossless,
```

Add `qqCatalog`, `kugouCatalog`, `kuwoCatalog`, `onlineTrackById`, and `onlineResolutionCache` alongside the existing网易云 maps. Add `onlinePlaybackPreferenceKey(platform)` and `onlineDownloadPreferenceKey(platform)` returning stable keys such as `qq_playback_quality` and `qq_download_quality`; load each enum with `runCatching` and the defaults above. Add six setters that update both `SharedPreferences` and `_state`.

- [ ] **Step 4: Implement explicit platform selection and search**

Implement:

```kotlin
fun selectOnlinePlatform(platform: MusicPlatform) {
    if (_state.value.onlineSearching || _state.value.onlinePlatform == platform) return
    _state.value = _state.value.copy(onlinePlatform = platform, onlineTracks = emptyList(), onlineActualQuality = null)
    _state.value.onlineQuery.trim().takeIf(String::isNotEmpty)?.let { searchOnline(it) }
}

fun searchOnline(query: String) {
    val trimmed = query.trim()
    if (trimmed.isBlank() || _state.value.onlineSearching) return
    val platform = _state.value.onlinePlatform
    _state.value = _state.value.copy(
        onlineQuery = trimmed,
        onlineTracks = emptyList(),
        onlineSearching = true,
        status = null,
    )
    streamSearchJob?.cancel()
    streamSearchJob = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                when (platform) {
                    MusicPlatform.Netease -> neteaseCatalog.search(trimmed, _state.value.neteasePlaybackQuality).map(::toOnlineTrack)
                    MusicPlatform.QQ -> qqCatalog.search(trimmed).map { it.toOnlineTrack() }
                    MusicPlatform.Kuwo -> kuwoCatalog.search(trimmed).map { it.toOnlineTrack() }
                    MusicPlatform.Kugou -> kugouCatalog.search(trimmed).map { it.toOnlineTrack() }
                }
            }
        }.onSuccess { results ->
            if (_state.value.onlinePlatform == platform && _state.value.onlineQuery == trimmed) {
                _state.value = _state.value.copy(
                    onlineTracks = results,
                    onlineSearching = false,
                    status = if (results.isEmpty()) "未找到相关歌曲" else null,
                )
            }
        }.onFailure { error ->
            if (_state.value.onlinePlatform == platform && _state.value.onlineQuery == trimmed) {
                _state.value = _state.value.copy(onlineSearching = false, status = error.message ?: "在线搜索失败，请稍后重试")
            }
        }
    }
}
```

Keep `searchNetease` as a compatibility wrapper that selects网易云 and delegates to `searchOnline`; synchronize the old `neteaseTracks` fields only where existing playback code still consumes them. Do not issue a request from state collection or a `LaunchedEffect` keyed only by platform.

- [ ] **Step 5: Implement temporary online queues and direct URL playback**

Implement `playOnline(tracks, startIndex)` by converting each `OnlineTrack` to a negative-ID `NativeTrack` with a `yaohu://` URI, filling `onlineTrackById`, and calling the existing `playFromQueue`. For网易云 also fill `neteaseTrackById` using the result’s query/sequence so the existing four-attempt VIP/free path remains unchanged. For QQ、酷我、酷狗 add `startOtherOnlinePlayback` from `startLocalPlaybackNow` that:

1. Rejects casting with `在线音乐暂不支持投送`.
2. Looks up `OnlineCacheKey(platform, track.key, requestedQuality.apiValue)`.
3. Calls the matching Catalog on `Dispatchers.IO` when no cache exists.
4. Calls `player.playUrl(resolved.playUrl, track.durationMs, startPosition)` directly.
5. On cached URL failure removes only that cache key and resolves once again.
6. On success updates queue/current track, `isStreaming`, playing state, actual quality, lyrics, MediaSession and progress polling.
7. On failure sets `suppressNeteaseAutoAdvance = true`, keeps the current track, and exposes the Chinese error without advancing.

Use one `onlineStartInProgress` guard for the three new platforms; do not modify `NativeBassPlayer` or reintroduce `StreamingProxyServer`.

- [ ] **Step 6: Implement shared online download and persistence boundaries**

Implement `downloadOnline(track)` with a `when` dispatch to the existing网易云 resolver or the new Catalog resolver and the current platform’s download quality. Track progress using `track.key` in `downloadingOnlineKeys`; use the existing `MediaStore.Audio` pending insert/copy/finalize/delete sequence and `safeDownloadName`/MIME mapping. On success set `status = "下载完成：${track.title}（${resolved.actualQuality}）"` and call `refresh(_state.value.permissionGranted)`; on failure remove any destination and clear the key.

Update `saveQueue()` so any queue containing an ID in `onlineTrackById` returns before writing `queue_items`, just as current网易云 online queues do. Do not put a URL, platform search result, or online URI into `SharedPreferences`.

- [ ] **Step 7: Run state, API and full JVM tests**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlinePlatformStateTest --tests com.example.local_music_player.YaohuOtherMusicApiTest"
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
```

Expected: both commands exit 0; the full command must include the existing network-stream policy tests and网易云 tests.

---

### Task 3: Replace the online screen with platform selection

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes: Task 2 `MusicUiState.online*` state and `selectOnlinePlatform`, `searchOnline`, `playOnline`, `downloadOnline` callbacks.
- Produces: a visible four-platform dropdown, generic search field, explicit search action, accessible result rows, and no focus-triggered network work.

- [ ] **Step 1: Add the UI contract assertions before implementation**

Extend `OnlinePlatformStateTest` with the pure UI contract:

```kotlin
@Test
fun online_platform_labels_are_not_netease_specific() {
    assertEquals(listOf("QQ", "酷我", "酷狗", "网易云"), MusicPlatform.entries.map { it.label })
}
```

Run the focused test and confirm it fails if the enum order/labels are not yet updated.

- [ ] **Step 2: Add the platform dropdown and generic search field**

Change `OnlineScreen` callbacks to `(List<OnlineTrack>, Int) -> Unit` and `(OnlineTrack) -> Unit`. Replace `state.neteaseTracks` with `state.onlineTracks`, `state.onlineSearching`, `state.onlineQuery`, `state.onlinePlatform`, and `state.downloadingOnlineKeys`.

Use a `Column` with the existing search row followed by a full-width `OutlinedButton`/`DropdownMenu` platform selector. The selector button has `Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)`, visible text `平台：${state.onlinePlatform.label}`, and a concise semantic name. Each `DropdownMenuItem` calls `onPlatformSelected(option)` only from its `onClick`; no `LaunchedEffect` may call search merely because `state.onlinePlatform` changes.

Use the field label and search icon content description `搜索歌曲`. Submit only trimmed non-empty text. Disable the search button and selector while `state.onlineSearching`; retain the keyboard Search action.

- [ ] **Step 3: Keep result actions accessible and stable**

Keep stable keys based on `track.key`. A row click calls `onPlay(state.onlineTracks, index)`. Long press only adds `track.key` to the local set that reveals the download `IconButton`; the row’s `CustomAccessibilityAction("下载")` calls `onDownload(track)` when not downloading. Keep row and icon targets at least 48dp, hide decorative icons with `contentDescription = null`, and use `liveRegion = Polite` for searching and status/error text.

Do not show “搜索网易云音乐” anywhere in `OnlineScreen`. Do not make platform focus, TalkBack exploration, or menu dismissal call a callback.

- [ ] **Step 4: Wire MusicApp callbacks and remove the old OnlineScreen callback names**

In both root and any existing `OnlineScreen` call sites, pass:

```kotlin
onPlatformSelected = viewModel::selectOnlinePlatform,
onSearch = viewModel::searchOnline,
onPlay = viewModel::playOnline,
onDownload = viewModel::downloadOnline,
```

Do not alter bottom navigation, player navigation, device selection, import, or settings routing in this task.

- [ ] **Step 5: Compile and inspect semantics**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Inspect a debug build’s UI tree or a connected device to confirm the four platform labels, “搜索歌曲”, a named platform selector, a named download action, 48dp minimum targets, and no request caused by focus movement.

---

### Task 4: Add independent platform quality settings

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes: Task 2 six quality setters and the four platform quality enums implementing `YaohuQuality`.
- Produces: settings menus for QQ、酷我、酷狗 in addition to the existing网易云 menus.

- [ ] **Step 1: Generalize the existing quality menu after the state tests are green**

Change the private `NeteaseQualityMenu` implementation into a generic Compose helper:

```kotlin
@Composable
private fun <T : YaohuQuality> PlatformQualityMenu(
    label: String,
    quality: T,
    options: List<T>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        ) { Text("$label：${quality.label}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == quality) "${option.label}（当前）" else option.label) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}
```

Use `PlatformQualityMenu` for网易云 and the three new platforms; keep all menu buttons at least 48dp and preserve existing menu labels.

- [ ] **Step 2: Wire the six new setters in every SettingsScreen call**

Add callback parameters to `SettingsScreen` and pass the ViewModel methods from each call site:

```kotlin
onQqPlaybackQuality = viewModel::setQqPlaybackQuality,
onQqDownloadQuality = viewModel::setQqDownloadQuality,
onKugouPlaybackQuality = viewModel::setKugouPlaybackQuality,
onKugouDownloadQuality = viewModel::setKugouDownloadQuality,
onKuwoPlaybackQuality = viewModel::setKuwoPlaybackQuality,
onKuwoDownloadQuality = viewModel::setKuwoDownloadQuality,
```

Render the six menus beside the existing网易云 menus. No menu selection starts a network request; the selected values only update preferences and future explicit resolution requests.

- [ ] **Step 3: Compile and perform the accessibility self-review**

Run:

```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Check with the accessibility baseline: concise Chinese names, no duplicate icon semantics, 48dp targets, visible current state, 4.5:1 text contrast, predictable keyboard/D-pad order, and no network work from focus or menu dismissal.

---

### Task 5: Full verification and device smoke test

**Files:**
- Modify only files required by defects found during verification.

- [ ] **Step 1: Run the required local verification commands**

Run from `D:\本地音乐播放器`:

```powershell
rtk git diff --check
```

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
rtk cmd /c "gradlew.bat :app:assembleRelease"
```

Record the exit code and the number of failed tests from each command. Do not claim completion if any command fails.

- [ ] **Step 2: Perform device UI and playback smoke checks**

On a connected Android device, verify in order:

1. Online media opens with `搜索歌曲` and the dropdown labels `QQ`、`酷我`、`酷狗`、`网易云`.
2. With an empty query, selecting a platform does not request; after entering a query, selecting each platform requests only that platform.
3. Search results show stable rows; focus/TalkBack exploration does not start another request; search controls disable during a request.
4. Click a result on each platform and confirm direct BASS playback, actual quality status, pause/resume, seek and next/previous behavior.
5. Long-press a result or invoke TalkBack “下载”, confirm download completion, MediaStore admission and local-library refresh; repeat with a failed/empty URL path and confirm no orphan pending item.
6. Open settings and confirm six new menus persist independently across restart without any Cookie field.
7. Confirm local playback, DLNA, Chromecast, Bluetooth route and logcat have no app FATAL/ANR.

- [ ] **Step 3: Review the final diff without committing**

Run:

```powershell
rtk git diff --check
rtk git status --short
```

Keep unrelated user modifications and generated files untouched. Report any unavailable real-device or API validation explicitly.
