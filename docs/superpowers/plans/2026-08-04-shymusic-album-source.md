# ShyMusic 在线源接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 ShyMusic（shybot.top）接入为在线媒体主源：歌曲搜索、真实专辑搜索与详情解析、音质切换、播放地址与歌词；妖狐仅做同平台兜底（ShyMusic 返回 `not`/无地址/目标音质不可用时），删除跨平台候选逻辑。

**Architecture:** 新增 `ShyMusicApi.kt`（`ShyMusicHttp`/`ShyMusicApiClient`/`ShyMusicCatalog`/音质枚举/模型）。`OnlineTrackResolver` 改为"ShyMusic 优先、妖狐同平台兜底"的唯一解析入口，播放与下载共用。`NativeMusicViewModel` 的歌曲搜索改走 ShyMusic；专辑 Tab 改为真实专辑搜索，详情按需拉取曲目。`OnlineScreen`/`MusicApp` 只展示状态并转发显式操作。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、`org.json`、`HttpURLConnection`、kotlinx.coroutines、现有 BASS 播放与 MediaStore 下载、`YaohuApiClient` 妖狐兜底。

## Global Constraints

- ShyMusic 基址 `https://shybot.top/v2/music/api/`；所有请求统一带 `shykey`，值来自未提交的 `android/local.properties` 的 `SHYMUSIC_API_KEY`，经 `BuildConfig.SHYMUSIC_API_KEY` 注入；密钥绝不写入源码、测试、日志、截图或已提交文档。
- ShyMusic 为主源，妖狐仅同平台兜底；不换歌、不跨平台。删除 `onlineFallbackPlatforms`/`exactOnlineFallbackCandidates`/`findExactOnlineFallbacks`。
- 专辑解析只用 ShyMusic 真实专辑接口（`_album_search`/`_album_info`/`_album_list`），不再用搜索结果凑专辑（删除 `groupOnlineAlbums`）。
- 不展示、不请求 SVIP 音质（master/atmos/hifi/higH 等）；最高档为无损 flac。
- 所有用户可见文案、状态与语义使用简体中文；点击/显式操作才发起搜索、解析、播放或下载；TalkBack 焦点变化不得触发网络请求。
- 下载仍写 `Music/汪汪播放器/`；`OnlineTrack`/`OnlineResolvedTrack`/缓存键结构保持兼容。
- 不新增第三方依赖；所有 shell 命令以 `rtk` 开头；Android 构建从 `D:\musicplayer\android` 运行；文件修改用 `apply_patch`；不创建 worktree、不自动提交。
- 完成前必须通过：`rtk git diff --check`、`:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleRelease`。

---

## Files and Responsibilities

- Create `android/app/src/main/kotlin/com/example/local_music_player/ShyMusicApi.kt`：HTTP 客户端、四平台音质枚举、`ShyTrack`/`ShyAlbum`/`ShyResolvedTrack` 模型与 `ShyMusicCatalog`。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt`：ShyMusic 优先 → 妖狐同平台兜底。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt`：`OnlineAlbum` 增加 artist/songCount/time/desc；删除跨平台候选与凑专辑函数。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`：歌曲搜索改 ShyMusic；专辑搜索/详情状态；播放/下载走新解析器；删除跨平台兜底。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`：专辑 Tab 真实专辑行；详情页加载/失败/重试。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`：专辑详情打开/返回/重试与专辑 Tab 触发搜索的接线。
- Modify `android/app/build.gradle.kts`：注入 `SHYMUSIC_API_KEY` 的 `buildConfigField`。
- Create `android/app/src/test/kotlin/com/example/local_music_player/ShyMusicApiTest.kt`：stub HTTP 的解析与映射测试。
- Create `android/app/src/test/kotlin/com/example/local_music_player/ShySourceFallbackTest.kt`：同平台兜底与解析缓存隔离测试。
- Rewrite `android/app/src/test/kotlin/com/example/local_music_player/OnlineFallbackTest.kt` / `OnlineAlbumTest.kt`：移除跨平台/凑专辑测试。

---

### Task 1: ShyMusic API 层（TDD）

**Files:**
- Create: `android/app/src/test/kotlin/com/example/local_music_player/ShyMusicApiTest.kt`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/ShyMusicApi.kt`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: 写失败测试（stub HTTP）**
  - `search_songs_maps_platform_fields`：qq 用 `mid`、wyy 用 `id`、kg 用 `hash`、kw 用 `id`，映射 title/artist/album/artwork/lyricUrl/duration。
  - `search_albums_maps_summary`：album_search 的 `album_mid`/`id`/`song_count`/`pic`/`time`。
  - `wyy_album_info_parses_nested_tracks`：`data[0].data` 曲目解析。
  - `qq_kg_kw_album_list_parses_tracks`：`_album_list` 的 `data` 数组解析。
  - `resolve_url_success_and_not_throws`：返回 URL 字符串成功；返回 `not`/`{"msg":...}` 抛 `ShyMusicException`。
  - `quality_mapping_uses_yaohu_tier`：`QqQuality.Flac`→qn=2、`NeteaseQuality.Lossless`→qn=2、`QqQuality.Mp3`→qn=0 等。
  - `lyrics_parse`：`{"lyric":"..."}` 解析。
