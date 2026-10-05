# 文件夹媒体库与设备控制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户按真实目录浏览本地音乐，在完整播放器中控制本机或已连接的 DLNA 设备，并在连接后默认将新选歌曲投送到该设备。

**Architecture:** 在现有 `Track` 数据中携带 MediaStore 的相对目录，在 `HomeScreen` 以路径即时构建当前目录。播放队列、当前曲目和播放目标维持在现有页面状态；完整播放器是单独的路由，复用同一组显式回调。`DlnaCastController` 持有活动会话，向 UI 暴露复用会话的播放与控制操作。

**Tech Stack:** Flutter/Dart、Android Kotlin MediaStore、BASS、`upnp_client` 的 AVTransport 与 RenderingControl、Flutter Material/Semantics。

## Global Constraints

- Android 构建、分析和 APK 构建只能在 `D:\musicplayer` 执行，所有 shell 命令以 `rtk` 开头。
- 不添加新依赖、数据库或后台媒体服务。
- 所有交互控件具备中文可访问名称、可预测焦点顺序和键盘/D-pad 操作；选择目录或设备不会触发播放或网络命令。
- 当前用户明确要求：不新增、不修改或运行自动化测试；验收仅通过人工设备测试完成。
- 设备不支持或实际拒绝的命令应禁用/保留可感知错误；绝不自动回退到手机播放。

---

## Planned File Structure

```text
android/app/src/main/kotlin/com/example/local_music_player/
  LibraryBridge.kt                    # 返回每首媒体的相对目录
  BassBridge.kt                       # 本机音量 MethodChannel 命令
lib/
  domain/models/track.dart            # 增加 folderPath
  domain/ports/{bass_player,dlna_renderer}.dart # 可选音量能力接口
  infrastructure/{media_store_library,bass_method_channel_player}.dart
  infrastructure/dlna/{dlna_renderer_client,dlna_cast_session}.dart
  application/dlna_cast_controller.dart
  presentation/{home_screen,player_controls,now_playing_screen}.dart
  app.dart
docs/release/first-release-checklist.md # 追加人工验收项
```

### Task 1: 从 MediaStore 提供目录路径并构建层级媒体库

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/LibraryBridge.kt`
- Modify: `lib/domain/models/track.dart`
- Modify: `lib/infrastructure/media_store_library.dart`
- Modify: `lib/presentation/home_screen.dart`

**Interfaces:**
- Produces: `Track.folderPath`，一个无前导/尾随 `/` 的相对目录；根目录使用空字符串。
- Produces: `HomeScreen` 内的 `_currentPath`，仅由用户进入/返回目录操作修改。

- [ ] **Step 1: 将 `RELATIVE_PATH` 纳入 Android 查询结果**

在 Kotlin projection 中加入 `MediaStore.MediaColumns.RELATIVE_PATH`，读取后以 `trim('/')` 规范化为 `folderPath`。空值使用空字符串；不得读取已弃用的绝对文件路径。

```kotlin
MediaStore.MediaColumns.RELATIVE_PATH,
// ...
"folderPath" to (it.getString(folderIndex) ?: "").trim('/'),
```

- [ ] **Step 2: 扩展 Dart 模型和映射**

```dart
class Track {
  const Track({
    required this.id,
    required this.uri,
    required this.title,
    required this.artist,
    required this.duration,
    required this.mimeType,
    this.folderPath = '',
  });

  final String folderPath;
}

// mapMediaRow
folderPath: (row['folderPath'] as String? ?? '').replaceAll(RegExp(r'^/+|/+$'), ''),
```

保留默认值，使现有调用方和早期设备的空目录媒体仍显示在根目录。

- [ ] **Step 3: 在 HomeScreen 即时列出当前目录**

维护 `_currentPath`。通过 `track.folderPath.split('/')` 得到直属子文件夹；当前目录歌曲条件为 `track.folderPath == _currentPath`。目录项在歌曲项之前并按不区分大小写名称排序。根目录外显示一个明确命名的“返回上级文件夹”按钮。

```dart
String childPath(String name) =>
    _currentPath.isEmpty ? name : '$_currentPath/$name';

