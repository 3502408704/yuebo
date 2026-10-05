# libVLC 统一播放内核迁移方案（2026-09-06）

> 状态：**已取消（2026-09-06 用户决定，勿再实施）**。本文件保留作架构决策证据。
> 原计划：引擎 libVLC（LGPLv2.1+）单引擎取代「BASS 音频 + ExoPlayer 视频 + FFmpeg 兜底」。
> 取消原因：Phase 1 Spike 实测 `libvlc.so` 单 ABI 压缩后 43.95MB（arm64 单 ABI APK ≈46MB，现主工程 ≈12MB，
> 差距 +34MB 体积不可接受），且 BASS 已在主工程直接下线、FFmpeg 兜底重编扩容，原双栈问题已消解。
> 现行架构 = Media3/ExoPlayer 音频视频主内核 + FFmpeg 兜底（见 AGENTS.md §0 与本文件「更新记录」末条）。

## 更新记录

- **2026-09-06 用户拍板取消 libVLC（方案作废）**：不再实施 libVLC 迁移；`android-vlc-spike/` 不入库、
  工作区原样保留待清理。主工程改走并已提交「Media3 音频内核（BASS 整体下线）+ FFmpeg 兜底扩容」。
- **2026-09-06 Phase 1 Spike 启动**：在仓库根新建独立工程 `android-vlc-spike/`（不依赖、不修改 `android/`
  主工程）；依赖探测选定 `org.videolan.android:libvlc-all:3.7.5`（截至 Maven Central 元数据 2026-07-08 的
  最新 3.x stable；4.0 线仍为 `4.0.0-eap29`，仅评估不默认上）。AGENTS.md §0 已挂指针。判据执行结果按
  方案 §5.3 格式回填「Spike 结果」节。
- **2026-09-06 binding 核验与体积基线（3.7.5）**：`javap` 确认——动态 EQ 全量透出（`MediaPlayer.setEqualizer` +
  `Equalizer.create/createFromPreset/getAmp(i)/setAmp(i,float)/setPreAmp/getPresetCount`）；变速 `setRate(float)`、
  音量 `setVolume(int)`、seek `setTime/setPosition`、音轨枚举 `getAudioTracks/getVideoTracks/getSpuTracks`、
  事件（Opening/Buffering/Playing/Paused/EndReached/EncounteredError 等）齐备；`attachViews(VLCVideoLayout,
  DisplayManager, useTextureView, disableVideo)` 中 DisplayManager 字节码无判空、可传 null 实测。
  体积基线：arm64-v8a `libvlc.so` 压缩后 **43.95MB**（`llvm-strip --strip-unneeded` 无减小，非符号膨胀，
  系模块整体静态打进单 so），双 ABI(arm64+x86_64) APK ≈100MB，arm64 单 ABI ≈46MB（现主工程 release ≈12MB，
  差距 +34MB，是 go/no-go 关键输入；若无法接受需评估 VLC 4.0 模块化或自编译裁剪子集）。
  首次 adb 装机被设备侧拦截（MIUI `INSTALL_FAILED_USER_RESTRICTED`），待用户开启「USB 安装」后复测。
  Spike 测试台 MainActivity 已具备：URL/本地文件(SAF)/UA/Referer/变速滑条/音量/EQ 预设轮换/seek/事件耗时日志/
  VLCVideoLayout 视频挂接与音轨枚举。

---

## 1. 背景与目标

现状是**两套播放内核**并维护：

| 用途 | 内核 | 载体 |
|---|---|---|
| 音频 | BASS（解码）+ 自建 AudioTrack + Kotlin 采样级 DSP | `NativeBassPlayer.kt`（988 行）+ `com.un4seen.bass` + 8 个解码插件 + `bass_fx` + 自带 openssl |
| 视频 | Media3/ExoPlayer + 原生 FFmpeg 兜底 | `NativeVideoPlayer.kt` / `ExoPlayerVideoEngine` + `FfmpegFallbackPlayer` + `src/main/cpp/ffmpeg_*` |

迁移动机（用户提出）：
1. BASS 流媒体能力弱（带不了自定义 UA/请求头、预缓冲/超时行为糙）且**商业授权成本高**（每个 OS 平台单独收费，与收会员费的商业分发冲突）；
2. 同时维护两套内核成本高；
3. 视频需要"更专业的播放器"级能力（字幕、更广协议/格式、更稳的缓存）；
4. 多格式问题交给 codec 完备的引擎，并期望**砍掉 FFmpeg 兜底自研路径**。

