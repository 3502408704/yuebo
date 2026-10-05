# Persistent Download Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace direct ViewModel downloads with durable Room-backed foreground download tasks and expose them through the redesigned Mine flow.

**Architecture:** `DownloadRepository` owns Room persistence and MediaStore lifecycle. `DownloadService` owns bounded HTTP stream execution and foreground notification. `NativeMusicViewModel` delegates commands and projects task state to Compose; it no longer transfers online-media bytes itself.

**Tech Stack:** Kotlin, Coroutines, Room 2.7.2, Android Foreground Service (`dataSync`), MediaStore, HttpURLConnection, Jetpack Compose.

## Global Constraints

- Work in `D:\本地音乐播放器`; run Android Gradle only from `D:\musicplayer\android`.
- Prefix every shell command with `rtk`; edit files only with `apply_patch`.
- Do not create a worktree, commit, delete unrelated files, or alter existing dirty changes.
- Add only Room dependencies; use the platform HTTP client and MediaStore directly.
- Downloads originate only from an explicit visible user action, save to `Music/汪汪播放器`, and use at most two concurrent transfers.
- UI copy and semantics are Simplified Chinese. Preserve 48dp targets, visible status feedback, TalkBack semantics, and one-level Back behavior.
- A completed-history removal deletes only the Room row, never the completed audio file.
- Final verification requires `rtk git diff --check`, `:app:assembleRelease`, and the download/UI device smoke flow.

---

### Task 1: Add Room and define testable task policy

**Files:**
- Modify: `android/settings.gradle.kts`
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/DownloadTask.kt`
- Create: `android/app/src/test/kotlin/com/example/local_music_player/DownloadTaskTest.kt`

**Interfaces:**
- Produces `DownloadTaskStatus`, `DownloadTaskSnapshot`, `RangeResumeDecision`, `rangeResumeDecision`, and `nextRunnableTaskIds` for the database, service, ViewModel, and UI.

- [ ] **Step 1: Add the failing policy tests**

```kotlin
class DownloadTaskTest {
    @Test fun rangeResponse_appends_only_for_partial_content_at_saved_offset() {
        assertEquals(RangeResumeDecision.Append, rangeResumeDecision(512L, 206, "bytes 512-1023/1024"))
        assertEquals(RangeResumeDecision.Restart, rangeResumeDecision(512L, 200, null))
    }

    @Test fun scheduler_starts_only_two_queued_tasks() {
        val tasks = listOf("a", "b", "c").map { DownloadTaskSnapshot(id = it, status = DownloadTaskStatus.QUEUED) }
        assertEquals(listOf("a", "b"), nextRunnableTaskIds(tasks, activeCount = 0))
    }

