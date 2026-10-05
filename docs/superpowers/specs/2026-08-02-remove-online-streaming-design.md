# 移除在线流媒体设计

## 目标

从当前主分支移除在线流媒体和 JS 音源管理功能，并使 Android 模块重新构建通过。

## 保留范围

- 本地 MediaStore 曲库与文件导入。
- 本机 BASS 播放、蓝牙路由、DLNA、Chromecast、更新检查和设置。
- 本地播放列表、队列、均衡器和睡眠定时器。

## 移除范围

- “在线”主页签、在线搜索界面和在线音质选择。
- “我的”中的音源管理入口和页面。
- MusicFree/LX 插件、QuickJS 运行时、内置流媒体 API、HTTP 播放代理及其资产、测试和文档。
- `MusicUiState`、`NativeMusicViewModel`、`MusicApplication` 与持久化队列中仅服务在线流媒体的状态、调用和恢复逻辑。
- QuickJS Gradle 依赖与仅为流媒体测试添加的测试依赖。

## 实现边界

- 删除已标记的流媒体文件；不删除本地导入、归档解压、权限或投送相关文件。
- 主导航只保留“曲库”和“我的”。
- 不为未来妖狐 API 预留接口、配置或空白 UI；后续接入另行设计。
- 不清理无关的未跟踪文件、构建产物或用户工具文件。

## 验收标准

- 项目中不再引用 `MusicFree`、`LxMusic`、`QuickJs`、`StreamingProxyServer`、`OnlineScreen`、`SourceManagerScreen` 或 `StreamQuality`。
- 不再显示在线音乐或音源管理入口。
- `rtk git diff --check` 通过。
- 在 `D:\\musicplayer\\android` 运行 `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"` 通过。
