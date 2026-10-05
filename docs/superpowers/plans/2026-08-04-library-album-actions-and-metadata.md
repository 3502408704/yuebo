# 媒体库专辑操作与元数据 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留自动发现专辑，合并导入专辑，并以长按和等价无障碍操作统一专辑及曲目的详情、编辑和删除体验。

**Architecture:** 在 `ImportedAlbum.kt` 定义纯 Kotlin 的 `LibraryAlbum` 聚合与外部元数据回退规则，供单元测试和 Compose 共用。`NativeMusicViewModel` 只负责持久化隐藏专辑键、移除导入登记、读取嵌入标签；`MusicApp.kt` 把聚合结果渲染为卡片和详情页，并在现有状态回调上执行菜单动作。

**Tech Stack:** Kotlin、Android MediaMetadataRetriever、Jetpack Compose Material 3、Compose Semantics、SharedPreferences、kotlin.test。

## Global Constraints

- 所有 shell 命令必须以 `rtk` 开头；Android Gradle 命令仅从 `D:\musicplayer\android` 执行。
- 文件修改只能使用 `apply_patch`；不创建 worktree、不删除用户文件、不自动提交。
- 不增加依赖；不写入源媒体的标签，不改变播放列表、播放器、投送或蓝牙路径。
- 所有新增可见文本和 TalkBack 标签使用简体中文；触控与键盘操作目标最小 48dp。
- 焦点移动、TalkBack 浏览和普通选择不能开始播放、扫描、连接或写入；只有点击或已显式选择的菜单操作可以执行命令。
- 专辑“从专辑列表移除”只更新应用内隐藏/导入记录，绝不删除源文件、目录权限或播放列表。

---

### Task 1: 可测试的专辑聚合与元数据回退

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/ImportedAlbum.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/ImportedAlbumTest.kt`

**Interfaces:**
- Produces `internal data class LibraryAlbum(val key: String, val name: String, val artist: String, val tracks: List<NativeTrack>, val importedAlbum: ImportedAlbum?)`.
- Produces `internal fun libraryAlbumKey(name: String): String` and `internal fun buildLibraryAlbums(libraryTracks: List<NativeTrack>, importedAlbums: List<ImportedAlbum>, overrides: Map<String, TrackMetadataOverride>, hiddenKeys: Set<String>): List<LibraryAlbum>`.
- Produces `internal data class ExternalTrackMetadata(val title: String, val artist: String, val album: String, val durationMs: Long)` and `internal fun externalTrackMetadata(displayName: String, embeddedTitle: String?, embeddedArtist: String?, embeddedAlbum: String?, embeddedDurationMs: String?): ExternalTrackMetadata`.
- Consumed by Task 2 for tag extraction and removal state, and by Task 3 for album cards and detail pages.

- [ ] **Step 1: Write the failing unit tests**

Add these tests to `ImportedAlbumTest`, then add the following local helper at the bottom of the test class.

```kotlin
@Test
fun libraryAlbumsMergeSameNameAndPreferImportedPresentation() {
    val automatic = nativeTrack(
        id = 1,
        uri = "content://media/external/audio/media/1",
        title = "自动曲目",
        artist = "自动艺术家",
        album = " 夜航 ",
    )
    val imported = sampleAlbum().copy(name = "夜航", artist = "导入艺术家")

    val albums = buildLibraryAlbums(listOf(automatic), listOf(imported), emptyMap(), emptySet())

    assertEquals(1, albums.size)
    assertEquals("夜航", albums.single().name)
    assertEquals("导入艺术家", albums.single().artist)
    assertEquals(imported, albums.single().importedAlbum)
    assertEquals(2, albums.single().tracks.size)
}

@Test
fun libraryAlbumsDeduplicateTracksAndHonorHiddenKey() {
    val imported = sampleAlbum().copy(
        name = "夜航",
        tracks = sampleAlbum().tracks.map { it.copy(album = "夜航") },
    )
    val duplicate = imported.tracks.single().toNativeTrack()

    val shown = buildLibraryAlbums(listOf(duplicate), listOf(imported), emptyMap(), emptySet())
    val hidden = buildLibraryAlbums(listOf(duplicate), listOf(imported), emptyMap(), setOf(libraryAlbumKey("夜航")))

    assertEquals(1, shown.single().tracks.size)
    assertTrue(hidden.isEmpty())
}

