package com.example.local_music_player

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

internal data class BaiduPanProxyRange(val start: Long, val end: Long)

internal fun parseBaiduPanProxyRange(header: String?, totalSize: Long): BaiduPanProxyRange? {
    val match = Regex("^bytes=(\\d+)-(\\d*)$").matchEntire(header ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull()
        ?.coerceAtMost(totalSize - 1)
        ?: (totalSize - 1)
    return if (totalSize > 0 && start in 0..end && start < totalSize) {
        BaiduPanProxyRange(start, end)
    } else {
        null
    }
}

internal fun baiduPanContentRange(start: Long, end: Long, total: Long): String =
    "bytes $start-$end/$total"

/**
 * 本地 127.0.0.1 HTTP 代理：把百度网盘 dlink 流媒体化。
 * BASS 通过 BASS_StreamCreateURL 播放代理地址，支持 Range 透传以实现 seek。
 * dlink 由 filemetas 获取，约 8 小时有效；每个客户端请求独立建立上游连接并跟随 302。
 */
internal class BaiduPanStreamProxy(
    private val api: BaiduPanApi,
    private val accessTokenProvider: () -> String,
) : AutoCloseable {
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var fsId: Long = 0
    @Volatile private var totalSize: Long = 0
    @Volatile private var dlink: String? = null
    @Volatile private var token: String? = null

    @Synchronized
    fun start(file: BaiduPanFile, advertisedAddress: InetAddress = InetAddress.getByName("127.0.0.1")): String {
        stop()
        fsId = file.fsId
        totalSize = file.size.coerceAtLeast(0)
        dlink = null
        token = UUID.randomUUID().toString()
        val socket = ServerSocket(0, 16, if (advertisedAddress.isLoopbackAddress) advertisedAddress else InetAddress.getByName("0.0.0.0"))
        server = socket
        acceptExecutor.execute { acceptLoop(socket) }
        return "http://${advertisedAddress.hostAddress}:${socket.localPort}/stream/$token"
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        fsId = 0
        totalSize = 0
        dlink = null
        token = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            runCatching { socket.accept() }.getOrNull()?.let { client ->
                clientExecutor.execute {
                    try {
                        client.use(::handleClient)
                    } catch (_: IOException) {
                        // 播放器可能提前断开一个 Range 请求。
                    }
                }
            }
        }
    }

    private fun handleClient(socket: java.net.Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val request = input.readLine() ?: return
        val headers = generateSequence { input.readLine() }
            .takeWhile { it.isNotEmpty() }
            .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
        val parts = request.split(' ')
        if (parts.size < 2 || parts[1] != "/stream/${token}" || fsId == 0L) {
            writeHeaders(socket, 404, emptyMap())
            return
        }
        if (parts[0] !in setOf("GET", "HEAD")) {
            writeHeaders(socket, 405, mapOf("Allow" to "GET, HEAD"))
            return
        }
        val requestedRange = headers["range"]
        val range = parseBaiduPanProxyRange(requestedRange, totalSize)
        if (requestedRange != null && range == null) {
            writeHeaders(socket, 416, mapOf("Content-Range" to "bytes */$totalSize"))
            return
        }
        val start = range?.start ?: 0
        val end = range?.end ?: (totalSize - 1).coerceAtLeast(0)
        val upstream = openUpstream(start, end, range != null)
        if (upstream == null) {
            writeHeaders(socket, 502, emptyMap())
            return
        }
        val responseHeaders = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Type" to (upstream.contentType ?: "application/octet-stream"),
        )
        if (upstream.length >= 0) responseHeaders["Content-Length"] = upstream.length.toString()
        if (range != null) responseHeaders["Content-Range"] = baiduPanContentRange(start, end, totalSize)
        writeHeaders(socket, if (range == null) 200 else 206, responseHeaders)
        if (parts[0] == "HEAD") return
        upstream.stream.use { stream ->
            val output = socket.getOutputStream()
            val buffer = ByteArray(64 * 1024)
            var remaining = upstream.length
            while (remaining != 0L) {
                val toRead = if (remaining < 0) buffer.size else minOf(buffer.size.toLong(), remaining).toInt()
                val read = stream.read(buffer, 0, toRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                if (remaining > 0) remaining -= read
            }
            output.flush()
        }
    }

    private data class Upstream(
        val stream: InputStream,
        val length: Long,
        val contentType: String?,
    )

    /** 打开一条上游 dlink 连接；失败（dlink 过期等）时刷新 dlink 重试一次。 */
    private fun openUpstream(start: Long, end: Long, ranged: Boolean): Upstream? {
        for (attempt in 0..1) {
            val link = dlink ?: resolveDlink() ?: return null
            val connection = try {
                openBaiduPanDlinkConnection(
                    dlink = link,
                    accessToken = accessTokenProvider(),
                    range = if (ranged) "bytes=$start-$end" else null,
                )
            } catch (error: IOException) {
                Log.w(TAG, "dlink 连接失败", error)
                return null
            }
            try {
                val code = connection.responseCode
                if (code !in setOf(200, 206) && attempt == 0) {
                    Log.w(TAG, "dlink 返回 $code，刷新后重试")
                    dlink = null
                    continue
                }
                if (code !in setOf(200, 206)) {
                    Log.w(TAG, "dlink 重试后仍返回 $code")
                    return null
                }
                val length = if (code == 206) {
                    connection.contentLengthLong.takeIf { it >= 0 } ?: (end - start + 1)
                } else {
                    connection.contentLengthLong
                }
                return Upstream(connection.inputStream, length, connection.contentType)
            } catch (error: IOException) {
                Log.w(TAG, "读取 dlink 响应失败", error)
                return null
            } finally {
                runCatching {
                    if (connection.responseCode !in setOf(200, 206)) connection.disconnect()
                }
            }
        }
        return null
    }

    fun resolveDlink(): String? {
        val metas = runBlocking {
            runCatching {
                api.fileMetas(accessTokenProvider(), listOf(fsId))
            }.onFailure { error -> Log.w(TAG, "获取 dlink 失败", error) }
                .getOrNull()
        } ?: return null
        val link = metas.firstOrNull { it.fsId == fsId }?.dlink
        if (link == null) {
            Log.w(TAG, "filemetas 未返回 dlink（fsId=$fsId）")
            return null
        }
        dlink = link
        return link
    }

    private fun writeHeaders(socket: java.net.Socket, status: Int, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        502 -> "Bad Gateway"
        else -> "Error"
    }

    override fun close() {
        stop()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    private companion object {
        const val TAG = "BaiduPanStreamProxy"
    }
}
