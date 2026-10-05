package com.example.local_music_player

import org.json.JSONObject

/**
 * 在线曲目（源驱动）：由 MusicFree 插件的搜索/钻取结果归一而来，与本地 NativeTrack
 * 同构地进队列、收藏、历史与下载。[raw] 携带的插件字段经 [pluginItem] 回传给插件解析。
 */
data class OnlineTrack(
    val pluginId: String,
    val platform: String,
    val sourceName: String,
    val platformId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val durationMs: Long? = null,
) {
    val key: String get() = listOf(pluginId, platform, platformId).joinToString(":")

    /** 回传插件的 item（MusicFree 协议 IMusicItem 同形字段）。 */
    fun pluginItem(): Map<String, Any?> = buildMap {
        put("id", platformId)
        put("title", title)
        artist.takeIf { it.isNotBlank() }?.let { put("artist", it) }
        album.takeIf { it.isNotBlank() }?.let { put("album", it) }
        artworkUrl?.let { put("artwork", it) }
        durationMs?.let { put("duration", it / 1000.0) }
        put("platform", platform)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("pluginId", pluginId)
        put("platform", platform)
        put("sourceName", sourceName)
        put("platformId", platformId)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("artworkUrl", artworkUrl ?: JSONObject.NULL)
        put("durationMs", durationMs ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(value: JSONObject): OnlineTrack? {
            val pluginId = value.optString("pluginId")
            val platformId = value.optString("platformId")
            val title = value.optString("title")
            if (pluginId.isBlank() || platformId.isBlank() || title.isBlank()) return null
            val platform = value.optString("platform", pluginId)
            return OnlineTrack(
                pluginId = pluginId,
                platform = platform,
                sourceName = value.optString("sourceName", platform),
                platformId = platformId,
                title = title,
                artist = value.optString("artist", ""),
                album = value.optString("album", ""),
                artworkUrl = value.optString("artworkUrl", "").takeIf { it.isNotBlank() },
                durationMs = value.optLong("durationMs", -1L).takeIf { it > 0 },
            )
        }

        fun fromStreamTrack(track: StreamTrack): OnlineTrack = OnlineTrack(
            pluginId = track.pluginId,
            platform = track.platform,
            sourceName = track.sourceName,
            platformId = track.key,
            title = track.name,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artwork,
            durationMs = track.durationMs.takeIf { it > 0 },
        )
    }
}

/** Returns the playable online tracks when a playlist contains only online song references. */
internal fun onlineTracksForFavoriteFolder(folder: FavoriteFolder): List<OnlineTrack>? {
    if (folder.items.isEmpty() || folder.items.any { it.source != "online" }) return null
    return folder.items.mapNotNull(MediaReference::onlineTrack)
        .takeIf { tracks ->
            tracks.size == folder.items.size && tracks.distinctBy(OnlineTrack::key).size == tracks.size
        }
}

/** 在线专辑/歌单/榜单条目的展示模型（源驱动钻取共用）。 */
data class OnlineCollection(
    val pluginId: String,
    val sourceName: String,
    val kind: StreamCollectionKind,
    val collectionId: String,
    val name: String,
    val artist: String,
    val artworkUrl: String?,
    val description: String?,
    val worksNum: String?,
) {
    /** LazyColumn/Grid 的稳定 key：kind + 源 + 平台内 id。 */
    val key: String get() = listOf(kind.name, pluginId, collectionId).joinToString(":")
}