void _openFolder(String path) => setState(() => _currentPath = path);

void _goUp() {
  final separator = _currentPath.lastIndexOf('/');
  setState(() => _currentPath = separator < 0 ? '' : _currentPath.substring(0, separator));
}
```

目录行使用 `ListTile` 与文件夹图标、明确 `Semantics` 标签；其 `onTap` 只调用 `_openFolder`。歌曲行仍是单独、可激活的播放控件。

- [ ] **Step 4: 人工检查目录浏览**

在实体 Android 设备授权媒体权限后，确认根目录、嵌套目录、根目录歌曲、目录内歌曲均正确显示；进入/返回目录不产生音频、不触发投送，且 TalkBack 与 D-pad 可以分别激活目录和歌曲。

- [ ] **Step 5: Commit**

```powershell
rtk git add android/app/src/main/kotlin/com/example/local_music_player/LibraryBridge.kt lib/domain/models/track.dart lib/infrastructure/media_store_library.dart lib/presentation/home_screen.dart
rtk git commit -m "feat: browse media by folder"
```

### Task 2: 补齐本机播放队列、音量和完整播放器控件

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/BassBridge.kt`
- Modify: `lib/domain/ports/bass_player.dart`
- Modify: `lib/infrastructure/bass_method_channel_player.dart`
- Modify: `lib/presentation/player_controls.dart`
- Create: `lib/presentation/now_playing_screen.dart`
- Modify: `lib/presentation/home_screen.dart`

**Interfaces:**
- Produces: `VolumePlayer.setVolume(double value)`，范围始终为 `0.0..1.0`；不改变现有 `BassPlayer`，以免无音量能力的调用方被迫实现它。
- Produces: `NowPlayingScreen`，接收当前曲目、位置、队列控制回调、音量能力与关闭操作。

- [ ] **Step 1: 在 BASS 桥接中增加音量命令**

`BassBridge` 接收 `volume`（0–1 的 double），夹紧后转换为 BASS 所需的 0–100 浮点值。Dart 端保持 0–1，不泄漏平台范围。

```kotlin
"volume" -> {
    val value = (call.argument<Double>("value") ?: 1.0).coerceIn(0.0, 1.0)
    result.success(BASS.BASS_ChannelSetAttribute(stream, BASS.BASS_ATTRIB_VOL, value * 100.0))
}
```

```dart
abstract interface class VolumePlayer {
  Future<void> setVolume(double value);
}
```

让 `MethodChannelBassPlayer implements BassPlayer, VolumePlayer`，并用 `value.clamp(0.0, 1.0)` 调用该命令。`HomeScreen` 接收可空 `VolumePlayer`；未提供时音量控件禁用且说明不可用。

- [ ] **Step 2: 将当前文件夹歌曲冻结为播放队列**

在点歌时用当前目录的歌曲列表创建 `_queue`、索引 `_queueIndex`。`_playQueueIndex` 负责本机 `bass.play`、更新当前曲目和将位置归零；`_previous` 与 `_next` 在首尾禁用，不环绕。浏览其他目录不会改变 `_queue`。

```dart
Future<void> _playQueueIndex(int index) async {
  final track = _queue[index];
  await widget.bass.play(track);
  if (!mounted) return;
  setState(() {
    _queueIndex = index;
    _currentTrack = track;
    _isPlaying = true;
    _position = Duration.zero;
  });
  _startPositionPolling();
}
```

- [ ] **Step 3: 创建完整播放器路由**

`NowPlayingScreen` 以 `Scaffold` 和语义化 `AppBar` 呈现曲名、艺术家、当前目标、时间文本、Slider、快退/快进 5 秒、上一首、播放/暂停、下一首和音量 Slider。全部使用独立 `IconButton`/`Slider`，将禁用状态和“不支持”描述传达给辅助技术。将现有 `PlayerControls` 保持为位置 Slider，新增可选的时间行而非另建自定义 Slider。

