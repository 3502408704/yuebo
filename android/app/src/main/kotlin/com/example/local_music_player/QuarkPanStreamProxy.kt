package com.example.local_music_player

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

internal data class QuarkPanProxyRange(val start: Long, val end: Long)

internal fun parseQuarkPanProxyRange(header: String?, totalSize: Long): QuarkPanProxyRange? {
    val match = Regex("^bytes=(\\d+)-(\\d*)$").matchEntire(header ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull()
        ?.coerceAtMost(totalSize - 1)
        ?: (totalSize - 1)
    return if (totalSize > 0 && start in 0..end && start < totalSize) {
        QuarkPanProxyRange(start, end)
    } else {
        null
    }
}

internal fun quarkPanContentRange(start: Long, end: Long, total: Long): String =
    "bytes $start-$end/$total"

/**
 * 打开夸克下载连接：手动跟随重定向并携带 Cookie/Referer/UA。
 * 下载 URL 的签名绑定生成时的 Cookie，必须用同一 Cookie 快照请求，否则 403。
 */
internal fun openQuarkPanDownloadConnection(
    downloadUrl: String,
    cookie: String,
    range: String?,
): HttpURLConnection {
    var url: String = downloadUrl
    var connection: HttpURLConnection? = null
    var redirects = 0
    while (true) {
        connection?.disconnect()
        val next = URL(url).openConnection() as HttpURLConnection
        connection = next
        try {
            next.instanceFollowRedirects = false
            next.connectTimeout = 20_000
            next.readTimeout = 30_000
            next.setRequestProperty("User-Agent", QUARK_PAN_UA)
            next.setRequestProperty("Referer", QUARK_PAN_REFERER)
            if (cookie.isNotBlank()) next.setRequestProperty("Cookie", cookie)
            if (range != null) next.setRequestProperty("Range", range)
            val code = next.responseCode
            val location = next.getHeaderField("Location")
            if (code in setOf(301, 302, 303, 307, 308) &&
                !location.isNullOrBlank() && redirects < 4
            ) {
                url = URL(URL(url), location).toString()
                redirects++
            } else {
                return next
            }
        } catch (error: IOException) {
            next.disconnect()
            throw error
        }
    }
}

/**
 * 本地 127.0.0.1 HTTP 代理：把夸克 download_url 流媒体化。
 * BASS/ExoPlayer 通过代理地址播放，支持 Range 透传实现 seek。
 * 每次请求先解析 download_url（用同一 Cookie 快照），带 Cookie 转发上游。
 */
internal class QuarkPanStreamProxy(
    private val api: QuarkPanApi,
) : AutoCloseable {
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var fid: String = ""
    @Volatile private var totalSize: Long = 0
    @Volatile private var downloadUrl: String? = null
    @Volatile private var cookieSnapshot: String = ""
    @Volatile private var token: String? = null

    @Synchronized
    fun start(file: QuarkPanFile, advertisedAddress: InetAddress = InetAddress.getByName("127.0.0.1")): String {
        stop()
        fid = file.fid
        totalSize = file.size.coerceAtLeast(0)
        downloadUrl = null
        cookieSnapshot = ""
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
        fid = ""
        totalSize = 0
        downloadUrl = null
        cookieSnapshot = ""
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
        if (parts.size < 2 || parts[1] != "/stream/${token}" || fid.isEmpty()) {
            writeHeaders(socket, 404, emptyMap())
            return
        }
        if (parts[0] !in setOf("GET", "HEAD")) {
            writeHeaders(socket, 405, mapOf("Allow" to "GET, HEAD"))
            return
        }
        val requestedRange = headers["range"]
        val range = parseQuarkPanProxyRange(requestedRange, totalSize)
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
        if (range != null) responseHeaders["Content-Range"] = quarkPanContentRange(start, end, totalSize)
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

    /** 打开一条上游下载连接；失败（URL 过期等）时重新解析一次。 */
    private fun openUpstream(start: Long, end: Long, ranged: Boolean): Upstream? {
        for (attempt in 0..1) {
            val link = downloadUrl ?: resolveDownloadUrl() ?: return null
            val connection = try {
                openQuarkPanDownloadConnection(
                    downloadUrl = link,
                    cookie = cookieSnapshot,
                    range = if (ranged) "bytes=$start-$end" else null,
                )
            } catch (error: IOException) {
                Log.w(TAG, "下载连接失败", error)
                return null
            }
            try {
                val code = connection.responseCode
                if (code !in setOf(200, 206) && attempt == 0) {
                    Log.w(TAG, "下载地址返回 $code，重新解析后重试")
                    downloadUrl = null
                    continue
                }
                if (code !in setOf(200, 206)) {
                    Log.w(TAG, "下载地址重试后仍返回 $code")
                    return null
                }
                val length = if (code == 206) {
                    connection.contentLengthLong.takeIf { it >= 0 } ?: (end - start + 1)
                } else {
                    connection.contentLengthLong
                }
                return Upstream(connection.inputStream, length, connection.contentType)
            } catch (error: IOException) {
                Log.w(TAG, "读取下载响应失败", error)
                return null
            } finally {
                runCatching {
                    if (connection.responseCode !in setOf(200, 206)) connection.disconnect()
                }
            }
        }
        return null
    }

    /** 解析下载地址并记录 Cookie 快照（签名绑定该 Cookie）。 */
    fun resolveDownloadUrl(): String? {
        val snapshot = api.cookie
        val link = runBlocking {
            runCatching { api.getDownloadUrl(fid) }
                .onFailure { error -> Log.w(TAG, "获取下载地址失败", error) }
                .getOrNull()
        } ?: return null
        downloadUrl = link
        cookieSnapshot = snapshot
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
        const val TAG = "QuarkPanStreamProxy"
    }
}
