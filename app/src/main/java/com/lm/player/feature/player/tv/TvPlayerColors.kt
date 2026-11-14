package com.lm.player.feature.player.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 播放页配色（2026-11-13）：跟随软件的浅色 / 深色主题（设置 → 主题外观，含「跟随系统」）。
 * 黑胶唱片本身、印在唱片上的文字、全屏封面上的压暗层在两套配色里保持不变（它们画在唱片 / 封面上，不是页面底色）。
 */
@Immutable
data class TvPlayerColors(
    val isDark: Boolean,
    /** 页面最底层颜色 */
    val background: Color,
    /** 盖在封面模糊底图上的遮罩颜色（深色压暗 / 浅色提亮） */
    val scrim: Color,
    /** 遮罩不透明度倍率：浅色用白色遮罩时需要更厚一点才能托住深色文字 */
    val scrimBoost: Float,
    val text: Color,
    val textSecondary: Color,
    /** 胶囊 / 面板 / 抽屉底色与描边 */
    val panel: Color,
    val panelBorder: Color,
    /** 面板里的格子 / 未选中项底色 */
    val subtleFill: Color,
    /** 强调色：选中、激活、进度 */
    val accent: Color,
    /** 播放键底色与图标色 */
    val playFill: Color,
    val onPlayFill: Color,
    /** 进度条底轨 */
    val track: Color
) {
    companion object {
        val Dark = TvPlayerColors(
            isDark = true,
            background = Color.Black,
            scrim = Color.Black,
            scrimBoost = 1f,
            text = Color.White,
            textSecondary = Color.White.copy(alpha = 0.65f),
            panel = Color(0xE6141418),
            panelBorder = Color(0x26FFFFFF),
            subtleFill = Color.White.copy(alpha = 0.06f),
            accent = Color(0xFFFFC947),
            playFill = Color(0xFFFFC947),
            onPlayFill = Color(0xFF3A2A00),
            track = Color.White.copy(alpha = 0.22f)
        )
        val Light = TvPlayerColors(
            isDark = false,
            background = Color(0xFFF2F2F5),
            scrim = Color.White,
            scrimBoost = 1.1f,
            text = Color(0xFF1C1C1E),
            textSecondary = Color(0xFF1C1C1E).copy(alpha = 0.6f),
            panel = Color(0xEBFFFFFF),
            panelBorder = Color(0x1A000000),
            subtleFill = Color.Black.copy(alpha = 0.05f),
            accent = Color(0xFFC98A00),
            playFill = Color(0xFFFFC947),
            onPlayFill = Color(0xFF3A2A00),
            track = Color.Black.copy(alpha = 0.14f)
        )
    }
}

val LocalTvPlayerColors = staticCompositionLocalOf { TvPlayerColors.Dark }

/** 按当前软件主题（MaterialTheme 的背景亮度）选择播放页配色 */
@Composable
fun currentTvPlayerColors(): TvPlayerColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) TvPlayerColors.Dark else TvPlayerColors.Light
