# 注册播放格式与压缩包专辑导入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让"汪汪播放器"出现在系统"用其他应用打开"选择器中（音频与 rar/zip/tgz 压缩包），并支持把压缩包专辑解压到统一目录 `Music/汪汪播放器/<专辑名>/` 后解析入库。

**Architecture:** 系统 Intent（VIEW/SEND）进入 `MainActivity` → `NativeMusicViewModel.importIncoming()` 分类路由：音频立即播放并后台复制入库；压缩包走 `ArchiveImporter`（zip/tgz 纯 Java、RAR 走 NDK unrar JNI）两阶段"解压到缓存 → 写入 MediaStore"。封面/`.lrc`/`.cue` 写入同目录，并新增 MediaStore 查找路径使 Android 11+ 无需全文件权限也能读取。

**Tech Stack:** Kotlin + Jetpack Compose（material3）、Android MediaStore、`java.util.zip`、RARLab unrar C++ 源码（NDK 27.0.12077973 + CMake + JNI）、Gradle（AGP）。

## Global Constraints

- 所有 Android 构建必须在 ASCII 联接路径执行：工作目录 `D:\musicplayer\android`，命令 `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"` 与 `rtk cmd /c "gradlew.bat :app:assembleRelease"`。
- 所有 shell 命令必须以 `rtk` 开头。
- 不执行任何 `git commit`（AGENTS.md 规则）；每个任务以"编译通过 + 人工检查点"作为验收，取代提交步骤。
- 文件修改一律用 `apply_patch`（Windows 上经 `codex.exe --codex-run-as-apply-patch` 调用）；不删除/改动现有未提交文件。
- 版本约束：compileSdk 36、minSdk 24、targetSdk 36、NDK 27.0.12077973；release 仅 `arm64-v8a`（abiFilters 现有配置）。
- 应用名固定为"汪汪播放器"；导入根目录固定为 `Music/汪汪播放器/`（Q+ 用 `RELATIVE_PATH`，<Q 用 `DATA` + `WRITE_EXTERNAL_STORAGE`）。
- 可播放音频扩展名集合：`mp3/ogg/oga/flac/wav/aiff/aif`（BASS 仅内置 libbass+libbass_fx，无 APE/AAC/WMA 解码）。
- 压缩包保留的非音频文件：`.cue`、`.lrc`、封面图 `jpg/jpeg/png/webp`，与音频写入同一 MediaStore 目录。
- 去重规则：同文件夹同名 + 同大小跳过。
- UI 文案、语义、错误与状态反馈全部简体中文；所有可点击目标不小于 48x48 dp；图标按钮必须有中文 tooltip/语义名；异步操作期间禁用重复触发；对话框可关闭并恢复焦点；不用颜色单独表达状态。
- 必须保留"所有文件访问"（MANAGE_EXTERNAL_STORAGE）权限流程：不得删除 `MusicApp.requestAllFilesAccess` 与 `allFilesAccessGranted` 现有逻辑（用户 2026-07-31 明确要求；封面与 .cue 依赖直接文件读取）。Task 9 的 MediaStore 查找仅是补充回退路径。
- 本仓库无测试基础设施（AGENTS.md：不给无测试代码库添加测试框架）；纯逻辑正确性以编译 + 真机清单验证。

## File Structure

新增文件：
- `android/app/src/main/kotlin/com/example/local_music_player/ArchiveImporter.kt` — 格式识别、zip/tgz 解压、RAR 接入、MediaStore 入库、去重、安全上限；导出 `ImportResult`、`PasswordRequest`、`ImportOutcome`。
- `android/app/src/main/kotlin/com/example/local_music_player/UnrarNative.kt` — JNI 声明（`extract`、`isEncrypted`）。
- `android/app/src/main/cpp/unrar_wrapper.cpp` — JNI 实现（调 unrar DLL API）。
- `android/app/src/main/cpp/CMakeLists.txt` — 编 `libunrar_wrapper.so`。
- `android/app/src/main/cpp/unrar/` — RARLab unrar 源码（下载 vendor，非手写）。
- `android/app/src/main/cpp/unrar_license.txt` — unrar 许可证文本。

修改文件：
- `android/app/src/main/AndroidManifest.xml` — 应用名"汪汪播放器"、音频/压缩包 VIEW/SEND/SEND_MULTIPLE 过滤器。
- `android/app/src/main/kotlin/com/example/local_music_player/MainActivity.kt` — `handleIntent`/`onNewIntent` 路由。
- `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt` — 状态字段、`importIncoming`、音频打开入库、`importArchive`、密码重试、外置歌词 MediaStore 查找。
- `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt` — 导入按钮、进度/结果/密码对话框、`loadNamedArtwork` MediaStore 查找、API 24–28 写权限。
- `android/app/build.gradle.kts` — `externalNativeBuild { cmake }`。

## Task 1: Manifest 意图过滤器与应用名

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: 新增 intent-filter（VIEW 音频 content/`audio/*`、VIEW 音频 file 扩展名、VIEW 压缩包 content MIME 集合、VIEW 压缩包 file 扩展名、SEND/SEND_MULTIPLE 音频与压缩包）；`android:label` 改为"汪汪播放器"。

- [ ] **Step 1: 修改 Manifest**

