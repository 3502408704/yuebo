# 设计：接入 BASS 解码插件与 CUE 增强支持

日期：2026-08-01
状态：已获用户口头批准（2026-08-01），等待文档审阅
说明：本设计文档按 AGENTS.md 规则不自动 git commit；工作树现有未提交修改原样保留。

## 背景与目标

本机播放使用 BASS，但当前很多格式（FLAC/AAC/M4A 等）实际依赖 Android 系统 MediaCodec 解码；不同系统版本自带解码器能力差异导致同一文件在不同手机上可能无法播放。用户希望把格式解码统一收归 BASS 官方插件，消除对系统解码器的依赖。同时希望增强 CUE 分轨支持。

现状：

- `NativeBassPlayer.kt`（`init` 于 :64-66）只加载 `bass` 与 `bass_fx`，未加载任何解码插件。
- `NativeMusicViewModel.kt` 的 `supportedAudioExtensions`（:379）只列出 12 种扩展名，不包含 ape/opus/wv/dsf/webm 等格式。
- CUE 解析（`parseCue` :1829）只记录第一个 `FILE` 行（`sourceFileName == null` 条件 :1840），多 FILE 整轨（每 FILE 对应一个源音频）只能展开第一个文件的分轨。
- CUE 文本解码（`decodeText` :1928、`ArchiveImporter.decodeCueText` :361）为 UTF-8 严格→GB18030 回退，不识别 UTF-8 BOM / UTF-16 LE / UTF-16 BE 编码。

目标：

1. 静态打包 8 个官方 BASS 解码插件到 `jniLibs/arm64-v8a/`，启动时 `BASS_PluginLoad` 加载；新增格式（ape/opus/wv/dsf/dff/webm/mka）走插件解码，现有格式（flac/aac/m4a/m4b 等）同样收归插件，系统 MediaCodec 仅作插件加载失败时的兜底。
2. CUE 增强：统一文本解码（支持 UTF-8 BOM / UTF-16 LE / UTF-16 BE），支持多 `FILE` 行 CUE 的分轨展开。

## 决策记录（brainstorming 问答结果）

| 问题 | 决策 |
| --- | --- |
| 接入哪些插件 | 8 个官方插件：BASSFLAC、BASS_AAC、BASSALAC、BASSAPE、BASSOPUS、BASSWV、BASSDSD、BASSWEBM |
| 插件集成方式 | 静态打包 `.so` 进 `jniLibs/arm64-v8a/`，`BASS_PluginLoad("bassxxx", 0)` 加载；排除运行时下载方案 |
| 系统解码器 | 保留 `BASS_CONFIG_ANDROID_CODECS` 默认（不设为 0），MediaCodec 仅作插件加载失败兜底 |
| CUE 文本编码 | UTF-8 BOM → UTF-16 LE/BE BOM → UTF-8 严格 → GB18030 回退 |
| CUE 多 FILE | 支持多 `FILE` 行，TRACK 归属最近 FILE；不做 `INDEX 00` pre-gap |
| 曲库可见性 | `queryTracks()` MediaStore 查询不变；ape/opus/wv 靠系统自然索引，webm/dsf 走“导入文件夹”原地址播放兜底 |
| 第三方 CUE 插件 | 不引入 `BASS_Substream`（官方无 CUE 插件，第三方不必要） |
| 新扩展名白名单 | `supportedAudioExtensions` 与 `ArchiveImporter.audioExtensions` 同步新增 `ape/opus/wv/dsf/dff/webm/mka` |

插件许可：BASS 及多数插件仅限非商业用途；BASS_AAC 为商业付费插件（包内自带文档，官网公开索引 404），用户已确认接入。符合 AGENTS.md“引入前检查许可、经用户确认”规则。

## 特性一：BASS 解码插件接入

### 插件清单（android/app/src/main/jniLibs/arm64-v8a/）

| 插件库 | 对应扩展名 | 说明 |
| --- | --- | --- |
| libbassflac.so | flac | FLAC 原生解码，替代 MediaCodec |
| libbass_aac.so | aac / m4a / m4b / mp4 | ADTS AAC + MP4/M4A 容器 |
| libbassalac.so | alac（m4a 内 ALAC 轨道） | Apple Lossless |
| libbassape.so | ape | Monkey's Audio |
| libbassopus.so | opus / oga | Opus；同时供 BASSWEBM 内嵌 Opus 轨道使用 |
| libbasswv.so | wv | WavPack |
| libbassdsd.so | dsf / dff | DSD 解码为 PCM 播放；可配合 libbasswv 播 WavPack 封装 DSD |
| libbasswebm.so | webm / mka | WebM/Matroska 音频；依赖 BASSOPUS（Opus 轨道）与 BASSFLAC（FLAC 轨道） |

### 加载（NativeBassPlayer.kt）