目标：**单引擎（libVLC）统一音频+视频**，删除 BASS 全家与 FFmpeg 兜底，保留应用自有的 UI/状态/投屏/在线媒体层，且不劣化听书核心体验（TalkBack + 变速不变调）。

**重要纠偏（调查结论，非预期）**：
- 投屏不会因换引擎"免费"。仓库**没有 Chromecast 实现**（仅文档/注释提过）；DLNA 是纯遥控（URL→远端渲染器），与本地内核无关、换引擎不受影响；唯一复用本地解码的投屏是 **AirPlay 音频（BASS PCM→RAOP）**，换引擎后需单独找裸 PCM 出口或下线。
- "字幕能力" VLC 有，但 App 目前**没有字幕 UI/音轨选择 UI**，需要新做。

---

## 2. 选型论证与许可

### 2.1 候选对比

| 候选 | 许可 | 商业可用性 | 结论 |
|---|---|---|---|
| **mpv / mpv-android** | 核心 GPLv2+（部分 LGPL 混编）；mpv-android 本体 GPLv3，且不以 AAR 提供 | 收会员费的闭源分发不可行，会强制开源整个 App | **排除** |
| **libVLC** | 引擎 **LGPLv2.1-or-later**；`org.videolan.android:libvlc-all` 在 Maven Central 活跃维护（2026-07 时 4.0 线到 `4.0.0-eap29`，157 个版本） | 闭源商业允许，义务见 2.2 | **选定** |
| Media3/ExoPlayer 统一 | Apache-2.0 | 最宽松、体积最小、变速不变调原生（pitch=1） | 仅作备选（视频能力与格式覆盖弱于 VLC） |

版本策略：**spike 依赖探测时以 Maven Central 实际标为 stable 的最新 3.x 系为准打底**；4.0 线到 2026-09 仍是 EAP（`4.0.0-eap*`），API 大改（如 VLCVideoLayout 渲染体系），**除非有硬需求否则不上 EAP**。精确版本号以探测结果回填本节。

### 2.2 LGPL 义务清单（需法律复核，先按常规执行）

1. 发布物内附 LGPLv2.1（或 v3）许可文本 + libVLC 源码可获取途径/书面要约；
2. 保持**动态链接**形态：以官方 aar 的 `.so` 随 APK 分发（`System.loadLibrary` 运行时加载），禁止静态合并——保证用户可替换/重链接 libVLC；
3. 提供替换说明（换 `.so` / 重链接指引），不要求开源我们自己的代码；
4. 在应用内「关于/许可」页挂许可入口（TalkBack 可读）。

---

## 3. 现状事实基线（2026-09-06 耦合调查，行号可导航）

### 3.1 音频栈：真正的迁移成本在"采样级 PCM 层"，不在解码

BASS 只解码；**PCM 输出与采样级效果全在 Kotlin**（`NativeBassPlayer.kt`）：

| 能力 | 实现位置 | 性质 |
|---|---|---|
| 解码 | `play` L213 / `playUrl` L244 + 插件名单 L198-211 | BASS |
| 变速不变调 0.5–2.0 | `setSpeed` L418 + `BASS_FX_TempoCreate` L227/L257、tempo 属性 L421 | BASS FX 原生 |
| 淡入/淡出/取消 | `startFadeIn` L745 / `fadeOut` L402 / `cancelFade` L758 / `fadeMultiplier` L763 | **Kotlin 在 AudioTrack 音量增益斜坡** |
| 静音跳过 | `startOutputLoop` L787-943 + `isSilentPcm` L971 | **Kotlin 输出循环扫 PCM** |
| 响度归一化 + 软限幅 | `applyLoudnessGain` L953 / `softLimit` L962；ReplayGain/R128 顶层纯函数 L54-87 | **Kotlin 增益/限幅** |
| 均衡器 10 段 + preamp | `setEq` L461 + DX8 PARAMEQ L496-510 | BASS DX8 原生 |
| 混响/合唱/回声/镶边/压缩 | `setEffects` L512 + DX8（L561/575/592/607/544） | BASS DX8 原生 |
| 输出路由 | `setPreferredDevice/routedDevice` L429/L444 | 自建 AudioTrack |
| AirPlay PCM 直送 | `airPlayInputFormat` L451 / `setRemotePcmSink` L456 | 复用 BASS 解码 |
| 音频焦点 | L120-133/L681-709/L772-784 | 长在播放器内部 |
| 内嵌歌词/标签读取 | `lyrics()` L353/L625-631 | BASS |