在 `<activity>` 的 `MAIN/LAUNCHER` intent-filter 之后追加以下 intent-filter，并把 `<application android:label="汐汐播放器"` 改为 `android:label="汪汪播放器"`：

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="content" />
    <data android:mimeType="audio/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="file" />
    <data android:pathPattern=".*\\.mp3" />
    <data android:pathPattern=".*\\.flac" />
    <data android:pathPattern=".*\\.ogg" />
    <data android:pathPattern=".*\\.oga" />
    <data android:pathPattern=".*\\.wav" />
    <data android:pathPattern=".*\\.aiff" />
    <data android:pathPattern=".*\\.aif" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="content" />
    <data android:mimeType="application/zip" />
    <data android:mimeType="application/x-zip-compressed" />
    <data android:mimeType="application/x-rar-compressed" />
    <data android:mimeType="application/vnd.rar" />
    <data android:mimeType="application/x-tar" />
    <data android:mimeType="application/gzip" />
    <data android:mimeType="application/x-gzip" />
    <data android:mimeType="application/x-compressed-tar" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="file" />
    <data android:pathPattern=".*\\.zip" />
    <data android:pathPattern=".*\\.rar" />
    <data android:pathPattern=".*\\.tar" />
    <data android:pathPattern=".*\\.gz" />
    <data android:pathPattern=".*\\.tgz" />
    <data android:pathPattern=".*\\.tar\\.gz" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="audio/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/zip" />
    <data android:mimeType="application/x-rar-compressed" />
    <data android:mimeType="application/vnd.rar" />
    <data android:mimeType="application/x-tar" />
    <data android:mimeType="application/gzip" />
    <data android:mimeType="application/x-gzip" />
    <data android:mimeType="application/x-compressed-tar" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="audio/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/zip" />
    <data android:mimeType="application/x-rar-compressed" />
    <data android:mimeType="application/vnd.rar" />
    <data android:mimeType="application/x-tar" />
    <data android:mimeType="application/gzip" />
    <data android:mimeType="application/x-gzip" />
    <data android:mimeType="application/x-compressed-tar" />
</intent-filter>
```

- [ ] **Step 2: 编译验证**

Run（工作目录 `D:\musicplayer\android`）:
```powershell
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
```
Expected: `BUILD SUCCESSFUL`（若 XML 有误会在此失败）。

- [ ] **Step 3: 人工检查点**

检查合并后的 Manifest 存在且包含上述过滤器：`android/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`。真机检查（Task 10 统一执行）：QQ 文件长按"其他应用打开"列表中可见"汪汪播放器"。

## Task 2: MainActivity 路由与 ViewModel 入口

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`

**Interfaces:**
- Consumes: Task 1 的 intent-filter（VIEW/SEND/SEND_MULTIPLE）。
- Produces: `NativeMusicViewModel.importIncoming(uris: List<Uri>)`（Task 3/4 消费）；`uriDisplayName(uri): String`、`isArchiveUri(uri): Boolean`、`audioExtensions` 常量。

- [ ] **Step 1: MainActivity 增加 Intent 路由**

把 `MainActivity.kt` 替换为：

```kotlin
package com.example.local_music_player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val viewModel: NativeMusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MusicApp() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: intent.clipData?.getItemAt(0)?.uri,
            )
            Intent.ACTION_SEND_MULTIPLE -> buildList {
                intent.clipData?.let { clip ->
                    repeat(clip.itemCount) { i -> clip.getItemAt(i).uri?.let(::add) }
                }
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
            }
            else -> emptyList()
        }.filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.importIncoming(uris)
    }
}
```

- [ ] **Step 2: ViewModel 增加状态字段与路由入口**

在 `NativeMusicViewModel.kt` 的 `MusicUiState` 末尾（`updateAnnouncement` 之后）追加：

```kotlin
    val importing: Boolean = false,
    val importProgress: Pair<Int, Int>? = null,
    val importResult: ImportResult? = null,
    val passwordRequest: PasswordRequest? = null,
```

在类内（`paste` 之后、`requestDelete` 之前）追加：

```kotlin
    private val archiveExtensions = setOf("zip", "rar", "tar", "gz")
    private val archiveMimes = setOf(
        "application/zip", "application/x-zip-compressed",
        "application/x-rar-compressed", "application/vnd.rar",
        "application/x-tar", "application/gzip", "application/x-gzip",
        "application/x-compressed-tar", "application/x-7z-compressed",
    )

    fun importIncoming(uris: List<Uri>) {
        val (archives, audio) = uris.partition(::isArchiveUri)
        audio.firstOrNull()?.let(::playAndImportAudio)
        archives.forEach(::importArchive)
    }

    private fun uriDisplayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "文件"

    private fun isArchiveUri(uri: Uri): Boolean {
        val lower = uriDisplayName(uri).lowercase()
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return true
        if (lower.substringAfterLast('.', "") in archiveExtensions) return true
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        return mime in archiveMimes
    }
```

- [ ] **Step 3: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`（`ImportResult`/`PasswordRequest`/`playAndImportAudio`/`importArchive` 尚未定义会编译失败——这是预期，Task 3/4 补齐）。

## Task 3: 音频打开：立即播放 + 自动入库 + 播放源切换

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`

**Interfaces:**
- Consumes: Task 2 的 `importIncoming`、`uriDisplayName`。
- Produces: `copyIntoMediaStore(sourceUri, displayName, relativeDir): Uri`、`querySingleTrack(uri): NativeTrack?`、`swapPlayingSourceTo(importedUri, originalUri)`。

- [ ] **Step 1: 实现音频打开入库**

在 `isArchiveUri` 之后追加：

