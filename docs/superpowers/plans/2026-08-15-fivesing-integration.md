# 5sing（中国原创基地）伴奏源接入 Implementation Plan

> **For agentic workers:** 本仓库禁止自动提交/建 worktree；各任务以 `rtk` 命令验证，不用 git commit。任务用 checkbox（`- [ ]`）跟踪。

**Goal:** 把 5sing 接入「在线音乐」：搜索（默认伴奏、可切 全部/原创/翻唱/伴奏）、在线播放、下载；播放/下载地址走 5sing `getsongurl`（MD5 签名）。

**Architecture:** 新增 `FiveSingApi.kt`（HTTP + 签名 + 搜索/解析）。`MusicPlatform` 加 `FiveSing`；`OnlineTrackResolver` 加 FiveSing 分支（跳过 ShyMusic）；`NativeMusicViewModel` 增加 `fivesingFilter` 状态与搜索分支；`OnlineScreen` 增加 5sing 筛选行并隐藏专辑页签。

**Tech Stack:** Kotlin、`org.json`、`HttpURLConnection`、kotlinx.coroutines、现有 BASS 播放 / DownloadEngine / Compose Material3。

## Global Constraints

- 5sing 搜索基址 `http://search.5sing.kugou.com/home/json`；地址解析基址 `https://5sservice.kugou.com/song/getsongurl`；签名 secret `5uytoxQewcvIc1gn1PlNF0T2jbbOzRl5`（来自 5sing 公开 JS）。
- 所有用户可见文案、状态与语义使用简体中文；显式点击才发起搜索/解析/播放/下载。
- 不新增第三方依赖；所有 shell 命令以 `rtk` 开头；Android 构建从 `D:\musicplayer\android` 运行；文件修改用 `apply_patch`；不自动提交、不建 worktree。
- 完成前必须通过：`rtk git diff --check`、`:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleRelease`。

---

## Files and Responsibilities

- Create `android/app/src/main/kotlin/com/example/local_music_player/FiveSingApi.kt`：`FiveSingException`、`FiveSingHttp`、`FiveSingApiClient`、`FiveSingFilter`、`FiveSingCatalog`。
- Create `android/app/src/test/kotlin/com/example/local_music_player/FiveSingApiTest.kt`：解析/签名/选档/付费/编解码测试。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt`：`MusicPlatform.FiveSing` + `yaohuApiValue`/`onlineQualityFromApiValue` 分支。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/ShyMusicApi.kt`：`shyQualityFor` 补 FiveSing。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt`：`resolveShy` 跳过 FiveSing；`resolveViaYaohu` 加 FiveSing 分支。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`：`fivesingFilter` 状态、`setFiveSingFilter`、`fetchOnlineSearch`/`loadOnlineAlbums` 分支、`onlineQuality` 兼容。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`：5sing 筛选行、隐藏专辑页签、`preferredOnlineViewMode`。
- Modify `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`：`setFiveSingFilter` 接线。

---

### Task 1: FiveSingApi.kt（TDD）

**Files:**
- Create: `android/app/src/test/kotlin/com/example/local_music_player/FiveSingApiTest.kt`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/FiveSingApi.kt`

**Interfaces:**
- Consumes: `OnlineTrack`/`OnlineResolvedTrack`/`OnlineQuality`（来自 `YaohuOtherMusicApi.kt`）、`YaohuApiException` 风格。
- Produces: `FiveSingFilter`、`FiveSingCatalog.search(query: String, filter: FiveSingFilter, limit: Int): List<OnlineTrack>`、`FiveSingCatalog.resolve(track: OnlineTrack, quality: YaohuQuality): OnlineResolvedTrack`。

- [ ] **Step 1: 写失败测试** `FiveSingApiTest.kt`（stub `FiveSingHttp`，覆盖搜索映射/剥标签/空 list null/filter/翻页、签名固定向量、getsongurl 解析选档/wav-wma 排除/付费、platformId 编解码）。
- [ ] **Step 2: 运行测试确认失败**：`rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests \"*FiveSingApiTest*\""`（工作目录 `D:\musicplayer\android`）。
- [ ] **Step 3: 实现 `FiveSingApi.kt`**（见设计文档第 4.1 节；签名算法见第 3.2 节）。
- [ ] **Step 4: 运行测试确认通过**。

