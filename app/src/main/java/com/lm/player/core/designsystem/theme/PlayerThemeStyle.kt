package com.lm.player.core.designsystem.theme

/**
 * 播放界面主题风格预设
 */
enum class PlayerThemeStyle(
    val id: String,
    val displayName: String,
    val description: String
) {
    MODERN(
        id = "modern",
        displayName = "黑胶唱片",
        description = "左侧旋转黑胶与唱臂，右侧歌曲信息与滚动歌词"
    ),
    NETEASE_TV_COVER(
        id = "netease_tv_cover",
        displayName = "全屏封面",
        description = "封面铺满全屏，左侧显示当前句与下一句歌词"
    ),
    IPOD_RETRO(
        id = "ipod_retro",
        displayName = "专辑卡片",
        description = "3D 专辑唱片架（手机端横屏同款），右侧滚动歌词"
    ),
    KARAOKE_COVER(
        id = "karaoke_cover",
        displayName = "大字歌词",
        description = "不放封面，超大字号歌词与翻译，右上角时钟"
    );

    companion object {
        fun fromId(id: String): PlayerThemeStyle {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MODERN
        }
    }
}