```kotlin
    private fun playAndImportAudio(uri: Uri) {
        val name = uriDisplayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("mp3", "ogg", "oga", "flac", "wav", "aiff", "aif")) {
            _state.value = _state.value.copy(
                status = "暂不支持 $extension 格式，目前支持 MP3/OGG/FLAC/WAV/AIFF。",
            )
            return
        }
        val tempTrack = NativeTrack(
            id = -1L,
            uri = uri,
            title = name,
            artist = "未知艺术家",
            album = "",
            durationMs = 0,
            format = extension.uppercase(),
            mimeType = runCatching { resolver.getType(uri) }.getOrNull() ?: "audio/*",
            folderPath = "",
        )
        playFromQueue(listOf(tempTrack), 0)
        _state.value = _state.value.copy(status = "已开始播放 $name，正在导入音乐库。")
        importSingleAudio(uri, name)
    }

    private fun importSingleAudio(uri: Uri, name: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { copyIntoMediaStore(uri, name, "Music/汪汪播放器") }
            }
                .onSuccess { importedUri ->
                    swapPlayingSourceTo(importedUri, uri)
                    val tracks = runCatching { withContext(Dispatchers.IO) { queryTracks() } }
                        .getOrElse { _state.value.tracks }
                    _state.value = restoreResume(tracks).copy(tracks = tracks, loading = false)
                    _state.value = _state.value.copy(status = "已将 $name 导入音乐库。")
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        status = "导入 $name 失败：${error.message ?: "未知错误"}",
                    )
                }
        }
    }

    private fun copyIntoMediaStore(sourceUri: Uri, displayName: String, relativeDir: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, resolver.getType(sourceUri) ?: "audio/*")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir.trim('/').plus("/"))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    relativeDir,
                ).apply { mkdirs() }
                val dest = File(dir, displayName)
                if (dest.exists()) dest.delete()
                put(MediaStore.MediaColumns.DATA, dest.absolutePath)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
        }
        val destination = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建目标文件")
        try {
            resolver.openInputStream(sourceUri)!!.use { input ->
                resolver.openOutputStream(destination)!!.use { output -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
        return destination
    }

    private fun querySingleTrack(uri: Uri): NativeTrack? = runCatching {
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }
        resolver.query(
            uri,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                folderColumn,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                NativeTrack(
                    id = cursor.getLong(0),
                    uri = uri,
                    title = cursor.getString(1) ?: "未知歌曲",
                    artist = cursor.getString(2).orEmpty().ifBlank { "未知艺术家" },
                    album = cursor.getString(3).orEmpty(),
                    durationMs = cursor.getLong(4).coerceAtLeast(0),
                    format = (cursor.getString(1) ?: "").substringAfterLast('.', "音频").uppercase(),
                    mimeType = cursor.getString(5).orEmpty().ifBlank { "audio/*" },
                    folderPath = cursor.getString(6).orEmpty().trim('/').let { path ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) path
                        else path.substringBeforeLast('/', "")
                    },
                    sizeBytes = cursor.getLong(7).coerceAtLeast(0),
                )
            }
        }
    }.getOrNull()

    private fun swapPlayingSourceTo(importedUri: Uri, originalUri: Uri) {
        val current = _state.value.currentTrack ?: return
        if (current.id != -1L || current.uri != originalUri) return
        val positionMs = player.positionMs()
        val track = querySingleTrack(importedUri) ?: return
        startLocalPlaybackNow(listOf(track), 0)
        if (positionMs > 0) player.seek(positionMs)
    }
```

- [ ] **Step 2: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`（Task 2 的 `importArchive` 未定义仍会导致失败，Task 4 补齐；若想中途单独验收，可先加空函数 `fun importArchive(uri: Uri) = Unit` 临时占位，Task 4 移除占位）。
## Task 4: ArchiveImporter 核心（zip/tgz/去重/安全/MediaStore 入库）

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/ArchiveImporter.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`

**Interfaces:**
- Consumes: Task 2 的 `importArchive(uri)` 调用点。
- Produces: `ArchiveImporter.import(context, sourceUri, password, onProgress): ImportOutcome`；`ImportResult(imported, skipped, failed, albumFolder)`；`PasswordRequest(uri, displayName, errorMessage)`；`ImportOutcome.Success/.NeedsPassword/.Failure`（Task 5/6/7 消费）。RAR 分支本任务先返回"尚未就绪"，Task 5 接入。

- [ ] **Step 1: 创建 ArchiveImporter.kt**

