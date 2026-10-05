# 视频播放器后台播放、字幕与 B 站播放器界面交接

更新时间：2026-09-10（本日第二次续接）。状态：**客户端代码已按 §4 风险项收敛；全量单测 314 项全绿、`:app:compileDebugKotlin` 与 `:app:assembleDebug` 均通过；仍无实体机验收证据（`adb devices` 为空，设备验收整体未做）。**

> 续接进展（见 §8）：修掉唯一失败用例（`VideoSubtitleTest` 原始字符串里的 `\u003e` 不被解释）、竖屏画面层语义与字幕焦点回退已改、前台服务启动时机与异常兜底已改、`clearVideoSubtitle()` 支持清除 B 站/AI 字幕、B 站字幕 AI 去重语言归一化修正，并补齐 B 站字幕轨道/正文/AI 字幕回归测试。

## 1. 用户目标

本轮需求来自用户对 `/mnt/d/bilibili-player-android` 的对照要求：

1. 视频播放过程中锁屏后继续播放音频，解锁后恢复视频画面。
2. 补齐字幕增强能力。
3. 播放器控件和字幕页面的 TalkBack 语义尽量模仿参考工程。
4. 播放器横竖屏的空间排布尽量照抄参考工程。

参考工程重点文件：

- `/mnt/d/bilibili-player-android/app/src/main/java/com/bilibili/player/feature/player/shared/AccessiblePlayerScaffold.kt`
- `/mnt/d/bilibili-player-android/app/src/main/java/com/bilibili/player/feature/player/shared/PlayerControlOverlay.kt`
- `/mnt/d/bilibili-player-android/app/src/main/java/com/bilibili/player/feature/player/PgcPlayerScreen.kt`
- `/mnt/d/bilibili-player-android/app/src/main/java/com/bilibili/player/feature/player/subtitle/SubtitleSettingsSheet.kt`
- `/mnt/d/bilibili-player-android/app/src/main/java/com/bilibili/player/feature/player/subtitle/SubtitleTranscriptSheet.kt`
- `/mnt/d/bilibili-player-android/app/src/main/java/com/bilibili/player/feature/player/subtitle/TimedTextListSheet.kt`
- `/mnt/d/bilibili-player-android/app/src/main/java/com/bilibili/player/feature/accessibility/AccessiblePage.kt`

## 2. 当前验证结论

已通过（2026-09-10 续接后重跑）：

- `:app:compileDebugKotlin`（含本次全部改动）
- `:app:assembleDebug` → `build\app\outputs\apk\debug\app-debug.apk`（36.21 MB），可随时 `adb install` 验收
- `:app:testDebugUnitTest`：**314 项全绿**（原 310 项 + 新增 4 项 B 站字幕回归）
  - 原失败项 `VideoSubtitleTest.exportsWebVttAndPlainText` 已修复。
    根因不是缩进/换行，而是**原始字符串（三引号）里的 `\u003e` 不被 Kotlin 解释**，期望值保留了字面 `\u003e`，
    而 `VideoSubtitleExporter.escapeCueText` 输出的是真实的 `>`；已改用转义字符串拼接，不再依赖 `\u` 解释。
- `rtk git diff --check` 无空白错误

当前环境：

- `adb devices` 没有实体设备或模拟器。
- 尚未验证锁屏 10 秒以上的音频连续播放。
- 尚未验证通知栏暂停/继续、解锁后画面恢复、横竖屏切换、真实 B 站字幕、AI 字幕、外挂字幕分享和 TalkBack。

## 3. 已完成的代码

### 3.1 后台音频播放

相关文件：

- `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/MediaPlaybackService.kt`（本轮未大改，已有通知服务）
- `android/app/src/main/kotlin/com/example/local_music_player/NativeVideoPlayer.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/FfmpegFallbackPlayer.kt`

当前实现：

- `VideoPlayerScreen` 监听 `Lifecycle.Event.ON_PAUSE` 和 `ON_RESUME`。
- 系统退后台时调用 `NativeMusicViewModel.enterVideoBackground()`。
- `enterVideoBackground()` 记录进入后台前的播放状态，调用 `videoPlayer.setVideoEnabled(false)`，保留视频音轨继续播放，并强制刷新 MediaSession/前台通知。
- 解锁或回到前台时调用 `resumeVideoFromBackground()`，按进入后台前状态恢复；如果用户进入后台前本来是暂停，不应自动恢复播放。
- `ExoPlayerVideoEngine.setVideoEnabled(false)` 通过禁用 `C.TRACK_TYPE_VIDEO` 保留音频；FFmpeg 兜底引擎走 `nativeSetVideoEnabled(false)`。
- 新增 `videoWasPlayingBeforeBackground`，区分系统退后台和用户主动按返回退出视频页。
- 用户主动返回列表仍走 `pauseVideoForBackground()`，会暂停视频，不应被当作锁屏后台播放。
- MediaSession 的标题、时长、播放/暂停、上一集/下一集、seek 由现有 `NativeMusicViewModel.updateMediaSession()` 统一更新。
- 视频进度轮询改为播放中 250ms、空闲 1s；缓冲状态单独暴露。

