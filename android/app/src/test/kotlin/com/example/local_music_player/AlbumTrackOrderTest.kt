package com.example.local_music_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 专辑曲目/剧集分集的显示顺序（正序/倒序一键切换）。
 *
 * 背景：部分上游（用户实测**酷狗听书**）返回的清单是倒序的，用户想从第 1 集顺读时无从下手，
 * 故在专辑/选集列表加了一键切换。这里钉住纯函数语义——`false` 原样（上游顺序）、`true` 反转，
 * 且**不按标题数字重排**（分集标题格式各站不一，猜数字比不猜更危险）。
 */
class AlbumTrackOrderTest {

    private val episodes = listOf("第1集", "第2集", "第3集")

    @Test
    fun ascendingKeepsUpstreamOrder() {
        assertEquals(episodes, orderedAlbumTracks(episodes, descending = false))
    }

    @Test
    fun descendingReversesList() {
        assertEquals(listOf("第3集", "第2集", "第1集"), orderedAlbumTracks(episodes, descending = true))
    }

    @Test
    fun reversingTwiceIsIdentity() {
        val once = orderedAlbumTracks(episodes, descending = true)!!
        assertEquals(episodes, orderedAlbumTracks(once, descending = true))
    }

    @Test
    fun nullStaysNullBeforeLoad() {
        // 尚未加载：不得当成空列表，否则列表会先闪一下「没有内容」再出数据
        assertNull(orderedAlbumTracks<String>(null, descending = false))
        assertNull(orderedAlbumTracks<String>(null, descending = true))
    }

    @Test
    fun upstreamAlreadyDescendingCanBeFlippedToAscending() {
        // 酷狗听书实测就是这种倒序返回：切换后应变成从第 1 集开始
        val upstream = listOf("第3集", "第2集", "第1集")
        assertEquals(episodes, orderedAlbumTracks(upstream, descending = true))
        assertEquals(upstream, orderedAlbumTracks(upstream, descending = false))
    }

    @Test
    fun singleAndEmptyListsAreSafe() {
        assertEquals(emptyList<String>(), orderedAlbumTracks(emptyList<String>(), descending = true))
        assertEquals(listOf("第1集"), orderedAlbumTracks(listOf("第1集"), descending = true))
    }

    @Test
    fun doesNotReorderByTitleNumber() {
        // 标题格式各站不一（「第01集」「61-81集完结」），只做原样/反转，绝不按数字猜。
        val odd = listOf("61-81集完结", "第01集", "预告")
        assertEquals(odd, orderedAlbumTracks(odd, descending = false))
        assertEquals(odd.reversed(), orderedAlbumTracks(odd, descending = true))
    }
}