- 在 `init`（:64-66：`BASS_SetConfig(BASS_CONFIG_DEV_DEFAULT,1)` → `BASS_Init(-1,48000,0)` 之后）加载插件：
  - 逐库 `BASS.BASS_PluginLoad("bassflac", 0)` 等（传通用名，Android 端从 BASS 同目录 nativeLibraryDir 自动补 `lib` 前缀/`.so` 后缀；API 见 BASS.java:733，`System.loadLibrary("bass")` 在 :853）。
  - 加载顺序：bassflac → bass_aac → bassalac → bassape → bassopus → basswv → bassdsd → basswebm（BASSWEBM 依赖 BASSOPUS/BASSFLAC，须在两者之后）。
  - 单个插件加载失败不阻断启动：记录日志并继续，MediaCodec 兜底仍在。
- 不修改 `BASS_CONFIG_ANDROID_CODECS`（保持默认），插件缺失/加载失败时系统解码器仍可兜底。

### 扩展名白名单（4 处同步）

1. `NativeMusicViewModel.kt:379` `supportedAudioExtensions`：现有 `{mp3, ogg, oga, flac, wav, aiff, aif, mp2, mp1, m4a, aac, m4b}` 追加 `ape/opus/wv/dsf/dff/webm/mka`。
2. `ArchiveImporter.kt:45` `audioExtensions`：同上追加。
3. `ArchiveImporter.kt:522` `mimeFor()`：新增格式不单独映射，走 else → `audio/*`（与 cue 先例一致：扩展名完整保留、播放以插件为准；MediaProvider 可能归一化自定义 MIME，不影响播放）。
4. `NativeMusicViewModel.kt:404` 导入失败提示文案：更新格式列表，包含 APE/Opus/WavPack/DSD/WebM 等。

### 播放路径

- `NativeBassPlayer.play()` 现有 `openFileDescriptor` + `BASS_StreamCreateFile` 路径无需改动：插件注册后 BASS 自动按格式分发解码。
- 投送路径不变：`LocalMediaServer` 原样服务文件，接收设备决定可播放性（不转码）。

## 特性二：CUE 增强

### 文本解码统一（两处副本同步）

- `NativeMusicViewModel.decodeText`（:1928）与 `ArchiveImporter.decodeCueText`（:361）改为同一逻辑：
  1. 前 2 字节 `FE FF` → UTF-16 BE；`FF FE` → UTF-16 LE（去 BOM）。
  2. 前 3 字节 `EF BB BF` → 去 BOM 后按 UTF-8。
  3. 否则 UTF-8 严格解码（现有逻辑）；失败回退 GB18030（现有逻辑）。

### parseCue 多 FILE 支持（NativeMusicViewModel.kt :1829）

- 逐行跟踪“当前 FILE”：`FILE <name> <type>` 行设置当前 FILE，其后 `TRACK / INDEX / TITLE / PERFORMER` 归属当前 FILE。
- `ParsedCue`（:1943）由单一 `sourceFileName` 重构为多源结构（如 `ParsedCue(files: List<CueFile>, album, performer)`，`CueFile(sourceFileName, entries)`）；`CueEntry` 结构不变。
- `cueValue`、`cueTimeMs`、INDEX 解析等既有逻辑保留。

### expandCueTracks 多源展开（NativeMusicViewModel.kt :1751）

- 对每个 FILE 的 basename 分别用 `cueSourceKey(folderPath, fileName)`（:1866）匹配曲库源音频；匹配不到的 FILE 跳过（不影响其它 FILE）。
- 每个 FILE 的分轨展开沿用现有 `source.copy(id = source.id xor (entry.number shl 56), cueStartMs = ..., isCueTrack = true)` 逻辑；`replacedSourceIds` 去重继续保证同一源音频不被两个 CUE 重复展开。
- 分轨全为空时回退原音频（沿用现有 `splitTracks.isEmpty()` 回退逻辑）。

## 构建与验收

- 构建：`D:\musicplayer\android` 下 `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`、`:app:assembleRelease`；`rtk git diff --check`。
- 体积影响：8 个 arm64-v8a 插件 .so（剥离后合计约 1–2 MB），APK 相应增加。
- 真机清单：
  1. ape/opus/wv/dsf 各放一首进曲库（系统能索引的），播放成功；FLAC 播放不再依赖 MediaCodec。
  2. webm/mka 经“导入文件夹”原地址播放成功。
  3. 导入失败提示文案包含新格式。
  4. UTF-8 BOM / UTF-16 LE / UTF-16 BE 三种编码 CUE 均正确展开分轨。
  5. 多 FILE CUE（每 FILE 一个源音频）全部展开；源音频缺失的 FILE 跳过。
  6. 现有单 FILE CUE、GB18030 CUE、CUE 分轨投送 DLNA/Chromecast 无回归。
  7. 插件加载失败路径：移除某插件 .so 后应用仍可启动并播放（MediaCodec 兜底）。

## 不在范围

- 运行时下载插件；x86 / x86_64 / armeabi-v7a 插件打包（构建仅 arm64-v8a）。
- `BASS_Substream` 或任何第三方 CUE 插件；`INDEX 00` pre-gap 处理。
- `queryTracks()` 的 MediaStore 查询逻辑改动（ape/opus/wv 依赖系统索引）。
- 均衡器/速度调节对插件格式的额外适配（BASS_FX TempoCreate 对 PCM 流通用，无需改动）。