DSP 调用面集中在 `NativeMusicViewModel.kt`：init 回放 L958-967；起播应用 L4195-4609；变速/音量投送分流 L5572-5695；效果 L6825-6893。UI 面板本身只走 VM 方法（`MusicApp.kt` EQ 滑杆 L7474-7550、效果 L7589-7601），契约可不动。

### 3.2 视频栈

- `VideoEngine` 接口已抽象（`NativeVideoPlayer.kt` L30-71）；`ExoPlayerVideoEngine` L77（Media3，带请求头+MIME `play` L179、HLS 512MB 磁盘缓存 L84-103、倍速 L223）；
- **无字幕/多音轨/截图代码**；
- FFmpeg 兜底 `FfmpegFallbackPlayer.kt` L20-266（自建 AudioTrack L210），格式判定 `isFfmpegFallbackFormat` L422 / `shouldFallbackToFfmpeg` L428；VM 分派/绑定 `bindFallbackEngine` L4371/L4399/L4449/L4795、L1078/L1121；
- UI 渲染：`MusicApp.kt` 直接取 `app.videoPlayer.player`（ExoPlayer 实例）L4788/L5202-5205 → **Surface 宿主必须随引擎换**。

### 3.3 投屏真实情况

| 路径 | 依赖本地内核？ | 换引擎影响 |
|---|---|---|
| DLNA 音频/视频（`DlnaDiscovery`/`DlnaRendererController`/`LocalMediaServer`） | 否（URL→远端渲染器，本地只停自己取续播位置） | 不受影响 |
| AirPlay 音频（`AirPlayController.kt` + `setRemotePcmSink`） | **是**（本地解码 PCM→RAOP） | 需裸 PCM 出口或下线 |
| Chromecast | 代码不存在 | —（真 Chromecast 需另接 Google Cast SDK，与引擎无关） |

### 3.4 会保留、与引擎解耦的层

- MediaSession（VM L796-828）、`MediaPlaybackService` 通知、`PlayerWidgetProvider`：**状态经 VM 中转**，回调转发 VM 方法，不直接调 BASS/ExoPlayer（个别取 position/volume），基本可复用；
- `AudioOutputController.kt`（AudioManager 枚举蓝牙设备）与内核无关；
- 全部在线服务/网盘代理/下载/歌词解析/导入/封面/更新器：引擎无关。

### 3.5 测试与可删资产

- 测试：`android/app/src/test` 37 个 JVM 文件，**无人实例化播放器**，全测与播放器同文件的顶层纯决策函数（`perceptualVolume`/响度公式/`migrateEqBands`/`AudioEffects`/`isFfmpegFallbackFormat` 等，约百条播放相关断言）→ **这些纯函数必须脱离播放器文件、随新分层保留**，否则测试基线崩塌。
- 可删：`libbass.so/bass_fx` + 插件（flac/aac/alac/ape/opus/wv/dsd/webm）+ 自带 openssl、`BASS.java/FX`、`FfmpegPlayerJni` + `main/cpp/ffmpeg_player`。`main/cpp` 仅留 unrar 解压。发布 ABI 现为 arm64-v8a（debug 加 x86_64），libVLC 均覆盖。

---

## 4. 目标架构（统一后，一次性视图）

```
Compose UI（MusicApp/VideoPlayerScreen/设备/播放页 DSP 面板）   —— 契约不变
        │ VM 方法（音量/变速/EQ 档/播放控制/投送开关…）
NativeMusicViewModel（状态机、MediaSession/通知/部件、投送控制、起播策略）
        │ 统一引擎契约
┌───────────────────────────────────────────────────────────────┐
│ engine/PlayerEngine（新建，抽象本地播放能力 + 事件）            │
│  ├─ VlcAudioEngine（libVLC MediaPlayer 音频）                   │
│  └─ VlcVideoEngine（实现既有 VideoEngine 接口 + attachSurface） │
│  （过渡期 NativeBassPlayer / ExoPlayerVideoEngine 并存做 A/B）  │
└───────────────────────────────────────────────────────────────┘
        │
libVLC（arm64-v8a / x86_64），解码 + aout + 字幕 + 协议
```

