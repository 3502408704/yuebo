# 本地音乐播放器项目交接

更新日期：2026-07-24

## 一句话状态

这是一个 Flutter Android 本地音乐播放器，已完成本地 MediaStore 扫描、BASS 本地播放桥接、受限 DLNA HTTP 媒体服务和 DLNA DMR 发现/AVTransport 适配。当前停在“投送设备选择器”的测试先行阶段，尚未把设备选择、HTTP 服务和 DLNA 播放串成完整投送会话。

## 用户目标与固定决策

- 平台：仅 Android，框架 Flutter。
- 产品：本地音乐播放器，强调 TalkBack、键盘/D-pad 和替代输入可用性。
- 本地播放：BASS 2.4 Android。用户确认仅非商业免费发布，因此可使用其非商业许可。
- 传输：先做 DLNA/UPnP 到小米电视；这不是文件传输，也不是 AirPlay 或 Google Cast。AirPlay/Chromecast 暂不做。
- 投送质量：直接向渲染器提供原始 MP3/FLAC 字节，绝不转码、降码率，也不传输 BASS 的 EQ/DSP 输出。
- 设备扫描、选择、投送、停止和恢复本地播放都必须是显式操作。选择设备本身不得开始播放或发起投送。
- 不创建 Git worktree；用户明确要求在主工作区工作。
- 不创建远程仓库或 GitHub 仓库。
- 无障碍基线已经由用户提供，后续不要再向用户询问无障碍方案。权威文件是根目录 `android-accessibility-standard-zh.md`。

## 工作区和工具约束

- 真实主工作区：`D:\本地音乐播放器`。
- Flutter/Dart 工具在非 ASCII 路径会出现分析器和 Gradle 路径问题。因此存在目录联接：`D:\musicplayer -> D:\本地音乐播放器`。
- Flutter、Dart、测试、分析和 APK 构建必须在 `D:\musicplayer` 执行。
- Flutter SDK：`D:\flutter-sdk`，已验证版本为 Flutter 3.44.8 / Dart 3.12.2。
- 所有 shell 命令必须以 `rtk` 开头，来自根目录 `C:\Users\35024\.codex\RTK.md` 的规则。
- Android Gradle 不能直接在物理中文路径构建；请始终经由 ASCII 联接构建。`android/gradle.properties` 已含 `kotlin.incremental=false`，用于规避联接路径下 Kotlin 增量缓存错误。
- 手工改文件只使用 `apply_patch`。不要重置、还原或删除用户已有的未跟踪文件。

常用命令：

```powershell
# 工作目录：D:\musicplayer
rtk cmd /c "D:\flutter-sdk\bin\dart.bat format lib test"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat analyze"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat test"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat build apk --debug"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat build apk --release"
```

## 必须遵守的工程流程

- 实现或修改用户可见界面时使用无障碍基线。每个操作需要清晰名称、角色、状态/值、动作结果和可预期返回路径。
- 新功能与修复采用 TDD：先写最小失败测试，实际观察失败，再写最小实现，最后运行相关测试和全量测试。
- 遇到测试失败或意外行为，先按系统化调试找根因、复现、检查近期改动，再修复。不要猜测式叠加修改。
- 当前已有设计与实施计划，分别见：
  - `docs/superpowers/specs/2026-07-24-local-music-player-dlna-design.md`
  - `docs/superpowers/plans/2026-07-24-local-music-player-first-release.md`
- 设计文档中提到过 `on_audio_query`，这是早期设想，已经不适用。该插件的 Android 集成有问题，现有实现改为项目自有 Kotlin `MediaStore` 通道，后续必须以代码和本交接文档为准。

## Git 历史

已提交的增量，从旧到新：

| 提交 | 内容 |
| --- | --- |
| `f1476ea` | DLNA 设计文档 |
| `c1ab313` | 首版实施计划 |
| `522d143` | Flutter Android 工程与基础配置 |
| `e052c88` | 播放状态协调器 |
| `fca2073` | MediaStore 本地曲目读取 |
| `4902abb` | Kotlin BASS 与原生媒体桥接 |
| `30c417a` | 令牌化本地 HTTP 媒体服务 |
| `f0838c8` | DLNA 发现与 AVTransport 客户端 |
| `02bb877` | 无障碍本地音乐库 UI、独立暂停控件、投送回到本地播放 |

不要提交这些用户提供或构建产生的内容：

- `android-accessibility-standard-zh.md`
- `bass24-android.zip`
- `upnp_client-1.0.4.tar.gz`
- `android/build/`

