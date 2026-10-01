package com.lm.player.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局字体与界面缩放规格 (LMPlayer TV车机版 10-Foot 远距离与车载大屏优化)
 *
 * 采用 50% ~ 150% 百分比连续调节 (默认 100%)，单一进度条即可适配手机屏、车载中控、
 * 显示器与超大客厅电视等各类分辨率与观看距离，无需再在若干固定预设之间二选一。
 */
const val MinUiScalePercent = 50
const val MaxUiScalePercent = 150
const val DefaultUiScalePercent = 100

/** 遥控器 ◀ ▶ 方向键单次调节步长 */
const val UiScaleStepPercent = 5

/** 将百分比归一为缩放倍数 (100% = 1.0x) */
fun uiScalePercentToFactor(percent: Int): Float =
    percent.coerceIn(MinUiScalePercent, MaxUiScalePercent) / 100f

/**
 * 全局统一 7 级语义化字体规格与尺寸设计系统 (LMPlayer TV Typography & Dimension Spec)
 */
data class AppDimensions(
    val fontScale: Float = 1f,

    // 7 级标准语义字号
    val pageTitleSize: TextUnit = 28.sp,
    val sectionTitleSize: TextUnit = 20.sp,
    val cardHeaderSize: TextUnit = 18.sp,
    val itemTitleSize: TextUnit = 16.5.sp,
    val bodySize: TextUnit = 15.sp,
    val captionSize: TextUnit = 13.sp,
    val badgeSize: TextUnit = 11.5.sp,

    // 兼容别名映射
    val titleLargeSize: TextUnit = pageTitleSize,
    val titleMediumSize: TextUnit = sectionTitleSize,
    val bodyLargeSize: TextUnit = itemTitleSize,
    val bodyMediumSize: TextUnit = bodySize,

    val coverLargeSize: Dp = 260.dp,
    val coverMediumSize: Dp = 110.dp,
    val coverSmallSize: Dp = 60.dp,

    val touchTargetLarge: Dp = 72.dp,
    val touchTargetMedium: Dp = 54.dp,
    val touchTargetSmall: Dp = 42.dp,

    val iconLargeSize: Dp = 38.dp,
    val iconMediumSize: Dp = 26.dp,
    val iconSmallSize: Dp = 18.dp,

    val paddingLarge: Dp = 24.dp,
    val paddingMedium: Dp = 16.dp,
    val paddingSmall: Dp = 10.dp
)

val LocalAppDimensions = compositionLocalOf { AppDimensions() }

/**
 * 按当前全局缩放倍数换算容器尺寸 (卡片宽度、窗口高度、封面与黑胶直径等)。
 *
 * 卡片内的 7 级语义字号已随进度条联动，容器尺寸必须同步缩放：否则调到 150% 时文字
 * 变大而卡片窗口尺寸不动，内容会溢出、截断并互相遮挡，看起来就像「缩放没生效」。
 * 网格类布局请同时把 [androidx.compose.foundation.lazy.grid.GridCells.Adaptive] 的
 * minSize 走这里换算，列数会随缩放自动减少，卡片才能真正变大。
 */
fun AppDimensions.scale(base: Dp): Dp = base * fontScale

/**
 * 以 100% 为基准按比例推导整套字体与视距尺寸。
 * 字号、封面、触控目标与图标全部线性跟随缩放倍数 (0.5x ~ 1.5x)，
 * 内边距保持恒定，避免大比例下固定内边距挤压内容导致换行与截断。
 */
private fun buildScaledDimensions(scale: Float): AppDimensions {
    val pageTitle = 26.sp * scale
    val sectionTitle = 18.sp * scale
    val cardHeader = 16.sp * scale
    val itemTitle = 15.sp * scale
    val body = 13.5.sp * scale
    val caption = 12.sp * scale
    val badge = 10.5.sp * scale
    return AppDimensions(
        fontScale = scale,
        pageTitleSize = pageTitle,
        sectionTitleSize = sectionTitle,
        cardHeaderSize = cardHeader,
        itemTitleSize = itemTitle,
        bodySize = body,
        captionSize = caption,
        badgeSize = badge,
        titleLargeSize = pageTitle,
        titleMediumSize = sectionTitle,
        bodyLargeSize = itemTitle,
        bodyMediumSize = body,
        coverLargeSize = 260.dp * scale,
        coverMediumSize = 110.dp * scale,
        coverSmallSize = 60.dp * scale,
        touchTargetLarge = 72.dp * scale,
        touchTargetMedium = 54.dp * scale,
        touchTargetSmall = 42.dp * scale,
        iconLargeSize = 38.dp * scale,
        iconMediumSize = 26.dp * scale,
        iconSmallSize = 18.dp * scale,
        paddingLarge = 24.dp,
        paddingMedium = 16.dp,
        paddingSmall = 10.dp
    )
}

@Composable
fun rememberAppDimensions(scalePercent: Int = DefaultUiScalePercent): AppDimensions {
    return buildScaledDimensions(scale = uiScalePercentToFactor(scalePercent))
}

/**
 * 全局字体缩放钳制 (防止不同分辨率/系统字号下文字换行与遮挡)
 *
 * 电视与车机的系统字体缩放设置差异极大，某些机型会把 fontScale 拉到 1.3~2.0，
 * 再叠加本应用自身的「全局字体与视距规格」缩放后，固定宽高的卡片与标签必然出现换行、截断与互相遮挡。
 * 这里将系统 fontScale 归一到 [MinFontScale, MaxFontScale] 区间：
 * 应用内的字号体系已由 50%~150% 调节进度条独立控制，外部系统设置只允许在可控范围内微调。
 */
const val MinFontScale = 0.85f
const val MaxFontScale = 1.10f

@Composable
fun ClampedFontScaleProvider(
    minScale: Float = MinFontScale,
    maxScale: Float = MaxFontScale,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val clampedFontScale = density.fontScale.coerceIn(minScale, maxScale)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = clampedFontScale
        )
    ) {
        content()
    }
}
