package com.example.local_music_player

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * DLNA GENA 事件通道（2026-09-28 A 路线升级）。
 *
 * 语义：对当前投屏设备的 AVTransport 服务发起 SUBSCRIBE，设备把播放状态变化
 * （TransportState 等）以 NOTIFY 推送到本机回调地址——替代原先「只能 500ms 轮询、
 * 设备暂停/停止最长滞后半秒才发现」的口径。轮询保留作为进度来源与降级兜底。
 *
 * 结构：
 * - [DlnaNotifyServer]：进程级单例回调服务器（随机端口），按路径分发 NOTIFY 给各会话；
 * - [DlnaEventChannel]：单设备会话。SUBSCRIBE 拿 SID → 周期续订（超时前 40%）→
 *   close 时 UNSUBSCRIBE。订阅失败/续订失败静默降级（轮询兜底），不抛给业务。
 */
internal object DlnaNotifyServer {
    private var server: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "dlna-notify-accept") }
    private val clientExecutor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "dlna-notify-client") }
    private val listeners = ConcurrentHashMap<String, (String) -> Unit>()

    /** 回调基地址（http://<局域网IP>:<端口>/dlna-events），首次调用时启动服务器。 */
    @Synchronized
    fun callbackBase(): String {
        val current = server
        if (current != null && !current.isClosed) return baseUrl(current.localPort)
        val socket = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        server = socket
        acceptExecutor.execute { acceptLoop(socket) }
        return baseUrl(socket.localPort)
    }

    private fun baseUrl(port: Int): String {
        val address = runCatching { LocalMediaServer.resolvePrivateLanAddress() }.getOrNull()
        val host = address?.hostAddress ?: "127.0.0.1"
        return "http://$host:$port/dlna-events"
    }

    fun register(listener: (String) -> Unit): Pair<String, String> {
        val token = UUID.randomUUID().toString()
        listeners[token] = listener
        return token to "${callbackBase()}/$token"
    }

    fun unregister(token: String) {
        listeners.remove(token)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            runCatching { socket.accept() }.getOrNull()?.let { client ->
                clientExecutor.execute { runCatching { handle(client) } }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            generateSequence { reader.readLine() }
                .takeWhile { it != null && it.isNotEmpty() }
                .forEach { header ->
                    if (header.startsWith("CONTENT-LENGTH:", true) || header.startsWith("Content-Length:", true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
            if (!requestLine.startsWith("NOTIFY ")) {
                socket.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                return
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(buffer, read, contentLength - read)
                    if (count <= 0) break
                    read += count
                }
                String(buffer, 0, read)
            } else ""
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
            val token = path.substringAfterLast('/')
            listeners[token]?.invoke(body)
        }
    }
}

internal class DlnaEventChannel(private val eventSubUrl: String) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callbackToken: String? = null
    @Volatile private var sid: String? = null
    @Volatile private var closed = false
    private var renewal: ScheduledExecutorService? = null

    /** 发起订阅并在成功后开始周期续订。返回是否订阅成功（失败时调用方继续走轮询兜底）。 */
    suspend fun subscribe(timeoutSeconds: Int = 600, onState: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (closed) return@withContext false
            val (token, callbackUrl) = DlnaNotifyServer.register { body ->
                // NOTIFY 到达即解析并回调；业务侧回调统一回主线程
                parseDlnaTransportState(body)?.let { state -> mainHandler.post { onState(state) } }
            }
            callbackToken = token
            val obtained = subscribeRequest(callbackUrl, timeoutSeconds)
            if (!obtained) {
                DlnaNotifyServer.unregister(token)
                callbackToken = null
                return@withContext false
            }
            startRenewal(callbackUrl, timeoutSeconds)
            true
        }

    fun close() {
        closed = true
        renewal?.shutdownNow()
        renewal = null
        val currentSid = sid
        val token = callbackToken
        callbackToken = null
        if (token != null) DlnaNotifyServer.unregister(token)
        if (currentSid != null) {
            // UNSUBSCRIBE 尽力而为：设备可能已离线
            runCatching {
                val connection = URL(eventSubUrl).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "UNSUBSCRIBE"
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.setRequestProperty("SID", currentSid)
                connection.responseCode
                connection.disconnect()
            }
        }
        sid = null
    }

    private fun startRenewal(callbackUrl: String, timeoutSeconds: Int) {
        // 超时前 40% 续订一次；失败按 30s 重试，设备长期离线则订阅自然过期（轮询兜底）
        val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "dlna-gena-renew") }
        renewal = executor
        executor.scheduleWithFixedDelay({
            if (closed) return@scheduleWithFixedDelay
            runCatching { subscribeRequest(callbackUrl, timeoutSeconds) }
        }, timeoutSeconds * 400L, 30_000L, TimeUnit.MILLISECONDS)
    }

    private fun subscribeRequest(callbackUrl: String, timeoutSeconds: Int): Boolean {
        val url = URL(eventSubUrl)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "SUBSCRIBE"
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.setRequestProperty("CALLBACK", "<$callbackUrl>")
        connection.setRequestProperty("NT", "upnp:event")
        connection.setRequestProperty("TIMEOUT", "Second-$timeoutSeconds")
        connection.setRequestProperty("HOST", URI(eventSubUrl).let { "${it.host}:${if (it.port > 0) it.port else 80}" })
        try {
            val status = connection.responseCode
            if (status !in 200..299) return false
            val newSid = connection.getHeaderField("SID") ?: return false
            sid = newSid
            return true
        } finally {
            connection.disconnect()
        }
    }
}
