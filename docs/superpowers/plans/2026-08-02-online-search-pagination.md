# 在线搜索结果自动扩展实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让在线搜索结果在用户滚动到底部时按妖狐 `g` 数量自动扩展并去重。

**Architecture:** Catalog 接受累计返回数量；`NativeMusicViewModel` 串行执行首次搜索和加载更多，维护结果、请求数量和停止条件；`OnlineScreen` 只监听列表滚动并触发 ViewModel，不直接访问网络。

**Tech Stack:** Kotlin、协程、Jetpack Compose `LazyListState`、现有 kotlin.test。

## Global Constraints

- 所有 shell 命令以 `rtk` 开头；Android 构建从 `D:\musicplayer\android` 执行。
- 文件修改使用 `apply_patch`；不新增依赖、不创建 worktree、不自动提交。
- UI 文本和无障碍语义使用简体中文；可点击目标至少 48dp；加载状态通过 live region 感知。
- 搜索失败不得清空已有结果，不得自动播放或推进播放队列。

---

### Task 1: 建立分页数量与合并策略测试

**Files:**
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/YaohuOtherMusicApiTest.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/OnlineSearchPaginationTest.kt`

**Interfaces:**
- Produces `ONLINE_SEARCH_PAGE_SIZE`, `nextOnlineSearchLimit`, `mergeOnlineSearchTracks` and `shouldContinueOnlineSearch` contracts for the ViewModel.

- [ ] **Step 1: Write failing tests**

测试首次页大小、累计数量、达到上限、按 key 去重，以及四个 Catalog 将 `g` 传给 API。

- [ ] **Step 2: Run focused tests and observe expected unresolved references**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlineSearchPaginationTest --tests com.example.local_music_player.YaohuOtherMusicApiTest"
```

- [ ] **Step 3: Implement only the pure contracts and Catalog limit signatures**

新增固定页大小 30、最大累计数量 150；Catalog 默认值改为 30，网易云搜索请求加入 `g`。

- [ ] **Step 4: Run focused tests and verify green**

使用同一命令，确认请求参数和合并策略通过。

---

### Task 2: ViewModel 串行加载更多

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/OnlineSearchPaginationTest.kt`

**Interfaces:**
- Adds `MusicUiState.onlineLoadingMore` and `MusicUiState.onlineHasMore`.
- Adds public `NativeMusicViewModel.loadMoreOnline()`.

- [ ] **Step 1: Add failing state-policy tests**

覆盖响应不足请求数量、累计响应无新增 key 时停止，以及加载期间不允许重复加载。

- [ ] **Step 2: Run focused tests and verify red**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlineSearchPaginationTest"
```

- [ ] **Step 3: Implement the minimal request flow**

`searchOnline` 重置累计数量并请求 30；`loadMoreOnline` 仅在当前关键词、平台、结果存在且未加载时请求下一个数量。成功后按 key 合并，响应不足或无新增时关闭 `onlineHasMore`；失败只清除加载标志并保留结果。

- [ ] **Step 4: Run focused and existing online tests**

```powershell
rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.OnlineSearchPaginationTest --tests com.example.local_music_player.YaohuOtherMusicApiTest --tests com.example.local_music_player.OnlinePlatformStateTest"
```

---

### Task 3: 在线列表触底触发与无障碍状态

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- `OnlineScreen` receives `onLoadMore: () -> Unit`; it never performs network work itself.

- [ ] **Step 1: Implement `LazyListState` bottom observation**

使用 `snapshotFlow` 观察可见末项，距离末尾 5 项以内调用 `onLoadMore`；以 `onlineLoadingMore` 和 `onlineHasMore` 防重复。

- [ ] **Step 2: Add loading live-region row and wire callback**

加载更多时显示 48dp 以上的进度行，TalkBack 可感知“正在加载更多”；在 `MusicApp` 传入 `viewModel::loadMoreOnline`。

- [ ] **Step 3: Compile and run UI-adjacent unit tests**

```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
```

---

### Task 4: Full verification and device smoke

**Files:**
- Modify: none beyond Tasks 1-3.

- [ ] **Step 1: Run repository gates**

```powershell
rtk git diff --check
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
rtk cmd /c "gradlew.bat :app:assembleRelease"
```

- [ ] **Step 2: Install and launch release APK**

```powershell
rtk adb -s UGAA9P4XVSOR4LGU install -r D:\musicplayer\build\app\outputs\apk\release\app-release.apk
rtk adb -s UGAA9P4XVSOR4LGU shell am start -n com.example.local_music_player/.MainActivity
```

- [ ] **Step 3: Verify online search flow**

Use the UI tree to select 在线流媒体, search a common keyword, scroll to the last five rows, and confirm a second loading state followed by additional or terminal results. Check crash buffer is empty.