设计要点：
1. **抽引擎无关辅助层**（新建 `engine/` 或沿用现有包，视 spike 后定）：`perceptualVolume`、响度/增益计算、ReplayGain/R128 解析（改由 Android `MediaMetadataRetriever` 等引擎无关渠道读标签，替代 BASS `lyrics()`）、淡出音量阶梯策略、EQ 档位归一化——**这些是现有 37 个测试文件的守护对象，先迁移后删旧**。
2. **音频焦点统一外置**为 `AudioFocusManager`（VM 层），替代 BASS 内部焦点与 ExoPlayer `handleAudioFocus=true` 两套。
3. **AirPlay/自定义输出设备**：见 §5/§7，spike 判定后决定去留。
4. 视频渲染：`VideoPlayerScreen` 改为 AndroidView 承载 libVLC 提供的 Surface（版本线决定 TextureView/VLCVideoLayout），不再外泄 `ExoPlayer.player` 类型。
5. 字幕/音轨选择 UI 属**新增**，独立排期，不阻塞主体迁移。

---

## 5. DSP 取舍清单（逐项，用户口径：保最小必需集）

> 统一后的音频输出走 libVLC 内部 aout（Android AudioTrack），**aar 形态无法自编采样级滤镜模块**，
> 因此 BASS 架构里 Kotlin 采样级 DSP 全部失去"样本级"能力，只能上移到策略层近似或砍。
> 每项给出：现状 → VLC 可行方案 → 代价/风险 → **Spike go/no-go 判据**。

| # | 能力 | 现状实现 | VLC 方案 | 代价/风险 | 判据 / 决策 |
|---|---|---|---|---|---|
| 1 | **变速不变调**（听书刚需） | BASS_FX TempoCreate | `MediaPlayer.setRate(0.5–2.0)` + scaletempo/time-stretch（`--audio-time-stretch`，桌面默认开） | Android/网络流下 time-stretch 是否生效未知 | **go 关键项**：真机 0.5×/1.5×/2.0× 用户耳测音高不变 |
| 2 | **均衡器 10 段 + preamp + 预设** | BASS DX8 PARAMEQ ×10 | 桌面 C API 有 `libvlc_audio_equalizer_*`；Android binding 是否透出**未确认** | 若 binding 不透出：无动态 EQ API | **go 关键项**：绑定源码搜 `setEqualizer/Equalizer`；无则**上报复议**（EQ 属必需集，需给替代方案：a. 保留静态 preset 重建媒体——体验差；b. 系统 5-band Equalizer 需要 aout 暴露 session id——未证实；c. 单独评估） |
| 3 | **淡入淡出**（切歌） | Kotlin AudioTrack 音量斜坡（样本级） | VM 层用 `setVolume(0..100 int)` 阶梯淡出；VLC 无公开采样级 fade | 每步可闻台阶/爆音风险；粒度 = 音量整数档 | **主观判据**：真机听评是否可接受；不行则降级为"淡出截断 + 短淡入"或暂砍（后补） |
| 4 | **响度归一化**（ReplayGain/R128→软限幅） | 标签增益（顶层纯函数，引擎无关）+ Kotlin 软限幅/LUFS 估测 | 增益部分改 VM 音量补偿（引擎无关，**可完整保留纯函数与测试**）；限幅/LUFS 实时监测无 VLC 等价 | 失去实时限幅 → 极端动态文件可能爆音 | 增益上移保留；软限幅**砍**（记入后补）；超响文件行为真机验证 |
| 5 | 静音跳过 | Kotlin 扫 PCM | VLC 无公开 skip-silence filter | 采样级，无法近似 | **砍（后补）**，听书档需求单独复议 |
| 6 | 混响/合唱/回声/镶边/压缩 | BASS DX8 | — | — | **砍**（用户已确认；压缩器如 VLC 内置静态滤镜可用则留为可选） |
| 7 | AirPlay 音频投送 | BASS PCM→RAOP | 桌面有 `libvlc_audio_set_callbacks` 裸 PCM；Android binding **未确认** | 无裸 PCM 出口则 RAOP 断粮 | 无则 **AirPlay 发送端下线（v1）**，记录回补条件 |
| 8 | 输出设备手动指定 | `setPreferredDevice`（自建 AudioTrack） | VLC aout 跟随系统默认路由，**无单设备定向 API** | 现有"设备页点选连接"工作流丢失 | 改"展示当前路由 + 跳系统蓝牙设置/系统路由切换"；TalkBack 走查通过为判据 |
| 9 | 音量感知曲线 | `perceptualVolume` 纯函数 | 保留纯函数，映射到 VLC `setVolume(0..100)`（VLC 音量本身按 aout 对数实现，映射需真机校准） | 刻度精度 | 保留曲线 + 校准；现有 `VolumeCurveTest` 不删 |
| 10 | 内嵌歌词/标签 | BASS `lyrics()` L353 | libVLC meta 读取能力未知 | 标签读取功能丢失 | 独立用 `MediaMetadataRetriever` 承载标签读取（引擎无关）；内嵌 LRC 场景冒烟 |

