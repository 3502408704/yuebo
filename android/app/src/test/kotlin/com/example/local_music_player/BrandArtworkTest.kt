package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 品牌化无封面占位：渐变确定性、分布与散列健壮性。 */
class BrandArtworkTest {

    @Test
    fun gradientIndexIsDeterministicAndBounded() {
        val seeds = listOf("三体有声剧", "庆余年 第二季", "斗罗大陆", "甄嬛传", "Live Aid 1985", "bilibili MV 合集", "")
        seeds.forEach { seed ->
            val index = brandGradientIndexFor(seed)
            assertEquals(index, brandGradientIndexFor(seed), "同一种子必须得到同一组渐变")
            assertTrue(index in GlassTheme.BrandGradients.indices, "渐变下标必须落在色板内")
        }
    }

    @Test
    fun gradientIndexSpreadsAcrossPalette() {
        val seeds = (1..60).map { "剧集标题样本 $it" }
        val distinct = seeds.map(::brandGradientIndexFor).toSet()
        assertTrue(distinct.size >= 3, "不同标题应分散到多组渐变，实际只用 ${distinct.size} 组")
    }
}
