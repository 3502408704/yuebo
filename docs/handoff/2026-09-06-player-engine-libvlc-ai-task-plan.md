# libVLC 播放内核迁移——执行任务书（供执行 AI）

> 起草：主 AI（2026-09-06）。用途：交给**另一个 AI** 在本仓库独立执行，不依赖起草方记忆。
> **开工前必读（按序）**：① 本任务书；② `AGENTS.md`（仓库唯一总纲，v2，先通读 §0-§9）；③
> `docs/handoff/2026-09-06-player-engine-libvlc-migration-plan.md`（方案/决策/调查基线，本任务书的依据）。
> 三份冲突时：AGENTS.md > 方案文档 > 任务书；AGENTS.md 声明冲突时以 AGENTS.md 为准。

---

## 0. 一句话任务

把 App 的本地播放内核从「BASS 音频 + ExoPlayer 视频 + FFmpeg 兜底」双栈迁移为 **libVLC 统一单引擎**，
**删除 BASS 全家与 FFmpeg 兜底**，保留应用自有 UI/状态/投送/在线媒体层，不劣化听书核心体验（TalkBack + 变速不变调）。
迁移分 Spike → 分层重构 → 音频迁移 → 清理合规 四阶段；**Spike go/no-go 不通过则冻结并回报**。

## 1. 硬性约束（违反 = 返工，不可协商）

1. **所有 shell 命令以 `rtk` 开头**（rtk 在 PATH；说明见 `C:\Users\35024\.codex\RTK.md`）。若执行环境无 rtk，
   降级为等价命令并在回报里注明。本机无 gradlew，直调发行版：
   `rtk cmd /c "C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat -p <工程目录> <任务>"`
2. 文件修改一律用编辑/写入工具；**禁止** shell 重定向、`sed`、脚本改写文件。`git diff --check` 必须干净。
3. **不自动 commit/push**；不创建 worktree/远程；工作树既有未提交修改与未跟踪文件**原样保留**，
   不做 reset/checkout/递归删除。
4. Android 构建只走 ASCII 路径：仓库根 `D:\wangwangplayer`。
5. **密钥绝不写入**代码/文档/测试/日志/APK：妖狐、百度网盘四组、shy 等均在服务器 `.env` 与
   `android/local.properties`（git 忽略）。本地播放迁移不得触碰这些密钥与在线源凭据逻辑。
6. 新增依赖（libVLC 等）已获用户许可（libVLC LGPLv2.1+）；其它新增第三方/原生依赖先查许可并回报，不自行引入。
7. 无障碍第一优先级：任何用户可见改动自审 `android-native-accessibility` 清单（中文语义、48dp、对比度、TalkBack 可感知）。
8. 音频/视频**主观听感判据（变速不变调、EQ、淡出、响度）必须由用户本人裁决**：执行 AI 只收集证据、
   给出结论与备选，产出「待用户裁决」清单，**不得自行宣布通过或砍功能**。
9. 0.6.9_beta9（RC 6099）验收窗口内，**不得改动 `android/` 主工程依赖/构建**；Spike 走独立工程（Phase 1）。

## 2. 仓库环境与工程现状（基线，2026-09-06）

- 仓库根 `D:\wangwangplayer`；模块：`android/`（Kotlin+Compose，包名 `com.example.local_music_player`）、
  `server/`、`admin-tool/`。服务端与本任务无关。
- APK 输出被 Gradle 9 重定向到仓库根 `build\app\outputs\apk\release\app-release.apk`。
- 客户端测试：`rtk cmd /c "...gradle.bat -p android testDebugUnitTest"`（现 287 项全绿，含播放相关约百条纯函数断言）。
- ABI：发布 arm64-v8a；debug 含 x86_64（模拟器 QA）。libVLC 官方 aar 两 ABI 均覆盖。
- `android/app/build.gradle(.kts)` 是 minSdk/targetSdk/compileSdk/依赖管理事实源（Spike 工程需与其对齐——自行读取复制）。
- 目标用户依赖 TalkBack（屏幕阅读器），UI 一律简体中文。

