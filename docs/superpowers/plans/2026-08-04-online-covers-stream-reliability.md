# 在线封面展示与流媒体播放稳定性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在线搜索结果/正在播放/迷你播放器全部显示封面；首次播放取址稳定（BASS 打不开自动换源）；解析快且地址缓存复用，缓存有明确上限与清理策略。

**Architecture:** 新增线程安全 `SimpleLruCache` 统一管理播放地址与封面位图缓存；`OnlineTrackResolver` 改为 suspend 并对 Shy 尝试加 5s 时间预算，新增 `resolveSkippingShy` 直达妖狐兜底；播放路径统一为"缓存→ShyMusic→妖狐"有界源尝试序列，BASS 打开失败即换下一源；新增 `OnlineArtwork` Composable + `OnlineArtworkCache` 渲染远程封面。

**Tech Stack:** Kotlin / Jetpack Compose / BASS（com.un4seen.bass）/ kotlinx-coroutines / org.json / Room（仅下载）

## Global Constraints

- 所有 shell 命令以 `rtk` 开头；Android 构建必须从 `D:\musicplayer\android` 执行。
- 文件修改一律使用 `apply_patch`；不自动提交；不创建 worktree；不删除用户文件。
- 不引入新的第三方依赖（Coil/Glide 等一律不用）。
- UI 文本、语义、错误与状态反馈使用简体中文。
- 在线 URL 与封面缓存一律不落盘；远程投送只由显式点击触发。
- 行内封面为装饰性，不新增 TalkBack 焦点节点；可点击目标 ≥48dp。
- 可复用现有 `decodeSampled()`/`ArtworkContent()`/`loadSongArtwork()`，不重复造轮子。

---

### Task 1: SimpleLruCache（条数 + 重量双上限，线程安全）

**Files:**
- Create: `android/app/src/main/kotlin/com/example/local_music_player/SimpleLruCache.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/SimpleLruCacheTest.kt`

**Interfaces:**
- Produces: `internal class SimpleLruCache<K, V>(maxEntries: Int, maxWeight: Long = Long.MAX_VALUE, weightOf: (V) -> Long = { 1L })`
- Methods: `get(key: K): V?`、`put(key: K, value: V)`、`remove(key: K)`、`clear()`、`val size: Int`

- [x] **Step 1: 写失败测试**

创建 `SimpleLruCacheTest.kt`：

```kotlin
package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimpleLruCacheTest {
    @Test
    fun evictsLeastRecentlyUsedWhenOverCapacity() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")
        cache.put("c", 3)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun evictsOldestWhenOverWeight() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 10, maxWeight = 10, weightOf = { it.toLong() })
        cache.put("a", 6)
        cache.put("b", 5)
        assertNull(cache.get("a"))
        assertEquals(5, cache.get("b"))
    }

    @Test
    fun replacingSameKeyKeepsSingleEntry() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 2, weightOf = { it.toLong() })
        cache.put("a", 5)
        cache.put("a", 3)
        assertEquals(1, cache.size)
        cache.put("b", 1)
        assertEquals(2, cache.size)
    }

    @Test
    fun removeAndClearReleaseWeight() {
        val cache = SimpleLruCache<String, Int>(maxEntries = 10, maxWeight = 10, weightOf = { it.toLong() })
        cache.put("a", 6)
        cache.put("b", 4)
        cache.remove("a")
        cache.put("c", 5)
        assertEquals(5, cache.get("c"))
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("b"))
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests \"com.example.local_music_player.SimpleLruCacheTest\""`（工作目录 `D:\musicplayer\android`）
Expected: FAIL — `SimpleLruCache` 未定义（编译错误）。

- [x] **Step 3: 最小实现**

创建 `SimpleLruCache.kt`：

