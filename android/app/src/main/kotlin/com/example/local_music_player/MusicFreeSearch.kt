package com.example.local_music_player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 多源并发搜索的薄封装：单源超时/失败都收敛为会话结果，不拖垮其余源。 */
object MusicFreeSearch {
    suspend fun searchSource(
        manager: MusicFreePluginManager,
        query: String,
        source: MusicFreeSource,
    ): Pair<StreamSourceSearchResult, List<StreamTrack>> = withContext(Dispatchers.IO) {
        try {
            val tracks = withTimeout(8_000) { manager.getPlugin(source).search(query, 1).items }
            (if (tracks.isEmpty()) StreamSourceSearchResult.Empty
            else StreamSourceSearchResult.Success(tracks.map { it.key })) to tracks
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            StreamSourceSearchResult.TimedOut to emptyList()
        } catch (error: Throwable) {
            StreamSourceSearchResult.Failed(error.message ?: "搜索失败") to emptyList()
        }
    }
}
