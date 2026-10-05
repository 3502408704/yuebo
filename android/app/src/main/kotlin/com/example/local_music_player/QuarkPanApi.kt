package com.example.local_music_player

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val QUARK_API_BASE = "https://drive-pc.quark.cn/1/clouddrive"
internal const val QUARK_PAN_REFERER = "https://pan.quark.cn"
internal const val QUARK_PAN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
internal const val QUARK_QR_TOKEN_URL = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
internal const val QUARK_QR_POLL_URL = "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
internal const val QUARK_ACCOUNT_INFO_URL = "https://pan.quark.cn/account/info"
internal const val QUARK_QR_LANDING_URL = "https://su.quark.cn/4_eMHBJ"
internal const val QUARK_CLIENT_ID = "532"
internal const val QUARK_PAN_PAGE_SIZE = 100
internal const val QUARK_PAN_SEARCH_PAGE_SIZE = 100
internal const val QUARK_SESSION_REFRESH_INTERVAL_MS = 100 * 60 * 1000L
internal const val QUARK_WEB_LOGIN_URL = "https://pan.quark.cn/#/login"
internal const val QUARK_WEBVIEW_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
internal val QUARK_WEB_COOKIE_DOMAINS = listOf(
    "https://pan.quark.cn",
    "https://drive.quark.cn",
    "https://drive-pc.quark.cn",
    "https://drive-h.quark.cn",
    "https://uop.quark.cn",
)
private const val TAG = "QuarkPanApi"

/** 夸克账号信息：昵称/头像（v1 头像可不展示）。 */
data class QuarkPanUser(
    val name: String,
    val avatarUrl: String?,
)

/** 二维码登录会话：qrUrl 是二维码内容（需渲染成图片供夸克 App 扫码）。 */
data class QuarkPanQrSession(
    val qrToken: String,
    val qrUrl: String,
)

/** 二维码轮询结果：Ok=已生成票据，可换 Cookie；Pending=等待扫码；Expired=过期；Failed=其他错误。 */
internal sealed class QuarkPanQrPollResult {
    data class Ok(val serviceTicket: String) : QuarkPanQrPollResult()
    object Pending : QuarkPanQrPollResult()
    object Expired : QuarkPanQrPollResult()
    data class Failed(val message: String) : QuarkPanQrPollResult()
}

data class QuarkPanFile(
    val fid: String,
    val pdirFid: String,
    val fileName: String,
    val isDir: Boolean,
    val size: Long,
    val category: Int,
    val updatedAt: Long,
)

data class QuarkPanFileList(
    val files: List<QuarkPanFile>,
    val hasMore: Boolean,
    val total: Int,
)

/** 登录二维码内容 URL：夸克 App 扫该 URL 进入确认页授权。 */
internal fun quarkQrUrl(token: String): String =
    "$QUARK_QR_LANDING_URL?token=${urlEncode(token)}&client_id=$QUARK_CLIENT_ID&ssb=weblogin" +
        "&uc_param_str=&uc_biz_str=${urlEncode("S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0")}"

internal fun quarkQrTokenFromJson(json: String): String? {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (body.optInt("status", -1) != 2000000) return null
    return body.optJSONObject("data")?.optJSONObject("members")?.optString("token")
        ?.takeIf { it.isNotBlank() }
}

internal fun quarkQrPollResult(json: String): QuarkPanQrPollResult {
    val body = runCatching { JSONObject(json) }.getOrNull()
        ?: return QuarkPanQrPollResult.Failed("夸克登录失败，请重试")
    return when (body.optInt("status", -1)) {
        2000000 -> {
            val ticket = body.optJSONObject("data")?.optJSONObject("members")
                ?.optString("service_ticket")
            if (ticket.isNullOrBlank()) {
                QuarkPanQrPollResult.Failed("夸克登录响应缺少授权票据")
            } else {
                QuarkPanQrPollResult.Ok(ticket)
            }
        }
        50004001 -> QuarkPanQrPollResult.Pending
        50004002, 50004003, 50004004 -> QuarkPanQrPollResult.Expired
        else -> QuarkPanQrPollResult.Failed(body.optString("message").ifBlank { "夸克登录失败，请重试" })
    }
}

internal fun parseQuarkPanUser(json: String): QuarkPanUser? {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val data = body.optJSONObject("data") ?: body
    val name = listOf("nickname", "nick_name", "user_name", "name", "display_name")
        .firstNotNullOfOrNull { data.optString(it).takeIf(String::isNotBlank) }
        ?: return null
    return QuarkPanUser(
        name = name,
        avatarUrl = data.optString("avatar").ifBlank { data.optString("avatar_url").ifBlank { null } },
    )
}