@Test
fun externalTrackMetadataPrefersTagsAndFallsBackToDisplayName() {
    assertEquals(
        ExternalTrackMetadata("标签标题", "标签艺术家", "标签专辑", 12_345),
        externalTrackMetadata("文件名.flac", " 标签标题 ", " 标签艺术家 ", " 标签专辑 ", "12345"),
    )
    assertEquals(
        ExternalTrackMetadata("文件名", "未知艺术家", "", 0),
        externalTrackMetadata("文件名.flac", " ", null, null, "not-a-number"),
    )
}

private fun nativeTrack(
    id: Long,
    uri: String,
    title: String,
    artist: String,
    album: String,
): NativeTrack = NativeTrack(
    id = id,
    uri = android.net.Uri.parse(uri),
    title = title,
    artist = artist,
    album = album,
    durationMs = 1_000,
    format = "FLAC",
    mimeType = "audio/flac",
    folderPath = "Music/测试",
)
```

- [ ] **Step 2: Run the focused test class and verify it fails**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"
```

Expected: compilation fails because `LibraryAlbum`, `buildLibraryAlbums`, and `externalTrackMetadata` do not exist.

- [ ] **Step 3: Implement the smallest pure helpers**

Append the following production types and helpers to `ImportedAlbum.kt`. Keep `Locale.ROOT` for a stable, locale-independent key. Build the automatic source only from tracks with a nonblank album; map imported tracks through `withMetadataOverride`; merge by key; deduplicate in first-seen order with `metadataKey`; and sort case-insensitively by display name.

```kotlin
import java.util.Locale

internal data class LibraryAlbum(
    val key: String,
    val name: String,
    val artist: String,
    val tracks: List<NativeTrack>,
    val importedAlbum: ImportedAlbum?,
)

internal data class ExternalTrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal fun libraryAlbumKey(name: String): String = name.trim().lowercase(Locale.ROOT)

internal fun externalTrackMetadata(
    displayName: String,
    embeddedTitle: String?,
    embeddedArtist: String?,
    embeddedAlbum: String?,
    embeddedDurationMs: String?,
): ExternalTrackMetadata = ExternalTrackMetadata(
    title = embeddedTitle?.trim().takeUnless { it.isNullOrBlank() }
        ?: displayName.substringBeforeLast('.', displayName),
    artist = embeddedArtist?.trim().takeUnless { it.isNullOrBlank() } ?: "未知艺术家",
    album = embeddedAlbum?.trim().orEmpty(),
    durationMs = embeddedDurationMs?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
)

internal fun buildLibraryAlbums(
    libraryTracks: List<NativeTrack>,
    importedAlbums: List<ImportedAlbum>,
    overrides: Map<String, TrackMetadataOverride>,
    hiddenKeys: Set<String>,
): List<LibraryAlbum> {
    data class Source(val tracks: List<NativeTrack>, val name: String, val artist: String, val imported: ImportedAlbum?)
    val grouped = linkedMapOf<String, MutableList<Source>>()
    libraryTracks.filter { it.album.isNotBlank() }.groupBy { libraryAlbumKey(it.album) }.forEach { (key, tracks) ->
        grouped.getOrPut(key) { mutableListOf() }.add(Source(tracks, tracks.first().album, tracks.first().artist, null))
    }
    importedAlbums.forEach { imported ->
        val tracks = imported.tracks.map(ImportedAlbumTrack::toNativeTrack)
            .map { track -> track.withMetadataOverride(overrides[track.metadataKey()]) }
        grouped.getOrPut(libraryAlbumKey(imported.name)) { mutableListOf() }
            .add(Source(tracks, imported.name, imported.artist, imported))
    }
    return grouped.asSequence().filter { (key, _) -> key !in hiddenKeys }.map { (key, sources) ->
        val imported = sources.mapNotNull(Source::imported).firstOrNull()
        val primary = imported?.let { source -> sources.first { it.imported == source } } ?: sources.first()
        LibraryAlbum(
            key = key,
            name = primary.name,
            artist = primary.artist,
            tracks = sources.flatMap(Source::tracks).distinctBy(NativeTrack::metadataKey),
            importedAlbum = imported,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).toList()
}
```

