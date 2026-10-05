# Task 3: 专辑列表、详情页与编辑交互

## 文件所有权

- 只修改 `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`。

不要创建/修改数据模型、测试或 `NativeMusicViewModel.kt`。另一个代理会提供 `ImportedAlbum`、`ImportedAlbumTrack`、`TrackMetadataOverride`、`MusicUiState.importedAlbums`、`MusicUiState.trackMetadataOverrides` 及 ViewModel 回调。

## 可假定的整合接口

根 `MusicApp` 可以调用：

```kotlin
viewModel.albumTracks(album: ImportedAlbum): List<NativeTrack>
viewModel.updateAlbum(album: ImportedAlbum)
viewModel.updateAlbumArtwork(albumId: String, front: Boolean, uri: Uri)
viewModel.updateTrackMetadata(track: NativeTrack, title: String, artist: String, album: String)
```

## 需求

- 为 `Screen` 新增专辑详情状态并保存当前专辑 ID；系统返回从详情回媒体库，不自动播放。
- “专辑”浏览模式只渲染 `state.importedAlbums`，按名称排序；没有已导入专辑时显示中文空状态。不得使用 MediaStore `ALBUM` 或 `albumCollections` 自动发现。
- 专辑卡片显示装饰封面、专辑名、艺术家（有值才显示）、曲目数；整卡只有一个中文可访问性节点，点击目标至少 48dp。
- 详情页有返回和“编辑专辑”入口。顶部为封面、名称、艺术家、曲目数、简介摘要；中部轨道列表；末尾为封底和完整简介。
- 轨道 key 为 `track.metadataKey()`。点击轨道调用现有 `playFromQueue`；轨道的“编辑元数据”是独立明确操作。现有普通 `TrackCard` 菜单也添加同一编辑入口。
- 使用标准 `AlertDialog` 编辑专辑名称、艺术家、简介；不要把编辑绑定到封面点击。封面、封底分别通过 `OpenDocument` 选择 `image/*`，由根回调调用 ViewModel。
- 封面、碟面、封底都是纯装饰：`contentDescription = null`，无点击、无语义焦点。
- 详情初次出现使用稳定 Compose `fadeIn + scaleIn`；碟面根据 `LazyListState` 滚动距离以 `animateFloatAsState` 旋转，封底可见时静止。API 24-25 需以 Build 版本保护动画开关查询。动效不表达信息，也不能拦截列表操作。
- 返回列表后保持原列表位置并滚动到原专辑项；编辑对话框关闭后焦点仍留在合理的编辑入口。显示保存结果的中文 live region。
- 仅用已有 Compose/Android API，不新增依赖；不要重构无关界面。

## 验证

数据模型尚由并行代理实现，当前任务无法单独完成 Kotlin 编译。完成后检查 Kotlin 语法、imports、Compose API 版本和所有新增参数调用；把待整合编译留给控制器在 ViewModel 任务后统一执行。不得通过修改数据模型或跳过动画需求来规避此限制。

## 项目约束

- 所有命令以 `rtk` 开头；只使用 `apply_patch` 编辑。
- 不提交、不创建 worktree、不清理或恢复其他人的修改。
- 你不是唯一在仓库工作的代理；仅编辑拥有的 `MusicApp.kt`，保留其他变更。
- 每个操作控件短中文名称、48dp 最小目标；焦点或 TalkBack 浏览不能启动导入或播放。

## 报告

将详细报告写到 `docs/superpowers/plans/2026-08-04-imported-album-experience-task-3-report.md`。说明修改位置、静态自审结果、无法独立编译的原因和待整合检查；最终回复仅包含状态、摘要、问题与报告路径。
