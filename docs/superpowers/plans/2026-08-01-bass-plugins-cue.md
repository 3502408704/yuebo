# BASS 解码插件与 CUE 增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 接入 8 个官方 BASS 解码插件（静态打包到 jniLibs/arm64-v8a 并 BASS_PluginLoad 加载），增强 CUE 分轨（BOM 感知文本解码 + 多 FILE 支持）。

**Architecture:** 插件 .so 放入 android/app/src/main/jniLibs/arm64-v8a/；NativeBassPlayer.init 在 BASS_Init 之后逐个 BASS_PluginLoad，失败不阻断、MediaCodec 兜底。CUE 侧统一 decodeText/decodeCueText 为 BOM 感知逻辑；parseCue 重构为多源结构（ParsedCue(files, album, performer)），expandCueTracks 按各 FILE 的 basename 分别匹配曲库源音频展开分轨。

**Tech Stack:** Android/Kotlin、BASS 官方插件（bassflac/bass_aac/bassalac/bassape/bassopus/basswv/bassdsd/basswebm）；8 个插件全部下载自 un4seen.com 官方服务器，直链均已实测可下载。bass_aac 即 BASS_AAC 2.4（GPL 免费分发、非商业免费；商用需联系 Nero AG 获取 AAC 专利许可），Android 版官方包：https://www.un4seen.com/files/z/2/bass_aac24-android.zip

## Global Constraints