截至本交接，唯一与实现有关、尚未提交的文件是 `test/presentation/cast_picker_test.dart`。它是下一项功能的刻意失败测试，尚未运行，因为生产文件 `lib/presentation/cast_picker.dart` 还不存在。

## 已完成实现

### 数据模型和播放状态

- `lib/domain/models/track.dart`：曲目 ID、`content://` URI、标题、艺术家、时长和 MIME 类型。
- `lib/domain/models/playback_mode.dart`：`idle`、`local`、`preparingCast`、`casting`、`castInterrupted`、`error`。
- `lib/domain/models/dlna_device.dart`：设备 ID、显示名、可用性。
- `lib/domain/ports/bass_player.dart`：本地播放器最小端口，含播放、暂停、恢复、定位、当前播放位置。
- `lib/domain/ports/dlna_renderer.dart`：AVTransport 端口，含 load/play/pause/stop/seek/position。
- `lib/domain/playback_coordinator.dart`：
  - `startCasting`：读取 BASS 位置，标为 `preparingCast`，暂停 BASS，向 renderer load/play；成功才转 `casting`。
  - renderer 失败时恢复 BASS，状态回到 `local`，抛出 `CastFailure`。
  - `returnToLocal(track)`：读取 renderer 位置，停止 renderer，用该位置调用 BASS 播放，并回到 `local`。

注意：协调器当前只有状态转移，不拥有 HTTP server 生命周期、选中设备对象或曲目队列；完整投送编排仍待实现。

### 本地媒体库

- `android/app/src/main/kotlin/com/example/localmusicplayer/LibraryBridge.kt`：请求 Android 音频读取权限、查询 MediaStore，并将歌曲字段返回 Flutter。
- `lib/infrastructure/media_store_library.dart`：`MediaStoreLibrary` 与 `LibraryPermissionDenied`，通过方法通道映射成 `Track`。
- `lib/domain/ports/library_repository.dart`：仓储端口。
- 当前 UI 能正确区分加载、权限拒绝、加载错误、空音乐库和有曲目列表。

### BASS 本地播放

- BASS Java 绑定：`android/app/src/main/java/com/un4seen/bass/BASS.java`。
- ABI 库：`android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libbass.so`。
- Kotlin 桥：`android/app/src/main/kotlin/com/example/localmusicplayer/BassBridge.kt`。
- Flutter 适配：`lib/infrastructure/bass_method_channel_player.dart`。
- BASS 从 Android `content://` URI 的文件描述符加载、按毫秒定位、播放/暂停/恢复和读取位置。
- BASS 许可文本已在 `assets/licenses/bass-license.txt` 声明为 Flutter asset。尚缺应用内“关于/许可”界面。

### DLNA 基础设施

- `lib/infrastructure/dlna/source_reader.dart`：`SourceReader` 端口。
- `lib/infrastructure/dlna/content_uri_source_reader.dart`：通过 Kotlin 通道按 64 KiB 块读取 MediaStore `content://` 数据。
- `android/app/src/main/kotlin/com/example/localmusicplayer/MediaSourceBridge.kt`：原生读取实现。
- `lib/infrastructure/dlna/local_media_server.dart`：只服务一个当前曲目的本地 HTTP server。
  - URI 含不可猜测 token。
  - 仅接受当前 token。
  - 支持 `HEAD`、`GET`、字节 Range 与 `206 Partial Content`。
  - 不提供目录列出、上传或任意文件访问。
- `lib/infrastructure/dlna/dlna_discovery.dart`：用 `upnp_client` 搜索 IPv4 UPnP `MediaRenderer:1`，仅保留带 AVTransport 的设备。
- `lib/infrastructure/dlna/dlna_renderer_client.dart`：以 `AvTransportService` 实现 load/play/pause/seek/stop/position；时间格式为 DLNA `HH:MM:SS`。

### 已完成界面

- `lib/app.dart`：创建 `MethodChannelBassPlayer` 与 `MediaStoreLibrary`，显示 `HomeScreen`。
- `lib/presentation/home_screen.dart`：本地曲目列表，点按一首歌曲后调用 BASS 播放。
- 底部当前播放栏采用自定义 `Row`，不使用会合并尾部按钮语义的 `ListTile`。
  - 播放信息语义为“正在播放：曲名，艺术家”。
  - 暂停/播放按钮独立语义，名称准确且可单独获取 TalkBack 焦点。
- 已修复过一次真实语义问题：`ListTile` 把歌曲信息和“暂停”合并为一个节点。不要恢复为该结构。

## 当前未完成的关键功能

按优先级执行：