相关入口行号可从当前文件搜索：`enterVideoBackground`、`resumeVideoFromBackground`、`videoWasPlayingBeforeBackground`、`updateMediaSession`。

### 3.2 字幕模型、解析和增强能力

相关文件：

- `android/app/src/main/kotlin/com/example/local_music_player/VideoSubtitle.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/BilibiliApi.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/VideoSubtitleExporter.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/VideoSubtitleShare.kt`
- `android/app/src/main/res/xml/file_paths.xml`

已实现：

- 外挂字幕：SRT、VTT、ASS、SSA、LRC、TTML、XML。
- TTML/XML 支持 `<p begin/end>`、`<p begin/dur>`、秒、毫秒和时钟格式；XML 解析关闭外部实体、DTD 和外部 schema 访问。
- 统一清理 ASS 标签、HTML 标签、实体、换行和 BOM。
- B 站字幕轨道解析：`/x/player/v2` 的 `subtitle.subtitles`，识别语言、作者、锁定状态和 AI 字幕。
- B 站 JSON 字幕正文解析：`body[].from/to/content`。
- B 站 AI 定时字幕解析：当前按 `data.model_result.subtitle[].part_subtitle[]` 读取时间和文本。
- 主字幕、副字幕切换；副字幕默认尽量选择不同语言。
- 字幕显示/隐藏、字幕延迟（-30 秒到 +30 秒）、字号（75% 到 175%）。
- 当前字幕朗读、上一句、重读、下一句、字幕稿搜索和按句跳转。
- 导出 WebVTT 和纯文本，通过现有 `${packageName}.update_provider` FileProvider 交给系统分享。
- 字幕内容在播放器画面上以副字幕在上、主字幕在下显示，并合并成一个 TalkBack 语义节点。

### 3.3 播放器界面与无障碍

相关文件：

- `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- `android/app/src/main/kotlin/com/example/local_music_player/VideoSubtitleSheets.kt`

已实现：

- 竖屏：顶部固定 16:9 播放区，下方为可滚动的标题、状态、进度、上一集/快退/播放/快进/下一集、倍速、音量、选集。
- 横屏：沉浸式全屏，顶部标题和操作栏、中央播放控制、底部进度和传输控制，选集从右侧滑入面板。
- 直播或未知时长时隐藏进度控制，并在状态语义中说明“直播”或“时长未知”。
- 播放器节点包含播放状态和“显示/隐藏媒体控制”动作。
- 进度条增加 `ProgressBarRangeInfo`、`setProgress` 和跳到开头/四分之一/一半/四分之三/结尾的自定义动作。
- 播放、返回、全屏、退出全屏、投送、更多选项、上一集/下一集等图标按钮有中文 content description，目标尺寸至少 48dp。
- 字幕设置、主字幕选择、副字幕选择、字幕稿和选集使用 `ModalBottomSheet`，设置 `paneTitle`、标题 heading、按钮 role、状态描述和 live region。
- 字幕稿支持搜索，列表项语义包含时间、文本、当前状态和“跳转到此字幕”动作。
- 选集工作表关闭后尝试把焦点恢复到“选集”入口。

## 4. 必须优先处理的风险和未完成项

### 4.1 锁屏后台播放尚未证明，前台服务启动时机有风险

当前 `MediaPlaybackService.show()` 直接调用 `startForegroundService()`，没有捕获 `ForegroundServiceStartNotAllowedException`。`enterVideoBackground()` 在 `ON_PAUSE` 中强制刷新通知；在部分 Android 版本或厂商系统上，此时已经不再被视为前台启动，可能直接拒绝启动服务。因此不能仅凭编译通过声明“锁屏后台播放已修复”。

后续 AI 应先检查并验证：

1. 视频开始播放时，应用仍在前台，前台媒体服务和通知是否已经成功启动。
2. 锁屏后是否仍有 `MediaPlaybackService`、通知和有效 MediaSession。
3. `ON_PAUSE` 期间 `show()` 失败时是否有日志或未捕获异常。
4. Android 13/14/15 及目标设备厂商对通知权限、前台媒体服务类型的行为。
5. 参考工程是在播放状态变化时提前启动媒体服务；可考虑沿用该时机，避免把首次服务启动放在 `ON_PAUSE`。

不要为了后台播放重新引入 BASS 或 libVLC。现行架构是 Media3/ExoPlayer + FFmpeg 兜底，libVLC 迁移已由用户取消。

**续接已处理（代码层）**：

1. 启动时机：`playVideos()` 在 `_state.videoPlaying = true` 之后即调用 `updateMediaSession()` →
   `MediaPlaybackService.show()`，也就是**前台播放一开始就启动了前台服务与通知**，锁屏只是切画面轨，
   不再依赖锁屏瞬间首次启动服务。
2. `MediaPlaybackService.show()` 改为只在服务未运行（`running == false`）时走 `startForegroundService()`；
   已在运行时改用 `startService()` 刷新通知。
3. 捕获 `ForegroundServiceStartNotAllowedException` / `SecurityException` 并 `Log.w` 后退化为 `startService()`，
   兜底失败也只记日志，不再抛出未捕获异常。
4. `running` 由 `onCreate/onDestroy` 维护（进程内标记，进程重启自动复位）。
5. 清单已确认：`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限与 `foregroundServiceType="mediaPlayback"` 齐备。

