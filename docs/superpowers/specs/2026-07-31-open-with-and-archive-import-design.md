# 设计：注册播放格式与压缩包专辑导入

日期：2026-07-31
状态：已获用户口头批准（2026-07-31），等待文档审阅
说明：本设计文档按 AGENTS.md 规则不自动 git commit；工作树现有未提交修改原样保留。

## 背景与目标

用户经常通过 QQ 群/网盘下载以压缩包（rar/zip/tgz）分发的专辑。现状：

- 应用未注册任何 `VIEW`/`SEND` 意图过滤器，系统"其他应用打开"列表中不出现"汪汪播放器"。
- 曲库只读取 `MediaStore`（`NativeMusicViewModel.queryTracks`），无法直接使用压缩包内容。
- 已有外置封面与 `.lrc` 解析代码，但读取依赖 Android 11+ 的"所有文件访问"权限（实际未申请），在 Android 11+ 上不生效。

目标：

1. 注册常见音频格式，出现在"用其他应用打开"选择器中；打开音频时立即播放并自动入库。
2. 支持 rar/zip/tgz 专辑压缩包解压，解压到统一目录 `Music/汪汪播放器/<专辑名>/` 并解析入库。

## 决策记录（brainstorming 问答结果）

| 问题 | 决策 |
| --- | --- |
| 打开音频行为 | 立即播放 + 自动复制入库（复制完成后播放源无缝切换到库内副本） |
| 压缩包入口 | 系统"打开方式"(VIEW) + 应用内"导入"按钮(SAF) + ACTION_SEND/SEND_MULTIPLE |
| RAR 方案 | NDK 编 unrar 源码（RAR4+RAR5），zip/tgz 用平台内置，体积最小 |
| 统一目录 | `Music/汪汪播放器/<专辑名>/`（MediaStore），应用名按"汪汪播放器" |
| 非音频文件 | `.cue` + 封面 + `.lrc` 全部保留到同目录 |
| 导入完成 | 结果对话框（成功/跳过/失败），不自动播放 |
| 去重 | 同文件夹同名 + 同大小跳过 |
| 密码压缩包 | 加密 RAR 弹窗输入密码重试；加密 zip 提示暂不支持 |
| 应用名 | Manifest label 由"汐汐播放器"改为"汪汪播放器" |

## 特性一：注册音频/压缩包打开方式

### Manifest（android/app/src/main/AndroidManifest.xml）

在 MainActivity 上新增过滤器（MainActivity 已是 exported=true、launchMode=singleTop）。多个 data 元素在同一 filter 内是 AND 关系，不同 scheme/mime 组合需拆成多个 filter。

