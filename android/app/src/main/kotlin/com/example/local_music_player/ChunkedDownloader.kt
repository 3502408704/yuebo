package com.example.local_music_player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 单文件 HTTP 传输参数：URL、附加头、目标临时文件与分段策略。 */
internal data class HttpTransferSpec(
    val tag: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val target: File,
    /** 单任务最大并发连接数（服务器不支持分段续传时自动退化为单流）。 */
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    /** 分段下限：总长除以它的结果才是连接数，避免小文件开多连接白费握手。 */
    val minChunkBytes: Long = DEFAULT_MIN_CHUNK_BYTES,
) {
    companion object {
        internal const val DEFAULT_MAX_CONNECTIONS = 4
        internal const val DEFAULT_MIN_CHUNK_BYTES = 2L * 1024 * 1024
    }
}

/** 传输事件回调：进度（含速度与 etag）、完成、失败。由 [ChunkedDownloader] 在 IO 线程触发。 */
internal interface TransferListener {
    fun onProgress(tag: String, downloadedBytes: Long, totalBytes: Long, speedBps: Long, etag: String?)
    fun onCompleted(tag: String, totalBytes: Long)
    fun onFailed(tag: String, message: String)

    /** 分集下载专用：分片全部就绪，进入解密拼合成单文件阶段（单文件引擎无此阶段）。 */
    fun onFinalizing(tag: String) {}

    /** 分集下载专用：字节级进度（分片字节数逐个累加，总数在合成前未知时为 -1）。 */
    fun onBytesProgress(tag: String, downloadedBytes: Long, totalBytes: Long) {}
}

/** 致命传输错误（地址失效/内容异常等），直接终结任务；区别于可重试的 [IOException]。 */
internal class TransferFailure(message: String) : Exception(message)

/** .part 同目录的断点文件（`<名>.part.meta`）；任务删除/重置时须一并清理。 */
internal fun downloadProgressFile(target: File): File = File(target.parentFile, target.name + ".meta")

/**
 * 自研多连接下载器（替换 Fetch2）：单文件按字节区间切多段并行下载（IDM 模式），
 * 分段进度落盘断点文件，重试前用 If-Range/总长校验内容未变，掉线挂起不计重试次数。
 * Room 侧任务状态仍由 [DownloadEngine] 维护，本类只负责把一个 URL 变成完整文件。
 * 传输用平台 HttpURLConnection（与 BaiduPanApi/QuarkPanApi 同一惯用法）。
 */
