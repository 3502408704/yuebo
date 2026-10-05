# 已导入专辑体验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户通过“导入文件夹”建立可编辑、带封面/封底/简介与 CUE 分轨的专辑，并在媒体库展示独立的专辑详情页。

**Architecture:** 新增纯 Kotlin 的 `ImportedAlbum` 数据模型和 JSON 存储帮助器；`NativeMusicViewModel` 持有专辑目录与全局歌曲元数据覆盖，负责文件夹导入、CUE 展开与持久化。`MusicApp.kt` 只读取状态并实现专辑网格、详情页、编辑对话框、图片选择和受控动效。

**Tech Stack:** Kotlin、Android `SharedPreferences` / `DocumentsContract` / `ContentResolver`、Jetpack Compose Material 3、kotlin.test、既有 BASS 播放链路。

## Global Constraints

- 所有 shell 命令以 `rtk` 开头；Android 构建必须在 `D:\musicplayer\android` 执行。
- 只用 `apply_patch` 修改文件；不创建 worktree，不删除或恢复用户文件，不自动提交。
- 不新增依赖；专辑、图片和元数据使用 Android 标准 API 与现有 Compose BOM。
- 专辑只来自用户“导入文件夹”，不得用 MediaStore `ALBUM` 自动发现。
- 歌曲元数据是应用内覆盖，不写原音频；覆盖键为 `uri + cueStartMs`，确保 CUE 分轨可分别编辑。
- 保留既有 CUE UTF-8 严格解码、GB18030 回退、分轨时长/起始位置和播放路径。
- 封面、碟面、封底是装饰图片：`contentDescription = null`，无可访问性焦点；操作控件有简短中文语义和至少 48dp 点击目标。
- 导入、播放、保存只由明确点击触发；焦点移动和 TalkBack 浏览不能触发工作。
- 用户完成设备手动测试；交付时提供测试项目，不运行模拟器自动 QA。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `android/app/src/main/kotlin/com/example/local_music_player/ImportedAlbum.kt` | 专辑/曲目覆盖值对象、JSON 序列化、稳定 ID 与元数据键。 |
| `android/app/src/test/kotlin/com/example/local_music_player/ImportedAlbumTest.kt` | 纯数据模型的持久化与 CUE 覆盖键测试。 |
| `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt` | 状态、导入目录扫描、外部 CUE 展开、专辑/歌曲元数据保存与恢复。 |
| `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt` | 仅导入专辑的媒体库视图、详情/编辑界面、图片选择、动画与导航。 |

## Task 1: 专辑模型与持久化格式

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/ImportedAlbum.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/ImportedAlbumTest.kt`

**Interfaces:**
- Produces `data class ImportedAlbum`, `data class ImportedAlbumTrack`, `data class TrackMetadataOverride`.
- Produces `object ImportedAlbumJson` with `encode(albums: List<ImportedAlbum>): String` and `decode(value: String?): List<ImportedAlbum>`.
- Produces `fun importedAlbumId(treeUri: String): String`, `fun NativeTrack.metadataKey(): String`, `fun NativeTrack.withMetadataOverride(override: TrackMetadataOverride?): NativeTrack`, and `fun ImportedAlbum.matches(query: String): Boolean`.
- Produces `fun NativeTrack.toImportedAlbumTrack(): ImportedAlbumTrack` and `fun ImportedAlbumTrack.toNativeTrack(): NativeTrack`; both conversions preserve URI, title, artist, album, duration, format, MIME, cue start and `isCueTrack`.

- [ ] **Step 1: 写出失败的模型测试**

```kotlin
class ImportedAlbumTest {
    @Test fun albumJsonRoundTripKeepsArtworkDescriptionAndCueTrack() {
        val album = ImportedAlbum(
            id = importedAlbumId("content://tree/album"),
            name = "专辑",
            artist = "艺术家",
            treeUri = "content://tree/album",
            tracks = listOf(ImportedAlbumTrack(
                uri = "content://document/source.flac", title = "第一首", artist = "艺术家",
                album = "专辑", durationMs = 1234, format = "FLAC", mimeType = "audio/flac",
                cueStartMs = 60_000, isCueTrack = true,
            )),
            frontCoverUri = "content://document/front.jpg",
            backCoverUri = "content://document/back.jpg",
            description = "简介",
            descriptionEdited = true,
        )
        assertEquals(listOf(album), ImportedAlbumJson.decode(ImportedAlbumJson.encode(listOf(album))))
    }