**仍未证明**：真机锁屏 10s+ 音频连续、通知栏控制、Android 版本/厂商差异（无设备，见 §8）。

### 4.2 竖屏播放器的 `clearAndSetSemantics` 可能吞掉子控件语义

`MusicApp.kt` 中竖屏播放区目前把 `videoTapModifier` 放在包含顶部返回/投送/更多/全屏按钮和中央播放按钮的外层 `Box` 上，而 `videoTapModifier` 使用 `clearAndSetSemantics`。这可能使 TalkBack 只看到“播放器”，看不到外层 Box 内的返回、投送、更多和全屏控件。

后续应把点击手势和“播放器”语义放到只承载视频画面的底层 Box，控制层作为同级或独立语义节点保留；然后用无障碍树实际确认，而不是只看代码。

**续接已处理**：竖屏外层 Box 只保留 `fillMaxWidth().aspectRatio(16f/9f).background(Black)`，
新增内层 `Box(Modifier.fillMaxSize().then(videoTapModifier))` 只包 `VideoSurfaceStack`，
顶部返回/投送/更多/全屏与中央播放按钮作为同级节点保留各自语义。
横屏原本就已是该结构，无需改动。
另外把当前生效的主/副字幕文本并入“播放器”节点的 `stateDescription`（新增
`videoSubtitleAccessibilityText(state)`，画面层与语义层共用同一份计算），
否则 `clearAndSetSemantics` 会连字幕一起吞掉。**无障碍树仍需在真机上确认。**

### 4.3 字幕选择工作表焦点恢复不完整

`VideoSubtitleSettingsSheet` 给主字幕/副字幕选择工作表传入的是 `null` return focus requester；关闭选择工作表后没有明确恢复到“主字幕”或“副字幕”设置行。当前主要依靠 `FocusRequester.requestFocus()`，没有参考工程里的 `ACTION_ACCESSIBILITY_FOCUS` 原生补偿。

后续应：

- 分别为主字幕和副字幕设置行建立 `FocusRequester`。
- 选择工作表关闭后恢复到对应设置行。
- 字幕稿关闭后保持恢复到“字幕稿”入口。
- TalkBack 开启时确认工作表标题先被读出，关闭后不会跳到页面顶部或丢焦点。

**续接已处理**：新增 `primaryRowFocusRequester` / `secondaryRowFocusRequester` 并挂到“主字幕”“副字幕”设置行；
`VideoSubtitleTrackSelectionSheet` 增加 `returnFocusRequester` 参数并透传给 `VideoSubtitleDialogFocusEffect`，
两个选择工作表关闭后分别回到对应设置行（字幕稿仍回到“字幕稿”行，选集回到“选集”入口）。
同时给 `VideoSubtitleDialogFocusEffect` 加了降级播报：`requestFocus()` 失败时
（节点未挂载、无障碍服务未就绪）通过 AccessibilityManager 发 `TYPE_ANNOUNCEMENT` 播报工作表标题，
正常路径不播报、不会重复朗读。**实际播报与焦点行为仍需 TalkBack 真机确认。**

### 4.4 字幕功能仍需真实数据回归

