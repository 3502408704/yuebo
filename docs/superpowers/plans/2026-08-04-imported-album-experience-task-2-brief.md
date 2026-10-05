# Task 2: ViewModel 的专辑目录、导入与覆盖元数据

## 文件所有权

- 只修改 `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`。

不要编辑 `ImportedAlbum.kt`、`ImportedAlbumTest.kt` 或 `MusicApp.kt`。这些文件由其他子代理或控制器负责。

## 已存在的模型接口

```kotlin
data class TrackMetadataOverride(val title: String, val artist: String, val album: String)
data class ImportedAlbumTrack(/* uri, title, artist, album, durationMs, format, mimeType, cueStartMs, isCueTrack */)
data class ImportedAlbum(/* id, name, artist, treeUri, tracks, frontCoverUri, backCoverUri, description, descriptionEdited */)
object ImportedAlbumJson { fun encode(albums: List<ImportedAlbum>): String; fun decode(value: String?): List<ImportedAlbum> }
fun importedAlbumId(treeUri: String): String
fun NativeTrack.metadataKey(): String
fun NativeTrack.withMetadataOverride(override: TrackMetadataOverride?): NativeTrack
fun NativeTrack.toImportedAlbumTrack(): ImportedAlbumTrack
fun ImportedAlbumTrack.toNativeTrack(): NativeTrack
```

## 必须实现

1. 在 `MusicUiState` 增加：

```kotlin
val importedAlbums: List<ImportedAlbum> = emptyList()
val trackMetadataOverrides: Map<String, TrackMetadataOverride> = emptyMap()
```

2. 初始化时从 `SharedPreferences` 的 `imported_albums` 和 `track_metadata_overrides` 加载；保存使用 `ImportedAlbumJson` 和 `org.json.JSONObject`。JSON 损坏必须安全回退为空。

3. 提供 UI 将调用的公开方法：

```kotlin
fun albumTracks(album: ImportedAlbum): List<NativeTrack>
fun updateAlbum(album: ImportedAlbum)
fun updateAlbumArtwork(albumId: String, front: Boolean, uri: Uri)
fun updateTrackMetadata(track: NativeTrack, title: String, artist: String, album: String)
```

`updateAlbumArtwork` 必须在明确的选择回调中获得持久化读取权限；所有保存即刻更新 `_state`，并提供可见中文状态。`updateTrackMetadata` 以 `metadataKey()` 保存覆盖值；刷新后的 `tracks`、队列、当前曲目和 `albumTracks` 都应用该覆盖，不能损坏 CUE 的时长、起始位置或播放 URI。

4. 把现有 `collectAudioFromTree` 改为返回内部 `FolderImport`：音频、同树 CUE 表、图片 URI、`简介.txt` 内容。遍历时：

- 目录 MIME 继续递归；节点上限 5 万。
- 支持的音频继续用 `buildExternalTrack`；给同目录音频/CUE 分配同一稳定目录键。
- `.cue` 与 `.cue.txt` 通过既有 `decodeText` 读取为现有 `CueSheet`，调用既有 CUE parser，而不是重新写 parser。
- 匹配 `cover/front/folder/albumart` 为前封面、`back/backcover/封底` 为封底，扩展名仅 jpg/jpeg/png/webp；读取名为 `简介.txt` 的第一个非空文本。

5. `expandCueTracks` 接受可选 `cueSheets: List<CueSheet>`；没有传参时保留当前 MediaStore 查询行为。目录导入传入其收集的 CUE，确保导入专辑的 CUE 分轨保留现有规则。

6. 在 `importFolder` 中使用 `importedAlbumId(treeUri.toString())` upsert 专辑：

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

继续创建同名既有播放列表并播放第一首。导入成功状态同时说明已建立专辑；任何读取错误显示中文状态，不能抛出/闪退。

## 约束

- 所有命令以 `rtk` 开头，Android 编译目录为 `D:\musicplayer\android`。
- 仅 `apply_patch` 写文件；不提交、不创建 worktree、不删除或恢复任何已有改动。
- 不引入依赖，不修改原音频文件，不触碰播放器/投送代码。
- 你不是唯一工作者；仅编辑拥有的文件并保留其他代理的改动。
- 执行 task 1 数据测试和 `:app:compileDebugKotlin`，报告实际结果。

## 报告

将详细报告写到 `docs/superpowers/plans/2026-08-04-imported-album-experience-task-2-report.md`，包含修改位置、测试命令/结果、错误处理和自审；最终回复仅包含状态、测试摘要、问题与报告路径。