```kotlin
package com.example.local_music_player

/** 轻量内存 LRU：超过条数或总重量上限时按最久未访问顺序逐出。线程安全。 */
internal class SimpleLruCache<K, V>(
    private val maxEntries: Int,
    private val maxWeight: Long = Long.MAX_VALUE,
    private val weightOf: (V) -> Long = { 1L },
) {
    private val values = object : LinkedHashMap<K, V>(0, 0.75f, true) {}
    private var totalWeight = 0L
    private val lock = Any()

    val size: Int get() = synchronized(lock) { values.size }

    fun get(key: K): V? = synchronized(lock) { values[key] }

    fun put(key: K, value: V) {
        synchronized(lock) {
            values.put(key, value)?.let { totalWeight -= weightOf(it) }
            totalWeight += weightOf(value)
            evict()
        }
    }

    fun remove(key: K) {
        synchronized(lock) {
            values.remove(key)?.let { totalWeight -= weightOf(it) }
        }
    }

    fun clear() {
        synchronized(lock) {
            values.clear()
            totalWeight = 0L
        }
    }

    private fun evict() {
        val iterator = values.entries.iterator()
        while (iterator.hasNext() && (values.size > maxEntries || totalWeight > maxWeight)) {
            val entry = iterator.next()
            totalWeight -= weightOf(entry.value)
            iterator.remove()
        }
    }
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests \"com.example.local_music_player.SimpleLruCacheTest\""`（工作目录 `D:\musicplayer\android`）
Expected: PASS（4 个测试全绿）。

---

### Task 2: OnlineTrackResolver 超时预算 + resolveSkippingShy（TDD）

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineTrackResolver.kt`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/ShySourceFallbackTest.kt`

**Interfaces:**
- Consumes: `ShyMusicCatalog.resolve`、`NeteaseCatalog/QqCatalog/KuwoCatalog/KugouCatalog.search/resolve`
- Produces: `suspend fun resolve(track: OnlineTrack, quality: YaohuQuality): OnlineResolvedTrack`（Shy 带超时）、`suspend fun resolveSkippingShy(track: OnlineTrack, quality: YaohuQuality): OnlineResolvedTrack`、构造参数 `shyTimeoutMs: Long = 5_000`

- [x] **Step 1: 写失败测试（先改测试文件）**

在 `ShySourceFallbackTest.kt` 顶部加 import：

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
```

把 `resolver(...)` 辅助函数改为可注入超时：

```kotlin
    private fun resolver(
        shy: ShyMusicHttp,
        yaohu: com.example.local_music_player.YaohuHttp,
        shyTimeoutMs: Long = 5_000,
    ) = OnlineTrackResolver(
        shyCatalog = ShyMusicCatalog(shy),
        neteaseCatalog = NeteaseCatalog(yaohu),
        qqCatalog = QqCatalog(yaohu),
        kuwoCatalog = KuwoCatalog(yaohu),
        kugouCatalog = KugouCatalog(yaohu),
        shyTimeoutMs = shyTimeoutMs,
    )