```kotlin
package com.example.local_music_player

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val albumFolder: String?,
)

data class PasswordRequest(
    val uri: Uri,
    val displayName: String,
    val errorMessage: String? = null,
)

sealed interface ImportOutcome {
    data class Success(val result: ImportResult) : ImportOutcome
    data class NeedsPassword(val request: PasswordRequest) : ImportOutcome
    data class Failure(val message: String) : ImportOutcome
}

class ImportException(message: String) : Exception(message)
class NeedsPasswordException(val wrongPassword: Boolean) : Exception()
class EncryptedZipException : Exception()

object ArchiveImporter {
    private val audioExtensions = setOf("mp3", "flac", "ogg", "oga", "wav", "aiff", "aif")
    private val keptExtensions = setOf("cue", "lrc", "jpg", "jpeg", "png", "webp")
    private const val MAX_FILES = 5000
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    private enum class Format { ZIP, RAR, GZIP_TAR, GZIP, TAR, UNKNOWN }
    private enum class FileImport { Imported, Duplicate, Error }

    suspend fun import(
        context: Context,
        sourceUri: Uri,
        password: String?,
        onProgress: (Int, Int) -> Unit,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val archiveName = uriFileName(resolver, sourceUri)
        runCatching {
            val tempRoot = File(context.cacheDir, "import/${System.currentTimeMillis()}")
                .apply { mkdirs() }
            try {
                val format = detectFormat(resolver, sourceUri, archiveName)
                when (format) {
                    Format.ZIP -> extractZip(resolver, sourceUri, tempRoot)
                    Format.GZIP_TAR -> extractGzipTar(resolver, sourceUri, tempRoot)
                    Format.GZIP -> extractGzipSingle(resolver, sourceUri, archiveName, tempRoot)
                    Format.TAR -> extractTarStream(resolver.openInputStream(sourceUri)!!, tempRoot)
                    Format.RAR -> throw ImportException("RAR 解压组件尚未就绪。")
                    Format.UNKNOWN -> throw ImportException("无法识别的压缩格式。")
                }
                val albumName = chooseAlbumName(tempRoot, archiveName)
                ImportOutcome.Success(importTree(resolver, tempRoot, albumName, onProgress))
            } finally {
                tempRoot.deleteRecursively()
            }
        }.getOrElse { error ->
            when (error) {
                is NeedsPasswordException -> ImportOutcome.NeedsPassword(
                    PasswordRequest(sourceUri, archiveName, if (error.wrongPassword) "密码错误，请重试。" else null),
                )
                is EncryptedZipException -> ImportOutcome.Failure("暂不支持加密的 ZIP 文件。")
                else -> ImportOutcome.Failure("导入失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun uriFileName(resolver: ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "压缩包"

    private fun detectFormat(resolver: ContentResolver, uri: Uri, name: String): Format {
        val lower = name.lowercase()
        val magic = resolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(8)
            val n = input.read(head)
            head.copyOf(n.coerceAtLeast(0))
        } ?: ByteArray(0)
        if (magic.size >= 4 &&
            magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2] == 3.toByte() && magic[3] == 4.toByte()
        ) return Format.ZIP
        if (magic.size >= 7 &&
            magic[0] == 'R'.code.toByte() && magic[1] == 'a'.code.toByte() &&
            magic[2] == 'r'.code.toByte() && magic[3] == '!'.code.toByte() &&
            magic[4] == 0x1A.toByte() && magic[5] == 0x07.toByte() &&
            (magic[6] == 0.toByte() || magic[6] == 1.toByte())
        ) return Format.RAR
        if (magic.size >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) {
            return if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) Format.GZIP_TAR
            else Format.GZIP
        }
        return if (lower.endsWith(".tar")) Format.TAR else Format.UNKNOWN
    }

    private fun archiveStem(name: String): String = name.lowercase()
        .replace(Regex("\\.(tar\\.gz|tgz|tar|zip|rar|gz)$"), "")
        .trim()
        .ifBlank { "导入专辑" }

    private fun chooseAlbumName(root: File, archiveName: String): String {
        val top = root.listFiles().orEmpty()
        if (top.size == 1 && top[0].isDirectory &&
            top[0].walkTopDown().any { it.isFile && it.extension.lowercase() in audioExtensions }
        ) {
            return top[0].name
        }
        return archiveStem(archiveName)
    }

    private fun sanitizeEntryName(name: String): String? {
        val normalized = name.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (normalized.startsWith("/") || normalized.contains(":")) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun extractZip(resolver: ContentResolver, sourceUri: Uri, tempRoot: File) {
        resolver.openInputStream(sourceUri)!!.use { raw ->
            ZipInputStream(raw).use { zip ->
                var count = 0
                var total = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    val safe = if (entry.isDirectory) null else sanitizeEntryName(entry.name)
                    if (safe != null) {
                        val target = File(tempRoot, safe).apply { parentFile?.mkdirs() }
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                if (total > MAX_TOTAL_BYTES) throw ImportException("压缩包内容过大。")
                            }
                        }
                        count++
                        if (count > MAX_FILES) throw ImportException("文件数量过多。")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun extractGzipTar(resolver: ContentResolver, sourceUri: Uri, tempRoot: File) {
        resolver.openInputStream(sourceUri)!!.use { raw ->
            GZIPInputStream(raw).use { gz -> extractTarStream(gz, tempRoot) }
        }
    }

    private fun extractGzipSingle(
        resolver: ContentResolver,
        sourceUri: Uri,
        archiveName: String,
        tempRoot: File,
    ) {
        val targetName = archiveStem(archiveName)
        resolver.openInputStream(sourceUri)!!.use { raw ->
            GZIPInputStream(raw).use { gz ->
                val target = File(tempRoot, targetName).apply { parentFile?.mkdirs() }
                FileOutputStream(target).use { out -> gz.copyTo(out) }
            }
        }
    }

    private fun extractTarStream(input: InputStream, tempRoot: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var count = 0
        var total = 0L
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val name = tarString(header, 0, 100)
            val prefix = tarString(header, 345, 155)
            val size = tarOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            if (type == 'L') {
                val longName = ByteArray(size.toInt())
                readFully(input, longName)
                skipBlocks(input, size)
                pendingLongName = String(longName, Charsets.UTF_8).trimEnd('\u0000', '\n')
                continue
            }
            val entryName = pendingLongName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            pendingLongName = null
            val safe = sanitizeEntryName(entryName)
            if (safe == null) {
                skipBlocks(input, size)
                continue
            }
            if (type == '5') {
                File(tempRoot, safe).mkdirs()
                skipBlocks(input, size)
                continue
            }
            if (type == '0' || type == '\u0000') {
                val target = File(tempRoot, safe).apply { parentFile?.mkdirs() }
                FileOutputStream(target).use { out ->
                    var remaining = size
                    val buf = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                        total += n
                        if (total > MAX_TOTAL_BYTES) throw ImportException("压缩包内容过大。")
                    }
                }
                count++
                if (count > MAX_FILES) throw ImportException("文件数量过多。")
                skipBlocks(input, size)
            } else {
                skipBlocks(input, size)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return offset == 0
            offset += n
        }
        return true
    }

    private fun skipBlocks(input: InputStream, size: Long) {
        var remaining = (size + 511) / 512 * 512
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun tarString(buffer: ByteArray, offset: Int, length: Int): String =
        String(buffer, offset, length, Charsets.US_ASCII).substringBefore('\u0000').trim()

    private fun tarOctal(buffer: ByteArray, offset: Int, length: Int): Long =
        tarString(buffer, offset, length).trim().toLongOrNull(8) ?: 0L

    private fun importTree(
        resolver: ContentResolver,
        root: File,
        albumName: String,
        onProgress: (Int, Int) -> Unit,
    ): ImportResult {
        val candidates = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in (audioExtensions + keptExtensions) }
            .toList()
        val relativeDir = "Music/汪汪播放器/$albumName"
        var imported = 0
        var skipped = 0
        var failed = 0
        candidates.forEachIndexed { index, file ->
            val outcome = if (file.extension.lowercase() in audioExtensions) {
                importAudioFile(resolver, file, relativeDir)
            } else {
                importNonAudioFile(resolver, file, relativeDir)
            }
            when (outcome) {
                FileImport.Imported -> imported++
                FileImport.Duplicate -> skipped++
                FileImport.Error -> failed++
            }
            onProgress(index + 1, candidates.size)
        }
        return ImportResult(imported, skipped, failed, albumName)
    }

    private fun importAudioFile(resolver: ContentResolver, file: File, relativeDir: String): FileImport {
        val name = file.name
        val size = file.length()
        return try {
            if (isDuplicate(resolver, relativeDir, name, size)) {
                FileImport.Duplicate
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.extension))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.SIZE, size)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return FileImport.Error
                try {
                    resolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
                FileImport.Imported
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    relativeDir,
                ).apply { mkdirs() }
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = false)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, dest.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.extension))
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                FileImport.Imported
            }
        } catch (error: Throwable) {
            FileImport.Error
        }
    }

    private fun importNonAudioFile(resolver: ContentResolver, file: File, relativeDir: String): FileImport {
        return try {
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "text/plain"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: return FileImport.Error
                try {
                    resolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
                FileImport.Imported
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    relativeDir,
                ).apply { mkdirs() }
                val dest = File(dir, file.name)
                if (dest.exists()) FileImport.Duplicate else {
                    file.copyTo(dest)
                    FileImport.Imported
                }
            }
        } catch (error: Throwable) {
            FileImport.Error
        }
    }

    private fun isDuplicate(resolver: ContentResolver, relativeDir: String, name: String, size: Long): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf("$relativeDir/", name, size.toString()),
                null,
            )?.use { it.moveToFirst() } ?: false
        } else {
            val path = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "$relativeDir/$name",
            ).absolutePath
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { it.moveToFirst() } ?: false
        }
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        else -> "audio/*"
    }
}
```