```dart
Navigator.of(context).push(
  MaterialPageRoute<void>(
    builder: (_) => NowPlayingScreen(
      track: _currentTrack!,
      position: _position,
      isPlaying: _isPlaying,
      canGoPrevious: _queueIndex > 0,
      canGoNext: _queueIndex + 1 < _queue.length,
      onPrevious: _previous,
      onTogglePlayback: _togglePlayback,
      onNext: _next,
      onSeek: _seek,
      onSetVolume: _setVolume,
    ),
  ),
);
```

底部迷你播放器整体可打开此路由，但播放/暂停保持为独立控件，避免合并语义节点。`_positionTimer` 的刷新不得移动焦点或触发播报。

- [ ] **Step 4: 人工检查本机完整播放器**

在实体设备中播放一首至少三分钟的歌，确认迷你播放器能打开完整页；播放/暂停、上一首/下一首、快退/快进、拖动进度、时间变化和音量均生效。确认首尾歌曲禁用相应按钮，并用 TalkBack、D-pad 和大字体重走流程。

- [ ] **Step 5: Commit**

```powershell
rtk git add android/app/src/main/kotlin/com/example/local_music_player/BassBridge.kt lib/domain/ports/bass_player.dart lib/infrastructure/bass_method_channel_player.dart lib/presentation/player_controls.dart lib/presentation/now_playing_screen.dart lib/presentation/home_screen.dart
rtk git commit -m "feat: add full local player controls"
```

### Task 3: 让活动 DLNA 会话可复用并支持控制与音量

**Files:**
- Modify: `lib/domain/ports/dlna_renderer.dart`
- Modify: `lib/infrastructure/dlna/dlna_renderer_client.dart`
- Modify: `lib/infrastructure/dlna/dlna_cast_session.dart`
- Modify: `lib/application/dlna_cast_controller.dart`
- Modify: `lib/app.dart`

**Interfaces:**
- Produces: `VolumeRenderer.supportsVolume`、`setVolume(double)`；这是独立于现有 `DlnaRenderer` 的可选接口，无 `RenderingControl` 服务的设备返回 `false`。
- Produces: `DlnaCastController.playActive/pauseActive/resumeActive/seekActive/setActiveVolume/replaceActiveTrack`，只在活动投送会话上工作。

- [ ] **Step 1: 扩展窄 DLNA 端口与客户端**

在端口文件新增独立 `VolumeRenderer`；让 `DlnaRendererClient implements DlnaRenderer, VolumeRenderer`，构造函数接收可空 `RenderingControlService`。使用 `renderingControlService()` 取得它；`supportsVolume` 只在服务存在且上次命令未失败时为真。`setVolume` 将 0–1 夹紧并转为 0–100 整数；动作失败后抛出，交给上层将该能力禁用并显示错误。

```dart
bool get supportsVolume => _renderingControl != null && !_volumeUnavailable;

Future<void> setVolume(double value) async {
  final service = _renderingControl;
  if (service == null) throw StateError('Device does not support volume');
  try {
    await service.setVolume(volume: (value.clamp(0.0, 1.0) * 100).round());
  } on Object {
    _volumeUnavailable = true;
    rethrow;
  }
}
```

- [ ] **Step 2: 让 CastSession 重用 HTTP server 而非拒绝换歌**

新增 `ControlledCastSession implements CastSession`，承载 `replaceTrack`、播放、暂停、定位、位置、音量和能力成员；保留既有 `CastSession` 不变。让 `DlnaCastSession implements ControlledCastSession`。`replaceTrack` 先停止旧 server，再启动新曲目的 tokenized URL，再 `renderer.load` 和 `renderer.play`；任一步失败都停止 server 并抛出。不要调用 `PlaybackCoordinator.startCasting` 第二次，因此不要恢复本机声音。

- [ ] **Step 3: 公开活动会话控制并保留设备目标**

`DlnaCastController.start` 成功后设置活动会话；其 `replaceActiveTrack` 将活动会话检查为 `ControlledCastSession` 后代理。所有没有活动会话或没有控制能力的命令抛出明确 `StateError`。`returnToLocal` 仍是唯一清理会话、停止服务器并清除活动目标的成功路径。

```dart
Future<void> replaceActiveTrack(Track track) {
  final session = _activeSession;
  if (session == null) throw StateError('No active DLNA cast session');
  return session.replaceTrack(track);
}
```

