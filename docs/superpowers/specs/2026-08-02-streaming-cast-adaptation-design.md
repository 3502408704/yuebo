# 流媒体投送适配设计

## 目标

让网易云及后续妖狐平台的在线曲目能够使用现有 DLNA/Chromecast 投送，同时保留本机流媒体播放、定位和回切能力。所有在线投送均使用平台返回的直接播放地址；本地媒体仍由手机上的 `LocalMediaServer` 提供。

## 范围与约束

- 不新增 UI，不改变底部导航、设备选择或现有中文语义。
- 本地 `content://` 曲目继续走 `LocalMediaServer`，支持 Range 和现有回退。
- 在线曲目包括网易云条目和通用 `StreamTrack` 条目；URL 只在应用进程内缓存，应用结束即失效，队列持久化不保存 URL。
- 不把曜狐密钥写入文档、源码或日志；解析仍由现有 `YaohuApiClient` 完成。
- 不新增依赖，不使用 Chromecast 私有 API。

## 方案

### 在线地址解析

在 `NativeMusicViewModel` 中统一形成投送源：

1. 网易云按曲目 ID 和播放音质读取 `NeteaseResolutionCache`。
2. 缓存缺失时使用现有 VIP/VIP/免费/免费顺序解析，并写回缓存。
3. 通用 `StreamTrack` 优先读取 `streamUrlByTrackId`；若条目仍带有可解析的原始对象，则沿用已有音源解析路径并缓存结果。
4. 本地曲目才调用 `mediaServer.start`。

投送源同时携带 URL、MIME 类型、时长和“是否可刷新”标记，调用方无需根据 URI 猜测来源。

### DLNA 与 Chromecast

- `startDlna` 和 `startChromecast` 在暂停 BASS 前解析投送源。
- 在线源直接传给现有控制器；本地源保持原流程。
- 在线源的首次 `load`/播放失败时只清理对应缓存并重新解析一次，再次失败恢复本机，不自动切换队列。
- 本地源失败不重复启动服务，沿用现有恢复行为。

### 返回本机

从远端取得位置后，在线曲目调用 `player.playUrl`，本地曲目调用 `player.play`。在线缓存 URL 在本机创建失败时只刷新一次；成功后继续原位置并恢复进度轮询。所有路径都停止不再需要的 `LocalMediaServer`/代理。

### 传输控制

现有远程暂停、继续、定位、音量和进度轮询保持不变。倍速设置在 DLNA 投送时通过公开的 `Play` 速度参数发送，在 Chromecast 投送时通过公开的 `RemoteMediaClient.setPlaybackRate` 发送；请求失败时保留原倍速并反馈操作失败。

## 错误与状态

- 地址解析错误、投送加载错误和回切错误都留在当前曲目，不触发 `advanceAfterStop`。
- 重试只针对在线地址缓存失效，最多一次；重试期间不播报接口细节。
- 失败时停止已启动的本地服务、恢复 BASS，并使用现有“操作失败，请重试。”状态。

## 测试与验收

- 单元测试验证：本地/在线源选择、网易云缓存命中与强制刷新、在线 URL 回切使用 `playUrl` 的决策。
- `rtk git diff --check` 无输出。
- `:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:assembleRelease` 通过。
- 设备冒烟：网易云曲目播放后分别投送 DLNA/Chromecast，验证首次加载、暂停/继续、定位、音量、返回本机和缓存失效回退；本地曲目投送回归不受影响。
