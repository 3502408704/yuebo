package com.example.local_music_player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.util.Log

internal const val BAIDU_PAN_TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
internal const val BAIDU_PAN_AUTHORIZE_URL = "https://openapi.baidu.com/oauth/2.0/authorize"
internal const val BAIDU_PAN_DEVICE_CODE_URL = "https://openapi.baidu.com/oauth/2.0/device/code"
internal const val BAIDU_PAN_UINFO_URL = "https://pan.baidu.com/rest/2.0/xpan/nas"
internal const val BAIDU_PAN_LIST_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
internal const val BAIDU_PAN_METAS_URL = "https://pan.baidu.com/rest/2.0/xpan/multimedia"
internal const val BAIDU_PAN_ERRNO_TOKEN_EXPIRED = -6
internal const val BAIDU_PAN_PAGE_SIZE = 1000
internal const val BAIDU_PAN_SEARCH_PAGE_SIZE = 100
private const val TAG = "BaiduPanApi"

/** 百度网盘下载/流媒体请求需要的客户端标识（官方文档要求携带 User-Agent）。 */
internal const val BAIDU_PAN_UA =
    "netdisk;P2SP;8.1.0.0;PC;android-android;8.1.0.0;JSBridge;2021-01-01"

/** dlink 下载/流媒体请求专用 UA：百度风控要求下载链接请求必须携带该 UA。 */
internal const val BAIDU_PAN_DLINK_UA = "pan.baidu.com"

data class BaiduPanToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresInMs: Long,
)

/** 设备码登录会话：二维码图片由 qrcodeUrl 提供，userCode 用于网页端确认授权。 */
data class BaiduPanDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val qrcodeUrl: String,
    val expiresInMs: Long,
    val intervalMs: Long,
)

/** 设备码轮询结果：Ok=已授权；Pending=继续轮询；Expired=设备码过期需重新获取；Failed=其他错误。 */
internal sealed class BaiduPanDeviceTokenResult {
    data class Ok(val token: BaiduPanToken) : BaiduPanDeviceTokenResult()
    object Pending : BaiduPanDeviceTokenResult()
    object Expired : BaiduPanDeviceTokenResult()
    data class Failed(val message: String) : BaiduPanDeviceTokenResult()
}

data class BaiduPanUser(
    val name: String,
    val netdiskName: String,
    val vipType: Int,
    val avatarUrl: String?,
)

data class BaiduPanFile(
    val fsId: Long,
    val path: String,
    val isDir: Boolean,
    val serverFilename: String,
    val size: Long,
    val category: Int,
    val serverMtime: Long,
)

data class BaiduPanFileList(
    val files: List<BaiduPanFile>,
    val errno: Int,
)

data class BaiduPanFileMeta(
    val fsId: Long,
    val path: String,
    val dlink: String?,
)

/** 网盘 filemanager 批量操作条目：copy/move 需要 dest（目标目录），newname 可省略。 */
internal data class BaiduPanFileManagerEntry(
    val path: String,
    val dest: String? = null,
    val newname: String? = null,
)

internal fun baiduPanFilelistJson(entries: List<BaiduPanFileManagerEntry>): String =
    JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject().apply {
                    put("path", entry.path)
                    entry.dest?.let { put("dest", it) }
                    entry.newname?.let { put("newname", it) }
                },
            )
        }
    }.toString()

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** dlink 追加 access_token；dlink 通常已带查询参数，个别情况没有则用 ? 连接。 */
internal fun baiduPanDlinkWithToken(dlink: String, accessToken: String): String {
    val separator = if ('?' in dlink) "&" else "?"
    return "$dlink$separator" + "access_token=${urlEncode(accessToken)}"
}

/** 网盘全局搜索 URL：dir 固定为根目录并递归子目录，与 list 接口返回同样的文件列表结构。 */
internal fun baiduPanSearchUrl(
    accessToken: String,
    keyword: String,
    page: Int = 1,
    num: Int = BAIDU_PAN_SEARCH_PAGE_SIZE,
): String = "$BAIDU_PAN_LIST_URL?method=search&key=${urlEncode(keyword)}" +
    "&dir=${urlEncode("/")}&web=1&recursion=1&page=$page&num=$num" +
    "&access_token=${urlEncode(accessToken)}"