- [ ] **Step 4: Run the focused test class and verify it passes**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"
```

Expected: `ImportedAlbumTest` passes, including round trip compatibility tests already in the file.

### Task 2: ViewModel persistence, embedded tag extraction and non-destructive album removal

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/ImportedAlbumTest.kt`

**Interfaces:**
- Consumes `LibraryAlbum`, `libraryAlbumKey`, `externalTrackMetadata` from Task 1.
- Adds `val hiddenAlbumKeys: Set<String> = emptySet()` to `MusicUiState`.
- Produces `fun removeLibraryAlbum(album: LibraryAlbum)`.
- `buildExternalTrack(uri: Uri, name: String)` passes MediaMetadataRetriever title, artist, album, and duration to `externalTrackMetadata`.
- Consumed by Task 3 as `state.hiddenAlbumKeys` and `viewModel::removeLibraryAlbum`.

- [ ] **Step 1: Extend the failing aggregation test for user metadata precedence**

Add the assertion below. It demonstrates that a saved song edit participates in the automatic grouping and is therefore the source of truth after reload.

```kotlin
@Test
fun libraryAlbumsUseSavedTrackOverridesBeforeGrouping() {
    val track = nativeTrack(1, "content://media/1", "原题", "艺术家", "原专辑")
    val overrides = mapOf(track.metadataKey() to TrackMetadataOverride("新题", "新艺术家", "新专辑"))

    val albums = buildLibraryAlbums(listOf(track.withMetadataOverride(overrides[track.metadataKey()])), emptyList(), overrides, emptySet())

    assertEquals("新专辑", albums.single().name)
    assertEquals("新艺术家", albums.single().artist)
}
```

- [ ] **Step 2: Run the focused test class and verify the new test passes before ViewModel changes**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"
```

Expected: PASS. This confirms Task 1's grouping contract before the Android integration is changed.

- [ ] **Step 3: Add state and persistence, then wire metadata extraction**

In `MusicUiState`, add `hiddenAlbumKeys` next to `importedAlbums`. During initialization, set it from `loadHiddenAlbumKeys()`. Store the set in `player_settings` using the new `hidden_album_keys` key:

```kotlin
private fun loadHiddenAlbumKeys(): Set<String> =
    settingsPreferences.getStringSet("hidden_album_keys", emptySet()).orEmpty()

private fun saveHiddenAlbumKeys(keys: Set<String>) {
    settingsPreferences.edit().putStringSet("hidden_album_keys", keys).apply()
}

