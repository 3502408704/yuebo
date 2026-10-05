package com.example.local_music_player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** MusicFree 音源：一个源对应一个插件 JS（本地文件或远程 URL 导入）。 */
data class MusicFreeSource(
    val id: String,
    val name: String,
    val url: String = "",
    val version: String = "",
    val enabled: Boolean = true,
    val localFile: String? = null, // filesDir/musicfree/plugins/ 下的插件文件名
)

/** Toggle only changes availability; the installed source record remains intact until removal. */
internal fun setMusicFreeSourceEnabled(
    sources: List<MusicFreeSource>,
    id: String,
    enabled: Boolean,
): List<MusicFreeSource> = sources.map { source ->
    if (source.id == id) source.copy(enabled = enabled) else source
}

/** 源列表与插件文件的持久化（SharedPreferences JSON 数组 + filesDir 插件文件）。 */
class MusicFreeSourceStore(private val context: Context) {

    private val preferences = context.getSharedPreferences("musicfree_sources", Context.MODE_PRIVATE)

    /** 读取源列表；无数据或损坏时返回空列表（兼容旧数据的 protocol/isBuiltin 字段：忽略）。 */
    fun load(): List<MusicFreeSource> = runCatching {
        val values = JSONArray(preferences.getString("sources", "[]"))
        List(values.length()) { index -> values.getJSONObject(index) }.mapNotNull { value ->
            val id = value.optString("id")
            val name = value.optString("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            if (value.optBoolean("isBuiltin", false)) return@mapNotNull null
            MusicFreeSource(
                id = id,
                name = name,
                url = value.optString("url", ""),
                version = value.optString("version", ""),
                enabled = value.optBoolean("enabled", true),
                localFile = if (value.has("localFile") && !value.isNull("localFile")) value.getString("localFile") else null,
            )
        }
    }.getOrDefault(emptyList())

    fun save(sources: List<MusicFreeSource>) {
        val values = JSONArray().apply {
            sources.forEach { source ->
                put(JSONObject().apply {
                    put("id", source.id)
                    put("name", source.name)
                    put("url", source.url)
                    put("version", source.version)
                    put("enabled", source.enabled)
                    put("localFile", source.localFile ?: JSONObject.NULL)
                })
            }
        }
        preferences.edit().putString("sources", values.toString()).apply()
    }

    /** 由 URL 生成稳定源 id：SHA-256 前 8 字节的 hex 字符串。 */
    fun sourceIdFromUrl(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

    /** 解析订阅 JSON（{plugins:[{name,url,version}]}）；非订阅格式返回空列表，重复 url 去重。 */
    fun parseSubscription(json: String): List<MusicFreeSource> = runCatching {
        val root = JSONObject(json)
        val plugins = root.optJSONArray("plugins") ?: return@runCatching emptyList()
        val seen = LinkedHashSet<String>()
        val sources = mutableListOf<MusicFreeSource>()
        for (index in 0 until plugins.length()) {
            val plugin = plugins.optJSONObject(index) ?: continue
            val name = plugin.optString("name").ifBlank { continue }
            val url = plugin.optString("url").ifBlank { continue }
            if (!seen.add(url)) continue
            sources.add(
                MusicFreeSource(
                    id = sourceIdFromUrl(url),
                    name = name,
                    url = url,
                    version = plugin.optString("version", ""),
                ),
            )
        }
        sources
    }.getOrDefault(emptyList())

    /** 下载文本内容（跟随重定向、gzip、UTF-8）；失败抛 IOException 中文消息。 */
    fun fetchText(url: String): String {
        JsNetworkBridge.validatePluginUrl(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val status = connection.responseCode
            if (status >= 400) {
                throw IOException("下载失败：HTTP $status")
            }
            val stream = if (connection.getHeaderField("Content-Encoding")?.equals("gzip", true) == true) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun pluginsDir(): File = File(context.filesDir, "musicfree/plugins")

    /** 写插件 JS 到 filesDir/musicfree/plugins/<id>.js（目录不存在则创建）。 */
    fun writePluginFile(source: MusicFreeSource, js: String) {
        val dir = pluginsDir()
        if (!dir.exists()) dir.mkdirs()
        File(dir, source.localFile ?: "${source.id}.js").writeText(js, Charsets.UTF_8)
    }

    /** 读插件 JS；文件不存在返回 null。 */
    fun readPluginFile(source: MusicFreeSource): String? {
        val file = File(pluginsDir(), source.localFile ?: "${source.id}.js")
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    /** 删除插件 JS 文件。 */
    fun deletePluginFile(source: MusicFreeSource) {
        File(pluginsDir(), source.localFile ?: "${source.id}.js").delete()
    }
}
