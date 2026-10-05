# 月播（Yuebo）· 最终版

> 原名「汪汪播放器」，2026-09-11 起更名为「月播」。**20261005final 为最终版本，项目停止更新。**

月播是一个 Android 原生本地媒体播放器：本地音乐/视频播放为核心，支持用户自行导入 MusicFree 插件音源进行在线搜索与播放。不连接任何自营服务器，不含账号体系。

## 功能

- **本地媒体库**：MediaStore 与外部打开 Intent 导入，打开即进文件库；压缩包导入（zip/rar/tar/gz）、队列、收藏夹、播放历史、断点续播、内嵌封面与歌词。
- **音频内核**：Media3/ExoPlayer 为主，长尾格式（APE/WV/DSF 等）由 FFmpeg 音频引擎兜底；视频 Media3 优先，WMV/RMVB/MPG 等旧格式走 FFmpeg JNI 兜底。
- **MusicFree 插件音源（在线）**：在「我的 → 音源管理」导入插件文件 / 直链 / 订阅 JSON。插件运行于 QuickJS 沙箱（与 MusicFree 官方安卓端同引擎），随包内置 axios / crypto-js / qs / he / big-integer / dayjs / cheerio / buffer / pako require 白名单。支持搜索（歌曲/专辑/歌单/歌手）、专辑/歌单/榜单钻取、歌词、按档位（省流/标准/高音质/最高音质）解析播放与下载。插件网络仅允许 http/https 并拒绝内网/保留地址。
- **百度网盘 / 夸克网盘**：媒体库浏览下拉直达，目录浏览、流播放、下载（OAuth/扫码凭据只存本机）。
- **投送**：DLNA（内置转码内核）与 Chromecast；歌词系统 TTS 朗读、均衡器、睡眠定时、跳过片头片尾（按专辑/文件夹配置，作用于本机播放）。
- **倍速**：播放页调节，作用于当前播放会话，不持久化。

## 构建

无 Gradle wrapper，需本机 Gradle 9.x + JDK 17 + Android SDK（compileSdk 36）：

```powershell
gradle.bat -p <仓库>\android :app:assembleRelease     # 产物 build/app/outputs/apk/release/app-release.apk
gradle.bat -p <仓库>\android testDebugUnitTest        # 单元测试
```

`android/local.properties` 需含 SDK 路径；百度网盘 `BAIDU_PAN_*` 键值可选（缺省时网盘登录走设备码流程）。

## 结构

- `android/` — 客户端唯一源码树。入口：`MainActivity.kt` → `MusicApp.kt`（Compose UI/导航）→ `NativeMusicViewModel.kt`（状态与业务）→ `MusicApplication.kt`（引擎与服务装配）。
- `android/.../QuickJsRuntime.kt`、`MusicFreePlugin.kt`、`MusicFreeSource.kt`、`JsNetworkBridge.kt`、`SourceManagerScreen.kt` — 插件音源层。
- `android/app/src/main/assets/musicfree/` — 插件 require 白名单 polyfill。
- `archive/tts-workbench/` — 已退役的 AI TTS 工作台源码存档（不参与编译，仅留档）。
- `docs/release/`、`docs/handoff/release-runbook.md` — 历史版本验收与发版记录。

工程约束、模块边界与不变量见 [`AGENTS.md`](AGENTS.md)；版本变更见 [`CHANGELOG.md`](CHANGELOG.md)。

## 许可与说明

- 月播自有代码采用 [Apache License 2.0](LICENSE) 授权。
- 仓库内 FFmpeg / mbedtls / unrar 等第三方组件许可见 `android/app/src/main/cpp/` 与 `THIRD_PARTY_NOTICES.md`。
- MusicFree 插件协议来自 [maotoumao/MusicFree](https://github.com/maotoumao/MusicFree) 的公开插件格式；插件脚本由用户自行导入，其内容与可用性由插件作者维护，与本项目无关。
