# 在线封面展示与流媒体播放稳定性设计

日期：2026-08-04

## 目标

1. 在线搜索结果的歌曲行、专辑行、专辑详情页，以及在线歌曲的正在播放页/迷你播放器，全部显示封面。
2. 首次播放取址稳定：ShyMusic 给出的地址若被 BASS 实际打不开，必须继续尝试妖狐同平台兜底，而不是直接失败。
3. 解析快速且地址缓存复用，避免重复请求；缓存必须有明确的容量上限与清理策略，不能无限堆在内存。

## 范围

包含：

- 轻量远程封面加载 Composable 与内存 LRU 图片缓存（不引入 Coil/Glide）。
- 搜索结果歌曲行/专辑行/专辑详情封面，正在播放页与迷你播放器在线封面。
- 播放取址的源尝试序列（缓存 → ShyMusic → 妖狐同平台），BASS 打开失败视为换源信号。
- ShyMusic 解析 5 秒时间预算，超时视同失败走妖狐兜底。
- 播放地址缓存：只缓存 BASS 真正打开成功的地址；容量上限 + 搜索/切平台清理。
- 搜索返回后后台预解析前 3 首（与播放共用同一解析与缓存，受同一上限约束）。
- 相关纯逻辑单元测试、编译、设备冒烟。

不包含：

- 引入第三方图片加载库。
- URL 或封面缓存持久化到磁盘（延续既有"在线 URL 不落盘"约定）。
- 播放已出声后网络断流的自动重试/切歌——本轮聚焦首次播放取址。
- 修改 DLNA/Chromecast 的取址逻辑（Cast 侧沿用现有 refreshable 机制）。

## 约束与事实

- 搜索响应已携带封面 URL：ShyMusic 为 `pic`，妖狐为 `image`/`cover`/`picture`。`OnlineTrack.artworkUrl`、`OnlineAlbum.coverUrl` 已解析，仅未渲染。
- `player.playUrl` 在 `BASS_StreamCreateURL` 失败或 2 秒内拿不到音频格式时抛异常（`check`）。
- 在线曲目不写持久化队列（`shouldPersistQueueTrack` 排除在线 ID）；`NativeTrack` 新增 `artworkUrl` 默认 null，不影响旧队列 JSON 解析。
- 仓库规则：命令以 `rtk` 开头；构建从 `D:\musicplayer\android` 执行；文件修改用 `apply_patch`；不自动提交；不创建 worktree。

## 方案

### 封面渲染

新增 `OnlineArtwork(url: String?, modifier: Modifier)` Composable：

- `produceState<Bitmap?>`，key 为 url；`Dispatchers.IO` 用 `HttpURLConnection` GET 拉取字节，复用现有 `decodeSampled()` 解码。
- url 为空或加载失败显示 `Icons.Default.Album` 占位（与现有 `ArtworkContent` 一致）。
- 行内封面为装饰性：`contentDescription = null`，行保持单一语义节点，不产生重复焦点。

新增 `OnlineArtworkCache`（文件级单例，供所有实例复用）：

- 基于统一 `SimpleLruCache`，上限 100 条、总像素约 16MB，超限按访问序逐出。
- `MusicApplication.onTrimMemory` 在 UI 隐藏或中度以上内存压力时清空图片缓存。

渲染位置：

- `OnlineTrackRow` 左侧 48dp 方形缩略图，行 minHeight 提到 56dp。
- `OnlineAlbumRow` 左侧 56dp 封面。
- `OnlineAlbumDetailScreen` 标题区左侧约 96dp 封面。
- `NativeTrack` 增加 `artworkUrl: String? = null`；`onlineTrackToNativeTrack`/`neteaseTrackToNativeTrack` 填充。
- `AlbumArt`/`SongArtworkThumbnail`/`MiniPlayer`：`track.artworkUrl` 非空走 `OnlineArtwork`，否则走现有本地封面逻辑。

### 首次播放取址稳定

根因：`OnlineTrackResolver.resolve` 在 Shy API 返回 URL 后直接返回；若该 URL 被 BASS 打开失败，`startOtherOnlinePlayback`/`startNeteasePlaybackWithFallback` 直接失败，妖狐兜底 URL 从未被尝试。

改法：