1. 音频 VIEW（content scheme 用 MIME 通配 `audio/*` 覆盖 QQ/文件管理器的各种上报类型；file scheme 限定可播放扩展名）：
   - action VIEW；category DEFAULT、BROWSABLE
   - data scheme=content + mimeType=audio/*
   - data scheme=file + pathPattern：.mp3 / .flac / .ogg / .oga / .wav / .aiff / .aif
2. 压缩包 VIEW：
   - action VIEW；category DEFAULT、BROWSABLE
   - content scheme MIME：application/zip、application/x-zip-compressed、application/x-rar-compressed、application/vnd.rar、application/x-tar、application/gzip、application/x-gzip、application/x-compressed-tar
   - file scheme pathPattern：.zip / .rar / .tar / .gz / .tgz / .tar.gz
3. ACTION_SEND / SEND_MULTIPLE（QQ"发送给其他应用"）：mime 与音频 `audio/*` 及压缩包集合一致。

### MainActivity

- `onCreate` 已有 setContent；新增 `handleIntent(intent)`，并在 `onNewIntent` 中调用（singleTop 复用实例）。
- 解析：VIEW → intent.data；SEND → EXTRA_STREAM（ClipData）；SEND_MULTIPLE → ClipData 全部 item。
- 读取前 `takePersistableUriPermission`（尽力而为，失败不阻塞）。
- 将 URI 列表传给 `NativeMusicViewModel.importIncoming(uris)`。

### 音频打开流程（NativeMusicViewModel）

1. 校验 MIME/扩展名；BASS 可播放格式（mp3/ogg/flac/wav/aiff）→ 继续；否则设置状态"暂不支持该格式…"。
2. 立即以原 URI 创建播放队列并开始播放（现有 play 路径复用，BASS 可从 content:// 读取）。
3. 后台复制入库：`Music/汪汪播放器/`（沿用 `paste()` 的 MediaStore insert + IS_PENDING 模式；API 24–28 需 WRITE_EXTERNAL_STORAGE 运行时授权）。
4. 复制完成后：若当前播放项正是该文件，将其 URI 切换为库内副本并在原位置续播（重建 BASS 流）；刷新曲库 `refresh(true)`。

## 特性二：压缩包解压导入

### 新文件 ArchiveImporter.kt

- 签名：`suspend fun import(context, sourceUri, password: String?, onProgress: (Int, Int) -> Unit): ImportResult`（IO 线程）。
- 结果：`ImportResult(imported: Int, skipped: Int, failed: Int, albumFolder: String?)`。

### 流程（两阶段）

1. 解压到缓存：`context.cacheDir/import/<random>/`。
   - 格式识别：扩展名 + 魔数（zip `PK\x03\x04`、rar4 `Rar!\x1A\x07\x00`、rar5 `Rar!\x1A\x07\x01\x00`、gzip `\x1F\x8B`）。
   - zip：`java.util.zip.ZipInputStream`。
   - tar.gz / gz / tar：`GZIPInputStream` + 手写最小 ustar tar 解析（512 字节头，支持 'L' 长文件名扩展；未知类型条目跳过；不支持 pax 高级头）。
   - rar：JNI 调用原生 unrar 解压到临时目录；加密包首次报"需要密码"时暂停流程，UI 弹窗获取密码后重试。
2. 入库 MediaStore：
   - 专辑文件夹名：压缩包内恰有一个含音频的顶层目录 → 用该目录名；否则压缩包名去扩展名。
   - 目标：`Music/汪汪播放器/<专辑名>/`（RELATIVE_PATH，Q+；<Q 用 DATA 直接写，需 WRITE 权限）。
   - 音频文件：去重（MediaStore 查询同 RELATIVE_PATH + DISPLAY_NAME + SIZE）→ insert + IS_PENDING=1 → 流式复制 → IS_PENDING=0；失败回滚 delete。
   - 非音频：`.cue`（mime text/plain）、封面图 jpg/jpeg/png/webp、`.lrc` 写入同目录（MediaStore.Files）。
   - 完成：清理临时目录，返回统计。

### 原生 unrar（android/app/src/main/cpp）

- 引入 RARLab unrar 源码（vendor 到 `cpp/unrar/`），保留 unrar 许可证文本 `unrar_license.txt`。
- `CMakeLists.txt` 编 `libunrar_wrapper.so`（arm64-v8a；x86_64 供本机模拟器调试，打包排除规则沿用现有 jniLibs 配置）。
- JNI：`extract(path, destDir, password) -> errorCode`、`isEncrypted(path) -> Boolean`；实现参照 unrar 命令行 main（CmdExtract），密码经 CmdExtract 传入。
- app/build.gradle.kts 增加 externalNativeBuild cmake 配置；NDK 27.0.12077973 已声明。

### 安全与健壮性

- zip-slip 防护：拒绝绝对路径、`..`、反斜杠规范化后的越界路径；跳过符号链接/设备条目。
- 上限：解压总量（如 4GB）与文件数（如 5000）防解压炸弹；磁盘剩余空间检查。
- 失败/取消：删除已插入的 MediaStore 行与临时目录；不残留半成品。
- 导入期间 UI 禁用重复触发；进度可达（解压阶段不确定进度，入库阶段按文件数确定性进度）。

### UI 与无障碍

- 曲库页 AppBar 新增"导入"图标按钮（含中文 tooltip/语义名），与搜索按钮并列；ACTION_OPEN_DOCUMENT 多选（mime 过滤压缩包 + 音频）。
- 密码弹窗：AlertDialog + 密码输入框，可取消；取消则中止导入并提示。
- 完成对话框：`成功 N 首、跳过 M 个重复、失败 K 个`，关闭后焦点回到合理位置；符合 48dp 目标、TalkBack 播报。
- 进度状态文案、按钮、错误均为简体中文。

### 封面 / 歌词 / 分轨读取重构（配合特性二）

- `MusicApp.kt loadNamedArtwork`：新增 MediaStore 路径——按 track.folderPath + 候选名（cover/folder/front/albumart*/专辑名）查 MediaStore.Files 中的图片，经 content URI 解码；原直接 File 路径保留用于 Android ≤10 或已授权全文件访问的情况。
- `NativeMusicViewModel.kt loadExternalLyrics`：同样新增 MediaStore 路径（同文件夹同名 .lrc，UTF-8/GB18030 解码逻辑复用）。
- `.cue` 分轨：现有 queryCueSheets 已含 MediaStore.Files 路径，无需改动，写入 .cue 后自动生效。

## 权限

- API 33+：READ_MEDIA_AUDIO（现有）+ 写入 MediaStore 音乐目录无需额外权限。
- API 29–32：READ_EXTERNAL_STORAGE（现有）足够（MediaStore 写入）。
- API 24–28：需 WRITE_EXTERNAL_STORAGE 运行时授权（Manifest 已声明，未在运行时申请）——在导入/打开入库时一并申请。
- 必须保留现有 MANAGE_EXTERNAL_STORAGE（所有文件访问）权限申请流程（MusicApp 的 requestAllFilesAccess 与 allFilesAccessGranted 逻辑，2026-07-31 用户明确要求）；封面与 .cue 依赖直接文件读取，绝不能移除。Task 9 的 MediaStore 查找仅为未授权时的补充回退路径。

## 构建与验收

- 构建：`D:\musicplayer\android` 下 `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`、`:app:assembleRelease`。
- 真机清单：
  1. QQ 文件"其他应用打开"选择器中可见"汪汪播放器"；音频打开即播并入库，授权过期后仍可播。
  2. zip / rar4 / rar5 / tgz 各解一包到 `Music/汪汪播放器/<专辑名>/`，曲库出现整轨/分轨。
  3. 同名同大小重复导入被跳过并在结果对话框报告。
  4. Android 13 上封面、`.lrc`、`.cue` 分轨均生效（无需全文件权限）。
  5. 加密 RAR 弹窗输密码成功解压；取消时无残留。
  6. 导入中重复点击被禁用；TalkBack 可播报进度与结果。
- 体积影响：APK 增加约 0.5–0.8MB（unrar .so）；zip/tgz 无额外依赖。

## 不在范围

- 7z/ISO/CAB 等其它压缩格式；加密 zip 解压；RAR 创建能力。
- 不新增"全部文件访问"权限。