- 所有 shell 命令以 rtk 开头；Android 构建必须在 D:\musicplayer\android 执行：rtk cmd /c "gradlew.bat :app:compileDebugKotlin"、rtk cmd /c "gradlew.bat :app:assembleRelease"。
- 文件修改一律用 apply_patch；不自动 git commit、不创建 worktree、不清理脏工作树（仓库硬性规则；本计划无“提交”步骤，每任务以构建验证收尾）。
- 无单元测试框架（android/app/src 无 test）；验证 = rtk git diff --check + compileDebugKotlin/assembleRelease + 真机冒烟。
- 保持 BASS_CONFIG_ANDROID_CODECS 默认（不设为 0）；插件加载失败不阻断启动。
- 新扩展名白名单 4 处同步：NativeMusicViewModel.supportedAudioExtensions（:379）、ArchiveImporter.audioExtensions（:45）、ArchiveImporter.mimeFor（:522，确认走 audio/* 兜底、不改代码）、NativeMusicViewModel 导入失败文案（:404）。
- UI 文案简体中文；只改 spec 覆盖的行为，不顺手重构。
- 本机 apply_patch 工具说明：apply_patch 包装会拦截命令文本中字面出现的「三颗星 + 空格 + Update File 冒号」连续串。执行 Update File 型 patch 时，把「三颗星加空格」与「Update File 冒号」拆成两个字符串变量拼接后作为 patch 首行，整个 patch 作为参数传给 codex.exe 的 --codex-run-as-apply-patch；Add File 型 patch 可直接写。写入内容时反引号写双份、ASCII 双引号写双份（经参数传递后还原为单份）。

---

### Task 1: 放置 8 个插件 .so 到 jniLibs/arm64-v8a/

**Files:**
- Create (binary): android/app/src/main/jniLibs/arm64-v8a/libbassflac.so、libbass_aac.so、libbassalac.so、libbassape.so、libbassopus.so、libbasswv.so、libbassdsd.so、libbasswebm.so
- 前置（官方直链，已实测 HTTP 200）：https://www.un4seen.com/files/z/2/bass_aac24-android.zip（610,993 字节，含 libs/arm64-v8a/libbass_aac.so 等 4 ABI 与 BASS_AAC.java）

**Interfaces:**
- Consumes: 无
- Produces: 8 个 libbass*.so 位于 android/app/src/main/jniLibs/arm64-v8a/，供 Task 2 的 BASS_PluginLoad 加载

- [x] **Step 1: 下载 7 个免费插件包到构建临时目录**

Run（任意工作目录）：
`powershell
rtk powershell -Command "New-Item -ItemType Directory -Force -Path 'D:\本地音乐播放器\android\build\plugin-stage' | Out-Null; Invoke-WebRequest -Uri 'https://www.un4seen.com/files/bassflac24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\bassflac24-android.zip' -TimeoutSec 60; Invoke-WebRequest -Uri 'https://www.un4seen.com/files/bassalac24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\bassalac24-android.zip' -TimeoutSec 60; Invoke-WebRequest -Uri 'https://www.un4seen.com/files/bassape24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\bassape24-android.zip' -TimeoutSec 60; Invoke-WebRequest -Uri 'https://www.un4seen.com/files/bassopus24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\bassopus24-android.zip' -TimeoutSec 60; Invoke-WebRequest -Uri 'https://www.un4seen.com/files/basswv24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\basswv24-android.zip' -TimeoutSec 60; Invoke-WebRequest -Uri 'https://www.un4seen.com/files/bassdsd24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\bassdsd24-android.zip' -TimeoutSec 60; Invoke-WebRequest -Uri 'https://www.un4seen.com/files/basswebm24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\basswebm24-android.zip' -TimeoutSec 60"
`
Expected: 7 个 zip 下载完成（各约 60KB–650KB）。

- [x] **Step 2: 解压并复制 arm64-v8a 的 .so 到 jniLibs**

`powershell
rtk powershell -Command "Get-ChildItem 'D:\本地音乐播放器\android\build\plugin-stage' -Filter *.zip | Expand-Archive -DestinationPath 'D:\本地音乐播放器\android\build\plugin-stage\out' -Force; Copy-Item -Path 'D:\本地音乐播放器\android\build\plugin-stage\out\*\libs\arm64-v8a\libbass*.so' -Destination 'D:\本地音乐播放器\android\app\src\main\jniLibs\arm64-v8a\' -Force"
`
Expected: jniLibs/arm64-v8a/ 出现 7 个新 libbass*.so。

- [x] **Step 3: 下载官方 BASS_AAC Android 插件并放置 .so**

`powershell
rtk powershell -Command "Invoke-WebRequest -Uri 'https://www.un4seen.com/files/z/2/bass_aac24-android.zip' -OutFile 'D:\本地音乐播放器\android\build\plugin-stage\bass_aac24-android.zip' -TimeoutSec 60; Expand-Archive -Path 'D:\本地音乐播放器\android\build\plugin-stage\bass_aac24-android.zip' -DestinationPath 'D:\本地音乐播放器\android\build\plugin-stage\out' -Force; Copy-Item -Path 'D:\本地音乐播放器\android\build\plugin-stage\out\*\libs\arm64-v8a\libbass_aac.so' -Destination 'D:\本地音乐播放器\android\app\src\main\jniLibs\arm64-v8a\' -Force; Write-Host 'BASS_AAC 已就位（官方 bass_aac24-android.zip）'"
`
Expected: 输出“BASS_AAC 已就位（官方 bass_aac24-android.zip）”；网络异常时重试即可。

- [x] **Step 4: 确认 8 个插件就位**

`powershell
rtk powershell -Command "Get-ChildItem 'D:\本地音乐播放器\android\app\src\main\jniLibs\arm64-v8a' | Select-Object Name"
`
Expected: libbass.so、libbass_fx.so + libbassflac.so、libbass_aac.so、libbassalac.so、libbassape.so、libbassopus.so、libbasswv.so、libbassdsd.so、libbasswebm.so（8 个插件全部就位）。

### Task 2: NativeBassPlayer 加载插件

**Files:**
- Modify: android/app/src/main/kotlin/com/example/local_music_player/NativeBassPlayer.kt（imports 加 android.util.Log；init 块加 loadPlugins() 调用与函数）

**Interfaces:**
- Consumes: Task 1 的 8 个 libbass*.so
- Produces: NativeBassPlayer 启动即注册插件；对 NativeMusicViewModel 无接口变化

- [x] **Step 1: 加 import**

`kotlin
import android.util.Log
`

- [x] **Step 2: init 块追加插件加载**

在 NativeBassPlayer.kt 的 init 块（:63-67，BASS_Init 的 check 语句之后）插入 loadPlugins() 调用，并在类内新增私有函数 loadPlugins()。整段替换后的 init 块与函数如下：

`kotlin
    init {
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_DEV_DEFAULT, 1)
        check(BASS.BASS_Init(-1, 48_000, 0)) {
            "本机音频引擎不可用：${BASS.BASS_ErrorGetCode()}"
        }
        loadPlugins()
    }

    private fun loadPlugins() {
        listOf(
            "bassflac", "bass_aac", "bassalac", "bassape",
            "bassopus", "basswv", "bassdsd", "basswebm",
        ).forEach { plugin ->
            if (BASS.BASS_PluginLoad(plugin, 0) == 0) {
                Log.w("NativeBassPlayer", "BASS 插件加载失败: " + plugin + " 错误码 " + BASS.BASS_ErrorGetCode() + "，系统解码器兜底")
            }
        }
    }
`

说明：BASS_PluginLoad 返回 0 表示加载失败，仅记录日志、不抛异常（MediaCodec 兜底）；加载顺序 bassflac → bass_aac → bassalac → bassape → bassopus → basswv → bassdsd → basswebm（BASSWEBM 依赖 BASSOPUS/BASSFLAC，须在两者之后）。

- [x] **Step 3: 编译验证**

Run（工作目录 D:\musicplayer\android）：
`powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
`
Expected: BUILD SUCCESSFUL

- [x] **Step 4: 打包验证**

`powershell
rtk cmd /c "gradlew.bat :app:assembleRelease"
`
Expected: BUILD SUCCESSFUL；APK 体积较之前增大（插件 .so 进入 APK）。

### Task 3: 扩展名白名单同步

**Files:**
- Modify: NativeMusicViewModel.kt:379、NativeMusicViewModel.kt:404、ArchiveImporter.kt:45

**Interfaces:**
- Consumes: 无
- Produces: supportedAudioExtensions / audioExtensions 包含新格式；importFiles（:396 校验）、:538 过滤、importArchive（ArchiveImporter:337）接受新扩展名

- [x] **Step 1: NativeMusicViewModel.kt:379 追加扩展名**

替换 supportedAudioExtensions 的 setOf 行：
`kotlin
    private val supportedAudioExtensions =
        setOf("mp3", "ogg", "oga", "flac", "wav", "aiff", "aif", "mp2", "mp1", "m4a", "aac", "m4b", "ape", "opus", "wv", "dsf", "dff", "webm", "mka")
`

- [x] **Step 2: ArchiveImporter.kt:45 同步追加**

替换 audioExtensions 的 setOf 行：
`kotlin
    private val audioExtensions = setOf("mp3", "flac", "ogg", "oga", "wav", "aiff", "aif", "mp2", "mp1", "m4a", "aac", "m4b", "ape", "opus", "wv", "dsf", "dff", "webm", "mka")
`

- [x] **Step 3: NativeMusicViewModel.kt:404 导入失败文案更新**

替换 status 字符串：
`kotlin
                    status = "未找到支持的音频文件，支持 MP3/OGG/FLAC/WAV/AIFF/M4A/AAC/APE/OPUS/WavPack/DSD/WebM 等常见格式。",
`

- [x] **Step 4: 确认 ArchiveImporter.mimeFor（:522）无需改动**（新格式落 else → audio/*，与 cue 先例一致；读代码确认）

- [x] **Step 5: 编译验证**

Run（工作目录 D:\musicplayer\android）：rtk cmd /c "gradlew.bat :app:compileDebugKotlin" → Expected: BUILD SUCCESSFUL

### Task 4: CUE 文本解码统一（BOM 感知）

**Files:**
- Modify: NativeMusicViewModel.kt:1928-1932（decodeText）、ArchiveImporter.kt:361-365（decodeCueText）

**Interfaces:**
- Consumes: 无
- Produces: decodeText/decodeCueText 行为：UTF-8 BOM → UTF-16 LE BOM → UTF-16 BE BOM → UTF-8 严格 → GB18030 回退；调用方（queryCueSheets :1796/:1816、歌词 :1904/:1923、countCueTracks :356）无需改动即受益

- [x] **Step 1: 替换 NativeMusicViewModel.decodeText**

整函数替换（:1928-1932）为：
`kotlin
    private fun decodeText(bytes: ByteArray): String {
        var offset = 0
        var charset: Charset = Charsets.UTF_8
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                offset = 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                offset = 2
                charset = Charsets.UTF_16LE
            }
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                offset = 2
                charset = Charsets.UTF_16BE
            }
        }
        val decoded = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        }
        if (decoded.isSuccess) return decoded.getOrThrow()
        return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
    }
`
说明：NativeMusicViewModel.kt 已 import java.nio.charset.Charset 与 CodingErrorAction（原实现已用），无需新增 import。

- [x] **Step 2: 同步替换 ArchiveImporter.decodeCueText（:361-365）**

同一实现（函数名 decodeCueText）；确认 ArchiveImporter 已 import java.nio.ByteBuffer、java.nio.charset.Charset、java.nio.charset.CodingErrorAction（原实现已用）。

- [x] **Step 3: 编译验证**

Run（工作目录 D:\musicplayer\android）：rtk cmd /c "gradlew.bat :app:compileDebugKotlin" → Expected: BUILD SUCCESSFUL

### Task 5: parseCue 多 FILE 支持

**Files:**
- Modify: NativeMusicViewModel.kt:1829-1864（parseCue）、:1943（ParsedCue 数据类）

**Interfaces:**
- Consumes: queryCueSheets 产出的 CueSheet.contents
- Produces: ParsedCue(files: List<CueFile>, album, performer)；CueFile(sourceFileName, entries)；供 Task 6 逐 FILE 匹配曲库源音频

- [x] **Step 1: ParsedCue 重构为多源结构**

替换 :1943 附近数据类（CueEntry 保持不变）：
`kotlin
    private data class CueSheet(val folderPath: String, val fileName: String, val contents: String)
    private data class CueFile(val sourceFileName: String, val entries: List<CueEntry>)
    private data class ParsedCue(val files: List<CueFile>, val album: String, val performer: String)
    private data class CueEntry(val number: Int, var title: String = "", var performer: String = "", var startMs: Long = -1)
`

- [x] **Step 2: 重写 parseCue 逐行跟踪当前 FILE**

整函数替换（:1829-1864）为：
`kotlin
    private fun parseCue(contents: String): ParsedCue? {
        var album = ""
        var performer = ""
        var currentFile: String? = null
        var current: CueEntry? = null
        val pendingEntries = mutableListOf<CueEntry>()
        val files = mutableListOf<CueFile>()
        val trackPattern = Regex("^\\s*TRACK\\s+(\\d+)\\s+AUDIO\\s*$", RegexOption.IGNORE_CASE)
        val indexPattern = Regex("^\\s*INDEX\\s+01\\s+(\\d+):(\\d+):(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        contents.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("FILE ", true) -> {
                    current?.let(pendingEntries::add)
                    current = null
                    flushCueFile(currentFile, pendingEntries, files)
                    currentFile = line.substringAfter(' ').substringBeforeLast(' ').trim()
                        .trim('"').substringAfterLast('\\').substringAfterLast('/').trim()
                }
                trackPattern.matches(line) -> {
                    current?.let(pendingEntries::add)
                    current = CueEntry(trackPattern.matchEntire(line)!!.groupValues[1].toInt())
                }
                indexPattern.matches(line) && current != null -> {
                    val values = indexPattern.matchEntire(line)!!.groupValues
                    current!!.startMs = cueTimeMs(values[1], values[2], values[3])
                }
                line.startsWith("TITLE ", true) -> {
                    if (current == null) album = cueValue(line.substringAfter(' '))
                    else current!!.title = cueValue(line.substringAfter(' '))
                }
                line.startsWith("PERFORMER ", true) -> {
                    if (current == null) performer = cueValue(line.substringAfter(' '))
                    else current!!.performer = cueValue(line.substringAfter(' '))
                }
            }
        }
        current?.let(pendingEntries::add)
        flushCueFile(currentFile, pendingEntries, files)
        val parsed = files.map { CueFile(it.sourceFileName, it.entries.filter { e -> e.startMs >= 0 }) }
            .filter { it.entries.isNotEmpty() }
        return if (parsed.isEmpty()) null else ParsedCue(parsed, album, performer)
    }

    private fun flushCueFile(
        sourceFileName: String?,
        pendingEntries: MutableList<CueEntry>,
        files: MutableList<CueFile>,
    ) {
        sourceFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
            if (pendingEntries.isNotEmpty()) files.add(CueFile(fileName, pendingEntries.toList()))
        }
        pendingEntries.clear()
    }
`

说明：去掉原 :1840 处 sourceFileName == null 才记录 FILE 的限制；TRACK/INDEX/TITLE/PERFORMER 归属最近 FILE；换 FILE 时先提交上一个未完成分轨；无有效分轨（startMs >= 0 为空）的 FILE 不进入结果；所有 FILE 都无分轨时返回 null（与原逻辑一致）。

- [x] **Step 3: 编译验证**

Run（工作目录 D:\musicplayer\android）：
`powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
`

Expected: BUILD SUCCESSFUL

### Task 6: expandCueTracks 多源展开

**Files:**
- Modify: NativeMusicViewModel.kt:1751-1778（expandCueTracks）

**Interfaces:**
- Consumes: Task 5 的 ParsedCue.files
- Produces: 多 FILE CUE 每个 FILE 独立展开；replacedSourceIds 去重；分轨全空回退原音频

- [x] **Step 1: 重写 cueSheets.forEach 内层循环**

将现有 forEach 块（:1755-1776，单源 parsed.sourceFileName 匹配）替换为：
`kotlin
            cueSheets.forEach { sheet ->
                val parsed = parseCue(sheet.contents) ?: return@forEach
                parsed.files.forEach { file ->
                    val source = sources[cueSourceKey(sheet.folderPath, file.sourceFileName)]
                        ?: sheet.takeIf { it.folderPath.isBlank() }?.let {
                            audioTracks.singleOrNull { track -> track.title.equals(file.sourceFileName, true) }
                        }
                        ?: return@forEach
                    if (!replacedSourceIds.add(source.id)) return@forEach
                    val entries = file.entries.sortedBy { it.startMs }
                    val splitTracks = entries.mapIndexedNotNull { index, entry ->
                        val end = entries.getOrNull(index + 1)?.startMs ?: source.durationMs
                        val duration = end - entry.startMs
                        if (duration <= 0) null else source.copy(
                            id = source.id xor (entry.number.toLong() shl 56),
                            title = entry.title.ifBlank { "${source.title.substringBeforeLast('.') } ${entry.number}" },
                            artist = entry.performer.ifBlank { parsed.performer.ifBlank { source.artist } },
                            album = parsed.album.ifBlank { source.album },
                            durationMs = duration,
                            sizeBytes = 0,
                            cueStartMs = entry.startMs,
                            isCueTrack = true,
                        )
                    }
                    if (splitTracks.isEmpty()) replacedSourceIds.remove(source.id) else addAll(splitTracks)
                }
            }
`

说明：外层新增 parsed.files.forEach，源匹配改为按 file.sourceFileName；album/performer 仍是 CUE 级（跨 FILE 共享）；splitTracks 为空回退原音频、replacedSourceIds 去重逻辑沿用原实现；源音频缺失的 FILE 跳过、不影响其它 FILE。

- [x] **Step 2: 编译验证**

Run（工作目录 D:\musicplayer\android）：
`powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
`

Expected: BUILD SUCCESSFUL

### Task 7: 构建与真机验收

**Files:**
- 无代码改动；验证 Task 1-6 全部产物

**Interfaces:**
- Consumes: 插件 .so（Task 1）、loadPlugins（Task 2）、扩展名白名单（Task 3）、BOM 解码（Task 4）、多 FILE CUE（Task 5-6）
- Produces: 静态检查与真机验收记录；可进入发布流程（仅当用户确认）

- [x] **Step 1: 静态检查与 Release 构建**

Run（工作目录 D:\musicplayer\android）：
`powershell
rtk git diff --check
rtk cmd /c "gradlew.bat :app:assembleRelease"
`

Expected: 无空白错误；BUILD SUCCESSFUL；APK 体积较 0.5.61 增大（新增插件 .so 约 1-2 MB）。

- [ ] **Step 2: 真机验收清单**

按 scripts/smoke-checklist.md 与真实设备验收清单逐项记录，至少覆盖：
1. 新格式播放：ape/opus/wv 各一首进曲库播放成功；dsf/dff 走导入文件夹原地址播放成功；FLAC 播放不再依赖 MediaCodec（日志确认 bassflac 生效）。
2. webm/mka 经导入文件夹原地址播放成功。
3. AAC/M4A 回归：libbass_aac.so 存在时走 BASS_AAC；缺失时仍可经 MediaCodec 播放、不闪退。
4. CUE 编码：UTF-8 BOM / UTF-16 LE / UTF-16 BE 三种编码 CUE 均正确展开分轨（切下一曲验证 INDEX 定位）。
5. 多 FILE CUE：两个 FILE 各带若干 TRACK 全部展开；移除其中一个源音频后该 FILE 分轨跳过、另一 FILE 正常。
6. 回归：单 FILE CUE、GB18030 CUE、CUE 分轨投送 DLNA/Chromecast 无回归；蓝牙路由与 TalkBack 关键路径通过。
7. 插件缺失兜底：临时移除某插件 .so 后应用仍可启动并播放（MediaCodec 兜底），不闪退。
8. 导入失败文案（:404）包含新格式；导入文件夹能收集到 webm/dsf 等新扩展名。

- [ ] **Step 3: 自审与收尾**

- `rtk git diff --check` 通过；本计划覆盖 spec 全部决策（8 个插件、CUE BOM 感知、多 FILE）。
- 搜索确认无遗留占位符（{{、TODO、FIXME、xxx）；临时探测文件已清理。
- 发布（仅当用户确认）：按 AGENTS.md 发布流程升 versionCode 5062、同步 CHANGELOG.md 与 update.json、跑 scripts/verify-release.ps1；不自动提交。