---

## 6. 分阶段路线

### Phase 0（本次）：决策落档 ✅
引擎 libVLC、许可路线、DSP 口径、本文档。**0.6.9_beta9 RC 验收不受影响**（纯新增文档，未触碰构建/依赖）。

### Phase 1：Spike（改代码第一站，独立工程/分支，不动 android/ 主工程）
- **落点**：仓库新增独立实验工程（如 `android-vlc-spike/` 独立 Gradle 工程 + libvlc-all，独立包名与 App），与 RC 构建完全隔离；
- **判据**：跑通 §5 表内全部 **go 关键项/主观判据** + 下面清单，产出「go/no-go」：
  1. 依赖/版本探测（3.x stable vs 4.0-eap）与 ABI、APK 体积增量（基线现状 release APK）、R8/混淆规则；
  2. 本地格式矩阵真机验证（用用户真实样本）：flac/ape/alac-m4a/wv/opus/ogg/webm；若存在则 dsd；wmv/rmvb/mpg（吃掉 ffmpeg 兜底）；mp3 帧同步乱序样本；
  3. 在线流：签名 URL 无 Range 冷起流首包→出声 ≤ 现状（≈1.5~2s）；缓冲中 seek；三型源（服务端签名代理 / 需 Referer / 需 UA）逐个用 VLC 媒体 options 验证（`:http-user-agent`/`:http-referrer`/`:http-cookies` 是否够用）；
  4. 变速不变调 / EQ 暴露 / 淡出听感 / 音量曲线校准（§5 判据项）；
  5. 音频焦点 + 蓝牙：A2DP 中途切换、来电暂停恢复；焦点外置设计落地性；
  6. 后台 + MediaSession/通知/部件事件流正确性；
  7. 视频 parity：seek 精度、倍速、HLS 缓存、字幕轨枚举可行性；
  8. LGPL 义务材料占位（许可文本页 + 替换说明模板）；
  9. **go 汇总**：① 必须全过（变速、EQ、流式起播、焦点/蓝牙、事件流）；② 必须全部有结论（AirPlay 去留、fade 听感）；③ 记录未覆盖项与后续测试。
- **失败回退**：Spike 独立工程，不污染主工程；go/no-go 不过则本方案冻结，回 BASS+ExoPlayer 现状或复议 Media3 统一路线。

### Phase 2：统一契约 + 视频先行迁移
1. 抽取引擎无关辅助层（§4 要点 1），保住 37 个测试文件的守护函数（逐文件搬迁，先移后删，跑绿 `testDebugUnitTest`）；
2. `AudioFocusManager` 外置（VM 层），删除 BASS 内部焦点与 ExoPlayer 自动焦点；
3. `VideoEngine` 接口保持，新增 `VlcVideoEngine` + Surface 宿主改造 `VideoPlayerScreen`；切换后验证：本机视频全格式、B 站/网盘/影视源播放、倍速、seek、DLNA 视频投送（控制面不动）、**ffmpeg 兜底判定与 C 代码下线**；
4. 期间主分支随时可回退：引擎选择配置化，两套并存。

