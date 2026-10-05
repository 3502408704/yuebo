# 专辑下载文件夹/封面 + 在线双列网格

日期：2026-08-04

## 目标

1. 专辑下载：新建 `Music/汪汪播放器/<专辑名>/` 文件夹，把专辑封面、每首歌封面与歌曲放进文件夹。
2. 封面必须下载，但绝不能被系统媒体库（相册/扫描器）识别；`covers/` 子目录 + `.nomedia` + `application/octet-stream` MIME。
3. 本地曲库与在线媒体统一双列显示（含在线专辑详情页），并控制图片加载大小。

## 已确认命名（方案A）

- 歌曲：`Music/汪汪播放器/<专辑名>/<歌手 - 歌名>.<ext>`
- 专辑封面：`Music/汪汪播放器/<专辑名>/covers/cover.jpg`（`album.coverUrl`，回退首首歌封面）
- 单曲封面：`Music/汪汪播放器/<专辑名>/covers/<歌手 - 歌名>.jpg`
- 单曲下载不变：平铺 `Music/汪汪播放器/`，不建文件夹、不下载封面
- 重复下载：同一专辑已完成歌曲跳过（DAO 按 folderName 匹配）

## 任务分解

1. Room：`DownloadTaskEntity` 加 `folderName` 列（v1→v2 迁移 `MIGRATION_1_2`）；新增 `findCompletedOnlineTask` DAO。
2. `DownloadRepository.enqueue` 增加 `folderName` 参数；`createPendingMedia`/`findPendingMedia`/`legacyPendingFile` 按 `folderName` 计算 `RELATIVE_PATH`；`toTask` 映射。
3. `DownloadTask` 增加 `folderName` 字段。
4. 新建 `AlbumCoverDownloader.kt`：写 `covers/.nomedia`、`covers/cover.jpg`、`covers/<歌手 - 歌名>.jpg`（MediaStore.Files + octet-stream，API<29 直接写文件）；已有文件跳过；失败静默。
5. `NativeMusicViewModel.downloadOnlineAlbum`：先下载封面，再 `enqueueOnlineAlbumTracks(tracks, quality, folderName)`。
6. `MusicApp.loadNamedArtwork` 增加 `covers/` 子目录检查（MediaStore octet-stream + 直接文件读取）；`loadSongArtwork` 增加 `<歌手 - 歌名>` 名字。
7. `OnlineScreen`：歌曲 Tab、专辑 Tab、专辑详情页改为 `LazyVerticalGrid(GridCells.Fixed(2))` 卡片；保留点击播放/长按下载/加载更多整行/中文无障碍语义。
8. 图片大小：`OnlineArtwork`/`loadRemoteArtwork` 解码目标从 720 降到 512。

## 验证

- `rtk git diff --check`
- `gradlew.bat :app:compileDebugKotlin`、`:app:assembleRelease`、`testDebugUnitTest`
- 模拟器冒烟：专辑下载后 `Music/汪汪播放器/<专辑名>/covers/` 存在 `.nomedia`；MediaStore 无 image/ 行；在线页双列渲染