/** 解析重定向后的下一个请求地址：绝对地址直接用，相对地址基于当前地址解析。 */
internal fun resolveBaiduPanRedirectUrl(currentUrl: String, location: String): String =
    URL(URL(currentUrl), location).toString()

/**
 * 打开 dlink 下载连接：携带 UA，手动跟随 301/302/303/307/308 重定向。
 * 百度官方说明 302 属风控策略，需解析 Location 重新请求；自动跟随可能丢失 UA/Range 头。
 * 返回的连接由调用方检查 200/206 并负责关闭。
 */
internal fun openBaiduPanDlinkConnection(
    dlink: String,
    accessToken: String,
    range: String?,
): HttpURLConnection {
    var url: String = baiduPanDlinkWithToken(dlink, accessToken)
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
            next.setRequestProperty("User-Agent", BAIDU_PAN_DLINK_UA)
            if (range != null) next.setRequestProperty("Range", range)
            val code = next.responseCode
            val location = next.getHeaderField("Location")
            if (code in setOf(301, 302, 303, 307, 308) &&
                !location.isNullOrBlank() && redirects < 4
            ) {
                url = resolveBaiduPanRedirectUrl(next.url.toString(), location)
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

/** 验证页 URL 附带移动端展示与用户码：与官方二维码内容一致，打开后自动完成授权码输入。 */
internal fun baiduPanVerificationUrlWithCode(code: BaiduPanDeviceCode): String {
    val separator = if ('?' in code.verificationUrl) "&" else "?"
    return "${code.verificationUrl}$separator" + "display=mobile&code=${urlEncode(code.userCode)}"
}

internal fun parseBaiduPanToken(json: String): BaiduPanToken? {
    val body = JSONObject(json)
    val access = body.optString("access_token")
    if (access.isBlank()) return null
    return BaiduPanToken(
        accessToken = access,
        refreshToken = body.optString("refresh_token"),
        expiresInMs = body.optLong("expires_in", 0) * 1_000,
    )
}

internal fun parseBaiduPanDeviceCode(json: String): BaiduPanDeviceCode? {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val deviceCode = body.optString("device_code")
    if (deviceCode.isBlank()) return null
    return BaiduPanDeviceCode(
        deviceCode = deviceCode,
        userCode = body.optString("user_code"),
        verificationUrl = body.optString("verification_url"),
        qrcodeUrl = body.optString("qrcode_url"),
        expiresInMs = body.optLong("expires_in", 0) * 1_000,
        intervalMs = body.optLong("interval", 0) * 1_000,
    )
}

internal fun parseBaiduPanDeviceTokenResult(json: String): BaiduPanDeviceTokenResult {
    val body = runCatching { JSONObject(json) }.getOrNull()
        ?: return BaiduPanDeviceTokenResult.Failed("百度登录失败，请重试")
    return when (body.optString("error")) {
        "authorization_pending", "slow_down" -> BaiduPanDeviceTokenResult.Pending
        "invalid_grant", "expired_token" -> BaiduPanDeviceTokenResult.Expired
        else -> parseBaiduPanToken(json)?.let { BaiduPanDeviceTokenResult.Ok(it) }
            ?: BaiduPanDeviceTokenResult.Failed(oauthErrorMessage(json))
    }
}

internal fun oauthErrorMessage(json: String): String {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return "百度登录失败，请重试"
    return body.optString("error_description")
        .ifBlank { body.optString("error") }
        .ifBlank { "百度登录失败，请重试" }
}

/** 判断回调 URL 是否为百度 OAuth 回调：协议/主机/端口/路径一致（百度会在回调地址后追加 query）。 */
internal fun isBaiduPanOAuthRedirect(url: String, redirectUri: String): Boolean {
    val actual = runCatching { URL(url) }.getOrNull() ?: return false
    val registered = runCatching { URL(redirectUri) }.getOrNull() ?: return false
    return actual.protocol == registered.protocol &&
        actual.authority == registered.authority &&
        actual.path.ifBlank { "/" } == registered.path.ifBlank { "/" }
}

/** 取 URL query 参数首个值：纯标准库实现，供 JVM 单测与回调解析共用。 */
internal fun baiduPanQueryParameter(url: String, name: String): String? {
    val query = url.substringAfter('?', "").substringBefore('#')
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        if (urlDecode(pair.substringBefore('=')) == name) {
            return urlDecode(pair.substringAfter('=', ""))
        }
    }
    return null
}

private fun urlDecode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

internal fun parseBaiduPanUser(json: String): BaiduPanUser? {
    val body = JSONObject(json)
    if (body.optInt("errno", -1) != 0) return null
    return BaiduPanUser(
        name = body.optString("baidu_name"),
        netdiskName = body.optString("netdisk_name"),
        vipType = body.optInt("vip_type", 0),
        avatarUrl = body.optString("avatar_url").ifBlank { null },
    )
}

internal fun parseBaiduPanFileList(json: String): BaiduPanFileList {
    val body = JSONObject(json)
    val errno = body.optInt("errno", -1)
    val list = body.optJSONArray("list") ?: JSONArray()
    val files = List(list.length()) { index ->
        val item = list.getJSONObject(index)
        BaiduPanFile(
            fsId = item.optLong("fs_id"),
            path = item.optString("path"),
            isDir = item.optInt("isdir", 0) == 1,
            serverFilename = item.optString("server_filename"),
            size = item.optLong("size"),
            category = item.optInt("category", 0),
            serverMtime = item.optLong("server_mtime"),
        )
    }
    return BaiduPanFileList(files, errno)
}

internal fun parseBaiduPanFileMetas(json: String): List<BaiduPanFileMeta> {
    val body = JSONObject(json)
    if (body.optInt("errno", -1) != 0) return emptyList()
    val list = body.optJSONArray("list") ?: return emptyList()
    return List(list.length()) { index ->
        val item = list.getJSONObject(index)
        BaiduPanFileMeta(
            fsId = item.optLong("fs_id"),
            path = item.optString("path"),
            dlink = item.optString("dlink").ifBlank { null },
        )
    }
}

internal fun panApiErrorMessage(json: String): String {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return "百度网盘请求失败"
    return body.optString("errmsg").ifBlank { panErrorMessage(body.optInt("errno", -1)) }
}

internal fun panErrorMessage(errno: Int): String = when (errno) {
    0 -> ""
    BAIDU_PAN_ERRNO_TOKEN_EXPIRED -> "百度账号登录已过期，请重新登录"
    -9 -> "文件不存在或已删除"
    -10 -> "文件已删除或移动"
    else -> "百度网盘请求失败（错误码 $errno）"
}

/** 音频扩展名判定：网盘内容不过滤展示，但只有音频才提供在线播放。 */
internal fun isPanAudioFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf(
        "mp3", "ogg", "oga", "flac", "wav", "aiff", "aif",
        "m4a", "aac", "m4b", "mp2", "mp1", "ape", "wv", "opus", "webm",
    )
}