1. 完成 `CastPicker`：显式打开才扫描，选设备不投送，另按“开始投送到 <设备>”才调用回调；对话框有标题、关闭路径、扫描/空/错误/重试状态，关闭后焦点回到触发按钮。
2. 将 `HomeScreen` 的“投送到设备”入口和 `DlnaDiscovery` 接起来。需要在应用层保留 `DiscoveredRenderer`，因为 UI `DlnaDevice` 不包含 `AvTransportService`。
3. 建立真实投送会话：选择后的 renderer、当前 `Track`、LAN IPv4 地址、`ContentUriSourceReader`、`LocalMediaServer` 和 `DlnaRendererClient` 一起传给/封装进会话控制器。
4. 在会话中处理 server 生命周期：开始投送时建 server，成功后保持，取消/失败/停止/回到本地/断网都必须关闭。失败不得自动在手机扬声器恢复；显示可恢复状态。
5. 增加播放/暂停/seek 状态、播放进度与可访问 Slider 语义（当前时间、总时长、范围、快进/快退动作）。
6. 增加队列、上一首/下一首、循环/随机，且无障碍状态可读。
7. 加入 `audio_service` 和 `audio_session`：通知、锁屏、耳机、音频焦点、前台服务、后台本地和投送命令分流。
8. 添加 BASS 关于/许可页面。
9. 增加 README、发布清单、release APK、真机和小米电视验收记录。

## 下一步的精确起点：投送设备选择器

已存在失败测试：`test/presentation/cast_picker_test.dart`。运行：

```powershell
# 工作目录：D:\musicplayer
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat test test\presentation\cast_picker_test.dart"
```

预期初始失败：找不到 `package:local_music_player/presentation/cast_picker.dart`。

该测试要求生产接口：

```dart
Future<void> showCastPicker({
  required BuildContext context,
  required Future<List<DlnaDevice>> Function() scanDevices,
  required Future<void> Function(DlnaDevice device) onStartCasting,
});
```

测试锁定以下行为：

1. 用户点击语义名为“投送到设备”的按钮后，出现语义名为“选择投送设备”的弹窗标题。
2. 扫描结果中“小米电视”的语义名是“小米电视，DLNA 设备，可用”。
3. 点选该设备后，`onStartCasting` 调用数仍为 0。
4. 出现语义名“开始投送到小米电视”的按钮。
5. 只有点击这个按钮后，`onStartCasting` 才调用一次。

推荐最小实现：

- 新增 `lib/presentation/cast_picker.dart`，其中的 stateful dialog 在 `initState` 调 `scanDevices`，因为打开弹窗已经是显式用户操作。
- 使用 `showDialog`/`AlertDialog`，其模态语义可隔离背景；加一个有文字或有明确语义名的关闭按钮。
- 设备行使用独立、可访问的 `ListTile` 或 `RadioListTile`，选择仅更新内部选中状态。
- 开始按钮无选择时禁用；选择后具备完整动作名。
- 在调用 `showCastPicker` 的外层按钮持有 `FocusNode`，`await showCastPicker` 返回后请求该节点焦点，实现关闭后焦点返还。
- 不要在焦点变更、列表语义浏览或设备选择时扫描或开始网络投送。

测试完成后，在接入 `HomeScreen` 前运行：

```powershell
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat analyze"
rtk cmd /c "D:\flutter-sdk\bin\flutter.bat test"
```

## 投送会话的建议实现顺序

以下仍需先写各自失败测试：

1. 新建应用层 `DlnaCastSession` 或等价的最小会话对象，明确拥有 `LocalMediaServer` 与 `DlnaRenderer`。
2. 测试成功路径：server start -> coordinator startCasting -> mode casting；确认 server 仍运行。
3. 测试失败路径：server start 或 renderer load/play 任意失败 -> server stop；协调器恢复/设置正确的用户可见状态。
4. 测试停止和返回本地：读取 renderer position -> stop renderer -> BASS 从该 position play -> stop server。
5. 从安卓网络接口中选择有效 LAN IPv4，传入 `LocalMediaServer(advertisedAddress: address)`。不能使用 loopback、蜂窝地址或 IPv6 作为小米电视可访问的首版地址。
6. `DlnaRendererClient` 用所选 `DiscoveredRenderer.upnpDevice.avTransportService()` 创建；必须空值检查。
7. 只支持 `audio/mpeg` 和 `audio/flac` 投送；其它 MIME 类型明确拒绝并给出可感知错误，不能静默降质或转码。

## 已验证结果

在提交 `02bb877` 后、创建未提交的 `cast_picker_test.dart` 之前，已在 `D:\musicplayer` 成功运行：

```text
flutter analyze  -> No issues found
flutter test     -> 11 tests passed
```

已单独验证：