internal class ChunkedDownloader(
    private val isOnline: () -> Boolean,
    private val networkSignal: Flow<Unit>,
    private val retryAttempts: Int = DEFAULT_RETRY_ATTEMPTS,
    private val retryBackoffBaseMs: Long = DEFAULT_RETRY_BACKOFF_BASE_MS,
) {
    /** 执行一次传输；正常返回代表完成，失败经 [TransferListener.onFailed] 上报。可被取消（暂停）。 */
    suspend fun run(spec: HttpTransferSpec, listener: TransferListener) {
        try {
            runOnce(spec, listener)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TransferFailure) {
            listener.onFailed(spec.tag, failure.message ?: "下载失败，请重试")
        } catch (error: IOException) {
            listener.onFailed(spec.tag, downloadFailureMessage(error))
        }
    }

    /** If-Range 校验发现内容已变时，重置断点整重下一次；再次失配则判失败。 */
    private suspend fun runOnce(spec: HttpTransferSpec, listener: TransferListener) {
        try {
            runTransfer(spec, listener, allowRestart = true)
        } catch (changed: ContentChanged) {
            runTransfer(spec, listener, allowRestart = false)
        }
    }

    private suspend fun runTransfer(spec: HttpTransferSpec, listener: TransferListener, allowRestart: Boolean) {
        val probe = withRetries { probeContent(spec) }
        val progressFile = downloadProgressFile(spec.target)
        if (!probe.resumable) progressFile.delete()
        val etag = probe.etag
        val chunks = resumeChunks(spec, probe)
        spec.target.parentFile?.mkdirs()
        RandomAccessFile(spec.target, "rw").use { storage ->
            val channel = storage.channel
            if (probe.totalBytes > 0) storage.setLength(probe.totalBytes)
            try {
                coroutineScope {
                    val workers = chunks.map { chunk ->
                        launch(Dispatchers.IO) {
                            downloadChunk(spec, chunk, channel, probe.totalBytes, etag, allowRestart)
                        }
                    }
                    val reporter = launch { reportProgress(spec, chunks, probe.totalBytes, etag, listener) }
                    workers.forEach { it.join() }
                    reporter.cancel()
                }
            } finally {
                // 暂停/异常路径都要留住分段进度，供下次续传。
                if (probe.resumable) writeMeta(progressFile, probe.totalBytes, etag, chunks)
            }
        }
        verifyComplete(spec, probe.totalBytes)
        listener.onCompleted(spec.tag, if (probe.totalBytes > 0) probe.totalBytes else spec.target.length())
    }

    // —— 探测与分段 ——

    private class ProbeResult(val totalBytes: Long, val etag: String?, val resumable: Boolean)

    /** 字节区间探测：206=支持分段续传；200=服务器忽略区间请求，退化为单流整下。 */
    private fun probeContent(spec: HttpTransferSpec): ProbeResult {
        val connection = openTransferConnection(spec, listOf(HEADER_RANGE to PROBE_RANGE_VALUE))
        try {
            rejectHtmlContent(connection)
            val code = connection.responseCode
            return when {
                code == HttpURLConnection.HTTP_PARTIAL -> {
                    val total = parseContentRangeTotal(connection.getHeaderField(HEADER_CONTENT_RANGE))
                        ?: throw TransferFailure("下载地址响应异常，请重试")
                    ProbeResult(total, connection.getHeaderField(HEADER_ETAG), resumable = true)
                }
                code == HttpURLConnection.HTTP_OK -> ProbeResult(
                    connection.getHeaderField(HEADER_CONTENT_LENGTH)?.toLongOrNull() ?: -1L,
                    connection.getHeaderField(HEADER_ETAG),
                    resumable = false,
                )
                code in 500..599 -> throw IOException(serverError(code))
                code in 400..499 -> throw TransferFailure(unavailableMessage(code))
                else -> throw TransferFailure("下载地址响应异常，请重试")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 计算或恢复分段计划：断点文件的总长与 etag 和本次响应一致才允许续传。 */
    private fun resumeChunks(spec: HttpTransferSpec, probe: ProbeResult): List<Chunk> {
        if (!probe.resumable || probe.totalBytes <= 0) {
            return listOf(Chunk(0, if (probe.totalBytes > 0) probe.totalBytes - 1 else -1))
        }
        val saved = readMeta(downloadProgressFile(spec.target))
        val canResume = saved != null && saved.totalBytes == probe.totalBytes &&
            (saved.etag == null || probe.etag == null || saved.etag == probe.etag)
        return if (canResume && saved != null) saved.chunks else planChunks(probe.totalBytes, spec)
    }

    private fun planChunks(totalBytes: Long, spec: HttpTransferSpec): List<Chunk> {
        val connections = ((totalBytes + spec.minChunkBytes - 1) / spec.minChunkBytes)
            .coerceIn(1L, spec.maxConnections.coerceAtLeast(1).toLong()).toInt()
        val base = totalBytes / connections
        val remainder = totalBytes % connections
        var start = 0L
        return (0 until connections).map { index ->
            val length = base + if (index < remainder) 1 else 0
            val chunk = Chunk(start, start + length - 1)
            start += length
            chunk
        }
    }

    // —— 传输 ——

    private class Chunk(val start: Long, val endInclusive: Long) {
        val done = AtomicLong(0)
        val remaining: Long get() = endInclusive - start + 1 - done.get()
    }

    private suspend fun downloadChunk(
        spec: HttpTransferSpec,
        chunk: Chunk,
        channel: FileChannel,
        totalBytes: Long,
        etag: String?,
        allowRestart: Boolean,
    ) {
        while (chunk.remaining > 0) {
            withRetries { fetchChunkRange(spec, chunk, channel, totalBytes, etag, allowRestart) }
            return
        }
    }

    /** 统一重试策略：致命错误直抛；掉线挂起等恢复信号不计数；在线 IO 失败按指数退避重试。 */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: TransferFailure) {
                throw failure
            } catch (error: IOException) {
                if (!isOnline()) {
                    networkSignal.first()
                    continue
                }
                attempt += 1
                if (attempt > retryAttempts) throw error
                delay(retryBackoffBaseMs shl (attempt - 1))
            }
        }
    }

    /** If-Range 校验失败：服务器对分段请求回了完整 200，说明内容已变，必须重下。 */
    private class ContentChanged : Exception()

    private suspend fun fetchChunkRange(
        spec: HttpTransferSpec,
        chunk: Chunk,
        channel: FileChannel,
        totalBytes: Long,
        etag: String?,
        allowRestart: Boolean,
    ) {
        val from = chunk.start + chunk.done.get()
        if (from > chunk.endInclusive) return
        val requestHeaders = ArrayList<Pair<String, String>>(3)
        requestHeaders.add(HEADER_RANGE to RANGE_VALUE_FORMAT.format(Locale.US, from, chunk.endInclusive))
        etag?.let { requestHeaders.add(HEADER_IF_RANGE to it) }
        val connection = openTransferConnection(spec, requestHeaders)
        try {
            val code = connection.responseCode
            // 「200 当整流接收」只允许覆盖整个文件的分段，否则会把越界内容写进别的分段。
            val coversWholeFile = chunk.start == 0L && chunk.done.get() == 0L &&
                (chunk.endInclusive < 0 || chunk.endInclusive == totalBytes - 1)
            when {
                code == HttpURLConnection.HTTP_PARTIAL -> streamInto(connection, channel, from, chunk)
                code == HttpURLConnection.HTTP_OK && coversWholeFile ->
                    streamInto(connection, channel, 0L, chunk)
                code == HttpURLConnection.HTTP_OK -> {
                    if (allowRestart) throw ContentChanged()
                    throw TransferFailure("下载内容已变化，请重试")
                }
                code in 500..599 -> throw IOException(serverError(code))
                else -> throw TransferFailure(unavailableMessage(code))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun streamInto(connection: HttpURLConnection, channel: FileChannel, from: Long, chunk: Chunk) {
        rejectHtmlContent(connection)
        val buffer = ByteBuffer.allocate(BUFFER_BYTES)
        var position = from
        connection.inputStream.use { input ->
            while (true) {
                buffer.clear()
                val read = input.read(buffer.array())
                if (read < 0) break
                if (read == 0) continue
                buffer.limit(read)
                while (buffer.hasRemaining()) channel.write(buffer, position + buffer.position())
                position += read
                chunk.done.addAndGet(read.toLong())
            }
        }
        if (chunk.endInclusive >= 0 && position <= chunk.endInclusive) {
            throw IOException("connection interrupted before chunk end")
        }
    }

    /** 与网盘 API 同款连接组装：显式超时，附加头逐项写入。 */
    private fun openTransferConnection(
        spec: HttpTransferSpec,
        extraHeaders: List<Pair<String, String>>,
    ): HttpURLConnection {
        val connection = URL(spec.url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        spec.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    /** 错误页/网关 HTML 会被当成音频存下来；发现即判地址失效。 */
    private fun rejectHtmlContent(connection: HttpURLConnection) {
        val type = connection.contentType?.lowercase() ?: return
        if ("text/html" in type) throw TransferFailure("下载地址已失效，请重试")
    }

    private fun unavailableMessage(code: Int): String =
        if (code == 403 || code == 404 || code == 410) "下载地址已失效，请重试" else "下载失败，请重试"

    // —— 进度上报与断点文件 ——

    private suspend fun reportProgress(
        spec: HttpTransferSpec,
        chunks: List<Chunk>,
        totalBytes: Long,
        etag: String?,
        listener: TransferListener,
    ) {
        var lastBytes = 0L
        var lastAt = System.nanoTime()
        while (true) {
            delay(PROGRESS_TICK_MS)
            val downloaded = chunks.sumOf { it.done.get() }
            val now = System.nanoTime()
            val elapsed = (now - lastAt).coerceAtLeast(1)
            val speed = ((downloaded - lastBytes) * 1_000_000_000L / elapsed).coerceAtLeast(0)
            lastBytes = downloaded
            lastAt = now
            listener.onProgress(spec.tag, downloaded, totalBytes, speed, etag)
        }
    }

    private class SavedProgress(val totalBytes: Long, val etag: String?, val chunks: List<Chunk>)

    private fun readMeta(file: File): SavedProgress? {
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val total = root.getLong("total_bytes")
            val etag = root.optString("etag").takeIf { it.isNotEmpty() }
            val array = root.getJSONArray("chunks")
            val chunks = (0 until array.length()).map { index ->
                val item = array.getJSONArray(index)
                val chunk = Chunk(item.getLong(0), item.getLong(1))
                chunk.done.set(item.getLong(2))
                chunk
            }
            if (total <= 0 || chunks.isEmpty()) null else SavedProgress(total, etag, chunks)
        }.getOrNull()
    }

    private fun writeMeta(file: File, totalBytes: Long, etag: String?, chunks: List<Chunk>) {
        if (totalBytes <= 0) return
        runCatching {
            val array = JSONArray()
            chunks.forEach { chunk -> array.put(JSONArray().put(chunk.start).put(chunk.endInclusive).put(chunk.done.get())) }
            val root = JSONObject()
                .put("total_bytes", totalBytes)
                .put("etag", etag ?: "")
                .put("chunks", array)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun verifyComplete(spec: HttpTransferSpec, totalBytes: Long) {
        val actual = spec.target.length()
        if (totalBytes > 0 && actual != totalBytes) throw TransferFailure("下载校验失败（文件大小不符），请重试")
        if (actual <= 0L) throw TransferFailure("下载校验失败（空文件），请重试")
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_TICK_MS = 500L
        const val DEFAULT_RETRY_ATTEMPTS = 4
        const val DEFAULT_RETRY_BACKOFF_BASE_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val HEADER_RANGE = "Range"
        const val HEADER_IF_RANGE = "If-Range"
        const val HEADER_ETAG = "ETag"
        const val HEADER_CONTENT_RANGE = "Content-Range"
        const val HEADER_CONTENT_LENGTH = "Content-Length"
        const val PROBE_RANGE_VALUE = "bytes=0-0"
        const val RANGE_VALUE_FORMAT = "bytes=%d-%d"

        fun serverError(code: Int): String = "HTTP %d".format(Locale.US, code)
    }
}

/** Content-Range 形如 `bytes 0-0/12345`；取总长，解析失败返回 null。 */
internal fun parseContentRangeTotal(header: String?): Long? {
    if (header.isNullOrBlank()) return null
    val total = header.substringAfterLast('/', "").trim()
    if (total == "*" || total.isEmpty()) return null
    return total.toLongOrNull()
}