## 3. 代码事实基线（已由主 AI 调查，改代码前可据此直达；不符时以实际代码为准并回报）

**音频（真成本在 Kotlin 采样级 PCM 层，不在解码）**——`android/app/src/main/kotlin/com/example/local_music_player/NativeBassPlayer.kt`（988 行）：
- 解码 `play` ~L213 / `playUrl` ~L244；插件名单 ~L198-211（flac/aac/alac/ape/opus/wv/dsd/webm + bass_fx + 自带 openssl）；
- 变速不变调 `setSpeed` ~L418 + `BASS_FX_TempoCreate` ~L227/257；
- 淡入/淡出/取消 ~L745/L402/L758 + `fadeMultiplier` ~L763（AudioTrack 音量斜坡，样本级）；
- 静音跳过 `startOutputLoop` ~L787-943 + `isSilentPcm` ~L971；
- 响度归一化 + 软限幅 `applyLoudnessGain` ~L953 / `softLimit` ~L962；ReplayGain/R128 顶层纯函数 ~L54-87；
- EQ 10 段 `setEq` ~L461 + DX8 PARAMEQ ~L496-510；效果（混响/合唱/回声/镶边/压缩）`setEffects` ~L512；
- 输出路由 `setPreferredDevice/routedDevice` ~L429/444；AirPlay PCM `setRemotePcmSink` ~L456；
- 音频焦点长在播放器内部 ~L120-133/681-709/772-784；歌词标签 `lyrics()` ~L353/625-631。
DSP 调用面在 `NativeMusicViewModel.kt`：init ~L958-967；起播应用 ~L4195-4609；变速/音量投送分流 ~L5572-5695；
效果 ~L6825-6893。UI 面板只经 VM 方法（EQ 滑杆 `MusicApp.kt` ~L7474-7550；效果 ~L7589-7601）。

**视频**：`VideoEngine` 抽象已存在 `NativeVideoPlayer.kt` ~L30-71；Media3 实现 `ExoPlayerVideoEngine` ~L77
（带请求头+MIME `play` ~L179、HLS 512MB 磁盘缓存 ~L84-103、倍速 ~L223）；**无字幕/音轨 UI**；
FFmpeg 兜底 `FfmpegFallbackPlayer.kt` ~L20-266（自建 AudioTrack ~L210），判定 `isFfmpegFallbackFormat` ~L422 /
`shouldFallbackToFfmpeg` ~L428；VM 分派 ~L4371/L4399/L4449/L4795、L1078/L1121。UI 渲染 `MusicApp.kt` 直接取
`app.videoPlayer.player`（ExoPlayer 实例）~L4788/L5202-5205。

**投屏实情（纠偏）**：DLNA 是 URL→远端渲染器遥控（不受换引擎影响）；**仓库无 Chromecast 实现**；
唯一复用本地解码的投屏是 AirPlay 音频（PCM→RAOP，`AirPlayController.kt`）。

**可保留层**：MediaSession（VM ~L796-828）、MediaPlaybackService 通知、PlayerWidgetProvider（回调经 VM 中转，
不直接调内核）；`AudioOutputController.kt`（AudioManager 枚举）引擎无关；在线服务/网盘代理/下载/歌词解析/导入/封面与引擎无关。

**测试**：`android/app/src/test` 37 个 JVM 文件全部不实例化播放器，测的是与播放器同文件的**顶层纯决策函数**
（音量感知曲线/响度公式/EQ 档迁移/AudioEffects/ffmpeg 判定等）。→ 这些纯函数必须**先迁出播放器文件、保住测试**，才能删内核。

## 4. 决策与 DSP 口径（用户已拍板，Spike 验证后按表执行）

- 引擎：**libVLC**（LGPLv2.1+，商业可用，义务见方案 §2.2）。版本：spike 探测 Maven Central 后取**最新 3.x stable**
  打底，4.0-eap 仅评估不默认上。