```

给既有 4 个测试方法体加 `= runBlocking { ... }` 包裹（`resolve` 变为 suspend 后直接调用会编译失败）：

- `shy_success_skips_yaohu`、`shy_not_falls_back_to_same_platform_yaohu`、`download_quality_api_value_routes_through_same_resolver` 三个方法签名改为 `= runBlocking {`，方法体末尾补 `}`。
- `both_sources_fail_throws` 改为 `= runBlocking { assertFailsWith<YaohuApiException> { resolver(shy, yaohu).resolve(onlineTrack(), QqQuality.Flac) } }`。

新增两个测试：

```kotlin
    @Test
    fun shy_timeout_falls_back_to_same_platform_yaohu() = runBlocking {
        val shy = RecordingShyHttp { parameters ->
            when (parameters["type"]) {
                "qq_url" -> {
                    Thread.sleep(200)
                    "http://shy/audio.flac"
                }
                "qq_lrc" -> """{"lyric":"shy lrc"}"""
                else -> error("unexpected type ${parameters["type"]}")
            }
        }
        val yaohu = RecordingYaohuHttp { path, parameters ->
            when {
                path == "/api/music/qq_plus" && parameters.containsKey("g") -> qqSearchJson
                path == "/api/music/qq_plus" -> qqResolveJson
                else -> error("unexpected path $path")
            }
        }

        val resolved = withContext(Dispatchers.Default) {
            resolver(shy, yaohu, shyTimeoutMs = 50).resolve(onlineTrack(), QqQuality.Flac)
        }

        assertEquals("http://yaohu/audio.flac", resolved.playUrl)
        assertTrue(yaohu.requests.isNotEmpty())
    }

    @Test
    fun resolve_skipping_shy_goes_directly_to_yaohu() = runBlocking {
        val shy = RecordingShyHttp { error("Shy 不应被调用") }
        val yaohu = RecordingYaohuHttp { path, parameters ->
            when {
                path == "/api/music/qq_plus" && parameters.containsKey("g") -> qqSearchJson
                path == "/api/music/qq_plus" -> qqResolveJson
                else -> error("unexpected path $path")
            }
        }

        val resolved = resolver(shy, yaohu).resolveSkippingShy(onlineTrack(), QqQuality.Flac)

        assertEquals("http://yaohu/audio.flac", resolved.playUrl)
    }
```

- [x] **Step 2: 运行测试确认失败**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests \"com.example.local_music_player.ShySourceFallbackTest\""`（工作目录 `D:\musicplayer\android`）
Expected: 编译失败 — `OnlineTrackResolver` 无 `shyTimeoutMs` 参数、`resolve` 非 suspend、无 `resolveSkippingShy`。

- [x] **Step 3: 最小实现**

修改 `OnlineTrackResolver.kt`：

```kotlin
package com.example.local_music_player

import kotlinx.coroutines.withTimeout

class OnlineTrackResolver(
    private val shyCatalog: ShyMusicCatalog = ShyMusicCatalog(),
    private val neteaseCatalog: NeteaseCatalog = NeteaseCatalog(),
    private val qqCatalog: QqCatalog = QqCatalog(),
    private val kuwoCatalog: KuwoCatalog = KuwoCatalog(),
    private val kugouCatalog: KugouCatalog = KugouCatalog(),
    private val shyTimeoutMs: Long = 5_000,
) {
    /** 主源 ShyMusic（带时间预算）；失败时同平台妖狐搜索匹配兜底，不跨平台、不换歌。 */
    suspend fun resolve(track: OnlineTrack, quality: YaohuQuality): OnlineResolvedTrack {
        val shy = runCatching { withTimeout(shyTimeoutMs) { shyCatalog.resolve(track, quality) } }.getOrNull()
        if (shy != null) return shy.toOnlineResolvedTrack()
        return resolveViaYaohu(track, quality)
    }

    /** 已确认 Shy 地址不可播时跳过 Shy，直接走妖狐同平台兜底。 */
    suspend fun resolveSkippingShy(track: OnlineTrack, quality: YaohuQuality): OnlineResolvedTrack =
        resolveViaYaohu(track, quality)

    suspend fun resolve(track: OnlineTrack, qualityApiValue: String): OnlineResolvedTrack =
        resolve(track, qualityFor(track.platform, qualityApiValue))
    // ...resolveViaYaohu / qualityFor 等其余代码保持不变
```

其余私有方法（`resolveViaYaohu`、`qualityFor`、`onlineExtensionForMime`）与文件底部辅助函数保持不变。

- [x] **Step 4: 运行测试确认通过**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests \"com.example.local_music_player.ShySourceFallbackTest\""`（工作目录 `D:\musicplayer\android`）
Expected: PASS（6 个测试全绿，其中 2 个新增）。

---

### Task 3: 缓存统一为 LRU + NeteaseResolutionCache 加容量与 clear

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/YaohuNeteaseApi.kt:74-86`
- Test: `android/app/src/test/kotlin/com/example/local_music_player/NeteaseResolutionCacheTest.kt`