在 `app.dart` 将这些控制方法作为 `HomeScreen` 回调传入；不创建第二个 `DlnaCastController`。

- [ ] **Step 4: 人工检查设备控制**

连接同一 Wi-Fi 的 DLNA 设备后，确认成功连接后底栏显示设备名。进入不同文件夹并点歌，确认新歌直接替换设备端节目且手机不发声；确认暂停/播放、定位、快进/快退和上一首/下一首在设备端生效。验证带 RenderingControl 的设备音量变化，及没有该服务/命令失败时音量禁用并给出清晰提示。

- [ ] **Step 5: Commit**

```powershell
rtk git add lib/domain/ports/dlna_renderer.dart lib/infrastructure/dlna/dlna_renderer_client.dart lib/infrastructure/dlna/dlna_cast_session.dart lib/application/dlna_cast_controller.dart lib/app.dart
rtk git commit -m "feat: control active DLNA playback"
```

### Task 4: 将统一播放器接入投送目标并记录人工验收

**Files:**
- Modify: `lib/presentation/home_screen.dart`
- Modify: `lib/presentation/now_playing_screen.dart`
- Modify: `docs/release/first-release-checklist.md`

**Interfaces:**
- Consumes: 本机 BASS 回调及 Task 3 的活动投送回调。
- Produces: 一个根据当前目标分派命令的完整播放器，且投送时显示设备名称与可用能力。

- [ ] **Step 1: 在 HomeScreen 集中分派播放命令**

`_playQueueIndex` 在 `_isCasting` 时调用 `replaceActiveTrack`，否则调用 `bass.play`。`_togglePlayback`、`_seek`、`_setVolume` 与位置轮询按当前目标调用对应回调。设备端位置轮询同样每秒一次；指令错误设置页面的可恢复 `_error`，保留 `_isCasting` 和当前设备名称。

- [ ] **Step 2: 将设备能力传给完整播放器**

完整播放器在投送时显示“正在投送到：<设备名>”。没有设备音量能力时显示禁用的音量控件并带 `Semantics` 描述“此设备不支持音量控制”；上一首/下一首仅由队列边界决定，仍通过应用替换当前设备歌曲实现。保留明确的“返回本机播放”操作，不能因播放失败自行调用。

- [ ] **Step 3: 更新人工验收清单**

在现有发布清单增加：真实文件夹导航、完整播放器所有控件、连接设备后跨目录选歌默认投送、DLNA 音量支持/不支持、设备断开恢复、TalkBack、D-pad 与大字体检查。每项保留未勾选状态，供用户实机记录。

- [ ] **Step 4: 执行静态构建检查，不运行测试**

在 ASCII 目录仅运行格式化、静态分析和 debug APK 构建；明确不执行 `flutter test`。

```powershell
rtk cmd /c "D:\flutter-sdk\bin\dart.bat format lib"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat analyze"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat build apk --debug"
```

- [ ] **Step 5: Commit**

```powershell
rtk git add lib/presentation/home_screen.dart lib/presentation/now_playing_screen.dart docs/release/first-release-checklist.md
rtk git commit -m "feat: route player controls to connected device"
```

## Plan Self-Review

### Spec Coverage

- 真实目录浏览、目录返回和播放队列冻结由 Task 1 与 Task 2 覆盖。
- 迷你播放器、完整播放器、所有本机控制和无障碍语义由 Task 2 覆盖。
- 已连接设备成为默认目标、设备控制、音量能力降级和不自动回退由 Task 3 与 Task 4 覆盖。
- 用户要求的人工验收和不运行自动化测试由每项人工检查及 Task 4 覆盖。

### Placeholder Scan

计划不包含待定项；每个改动都有精确文件、接口、实现边界、人工检查和提交命令。

### Type Consistency

`Track.folderPath` 在 Kotlin、MediaStore 映射和目录界面中一致；音量在 Dart 端始终是 `double 0.0..1.0`，只在 BASS 和 UPnP 边界转换；可选能力不会破坏现有端口实现或测试替身；所有投送控制都经由单一 `DlnaCastController` 的活动会话。
