package com.example.local_music_player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 无封面占位的品牌 glyph 类别：按内容类型选月牙系图标。 */
enum class BrandArtworkKind { Music, Video, AudioBook, Generic }

/**
 * 标题哈希 → 品牌渐变组下标。纯函数：同一标题永远得到同一组渐变，
 * 让无封面资源呈现稳定、多样又统一的品牌化外观。
 */
internal fun brandGradientIndexFor(seed: String): Int {
    var hash = 0
    for (ch in seed.trim()) hash = hash * 31 + ch.code
    val size = GlassTheme.BrandGradients.size
    return ((hash % size) + size) % size
}

private fun brandGlyphRes(kind: BrandArtworkKind): Int = when (kind) {
    BrandArtworkKind.Music -> R.drawable.brand_glyph_music
    BrandArtworkKind.Video -> R.drawable.brand_glyph_video
    BrandArtworkKind.AudioBook -> R.drawable.brand_glyph_audiobook
    BrandArtworkKind.Generic -> R.drawable.brand_moon_wave
}

/**
 * 品牌化无封面占位卡：标题哈希渐变背景 + 居中月牙系 glyph。
 * 纯装饰（contentDescription 由外层语义承担），不重复展示标题首字——
 * 首字会先于标题被读出（如「综」+「综艺…」听成「综综艺」），也是视觉冗余。
 */
@Composable
fun BrandArtworkPlaceholder(
    seed: String,
    kind: BrandArtworkKind,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
) {
    val gradientIndex = remember(seed) { brandGradientIndexFor(seed) }
    val (gradientStart, gradientEnd) = GlassTheme.BrandGradients[gradientIndex]
    val glyphRes = remember(kind) { brandGlyphRes(kind) }
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(listOf(gradientStart, gradientEnd))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(glyphRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.padding(6.dp),
        )
    }
}