**Interfaces:**
- Consumes: `SimpleLruCache`（Task 1）
- Produces: `NeteaseResolutionCache(maxEntries: Int = 50)`，新增 `fun clear()`；`get/put/remove` 签名不变

- [x] **Step 1: 写失败测试**

在 `NeteaseResolutionCacheTest.kt` 增加：

```kotlin
    @Test
    fun evictsLeastRecentlyUsedWhenOverCapacity() {
        val cache = NeteaseResolutionCache(maxEntries = 2)
        val first = resolved("https://a/1.flac")
        val second = resolved("https://a/2.flac")
        val third = resolved("https://a/3.flac")
        cache.put(1L, NeteaseQuality.Lossless, first)
        cache.put(2L, NeteaseQuality.Lossless, second)
        cache.get(1L, NeteaseQuality.Lossless)
        cache.put(3L, NeteaseQuality.Lossless, third)
        assertNull(cache.get(2L, NeteaseQuality.Lossless))
        assertEquals(first, cache.get(1L, NeteaseQuality.Lossless))
    }

    @Test
    fun clearReleasesAllEntries() {
        val cache = NeteaseResolutionCache()
        cache.put(1L, NeteaseQuality.Lossless, resolved("https://a/1.flac"))
        cache.clear()
        assertNull(cache.get(1L, NeteaseQuality.Lossless))
    }

    private fun resolved(url: String) = NeteaseResolvedTrack(
        playUrl = url,
        actualQuality = NeteaseQuality.Lossless,
        lyrics = null,
        mimeType = "audio/flac",
        endpoint = NeteaseEndpoint.Vip,
    )
```

- [x] **Step 2: 运行测试确认失败**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests \"com.example.local_music_player.NeteaseResolutionCacheTest\""`（工作目录 `D:\musicplayer\android`）
Expected: FAIL — 无 `maxEntries` 参数、无 `clear()`。

- [x] **Step 3: 最小实现**

替换 `YaohuNeteaseApi.kt:74-86` 的 `NeteaseResolutionCache` 类：

```kotlin
class NeteaseResolutionCache(private val maxEntries: Int = 50) {
    private val values = SimpleLruCache<Pair<Long, NeteaseQuality>, NeteaseResolvedTrack>(maxEntries)

    fun get(trackId: Long, quality: NeteaseQuality) = values.get(trackId to quality)

    fun put(trackId: Long, quality: NeteaseQuality, resolved: NeteaseResolvedTrack) =
        values.put(trackId to quality, resolved)

    fun remove(trackId: Long, quality: NeteaseQuality) = values.remove(trackId to quality)