- [ ] **Step 2: ViewModel 实现 importArchive**

在 `NativeMusicViewModel.kt` 的 `importIncoming` 之后追加：

```kotlin
    fun importArchive(uri: Uri) {
        if (_state.value.importing) return
        _state.value = _state.value.copy(importing = true, importProgress = null, status = null)
        viewModelScope.launch {
            val outcome = ArchiveImporter.import(getApplication(), uri, null) { done, total ->
                _state.value = _state.value.copy(importProgress = done to total)
            }
            when (outcome) {
                is ImportOutcome.Success -> {
                    refresh(true)
                    _state.value = _state.value.copy(
                        importing = false,
                        importProgress = null,
                        importResult = outcome.result,
                        status = "导入完成：成功 ${outcome.result.imported} 首。",
                    )
                }
                is ImportOutcome.NeedsPassword -> {
                    _state.value = _state.value.copy(
                        importing = false,
                        importProgress = null,
                        passwordRequest = outcome.request,
                    )
                }
                is ImportOutcome.Failure -> {
                    _state.value = _state.value.copy(
                        importing = false,
                        importProgress = null,
                        status = outcome.message,
                    )
                }
            }
        }
    }
```

- [ ] **Step 3: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 人工检查点（zip/tgz 通路）**

准备两个测试包放入手机 Download：`测试专辑.zip` 与 `测试专辑.tgz`（各含 `01.mp3`、`cover.jpg`、`专辑.lrc`、`专辑.cue`）。Task 7 完成后经"应用内导入"选择验证：音频写入 `Music/汪汪播放器/测试专辑/`、`.cue/.lrc/封面`同目录、重复导入跳过、曲库出现整轨与分轨。

## Task 5: 原生 unrar（NDK/JNI）并接入 RAR

**Files:**
- Create: `android/app/src/main/cpp/unrar_wrapper.cpp`
- Create: `android/app/src/main/cpp/CMakeLists.txt`
- Create: `android/app/src/main/cpp/unrar_license.txt`
- Create: `android/app/src/main/kotlin/com/example/local_music_player/UnrarNative.kt`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/ArchiveImporter.kt`

**Interfaces:**
- Consumes: Task 4 的 `Format.RAR` 分支（当前抛"尚未就绪"）。
- Produces: `UnrarNative.extract(path: String, destDir: String, password: String?): Int`（0=成功、22=需要密码、24=密码错误、其它为 unrar 错误码）、`UnrarNative.isEncrypted(path): Boolean`；ArchiveImporter 的 RAR 分支改走 `UnrarNative`。

- [ ] **Step 1: 下载并 vendor unrar 源码**

```powershell
rtk powershell -Command "New-Item -ItemType Directory -Force -Path 'D:\本地音乐播放器\android\app\src\main\cpp\unrar' | Out-Null"
rtk powershell -Command "Invoke-WebRequest -Uri 'https://www.rarlab.com/rar/unrarsrc-7.1.6.tar.gz' -OutFile 'D:\本地音乐播放器\android\app\src\main\cpp\unrarsrc.tar.gz'"
```
解压到 `cpp/unrar/`（保留 `license.txt` 并复制为 `cpp/unrar_license.txt`）。若 rarlab 已发布更高稳定版本则替换版本号；下载失败时从 `https://www.rarlab.com/rar_add.htm` 取最新 `unrarsrc-*.tar.gz`。确认 `cpp/unrar/dll.hpp` 与 `cpp/unrar/dll.cpp` 存在。