- 当前没有 B 站字幕轨道和 JSON 正文的完整生产夹具测试。
- 当前没有 AI 定时字幕成功、未登录、无字幕和接口结构变化测试。
- `clearVideoSubtitle()` 的语义偏向清除外挂字幕；当当前选中的是 B 站远程字幕时，需确认“清除当前字幕”是否应清除选中轨道，不能只移除 `external:<videoId>`。
- 字幕设置里使用 `LocalClipboardManager`，编译有弃用警告；不阻塞当前功能，但后续可换现行剪贴板 API。
- B 站字幕 URL 只在客户端直连，不得经过汪汪服务，也不得把账号 cookie、密钥或解析地址写入日志。
- `VideoSubtitleShare` 只应写入 cache 目录，不能把字幕导出文件写入项目、Git 或 APK。

**续接已处理**：

- 新增 4 项 JVM 回归测试（`VideoSubtitleTest`，共 8 项）：B 站字幕轨道解析（人工/AI/锁定/作者/URL 归一化）、
  `need_login_subtitle` 与 `allow_submit` 标记、只有 AI 字幕时保留该轨道、缺少 `subtitle_url` 的轨道丢弃、
  非 JSON / `code != 0` / 缺 `data` 三种结构变化、字幕正文 `body[].from/to/content`、
  AI 定时字幕 `data.model_result.subtitle[].part_subtitle[]` 成功与失败分支、主/副字幕默认选择。
- `clearVideoSubtitle()` 重写：清除**当前选中**的字幕（原来是只清 `external:<videoId>`，选中 B 站/AI 字幕时点了没反应）。
  外挂与 AI 轨道清除后从轨道列表移除（可重新加载），B 站远程轨道保留在列表中供再次选择；
  同时清掉对应缓存并给出 `已清除当前字幕` 状态播报。
- 顺带修正 B 站字幕 AI 去重的语言归一化：`ai-zh` 与 `zh-CN` 之前归一化后分别是 `zh` 和 `zh-cn`，
  同一语言的人工/AI 字幕**根本没被去重**。现抽出 `baseVideoSubtitleLanguage()`
  （去 `ai-` 前缀 + 取 `'-'` 前主干），轨道去重与副字幕选轨共用同一套归一化。

**仍未做**：`LocalClipboardManager` 弃用告警仍在（不阻塞）；真实 B 站字幕 / AI 字幕 / 外挂字幕分享尚无真机证据。

### 4.5 与参考工程的差异需要以设备截图和无障碍树收口

当前是按参考工程结构改造，不是像素级复制。尚未确认：

- 横屏顶部/底部控制层高度、透明度、边缘 inset 是否与参考工程一致。
- 竖屏标题、播放区、控制区和选集入口的滚动位置是否符合目标排布。
- 控件自动隐藏在 TalkBack 开启、普通触控、弹出字幕工作表和切换横屏时是否稳定。
- 字幕在刘海、导航栏、横屏底部控制层和长文本下是否遮挡内容。
- 状态播报是否出现“加载中/缓冲/播放/暂停”快速重复播报。

## 5. 后续建议执行顺序

1. 先重跑 `testDebugUnitTest`，确认 310 项或更新后的总数全绿。
2. 修正竖屏播放区语义树，补主/副字幕选择工作表焦点回退。
3. 检查 `MediaPlaybackService.show()` 的前台服务启动时机和异常处理；保持服务在视频前台播放期间已启动，锁屏只切换画面轨，不依赖锁屏瞬间首次启动服务。
4. 编译 debug APK 并安装实体机。
5. 播放本地 MP4、B 站在线视频、在线剧集、百度/夸克网盘视频各至少一条；播放中锁屏 10 秒以上，确认音频继续，通知显示正确。
6. 在锁屏通知上暂停/继续，解锁后确认视频轨恢复；确认用户手动“仅播放声音”设置不会被覆盖。
7. 验证 SRT、ASS、VTT、LRC、TTML；验证 B 站普通字幕、AI 字幕、主副字幕切换、延迟、字号、朗读、字幕稿跳转和导出分享。
8. 开启 TalkBack，记录播放器、进度条、字幕、字幕工作表、选集工作表的实际播报和焦点恢复。
9. 按 `scripts/smoke-checklist.md` 的视频播放、全屏投送和 TalkBack 项目记录证据；没有实体设备时只能报告“未验证”。
10. 最后再运行 `rtk git diff --check`、compile、全量单测；不要自动 commit 或 push。

## 6. 常用命令

