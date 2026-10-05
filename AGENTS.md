# 月播仓库工作手册（最终版）

> 本文件是仓库级导航与工程约束。项目原名「汪汪播放器」，现名「月播」。
> **20261005final（versionCode 20261005）为最终版本，项目停止更新。** 更新日期：2026-10-05。

## 1. 项目形态

月播是一个 **Android 原生本地媒体播放器**：本地音乐/视频播放为核心，支持用户自行导入 MusicFree 插件音源做在线搜索/播放/下载。**不连接任何自营服务器，不含账号体系**（服务端、月播账号、工具页、更新检查已于最终版整体删除；TTS 工作台源码存档于 `archive/tts-workbench/`，不参与编译）。

- 唯一源码树：`android/`（Kotlin + Jetpack Compose + Media3 + FFmpeg JNI + QuickJS）。
- 工作区：Windows `D:\wangwangplayer`；构建无 wrapper，需本机 Gradle 9.x + JDK 17 + SDK 36。
- 历史 Flutter/BASS/服务端文档属于历史背景，不再描述当前行为。

## 2. 代码地图与入口

| 主题 | 首看 |
| --- | --- |
| 启动、Intent、外部打开 | `MainActivity.kt`、`AndroidManifest.xml` |
| 导航（媒体库/在线/我的 三 tab）、页面分派 | `MusicApp.kt`（`Screen`/`MainTab`/`MineSection`/`LibrarySection`/`LibraryBrowseSource` 枚举 + `when` 分派）、`OnlineScreens.kt` |
| 本地库、队列、收藏/历史、播放协调 | `NativeMusicViewModel.kt`（`MusicUiState` + 公开动作；大文件，用 `rg` 定位函数再读） |
| 引擎装配、下载/网盘服务 | `MusicApplication.kt`（lazy 单例） |
| 音频引擎契约 | `AudioEngine.kt`（`playUrl(url, durationMs, positionMs, headers, isLive)` 支持 headers）→ `MainThreadAudioEngine` → `HybridAudioEngine` → `Media3AudioEngine`/`FfmpegAudioEngine` |
| 视频播放 | `NativeVideoPlayer.kt`（Media3）、`FfmpegFallbackPlayer.kt`（JNI 兜底，https 走随包 `assets/ssl/isrg-roots.pem` CA） |
| **插件音源层** | `QuickJsRuntime.kt`（沙箱：单线程、64MB 限制、Promise 驱动、超时）、`MusicFreePlugin.kt`（CJS 加载/协议校验/搜索/钻取/歌词/getMediaSource 音质降档）、`MusicFreeSource.kt`（SharedPreferences 存储 + filesDir 插件文件）、`JsNetworkBridge.kt`（`__wwHttp` 桥 + `validatePluginUrl`：仅 http/https、拒内网/保留地址）、`MusicFreeSearch.kt`、`StreamSearchSession.kt`、`SourceManagerScreen.kt`（导入/启停/测活 UI） |
| 在线页面 | `OnlineScreens.kt`（在线搜索页/合集详情/榜单 + `MainNavigation`） |
| 插件 polyfill | `android/app/src/main/assets/musicfree/`（axios/crypto-js/qs/he/big-integer/dayjs/cheerio/buffer/pako） |
| 下载 | `DownloadTask.kt`/`DownloadRepository.kt`/`DownloadEngine.kt`（在线走插件解析；百度/夸克直连）+ `DownloadService.kt` |
| 网盘 | `BaiduPanApi.kt`/`QuarkPanApi.kt` + `*StreamProxy.kt`（本机 127.0.0.1 Range 代理）+ `*AuthorizeScreen`（登录浮层在 MusicApp 媒体库浏览分支） |
| 投送 | `Dlna*.kt`、`ChromecastCast.kt`、`LocalMediaServer.kt`、`CastRemote.kt` |
| 歌词 | `LyricParser.kt`、`LyricTtsEngine.kt`（系统 TTS 朗读，与已归档的 TTS 工作台无关） |

`MusicApp.kt` 与 `NativeMusicViewModel.kt` 很大：用 `rg -n "fun 名称"` 定位再读局部，不要通读。

## 3. 关键不变量

1. **不引入服务端**：客户端不得出现自营服务器基址、账号登录、请求签名或应用身份头。在线能力只经插件音源。
2. **插件网络边界**：`JsNetworkBridge.validatePluginUrl` 是硬约束——仅 http/https，拒绝 localhost/环回/私有/保留地址；改动必须带 `JsNetworkBridge` 单测。
3. **音质档位**：`StreamQuality`（low/standard/high/super）是唯一档位模型，解析失败沿 `fallbackOrder()` 自动降档；下载任务存 `pluginValue` 字符串。
4. **倍速是会话级**：`playbackSpeed`/`videoSpeed` 不持久化，每次会话从 1.0 开始，只在播放页调节。
5. **片尾跳过仅本机**：轮询片尾门槛必须带 `!isCasting` 守卫（投送由远端自然结束驱动）。
6. **导航形态**：三 tab（媒体库/在线/我的），起始页 = 本地文件库；媒体库浏览下拉 = 本地媒体/百度网盘/夸克网盘；我的页五区（音源管理/播放列表/下载管理/设置）。
7. **每源独立沙箱**：一个插件一个 `QuickJsRuntime`（专属 HandlerThread）；关闭插件必须 `close()` 释放 JS 对象，禁止跨运行时共享 JSObject。
8. **无障碍**：新列表行遵循既有模式——`combinedClickable` + `clearAndSetSemantics` + `contentDescription` + `customActions`；页面设 `paneTitle`；状态文本用 `liveRegion`。
9. **历史文档**：`docs/release/`、`docs/handoff/` 只描述历史版本；与本文件冲突时以本文件和源码为准。

## 4. 构建与测试

```powershell
# 单元测试（约 270 项）
C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat -p D:\wangwangplayer\android testDebugUnitTest
# Debug / Release 构建
... gradle.bat -p D:\wangwangplayer\android :app:assembleDebug
... gradle.bat -p D:\wangwangplayer\android :app:assembleRelease   # 产物 build/app/outputs/apk/release/app-release.apk
```

- `android/local.properties`：`sdk.dir` 必填；`BAIDU_PAN_*` 可选（缺省走设备码登录）。不要提交密钥。
- `versionCode/versionName`：`android/app/build.gradle.kts`（当前 20261005 / `20261005final`，最终版不再变更）。
- instrumentation fixtures：`python scripts/generate-player-fixtures.py --ffmpeg <host-ffmpeg>`（输出 `build/player-fixtures/`，不提交）。
- 涉及 UI/播放/导入/投送的真机验证按 `scripts/smoke-checklist.md`。

## 5. 维护规则

项目已停止更新；如确需微调：

1. 保持第 3 节不变量，不为单个需求新增抽象层或依赖（插件层唯一外部依赖是 `wang.harlon.quickjs:wrapper-android`）。
2. 新增/修改行为至少带一个能失败的 JVM 单测；完成后跑 `testDebugUnitTest` + `assembleDebug`。
3. 版本记录写入 `CHANGELOG.md`；文档与源码冲突时以源码为准并更新本文件。
4. `archive/` 与 `docs/` 历史内容不要回迁进源码树；临时探针（`scripts/tmp_*`、`.tmp-*`、`build/`）不入库。