internal fun parseQuarkPanFileList(json: String): QuarkPanFileList {
    val body = JSONObject(json)
    val data = body.optJSONObject("data") ?: JSONObject()
    val list = data.optJSONArray("list") ?: JSONArray()
    val files = List(list.length()) { index ->
        val item = list.getJSONObject(index)
        QuarkPanFile(
            fid = item.optString("fid"),
            pdirFid = item.optString("pdir_fid"),
            fileName = item.optString("file_name"),
            isDir = item.optInt("file_type", -1) == 0 || item.optBoolean("dir", false),
            size = item.optLong("size"),
            category = item.optInt("category", 0),
            updatedAt = item.optLong("updated_at"),
        )
    }
    val total = data.optJSONObject("metadata")?.optInt("total", -1) ?: -1
    val hasMore = if (total >= 0) files.size < total else files.isNotEmpty()
    return QuarkPanFileList(files, hasMore, total)
}

internal fun quarkDownloadUrlFromJson(json: String): String? {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val list = body.optJSONArray("data") ?: return null
    if (list.length() == 0) return null
    return list.getJSONObject(0).optString("download_url").ifBlank { null }
}

internal fun quarkApiErrorMessage(json: String): String {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return "夸克网盘请求失败"
    return body.optString("message").ifBlank { body.optString("msg") }
        .ifBlank { "夸克网盘请求失败（状态 ${body.optInt("status", -1)}）" }
}

internal fun quarkErrorMessage(status: Int, message: String): String = when {
    status == 401 -> "夸克登录已过期，请重新登录"
    status == 403 -> "夸克网盘访问被拒绝，请重新登录"
    message.isNotBlank() -> message
    else -> "夸克网盘请求失败（HTTP $status）"
}

internal fun quarkFileSortUrl(pdirFid: String, page: Int, size: Int, sort: String): String =
    "$QUARK_API_BASE/file/sort?pr=ucpro&fr=pc&pdir_fid=${urlEncode(pdirFid)}" +
        "&_page=$page&_size=$size&_fetch_total=1&fetch_all_file=1&_sort=${urlEncode(sort)}"

internal fun quarkSearchUrl(keyword: String, page: Int, size: Int): String =
    "$QUARK_API_BASE/file/search?pr=ucpro&fr=pc&q=${urlEncode(keyword)}" +
        "&_page=$page&_size=$size&_fetch_total=1&_sort=${urlEncode("file_type:asc,file_name:asc")}"

internal fun quarkDeleteBody(fids: List<String>): String =
    JSONObject()
        .put("action_type", 2)
        .put("filelist", JSONArray(fids))
        .put("exclude_fids", JSONArray())
        .toString()

internal fun quarkRenameBody(fid: String, newName: String): String =
    JSONObject().put("fid", fid).put("file_name", newName).toString()

/** actionType：0=复制，1=移动。 */
internal fun quarkCopyMoveBody(actionType: Int, fids: List<String>, toPdirFid: String): String =
    JSONObject()
        .put("action_type", actionType)
        .put("to_pdir_fid", toPdirFid)
        .put("filelist", JSONArray(fids))
        .put("exclude_fids", JSONArray())
        .toString()

/**
 * 合并夸克 Cookie：保留已有会话并按 Set-Cookie 新增/覆盖。
 * 注意先 trim 再定位 '='，避免前导空格导致 substring 越界（历史 bug）。
 */