    fun clear() = values.clear()
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest --tests \"com.example.local_music_player.NeteaseResolutionCacheTest\""`（工作目录 `D:\musicplayer\android`）
Expected: PASS（3 个测试全绿）。

---

### Task 4: ViewModel 播放取址稳定 + 缓存清理 + 预解析（编译验证）

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`

**Interfaces:**
- Consumes: `SimpleLruCache`、`OnlineTrackResolver.resolve/resolveSkippingShy`、`OnlineResolvedTrack`
- Produces: 私有 `suspend fun playOnlineWithFallback(onlineTrack: OnlineTrack, quality: YaohuQuality, durationMs: Long, positionMs: Long): OnlineResolvedTrack`；`onlineResolutionCache` 类型改为 `SimpleLruCache<OnlineCacheKey, OnlineResolvedTrack>(100)`；`NeteasePlaybackResult.Online(resolved: OnlineResolvedTrack)` 取代原 `Shy`

- [x] **Step 1: 数据模型与缓存字段**

`NativeMusicViewModel.kt`：

1. `NativeTrack`（约 79-94 行）末尾加字段：

```kotlin
    val isCueTrack: Boolean = false,
    val artworkUrl: String? = null,
)
```

2. 缓存字段（约 478-479 行）：

```kotlin
private val neteaseResolutionCache = NeteaseResolutionCache()
private val onlineResolutionCache = SimpleLruCache<OnlineCacheKey, OnlineResolvedTrack>(maxEntries = 100)
```

3. 在 `private var onlineSearchJob: Job? = null`（约 488 行）旁新增：

```kotlin
    private var onlinePreResolveJob: Job? = null
```

4. `neteaseTrackToNativeTrack`（约 1434 行）与 `onlineTrackToNativeTrack`（约 1439 行）各加一行 `artworkUrl = track.artworkUrl,`。

- [x] **Step 2: 替换 onlineResolutionCache 下标用法**

`resolveOtherOnlineForCast`（约 1537-1540 行）改为：

```kotlin
        val key = OnlineCacheKey(track.platform, track.key, quality.apiValue)
        if (forceRefresh) onlineResolutionCache.remove(key)
        onlineResolutionCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { onlineResolver.resolve(track, quality) }.also {
            onlineResolutionCache.put(key, it)
        }
```

- [x] **Step 3: 重构 startOtherOnlinePlayback 为源尝试序列**

`startOtherOnlinePlayback`（约 1878-1916 行）方法体替换为：

```kotlin
    private fun startOtherOnlinePlayback(
        queue: List<NativeTrack>,
        index: Int,
        track: NativeTrack,
        onlineTrack: OnlineTrack,
        startPosition: Long,
    ) {
        if (onlineStartInProgress) return
        onlineStartInProgress = true
        suppressNeteaseAutoAdvance = false
        val quality = onlineQuality(onlineTrack.platform, download = false)
        viewModelScope.launch {
            val resolved = runCatching {
                withContext(Dispatchers.IO) {
                    playOnlineWithFallback(onlineTrack, quality, track.durationMs, startPosition)
                }
            }.getOrNull()
            if (resolved != null) {
                onlineStartInProgress = false
                applyOtherOnlineSuccess(queue, index, track, resolved, startPosition)
                return@launch
            }
            onlineStartInProgress = false
            autoAdvanceInProgress = false
            suppressNeteaseAutoAdvance = true
            _state.value = _state.value.copy(
                isStreaming = false,
                playing = false,
                status = "暂时无法播放这首歌曲，请稍后重试",
            )
        }
    }

    /** 依次尝试 缓存 → ShyMusic → 妖狐同平台兜底，返回 BASS 真正打开成功的地址并写入缓存。 */
    private suspend fun playOnlineWithFallback(
        onlineTrack: OnlineTrack,
        quality: YaohuQuality,
        durationMs: Long,
        positionMs: Long,
    ): OnlineResolvedTrack {
        val cacheKey = OnlineCacheKey(onlineTrack.platform, onlineTrack.key, quality.apiValue)
        onlineResolutionCache.get(cacheKey)?.let { cached ->
            if (runCatching { playOtherOnlineUrl(cached, durationMs, positionMs) }.isSuccess) return cached
            onlineResolutionCache.remove(cacheKey)
        }
        val resolved = runCatching { onlineResolver.resolve(onlineTrack, quality) }.getOrNull()
        if (resolved != null && runCatching { playOtherOnlineUrl(resolved, durationMs, positionMs) }.isSuccess) {
            onlineResolutionCache.put(cacheKey, resolved)
            return resolved
        }
        val fallback = runCatching { onlineResolver.resolveSkippingShy(onlineTrack, quality) }.getOrNull()
            ?: throw YaohuApiException("未获取到可播放的音频")
        playOtherOnlineUrl(fallback, durationMs, positionMs)
        onlineResolutionCache.put(cacheKey, fallback)
        return fallback
    }
```

注意：原方法里的 `val cacheKey = OnlineCacheKey(...)` 已移入 helper，删除旧行。

- [x] **Step 4: 重构 startNeteasePlaybackWithFallback 让 Shy 播放失败继续走妖狐链**

1. 替换 sealed interface（约 481-485 行）：

```kotlin
private sealed interface NeteasePlaybackResult {
    data class Online(val resolved: OnlineResolvedTrack) : NeteasePlaybackResult
    data class Yaohu(val resolved: NeteaseResolvedTrack) : NeteasePlaybackResult
}
```

2. 替换 `withContext(Dispatchers.IO) { ... }` 解析块（约 1792-1830 行）：

```kotlin
                withContext(Dispatchers.IO) {
                    val quality = _state.value.neteasePlaybackQuality
                    val onlineTrack = onlineTrackById[track.id]
                    // 1) 统一在线地址缓存（含预解析结果与历史成功地址）
                    if (onlineTrack != null) {
                        val onlineKey = OnlineCacheKey(onlineTrack.platform, onlineTrack.key, quality.apiValue)
                        onlineResolutionCache.get(onlineKey)?.let { cached ->
                            if (runCatching { player.playUrl(cached.playUrl, track.durationMs, startPosition) }.isSuccess) {
                                return@withContext NeteasePlaybackResult.Online(cached)
                            }
                            onlineResolutionCache.remove(onlineKey)
                        }
                    }
                    // 2) ShyMusic 主源优先；地址打不开同样视为失败，继续走妖狐兜底
                    if (onlineTrack != null) {
                        val shy = runCatching { shyCatalog.resolve(onlineTrack, quality) }.getOrNull()
                        if (shy != null && runCatching { player.playUrl(shy.playUrl, track.durationMs, startPosition) }.isSuccess) {
                            val resolved = shy.toOnlineResolvedTrack()
                            onlineResolutionCache.put(
                                OnlineCacheKey(onlineTrack.platform, onlineTrack.key, quality.apiValue),
                                resolved,
                            )
                            return@withContext NeteasePlaybackResult.Online(resolved)
                        }
                    }
                    // 3) 妖狐缓存与同平台兜底链（原逻辑）
                    val cacheHit = runCatching {
                        neteaseResolutionCache.get(track.id, quality)?.also { cached ->
                            player.playUrl(cached.playUrl, cached.durationMs ?: track.durationMs, startPosition)
                        }
                    }.getOrNull()
                    if (cacheHit != null) return@withContext NeteasePlaybackResult.Yaohu(cacheHit)
                    neteaseResolutionCache.remove(track.id, quality)
                    val yaohuTrack = findNeteaseYaohuMatch(onlineTrack, quality)
                        ?: throw YaohuApiException("未获取到可播放的音频")
                    var lastError: Throwable? = null
                    var resolved: NeteaseResolvedTrack? = null
                    NeteaseEndpoint.playbackAttempts().forEachIndexed { attempt, endpoint ->
                        if (resolved != null) return@forEachIndexed
                        try {
                            resolved = neteaseCatalog.resolve(yaohuTrack, quality, endpoint).also { candidate ->
                                player.playUrl(candidate.playUrl, candidate.durationMs ?: track.durationMs, startPosition)
                                neteaseResolutionCache.put(track.id, quality, candidate)
                            }
                        } catch (error: Throwable) {
                            lastError = error
                            if (attempt < NeteaseEndpoint.playbackAttempts().lastIndex) delay(400)
                        }
                    }
                    resolved ?: throw (lastError ?: YaohuApiException("未获取到可播放的音频"))
                    NeteasePlaybackResult.Yaohu(resolved)
                }
```

3. `onSuccess` 里把 `is NeteasePlaybackResult.Shy ->` 分支改为：

```kotlin
                    is NeteasePlaybackResult.Online -> applyOtherOnlineSuccess(
                        queue,
                        index,
                        track,
                        result.resolved,
                        startPosition,
                    )
```

- [x] **Step 5: 搜索/切平台清理缓存 + 预解析前 3 首**

1. `selectOnlinePlatform`（约 1145 行）在 `_state.value = _state.value.copy(...)` 之前加：

```kotlin
        onlinePreResolveJob?.cancel()
        onlineResolutionCache.clear()
        neteaseResolutionCache.clear()
```

2. `searchOnline`（约 1175 行）在 `onlineSearchJob?.cancel()` 之前加同样的三行。

3. `searchOnline` 的 `onSuccess` 里、`_state.value = _state.value.copy(...)` 之后加：

```kotlin
                    preResolveOnlineTracks(merged.take(3))
```

4. 新增预解析方法（放在 `fetchOnlineSearch` 附近）：

```kotlin
    /** 搜索返回后后台预解析前 3 首，写入统一地址缓存；新搜索/切平台时取消。 */
    private fun preResolveOnlineTracks(tracks: List<OnlineTrack>) {
        if (tracks.isEmpty()) return
        onlinePreResolveJob?.cancel()
        onlinePreResolveJob = viewModelScope.launch {
            tracks.forEach { track ->
                val quality = onlineQuality(track.platform, download = false)
                val key = OnlineCacheKey(track.platform, track.key, quality.apiValue)
                if (onlineResolutionCache.get(key) != null) return@forEach
                runCatching { withContext(Dispatchers.IO) { onlineResolver.resolve(track, quality) } }
                    .onSuccess { onlineResolutionCache.put(key, it) }
            }
        }
    }
```

- [x] **Step 6: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`（工作目录 `D:\musicplayer\android`）
Expected: BUILD SUCCESSFUL（若 `shyCatalog`/`onlineResolver` 成员缺失，检查 `NativeMusicViewModel` 中对应私有字段存在）。

---

### Task 5: 封面渲染（OnlineArtwork + 搜索行/专辑行/详情/正在播放/迷你）

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
- Modify: `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`

**Interfaces:**
- Consumes: `NativeTrack.artworkUrl`（Task 4）、`OnlineTrack.artworkUrl`、`OnlineAlbum.coverUrl`
- Produces: `internal fun OnlineArtwork(url: String?, modifier: Modifier)`、`internal object OnlineArtworkCache`、`private fun loadRemoteArtwork(url: String): Bitmap?`

- [x] **Step 1: 新增 OnlineArtwork 与缓存（MusicApp.kt，放在 ArtworkContent 附近约 3075 行）**

```kotlin
/** 远程封面内存缓存：100 张 / 16MB，LRU 逐出；onTrimMemory 时清空。 */
internal object OnlineArtworkCache {
    private val cache = SimpleLruCache<String, Bitmap>(
        maxEntries = 100,
        maxWeight = 16L * 1024 * 1024,
        weightOf = { it.byteCount.toLong() },
    )
    fun get(url: String): Bitmap? = cache.get(url)
    fun put(url: String, bitmap: Bitmap) = cache.put(url, bitmap)
    fun clear() = cache.clear()
}

@Composable
internal fun OnlineArtwork(url: String?, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = url?.let(OnlineArtworkCache::get), url) {
        value = if (url == null) null else withContext(Dispatchers.IO) {
            OnlineArtworkCache.get(url) ?: loadRemoteArtwork(url)?.also { OnlineArtworkCache.put(url, it) }
        }
    }
    ArtworkContent(bitmap, modifier)
}