### Phase 3：音频迁移
1. `VlcAudioEngine` 实现统一 `PlayerEngine`（音量/变速/seek/事件/焦点协作）；
2. VM 调用面从 BASS 迁到契约（L958-967/L4195-5695/L6825-6893 全部对齐），状态/持久化键不变；
3. 逐项按 §5 决策执行 DSP 上移/砍/后补；真机听书走查（变速、EQ 若留、淡出、超响文件）。

### Phase 4：清理 + 合规 + 发布
1. 删 BASS 全家（含插件与自带 openssl）、`BASS.java/FX`、ffmpeg 兜底 C 代码、`FfmpegPlayerJni`；`cpp/` 留 unrar；
2. 更新测试（改造引用 `com.un4seen.bass` 常量的用例，如 `NetworkStreamPlaybackTest` L3）；
3. LGPL 义务材料入应用；APK 体积增量记录进 `docs/release/<版本>.md`；
4. 按 `AGENTS.md` §3/§4 全套门槛（编译、全量单测、真机冒烟清单、无障碍自审、发布四件套）收口。

> 全程约束：每次代码改动遵守 `AGENTS.md` §4 流程；**不得在 0.6.9_beta9 验收窗口内改动 android/ 主工程依赖**；commit/push 只在用户明确指示时进行。

---

## 7. 风险登记表

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| 1 | libVLC Android 不透出动态 EQ（§5#2） | 高 | go 判据前置；备选路径预研（系统 Equalizer/session id、静态 preset、复议砍 EQ） |
| 2 | 变速 time-stretch 在 Android/流媒体上无效或劣化 | 高 | spike 真机耳测；预案：接受变速轻微变调 / 保留 BASS 仅网络流（破坏统一，慎用） |
| 3 | AirPlay 无裸 PCM 回调 → 发送端下线 | 中 | 明确下线范围与回补条件；DLNA 为主投送不受影响 |
| 4 | 手动输出设备点选丢失 | 中 | 改系统路由方案 + 无障碍走查 |
| 5 | 淡出/响度失去样本级精度，听书体感变化 | 中 | 主观判据前置；增益上移保测试；后补清单 |
| 6 | 采样级 DSP（静音跳过等）砍掉后听书体验下降 | 中 | 用户已点头砍（后补）；上线前真机听书走查留证 |
| 7 | APK +20~40MB / 冷启动 libVLC init 耗时 | 中 | spike 量基线；debug/release ABI 控制 |
| 8 | 视频 Surface 宿主重构引入渲染回归 | 中 | VideoEngine 抽象先行、两套并存 A/B |
| 9 | 37 个纯函数测试因代码搬迁失守 | 中 | 先搬迁后删除、每步跑绿 |
| 10 | LGPL 合规疏漏 | 中 | §2.2 清单 + 法律复核；不作为 go 阻断项但发布前必须完成 |
| 11 | 4.0-eap API 变动拖累工期 | 低 | 默认 3.x stable，EAP 仅评估 |

---

## 8. 验收口径（映射 AGENTS.md §3 质量门槛）

- **改动门槛**：每次改动的编译 + `git diff --check` + 最小实现；
- **冒烟门槛**：真机（TalkBack）起播、变速、EQ（若保留）、焦点/蓝牙、DLNA 投送、听书整段无异常；logcat 无本应用 FATAL/ANR；`LocalMediaServer` IOException 吞掉；
- **无障碍门槛**：按 `android-native-accessibility` 自审（字幕/音轨新 UI、设备路由新 UI、许可页）；
- **发布门槛**：体积增量、许可材料、CHANGELOG/update.json、`verify-release.ps1`、验收证据入 `docs/release/`；
- **回归门槛**：本机播放、DLNA、蓝牙路由、TalkBack 关键路径全量回归。

## 9. 待办与后续提醒

- [ ] Spike 工程搭建与判据执行（Phase 1）——动手改代码的第一站，由下一次会话启动；
- [ ] 动手前在 AGENTS.md §0 增一行指针并更新本文件「更新记录」；
- [ ] LGPL 合规材料与法律复核（不阻塞 spike，阻塞发布）；
- [ ] 字幕/音轨选择 UI 需求单独排期（本方案不阻塞其立项，也不被其阻塞）；
- [ ] 在线影视/网盘/B 站相关源在视频引擎切换后按现状源逐一回归（源侧不动）。