internal fun mergeQuarkCookies(current: String, setCookies: List<String>): String {
    if (setCookies.isEmpty()) return current
    val map = LinkedHashMap<String, String>()
    map.putAll(parseQuarkCookieString(current))
    setCookies.forEach { header ->
        val name = header.substringBefore('=').trim()
        val value = header.substringAfter('=', "").substringBefore(';')
        if (name.isNotEmpty()) {
            map[name] = value
        }
    }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * 把 Cookie 字符串安全解析为 name->value。
 * 先 trim 再定位 '='，并做边界保护，避免前导空格导致 substring 越界崩溃。
 */
internal fun parseQuarkCookieString(cookie: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    cookie.split(';').forEach { rawPart ->
        val part = rawPart.trim()
        val eq = part.indexOf('=')
        if (eq > 0 && eq < part.length) {
            map[part.substring(0, eq)] = part.substring(eq + 1)
        }
    }
    return map
}

/**
 * 夸克网盘 API 客户端。会话状态是 Cookie：登录成功后由 [setCookie] 注入，
 * 每次响应的 Set-Cookie（如 __puus 续期）自动合并回 [cookie]。
 */
internal class QuarkPanApi {
    @Volatile var cookie: String = ""
        private set

    fun setCookie(value: String) {
        cookie = value
    }

    /** 获取二维码登录会话；随后用 [pollQrLogin] 轮询，成功后 [exchangeTicket] 换 Cookie。 */
    suspend fun getQrSession(): QuarkPanQrSession = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val url = "$QUARK_QR_TOKEN_URL?client_id=$QUARK_CLIENT_ID&v=1.2&request_id=$requestId"
        val (json) = request(
            url = url,
            method = "GET",
            body = null,
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
        Log.d(TAG, "getQrSession response: ${json.take(200)}")
        val token = quarkQrTokenFromJson(json) ?: error(quarkApiErrorMessage(json))
        QuarkPanQrSession(qrToken = token, qrUrl = quarkQrUrl(token))
    }

    /** 轮询二维码登录状态。 */
    suspend fun pollQrLogin(qrToken: String): QuarkPanQrPollResult = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val url = "$QUARK_QR_POLL_URL?client_id=$QUARK_CLIENT_ID&v=1.2" +
            "&token=${urlEncode(qrToken)}&request_id=$requestId"
        val (json) = request(
            url = url,
            method = "GET",
            body = null,
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
        quarkQrPollResult(json)
    }

    /** 用 service_ticket 换取会话 Cookie 与账号信息；成功后 [cookie] 已更新。 */
    suspend fun exchangeTicket(serviceTicket: String): QuarkPanUser = withContext(Dispatchers.IO) {
        val url = "$QUARK_ACCOUNT_INFO_URL?st=${urlEncode(serviceTicket)}&lw=scan"
        val (json) = request(
            url = url,
            method = "GET",
            body = null,
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
        parseQuarkPanUser(json) ?: error(quarkApiErrorMessage(json))
    }

    /** 用当前会话 Cookie 获取账号信息（网页登录/会话恢复用）。 */
    suspend fun userInfo(): QuarkPanUser = withContext(Dispatchers.IO) {
        val url = "$QUARK_ACCOUNT_INFO_URL?fr=pc&platform=pc"
        val (json) = request(
            url = url,
            method = "GET",
            body = null,
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
        parseQuarkPanUser(json) ?: error(quarkApiErrorMessage(json))
    }

    suspend fun listDir(
        pdirFid: String,
        page: Int = 1,
        size: Int = QUARK_PAN_PAGE_SIZE,
    ): QuarkPanFileList = withContext(Dispatchers.IO) {
        val (json) = request(
            url = quarkFileSortUrl(pdirFid, page, size, "file_type:asc,file_name:asc"),
            method = "GET",
            body = null,
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
        parseQuarkPanFileList(json)
    }

    /**
     * 递归列出 fid 下所有非目录文件（含各级子目录），供整个文件夹下载使用。
     * 返回 (文件, 相对目录)；相对目录为空表示文件直接位于目标文件夹下。
     */
    suspend fun listDirRecursive(
        fid: String,
        maxFiles: Int = 2000,
    ): List<Pair<QuarkPanFile, String>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<QuarkPanFile, String>>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(fid to "")
        while (pending.isNotEmpty() && result.size < maxFiles) {
            val (currentFid, rel) = pending.removeFirst()
            var page = 1
            while (result.size < maxFiles) {
                val list = listDir(currentFid, page)
                if (list.files.isEmpty()) break
                for (item in list.files) {
                    if (item.isDir) {
                        pending.add(item.fid to if (rel.isBlank()) item.fileName else "$rel/${item.fileName}")
                    } else if (result.size < maxFiles) {
                        result.add(item to rel)
                    }
                }
                if (!list.hasMore) break
                page++
            }
        }
        result
    }

    suspend fun search(
        keyword: String,
        page: Int = 1,
        size: Int = QUARK_PAN_SEARCH_PAGE_SIZE,
    ): QuarkPanFileList = withContext(Dispatchers.IO) {
        val (json) = request(
            url = quarkSearchUrl(keyword, page, size),
            method = "GET",
            body = null,
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
        parseQuarkPanFileList(json)
    }

    /** 获取下载地址；URL 签名绑定请求时的 Cookie，流式下载必须携带同一 Cookie 快照。 */
    suspend fun getDownloadUrl(fid: String): String = withContext(Dispatchers.IO) {
        val (json) = request(
            url = "$QUARK_API_BASE/file/download?pr=ucpro&fr=pc",
            method = "POST",
            body = "{\"fids\":[\"$fid\"]}",
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
            extraHeaders = mapOf("User-Agent" to QUARK_PAN_UA),
        )
        quarkDownloadUrlFromJson(json) ?: error(quarkApiErrorMessage(json))
    }

    suspend fun delete(fids: List<String>) = withContext(Dispatchers.IO) {
        request(
            url = "$QUARK_API_BASE/file/delete?pr=ucpro&fr=pc&uc_param_str=",
            method = "POST",
            body = quarkDeleteBody(fids),
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
    }

    suspend fun rename(fid: String, newName: String) = withContext(Dispatchers.IO) {
        request(
            url = "$QUARK_API_BASE/file/rename?pr=ucpro&fr=pc&uc_param_str=",
            method = "POST",
            body = quarkRenameBody(fid, newName),
            referer = QUARK_PAN_REFERER,
            cookie = cookie,
        )
    }

    /**
     * 复制(0)或移动(1)文件到目标目录；服务端可能异步返回 task_id，轮询直至完成。
     */
    suspend fun copyOrMove(actionType: Int, fids: List<String>, toPdirFid: String) =
        withContext(Dispatchers.IO) {
            val endpoint = if (actionType == 0) "file/copy" else "file/move"
            val (json) = request(
                url = "$QUARK_API_BASE/$endpoint?pr=ucpro&fr=pc&uc_param_str=",
                method = "POST",
                body = quarkCopyMoveBody(actionType, fids, toPdirFid),
                referer = QUARK_PAN_REFERER,
                cookie = cookie,
            )
            val body = runCatching { JSONObject(json) }.getOrNull()
            val data = body?.optJSONObject("data")
            val finish = data?.optBoolean("finish", false) ?: true
            val taskId = data?.optString("task_id")
            if (!finish && !taskId.isNullOrBlank()) {
                pollTask(taskId)
            }
        }

    /** 刷新会话：不带 __puus 请求 config，服务端会下发新的 __puus。 */
    suspend fun refreshSession() {
        withContext(Dispatchers.IO) {
            val stripped = cookie.split(';')
                .map { it.trim() }
                .filterNot { it.startsWith("__puus=") }
                .joinToString("; ")
            request(
                url = "$QUARK_API_BASE/config?pr=ucpro&fr=pc",
                method = "GET",
                body = null,
                referer = QUARK_PAN_REFERER,
                cookie = stripped,
            )
        }
    }

    private suspend fun pollTask(taskId: String) {
        repeat(30) {
            delay(1000)
            val (json) = request(
                url = "$QUARK_API_BASE/task?pr=ucpro&fr=pc&task_id=${urlEncode(taskId)}",
                method = "GET",
                body = null,
                referer = QUARK_PAN_REFERER,
                cookie = cookie,
            )
            val body = runCatching { JSONObject(json) }.getOrNull()
            val data = body?.optJSONObject("data")
            val finish = data?.optBoolean("finish", false) ?: false
            if (finish) return
        }
    }

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)

    private suspend fun request(
        url: String,
        method: String,
        body: String?,
        referer: String?,
        cookie: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("User-Agent", QUARK_PAN_UA)
            referer?.let { connection.setRequestProperty("Referer", it) }
            if (cookie != null && cookie.isNotBlank()) {
                connection.setRequestProperty("Cookie", cookie)
            }
            extraHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == 401 || code == 403) {
                error(quarkErrorMessage(code, connection.responseMessage ?: ""))
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && text.isBlank()) {
                error(quarkErrorMessage(code, "HTTP $code"))
            }
            val setCookies = connection.headerFields
                ?.get("Set-Cookie")
                .orEmpty()
                .filter { it.isNotBlank() }
            mergeSetCookies(setCookies)
            text to setCookies
        } catch (error: IOException) {
            Log.w(TAG, "request failed: $method $url", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /** 把响应 Set-Cookie 合并进会话（仅保留 quark.cn 相关，避免污染）。 */
    private fun mergeSetCookies(setCookies: List<String>) {
        cookie = mergeQuarkCookies(cookie, setCookies)
    }
}