    @Test fun completed_record_removal_never_requests_media_deletion() {
        assertFalse(shouldDeletePendingMedia(DownloadTaskAction.RemoveCompletedRecord))
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the policy types do not exist**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest"` from `D:\musicplayer\android`.

Expected: compilation failure referencing `RangeResumeDecision` or `DownloadTaskSnapshot`.

- [ ] **Step 3: Add Room build support and the minimal pure policy model**

Add the Kotlin Symbol Processing plugin `com.google.devtools.ksp` with version `2.3.10` to `android/settings.gradle.kts`, apply it in the app module, and add these dependencies to the app module. AGP 9 uses built-in Kotlin, so KAPT is not compatible:

```kotlin
implementation("androidx.room:room-runtime:2.7.2")
implementation("androidx.room:room-ktx:2.7.2")
ksp("androidx.room:room-compiler:2.7.2")
```

Create `DownloadTask.kt` with these exact states and policy contracts:

```kotlin
enum class DownloadTaskStatus { QUEUED, DOWNLOADING, WAITING_NETWORK, PAUSED, FAILED, COMPLETED }
enum class DownloadTaskAction { Cancel, RemoveCompletedRecord }
enum class RangeResumeDecision { Append, Restart }

data class DownloadTaskSnapshot(
    val id: String,
    val status: DownloadTaskStatus,
    val downloadedBytes: Long = 0,
)

fun rangeResumeDecision(offset: Long, responseCode: Int, contentRange: String?): RangeResumeDecision =
    if (offset > 0 && responseCode == 206 && contentRange?.startsWith("bytes $offset-") == true) {
        RangeResumeDecision.Append
    } else {
        RangeResumeDecision.Restart
    }

fun nextRunnableTaskIds(tasks: List<DownloadTaskSnapshot>, activeCount: Int, limit: Int = 2): List<String> =
    tasks.asSequence().filter { it.status == DownloadTaskStatus.QUEUED }.take((limit - activeCount).coerceAtLeast(0)).map { it.id }.toList()

fun shouldDeletePendingMedia(action: DownloadTaskAction): Boolean = action == DownloadTaskAction.Cancel
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest"` from `D:\musicplayer\android`.

Expected: `BUILD SUCCESSFUL` and three passing tests.

### Task 2: Persist download tasks with Room and a MediaStore-aware repository

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/DownloadTaskDatabase.kt`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/DownloadRepository.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApplication.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/DownloadTaskTest.kt`

**Interfaces:**
- Consumes `DownloadTaskStatus` and `OnlineTrack`.
- Produces `DownloadRepository.tasks: Flow<List<DownloadTask>>`, `enqueue`, `pause`, `resume`, `cancel`, `retry`, `removeCompletedRecord`, `markProgress`, `markCompleted`, and `markFailure`.

- [ ] **Step 1: Extend the failing tests for task mapping and terminal cleanup policy**

```kotlin
@Test fun active_task_state_projects_to_online_key() {
    val task = DownloadTaskSnapshot(id = "id", status = DownloadTaskStatus.DOWNLOADING)
    assertTrue(task.status in setOf(DownloadTaskStatus.QUEUED, DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.WAITING_NETWORK))
}

@Test fun only_cancel_deletes_the_pending_media_item() {
    assertTrue(shouldDeletePendingMedia(DownloadTaskAction.Cancel))
    assertFalse(shouldDeletePendingMedia(DownloadTaskAction.RemoveCompletedRecord))
}
```

- [ ] **Step 2: Run the focused policy test and verify the new assertions fail before the cleanup rule is adjusted**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest"` from `D:\musicplayer\android`.

Expected: a failing cleanup-policy assertion until cancellation is represented separately from pause and failure.

- [ ] **Step 3: Implement Room entities, DAO, database, and repository compensation**

Create one `download_tasks` table with an entity that stores these columns: task ID, platform, query, sequence, platform ID, title, artist, album, artwork URL, requested quality API value, MIME type, extension, pending MediaStore URI, downloaded bytes, total bytes, ETag, status, error text, created time, and completed time. Store enum values as strings; do not add a Room type converter.

Define a DAO with `observeAll(): Flow<List<DownloadTaskEntity>>`, `upsert`, `findById`, `findByStatuses`, and `deleteById`. Order non-completed work by creation time and completed work by completion time descending.

Make `DownloadRepository` a concrete class backed by `ContentResolver` and the DAO. `enqueue(track, qualityApiValue)` must insert a queued database record, insert an `IS_PENDING=1` `MediaStore.Audio` item at `Music/汪汪播放器`, then update the record with the URI. If MediaStore insertion fails, delete the new database row. If URI persistence fails, delete both the database row and pending item. `cancel` deletes the pending item and row; `removeCompletedRecord` only deletes the row. `tasks` maps entities to a UI-safe `DownloadTask` domain value.

Expose `val downloads by lazy { DownloadRepository(contentResolver, DownloadTaskDatabase.create(this)) }` from `MusicApplication`.

- [ ] **Step 4: Re-run the focused policy test and compile the app module**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest :app:compileDebugKotlin"` from `D:\musicplayer\android`.

Expected: `BUILD SUCCESSFUL`.

### Task 3: Extract online resolution and execute durable downloads in a foreground service

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/DownloadService.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApplication.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/DownloadTaskTest.kt`

**Interfaces:**
- Consumes `DownloadRepository`, persisted `DownloadTask`, `OnlineTrack`, and requested quality API values.
- Produces `OnlineTrackResolver.resolve(track, qualityApiValue)`, `DownloadService.start(context)`, and a service that advances task status without ViewModel lifetime dependence.

- [ ] **Step 1: Add failing pure tests for retry and bounded scheduling**

```kotlin
@Test fun waiting_network_task_is_not_counted_as_an_active_transfer() {
    val tasks = listOf(
        DownloadTaskSnapshot("waiting", DownloadTaskStatus.WAITING_NETWORK),
        DownloadTaskSnapshot("queued", DownloadTaskStatus.QUEUED),
    )
    assertEquals(listOf("queued"), nextRunnableTaskIds(tasks, activeCount = 0))
}
```

- [ ] **Step 2: Run the focused test and verify the current scheduler behavior before service work**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest"` from `D:\musicplayer\android`.

Expected: passing policy test; the service class is not yet present.

- [ ] **Step 3: Move the shared online resolution switch out of the ViewModel**

Move the existing `resolveOnline(track, quality)` platform switch and the platform-specific quality conversion into concrete `OnlineTrackResolver`. It must construct the existing Netease, QQ, Kuwo, Kugou, and Apple catalogs once and accept a persisted quality API value. Replace ViewModel calls to the private resolver with `(application as MusicApplication).onlineResolver.resolve(...)`; do not duplicate the platform switch in the service.

- [ ] **Step 4: Implement `DownloadService` with direct buffered MediaStore streaming**

Declare `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`, then declare `DownloadService` with `android:foregroundServiceType="dataSync"` in the manifest. The service must use a `SupervisorJob` plus `Dispatchers.IO`, return `START_STICKY`, and create a dedicated low-importance `downloads` notification channel.

Use this control flow for each runner:

```kotlin
val resolved = application.downloads.resolver.resolve(task.toOnlineTrack(), task.qualityApiValue)
val connection = (URL(resolved.playUrl).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15_000
    readTimeout = 30_000
    if (task.downloadedBytes > 0) setRequestProperty("Range", "bytes=${task.downloadedBytes}-")
}
val decision = rangeResumeDecision(task.downloadedBytes, connection.responseCode, connection.getHeaderField("Content-Range"))
```

For `Append`, use `resolver.openOutputStream(uri, "wa")`; for `Restart`, delete the stale pending URI through the repository, create a new pending item, and restart the request without a Range header. Copy with one `ByteArray(64 * 1024)`, check pause/cancel between reads, persist progress no more than once per second, and update the foreground notification from the aggregate active tasks.

`IOException` marks the task `WAITING_NETWORK`, schedules bounded exponential retry, and lets other queued work use the free slot. Invalid resolved links, unknown output URI, and storage errors mark `FAILED` with Chinese visible error text. Completion clears `IS_PENDING`, marks `COMPLETED`, and stops foreground mode when no transfer remains.

- [ ] **Step 5: Re-run policy tests and compile the service**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest :app:compileDebugKotlin"` from `D:\musicplayer\android`.

Expected: `BUILD SUCCESSFUL`.

### Task 4: Delegate online-download commands and state from NativeMusicViewModel

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/test/kotlin/com/example/local_music_player/DownloadTaskTest.kt`

**Interfaces:**
- Consumes `MusicApplication.downloads`, `DownloadService.start`, and `DownloadTask`.
- Produces `MusicUiState.downloadTasks`, existing `downloadingOnlineKeys` projection, and ViewModel commands `pauseDownload`, `resumeDownload`, `cancelDownload`, `retryDownload`, and `removeDownloadRecord`.

- [ ] **Step 1: Add the failing projection test**

```kotlin
@Test fun active_downloads_project_to_online_row_busy_keys() {
    val tasks = listOf(
        DownloadTask(id = "a", onlineKey = "qq:q:1:x", status = DownloadTaskStatus.DOWNLOADING),
        DownloadTask(id = "b", onlineKey = "qq:q:2:y", status = DownloadTaskStatus.COMPLETED),
    )
    assertEquals(setOf("qq:q:1:x"), activeOnlineDownloadKeys(tasks))
}
```

- [ ] **Step 2: Run the focused test and verify it fails before the projection helper exists**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest"` from `D:\musicplayer\android`.

Expected: compilation failure for `activeOnlineDownloadKeys`.

- [ ] **Step 3: Implement the minimal ViewModel delegation**

Add `downloadTasks: List<DownloadTask> = emptyList()` to `MusicUiState` and a pure `activeOnlineDownloadKeys(tasks)` helper. In ViewModel initialization, collect `application.downloads.tasks` in `viewModelScope` and update both fields from the same database snapshot.

Replace `legacyDownloadNetease` and `downloadOnlineTrack` with `downloadOnline`, which chooses the existing per-platform download quality, calls `downloads.enqueue(track, quality.apiValue)`, and starts `DownloadService`. Change album download from sequential byte-copying to enqueueing each track in source order. Add the five task-command methods that delegate to the repository and restart the service for resume or retry. Keep the existing online row disabled state driven by `downloadingOnlineKeys`.

- [ ] **Step 4: Run the projection test and compile the ViewModel**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest :app:compileDebugKotlin"` from `D:\musicplayer\android`.

Expected: `BUILD SUCCESSFUL`.

### Task 5: Replace Mine navigation and add accessible download management UI

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/DownloadTaskTest.kt`

**Interfaces:**
- Consumes `MusicUiState.downloadTasks` and the five ViewModel task commands.
- Produces bottom navigation `我的`, `MineSection.Home/Downloads/Settings`, `DownloadManagerScreen`, and notification-permission request on first user-started download.

- [ ] **Step 1: Add failing pure UI summary tests**

```kotlin
@Test fun mine_summary_uses_active_count_or_empty_copy() {
    assertEquals("暂无任务", mineDownloadSummary(emptyList()))
    assertEquals("2 项进行中", mineDownloadSummary(listOf(
        DownloadTaskSnapshot("a", DownloadTaskStatus.DOWNLOADING),
        DownloadTaskSnapshot("b", DownloadTaskStatus.QUEUED),
    )))
}
```

- [ ] **Step 2: Run the focused test and verify it fails before the summary helper exists**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest"` from `D:\musicplayer\android`.

Expected: compilation failure for `mineDownloadSummary`.

- [ ] **Step 3: Implement the new Mine hierarchy and download manager**

Remove `MainTab.Settings`, retain `MainTab.Mine`, and make the bottom bar contain only `本地音乐`, `在线流媒体`, and `我的`. Reduce `MineSection` to `Home`, `Downloads`, and `Settings`; remove the source and playlist routes from `MineScreen`.

Render Mine home as a plain `LazyColumn` with exactly two `ListItem`-style parent rows, not cards: `下载管理` with `mineDownloadSummary`, and `设置` with `外观、播放、更新和关于`. Use material Download and Settings icons as decorative children and give the parent row one concise semantic name and `onClick(label = "进入")`.

Create `DownloadManagerScreen` with an AppBar Back action and stable `LazyColumn` keys. Group rows by downloading, paused-or-failed, and completed. A running row contains visible bytes/percent, a `LinearProgressIndicator`, and 48dp pause/cancel icon buttons with Chinese tooltip and semantic names. Paused rows expose resume/cancel; failed rows expose retry/cancel plus visible error; completed rows expose only remove-record. Do not announce progress on each recomposition; announce explicit command outcomes and terminal state changes.

Wrap the existing Settings content in a child page with an AppBar Back action. Handle system Back for Downloads and Settings by returning to Mine and restore focus to the triggering row. Request `POST_NOTIFICATIONS` when a user starts a download, without treating permission focus as a download action.

- [ ] **Step 4: Run focused tests and compile Compose UI**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests com.example.local_music_player.DownloadTaskTest :app:compileDebugKotlin"` from `D:\musicplayer\android`.

Expected: `BUILD SUCCESSFUL`.

### Task 6: Remove obsolete direct-download behavior and perform verification

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- Modify: `docs/superpowers/specs/2026-08-04-persistent-download-manager-design.md` only if implementation exposes an actual design mismatch
- Test: `android/app/src/test/kotlin/com/example/local_music_player/DownloadTaskTest.kt`

**Interfaces:**
- Consumes all previous task interfaces.
- Produces a single online-download path through `DownloadRepository` and a verified release build.

- [ ] **Step 1: Search for the retired ViewModel byte-copy path**

Run: `rtk rg -n "legacyDownloadNetease|downloadOnlineTrack|openOutputStream\(destination\)" android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`.

Expected: identify the obsolete direct online-transfer functions before removal.

- [ ] **Step 2: Remove only obsolete online download code and retain update downloading**

Delete `legacyDownloadNetease` and the direct online `downloadOnlineTrack` implementation once all callers use the repository. Do not alter `UpdateChecker.downloadAndInstall`, import copying, playback, casting, Bluetooth, or existing media deletion.

- [ ] **Step 3: Run unit tests, static whitespace check, and debug compilation**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest"` from `D:\musicplayer\android`.

Run: `rtk git diff --check` from `D:\本地音乐播放器`.

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"` from `D:\musicplayer\android`.

Expected: all tests pass, no whitespace errors, and `BUILD SUCCESSFUL`.

- [ ] **Step 4: Build the release APK**

Run: `rtk cmd /c "gradlew.bat :app:assembleRelease"` from `D:\musicplayer\android`.

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run device smoke checks and record limitations**

On an Android device or emulator, start a download, background the app, pause/resume, disable and restore network, test a server without Range support, cancel a pending task, remove a completed record, and confirm completed audio appears in the library. Navigate Mine -> Download manager -> Mine and Mine -> Settings -> Mine with TalkBack, D-pad/keyboard, and large font enabled. Confirm no application FATAL or ANR in logcat. Note that Android force-stop and device reboot do not auto-restart downloads by design.