- [ ] **Step 2: 编写 CMakeLists.txt**

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(unrar_wrapper CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB UNRAR_SRC CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/unrar/*.cpp)
list(FILTER UNRAR_SRC EXCLUDE REGEX ".*/main\\.cpp$")

add_library(unrar_wrapper SHARED unrar_wrapper.cpp ${UNRAR_SRC})
target_include_directories(unrar_wrapper PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/unrar)
target_compile_definitions(unrar_wrapper PRIVATE _UNIX RARDLL SILENT)
target_compile_options(unrar_wrapper PRIVATE -D_FILE_OFFSET_BITS=64)
```

- [ ] **Step 3: 编写 JNI 包装 unrar_wrapper.cpp**

```cpp
#include <jni.h>
#include <string>
#include "dll.hpp"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_local_music_player_UnrarNative_isEncrypted(
    JNIEnv* env, jclass, jstring pathJ) {
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char*>(path);
    data.OpenMode = RAR_OM_LIST;
    data.CmtBuf = nullptr;
    HANDLE hArc = RAROpenArchiveEx(&data);
    env->ReleaseStringUTFChars(pathJ, path);
    if (!hArc) return JNI_FALSE;
    jboolean encrypted = JNI_FALSE;
    RARHeaderDataEx header{};
    header.CmtBuf = nullptr;
    if (RARReadHeaderEx(hArc, &header) == 0) {
        encrypted = (header.Flags & RHDF_ENCRYPTED) ? JNI_TRUE : JNI_FALSE;
    }
    RARCloseArchive(hArc);
    return encrypted;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_local_music_player_UnrarNative_extract(
    JNIEnv* env, jclass, jstring pathJ, jstring destJ, jstring pwdJ) {
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    const char* dest = env->GetStringUTFChars(destJ, nullptr);
    const char* pwd = pwdJ ? env->GetStringUTFChars(pwdJ, nullptr) : nullptr;

    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char*>(path);
    data.OpenMode = RAR_OM_EXTRACT;
    data.CmtBuf = nullptr;
    HANDLE hArc = RAROpenArchiveEx(&data);
    if (!hArc) {
        if (pwdJ) env->ReleaseStringUTFChars(pwdJ, pwd);
        env->ReleaseStringUTFChars(destJ, dest);
        env->ReleaseStringUTFChars(pathJ, path);
        return ERAR_EOPEN;
    }
    if (pwd) RARSetPassword(hArc, const_cast<char*>(pwd));

    int rc = 0;
    RARHeaderDataEx header{};
    header.CmtBuf = nullptr;
    while ((rc = RARReadHeaderEx(hArc, &header)) == 0) {
        rc = RARProcessFileW(hArc, RAR_EXTRACT, const_cast<char*>(dest), nullptr);
        if (rc != 0) break;
    }
    RARCloseArchive(hArc);

    if (pwdJ) env->ReleaseStringUTFChars(pwdJ, pwd);
    env->ReleaseStringUTFChars(destJ, dest);
    env->ReleaseStringUTFChars(pathJ, path);
    return rc;
}
```

- [ ] **Step 4: 创建 UnrarNative.kt**

```kotlin
package com.example.local_music_player

object UnrarNative {
    init {
        System.loadLibrary("unrar_wrapper")
    }

    /** 0=成功；22=需要密码；24=密码错误；其它为 unrar 错误码。 */
    external fun extract(path: String, destDir: String, password: String?): Int

    external fun isEncrypted(path: String): Boolean
}
```

- [ ] **Step 5: build.gradle.kts 接入 CMake**

在 `android { ... }` 块内（`buildFeatures` 之前）增加：

```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

- [ ] **Step 6: ArchiveImporter 接入 RAR**

把 Task 4 中 `Format.RAR` 分支替换为：

```kotlin
                    Format.RAR -> {
                        val archiveFile = File(tempRoot, "archive.rar")
                        resolver.openInputStream(sourceUri)!!.use { input ->
                            archiveFile.outputStream().use { input.copyTo(it) }
                        }
                        val code = runCatching {
                            UnrarNative.extract(archiveFile.absolutePath, tempRoot.absolutePath, password)
                        }.getOrElse {
                            throw ImportException("RAR 解压组件不可用：${it.message ?: "未知错误"}")
                        }
                        when (code) {
                            0 -> Unit
                            22 -> throw NeedsPasswordException(wrongPassword = false)
                            24 -> throw NeedsPasswordException(wrongPassword = true)
                            else -> throw ImportException("解压失败（错误码 $code）。")
                        }
                    }
```

- [ ] **Step 7: 编译并确认 .so 产出**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`；确认 `android/app/build/intermediates/cxx/.../arm64-v8a/.../libunrar_wrapper.so` 生成（`rtk powershell -Command "Get-ChildItem -Recurse -Filter libunrar_wrapper.so 'D:\本地音乐播放器\android\app\build' | Select-Object FullName, Length"`）。首次构建会下载 CMake/NDK，耗时较长属正常。

- [ ] **Step 8: 人工检查点**

真机准备 `测试专辑.rar`（RAR4）与 `测试专辑5.rar`（RAR5，各含 mp3+cover），确认两版都能解压入库、中文文件名正常（若中文路径乱码，需改走宽字符 API `RAROpenArchiveDataExW`/`RARProcessFileW` 传 UTF-16——本任务已知风险点）。

## Task 6: 密码弹窗流程

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes: Task 4 的 `PasswordRequest`、`ImportOutcome.NeedsPassword`。
- Produces: `submitPassword(password: String)`、`cancelImportPassword()`、`dismissImportResult()`；UI 对话框在 Task 7 完成，本任务完成 ViewModel 侧。

- [ ] **Step 1: ViewModel 密码重试**

在 `importArchive` 之后追加：

```kotlin
    fun submitPassword(password: String) {
        val request = _state.value.passwordRequest ?: return
        _state.value = _state.value.copy(passwordRequest = null, importing = true, importProgress = null)
        viewModelScope.launch {
            val outcome = ArchiveImporter.import(getApplication(), request.uri, password) { done, total ->
                _state.value = _state.value.copy(importProgress = done to total)
            }
            when (outcome) {
                is ImportOutcome.Success -> {
                    refresh(true)
                    _state.value = _state.value.copy(
                        importing = false,
                        importProgress = null,
                        importResult = outcome.result,
                        status = "导入完成：成功 ${outcome.result.imported} 首。",
                    )
                }
                is ImportOutcome.NeedsPassword -> {
                    _state.value = _state.value.copy(
                        importing = false,
                        importProgress = null,
                        passwordRequest = outcome.request,
                    )
                }
                is ImportOutcome.Failure -> {
                    _state.value = _state.value.copy(
                        importing = false,
                        importProgress = null,
                        status = outcome.message,
                    )
                }
            }
        }
    }

    fun cancelImportPassword() {
        _state.value = _state.value.copy(passwordRequest = null, status = "已取消导入。")
    }

    fun dismissImportResult() {
        _state.value = _state.value.copy(importResult = null)
    }
```

- [ ] **Step 2: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`。
## Task 7: 导入 UI（按钮 / 进度 / 结果与密码对话框）

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes: Task 4/6 的 `state.importing`、`state.importProgress`、`state.importResult`、`state.passwordRequest`；`viewModel.importArchive/submitPassword/cancelImportPassword/dismissImportResult`。
- Produces: 曲库页 AppBar"导入"按钮；顶部进度条；密码与结果 AlertDialog。

- [ ] **Step 1: AppBar 增加 onImport 槽位**

把 `MusicApp.kt` 的 `AppBar` 签名与渲染改为：

```kotlin
@Composable
private fun AppBar(
    title: String,
    onBack: (() -> Unit)?,
    onDevices: (() -> Unit)?,
    onPaste: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        }
        if (onImport != null) {
            IconButton(onClick = onImport) {
                Icon(Icons.Default.FileOpen, contentDescription = "导入压缩包或音频")
            }
        }
        if (onPaste != null) {
            IconButton(onClick = onPaste) {
                Icon(Icons.Default.ContentPaste, contentDescription = "粘贴到当前文件夹")
            }
        }
        if (onDevices != null) {
            IconButton(onClick = onDevices) {
                Icon(Icons.Default.Cast, contentDescription = "播放设备")
            }
        }
    }
}
```

- [ ] **Step 2: LibraryScreen 透传 onImport**

`LibraryScreen` 签名增加 `onImport: () -> Unit`（放在 `onSearch` 之后），并在 `AppBar` 调用处增加 `onImport = onImport`。

- [ ] **Step 3: MusicApp 接线导入入口与对话框**

在 `MusicApp` 中 `deleteLauncher` 之后追加：

```kotlin
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { viewModel.importArchive(it) } }
```

`LibraryScreen` 调用处（`MainTab.Library` 分支）增加：

```kotlin
                onImport = {
                    importLauncher.launch(
                        arrayOf(
                            "application/zip", "application/x-rar-compressed",
                            "application/vnd.rar", "application/x-tar",
                            "application/gzip", "application/x-gzip",
                            "application/x-compressed-tar", "audio/*",
                        ),
                    )
                },
```

在 `LaunchedEffect(state.status)` 之后追加：

```kotlin
    LaunchedEffect(state.importResult) {
        state.importResult?.let {
            accessibilityView.announceForAccessibility(
                "导入完成：成功 ${it.imported} 首，跳过 ${it.skipped} 个，失败 ${it.failed} 个。",
            )
        }
    }
```

在 `MaterialTheme` 的 `Scaffold` 内容 `Box` 内、`when (screen)` 之后追加进度条与对话框：

```kotlin
                if (state.importing) {
                    ImportProgressBar(
                        progress = state.importProgress,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                state.passwordRequest?.let { request ->
                    PasswordDialog(
                        request = request,
                        onSubmit = viewModel::submitPassword,
                        onDismiss = viewModel::cancelImportPassword,
                    )
                }
                state.importResult?.let { result ->
                    ImportResultDialog(
                        result = result,
                        onDismiss = viewModel::dismissImportResult,
                    )
                }
```

- [ ] **Step 4: 新增三个 Composable（MusicApp.kt 文件底部，ThemeModeMenu 之后）**

```kotlin
@Composable
private fun ImportProgressBar(progress: Pair<Int, Int>?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinearProgressIndicator(
                progress = {
                    val total = (progress?.second ?: 1).coerceAtLeast(1)
                    (progress?.first ?: 0).toFloat() / total
                },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text(
                if (progress == null) "正在解压压缩包…"
                else "正在导入 ${progress.first}/${progress.second}…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun PasswordDialog(
    request: PasswordRequest,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩包需要密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${request.displayName} 已加密，请输入解压密码。")
                request.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ImportResultDialog(result: ImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入完成") },
        text = {
            Text(
                "成功导入 ${result.imported} 首，跳过 ${result.skipped} 个重复文件，" +
                    "失败 ${result.failed} 个。",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
```

新增 import：`androidx.compose.ui.text.input.PasswordVisualTransformation`、`androidx.compose.foundation.text.KeyboardOptions`、`androidx.compose.ui.text.input.KeyboardType`、`androidx.compose.material3.LinearProgressIndicator`、`androidx.compose.material3.Surface`、`androidx.compose.ui.semantics.liveRegion`。

- [ ] **Step 5: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 人工检查点**

曲库页 AppBar 出现"导入压缩包或音频"按钮（≥48dp、有语义名）；点开系统文件选择器可多选；导入中顶部出现进度条且重复点击无效（`importArchive` 有 `importing` 守卫）；完成后弹结果对话框、TalkBack 播报结果；密码包弹密码框，错误密码提示"密码错误，请重试。"。

## Task 8: API 24–28 写权限

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`

**Interfaces:**
- Consumes: Task 3/4 的 <Q 直接写 `Music/` 逻辑。
- Produces: API 24–28 下导入前已具备 `WRITE_EXTERNAL_STORAGE`（Manifest 已声明）。

- [ ] **Step 1: 权限请求改为多权限**

把 `MusicApp` 中的 `val mediaPermission = Manifest.permission.READ_EXTERNAL_STORAGE` 替换为：

```kotlin
    val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
```

把 `permissionLauncher` 改为：

```kotlin
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> viewModel.refresh(result.values.all { it }) }
```

把 `onRequestPermission = { permissionLauncher.launch(mediaPermission) }` 改为：

```kotlin
                onRequestPermission = { permissionLauncher.launch(storagePermissions) },