/** 视频扩展名判定：云端文件点击后走独立视频播放器（不进入音频队列）。 */
internal fun isPanVideoFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf(
        "mp4", "m4v", "webm", "mkv", "ts", "flv", "3gp", "mov", "avi", "wmv", "rmvb", "rm", "mpg", "mpeg",
    )
}

internal class BaiduPanApi(
    private val apiKey: String,
    private val secretKey: String,
    /** 授权码模式回调地址：在百度开放平台控制台登记后配置；为空表示未启用授权码模式。 */
    val oauthRedirectUri: String = "",
) {
    /**
     * 授权码模式授权页地址（官方文档：适用于有 Server 端的应用）。
     * 用户在页面登录并确认后百度 302 到 redirect_uri 并携带 code，全程无需输入设备码；
     * 未配置回调地址时返回 null，调用方回退设备码流程。
     */
    fun authorizePageUrl(): String? {
        if (oauthRedirectUri.isBlank()) return null
        return "$BAIDU_PAN_AUTHORIZE_URL?response_type=code" +
            "&client_id=${urlEncode(apiKey)}&redirect_uri=${urlEncode(oauthRedirectUri)}" +
            "&scope=basic,netdisk&display=mobile"
    }

    /** 授权码换 token：code 一次性且 10 分钟有效，redirect_uri 必须与授权时完全一致。 */
    suspend fun exchangeAuthorizationCode(authCode: String): BaiduPanToken =
        withContext(Dispatchers.IO) {
            val response = get(
                "$BAIDU_PAN_TOKEN_URL?grant_type=authorization_code" +
                    "&code=${urlEncode(authCode)}" +
                    "&client_id=${urlEncode(apiKey)}&client_secret=${urlEncode(secretKey)}" +
                    "&redirect_uri=${urlEncode(oauthRedirectUri)}",
            )
            parseBaiduPanToken(response) ?: error(oauthErrorMessage(response))
        }

    /** 获取设备码：qrcodeUrl 是二维码图片地址，配合轮询完成免密登录。 */
    suspend fun deviceCode(): BaiduPanDeviceCode = withContext(Dispatchers.IO) {
        val url = "$BAIDU_PAN_DEVICE_CODE_URL?response_type=device_code" +
            "&client_id=${urlEncode(apiKey)}&scope=basic,netdisk"
        val response = get(url)
        parseBaiduPanDeviceCode(response) ?: error(oauthErrorMessage(response))
    }

    /** 轮询设备码授权状态；Pending 表示用户尚未扫码确认。 */
    suspend fun exchangeDeviceToken(deviceCode: String): BaiduPanDeviceTokenResult =
        withContext(Dispatchers.IO) {
            val url = "$BAIDU_PAN_TOKEN_URL?grant_type=device_token" +
                "&code=${urlEncode(deviceCode)}" +
                "&client_id=${urlEncode(apiKey)}&client_secret=${urlEncode(secretKey)}"
            parseBaiduPanDeviceTokenResult(get(url))
        }

    /** 下载二维码图片（百度返回 PNG 字节）。 */
    suspend fun qrImageBytes(qrcodeUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(qrcodeUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", BAIDU_PAN_UA)
            if (connection.responseCode !in 200..299) {
                error("二维码下载失败（${connection.responseCode}）")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun refreshAccessToken(refreshToken: String): BaiduPanToken = withContext(Dispatchers.IO) {
        val response = postForm(
            BAIDU_PAN_TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to apiKey,
                "client_secret" to secretKey,
            ),
        )
        parseBaiduPanToken(response) ?: error(oauthErrorMessage(response))
    }

    suspend fun userInfo(accessToken: String): BaiduPanUser = withContext(Dispatchers.IO) {
        val response = get(
            "$BAIDU_PAN_UINFO_URL?method=uinfo&access_token=${urlEncode(accessToken)}",
        )
        parseBaiduPanUser(response) ?: error(panApiErrorMessage(response))
    }

    suspend fun listDir(
        accessToken: String,
        dir: String,
        start: Int = 0,
        limit: Int = BAIDU_PAN_PAGE_SIZE,
    ): BaiduPanFileList = withContext(Dispatchers.IO) {
        val url = "$BAIDU_PAN_LIST_URL?method=list&dir=${urlEncode(dir)}&start=$start" +
            "&limit=$limit&order=name&desc=0&access_token=${urlEncode(accessToken)}"
        parseBaiduPanFileList(get(url))
    }

    /**
     * 递归列出 dir 下所有非目录文件（含各级子目录），供整个文件夹下载使用。
     * 逐目录分页调用 [listDir]，子目录入队继续遍历；最多收集 [maxFiles] 个文件。
     */
    suspend fun listDirRecursive(
        accessToken: String,
        dir: String,
        maxFiles: Int = 2000,
    ): List<BaiduPanFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<BaiduPanFile>()
        val pendingDirs = ArrayDeque<String>()
        pendingDirs.add(dir)
        while (pendingDirs.isNotEmpty() && files.size < maxFiles) {
            val current = pendingDirs.removeFirst()
            var start = 0
            while (files.size < maxFiles) {
                val page = listDir(accessToken, current, start)
                if (page.errno != 0 || page.files.isEmpty()) break
                for (item in page.files) {
                    if (item.isDir) {
                        pendingDirs.add(item.path)
                    } else if (files.size < maxFiles) {
                        files.add(item)
                    }
                }
                if (page.files.size < BAIDU_PAN_PAGE_SIZE) break
                start += page.files.size
            }
        }
        files
    }

    /** 网盘全局关键字搜索：返回结构与 list 相同，可复用同一解析。 */
    suspend fun search(
        accessToken: String,
        keyword: String,
        page: Int = 1,
        num: Int = BAIDU_PAN_SEARCH_PAGE_SIZE,
    ): BaiduPanFileList = withContext(Dispatchers.IO) {
        parseBaiduPanFileList(get(baiduPanSearchUrl(accessToken, keyword, page, num)))
    }

    suspend fun fileMetas(accessToken: String, fsIds: List<Long>): List<BaiduPanFileMeta> =
        withContext(Dispatchers.IO) {
            val fsids = fsIds.joinToString(",", "[", "]")
            val url = "$BAIDU_PAN_METAS_URL?method=filemetas&dlink=1&fsids=$fsids" +
                "&access_token=${urlEncode(accessToken)}"
            parseBaiduPanFileMetas(get(url))
        }

    /** 删除网盘文件（同步删除）；返回 errno，0 表示成功，-9/-10 表示文件已不存在。 */
    suspend fun deleteFile(accessToken: String, path: String): Int = withContext(Dispatchers.IO) {
        val url = "$BAIDU_PAN_LIST_URL?method=filemanager&opera=delete&openapi=xpansdk" +
            "&access_token=${urlEncode(accessToken)}"
        val filelist = JSONArray().put(path).toString()
        val response = postForm(url, mapOf("async" to "0", "filelist" to filelist))
        Log.d(TAG, "deleteFile url=$url filelist=$filelist response=$response")
        val body = runCatching { JSONObject(response) }.getOrNull()
            ?: error(panApiErrorMessage(response))
        body.optInt("errno", -1)
    }

    /** 复制或移动网盘文件/文件夹到目标目录；返回 errno，0 成功，-9/-10 已不存在。 */
    suspend fun copyOrMoveFile(
        accessToken: String,
        opera: String,
        paths: List<String>,
        dest: String,
        newname: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        val url = "$BAIDU_PAN_LIST_URL?method=filemanager&opera=${urlEncode(opera)}&openapi=xpansdk" +
            "&access_token=${urlEncode(accessToken)}"
        val filelist = baiduPanFilelistJson(paths.map { BaiduPanFileManagerEntry(it, dest, newname) })
        val response = postForm(
            url,
            mapOf(
                "async" to "0",
                "ondup" to "newcopy",
                "filelist" to filelist,
            ),
        )
        Log.d(TAG, "copyOrMoveFile url=$url filelist=$filelist response=$response")
        val body = runCatching { JSONObject(response) }.getOrNull()
            ?: error(panApiErrorMessage(response))
        body.optInt("errno", -1)
    }

    private fun postForm(url: String, fields: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", BAIDU_PAN_UA)
            val body = fields.entries.joinToString("&") { (key, value) ->
                "${urlEncode(key)}=${urlEncode(value)}"
            }
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            return readBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", BAIDU_PAN_UA)
            return readBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    /** 百度 OAuth 错误（如 authorization_pending）以 400 状态返回 JSON，必须读 errorStream。 */
    private fun readBody(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (text.isBlank()) error("百度接口返回 $code")
        return text
    }
}
