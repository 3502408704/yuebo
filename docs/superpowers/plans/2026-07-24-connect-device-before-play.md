# 先连接设备后播放 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户可先选择并连接 DLNA 设备，再从任何媒体目录选择歌曲投送。

**Architecture:** `DlnaCastController` 保存已扫描并选中的 renderer，但仅在首次歌曲播放时创建 `CastSession`。`PlaybackController` 将“设备已连接”和“正在投送”分离；`HomeScreen` 始终暴露连接入口。

**Tech Stack:** Flutter/Dart、DLNA AVTransport、Flutter Material/Semantics。

## Global Constraints

- 不新增依赖，不新增、修改或运行自动化测试。
- 设备选择只建立逻辑连接，不加载媒体、暂停本机或启动 HTTP server。
- 目录返回只用左上角标准返回按钮和系统返回手势。

---

### Task 1: 分离设备连接与媒体会话

**Files:**
- Modify: `lib/application/dlna_cast_controller.dart`
- Modify: `lib/application/playback_controller.dart`

- [ ] `DlnaCastController.selectDevice(DlnaDevice)` 验证设备来自最近扫描并保存 renderer；`start(track)` 使用已选 renderer 创建首个 session。
- [ ] `PlaybackSnapshot` 增加 `connectedDeviceName`；选择设备只更新该状态。`playFromQueue` 在存在连接设备但尚未投送时调用 `start(track)`，成功后才将 `isCasting` 设为 true。
- [ ] 投送失败保持已连接设备状态、不自动播放本机；返回本机仅在实际 session 存在时可用。
- [ ] 人工检查：连接设备时无声音；之后点歌默认投送。

### Task 2: 恢复入口并简化目录返回

**Files:**
- Modify: `lib/presentation/home_screen.dart`

- [ ] AppBar 始终显示“投送到设备”图标；未连接时打开选择器，已连接时显示设备名称/断开操作。
- [ ] AppBar 标题为“本地音乐”；非根目录使用 `leading: BackButton(onPressed: _goUp)`，删除列表中的返回行。
- [ ] 文件夹选择不触发投送；已连接设备时歌曲激活交给 `PlaybackController.playFromQueue`。

### Task 3: 静态构建和真机安装

- [ ] 仅运行 formatter、`flutter analyze`、debug APK 构建；不运行测试。
- [ ] 覆盖安装 APK，确认冷启动、设备连接和之后点歌投送。

## Plan Self-Review

- Task 1 覆盖连接与播放分离；Task 2 覆盖入口、窗口标题与目录导航；Task 3 覆盖用户要求的真机验收。
