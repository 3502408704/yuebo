package com.example.local_music_player

import android.content.Context
import android.util.Log
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSException
import java.io.IOException

/** 搜索结果曲目；raw 保留插件原始 item（JSObject 或 Map），播放时回传 getMediaSource。 */
data class StreamTrack(
    val key: String,
    val pluginId: String,
    val sourceName: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val platform: String,
    val artwork: String? = null,
    val raw: Any?,
)

data class SearchPage(val isEnd: Boolean, val items: List<StreamTrack>)

/** 插件解析出的真实播放地址与请求头。 */
data class MediaSource(
    val url: String,
    val headers: Map<String, String>,
    val resolvedQuality: StreamQuality? = null,
)

/** 专辑/歌单/榜单条目（MusicFree 协议 IAlbumItem/IMusicSheetItem 同形）。 */
data class StreamCollection(
    val key: String,
    val pluginId: String,
    val name: String,
    val artist: String,
    val artwork: String?,
    val description: String?,
    val worksNum: String?,
    val kind: StreamCollectionKind,
    val raw: Any?,
)

enum class StreamCollectionKind { Album, Sheet, TopList }

/** 歌手条目（IArtistItem 同形）。 */
data class StreamArtist(
    val key: String,
    val pluginId: String,
    val name: String,
    val avatar: String?,
    val raw: Any?,
)

/** 榜单分组（getTopLists 返回 {title, data:[sheetItem]}）。 */
data class StreamTopListGroup(val title: String, val sheets: List<StreamCollection>)

/** 歌词（getLyric 返回 {rawLrc, translation}）。 */
data class StreamLyrics(val rawLrc: String?, val translation: String?)

private const val TAG_DEBUG = "YueboMF"

internal fun musicFreePageItems(page: Map<*, *>): List<Map<*, *>> {
    val items = page["data"] as? List<*> ?: page["musicList"] as? List<*> ?: return emptyList()
    return items.mapNotNull { it as? Map<*, *> }
}

internal fun musicFreeQualityValue(supported: List<String>, quality: StreamQuality): String {
    val values = supported.map { it.trim() }.filter { it.isNotEmpty() }
    if (values.isEmpty()) return quality.pluginValue
    val last = values.lastIndex
    val index = when (quality) {
        StreamQuality.Low -> 0
        StreamQuality.Standard -> minOf(1, last)
        StreamQuality.High -> minOf(2, last)
        StreamQuality.Super -> last
    }
    return values[index]
}

/**
 * 单个 MusicFree 插件：包装 QuickJsRuntime，负责加载校验、搜索、钻取、歌词与取流。
 * 所有函数在插件缺失对应实现时返回空结果（协议里函数均可选，UI 按能力显隐）。
 */
