package com.example.local_music_player

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 玻璃拟态深色主题设计令牌。
 * 深黑灰底 + 低饱和暖橙强调色，统一圆角/间距/字重三级层级。
 * 所有核心模块（专辑详情、播放详情、桌面歌词）复用此令牌，保证风格统一。
 */
object GlassTheme {

    // —— 底色（深黑灰，低饱和）——
    val BgDeep = Color(0xFF0F0F12)          // 最深底
    val BgBase = Color(0xFF14141A)          // 主背景
    val BgSurface = Color(0xFF1A1A20)       // 卡片/面板底
    val BgElevated = Color(0xFF22222A)      // 浮起元素
    val GlassTint = Color(0x14FFFFFF)       // 玻璃蒙版（半透明白）

    // —— 强调色（低饱和暖橙）——
    val Accent = Color(0xFFFF9A62)          // 主强调
    val AccentDim = Color(0xB3FF9A62)       // 弱化强调
    val AccentGlow = Color(0x33FF9A62)      // 强调光晕

    // —— 文字（对比度达标：正文 ≥4.5:1）——
    val TextPrimary = Color(0xFFF2F2F5)     // 一级：标题/当前行
    val TextSecondary = Color(0xB3F2F2F5)   // 二级：歌手/说明（alpha 0.7）
    val TextTertiary = Color(0x8CF2F2F5)    // 三级：时长/弱化（alpha 0.55）
    val TextOnAccent = Color(0xFF1A1A20)    // 强调色上的文字

    // —— 圆角 ——
    val RadiusSmall = 6.dp
    val RadiusMedium = 8.dp
    val RadiusLarge = 8.dp
    val RadiusXLarge = 12.dp

    // —— 间距 ——
    val SpaceXS = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp
    val SpaceL = 24.dp
    val SpaceXL = 32.dp
    val SpaceXXL = 48.dp

    // —— 页面布局 ——
    val PageHorizontalPadding = 16.dp
    val PageVerticalPadding = 12.dp
    val SectionGap = 12.dp
    val GridGap = 12.dp
    val TopBarHeight = 64.dp
    val MinTouchTarget = 48.dp

    // —— 玻璃拟态阴影（柔化，无硬边）——
    val ElevationSmall = 4.dp
    val ElevationMedium = 12.dp
    val ElevationLarge = 24.dp

    // —— 动效令牌（时长毫秒 + M3 Expressive 强调曲线，全局过渡统一取用）——
    val MotionShort = 180
    val MotionMedium = 240
    val MotionLong = 320
    val EasingEmphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // —— 品牌渐变占位色板：无封面资源按标题哈希确定性取一组，
    //    中间调深色保证白色 glyph 可读（≥3:1），首组为品牌暖橙 ——
    val BrandGradients: List<Pair<Color, Color>> = listOf(
        Color(0xFFB85C32) to Color(0xFFFF9A62), // 落日橙（品牌）
        Color(0xFF5E4B8A) to Color(0xFF8E7CC3), // 暮山紫
        Color(0xFF2E6E6A) to Color(0xFF5FA8A0), // 青霭
        Color(0xFF33518C) to Color(0xFF6C8FC7), // 黛蓝
        Color(0xFF9C4A63) to Color(0xFFD07E96), // 玫瑰
        Color(0xFF4A4A55) to Color(0xFF737382), // 岩灰（兜底中性）
    )

    /** Material3 深色配色，供 MaterialTheme 使用。 */
    fun colorScheme() = darkColorScheme(
        primary = Accent,
        onPrimary = TextOnAccent,
        primaryContainer = AccentDim,
        onPrimaryContainer = TextPrimary,
        background = BgBase,
        onBackground = TextPrimary,
        surface = BgSurface,
        onSurface = TextPrimary,
        surfaceVariant = BgElevated,
        onSurfaceVariant = TextSecondary,
        secondary = AccentDim,
        onSecondary = TextOnAccent,
        error = Color(0xFFFF6B6B),
        onError = TextPrimary,
    )

    /** Material3 浅色配色：与深色玻璃主题同一套暖橙强调/圆角/字重体系，保证两模式风格统一。 */
    fun lightColorScheme() = lightColorScheme(
        primary = Accent,
        onPrimary = TextOnAccent,
        primaryContainer = Color(0xFFFFD9C4),
        onPrimaryContainer = Color(0xFF4A2010),
        background = Color(0xFFF4F5F7),
        onBackground = Color(0xFF1A1A20),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1A20),
        surfaceVariant = Color(0xFFE7E8EE),
        onSurfaceVariant = Color(0xFF55555E),
        secondary = Color(0xFFFFB28A),
        onSecondary = TextOnAccent,
        error = Color(0xFFD64545),
        onError = Color(0xFFFFFFFF),
    )

    /** 三级字重层级：标题(中粗)/正文(常规)/弱化(细)。颜色交由 colorScheme 控制，深浅主题均安全。 */
    fun typography() = Typography(
        // 一级：页面/专辑标题
        titleLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        // 专辑名（播放页大标题）
        headlineSmall = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
        ),
        // 二级：歌名/当前句
        titleMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        // 正文
        bodyLarge = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        ),
        bodyMedium = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        // 三级：歌手/时长/弱化
        labelMedium = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelSmall = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
    )

    /** Keep card corners compact to avoid creating a false visual hierarchy. */
    fun shapes() = Shapes(
        extraSmall = RoundedCornerShape(RadiusSmall),
        small = RoundedCornerShape(RadiusMedium),
        medium = RoundedCornerShape(RadiusMedium),
        large = RoundedCornerShape(RadiusMedium),
        extraLarge = RoundedCornerShape(RadiusXLarge),
    )
}

/** 在 Composable 内便捷取当前玻璃主题色。 */
@Composable
fun glassAccent() = MaterialTheme.colorScheme.primary

/**
 * 系统动画是否可用（无障碍/开发者选项关闭动画时为 false）。
 * 所有新增动效必须经此门控：false 时一律退化为直接切换，不阻塞 TalkBack。
 */
@Composable
fun rememberMotionEnabled(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

@Composable
fun glassTextPrimary() = GlassTheme.TextPrimary

@Composable
fun glassTextSecondary() = GlassTheme.TextSecondary

@Composable
fun glassTextTertiary() = GlassTheme.TextTertiary
