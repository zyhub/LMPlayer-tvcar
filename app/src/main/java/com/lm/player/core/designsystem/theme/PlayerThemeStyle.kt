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
        displayName = "经典黑胶",
        description = "左半屏旋转黑胶与控制器 + 右半屏全高实时滚动歌词"
    ),
    NETEASE_TV_COVER(
        id = "netease_tv_cover",
        displayName = "全屏封面",
        description = "左半边歌曲封面图 + 右半边阴影与7列滚动歌词"
    ),
    IPOD_RETRO(
        id = "ipod_retro",
        displayName = "怀旧专辑 (iPod)",
        description = "经典 3D Cover Flow 滚动卡片流，歌词在卡片下方展示"
    ),
    KARAOKE_COVER(
        id = "karaoke_cover",
        displayName = "画卷逐字",
        description = "大尺寸圆角精选封面 + 逐字流光卡拉OK动感歌词"
    );

    companion object {
        fun fromId(id: String): PlayerThemeStyle {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MODERN
        }
    }
}
