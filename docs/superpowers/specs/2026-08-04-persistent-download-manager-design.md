# Persistent Download Manager Design

**Date:** 2026-08-04

## Goal

Replace the ViewModel-bound online music download path with a durable, user-controlled download system. User-started downloads continue after the app leaves the foreground, recover after service or process recreation, support pause, resume, and HTTP Range continuation, and publish completed audio to `Music/汪汪播放器`.

The primary navigation destination changes from Settings to Mine. Mine is redesigned from scratch and contains only Download manager and Settings. Source management is removed from the user flow; playlists remain in the player flow.

## Scope

- Persist online music download tasks and their completed-history records in Room.
- Run active tasks in a dedicated `dataSync` foreground service, independent of playback.
- Keep at most two downloads active at once.
- Resume using a fresh resolved URL and HTTP Range only when the server confirms a matching `206` response; otherwise restart the pending file.
- Write directly to a pending `MediaStore.Audio` item under `Music/汪汪播放器`, then publish it on success.
- Provide Mine, Download manager, and Settings navigation with accessible state, actions, and Back behavior.

Out of scope: device-reboot auto-start, a third-party HTTP client, deletion of completed music from Download manager, source management, and playlist management from Mine.

## Architecture

`NativeMusicViewModel` invokes a `DownloadRepository` and observes its task flow. It no longer opens a URL or writes downloaded bytes itself.

`DownloadRepository` owns Room operations and is the single public interface for enqueue, pause, resume, cancel, retry, remove-completed-record, and observation. Each persisted task contains the original online track identity and selected quality needed to resolve a new temporary URL, the pending MediaStore URI, byte counters, HTTP validator data when available, error text, and lifecycle state.

`DownloadService` is a separate foreground service declared with the `dataSync` type. It reloads unfinished tasks, starts at most two workers, exposes one progress notification, and uses `START_STICKY` recovery. It must not share the existing media-playback service or notification channel.

The service uses the platform `HttpURLConnection` API with buffered stream copying. This is sufficient for the bounded audio-download workload; Room is the sole added dependency. No third-party HTTP client is introduced unless measured compatibility or performance evidence requires it.

## Task Lifecycle

1. A user explicitly selects download from online music. The repository creates a queued task, creates a pending MediaStore item in `Music/汪汪播放器`, and persists its URI. If either side fails, it compensates by deleting the created record or pending item.
2. The service resolves a fresh media URL, starts the foreground notification, and marks the task downloading.
3. Progress is persisted at a bounded interval, not on every buffer write. The UI observes the database state rather than polling the service.
4. Pause keeps the pending item and byte count. Resume re-resolves the URL, issues a Range request from the stored offset, and appends only after a matching `206` response. A `200` or mismatched response deletes and recreates the pending item before restarting.
5. Completion clears `IS_PENDING`, records the completed task, and refreshes the media library.
6. Cancel removes the pending MediaStore item and task. Removing a completed record deletes only the Room record, never the published music file.

Transient network failures enter a waiting-for-network state with bounded backoff and resume automatically. Non-recoverable failures retain their task and a visible error so the user can retry manually. Storage-full tasks retain their partial data until the user retries after making room.

An Android force-stop cannot be bypassed. Ordinary app exit, backgrounding, and service/process recreation are covered by the foreground service and persisted task state. Device reboot does not automatically restart downloads.

## UI And Accessibility

Bottom navigation contains `本地音乐`, `在线流媒体`, and `我的`.

Mine is a plain two-row Material list with no inherited cards or legacy sections:

- `下载管理`, with an activity summary such as active-task count or `暂无任务`.
- `设置`, with the subtitle `外观、播放、更新和关于`.

Download manager and Settings are child pages with an AppBar Back action. Download manager groups stable task rows into downloading, paused or failed, and completed states. Running tasks show a visible progress indicator and explicit pause and cancel commands. Failed tasks provide retry. Completed tasks provide remove-record only.

Rows, icon actions, and navigation use concise Simplified Chinese semantics; icon controls have names/tooltips and a minimum 48dp target. Task state is exposed as text and semantic state, rather than color alone. Progress changes do not repeatedly announce through TalkBack; user-triggered pauses, resumes, completion, and errors provide visible and accessible feedback.

System Back returns from Download manager or Settings to Mine. Return focus is restored to the originating Mine row, and navigation focus alone never starts a download or other I/O.

## Permissions And Failure Feedback

Add the foreground `dataSync` service declaration and required service permission. Request notification permission when the user first starts a download so foreground progress is visible; the task remains under explicit user control.

Visible task messages cover waiting for network, restarting because Range is unavailable, storage full, retryable failure, completion, pause, and cancellation. Repeated action controls are disabled while their state transition is in flight.

## Verification

Unit tests cover task state transitions, task-record deletion policy, Range response policy, and bounded concurrency selection.

Device smoke tests cover download start, pause/resume, network loss and recovery, Range-unavailable restart, app background and service recreation, cancellation cleanup, completed-file discovery in the library, record removal without file deletion, notification behavior, TalkBack, keyboard/D-pad, and large-font layout.

Before completion, run `rtk git diff --check`, the Release build from `D:\musicplayer\android`, and the relevant device smoke checklist. Keep existing playback, casting, Bluetooth, import, and update flows intact.