private fun loadRemoteArtwork(url: String): Bitmap? = runCatching {
    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 5_000
        requestMethod = "GET"
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        connection.inputStream.use { input -> decodeSampled(input.readBytes(), 720) }
    } finally {
        connection.disconnect()
    }
}.getOrNull()
```

- [x] **Step 2: AlbumArt / SongArtworkThumbnail 分支远程封面**

`AlbumArt`（约 3032 行）替换为：

```kotlin
@Composable
private fun AlbumArt(track: NativeTrack) {
    if (track.artworkUrl != null) {
        Card(
            Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = "歌曲封面：${track.title}" },
        ) {
            OnlineArtwork(track.artworkUrl, Modifier.fillMaxSize())
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, track.id) {
        value = withContext(Dispatchers.IO) { loadSongArtwork(context, track, 720) }
    }
    val modifier = Modifier.fillMaxWidth().height(180.dp).let {
        if (bitmap == null) it else it.semantics { contentDescription = "歌曲封面：${track.title}" }
    }
    Card(modifier) {
        ArtworkContent(bitmap, Modifier.fillMaxSize())
    }
}
```

`SongArtworkThumbnail`（约 3057 行）替换为：

```kotlin
@Composable
private fun SongArtworkThumbnail(track: NativeTrack, modifier: Modifier) {
    if (track.artworkUrl != null) {
        OnlineArtwork(track.artworkUrl, modifier)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, track.id) {
        value = withContext(Dispatchers.IO) { loadSongArtwork(context, track, 192) }
    }
    ArtworkContent(bitmap, modifier)
}
```

- [x] **Step 3: MiniPlayer 在线封面缩略图（约 3226 行）**

把 MiniPlayer 内第一个文本 `Column` 包进 `Row`（文本列加 `Modifier.weight(1f)`），前面条件显示封面：

```kotlin
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (track.artworkUrl != null) {
                    OnlineArtwork(track.artworkUrl, Modifier.size(48.dp))
                }
                Column(
                    Modifier.weight(1f).sizeIn(minHeight = 48.dp).clickable(onClick = onOpenNowPlaying)
                        .clearAndSetSemantics {
                            contentDescription = "${track.title}，${track.artist}"
                            onClick(label = "打开正在播放") { onOpenNowPlaying(); true }
                        },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
```

（原文本 `Column` 的 `Modifier.fillMaxWidth()` 换成 `Modifier.weight(1f)`，其余不变。）

- [x] **Step 4: 搜索结果歌曲行 / 专辑行 / 专辑详情封面（OnlineScreen.kt）**

1. 文件顶部 import 增加：

```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
```

2. `OnlineTrackRow` 行内 `Column` 之前加封面，行 minHeight 提到 56dp：

```kotlin
        Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp).combinedClickable(onClick = onPlay, onLongClick = onShowDownload)
