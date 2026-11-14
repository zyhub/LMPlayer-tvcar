package com.lm.player.core.designsystem.polish

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * TV 界面美化（2026-11-08）数据约定 —— Claude 维护，Antigravity 只读。
 * 界面组件放在本目录（core/designsystem/polish/）的新文件里，全部无状态；
 * 取色、加载状态、是否流畅模式等由 Claude 计算后传入。仅 TV 端，手机端暂不同步。
 */

/**
 * 封面取色：Claude 在后台从当前歌曲封面的缩略图算出；没有封面或还没算出时传 null，界面用主题默认色。
 */
@Immutable
data class CoverPalette(
    /** 主色（封面里面积最大的颜色） */
    val dominant: Color,
    /** 鲜艳色：适合小面积强调（进度条、焦点光晕、播放按钮） */
    val vibrant: Color,
    /** 柔和色：适合大面积背景渐变 */
    val muted: Color,
    /** 主色是否偏暗：true 时前景文字用浅色，false 用深色 */
    val isDark: Boolean
)

/** 骨架占位的形态 */
enum class SkeletonKind {
    /** 横向一排方形卡片（发现页推荐歌单 / 榜单、资料库歌单 / 专辑行） */
    CARD_ROW,

    /** 竖向歌曲列表（封面小图 + 两行文字） */
    SONG_LIST,

    /** 专辑 / 歌单网格 */
    ALBUM_GRID,

    /** 页面顶部大横幅（新歌首发卡等） */
    HERO_BANNER
}

/** 首次加载时的骨架占位参数 */
@Immutable
data class SkeletonSpec(
    val kind: SkeletonKind,
    /** 显示几个占位块（行 / 卡片数） */
    val itemCount: Int,
    /** 是否播放微光扫过动画；流畅模式下 Claude 传 false，只显示静态灰块 */
    val shimmer: Boolean
)

/** 遥控器焦点外观规格：全应用统一，Claude 会把它接进现有 Modifier.tvFocusable */
@Immutable
data class TvFocusStyleSpec(
    val borderWidth: Dp = 2.5.dp,
    val borderColor: Color = Color(0xFFFFC947),
    /** 外发光半径；0.dp 表示不发光 */
    val glowRadius: Dp = 10.dp,
    /** 外发光不透明度 0..1 */
    val glowAlpha: Float = 0.35f,
    /** 获得焦点时的放大倍数（现有值 1.06） */
    val focusedScale: Float = 1.06f
)
