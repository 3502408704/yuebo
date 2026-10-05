package com.example.local_music_player

/** 单源搜索的落定形态（测活与搜索会话共用）。 */
sealed interface StreamSourceSearchResult {
    data object Idle : StreamSourceSearchResult
    data object Loading : StreamSourceSearchResult
    data class Success(val keys: List<String>) : StreamSourceSearchResult
    data object Empty : StreamSourceSearchResult
    data class Failed(val message: String) : StreamSourceSearchResult
    data object TimedOut : StreamSourceSearchResult
}

/** 音源测活状态：搜索 + 取流两条链路的可用性，供源管理页展示。 */
data class StreamSourceTestStatus(
    val search: StreamSourceSearchResult,
    val resourceAvailable: Boolean?,
) {
    fun message(): String = when (search) {
        is StreamSourceSearchResult.Success -> when (resourceAvailable) {
            true -> "搜索可用，播放资源可用"
            false -> "搜索可用，播放资源不可用"
            null -> "搜索可用"
        }
        StreamSourceSearchResult.Empty -> "搜索无结果"
        StreamSourceSearchResult.TimedOut -> "搜索超时"
        is StreamSourceSearchResult.Failed -> "搜索失败：${search.message}"
        StreamSourceSearchResult.Loading -> "正在测试音源"
        StreamSourceSearchResult.Idle -> "未测试"
    }
}

/** 一次多源搜索会话：按源记录落定结果，[prioritySourceId] 是用户选中的站点。 */
data class StreamSearchSession(
    val query: String,
    val prioritySourceId: String?,
    val sourceResults: Map<String, StreamSourceSearchResult> = emptyMap(),
) {
    fun withSource(sourceId: String, result: StreamSourceSearchResult): StreamSearchSession =
        copy(sourceResults = sourceResults + (sourceId to result))

    fun resultFor(sourceId: String): StreamSourceSearchResult =
        sourceResults[sourceId] ?: StreamSourceSearchResult.Idle

    companion object {
        fun new(query: String, prioritySourceId: String?): StreamSearchSession =
            StreamSearchSession(query, prioritySourceId)
    }
}