fun removeLibraryAlbum(album: LibraryAlbum) {
    val current = _state.value
    val hidden = current.hiddenAlbumKeys + album.key
    val imported = album.importedAlbum?.let { source ->
        current.importedAlbums.filterNot { it.id == source.id }
    } ?: current.importedAlbums
    saveHiddenAlbumKeys(hidden)
    saveImportedAlbums(imported)
    _state.value = current.copy(
        hiddenAlbumKeys = hidden,
        importedAlbums = imported,
        status = "已从专辑列表移除“${album.name}”，源文件和播放列表未改变。",
    )
}
```

Replace the duration-only body in `buildExternalTrack` with a single retriever pass. Always release the retriever; any provider/decoder failure uses a null metadata object and therefore the pure fallback.

```kotlin
val metadata = runCatching {
    MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(getApplication<Application>(), uri)
            externalTrackMetadata(
                displayName = name,
                embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                embeddedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                embeddedAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                embeddedDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
            )
        } finally {
            retriever.release()
        }
    }
}.getOrElse { externalTrackMetadata(name, null, null, null, null) }
```

Use `metadata.title`, `metadata.artist`, `metadata.album`, and `metadata.durationMs` in the returned `NativeTrack`. Do not alter `expandCueTracks`: its current CUE fields already override the source fields only when present.

- [ ] **Step 4: Run focused tests and compile the Android module**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Expected: all focused tests pass and Kotlin compilation succeeds.

### Task 3: 专辑卡、详情页和统一曲目长按交互

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes `buildLibraryAlbums`, `LibraryAlbum`, `MusicUiState.hiddenAlbumKeys`, and `NativeMusicViewModel.removeLibraryAlbum` from Tasks 1-2.
- Extends `LibraryScreen` to receive `albums: List<LibraryAlbum>` and `onRemoveAlbum: (LibraryAlbum) -> Unit`.
- Replaces imported-only selection state with `LibraryAlbum.key` selection and restore keys.
- Replaces `TrackMetadataDialog`/file-details split with `TrackDetailsMetadataDialog`.

- [ ] **Step 1: Replace imported-only album derivation with unified entries**

At the `MusicApp` composition root, derive one `libraryAlbums` list with `remember(state.tracks, state.importedAlbums, state.trackMetadataOverrides, state.hiddenAlbumKeys)`. Pass this list into `LibraryScreen`; use the same list to resolve the selected detail entry by its `LibraryAlbum.key`. Keep the existing lazy-grid restoration mechanism, but make it hold the entry key rather than an imported-album ID.

Use this derivation, retaining the existing state variable name if desired but changing its contents to the stable aggregation key:

```kotlin
val libraryAlbums = remember(
    state.tracks,
    state.importedAlbums,
    state.trackMetadataOverrides,
    state.hiddenAlbumKeys,
) {
    buildLibraryAlbums(
        libraryTracks = state.tracks,
        importedAlbums = state.importedAlbums,
        overrides = state.trackMetadataOverrides,
        hiddenKeys = state.hiddenAlbumKeys,
    )
}
val selectedAlbum = selectedAlbumId?.let { key ->
    libraryAlbums.firstOrNull { album -> album.key == key }
}
```

In `LibraryScreen`, filter and sort the supplied entries by `name`, `artist`, and every track title. Replace `ImportedAlbumCard` with `LibraryAlbumCard`. Each card must use `combinedClickable`: click calls `onOpenAlbum`, long press sets a local menu state. Its `clearAndSetSemantics` supplies `onClick(label = "查看专辑详情")`, then `CustomAccessibilityAction("编辑专辑")` only when `importedAlbum != null`, and `CustomAccessibilityAction("从专辑列表移除")` for every entry. Do not start playback from any of these actions.

Use an `AlertDialog` before removal with title `从专辑列表移除？`, body `“${album.name}”将不再显示在专辑列表中。源文件和播放列表不会改变。`, cancel `取消`, and confirm `移除`. On confirm call `onRemoveAlbum(album)`. The long-press `DropdownMenu` contains the exact same applicable actions. Set the removal menu action's leading icon to `Icons.Default.Delete` with `contentDescription = null`.

Implement the card's operation state in the replacement composable. `onEdit` may be null for automatic-only entries; its custom action and menu item must then be omitted.

```kotlin
var menuOpen by remember { mutableStateOf(false) }
var removeConfirmOpen by remember { mutableStateOf(false) }
val edit = album.importedAlbum?.let { { onEdit(it) } }
Card(
    Modifier.fillMaxWidth().heightIn(min = 168.dp)
        .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
        .clearAndSetSemantics {
            contentDescription = label
            onClick(label = "查看专辑详情") { onOpen(); true }
            customActions = buildList {
                if (edit != null) add(CustomAccessibilityAction("编辑专辑") { edit(); true })
                add(CustomAccessibilityAction("从专辑列表移除") { removeConfirmOpen = true; true })
            }
        },
) { /* existing image and album text, using LibraryAlbum fields */ }
DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
    DropdownMenuItem(text = { Text("查看专辑详情") }, onClick = { menuOpen = false; onOpen() })
    if (edit != null) DropdownMenuItem(text = { Text("编辑专辑") }, onClick = { menuOpen = false; edit() })
    DropdownMenuItem(
        text = { Text("从专辑列表移除") },
        onClick = { menuOpen = false; removeConfirmOpen = true },
        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
    )
}
```

- [ ] **Step 2: Adapt the detail screen for both sources and remove the separate edit focus**

Change `AlbumDetailScreen` to receive `album: LibraryAlbum` and use `album.tracks`. Show the AppBar edit icon only when `album.importedAlbum != null`; pass that imported object to `AlbumEditDialog` and preserve the existing artwork launcher behavior. Pure automatic albums remain fully readable and playable without artificial tree URIs.

Replace `AlbumTrackRow`'s `TextButton("编辑元数据")` with a `combinedClickable` row and anchored `DropdownMenu`. The card's semantics must keep `onClick(label = "播放")` and set exactly these custom actions:

```kotlin
customActions = listOf(
    CustomAccessibilityAction("查看并编辑元数据") { metadataEditorOpen = true; true },
    CustomAccessibilityAction("删除") { deleteConfirmOpen = true; true },
)
```

The menu contains `查看并编辑元数据` and `删除`; deletion uses the same confirmation dialog and existing `onDelete(track)` handler used by `TrackCard`. Give the row a `FocusRequester` and restore it after the metadata dialog or delete confirmation closes. Do not expose a second text-button focus.

The relevant card shape is:

```kotlin
Card(
    Modifier.fillMaxWidth().heightIn(min = 88.dp)
        .combinedClickable(onClick = onPlay, onLongClick = { menuOpen = true })
        .focusRequester(rowFocusRequester)
        .clearAndSetSemantics {
            contentDescription = "${track.title}，${track.artist.ifBlank { "未知艺术家" }}，${formatTime(track.durationMs)}"
            onClick(label = "播放") { onPlay(); true }
            customActions = listOf(
                CustomAccessibilityAction("查看并编辑元数据") { metadataEditorOpen = true; true },
                CustomAccessibilityAction("删除") { deleteConfirmOpen = true; true },
            )
        },
) { /* existing two text lines only; no edit TextButton */ }
```

- [ ] **Step 3: Merge details and metadata editing for every library track card**

In `TrackCard`, remove the independent `detailsOpen` state, the `查看文件详情` custom action/menu item, and the separate metadata-editor entry. Replace both with exactly one `查看并编辑元数据` action that opens `TrackDetailsMetadataDialog`. Keep existing copy, cut, share, favorite and delete actions unchanged.

Rename `TrackMetadataDialog` to `TrackDetailsMetadataDialog`. Above the three existing editable `OutlinedTextField`s add noninteractive text for:

```kotlin
Text("格式：${track.format}")
Text("时长：${formatTime(track.durationMs)}")
Text("大小：${formatFileSize(track.sizeBytes)}")
Text("位置：${track.uri}")
```

Keep the editable labels `歌曲名称`、`艺术家`、`专辑`, save validation and the existing focus-restoration behavior. The dialog title is `查看并编辑元数据`. The resulting input controls and dialog buttons retain their 48dp minimum heights.

Use the same dialog invocation for both card types:

```kotlin
TrackDetailsMetadataDialog(
    track = track,
    onDismiss = { metadataEditorOpen = false; restoreCardFocus = true },
    onSave = { title, artist, album ->
        onUpdateTrackMetadata(track, title, artist, album)
        metadataEditorOpen = false
        restoreCardFocus = true
    },
)
```

- [ ] **Step 4: Compile and perform targeted emulator accessibility QA**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```

