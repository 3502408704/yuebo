# Task 1: 专辑模型与持久化格式

## 文件所有权

- 创建 `android/app/src/main/kotlin/com/example/local_music_player/ImportedAlbum.kt`
- 创建 `android/app/src/test/kotlin/com/example/local_music_player/ImportedAlbumTest.kt`

不要修改 `MusicApp.kt` 或 `NativeMusicViewModel.kt`。

## 必须提供的接口

```kotlin
data class TrackMetadataOverride(val title: String, val artist: String, val album: String)

data class ImportedAlbumTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val mimeType: String,
    val cueStartMs: Long = 0,
    val isCueTrack: Boolean = false,
)

data class ImportedAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val treeUri: String,
    val tracks: List<ImportedAlbumTrack>,
    val frontCoverUri: String? = null,
    val backCoverUri: String? = null,
    val description: String? = null,
    val descriptionEdited: Boolean = false,
)

object ImportedAlbumJson {
    fun encode(albums: List<ImportedAlbum>): String
    fun decode(value: String?): List<ImportedAlbum>
}

fun importedAlbumId(treeUri: String): String
fun NativeTrack.metadataKey(): String
fun NativeTrack.withMetadataOverride(override: TrackMetadataOverride?): NativeTrack
fun NativeTrack.toImportedAlbumTrack(): ImportedAlbumTrack
fun ImportedAlbumTrack.toNativeTrack(): NativeTrack
fun ImportedAlbum.matches(query: String): Boolean
```

`importedAlbumId` 使用 URI SHA-256 的十六进制前 16 位。`metadataKey` 必须包含 URI 和 `cueStartMs`，以使同源 CUE 分轨可分别编辑。`withMetadataOverride` 只能替换 title、artist、album，不能修改 URI、时长、CUE 起始位置和 `isCueTrack`。JSON 采用现有 `org.json`，损坏 JSON 或损坏数组项不得抛出异常，跳过坏项并返回其他有效项。

## TDD

先写并运行失败的 `ImportedAlbumTest`，至少覆盖：完整 JSON round-trip、同 URI 不同 CUE 起始位置产生不同键、覆盖不改变 CUE 时间字段、专辑名称/艺术家/曲名搜索匹配。确认 RED 后实现最小生产代码并运行同一测试 GREEN。

## 项目约束

- 所有命令以 `rtk` 开头；构建目录为 `D:\musicplayer\android`。
- 只使用 `apply_patch` 编辑；不提交、不创建 worktree、不清理或恢复其他人的修改。
- 不新增依赖；不改原音频文件。
- 你不是唯一在仓库工作的代理；保留其他变更，不改你的文件所有权范围以外的文件。

## 报告

将详细报告写到 `docs/superpowers/plans/2026-08-04-imported-album-experience-task-1-report.md`。报告包含 RED/GREEN 命令和结果、文件清单和自审结论；最终回复仅包含状态、测试摘要、问题与报告路径。