- [ ] **Step 2: 运行 `:app:testDebugUnitTest --tests '*ShyMusicApiTest'`，确认因缺类失败**
- [ ] **Step 3: 实现 `ShyMusicApi.kt`**
  - `ShyMusicException`、`interface ShyMusicHttp`、`ShyMusicApiClient`（`BuildConfig.SHYMUSIC_API_KEY`）。
  - `interface ShyQuality { qnIndex; label }` + `ShyQqQuality`/`ShyWyyQuality`/`ShyKgQuality`/`ShyKwQuality`（3 档）。
  - `ShyTrack`/`ShyAlbum`/`ShyResolvedTrack`；`ShyTrack.toOnlineTrack()`；平台 apiName 与 id 参数名映射。
  - `ShyMusicCatalog`：`searchSongs`/`searchAlbums`/`albumTracks`/`resolve`/`lyrics`。
  - `resolve`：映射 YaohuQuality→qnIndex，请求 `<平台>_url`，校验 `not`，解析 URL，取歌词，按 URL 扩展名定 mime/extension。
- [ ] **Step 4: 注入密钥**
  - `build.gradle.kts` 读 `local.properties` 的 `SHYMUSIC_API_KEY`，加 `buildConfigField`。
- [ ] **Step 5: 跑 ShyMusic 测试转绿；跑 `compileDebugKotlin`**

### Task 2: 解析器同平台兜底（TDD）

**Files:**
- Create: `android/app/src/test/kotlin/com/example/local_music_player/ShySourceFallbackTest.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt`

- [ ] **Step 1: 写失败测试**
  - `shy_success_skips_yaohu`：stub ShyMusic 成功时妖狐目录不被调用。
  - `shy_not_falls_back_same_platform_yaohu`：ShyMusic 抛错时调用同平台妖狐目录并返回其解析结果。
  - `resolver_never_uses_other_platform`：断言不存在跨平台候选函数（编译期即可验证：删除后无引用）。
- [ ] **Step 2: 改 `OnlineTrackResolver.kt`**
  - `resolve(track, quality)`：`runCatching { shyCatalog.resolve(...) }.getOrNull() ?: yaohuResolve(...)`。
  - `resolve(track, qualityApiValue)` 保持。
  - 网易云妖狐路径保留 VIP→Free 重试语义。
- [ ] **Step 3: 跑新测试 + 既有 `YaohuOtherMusicApiTest` 转绿**

### Task 3: ViewModel 主源切换与专辑状态

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt`

- [ ] **Step 1: `fetchOnlineSearch` 改走 `shyCatalog.searchSongs`；删除 `findExactOnlineFallbacks` 与 `playOnlineFallbackFor` 跨平台分支；`searchOnline` 不再调用 `groupOnlineAlbums`**
- [ ] **Step 2: 新增专辑搜索与详情状态**
  - `MusicUiState`：`onlineAlbumsSearching`、`onlineAlbumDetail: OnlineAlbumDetail?`（`album` + `tracks?` + `error?`）。
  - `searchOnlineAlbums()`：`shyCatalog.searchAlbums` → `onlineAlbums`。
  - `openOnlineAlbum(album)`/`retryOnlineAlbumDetail()`/`closeOnlineAlbum()`：`shyCatalog.albumTracks` 拉取并转 `List<OnlineTrack>`。
  - `downloadOnlineAlbum(album)`：先取曲目再逐个入队（保留进度回显）。
- [ ] **Step 3: 播放/下载解析统一走 `onlineResolver`（已内置兜底）；`startNeteasePlaybackWithFallback` 先试 ShyMusic 再走妖狐网易云链，失败仅提示不再跨平台；删除 `onlineFallbackTriedPlatforms`**
- [ ] **Step 4: `OnlineAlbum` 模型增加 artist/songCount/time/desc；删除 `groupOnlineAlbums`/`albumIdentity`/`isExactAlbumSearch`/`matchesSongMetadata`/`sameOnlineText`/`sameExactOnlineText`/`normalizedOnlineText`/`onlineFallbackPlatforms`/`exactOnlineFallbackCandidates`**

### Task 4: UI 接线

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

- [ ] **Step 1: 专辑 Tab**：`onlineAlbumsSearching` 进度行（live region）；`OnlineAlbumRow` 显示专辑名/歌手/歌曲数/封面；`onDownloadAlbum` 保持。
- [ ] **Step 2: 详情页**：`OnlineAlbumDetailScreen` 接收 `detail: OnlineAlbumDetail`；加载中显示进度、失败显示中文错误 + "重试"按钮（≥48dp、live region）；成功后显示曲目列表与"播放全部"。
- [ ] **Step 3: MusicApp**：打开详情调 `viewModel.openOnlineAlbum(album)`；返回/重试/关闭调对应方法；切到专辑 Tab 且关键词非空时调 `viewModel.searchOnlineAlbums()`；下载按钮权限逻辑保持不变。
- [ ] **Step 4: 无障碍自审**：专辑/详情页 paneTitle、错误 live region、48dp 目标、中文语义名。

### Task 5: 测试清理与全量验证

**Files:**
- Rewrite: `android/app/src/test/kotlin/com/example/local_music_player/OnlineFallbackTest.kt`
- Rewrite: `android/app/src/test/kotlin/com/example/local_music_player/OnlineAlbumTest.kt`

- [ ] **Step 1: 删除跨平台/凑专辑用例；`OnlineAlbumTest` 只保留 `OnlineViewMode` 顺序等与实现无关的断言或改测专辑模型字段**
- [ ] **Step 2: 跑 `rtk cmd /c "gradlew.bat :app:testDebugUnitTest"` 全绿**
- [ ] **Step 3: 跑 `rtk cmd /c "gradlew.bat :app:assembleRelease"` 构建通过**
- [ ] **Step 4: `rtk git diff --check` 无空白错误；`git status --short` 检查无密钥泄漏**