    @Test fun cueTracksWithSameUriHaveDifferentMetadataKeys() {
        val source = Uri.parse("content://document/source.flac")
        assertNotEquals(
            NativeTrack(1, source, "A", "", "", 1, "FLAC", "audio/flac", "", cueStartMs = 0).metadataKey(),
            NativeTrack(2, source, "B", "", "", 1, "FLAC", "audio/flac", "", cueStartMs = 60_000, isCueTrack = true).metadataKey(),
        )
    }

    @Test fun albumsAreMatchedByNameArtistOrTrackTitle() {
        val album = ImportedAlbum("a", "夜航", "作者", "tree", listOf(ImportedAlbumTrack("u", "星光", "作者", "夜航", 0, "MP3", "audio/mpeg")))
        assertTrue(album.matches("星光"))
        assertFalse(album.matches("不存在"))
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"` from `D:\musicplayer\android`.

Expected: 编译失败，原因是 `ImportedAlbum`、`ImportedAlbumJson` 与 `metadataKey` 尚未定义。

- [ ] **Step 3: 实现最小模型与 JSON**

```kotlin
data class TrackMetadataOverride(val title: String, val artist: String, val album: String)

data class ImportedAlbumTrack(
    val uri: String, val title: String, val artist: String, val album: String,
    val durationMs: Long, val format: String, val mimeType: String,
    val cueStartMs: Long = 0, val isCueTrack: Boolean = false,
)

data class ImportedAlbum(
    val id: String, val name: String, val artist: String, val treeUri: String,
    val tracks: List<ImportedAlbumTrack>, val frontCoverUri: String? = null,
    val backCoverUri: String? = null, val description: String? = null,
    val descriptionEdited: Boolean = false,
)
```

实现只接受 JSON 对象数组；损坏项跳过，不能让应用启动失败。稳定 ID 使用 URI SHA-256 的十六进制前 16 位。`metadataKey` 连接 URI 与 `cueStartMs`；覆盖值存在时直接替换三项显示文本。

- [ ] **Step 4: 运行模型测试并确认 GREEN**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"` from `D:\musicplayer\android`.

Expected: `ImportedAlbumTest` 的两个测试通过。

## Task 2: ViewModel 的专辑目录、导入与覆盖元数据

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/ImportedAlbumTest.kt`

**Interfaces:**
- Consumes Task 1 的所有模型接口。
- Adds `MusicUiState.importedAlbums: List<ImportedAlbum>` and `MusicUiState.trackMetadataOverrides: Map<String, TrackMetadataOverride>`.
- Produces `updateAlbum(album: ImportedAlbum)`, `updateAlbumArtwork(albumId: String, front: Boolean, uri: Uri)`, `updateTrackMetadata(track: NativeTrack, title: String, artist: String, album: String)` and `albumTracks(album: ImportedAlbum): List<NativeTrack>`.
- `importFolder(treeUri)` 必须生成/更新专辑、保持既有播放列表与播放首曲目行为。

- [ ] **Step 1: 扩展失败测试**

在 `ImportedAlbumTest` 增加“JSON 缺少新字段仍以空默认值恢复”和“`withMetadataOverride` 保留 CUE 起始位置”的测试：

```kotlin
@Test fun metadataOverrideChangesTextWithoutChangingCueTiming() {
    val source = NativeTrack(1, Uri.parse("content://document/a.flac"), "原题", "原作者", "原专辑", 2_000, "FLAC", "audio/flac", "", cueStartMs = 1_000, isCueTrack = true)
    val result = source.withMetadataOverride(TrackMetadataOverride("新题", "新作者", "新专辑"))
    assertEquals("新题", result.title)
    assertEquals(1_000, result.cueStartMs)
    assertTrue(result.isCueTrack)
}
```

- [ ] **Step 2: 确认 RED**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"` from `D:\musicplayer\android`.

Expected: 新增测试因覆盖帮助器不完整而失败。

- [ ] **Step 3: 接入目录扫描、CUE 与持久化**

修改 `collectAudioFromTree` 返回内部 `FolderImport`，其中包含：已收集音频、同树 CUE 内容、命名图片 URI 和 `简介.txt` 文本。遍历子文档时给音频和 CUE 使用相同的稳定目录键；调用 `expandCueTracks(audioTracks, cueSheets)`，使既有 CUE 规则可复用。

在 `importFolder` 中按 `importedAlbumId(treeUri.toString())` upsert：

```kotlin
val existing = _state.value.importedAlbums.firstOrNull { it.id == albumId }
val album = ImportedAlbum(
    id = albumId,
    name = existing?.name ?: folderName,
    artist = existing?.artist.orEmpty(),
    treeUri = treeUri.toString(),
    tracks = tracks.map(NativeTrack::toImportedAlbumTrack),
    frontCoverUri = existing?.frontCoverUri ?: frontCandidate,
    backCoverUri = existing?.backCoverUri ?: backCandidate,
    description = if (existing?.descriptionEdited == true) existing.description else descriptionFromFile,
    descriptionEdited = existing?.descriptionEdited == true,
)
```

首次初始化加载 `imported_albums` 与 `track_metadata_overrides`；更新后立即写回 JSON。MediaStore 刷新、外部专辑轨道恢复和队列/当前曲目均用 `withMetadataOverride` 统一显示覆盖值。图片权限与目录权限只在明确的导入/选择点击回调中取得；读取失败返回空结果和可见状态，不抛出异常。

- [ ] **Step 4: 运行定向单元测试与 Kotlin 编译**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.ImportedAlbumTest"` from `D:\musicplayer\android`.

Expected: `ImportedAlbumTest` 全部通过。

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"` from `D:\musicplayer\android`.

Expected: Kotlin 编译成功。

## Task 3: 专辑列表、详情页与编辑交互

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes `MusicUiState.importedAlbums`、`trackMetadataOverrides`、`NativeMusicViewModel.albumTracks` 和 Task 2 的更新方法。
- Adds `Screen.Album`、`ImportedAlbumCard`、`AlbumDetailScreen`、`AlbumEditDialog` 与 `TrackMetadataDialog`。
- Adds `OpenDocument` 图片选择回调；选择结果调用 `updateAlbumArtwork`。

- [ ] **Step 1: 复用 Task 1 的筛选测试并实现最小 UI 与导航**

在 `LibraryScreen` 的 `LibraryBrowse.Albums` 分支只显示 `state.importedAlbums`，按名称排序，且空状态为“尚未导入专辑”。保留现有艺术家、歌曲和文件夹代码；删除 albums 分支对 `albumCollections` 的调用。

`ImportedAlbumCard` 使用单个 48dp 以上的 clickable 语义节点，显示装饰封面、名称、艺术家与曲数；点击设置 `Screen.Album` 和当前 `albumId`，不调用播放。详情页使用：

```kotlin
LazyColumn(state = listState) {
    item(key = "hero") { AlbumHeader(...) }
    item(key = "disc") { AlbumDisc(frontCoverUri, rotation) }
    items(tracks, key = { it.metadataKey() }) { track -> AlbumTrackRow(...) }
    item(key = "back") { AlbumArtwork(backCoverUri, Modifier.fillMaxWidth()) }
    item(key = "description") { AlbumDescription(...) }
}
```

以 `LazyListState` 的已滚动距离驱动 `animateFloatAsState`，在封底可见时将碟面旋转归零；详情初次组合使用短 `fadeIn + scaleIn`。封面/碟面/封底都没有内容描述或焦点。系统动画关闭时读取 `ValueAnimator.areAnimatorsEnabled()` 并将动画目标固定为静态值。

通过“编辑专辑”打开标准 `AlertDialog`，编辑名称、艺术家和简介；通过明确的“选择封面”“选择封底”按钮启动图片选择。轨道行提供独立的“编辑元数据”按钮；现有 `TrackCard` 菜单也增加同一对话框入口，调用 `updateTrackMetadata`。对话框关闭后回到触发它的控件；返回专辑列表时保留 lazy grid 状态并滚动到上次的专辑项。

- [ ] **Step 2: 编译 UI**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"` from `D:\musicplayer\android`.

Expected: Kotlin 编译成功。

## Task 4: 整合复核与交付验证

**Files:**
- Modify only when a Task 1-3 review发现明确缺陷：`ImportedAlbum.kt`、`NativeMusicViewModel.kt`、`MusicApp.kt` 或其测试。

- [ ] **Step 1: 检查计划覆盖**

逐条对照 `docs/superpowers/specs/2026-08-04-imported-album-experience-design.md`：只导入专辑、封面/封底/简介、文件夹与曲目元数据、CUE、打开/滚动动效、图片无焦点、返回和状态反馈均有对应实现。

- [ ] **Step 2: 运行自动化验证**

Run: `rtk git diff --check` from `D:\本地音乐播放器`.

Expected: 无空白错误。

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest"` from `D:\musicplayer\android`.

Expected: 单元测试通过。

Run: `rtk cmd /c "gradlew.bat :app:assembleRelease"` from `D:\musicplayer\android`.

Expected: Release APK 构建成功；现有弃用警告可保留，新的编译错误不可保留。

- [ ] **Step 3: 整理用户手动测试清单**

交付时列出：导入目录、只显示导入专辑、重启恢复、前后封面、`简介.txt` 与编辑优先级、CUE 分轨和播放、歌曲/专辑编辑元数据、大字体、TalkBack、返回焦点、歌曲/文件夹浏览、播放列表、DLNA/Chromecast 和蓝牙回归。