```

- [ ] **Step 2: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`。

## Task 9: 封面 / 歌词 MediaStore 查找（Android 11+ 免全文件权限）

约束（用户 2026-07-31 明确要求）：本任务只新增 MediaStore 回退路径；必须原样保留 `MusicApp.requestAllFilesAccess`、`allFilesAccessGranted` 及直接文件读取逻辑，不得移除全文件访问流程。

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`

**Interfaces:**
- Consumes: Task 4 写入同目录的 `cover.jpg`、`.lrc`。
- Produces: `queryFolderImage(context, folderPath, names, allowAlbumArtPrefix)`；`loadNamedArtwork` 与 `loadExternalLyrics` 在 Q+ 优先走 MediaStore。

- [ ] **Step 1: 重写 loadNamedArtwork（MusicApp.kt）**

把现有 `loadNamedArtwork` 与 `loadAlbumArtwork` 替换为（`loadSongArtwork` 同步补 `context` 参数）：

```kotlin
private fun loadAlbumArtwork(context: android.content.Context, track: NativeTrack): Bitmap? =
    loadNamedArtwork(context, track, listOf("cover", "folder", "front", "albumart", track.album), allowAlbumArtPrefix = true)
        ?: loadEmbeddedArtwork(context, track)

private fun loadNamedArtwork(
    context: android.content.Context,
    track: NativeTrack,
    names: List<String>,
    allowAlbumArtPrefix: Boolean = false,
): Bitmap? {
    val expectedNames = names.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (expectedNames.isEmpty()) return null
    queryFolderImage(context, track.folderPath, expectedNames, allowAlbumArtPrefix)?.let { return it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
        return null
    }
    return runCatching {
        val folder = java.io.File(Environment.getExternalStorageDirectory(), track.folderPath)
        val artwork = folder.listFiles()?.firstOrNull {
            it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") &&
                (expectedNames.any { name -> it.nameWithoutExtension.equals(name, true) } ||
                    (allowAlbumArtPrefix && it.nameWithoutExtension.startsWith("albumart", true)))
        }
        artwork?.let { BitmapFactory.decodeFile(it.path) }
    }.getOrNull()
}