- DSP：**保最小必需集 = 变速不变调、均衡器、淡入、响度归一化**（尽量保留或近似）；混响/合唱/回声/镶边/压缩可砍；
  静音跳过、实时响度软限幅归后补（砍，记录）。逐项取舍与 go 判据见方案文档 §5 表，Spike 阶段逐项执行，含两条高风险：
  **变速 time-stretch 真机有效性** 与 **libVLC Android binding 是否透出动态 EQ**（不透出则回报复议，AI 不得自行砍 EQ）。

---

## 5. Phase 1 — Spike（动手第一站；做完回报 go/no-go，不继续下一阶段）

### 5.1 Spike 工程搭建
1. 在仓库根新建独立 Gradle 工程 `android-vlc-spike/`（settings + app 模块 + 独立包名如 `com.example.vlcspike`），
   依赖 `org.videolan.android:libvlc-all:<探测版本>`（从 Maven Central
   `https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml` 取版本列表，识别最新 stable
   与 4.0-eap，记录两者差异与体积）；minSdk/targetSdk/ABI 对齐主工程（读 `android/app` 构建文件复制）。
2. 工程能独立构建安装（debug APK + x86_64 供模拟器、arm64 供真机）。**不修改 `android/` 主工程任何文件**。

### 5.2 判据清单（每项给出"方法→证据→结论→是否需用户裁决"）
| # | 判据 | 验证方法 | 类型 |
|---|---|---|---|
| 1 | 格式矩阵：本地真实样本 flac/ape/alac-m4a/wv/opus/ogg/webm（若有 dsd）、wmv/rmvb/mpg/mp3 帧同步乱序 | 真机或模拟器逐个播、记录解码/出声/seek | 通过项 |
| 2 | 在线流冷起：签名 URL **不带 Range** 首包→出声延迟 ≤ 现状（实测约 1.5~2s）；缓冲中 seek | 起服务端? 否——用本机可直连样本 URL + libVLC 缓存选项调优 | 通过项 |
| 3 | 三型源请求头：①服务端签名代理直链（默认即可）②需 Referer ③需自定义 UA——用 `:http-user-agent`/`:http-referrer`/cookies 媒体 options 是否够用 | spike 工程内构造 | 通过项 |
| 4 | **变速不变调** 0.5×/1.5×/2.0× 音高不变（`setRate` + `--audio-time-stretch`） | 真机录音/耳测 | **用户裁决** |
| 5 | **动态 EQ**：Android binding 是否暴露（搜索/尝试调用 `setEqualizer`/Equalizer 类；编译探测即知） | 绑定 API 探测 | 若不透出 → **回报复议** |
| 6 | 淡出听感：`setVolume`(int 0-100) 阶梯淡出的可闻台阶 | 真机听评 | **用户裁决** |
| 7 | 音量曲线：perceptualVolume 映射到 VLC setVolume 刻度是否可用/需校准 | 探测 | 通过项 |
| 8 | 音频焦点 + 蓝牙：A2DP 中途切换跟随、来电暂停恢复；自建焦点外置方案（VM/AudioFocusManager）落地性 | 真机 | 通过项 |
| 9 | 后台 + MediaSession/通知/部件事件流（play/pause/seek/complete/error）正确性 | 真机 | 通过项 |
| 10 | 视频 parity：seek 精度、倍速、HLS 缓存、字幕轨枚举（**先枚举不做 UI**） | 真机 | 通过项 |
| 11 | 体积/启动基线：release APK 增量（现状基线=当前 android release APK）、libVLC 冷启动 init 耗时、R8/proguard 规则（aar 自带 consumer rules 是否够） | 测量 | 通过项 |
| 12 | LGPL 材料占位：许可文本 + 替换/重链接说明模板路径（不阻塞 spike，阻塞发布） | 文档 | 通过项 |
| 13 | AirPlay 去向：binding 是否暴露裸 PCM 回调（搜 `audio_set_callbacks` 等价物） | API 探测 | 无 → 回报建议下线范围 |

