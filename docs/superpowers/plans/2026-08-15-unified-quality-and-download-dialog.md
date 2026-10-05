# 统一音质体系 + 下载音质弹窗 实施计划

## 目标
1. 非喜马拉雅平台统一为「标准/极高/无损」三档，删除无效高档位。
2. 播放音质与下载音质聚合为全局设置（各一个）。
3. 喜马拉雅固定最高音质（超清），不可选、不弹窗。
4. 下载在线单曲/专辑时弹窗选择下载音质（仅本次生效）。

## 阶段
### A. 音质模型核心（YaohuOtherMusicApi / YaohuNeteaseApi / ShyMusicApi / OnlineTrackResolver）
- 新增 `OnlineQuality(Standard/High/Lossless) : YaohuQuality, ShyQuality`，apiValue 为规范值、qnIndex=0/1/2。
- 新增 `yaohuApiValue(platform, quality)`：QQ mp3/hq/flac；Kugou 128/320/flac；Kuwo standard/exhigh/lossless；Netease standard/exhigh/lossless。
- 新增 `onlineQualityFromApiValue(platform, value)` 反向归一到三档。
- 删除 QqQuality/KugouQuality/KuwoQuality/NeteaseQuality 及 ShyQq/Wyy/Kg/Kw 四套；目录 resolve 改收 OnlineQuality；actualQuality 改为 OnlineQuality。
- Ximalaya 保留 XimalayaQuality，仅用 Ultra。

### B. ViewModel 聚合（NativeMusicViewModel.kt）
- 状态删 10 个按平台字段 → playbackQuality/downloadQuality（OnlineQuality）。
- onlineQuality(platform, download)：喜马拉雅→Ultra，其余→全局。
- setter 收敛为 setPlaybackQuality/setDownloadQuality；新键 playback_quality/download_quality，从旧网易云键迁移一次；默认 播放=High、下载=Lossless。
- 网易云播放路径、findNeteaseYaohuMatch 等改用 OnlineQuality。
- 新增下载弹窗：requestDownloadOnline/requestDownloadOnlineAlbum → state.downloadQualityPrompt；confirm/cancel；喜马拉雅直接 Ultra 入队不弹窗。

### C. UI（MusicApp.kt）
- OnlineQualitySettingsMenu 改为全局「播放音质」「下载音质」选择器；喜马拉雅显示固定超清。
- SettingsScreen 回调收敛；删除 OnlineQualitySetting/QualityPickerData/NeteaseQualityMenu 死代码。
- 新增下载音质 AlertDialog（观察 downloadQualityPrompt）。

### D. 测试适配
- NeteaseQualityTest → OnlineQuality 映射测试；ShyMusicApiTest/NeteaseResolutionCacheTest/OnlineSearchPaginationTest/YaohuOtherMusicApiTest 等改 OnlineQuality。

### E. 验证
- compileDebugKotlin / testDebugUnitTest / assembleRelease 通过。
