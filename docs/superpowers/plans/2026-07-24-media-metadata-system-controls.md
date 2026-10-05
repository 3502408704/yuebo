# 媒体信息与系统控制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 显示歌曲格式和大小，并让 Android 系统媒体组件与应用、DLNA 投送共用同一组播放命令。

**Architecture:** `PlaybackController` 移到 application 层，成为队列、播放状态、位置、音量和本机/投送目标的唯一拥有者。`PlayerAudioHandler` 将 Android 系统媒体命令委托给它，`HomeScreen` 只维护当前目录并订阅控制器状态。

**Tech Stack:** Flutter/Dart、`audio_service`、Android MediaStore、BASS、DLNA AVTransport、Flutter Material/Semantics。

## Global Constraints

- 不增加依赖；复用已安装的 `audio_service`。
- 不新增、修改或运行自动化测试；仅执行静态分析、APK 构建和用户人工验收。
- 系统媒体命令与应用内命令必须使用同一播放控制器；投送失败不自动回退本机播放。
- 目录导航与文件夹选择不触发播放或网络操作；所有名称遵守中文无障碍语义。

---

### Task 1: 丰富媒体行信息并修正系统返回/文件夹语义

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/LibraryBridge.kt`
- Modify: `lib/domain/models/track.dart`
- Modify: `lib/infrastructure/media_store_library.dart`
- Modify: `lib/presentation/home_screen.dart`

**Interfaces:**
- Produces: `Track.sizeBytes`，始终为非负字节数。
- Produces: `formatTrackDetails(Track)`，返回“艺术家 · 格式 · 文件大小”。

- [ ] 查询 `MediaStore.MediaColumns.SIZE`，把 `sizeBytes` 传给 Dart；缺失或负值映射为 `0`。

```kotlin
MediaStore.MediaColumns.SIZE,
"sizeBytes" to it.getLong(sizeIndex),
```

```dart
final int sizeBytes;

String formatTrackDetails(Track track) =>
    '${track.artist} · ${track.mimeType.replaceFirst('audio/', '').toUpperCase()} · ${formatFileSize(track.sizeBytes)}';
```

- [ ] 在歌曲行显示该详情，语义名称包含详情；文件夹 `Semantics.label` 改为 `'$folder，文件夹'`。
- [ ] 以 `PopScope` 包裹媒体库：`_currentPath.isNotEmpty` 时 `canPop: false`，`onPopInvokedWithResult` 调用 `_goUp()`；根目录允许系统返回。
- [ ] 人工检查：元数据正确、文件夹先报名称、Android 返回手势仅回到上级目录。
- [ ] Commit: `feat: show media details and navigate folders with back`

### Task 2: 建立应用级播放控制器

**Files:**
- Create: `lib/application/playback_controller.dart`
- Modify: `lib/app.dart`
- Modify: `lib/presentation/home_screen.dart`
- Modify: `lib/presentation/now_playing_screen.dart`

**Interfaces:**
- Produces: `PlaybackController` 和不可变 `PlaybackSnapshot`。
- Consumes: `BassPlayer`、可选 `VolumePlayer`、`DlnaCastController`。

- [ ] 定义包含 `queue`、`queueIndex`、`track`、`position`、`isPlaying`、`castDevice`、`volume`、`supportsVolume` 的 `PlaybackSnapshot`；用 `ValueNotifier<PlaybackSnapshot>` 发布变化。

```dart
class PlaybackController extends ValueNotifier<PlaybackSnapshot> {
  PlaybackController({required BassPlayer bass, required DlnaCastController castController});
  Future<void> playFromQueue(List<Track> queue, int index);
  Future<void> pauseOrResume();
  Future<void> previous();
  Future<void> next();
  Future<void> seek(Duration position);
  Future<void> startCasting(DlnaDevice device);
  Future<void> returnToLocal();
}
```

- [ ] 将当前 `HomeScreen` 的队列、轮询、本机/DLNA 命令分派移入控制器；投送时 `playFromQueue` 调用 `replaceActiveTrack`，本机时调用 BASS。控制器错误通过 `ValueNotifier<Object?> error` 发布，且轮询失败停止轮询。
- [ ] `HomeScreen` 接收控制器而非各播放回调，仍仅拥有 `_tracks`、`_currentPath`、加载/权限 UI；以 `ValueListenableBuilder` 绑定迷你播放器与完整播放器。
- [ ] 人工检查：本机和投送的播放/暂停、切歌、定位、音量、返回本机播放均保持当前行为。
- [ ] Commit: `refactor: centralize playback state`

### Task 3: 发布 Android 系统媒体会话并路由命令

**Files:**
- Create: `lib/application/player_audio_handler.dart`
- Modify: `lib/main.dart`
- Modify: `lib/app.dart`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `PlayerAudioHandler extends BaseAudioHandler with SeekHandler`。
- Consumes: `PlaybackController` 的状态与明确命令。

- [ ] 在 `main()` 的 `runApp` 前初始化 `AudioService`；配置通知栏通道并把 handler 传入应用根组件。

```dart
final handler = await AudioService.init(
  builder: () => PlayerAudioHandler(controller),
  config: const AudioServiceConfig(
    androidNotificationChannelId: 'local_music_player.playback',
    androidNotificationChannelName: '本地音乐播放',
  ),
);
```

- [ ] handler 监听 `PlaybackController.state`，发布 `MediaItem(id: track.uri, title: track.title, artist: track.artist, album: targetLabel, duration: track.duration)` 和 `PlaybackState`。控制集合为上一首、播放/暂停、下一首；`MediaAction.seek` 仅在存在曲目时开放。
- [ ] 覆盖 `play`、`pause`、`skipToPrevious`、`skipToNext`、`seek`，都委托给控制器，不直接调用 BASS 或 DLNA；投送时由控制器自动路由。
- [ ] 按 `audio_service` Android 清单要求加入 `WAKE_LOCK`、`AudioService` service 和 `MediaButtonReceiver`，保留已有前台媒体服务权限。
- [ ] 人工检查：播放后通知栏/锁屏显示歌曲；播放/暂停、上一首、下一首和拖动进度在本机、投送设备两种目标下均执行正确命令。
- [ ] Commit: `feat: add Android system media controls`

### Task 4: 静态构建与人工验收记录

**Files:**
- Modify: `docs/release/first-release-checklist.md`

- [ ] 追加格式/大小、文件夹朗读、返回手势、通知栏/锁屏/耳机本机与投送控制的未勾选人工验收项。
- [ ] 仅运行以下命令，不运行 `flutter test`：

```powershell
rtk cmd /c "D:\flutter-sdk\bin\dart.bat format lib"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat analyze"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat build apk --debug"
```

- [ ] Commit: `docs: add system controls acceptance checks`

## Plan Self-Review

- Task 1 覆盖格式、大小、返回手势和文件夹朗读；Task 2 确保页面与系统共享播放状态；Task 3 覆盖通知栏、锁屏和耳机到本机/DLNA 的完整命令路由；Task 4 覆盖用户要求的人工验收和无自动化测试限制。
- `PlaybackController` 是唯一的命令分派点；`PlayerAudioHandler` 只发布状态并调用它，不持有 BASS 或 DLNA 实现。