### Task 2: MusicPlatform + when 分支

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/ShyMusicApi.kt`

**Interfaces:**
- Consumes: `MusicPlatform`（枚举本身）。
- Produces: 编译期完整；`MusicPlatform.FiveSing("5sing")` 在所有 exhaustive `when` 中可编译。

- [ ] **Step 1**: `MusicPlatform` 加 `FiveSing("5sing")`；`yaohuApiValue`/`onlineQualityFromApiValue` 补 FiveSing（返回 `OnlineQuality.Standard` / null）；`shyQualityFor` 把 FiveSing 并入 OnlineQuality 分支。
- [ ] **Step 2**: 编译验证 `:app:compileDebugKotlin`。

### Task 3: OnlineTrackResolver 接入

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt`

**Interfaces:**
- Consumes: `FiveSingCatalog`（Task 1）、`MusicPlatform.FiveSing`（Task 2）。
- Produces: `resolve(track, quality)` / `resolveSkippingShy(track, quality)` 对 FiveSing 返回 `fiveSingCatalog.resolve(...)`。

- [ ] **Step 1**: `resolveShy` 开头 `if (track.platform == MusicPlatform.FiveSing) return null`；`resolveViaYaohu` 加 `MusicPlatform.FiveSing -> fiveSingCatalog.resolve(track, quality)`。
- [ ] **Step 2**: 单测 `ShySourceFallbackTest.kt` 不回归；编译通过。

### Task 4: ViewModel 状态与链路

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`

**Interfaces:**
- Consumes: `FiveSingFilter`/`FiveSingCatalog`、`MusicPlatform.FiveSing`。
- Produces: state 增加 `fivesingFilter: FiveSingFilter`；`setFiveSingFilter(filter: FiveSingFilter)`；`fetchOnlineSearch` 对 FiveSing 走 `fiveSingCatalog.search`；`loadOnlineAlbums` 对 FiveSing 返回 empty。

- [ ] **Step 1**: `MusicUiState` 加 `fivesingFilter`；`selectOnlinePlatform` 切 FiveSing 重置默认伴奏；`searchOnline`/`loadMoreOnline` 分支传递 filter。
- [ ] **Step 2**: `setFiveSingFilter` 实现（改 filter → 清结果 → 重搜）。
- [ ] **Step 3**: `fetchOnlineSearch`/`loadOnlineAlbums` 分支。
- [ ] **Step 4**: 编译 + 既有 `OnlinePlatformStateTest`/`OnlineSearchPaginationTest` 通过。

### Task 5: UI 接线

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes: state.fivesingFilter、`onSelectFilter(filter)` 回调。
- Produces: OnlineScreen 新增参数 `onSelectFilter: (FiveSingFilter) -> Unit`；5sing 筛选行（全部/原创/翻唱/伴奏）；5sing 隐藏「专辑」页签；`preferredOnlineViewMode(FiveSing)=Songs`。

- [ ] **Step 1**: `preferredOnlineViewMode` 加 FiveSing→Songs；`episodeMode` 判断不变。
- [ ] **Step 2**: OnlineScreen 加筛选行（仅 FiveSing 显示），回调 `onSelectFilter`；专辑页签对 FiveSing 隐藏。
- [ ] **Step 3**: MusicApp 传入 `onSelectFilter = viewModel::setFiveSingFilter`。
- [ ] **Step 4**: 编译通过。

### Task 6: 验证

- [ ] `rtk git diff --check` 无空白错误。
- [ ] `:app:testDebugUnitTest` 全量通过（含新增 FiveSingApiTest）。
- [ ] `:app:compileDebugKotlin` 通过。
- [ ] `:app:assembleRelease` 通过。
- [ ] 冒烟（若有真机）：在线页选 5sing → 默认伴奏 → 搜索 → 播放 → 下载。
