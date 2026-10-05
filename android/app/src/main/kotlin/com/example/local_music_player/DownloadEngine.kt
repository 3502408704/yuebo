package com.example.local_music_player

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 单个下载任务的瞬时速度与剩余时间（仅用于界面展示，不入 Room）。 */
data class DownloadSpeedEta(val speedBps: Long, val etaMs: Long)

internal fun onlineTag(id: String): String = "online:$id"

internal fun panTag(fsId: Long): String = "pan:$fsId"

internal fun quarkTag(fid: String): String = "quark:$fid"

/**
 * 专业下载引擎：自研多连接传输（[ChunkedDownloader]）统一驱动在线音乐、百度网盘与夸克网盘下载。
 * 在线音乐经插件音源解析真实地址（音质档位由源决定，自动降档）；Room 仍是任务事实来源，
 * 单文件断点在 .part 旁的 .meta。
 */
internal class DownloadEngine(
    private val context: Context,
    private val downloads: DownloadRepository,
    private val panDownloads: BaiduPanDownloadRepository,
    private val quarkDownloads: QuarkPanDownloadRepository,
    private val pluginManager: MusicFreePluginManager,
    private val panApi: BaiduPanApi,
    private val quarkApi: QuarkPanApi,
    private val accessTokenProvider: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPersistAt = ConcurrentHashMap<String, Long>()
    private val speedEta = MutableStateFlow<Map<String, DownloadSpeedEta>>(emptyMap())
    private val settingsPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
    }

    val speedEtaFlow: StateFlow<Map<String, DownloadSpeedEta>> = speedEta.asStateFlow()

    /** 任务并发槽：同时最多 N 个任务在传；单任务内由下载器再开多连接。 */
    private val taskSlots = Semaphore(DOWNLOAD_CONCURRENT_LIMIT)

    /** 任一网络恢复可用时触发；掉线挂起的分段据此继续。 */
    private val networkAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val downloader = ChunkedDownloader(
        isOnline = ::hasNetwork,
        networkSignal = networkAvailable,
    )

    private val transfers = ConcurrentHashMap<String, Job>()

    private val transferListener = object : TransferListener {
        override fun onProgress(tag: String, downloadedBytes: Long, totalBytes: Long, speedBps: Long, etag: String?) {
            speedEta.update { current ->
                val etaMs = if (speedBps > 0 && totalBytes > downloadedBytes) {
                    (totalBytes - downloadedBytes) * 1000 / speedBps
                } else {
                    0L
                }
                current + (tag to DownloadSpeedEta(speedBps, etaMs))
            }
            scope.launch { onTransferProgress(tag, downloadedBytes, totalBytes, etag) }
        }

        override fun onCompleted(tag: String, totalBytes: Long) {
            speedEta.update { it - tag }
            scope.launch { onTransferCompleted(tag, totalBytes) }
        }

        override fun onFailed(tag: String, message: String) {
            speedEta.update { it - tag }
            scope.launch { onTransferFailed(tag, message) }
        }

        override fun onBytesProgress(tag: String, downloadedBytes: Long, totalBytes: Long) = Unit

        override fun onFinalizing(tag: String) = Unit
    }

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        networkAvailable.tryEmit(Unit)
                        // WiFi 恢复时重驱排队任务（仅 WiFi 下载模式下被挂起的任务）
                        if (!wifiOnlyDownload()) return
                        val caps = connectivity.getNetworkCapabilities(network)
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                            scope.launch { reconcile() }
                        }
                    }
                },
            )
        }
    }

    private fun hasNetwork(): Boolean = runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun wifiOnlyDownload(): Boolean = settingsPrefs.getBoolean("wifi_only_download", false)

    private fun isWifiConnected(): Boolean = runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = connectivity.activeNetwork ?: return false
        connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    fun startService() = DownloadService.start(context)

    /** 升级/重启后把未完成任务重新入队并继续下载（分段断点由下载器自动续上）。 */
    suspend fun reconcile() {
        downloads.unfinished().filter { it.status == DownloadTaskStatus.QUEUED }.forEach { ensureOnline(it.id) }
        panDownloads.unfinished().filter { it.status == DownloadTaskStatus.QUEUED }.forEach { ensurePan(it.fsId) }
        quarkDownloads.unfinished().filter { it.status == DownloadTaskStatus.QUEUED }.forEach { ensureQuark(it.fid) }
    }

    fun pauseOnline(id: String) {
        transfers.remove(onlineTag(id))?.cancel()
        scope.launch { downloads.pause(id) }
    }

    fun resumeOnline(id: String) {
        scope.launch {
            downloads.resume(id)
            ensureOnline(id)
        }
    }

    fun retryOnline(id: String) {
        scope.launch {
            downloads.retry(id)
            ensureOnline(id)
        }
    }

    fun cancelOnline(id: String) {
        val tag = onlineTag(id)
        transfers.remove(tag)?.cancel()
        scope.launch {
            downloads.cancel(id)
            downloadProgressFile(downloads.tempFile(id)).delete()
        }
    }

    fun pausePan(fsId: Long) {
        transfers.remove(panTag(fsId))?.cancel()
        scope.launch { panDownloads.pause(fsId) }
    }

    fun resumePan(fsId: Long) {
        scope.launch {
            panDownloads.resume(fsId)
            ensurePan(fsId)
        }
    }

    fun cancelPan(fsId: Long) {
        val tag = panTag(fsId)
        transfers.remove(tag)?.cancel()
        scope.launch {
            panDownloads.cancel(fsId)
            downloadProgressFile(panDownloads.tempFile(fsId)).delete()
        }
    }

    fun pauseQuark(fid: String) {
        transfers.remove(quarkTag(fid))?.cancel()
        scope.launch { quarkDownloads.pause(fid) }
    }

    fun resumeQuark(fid: String) {
        scope.launch {
            quarkDownloads.resume(fid)
            ensureQuark(fid)
        }
    }

    fun cancelQuark(fid: String) {
        val tag = quarkTag(fid)
        transfers.remove(tag)?.cancel()
        scope.launch {
            quarkDownloads.cancel(fid)
            downloadProgressFile(quarkDownloads.tempFile(fid)).delete()
        }
    }

    /** 插件解析失败的落库失败原因。 */
    private suspend fun failOnline(id: String, message: String) {
        downloads.markFailure(id, message)
    }

    private suspend fun ensureOnline(id: String) = withContext(Dispatchers.IO) {
        val tag = onlineTag(id)
        if (transfers.containsKey(tag)) return@withContext
        val task = downloads.find(id) ?: return@withContext
        if (task.status != DownloadTaskStatus.QUEUED) return@withContext
        if (wifiOnlyDownload() && !isWifiConnected()) return@withContext
        // 插件解析：按任务记录的源 id 找插件；缺失/未启用即任务失效。
        val source = pluginManager.sources().firstOrNull { it.id == task.track.pluginId }
        val plugin = source?.let { runCatching { pluginManager.getPlugin(it) }.getOrNull() }
        if (source == null || plugin == null) {
            failOnline(id, "音源不可用或已删除，请删除任务后重新下载")
            return@withContext
        }
        val quality = plugin.qualityOptions()
            .firstOrNull { it.value == task.qualityApiValue }
            ?.tier
            ?: StreamQuality.entries.firstOrNull { it.pluginValue == task.qualityApiValue }
            ?: StreamQuality.Standard
        val streamTrack = StreamTrack(
            key = task.track.platformId,
            pluginId = task.track.pluginId,
            sourceName = task.track.sourceName,
            name = task.track.title,
            artist = task.track.artist,
            album = task.track.album,
            durationMs = task.track.durationMs ?: 0L,
            platform = task.track.platform,
            artwork = task.track.artworkUrl,
            raw = task.track.pluginItem(),
        )
        val resolved = runCatching {
            plugin.getMediaSource(
                plugin.enrichTrack(streamTrack),
                quality,
                requestedValue = task.qualityApiValue,
            )
        }.getOrElse { error ->
            failOnline(id, downloadFailureMessage(error))
            return@withContext
        }
        if (resolved == null) {
            failOnline(id, "「${source.name}」未能解析${quality.label}音质，请换档位或稍后重试")
            return@withContext
        }
        if (downloads.find(id)?.status != DownloadTaskStatus.QUEUED) return@withContext
        val extension = extensionFromUrl(resolved.url) ?: "mp3"
        downloads.updateFormat(id, extension, downloadMimeType(extension))
        if (task.lyrics.isNullOrBlank()) {
            val lyrics = runCatching { plugin.getLyric(streamTrack)?.rawLrc }.getOrNull()
            if (!lyrics.isNullOrBlank()) downloads.updateLyrics(id, lyrics)
        }
        val file = downloads.tempFile(id)
        file.parentFile?.mkdirs()
        startTransfer(tag, resolved.url, resolved.headers, file)
    }

    private suspend fun ensurePan(fsId: Long) = withContext(Dispatchers.IO) {
        val tag = panTag(fsId)
        if (transfers.containsKey(tag)) return@withContext
        val task = panDownloads.find(fsId) ?: return@withContext
        if (task.status != DownloadTaskStatus.QUEUED) return@withContext
        if (wifiOnlyDownload() && !isWifiConnected()) return@withContext
        val url = resolvePanDownloadUrl(fsId)
        if (url == null) {
            panDownloads.markFailure(fsId, "无法获取下载地址，请重试")
            return@withContext
        }
        if (panDownloads.find(fsId)?.status != DownloadTaskStatus.QUEUED) return@withContext
        val file = panDownloads.tempFile(fsId)
        file.parentFile?.mkdirs()
        startTransfer(tag, url, mapOf("User-Agent" to BAIDU_PAN_DLINK_UA), file)
    }

    private suspend fun ensureQuark(fid: String) = withContext(Dispatchers.IO) {
        val tag = quarkTag(fid)
        if (transfers.containsKey(tag)) return@withContext
        val task = quarkDownloads.find(fid) ?: return@withContext
        if (task.status != DownloadTaskStatus.QUEUED) return@withContext
        if (wifiOnlyDownload() && !isWifiConnected()) return@withContext
        val url = resolveQuarkDownloadUrl(fid)
        if (url == null) {
            quarkDownloads.markFailure(fid, "无法获取下载地址，请重试")
            return@withContext
        }
        if (quarkDownloads.find(fid)?.status != DownloadTaskStatus.QUEUED) return@withContext
        val file = quarkDownloads.tempFile(fid)
        file.parentFile?.mkdirs()
        val headers = buildMap {
            put("User-Agent", QUARK_PAN_UA)
            if (quarkApi.cookie.isNotBlank()) put("Cookie", quarkApi.cookie)
            put("Referer", QUARK_PAN_REFERER)
        }
        startTransfer(tag, url, headers, file)
    }

    /** 解析夸克下载地址并跟随重定向拿到最终 URL（签名在 URL 内，交给下载器直连）。 */
    private suspend fun resolveQuarkDownloadUrl(fid: String): String? {
        if (quarkApi.cookie.isBlank()) return null
        val url = runCatching { quarkApi.getDownloadUrl(fid) }.getOrNull() ?: return null
        return runCatching {
            val connection = openQuarkPanDownloadConnection(
                downloadUrl = url,
                cookie = quarkApi.cookie,
                range = null,
            )
            try {
                if (connection.responseCode in setOf(200, 206)) connection.url.toString() else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private suspend fun resolvePanDownloadUrl(fsId: Long): String? {
        val token = accessTokenProvider()
        if (token.isBlank()) return null
        val dlink = runCatching {
            panApi.fileMetas(token, listOf(fsId))
        }.getOrNull()?.firstOrNull { it.fsId == fsId }?.dlink
        if (dlink.isNullOrBlank()) return null
        return runCatching {
            val connection = openBaiduPanDlinkConnection(dlink = dlink, accessToken = token, range = null)
            try {
                if (connection.responseCode in setOf(200, 206)) connection.url.toString() else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** 启动一次传输；同 tag 的旧传输（URL 刷新/重试路径）先取消再起新的，断点由 .meta 续上。 */
    private fun startTransfer(tag: String, url: String, headers: Map<String, String>, temp: File) {
        transfers.remove(tag)?.cancel()
        val spec = HttpTransferSpec(tag = tag, url = url, headers = headers, target = temp)
        transfers[tag] = scope.launch {
            taskSlots.withPermit { downloader.run(spec, transferListener) }
        }
    }

    private suspend fun onTransferProgress(tag: String, downloadedBytes: Long, totalBytes: Long, etag: String?) {
        if (!canTransfer(tag)) {
            transfers.remove(tag)?.cancel()
            return
        }
        val now = System.currentTimeMillis()
        when {
            tag.startsWith("online:") -> {
                val id = tag.removePrefix("online:")
                downloads.markDownloading(id)
                if (now - (lastPersistAt[tag] ?: 0L) >= PROGRESS_PERSIST_INTERVAL_MS) {
                    lastPersistAt[tag] = now
                    downloads.markProgress(id, downloadedBytes, totalBytes, etag)
                }
            }
            tag.startsWith("pan:") -> {
                val fsId = tag.removePrefix("pan:").toLong()
                panDownloads.markDownloading(fsId)
                if (now - (lastPersistAt[tag] ?: 0L) >= PROGRESS_PERSIST_INTERVAL_MS) {
                    lastPersistAt[tag] = now
                    panDownloads.markProgress(fsId, downloadedBytes)
                }
            }
            tag.startsWith("quark:") -> {
                val fid = tag.removePrefix("quark:")
                quarkDownloads.markDownloading(fid)
                if (now - (lastPersistAt[tag] ?: 0L) >= PROGRESS_PERSIST_INTERVAL_MS) {
                    lastPersistAt[tag] = now
                    quarkDownloads.markProgress(fid, downloadedBytes)
                }
            }
        }
    }

    private suspend fun onTransferCompleted(tag: String, totalBytes: Long) {
        transfers.remove(tag)
        lastPersistAt.remove(tag)
        when {
            tag.startsWith("online:") -> {
                val id = tag.removePrefix("online:")
                downloads.markDownloading(id)
                downloads.publishAndComplete(id, downloads.tempFile(id))
                downloadProgressFile(downloads.tempFile(id)).delete()
            }
            tag.startsWith("pan:") -> {
                val fsId = tag.removePrefix("pan:").toLong()
                panDownloads.markDownloading(fsId)
                panDownloads.publishAndComplete(fsId, panDownloads.tempFile(fsId))
                downloadProgressFile(panDownloads.tempFile(fsId)).delete()
            }
            tag.startsWith("quark:") -> {
                val fid = tag.removePrefix("quark:")
                quarkDownloads.markDownloading(fid)
                quarkDownloads.publishAndComplete(fid, quarkDownloads.tempFile(fid))
                downloadProgressFile(quarkDownloads.tempFile(fid)).delete()
            }
        }
    }

    private suspend fun onTransferFailed(tag: String, message: String) {
        transfers.remove(tag)
        lastPersistAt.remove(tag)
        when {
            tag.startsWith("online:") -> downloads.markFailure(tag.removePrefix("online:"), message)
            tag.startsWith("pan:") -> panDownloads.markFailure(tag.removePrefix("pan:").toLong(), message)
            tag.startsWith("quark:") -> quarkDownloads.markFailure(tag.removePrefix("quark:"), message)
        }
    }

    private suspend fun canTransfer(tag: String): Boolean = when {
        tag.startsWith("online:") -> downloads.find(tag.removePrefix("online:"))?.status in setOf(
            DownloadTaskStatus.QUEUED, DownloadTaskStatus.DOWNLOADING,
        )
        tag.startsWith("pan:") -> panDownloads.find(tag.removePrefix("pan:").toLong())?.status == DownloadTaskStatus.DOWNLOADING
        tag.startsWith("quark:") -> quarkDownloads.find(tag.removePrefix("quark:"))?.status == DownloadTaskStatus.DOWNLOADING
        else -> false
    }

    private companion object {
        const val DOWNLOAD_CONCURRENT_LIMIT = 3
        const val PROGRESS_PERSIST_INTERVAL_MS = 1_000L

        /** 从解析地址推断扩展名（无后缀或不可识别时返回 null，回退 mp3）。 */
        fun extensionFromUrl(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#')
            val candidate = path.substringAfterLast('.', "").lowercase()
            return candidate.takeIf { it in setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "wav", "opus", "ape", "wv") }
        }
    }
}