private fun queryFolderImage(
    context: android.content.Context,
    folderPath: String,
    names: Set<String>,
    allowAlbumArtPrefix: Boolean,
): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val folderCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol).orEmpty()
                if (!cursor.getString(folderCol).orEmpty().trim('/').equals(folderPath, true)) continue
                if (!cursor.getString(mimeCol).orEmpty().startsWith("image/")) continue
                val lower = name.lowercase()
                if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") &&
                    !lower.endsWith(".png") && !lower.endsWith(".webp")
                ) continue
                val base = lower.substringBeforeLast('.')
                val match = names.any { it.equals(base, true) } ||
                    (allowAlbumArtPrefix && base.startsWith("albumart", true))
                if (!match) continue
                val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cursor.getLong(id))
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { return it }
                }
            }
            null
        }
    }.getOrNull()
}
```

调用处同步更新：`loadSongArtwork` 增加 `context` 参数并传给 `loadNamedArtwork`；`AlbumArt`/`ArtworkContent` 等调用处传入现有 `context`。

- [ ] **Step 2: loadExternalLyrics 增加 MediaStore 路径（NativeMusicViewModel.kt）**

把 `loadExternalLyrics` 替换为：

```kotlin
    private fun loadExternalLyrics(track: NativeTrack): String? =
        mediaStoreLyrics(track) ?: directFileLyrics(track)

    private fun mediaStoreLyrics(track: NativeTrack): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val title = track.title.substringBeforeLast('.', track.title).trim()
        if (title.isBlank()) return null
        return runCatching {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val folderCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    if (!cursor.getString(folderCol).orEmpty().trim('/').equals(track.folderPath, true)) continue
                    if (!name.endsWith(".lrc", true)) continue
                    if (!name.substringBeforeLast('.').equals(title, true)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cursor.getLong(id))
                    resolver.openInputStream(uri)?.use { input ->
                        val text = decodeText(input.readBytes()).removePrefix("\uFEFF").trim()
                        if (text.isNotBlank()) return text
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun directFileLyrics(track: NativeTrack): String? = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
    ) null else runCatching {
        val title = track.title.substringBeforeLast('.', track.title).trim()
        if (title.isBlank()) null else {
            val folder = File(Environment.getExternalStorageDirectory(), track.folderPath)
            val lyricFile = folder.listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("lrc", true) && it.nameWithoutExtension.equals(title, true)
            }
            lyricFile?.let { decodeText(it.readBytes()).removePrefix("\uFEFF").trim().takeIf(String::isNotBlank) }
        }
    }.getOrNull()
```

- [ ] **Step 3: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 人工检查点**

Android 13 真机（未授权全文件访问）导入含 `cover.jpg`/`歌曲.lrc` 的压缩包后：正在播放页显示外置封面与外置歌词；含 `.cue` 的整轨出现分轨。

## Task 10: 最终构建与真机验收

**Files:**
- 无代码改动；仅验证。

- [ ] **Step 1: 全量构建**

Run（工作目录 `D:\musicplayer\android`）:
```powershell
rtk cmd /c "gradlew.bat :app:assembleRelease"
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 体积检查**

```powershell
rtk powershell -Command "(Get-Item 'D:\本地音乐播放器\android\app\build\outputs\apk\release\app-release.apk').Length / 1MB"
```
Expected: 相比 0.5.2 增加约 0.5–0.8MB（unrar .so）。

- [ ] **Step 3: 真机验收清单（逐项记录结果）**

1. QQ 文件长按"其他应用打开"：选择器可见"汪汪播放器"；打开 mp3 立即播放、自动入库到 `Music/汪汪播放器/`，QQ 缓存清理后仍可播放（播放源已切换为库内副本）。
2. 应用内导入 zip / rar4 / rar5 / tgz 各一包：解压到 `Music/汪汪播放器/<专辑名>/`，曲库出现整轨/分轨。
3. 同名同大小重复导入：跳过并在结果对话框报告；无 "(1)" 重复文件。
4. Android 13（未授权全文件访问）：外置封面、`.lrc`、`.cue` 分轨均生效。
5. 加密 RAR：弹密码框，正确密码解压成功；错误密码提示"密码错误"；取消无残留（cacheDir 临时目录已清理、无半成品 MediaStore 行）。
6. 导入过程中导入按钮/重复触发被禁用；进度条可感知；TalkBack 可播报进度与结果；对话框可关闭且焦点恢复正常。
7. 不支持的格式（如 .ape 打开）给出中文提示，不崩溃。
8. 回归：本机播放/暂停/定位/音量、DLNA/Chromecast 投送、蓝牙输出、断点续播不受影响。

## Self-Review 记录

- 规格覆盖：决策表 9 项全部有对应任务（打开行为=Task 3；入口=Task 2/7；RAR=Task 5；统一目录=Task 4；非音频保留=Task 4；完成对话框=Task 7；去重=Task 4；密码=Task 5/6；应用名=Task 1）。权限、封面/歌词重构、验收清单=Task 8/9/10。
- 无占位符：所有步骤含完整代码或精确命令。
- 类型一致性：`ImportResult`/`PasswordRequest`/`ImportOutcome`/`ArchiveImporter.import(context, uri, password, onProgress)`/`UnrarNative.extract(path, destDir, password)` 在各任务签名一致；ViewModel 函数名 `importIncoming/importArchive/submitPassword/cancelImportPassword/dismissImportResult` 在 Task 2/4/6/7 中一致。
