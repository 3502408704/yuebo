# 汪汪播放器 <版本> 发布验收存档

> 用法：发布新版本时复制本文件为 `docs/release/<version>.md`，逐项填写真实结果并贴证据；未完成的验收项必须如实标注"未验收"，不得删除。

## 版本信息

- 发布日期：
- 版本：`<versionName> (<versionCode>)`
- 架构：`arm64-v8a`
- APK：`build/app/outputs/apk/release/app-release.apk`
- SHA-256：
- 发布仓库提交：`<hash>`（`D:\local-music-player-releases`）

## 变更摘要

- （粘贴 CHANGELOG.md 对应版本内容）

## 真实设备验收记录

设备型号 / Android 版本：

### 1. MediaStore 与本机播放

- 授权、扫描：
- 播放/暂停/定位/音量：
- 证据：

### 2. 蓝牙路由

- A2DP 设备显示：
- 切换与系统最终路由回调：
- 重复点按当前路由不跳进度：
- 证据：

### 3. DLNA / UPnP

- 发现、MP3、FLAC、Range：
- 切歌/暂停/定位/音量/返回本机：
- 失败恢复：
- 证据：

### 4. Chromecast

- 发现、连接等待、MP3、FLAC：
- 暂停/定位/音量/切歌/返回本机：
- 超时错误：
- 证据：

### 5. 无障碍

- TalkBack、D-pad/键盘、大字体：
- 设备对话框与播放页：
- 证据：

## 压缩包导入专项验收

- zip / rar4 / rar5 / tgz / tar / gz：
- 去重（同目录同名 + 同大小跳过）：
- 封面 / lrc / cue（含 `.cue.txt`）入库：
- 加密 RAR 密码弹窗（错误码 22/24）：
- 系统"用其他应用打开"可见性（QQ 等）：
- 证据：

## 崩溃与日志

- `logcat` 无本应用 FATAL/ANR：
- `LocalMediaServer` 断开 IOException 已吞掉：
- 证据（logcat 片段）：

## 版本一致性

- `android/app/build.gradle.kts` / `CHANGELOG.md` / `update.json` 三处版本一致：
- 运行 `scripts/verify-release.ps1` 结果：
