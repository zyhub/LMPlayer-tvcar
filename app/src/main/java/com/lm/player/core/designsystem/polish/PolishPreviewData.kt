package com.lm.player.core.designsystem.polish

import androidx.compose.ui.graphics.Color

/**
 * TV 界面美化（2026-11-08）预览模拟数据
 * 提供暖色、冷色、近黑、浅色 4 组 CoverPalette，以及不同长度的测试歌名。
 */
object PolishPreviewData {

    /** 冷海蓝调（对标实机邓紫棋《喜欢你》等青蓝海天封面） */
    val coolOceanPalette = CoverPalette(
        dominant = Color(0xFF1B3B6F),
        vibrant = Color(0xFF2C85D8),
        muted = Color(0xFF12233C),
        isDark = true
    )

    /** 暖琥珀金调（对标小阿枫《爱江山更爱美人》、国风民谣等暖调封面） */
    val warmAmberPalette = CoverPalette(
        dominant = Color(0xFF5A3515),
        vibrant = Color(0xFFE58B24),
        muted = Color(0xFF2E1A0B),
        isDark = true
    )

    /** 近黑幽紫调（对标周杰伦《夜曲》、暗色金属等深邃封面） */
    val nearBlackPalette = CoverPalette(
        dominant = Color(0xFF1D1429),
        vibrant = Color(0xFF8B5CF6),
        muted = Color(0xFF110B1A),
        isDark = true
    )

    /** 浅色温润明亮调（浅色主题下的封面取色） */
    val lightPearlPalette = CoverPalette(
        dominant = Color(0xFFD6DFEF),
        vibrant = Color(0xFF3B67B2),
        muted = Color(0xFFE8EEF8),
        isDark = false
    )

    /** 超长标题（需要跑马灯滚动的样本） */
    const val SAMPLE_LONG_TITLE = "爱江山更爱美人 - 小阿枫 (2026 车载无损重置特别版) · 柠檬超清音源"

    /** 正常短标题（无需滚动的样本） */
    const val SAMPLE_SHORT_TITLE = "喜欢你 - G.E.M.邓紫棋"

    /** 样本副标题 */
    const val SAMPLE_SUBTITLE = "小阿枫 · 经典老歌大放送 (极高 320K)"
}