### 5.3 Spike 输出（回报给用户/主 AI，格式固定）
`docs/handoff/2026-09-06-player-engine-libvlc-migration-plan.md` 增一节「Spike 结果（<日期>）」或在工程内 README：
- 每个判据：结论 + 证据（版本号、logcat、截图/录制路径、体积数字）；
- **用户裁决清单**：§5.2 中标"用户裁决"的项 + EQ/AirPlay 复议项，给出各选项利弊；
- **go/no-go 建议**与理由；不通过时冻结建议。

## 6. Phase 2 — 统一契约 + 视频先行（Spike 通过后）
1. **先保测试**：把 NativeBassPlayer.kt 内顶层纯决策函数迁到引擎无关位置（逐文件搬移、每步 `testDebugUnitTest` 绿），
   迁移途中保留 `com.un4seen.bass` 依赖与旧文件（删除在 Phase 4）；
2. 音频焦点外置 `AudioFocusManager`（VM 层），替代 BASS 内部焦点与 ExoPlayer 自动焦点；
3. 新增 `VlcVideoEngine` 实现既有 `VideoEngine`；改造 `MusicApp.kt` Surface 宿主（AndroidView + libVLC Surface，
   版本线决定 TextureView/VLCVideoLayout），**不得再外泄 ExoPlayer.player 类型**；
4. 引擎选择配置化，BASS/ExoPlayer 路径保留可回退；验证：本机视频全格式、B 站/网盘/影视源播放、倍速、seek、
   DLNA 视频投送控制面回归、FFmpeg 兜底判定下线（代码 Phase 4 删）；
5. 完成标准：`testDebugUnitTest` 全绿 + 视频路径模拟器/真机冒烟无 FATAL/ANR。

## 7. Phase 3 — 音频迁移
1. `VlcAudioEngine` 实现统一 `PlayerEngine` 契约（音量含感知曲线映射/变速/seek/事件/焦点协作）；
2. VM 调用面逐段对齐契约（NativeMusicViewModel 前述行号段），状态与持久化键不变；旧引擎仅在配置位并存；
3. 按方案 §5 取舍表执行 DSP 上移/砍/后补（**先经用户确认裁决项**）；
4. 完成标准：听书整段真机走查（TalkBack 全程）、变速/EQ（若保留）/淡出/超响文件证据留档、全量单测绿。

## 8. Phase 4 — 清理 + 合规 + 发布收口
1. 删 BASS 全家（so/插件/自带 openssl/`BASS.java`+FX）、FFmpeg 兜底 Kotlin 与 `src/main/cpp/ffmpeg_*`（cpp 留 unrar）；
2. 改造引用 `com.un4seen.bass` 常量的测试（如 `NetworkStreamPlaybackTest` L3）后删除遗留；
3. LGPL 义务材料（许可文本页 + 替换说明）进应用（TalkBack 可读）；
4. 按 AGENTS.md §3 全套门槛收口：编译、`git diff --check`、全量单测、`verify-release.ps1`（若发版）、
   真实设备验收证据入 `docs/release/<版本>.md`；版本号/CHANGELOG/update.json 按发布流程执行；
5. 不 commit/push——交付=工作树改动清单（git status --short）+ 每阶段验证证据 + 未验证项 + 回滚路径
   （引擎配置化回退；删除前 git 历史可恢复）。

## 9. 禁止事项与边界（YAGNI）
- 不引入第二套抽象；不新增未要求依赖；不重写 UI 视觉；不改在线源/凭据/服务端逻辑。
- 不做 Chromecast、不新做字幕/音轨 UI（只枚举能力）。
- 不在 spike 内动 `android/` 主工程；不动 `.env`/`local.properties` 密钥。
- 主观判据不自行裁决；Spike 不过不推进 Phase 2。
- 不 commit/push；不创建 worktree/远程。

## 10. 每阶段回报模板（面向用户/主 AI）
```
阶段/日期/状态（进行中|完成|冻结|需裁决）
- 改动范围：<文件清单>
- 验证证据：<构建/测试/冒烟/logcat/体积/录制等>
- 用户裁决项：<决策清单与选项>
- 未验证项与风险：<...>
- 回滚：<...>
- 下一步建议：<...>
```
