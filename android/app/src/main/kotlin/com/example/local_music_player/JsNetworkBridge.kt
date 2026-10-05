package com.example.local_music_player

import android.util.Base64
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Java 网络桥：向 JS 注入 __wwHttp(config)，内部用 HttpURLConnection 发请求。
 *
 * 安全边界（最终版约束）：插件发起的请求只允许 http/https，且目标 host 不得是
 * localhost、环回、私有或保留地址——音源脚本来自外部导入，不能借宿主网络探测
 * 内网或打本机服务。校验在 [validatePluginUrl]（纯函数，可单测）。
 */
class JsNetworkBridge(private val runtime: QuickJsRuntime) {

    fun install() {
        runtime.setGlobal("__wwHttp", JSCallFunction { args ->
            val config = args.getOrNull(0) as? JSObject ?: return@JSCallFunction null
            // 加载阶段不发起真实网络：插件顶层副作用（如 getTopListDetail().then(...)）会在
            // evaluate 期间同步请求，失败即未处理拒绝导致导入失败；挂起后加载完成再放行。
            if (!runtime.allowNetwork) return@JSCallFunction runtime.pendingPromise()
            val result = runCatching { http(config.toMap()) }.getOrElse { e ->
                mapOf("__error" to (e.message ?: "网络请求失败"))
            }
            if (result["__error"] != null) {
                runtime.rejectPromise(mapOf("message" to result["__error"]))
            } else {
                runtime.resolvePromise(result)
            }
        })
    }

    /** 同步 HTTP 请求；返回 {status, statusText, headers, body[, bodyBase64]}。 */
    fun http(config: Map<String, Any?>): Map<String, Any?> {
        val urlString = config["url"]?.toString() ?: throw IOException("缺少 url")
        validatePluginUrl(urlString)
        val method = (config["method"]?.toString() ?: "GET").uppercase()
        val headers = (config["headers"] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
        val data = config["data"]
        val binary = config["responseType"]?.toString() == "arraybuffer"
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            for ((name, value) in headers) {
                // 只声明 gzip：插件默认 accept-encoding 含 br/deflate，服务端可能回 br 导致乱码。
                if (name.equals("accept-encoding", true) && value.contains("br")) {
                    connection.setRequestProperty(name, "gzip")
                } else {
                    connection.setRequestProperty(name, value)
                }
            }
            if (data != null && method != "GET" && method != "HEAD") {
                connection.doOutput = true
                val bytes = if (data is ByteArray) data else data.toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .entries
                .associate { (name, values) -> name.lowercase() to (values.firstOrNull() ?: "") }
            val body = readBody(connection, status, binary)
            connection.disconnect()
            val result = linkedMapOf<String, Any?>(
                "status" to status,
                "statusText" to statusText(status),
                "headers" to responseHeaders,
            )
            if (binary) {
                val bytes = body as? ByteArray ?: ByteArray(0)
                result["bodyBase64"] = Base64.encodeToString(bytes, Base64.NO_WRAP)
                result["body"] = ""
            } else {
                result["body"] = body as? String ?: ""
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, status: Int, binary: Boolean): Any {
        val stream = try {
            if (status >= 400) connection.errorStream else connection.inputStream
        } catch (_: IOException) {
            null
        } ?: return if (binary) ByteArray(0) else ""
        return stream.use { raw ->
            val encoding = connection.getHeaderField("Content-Encoding")?.lowercase() ?: ""
            val decoded = when {
                encoding.contains("gzip") -> GZIPInputStream(raw)
                encoding.contains("deflate") -> InflaterInputStream(raw, Inflater(true))
                else -> raw
            }
            decoded.use { input ->
                if (binary) input.readBytes()
                else input.readBytes().toString(charsetFromContentType(connection.getHeaderField("Content-Type")))
            }
        }
    }

    private fun charsetFromContentType(contentType: String?): Charset {
        val match = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType ?: return Charsets.UTF_8)
        return runCatching { Charset.forName(match?.groupValues?.get(1) ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }

    companion object {
        /**
         * 插件请求 URL 校验：仅 http/https；host 拒绝 localhost、环回、私有与保留地址
         * （含点分 IPv4 与 [IPv6] 字面量、以及 *.localhost 形式）。失败抛 IOException。
         */
        fun validatePluginUrl(urlString: String) {
            val uri = runCatching { URI(urlString.trim()) }.getOrElse { throw IOException("URL 无效") }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw IOException("仅允许 http/https 请求")
            val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
                ?: throw IOException("URL 缺少 host")
            if (host == "*" || host.isBlank()) throw IOException("URL host 无效")
            if (host.endsWith(".localhost") || host == "localhost") throw IOException("禁止请求本地地址")
            if (host.contains(":")) {
                if (isReservedIpv6(host)) throw IOException("禁止请求内网/保留地址")
                return
            }
            val octets = host.split('.').map { part -> part.toIntOrNull() }
            if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
                if (isReservedIpv4(octets.map { it!! })) throw IOException("禁止请求内网/保留地址")
            }
        }

        private fun isReservedIpv4(o: List<Int>): Boolean = when {
            o[0] == 0 || o[0] == 10 || o[0] == 127 -> true                              // 0/8、10/8、环回
            o[0] == 100 && o[1] in 64..127 -> true                                      // 100.64/10 CGNAT
            o[0] == 169 && o[1] == 254 -> true                                          // 链路本地
            o[0] == 172 && o[1] in 16..31 -> true                                       // 172.16/12
            o[0] == 192 && o[1] == 168 -> true                                          // 192.168/16
            o[0] == 192 && o[1] == 0 && o[2] == 0 -> true                               // 192.0.0/24
            o[0] == 192 && o[1] == 0 && o[2] == 2 -> true                               // TEST-NET-1
            o[0] == 198 && (o[1] == 18 || o[1] == 19) -> true                           // 198.18/15 基准测试
            o[0] == 198 && o[1] == 51 && o[2] == 100 -> true                            // TEST-NET-2
            o[0] == 203 && o[1] == 0 && o[2] == 113 -> true                             // TEST-NET-3
            o[0] >= 224 -> true                                                         // 组播/保留
            else -> false
        }

        private fun isReservedIpv6(h: String): Boolean = when {
            h == "::" || h == "::1" -> true                                             // 未指定/环回
            h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") ||
                h.startsWith("feb") -> true                                             // 链路本地 fe80::/10
            h.startsWith("fc") || h.startsWith("fd") -> true                            // ULA fc00::/7
            h.startsWith("ff") -> true                                                  // 组播
            h == "::ffff:127.0.0.1" -> true                                             // IPv4 映射环回
            else -> false
        }
    }
}
