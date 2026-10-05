package com.example.local_music_player

/**
 * 内容归属域：把播放历史与收藏夹按来源拆开存放的依据。
 *
 * 最终版三域：Music（在线·插件音源）、Local（本地媒体库）、Cloud（云盘）。
 * 旧版本的模块域（AudioBook/Video/Tv/Radio）随对应模块移除；历史里残留的旧域值
 * 由读取方容忍降级（firstOrNull 按 name 匹配，缺省 Music）。
 */
enum class MediaScope(val label: String) {
    Music("在线"),
    Local("媒体库"),
    Cloud("云盘"),
}

/** 单曲历史/收藏条目 → 内容域。网盘音频归云盘；在线曲目归在线域；其余（含本地视频）归媒体库。 */
internal fun mediaScopeForSingle(reference: MediaReference): MediaScope = when {
    reference.source == "baidupan" || reference.source == "quarkpan" -> MediaScope.Cloud
    reference.onlineTrack != null -> MediaScope.Music
    else -> MediaScope.Local
}

/** 专辑历史条目 → 内容域：在线曲目归在线域，其余归媒体库。 */
internal fun mediaScopeForAlbum(entry: AlbumPlaybackHistoryEntry): MediaScope = when {
    entry.trackReference.onlineTrack != null -> MediaScope.Music
    else -> MediaScope.Local
}