- `startOtherOnlinePlayback` 重构为有界源尝试序列：
  1. 缓存命中 → `playOtherOnlineUrl`；失败则驱逐该缓存条目，继续。
  2. ShyMusic 解析 → `playOtherOnlineUrl`；失败继续。
  3. 妖狐同平台兜底解析 → `playOtherOnlineUrl`；失败则整首失败。
  - 打开成功的那条写入 `onlineResolutionCache`。
- `startNeteasePlaybackWithFallback` 同样把 Shy 的 `playUrl` 失败改为"继续走妖狐链"（缓存 → 搜索精确匹配 → 多 endpoint 尝试）；Shy 成功时也写入 `neteaseResolutionCache`（目前该路径未缓存）。
- 全部源失败 → `status = "暂时无法播放这首歌曲，请稍后重试"`，不自动跳歌。

### 解析速度与缓存清理

- `OnlineTrackResolver.resolve`：Shy 尝试包 `withTimeout(5_000)`，超时视同失败走妖狐；`resolve` 改为 suspend（调用点均为 suspend 上下文，兼容）。

缓存清理策略：

- `onlineResolutionCache`（非网易云地址）：改为 `SimpleLruCache`，上限 100 条；每次 `searchOnline`/`selectOnlinePlatform` 开始时清空；BASS 打开失败逐出单条。
- `neteaseResolutionCache`：改为 `SimpleLruCache`，上限 50 条；同样在搜索/切平台时清空；失败驱逐单条。
- `OnlineArtworkCache`：100 条/16MB LRU + `onTrimMemory` 清空。
- `onlineTrackById`/`neteaseTrackById`：仅存当前队列，每次 `playOnline`/`playNetease` clear 重建，天然自清理。
- URL 一律不落盘，进程重启即清，不会跨会话累积。

预解析：

- `fetchOnlineSearch` 成功返回后，后台按顺序解析前 3 首并写入同一 LRU；新搜索/切平台时随 `onlineSearchJob` 取消。
- 解析失败静默忽略，不影响列表与手动播放。

## 文件边界

- `MusicApp.kt`：`OnlineArtwork`/`OnlineArtworkCache`、行/详情/正在播放/迷你封面接入。
- `OnlineScreen.kt`：歌曲行、专辑行、专辑详情封面。
- `NativeMusicViewModel.kt`：`NativeTrack.artworkUrl`、源尝试序列重构、缓存清理触发、预解析。
- `YaohuOtherMusicApi.kt`：`OnlineTrackResolver.resolve` 超时预算（改 suspend）。
- `MusicApplication.kt`：`onTrimMemory` 清图片缓存。
- 新增 `SimpleLruCache.kt`（或并入现有文件）；测试 `SimpleLruCacheTest.kt`、扩展 `ShySourceFallbackTest`。

## 错误处理

- 封面加载失败静默显示占位图标，不报错不打断列表。
- 换源尝试全程失败才提示"暂时无法播放这首歌曲，请稍后重试"，不暴露 URL/密钥/堆栈。
- 预解析失败静默，不影响搜索结果显示与手动播放。
- 超时只作为 Shy 尝试的等待预算，不阻塞后续源。

## 无障碍

- 行内封面 decorative，不新增 TalkBack 节点；行语义（标题/歌手/专辑）不变。
- 正在播放页封面保留"歌曲封面：标题"语义；迷你播放器沿用现有单一节点。
- 无新增可点击目标；操作目标尺寸保持 ≥48dp。
- 验收覆盖 TalkBack、大字体、灰阶模式。

## 测试与验收

按 TDD 顺序：

1. `SimpleLruCacheTest`：容量上限逐出、访问序更新、重量上限、清空。
2. 解析超时兜底：假 Shy Http 慢响应/超时 → 走妖狐；Shy 成功不调妖狐（扩展 `ShySourceFallbackTest`）。
3. 模型填充：`NativeTrack` 携带 `artworkUrl` 的转换逻辑测试。
4. 构建：`rtk git diff --check`；`gradlew :app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleRelease`（工作目录 `D:\musicplayer\android`）。
5. 设备冒烟：四平台搜索歌曲/专辑封面、专辑详情封面、正在播放/迷你封面；断源场景首次播放换源成功；同一曲目重复播放不重复请求（logcat 观察）；TalkBack 歌曲行单一节点；回归本地播放/DLNA/Chromecast/蓝牙。