所有命令必须以 `rtk` 开头。**本机是 Windows/PowerShell，工作目录为 `D:\wangwangplayer`**，
`android/` 没有 gradlew，直调本机 Gradle 9.1.0 发行版（下面的路径为已验证可用的本机路径）：

```text
rtk cmd /c "C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat -p android testDebugUnitTest"
rtk cmd /c "C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat -p android :app:compileDebugKotlin"
rtk cmd /c "C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat -p android :app:assembleDebug"
rtk git diff --check
rtk git status --short
rtk adb devices
```

注意：`rtk` 无法转发 PowerShell cmdlet（如 `Get-ChildItem`、`New-Item`），文件查看改用 `cmd /c` 或编辑器。
Gradle 9 把产物重定向到仓库根：`build\app\outputs\apk\debug\app-debug.apk`、
测试报告 `build\app\reports\tests\testDebugUnitTest\index.html`、
结果 XML `build\app\test-results\testDebugUnitTest\`。

## 7. 工作树注意事项

当前工作树有大量未提交修改，包含视频分类/在线视频等前序工作。不要执行 `git reset --hard`、`git checkout --`、递归删除或清理未跟踪文件。以下临时文件已存在，除非用户明确要求，不要删除：

- `scripts/tmp_beta9.apk`
- `scripts/tmp_proxy_range_test.py`
- `scripts/tmp_proxy_ua_final.py`
- `scripts/tmp_vol2_check.py`

本轮视频相关未提交文件主要是 `MusicApp.kt`、`NativeMusicViewModel.kt`、`BilibiliApi.kt`、`VideoSubtitle.kt`、`VideoSubtitleSheets.kt`、`VideoSubtitleExporter.kt`、`VideoSubtitleShare.kt`、`MediaPlaybackService.kt`、`file_paths.xml` 及对应测试。其他文件可能来自前序会话，修改前先看 `git diff`，不要覆盖用户已有变更。

## 8. 续接会话记录（2026-09-10）

按 §5 执行顺序推进，结果如下。

| §5 步骤 | 状态 | 说明 |
|---|---|---|
| 1 重跑单测 | ✅ | 先修掉 `exportsWebVttAndPlainText`（`\u` 原始字符串坑），314 项全绿 |
| 2 竖屏语义树 + 字幕焦点回退 | ✅ 代码层 | 见 §4.2 / §4.3；待 TalkBack 真机确认 |
| 3 前台服务启动时机与异常兜底 | ✅ 代码层 | 见 §4.1；待真机锁屏确认 |
| 4 编译 debug APK | ✅ | `build\app\outputs\apk\debug\app-debug.apk`（36.21 MB） |
| 5–9 真机/字幕/TalkBack 验收 | ❌ 未做 | `adb devices` 为空，无设备无模拟器 |
| 10 收尾检查 | ✅ | `rtk git diff --check` 干净、compile 通过、全量单测通过；未 commit/push |

本会话改动的文件：

- `android/app/src/test/kotlin/com/example/local_music_player/VideoSubtitleTest.kt`
  （修复导出断言 + 新增 4 项 B 站字幕回归）
- `android/app/src/main/kotlin/com/example/local_music_player/VideoSubtitle.kt`
  （`baseVideoSubtitleLanguage()`，轨道去重与副字幕选轨共用归一化）
- `android/app/src/main/kotlin/com/example/local_music_player/VideoSubtitleSheets.kt`
  （主/副字幕选择工作表焦点回退 + 播报降级）
- `android/app/src/main/kotlin/com/example/local_music_player/MediaPlaybackService.kt`
  （前台服务启动时机、异常兜底、`running` 标记）
- `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
  （`clearVideoSubtitle()` 支持清除 B 站/AI 字幕）
- `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
  （竖屏画面层语义重组 + 字幕并入播放器节点）
- 本交接文档

给下一棒的提示：

1. **设备验收仍是唯一缺口**：装 `app-debug.apk` 后按 §5 的 5–9 逐条走，尤其锁屏 10s+ 音频连续、
   通知栏暂停/继续、横竖屏切换、真实 B 站字幕与 AI 字幕、TalkBack 焦点回退。
2. 若锁屏后仍断音，优先看 `logcat` 里有没有 `MediaPlaybackService` 的
   `后台启动前台播放服务被拒绝`/`刷新播放通知失败` 告警，以及 `MediaSession` 是否仍然 active。
3. 横竖屏、字幕遮挡、状态播报重复等 §4.5 的视觉/播报差异，只能靠截图与无障碍树收口，代码层无法判断。
4. 提交前再跑一次 `rtk git diff --check` + 全量单测；不要自动 commit/push。