```

```kotlin
        OnlineArtwork(track.artworkUrl, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
```

3. `OnlineAlbumRow` 行内 `Column` 之前加封面：

```kotlin
        OnlineArtwork(album.coverUrl, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
```

4. `OnlineAlbumDetailScreen` 头部 `Row` 内、标题 `Column` 之前加：

```kotlin
            OnlineArtwork(album.coverUrl, Modifier.size(96.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clearAndSetSemantics {}) {
```

- [x] **Step 5: MusicApplication.onTrimMemory 清图片缓存**

`MusicApplication.kt` 增加：

```kotlin
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            OnlineArtworkCache.clear()
        }
    }
```

- [x] **Step 6: 编译验证**

Run: `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`（工作目录 `D:\musicplayer\android`）
Expected: BUILD SUCCESSFUL。

---

### Task 6: 全量验证

**Files:** 无（只跑命令）

- [x] **Step 1: diff 检查**

Run: `rtk git diff --check`
Expected: 无空白错误输出。

- [x] **Step 2: 全量单测**

Run: `rtk cmd /c "gradlew.bat :app:testDebugUnitTest"`（工作目录 `D:\musicplayer\android`）
Expected: 全部测试通过（既有 108+ 个 + 新增 SimpleLruCache/NeteaseResolutionCache/ShySourceFallback 测试）。

- [x] **Step 3: Release 构建**

Run: `rtk cmd /c "gradlew.bat :app:assembleRelease"`（工作目录 `D:\musicplayer\android`）
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 真机冒烟（记录到 `scripts/smoke-checklist.md` 或 release 文档）**

按 `scripts/smoke-checklist.md`：四平台搜索歌曲/专辑封面显示；专辑详情封面；在线歌曲正在播放页与迷你播放器封面；断源场景首次播放换源成功（Shy 打不开走妖狐）；同一曲目重复播放不重复请求（logcat 观察 qq_url/wyy_url 请求次数）；TalkBack 歌曲行单一节点；回归本地播放/DLNA/Chromecast/蓝牙；logcat 无本应用 FATAL/ANR。