Expected: Kotlin compilation succeeds.

Then use the Android emulator QA workflow to verify: automatic metadata-tagged albums display; an imported album with the same name appears once; card click opens but does not play; long press offers removal; TalkBack exposes the same card and track operations; album-track metadata has no separate edit focus; saving returns focus to the invoking row; and removing an album leaves the source file and its playlist untouched.

### Task 4: Full verification and release-quality review

**Files:**
- Modify: only files required to address verification failures in Tasks 1-3.

**Interfaces:**
- Consumes the completed aggregation, ViewModel and Compose changes from Tasks 1-3.
- Produces evidence that the implementation is whitespace-clean, unit-tested, compilable and release-buildable.

- [ ] **Step 1: Run all local unit tests**

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
```

Expected: all discovered local unit tests pass. Investigate any failure with `superpowers:systematic-debugging` before changing code.

- [ ] **Step 2: Run the required static and release gates**

Run:

```powershell
rtk git diff --check
```

Run from `D:\musicplayer\android`:

```powershell
rtk cmd /c "gradlew.bat :app:assembleRelease"
```

Expected: `git diff --check` has no output and `assembleRelease` ends with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Complete device smoke checks and report evidence**

Use the existing `scripts/smoke-checklist.md` in addition to these task-specific checks: imported and automatic same-name merge; persistent remove/restart; embedded title/artist/album parsing; CUE field priority; unified details-editor fields; direct-file, folder, song, artist and album-track long-press menus; TalkBack custom actions; large text; deletion confirmation; local playback; and DLNA/Chromecast/Bluetooth regression. Confirm `logcat` has no application FATAL or ANR.

- [ ] **Step 4: Leave the worktree uncommitted and summarize verification**

Do not create a commit, tag, worktree, release package, or remote action. Report the exact commands run, their outcomes, manual checks completed, and any checks that require a physical device.