class MusicFreePlugin(
    private val runtime: QuickJsRuntime,
    private val source: MusicFreeSource,
    private val js: String,
) : AutoCloseable {

    private var platformValue: String? = null
    private var searchFn: JSFunction? = null
    private var mediaSourceFn: JSFunction? = null
    private var albumInfoFn: JSFunction? = null
    private var sheetInfoFn: JSFunction? = null
    private var artistWorksFn: JSFunction? = null
    private var topListsFn: JSFunction? = null
    private var topListDetailFn: JSFunction? = null
    private var lyricFn: JSFunction? = null
    private var musicInfoFn: JSFunction? = null
    private var importMusicSheetFn: JSFunction? = null
    private var supportedSearchTypes: Set<String> = setOf("music", "album", "artist", "sheet")
    private var supportedQualityValues: List<String> = emptyList()
    private var loaded = false

    /** 插件平台名；未加载时抛出。 */
    val platform: String
        get() = platformValue ?: error("插件尚未加载：${source.name}")

    val displayName: String
        get() = platform

    // —— 能力位（load 后有效；UI 按能力显隐入口） ——
    val supportsMusicSearch: Boolean get() = "music" in supportedSearchTypes
    val supportsAlbumSearch: Boolean get() = "album" in supportedSearchTypes
    val supportsArtistSearch: Boolean get() = "artist" in supportedSearchTypes
    val supportsSheetSearch: Boolean get() = "sheet" in supportedSearchTypes
    val hasAlbumInfo: Boolean get() = albumInfoFn != null
    val hasSheetInfo: Boolean get() = sheetInfoFn != null
    val hasArtistWorks: Boolean get() = artistWorksFn != null
    val hasTopLists: Boolean get() = topListsFn != null && topListDetailFn != null
    val hasLyric: Boolean get() = lyricFn != null

    /** Native quality values declared by the plugin, in its advertised order. */
    fun qualityOptions(): List<StreamQualityOption> {
        load()
        return supportedQualityValues.mapIndexed { index, value ->
            StreamQualityOption(
                value = value,
                label = streamQualityLabel(platform, value),
                tier = streamQualityTier(index),
            )
        }
    }

    /** CommonJS 包装后 evaluate，校验 platform(字符串)/search(函数)；顶层副作用容忍，不 await 顶层 Promise。 */
    fun load() {
        if (loaded) return
        try {
            loadInner()
            loaded = true
        } catch (t: Throwable) {
            Log.e(TAG_DEBUG, "load 失败 ${source.name}: ${t::class.simpleName}: ${t.message}", t)
            throw t
        }
    }

    private fun loadInner() {
        val wrapper = "(function() {\n" +
            "var module = { exports: {} };\n" +
            "(function(module, exports, require) {\n$js\n})(module, module.exports, require);\n" +
            "return module.exports;\n})();"
        val moduleExports = runtime.evaluateObject(wrapper)
            ?: throw QuickJSException("插件加载失败：${source.name}")
        runtime.hold(moduleExports)
        var platformName: String? = null
        var search: JSFunction? = null
        var mediaSource: JSFunction? = null
        runtime.onJsThread {
            platformName = runCatching { moduleExports.getString("platform") }.getOrElse {
                Log.e(TAG_DEBUG, "getString platform ERR: ${it.message}")
                null
            }
            search = runCatching { moduleExports.getJSFunction("search") }.getOrElse {
                Log.e(TAG_DEBUG, "getJSFunction search ERR: ${it.message}")
                null
            }
            mediaSource = runCatching { moduleExports.getJSFunction("getMediaSource") }.getOrElse {
                Log.e(TAG_DEBUG, "getJSFunction getMediaSource ERR: ${it.message}")
                null
            }
            if (platformName.isNullOrBlank() || search == null) {
                // Parcel/webpack 打包插件形如 exports.default = 插件实例，需取 default 再校验。
                val defaultObj = runCatching { moduleExports.getJSObject("default") }.getOrNull()
                if (defaultObj != null) {
                    platformName = runCatching { defaultObj.getString("platform") }.getOrNull() ?: platformName
                    search = runCatching { defaultObj.getJSFunction("search") }.getOrNull() ?: search
                    mediaSource = runCatching { defaultObj.getJSFunction("getMediaSource") }.getOrNull() ?: mediaSource
                    runtime.release(defaultObj)
                }
            }
            fun stringArray(name: String): List<String> {
                val array = runCatching { moduleExports.getJSArray(name) }.getOrNull() ?: return emptyList()
                val values = mutableListOf<String>()
                for (index in 0 until array.length()) {
                    runCatching { array.get(index) as? String }.getOrNull()?.let(values::add)
                }
                runtime.release(array)
                return values
            }
            // 声明的搜索类型（supportedSearchType 数组；缺省假定全部支持）。
            stringArray("supportedSearchType").toSet().takeIf { it.isNotEmpty() }
                ?.let { supportedSearchTypes = it }
            supportedQualityValues = stringArray("supportedQualities")
            fun capability(name: String): JSFunction? =
                runCatching { moduleExports.getJSFunction(name) }.getOrNull()?.also { runtime.hold(it) }
            albumInfoFn = capability("getAlbumInfo")
            sheetInfoFn = capability("getMusicSheetInfo")
            artistWorksFn = capability("getArtistWorks")
            topListsFn = capability("getTopLists")
            topListDetailFn = capability("getTopListDetail")
            lyricFn = capability("getLyric")
            musicInfoFn = capability("getMusicInfo")
            importMusicSheetFn = capability("importMusicSheet")
        }
        if (platformName.isNullOrBlank()) {
            throw QuickJSException("插件 platform 无效：${source.name}")
        }
        if (search == null && topListsFn == null) {
            throw QuickJSException("插件缺少 search 函数：${source.name}")
        }
        platformValue = platformName
        searchFn = search?.let { runtime.hold(it) as JSFunction }
        mediaSourceFn = mediaSource?.let { runtime.hold(it) as JSFunction }
    }

    /** 搜索（type 恒 music）；异步结果经 jsToKotlin 已转 Map，同步结果为 JSObject，两种都处理。 */
    fun search(query: String, page: Int): SearchPage {
        load()
        val fn = searchFn ?: return SearchPage(isEnd = true, items = emptyList())
        val raw = runtime.callFunctionAsync(fn, 8_000, query, page, "music")
        return parseTrackPage(raw)
    }

    /** 专辑/歌单/歌手作品搜索：同 search 签名但 type 不同，结果映射为对应模型。 */
    fun searchAlbums(query: String, page: Int): List<StreamCollection> =
        searchCollections(query, page, "album", StreamCollectionKind.Album)

    fun searchArtists(query: String, page: Int): List<StreamArtist> {
        load()
        val fn = searchFn ?: return emptyList()
        val raw = runtime.callFunctionAsync(fn, 8_000, query, page, "artist")
        return dataItems(raw).mapIndexedNotNull { index, item -> artistFromMap(item, index) }
    }

    fun searchSheets(query: String, page: Int): List<StreamCollection> =
        searchCollections(query, page, "sheet", StreamCollectionKind.Sheet)

    private fun searchCollections(query: String, page: Int, type: String, kind: StreamCollectionKind): List<StreamCollection> {
        load()
        val fn = searchFn ?: return emptyList()
        val raw = runtime.callFunctionAsync(fn, 8_000, query, page, type)
        return dataItems(raw).mapIndexedNotNull { index, item -> collectionFromMap(item, kind, index) }
    }

    /** 专辑详情：{isEnd, musicList}。 */
    fun getAlbumInfo(collection: StreamCollection, page: Int): SearchPage {
        val fn = albumInfoFn ?: return SearchPage(isEnd = true, items = emptyList())
        return parseTrackPage(runtime.callFunctionAsync(fn, 10_000, collection.raw, page))
    }

    /** 歌单详情：{isEnd, musicList}。 */
    fun getMusicSheetInfo(collection: StreamCollection, page: Int): SearchPage {
        val fn = sheetInfoFn ?: return SearchPage(isEnd = true, items = emptyList())
        return parseTrackPage(runtime.callFunctionAsync(fn, 10_000, collection.raw, page))
    }

    /** 从平台链接或平台 ID 导入歌单。MusicFree 插件通过 importMusicSheet 暴露该能力。 */
    fun importMusicSheet(urlLike: String): List<StreamTrack> {
        load()
        val fn = importMusicSheetFn ?: return emptyList()
        val raw = runtime.callFunctionAsync(fn, 20_000, urlLike)
        val value = runtime.jsToKotlin(raw)
        val items = when (value) {
            is List<*> -> value
            is Map<*, *> -> musicFreePageItems(value)
            else -> emptyList()
        }
        return items.mapNotNull { item ->
            (item as? Map<*, *>)?.let { trackFromMap(it, 0) }
        }
    }

    /** 榜单详情：{isEnd, musicList}。 */
    fun getTopListDetail(collection: StreamCollection, page: Int): SearchPage {
        val fn = topListDetailFn ?: return SearchPage(isEnd = true, items = emptyList())
        return parseTrackPage(runtime.callFunctionAsync(fn, 10_000, collection.raw, page))
    }

    /** 歌手作品：type=music|album|artist，这里只用 music。 */
    fun getArtistWorks(artist: StreamArtist, page: Int): SearchPage {
        val fn = artistWorksFn ?: return SearchPage(isEnd = true, items = emptyList())
        return parseTrackPage(runtime.callFunctionAsync(fn, 10_000, artist.raw, page, "music"))
    }

    /** 榜单目录：分组返回。 */
    fun getTopLists(): List<StreamTopListGroup> {
        load()
        val fn = topListsFn ?: return emptyList()
        val raw = runtime.callFunctionAsync(fn, 10_000) as? List<*> ?: return emptyList()
        return raw.mapIndexedNotNull { index, group ->
            val map = group as? Map<*, *> ?: return@mapIndexedNotNull null
            val title = map["title"]?.toString().orEmpty()
            val sheets = (map["data"] as? List<*>)
                ?.mapIndexedNotNull { sheetIndex, item ->
                    (item as? Map<*, *>)?.let { collectionFromMap(it, StreamCollectionKind.TopList, sheetIndex) }
                }
                ?: emptyList()
            if (sheets.isEmpty()) null else StreamTopListGroup(title.ifBlank { "榜单 ${index + 1}" }, sheets)
        }
    }

    /** 歌词；插件不支持或未命中返回 null。 */
    fun getLyric(track: StreamTrack): StreamLyrics? {
        val fn = lyricFn ?: return null
        val raw = runCatching { runtime.callFunctionAsync(fn, 8_000, track.raw) }.getOrNull() ?: return null
        val map = raw as? Map<*, *> ?: return null
        val rawLrc = (map["rawLrc"] ?: map["lrc"])?.toString()?.takeIf { it.isNotBlank() }
        val translation = map["translation"]?.toString()?.takeIf { it.isNotBlank() }
        return if (rawLrc != null || translation != null) StreamLyrics(rawLrc, translation) else null
    }

    /**
     * 详情补全：部分平台的搜索 item 缺少取流所需字段，getMusicInfo 可补全。
     * 失败/插件未实现时原样返回 [track.raw]。
     */
    fun enrichTrack(track: StreamTrack): Any? {
        val fn = musicInfoFn ?: return track.raw
        return runCatching {
            runtime.callFunctionAsync(fn, 8_000, track.raw) as? Map<*, *>
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: track.raw
    }

    /** 取真实播放地址；按请求档位→低档逐级降档，全部失败返回 null。 */
    fun getMediaSource(item: Any?, requested: StreamQuality, requestedValue: String? = null): MediaSource? {
        load()
        val mediaFn = mediaSourceFn ?: return directUrl(item)
        val nativeValues = supportedQualityValues.map(String::trim).filter(String::isNotEmpty)
        val values = if (requestedValue != null && requestedValue in nativeValues) {
            val selected = nativeValues.indexOf(requestedValue)
            nativeValues.subList(0, selected + 1).asReversed()
        } else {
            requested.fallbackOrder().map { quality -> musicFreeQualityValue(nativeValues, quality) }
        }
        for ((index, pluginQuality) in values.distinct().withIndex()) {
            val quality = if (requestedValue != null && requestedValue in nativeValues) {
                streamQualityTier(nativeValues.indexOf(pluginQuality))
            } else {
                requested.fallbackOrder().getOrElse(index) { StreamQuality.Low }
            }
            val result = runCatching {
                mediaSourceFrom(runtime.callFunctionAsync(mediaFn, 8_000, item, pluginQuality), quality)
            }.getOrElse {
                Log.e(TAG_DEBUG, "getMediaSource ${source.name} $pluginQuality ERR: ${it.message}")
                null
            }
            if (result != null) return result
        }
        Log.e(TAG_DEBUG, "getMediaSource ${source.name} 全部档位失败 itemKeys=${(item as? Map<*, *>)?.keys}")
        return null
    }

    override fun close() {
        runtime.close()
    }

    // ==== 结果解析 ====

    private fun parseTrackPage(raw: Any?): SearchPage {
        val isEnd = pageIsEnd(raw)
        val items = dataItems(raw).mapIndexedNotNull { index, item -> trackFromMap(item, index) }
        return SearchPage(isEnd, items)
    }

    /** 结果页 isEnd 字段（兼容 Map 与 JSObject 两种返回形态；缺省 true）。 */
    private fun pageIsEnd(raw: Any?): Boolean = when (raw) {
        is Map<*, *> -> (raw["isEnd"] as? Boolean) ?: true
        is JSObject -> runtime.onJsThread {
            runCatching { raw.getBoolean("isEnd") }.getOrDefault(true)
        }
        else -> true
    }

    /** 统一取 data/musicList 数组并转 Map 列表（搜索页与详情页使用不同字段）。 */
    private fun dataItems(raw: Any?): List<Map<*, *>> = when (raw) {
        is Map<*, *> -> musicFreePageItems(raw)
        is JSObject -> {
            val items = runtime.onJsThread {
                val arr = sequenceOf("data", "musicList")
                    .mapNotNull { name -> runCatching { raw.getJSArray(name) }.getOrNull() }
                    .firstOrNull()
                    ?: return@onJsThread emptyList<Map<*, *>>()
                val maps = mutableListOf<Map<*, *>>()
                for (index in 0 until arr.length()) {
                    val item = runCatching { arr.get(index) as? JSObject }.getOrNull() ?: continue
                    runtime.hold(item)
                    val map = runtime.jsToKotlin(item) as? Map<*, *>
                    runtime.release(item)
                    if (map != null) maps.add(map)
                }
                runtime.release(arr)
                maps
            }
            runtime.release(raw)
            items
        }
        else -> emptyList()
    }

    private fun trackFromMap(item: Map<*, *>, index: Int): StreamTrack? {
        val id = item["id"]?.toString() ?: return null
        return StreamTrack(
            key = id,
            pluginId = source.id,
            sourceName = source.name,
            name = item["title"]?.toString() ?: item["name"]?.toString() ?: "未知歌曲",
            artist = item["artist"]?.toString() ?: "",
            album = item["album"]?.toString() ?: "",
            durationMs = durationMsOf(item["duration"], item["durationMs"]),
            platform = item["platform"]?.toString() ?: source.name,
            artwork = item["artwork"]?.toString()?.takeIf { it.isNotBlank() },
            raw = item,
        )
    }

    private fun collectionFromMap(item: Map<*, *>, kind: StreamCollectionKind, index: Int): StreamCollection? {
        val id = item["id"]?.toString() ?: return null
        val name = item["title"]?.toString() ?: item["name"]?.toString() ?: return null
        return StreamCollection(
            key = id,
            pluginId = source.id,
            name = name,
            artist = item["artist"]?.toString() ?: "",
            artwork = (item["artwork"] ?: item["coverImg"])?.toString()?.takeIf { it.isNotBlank() },
            description = item["description"]?.toString()?.takeIf { it.isNotBlank() },
            worksNum = (item["worksNum"] ?: item["date"])?.toString()?.takeIf { it.isNotBlank() },
            kind = kind,
            raw = item,
        )
    }

    private fun artistFromMap(item: Map<*, *>, index: Int): StreamArtist? {
        val id = item["id"]?.toString() ?: return null
        val name = item["name"]?.toString() ?: item["title"]?.toString() ?: return null
        return StreamArtist(
            key = id,
            pluginId = source.id,
            name = name,
            avatar = item["avatar"]?.toString()?.takeIf { it.isNotBlank() },
            raw = item,
        )
    }

    private fun durationMsOf(duration: Any?, durationMs: Any?): Long {
        if (durationMs is Number) return durationMs.toLong()
        val seconds = when (duration) {
            is Number -> duration.toDouble()
            is String -> duration.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        return (seconds * 1000).toLong()
    }

    private fun mediaSourceFrom(raw: Any?, quality: StreamQuality): MediaSource? {
        val map = when (raw) {
            is Map<*, *> -> raw
            is JSObject -> try {
                runtime.jsToKotlin(raw) as? Map<*, *>
            } finally {
                runtime.release(raw)
            }
            else -> null
        } ?: return null
        val url = map["url"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val headers = (map["headers"] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to it.value.toString() }
            ?: emptyMap()
        return MediaSource(url, headers, quality)
    }

    /** 直链模式：插件没有 getMediaSource 时，item 自身带 url。 */
    private fun directUrl(item: Any?): MediaSource? {
        val map = when (item) {
            is Map<*, *> -> item
            is JSObject -> runtime.jsToKotlin(item) as? Map<*, *>
            else -> null
        } ?: return null
        val url = map["url"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return MediaSource(url, emptyMap())
    }
}

/** 插件管理器：按源懒加载并缓存运行时，导入时做内存校验，退出时统一关闭。 */
class MusicFreePluginManager(private val context: Context) : AutoCloseable {

    private val store = MusicFreeSourceStore(context)
    private val cache = HashMap<String, MusicFreePlugin>()

    init {
        QuickJsRuntime.ensureNativeLoaded()
    }

    fun sources(): List<MusicFreeSource> = store.load()

    fun saveSources(sources: List<MusicFreeSource>) = store.save(sources)

    fun parseSubscription(json: String): List<MusicFreeSource> = store.parseSubscription(json)

    fun fetchText(url: String): String = store.fetchText(url)

    /** 懒加载缓存；读 filesDir 插件文件；加载失败不缓存并抛异常。 */
    @Synchronized
    fun getPlugin(source: MusicFreeSource): MusicFreePlugin {
        cache[source.id]?.let { return it }
        val js = store.readPluginFile(source)
            ?: throw QuickJSException("插件文件不存在：${source.name}")
        val runtime = QuickJsRuntime(source.name) { name -> moduleScript(name) }
        val plugin = MusicFreePlugin(runtime, source, js)
        try {
            runtime.start()
            JsNetworkBridge(runtime).install()
            plugin.load()
            runtime.allowNetwork = true
        } catch (t: Throwable) {
            runtime.close()
            throw t
        }
        cache[source.id] = plugin
        return plugin
    }

    /** 已加载的插件实例（未加载返回 null，不触发加载）。 */
    fun peekPlugin(sourceId: String): MusicFreePlugin? = cache[sourceId]

    /** 内存快速校验（导入时用）；失败抛异常。 */
    fun validate(source: MusicFreeSource, js: String) {
        val runtime = QuickJsRuntime(source.name) { name -> moduleScript(name) }
        try {
            runtime.start()
            JsNetworkBridge(runtime).install()
            MusicFreePlugin(runtime, source, js).load()
        } catch (t: Throwable) {
            Log.e(TAG_DEBUG, "validate 失败 ${source.name}: ${t::class.simpleName}: ${t.message}", t)
            throw t
        } finally {
            runtime.close()
        }
    }

    /** 移除缓存实例并删除插件文件。 */
    @Synchronized
    fun removePlugin(id: String) {
        cache.remove(id)?.close()
        store.load().firstOrNull { it.id == id }?.let { store.deletePluginFile(it) }
    }

    @Synchronized
    override fun close() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }

    private fun moduleScript(name: String): String? = try {
        context.assets.open("musicfree/$name.js").bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    }
}