- `test/presentation/home_screen_test.dart`：播放曲目后可找到独立语义节点“暂停”。
- `test/domain/playback_coordinator_test.dart`：投送成功、renderer 拒绝时恢复本地、从 renderer 位置回到本地，3 个测试均通过。

还未进行的验证：

- 未运行新建的 `cast_picker_test.dart`，因为它特意等待实现。
- 没有连接 Android 真机或小米电视，未验证 BASS、MediaStore 权限、DLNA 发现、HTTP server 和 AVTransport 的真实端到端行为。
- 仅成功构建过早期 debug APK；当前 HEAD 尚未重新构建 APK，更未构建 release APK。

## 真机验收重点

小米电视和手机必须在同一 Wi-Fi。发布前至少记录：

- 授权媒体权限后能看到实际歌曲并播放。
- BASS 播放、暂停、重新播放、定位是否正常。
- 小电视能被发现；选择设备不会播放；明确开始投送才播放。
- MP3 320 kbps、CD 质量 FLAC、以及电视支持时 24-bit/96 kHz FLAC。DLNA 无统一最高码率，不支持时应明确失败而非转码。
- 投送后电视是否使用 phone HTTP URL 拉取，Range 请求是否正常；结束投送后 URL 不再可访问。
- 断网/设备关机时进入可恢复错误状态，不自动从手机外放继续。
- TalkBack：列表、底栏独立暂停、投送弹窗标题/关闭/设备选择/开始投送/关闭后的焦点返还、进度控制、错误恢复。
- 大字体、D-pad/键盘、后台、通知、耳机和音频焦点（后两类需先实现 audio_service）。

## 发布前的缺口

- `audio_service` 和 `audio_session` 已在 `pubspec.yaml`，但当前没有 `PlayerAudioHandler`，尚未调用，通知/锁屏/耳机/音频焦点均未完成。
- 设计中提到队列、专辑、艺术家、封面、EQ、循环和随机，但当前实际只显示歌曲列表和本地播放/暂停；不要把它们误认为已实现。
- 尚未提供播放进度、停止、seek、next/previous、设备投送入口、投送停止/恢复入口或 BASS 关于页。
- `README.md` 与 `docs/release/first-release-checklist.md` 尚未创建。
- `android/app/build.gradle.kts` 的 release 当前明确使用 debug signing key，只适合本地验收，不适合分发；最终发布前需要添加并妥善保管独立 release signing config。

## 关键文件索引

| 目的 | 文件 |
| --- | --- |
| 无障碍权威基线 | `android-accessibility-standard-zh.md` |
| 设计 | `docs/superpowers/specs/2026-07-24-local-music-player-dlna-design.md` |
| 实施计划 | `docs/superpowers/plans/2026-07-24-local-music-player-first-release.md` |
| Flutter 入口 | `lib/main.dart`, `lib/app.dart` |
| 本地播放器 UI | `lib/presentation/home_screen.dart` |
| 未实现投送选择器的失败测试 | `test/presentation/cast_picker_test.dart` |
| 播放状态机 | `lib/domain/playback_coordinator.dart` |
| BASS Dart/Android 桥 | `lib/infrastructure/bass_method_channel_player.dart`, `android/app/src/main/kotlin/com/example/localmusicplayer/BassBridge.kt` |
| MediaStore Dart/Android 桥 | `lib/infrastructure/media_store_library.dart`, `android/app/src/main/kotlin/com/example/localmusicplayer/LibraryBridge.kt` |
| HTTP 媒体服务 | `lib/infrastructure/dlna/local_media_server.dart` |
| Content URI 读取桥 | `lib/infrastructure/dlna/content_uri_source_reader.dart`, `android/app/src/main/kotlin/com/example/localmusicplayer/MediaSourceBridge.kt` |
| DLNA 发现/控制 | `lib/infrastructure/dlna/dlna_discovery.dart`, `lib/infrastructure/dlna/dlna_renderer_client.dart` |
| Android 权限与明文 HTTP | `android/app/src/main/AndroidManifest.xml` |

## 交接后的首次操作建议

1. 阅读本文件、无障碍基线、设计和计划。
2. 在 `D:\musicplayer` 运行 `flutter test test/presentation/cast_picker_test.dart`，确认预期的缺文件失败。
3. 用 TDD 实现最小 `cast_picker.dart`，先只让该测试通过，不提前耦合真实 DLNA transport。
4. 每个已完成小增量运行 formatter、analyze、all tests 并提交；不要提交用户提供的 zip、基线文件或 `android/build/`。
5. 再实现投送会话生命周期，并在具备真机与小米电视时完成物理验收。
