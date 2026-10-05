package com.example.local_music_player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Serves currently selected content URIs to devices on the private LAN. */
class LocalMediaServer(private val context: Context) : AutoCloseable {
    private val contentResolver: ContentResolver = context.contentResolver
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null

    /**
     * 多会话（2026-09-28）：按 token 并发服务多条媒体流。
     * - 主会话（primary）：当前投送内容；新的 start/startRemote 会退役旧主会话（与旧单会话语义一致）；
     * - 预载会话（preload）：gapless 预载的下一首，不触碰主会话；同刻至多一个，新的预载替换旧的。
     * 退役 = 从映射移除，该 token 的后续请求一律 404。
     */
    private class Session(
        val track: NativeTrack,
        /** 远程中继源（本地文件会话为 null）。refresh 换得的**新地址必须写回**，供后续 Range 请求沿用。 */
        @Volatile var currentSource: RemoteMediaSource?,
        /** 本地文件会话的预读长度；远程会话为 0（由上游 Content-Length 决定）。 */
        val length: Long,
        /** 远程 HLS 分片回到本机代理的基地址；不把带签名的上游 URL 暴露给设备。 */
        val localUrl: String,
        /** 音频格式兼容层：接收端不支持源格式时，本会话实时解码为 WAV 流式输出（仅本地音频）。 */
        val transcode: Boolean = false,
        /** 视频流式 TS 会话：源容器转封装为 MPEG-TS 即转即推（本地或远程源直读）。 */
        val remux: Boolean = false,
        /** 转码/转封装预探测结果缓存；null=未探测。 */
        @Volatile var transcodeProbe: Triple<Int, Int, Long>? = null,
        /** HLS 清单中每个分片/密钥的短期不透明映射。 */
        val hlsParts: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var primaryToken: String? = null
    @Volatile private var preloadToken: String? = null

    data class RemoteMediaSource(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val refresh: (() -> RemoteMediaSource?)? = null,
    )

    @Synchronized
    fun start(track: NativeTrack, advertisedAddress: InetAddress, transcode: Boolean = false): URI {
        stopSessions()
        require(!advertisedAddress.isAnyLocalAddress && !advertisedAddress.isLoopbackAddress) {
            "投送需要可访问的局域网地址"
        }
        val length = contentLength(track.uri)
        require(length > 0) { "无法读取媒体长度" }
        return addSession(track, advertisedAddress, null, length = length, transcode = transcode, preload = false)
    }

    /** Relays an authenticated or short-lived online URL without exposing it to the receiver. */
    @Synchronized
    fun startRemote(track: NativeTrack, advertisedAddress: InetAddress, source: RemoteMediaSource): URI {
        stopSessions()
        require(!advertisedAddress.isAnyLocalAddress && !advertisedAddress.isLoopbackAddress) {
            "投送需要可访问的局域网地址"
        }
        return addSession(track, advertisedAddress, source, length = 0, preload = false)
    }

    /**
     * gapless 预载：为队列下一首建一条**非主**会话（替换上一条预载），正在播的主会话不受影响。
     * 调用方随后把返回地址经 SetNextAVTransportURI 预注册到设备，或换曲时直接消费。
     */
    @Synchronized
    fun startPreloadRemote(track: NativeTrack, advertisedAddress: InetAddress, source: RemoteMediaSource): URI {
        require(!advertisedAddress.isAnyLocalAddress && !advertisedAddress.isLoopbackAddress) {
            "投送需要可访问的局域网地址"
        }
        preloadToken?.let(sessions::remove)
        return addSession(track, advertisedAddress, source, length = 0, preload = true)
    }

    /** 流式 TS 会话：接收端支持 MPEG-TS 时的即转即推（本地或远程源直读，带自定义请求头）。 */
    @Synchronized
    fun startRemux(
        track: NativeTrack,
        advertisedAddress: InetAddress,
        source: RemoteMediaSource?,
    ): URI {
        require(!advertisedAddress.isAnyLocalAddress && !advertisedAddress.isLoopbackAddress) {
            "投送需要可访问的局域网地址"
        }
        stopSessions()
        return addSession(track, advertisedAddress, source, length = 0, preload = false, remux = true)
    }

    /** 本地文件的 gapless 预载变体（顺序播放时下一首的本机会话可提前就绪）。 */
    @Synchronized
    fun startPreloadLocal(track: NativeTrack, advertisedAddress: InetAddress): URI {
        require(!advertisedAddress.isAnyLocalAddress && !advertisedAddress.isLoopbackAddress) {
            "投送需要可访问的局域网地址"
        }
        val length = contentLength(track.uri)
        require(length > 0) { "无法读取媒体长度" }
        preloadToken?.let(sessions::remove)
        return addSession(track, advertisedAddress, null, length = length, transcode = false, preload = true)
    }

    /** 把预载会话提升为主会话（退役其余会话）。预载不存在时返回 null 并保持现状。 */
    @Synchronized
    fun promotePreload(): String? {
        val token = preloadToken ?: return null
        preloadToken = null
        primaryToken = token
        sessions.keys.filter { it != token }.forEach(sessions::remove)
        return token
    }

    @Synchronized
    fun isSessionAlive(token: String): Boolean = sessions.containsKey(token)

    @Synchronized
    fun stop(token: String) {
        sessions.remove(token)
        if (primaryToken == token) primaryToken = null
        if (preloadToken == token) preloadToken = null
        maybeCloseServer()
    }

    @Synchronized
    fun stop() {
        stopSessions()
        server?.close()
        server = null
    }

    /** 退役全部会话（保留服务器套接字给 start 流程复用，与旧版 stop-then-start 等价）。 */
    private fun stopSessions() {
        sessions.clear()
        primaryToken = null
        preloadToken = null
    }

    /** 全部会话清空且无服务在途时关闭监听，避免长期占用端口。 */
    private fun maybeCloseServer() {
        if (sessions.isEmpty()) {
            server?.close()
            server = null
        }
    }

    private fun addSession(
        track: NativeTrack,
        advertisedAddress: InetAddress,
        remoteSource: RemoteMediaSource?,
        length: Long,
        transcode: Boolean = false,
        remux: Boolean = false,
        preload: Boolean,
    ): URI {
        val socket = server ?: ServerSocket(0, 16, InetAddress.getByName("0.0.0.0")).also { newSocket ->
            server = newSocket
            acceptExecutor.execute { acceptLoop(newSocket) }
        }
        val token = UUID.randomUUID().toString()
        val localUrl = URI("http", null, advertisedAddress.hostAddress, socket.localPort, "/media/$token", null, null).toString()
        sessions[token] = Session(track, remoteSource, length, localUrl, transcode, remux)
        if (preload) preloadToken = token else primaryToken = token
        return URI(localUrl)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            runCatching { socket.accept() }.getOrNull()?.let { client ->
                clientExecutor.execute {
                    try {
                        client.use(::handleClient)
                    } catch (_: IOException) {
                        // Receivers commonly close a completed range request early.
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
        val target = parts.getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        val partKey = target.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("part=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
        val token = path.removePrefix("/media/").substringBefore('/')
        val session = sessions[token]
        if (parts.size < 2 || session == null) {
            writeHeaders(socket, 404, emptyMap())
            return
        }
        if (parts[0] !in setOf("GET", "HEAD")) {
            writeHeaders(socket, 405, mapOf("Allow" to "GET, HEAD"))
            return
        }
        val track = session.track
        val remoteSource = session.currentSource
        if (remoteSource != null) {
            val requestedUrl = partKey?.let(session.hlsParts::get)
            if (partKey != null && requestedUrl == null) {
                writeHeaders(socket, 404, emptyMap())
                return
            }
            relayRemote(socket, parts[0], headers["range"], session, requestedUrl)
            return
        }
        if (session.transcode) {
            serveTranscoded(socket, parts[0], session)
            return
        }
        if (session.remux) {
            serveTsRemuxed(socket, parts[0], session)
            return
        }
        val length = contentLength(track.uri)
        val range = parseRange(headers["range"], length)
        if (headers.containsKey("range") && range == null) {
            writeHeaders(socket, 416, mapOf("Content-Range" to "bytes */$length"))
            return
        }
        val start = range?.first ?: 0
        val end = range?.second ?: length - 1
        val responseHeaders = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Type" to dlnaMimeType(track.mimeType),
            "Content-Length" to (end - start + 1).toString(),
        )
        if (range != null) responseHeaders["Content-Range"] = "bytes $start-$end/$length"
        writeHeaders(socket, if (range == null) 200 else 206, responseHeaders)
        if (parts[0] == "HEAD") return
        contentResolver.openFileDescriptor(track.uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.position(start)
                BufferedOutputStream(socket.getOutputStream()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = end - start + 1
                    while (remaining > 0) {
                        val read = channel.read(java.nio.ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 格式兼容层服务（仅本地音频会话）：探测源可解码后，实时解码为 WAV 流式输出。
     * 探测失败回落原文件直通（不比现状差）；输出不支持 Range（响应头不带 Accept-Ranges）。
     */
    private fun serveTranscoded(socket: java.net.Socket, method: String, session: Session) {
        val probe = session.transcodeProbe ?: DlnaAudioTranscoder(context, session.track.uri, DlnaTranscodeDiscardStream)
            .probe()?.also { session.transcodeProbe = it }
        val track = session.track
        if (probe == null) {
            // 源解不了：回落原格式直通（设备行为与此前一致）
            val length = contentLength(track.uri)
            writeHeaders(socket, 200, linkedMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Type" to dlnaMimeType(track.mimeType),
                "Content-Length" to length.toString(),
            ))
            if (method == "HEAD") return
            serveLocalRange(socket, track, 0, length - 1)
            return
        }
        val (sampleRate, channels, durationMs) = probe
        val dataBytes = durationMs * sampleRate * channels * 2L / 1000L
        writeHeaders(socket, 200, linkedMapOf(
            "Content-Type" to "audio/wav",
            "Content-Length" to (DlnaAudioTranscoder.WAV_HEADER_BYTES + dataBytes).toString(),
        ))
        if (method == "HEAD") return
        val transcoder = DlnaAudioTranscoder(context, session.track.uri, socket.getOutputStream())
        transcoder.transcodeWav(sampleRate, channels, durationMs, cancelled = { !sessions.containsValue(session) })
        socket.getOutputStream().flush()
    }

    /** 本地文件按 Range 区间回放（回落直通用）。 */
    private fun serveLocalRange(socket: java.net.Socket, track: NativeTrack, start: Long, end: Long) {
        contentResolver.openFileDescriptor(track.uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.position(start)
                BufferedOutputStream(socket.getOutputStream()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = end - start + 1
                    while (remaining > 0) {
                        val read = channel.read(java.nio.ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    output.flush()
                }
            }
        }
    }

    /** 流式 TS 服务：边转封装边推（PAT/PMT 先行 + 抽样转包），设备断开即取消。 */
    private fun serveTsRemuxed(socket: java.net.Socket, method: String, session: Session) {
        val upstream = session.currentSource
        val uri = upstream?.url?.let(Uri::parse) ?: session.track.uri
        writeHeaders(socket, 200, linkedMapOf("Content-Type" to "video/mp2t"))
        if (method == "HEAD") return
        val pump = DlnaTsStreamPump(context, uri, upstream?.headers ?: emptyMap())
        pump.pump(socket.getOutputStream()) { !sessions.containsValue(session) }
        socket.getOutputStream().flush()
    }

    private fun relayRemote(
        socket: java.net.Socket,
        method: String,
        range: String?,
        session: Session,
        requestedUrl: String? = null,
    ) {
        var source = session.currentSource ?: run {
            writeHeaders(socket, 404, emptyMap())
            return
        }
        if (requestedUrl != null) source = source.copy(url = requestedUrl, refresh = null)
        repeat(2) { attempt ->
            val connection = runCatching {
                (URL(source.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = method
                source.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (range != null) setRequestProperty("Range", range)
            }
            }.getOrElse {
                writeHeaders(socket, 502, emptyMap())
                return
            }
            try {
            val status = connection.responseCode
            if (status !in setOf(200, 206) && attempt == 0) {
                val refreshed = source.refresh?.invoke()
                if (refreshed != null) {
                    // 写回会话：后续 Range 请求沿用新地址（多会话下原字段副作用已不成立）
                    session.currentSource = refreshed
                    source = refreshed
                    return@repeat
                }
            }
            if (status !in setOf(200, 206)) {
                writeHeaders(socket, 502, emptyMap())
                return
            }
            // m3u8 播放列表必须继续经本机代理，否则后续分片会丢失插件鉴权 headers。
            val upstreamMime = (connection.contentType ?: "").substringBefore(';').trim().lowercase()
            val isHls = upstreamMime.endsWith("mpegurl") || source.url.substringBefore('?').lowercase().let {
                it.endsWith(".m3u8") || it.endsWith(".mpls")
            }
            val mime = when {
                upstreamMime.isNotBlank() -> upstreamMime
                isHls -> "application/vnd.apple.mpegurl"
                session.track.mimeType.isNotBlank() -> dlnaMimeType(session.track.mimeType)
                else -> "application/octet-stream"
            }
            if (method == "GET" && isHls) {
                val base = URL(source.url)
                val attrUri = Regex("""URI="([^"]+)"""")
                fun absolutize(spec: String): String =
                    runCatching { URL(base, spec).toString() }.getOrDefault(spec)
                fun localize(spec: String): String {
                    if (session.hlsParts.size >= 1024) session.hlsParts.clear()
                    val key = UUID.randomUUID().toString()
                    session.hlsParts[key] = absolutize(spec)
                    return "${session.localUrl}?part=$key"
                }
                val body = connection.inputStream.bufferedReader().readLines().joinToString("\r\n") { line ->
                    when {
                        line.startsWith("#") -> attrUri.replace(line) { match -> "URI=\"${localize(match.groupValues[1])}\"" }
                        line.isBlank() -> line
                        else -> localize(line.trim())
                    }
                }.toByteArray(Charsets.UTF_8)
                writeHeaders(socket, status, linkedMapOf("Content-Type" to mime, "Content-Length" to body.size.toString()))
                socket.getOutputStream().apply { write(body); flush() }
                return
            }
            val responseHeaders = linkedMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Type" to (connection.contentType ?: "application/octet-stream"),
            )
            connection.contentLengthLong.takeIf { it >= 0 }?.let { responseHeaders["Content-Length"] = it.toString() }
            connection.getHeaderField("Content-Range")?.let { responseHeaders["Content-Range"] = it }
            writeHeaders(socket, status, responseHeaders)
            if (method == "HEAD") return
            connection.inputStream.use { input ->
                BufferedOutputStream(socket.getOutputStream()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            return
            } finally {
                connection.disconnect()
            }
        }
        writeHeaders(socket, 502, emptyMap())
    }

    private fun writeHeaders(socket: java.net.Socket, status: Int, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
        val match = Regex("^bytes=(\\d+)-(\\d*)$").matchEntire(header ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull()?.coerceAtMost(length - 1) ?: length - 1
        return if (start in 0..end && start < length) start to end else null
    }

    private fun contentLength(uri: Uri): Long = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        ?.takeIf { it >= 0 }
        ?: contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 }
        ?: 0

    private fun dlnaMimeType(mimeType: String): String = when (mimeType.lowercase()) {
        "audio/flac", "audio/x-flac" -> "audio/x-flac"
        else -> mimeType
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Error"
    }

    override fun close() {
        stop()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    companion object {
        fun resolvePrivateLanAddress(): InetAddress {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.isSiteLocalAddress && address.address.size == 4) return address
                }
            }
            error("未找到可用于投送的局域网 IPv4 地址")
        }
    }
}
