package com.lm.player.core.designsystem.polish

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.theme.LMPlayerTheme

/**
 * 首次加载骨架占位组件（替代传统菊花转圈）。
 *
 * 性能红线：
 * 1. 严格无状态，不可被遥控器获焦；
 * 2. 所有骨架块共用同一个动画时钟（[rememberInfiniteTransition]）；
 * 3. 当 [SkeletonSpec.shimmer] == false（流畅模式）时，彻底不启动任何动画时钟，纯静态绘制；
 * 4. 动画值 [State.value] 仅在 [drawBehind] 绘制阶段读取，零组合重组。
 */
@Composable
fun SkeletonPlaceholder(
    spec: SkeletonSpec,
    modifier: Modifier = Modifier
) {
    // 基础灰块色与扫光高光色（自适应深色 / 浅色主题）
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val baseColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
    }
    val highlightColor = if (isDark) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.White.copy(alpha = 0.85f)
    }

    // 性能保证：shimmer = false 时绝对不创建 rememberInfiniteTransition
    val shimmerProgress: State<Float>? = if (spec.shimmer) {
        val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_progress"
        )
    } else {
        null
    }

    Box(
        modifier = modifier,
        propagateMinConstraints = true
    ) {
        when (spec.kind) {
            SkeletonKind.CARD_ROW -> {
                SkeletonCardRow(
                    count = spec.itemCount.coerceAtLeast(1),
                    baseColor = baseColor,
                    highlightColor = highlightColor,
                    shimmerProgress = shimmerProgress
                )
            }
            SkeletonKind.SONG_LIST -> {
                SkeletonSongList(
                    count = spec.itemCount.coerceAtLeast(1),
                    baseColor = baseColor,
                    highlightColor = highlightColor,
                    shimmerProgress = shimmerProgress
                )
            }
            SkeletonKind.ALBUM_GRID -> {
                SkeletonAlbumGrid(
                    count = spec.itemCount.coerceAtLeast(1),
                    baseColor = baseColor,
                    highlightColor = highlightColor,
                    shimmerProgress = shimmerProgress
                )
            }
            SkeletonKind.HERO_BANNER -> {
                SkeletonHeroBanner(
                    count = spec.itemCount.coerceAtLeast(1),
                    baseColor = baseColor,
                    highlightColor = highlightColor,
                    shimmerProgress = shimmerProgress
                )
            }
        }
    }
}

/** 单个骨架块绘制 Modifier（绘制期读值，零重组） */
private fun Modifier.skeletonBlock(
    shape: Shape,
    baseColor: Color,
    highlightColor: Color,
    shimmerProgress: State<Float>?
): Modifier = this
    .clip(shape)
    .then(
        if (shimmerProgress == null) {
            Modifier.background(baseColor)
        } else {
            Modifier.drawBehind {
                val p = shimmerProgress.value
                val w = size.width
                val h = size.height
                // 高光从左向右扫过，覆盖完整对角线
                val startX = (p * 3f - 1f) * w
                val endX = startX + w
                val brush = Brush.linearGradient(
                    colors = listOf(baseColor, highlightColor, baseColor),
                    start = Offset(startX, 0f),
                    end = Offset(endX, h)
                )
                drawRect(brush = brush)
            }
        }
    )

/** ① CARD_ROW：横向卡片行（对标发现页推荐歌单 / 榜单，140dp 方形卡片 + 14dp 圆角 + 16dp 间距） */
@Composable
private fun SkeletonCardRow(
    count: Int,
    baseColor: Color,
    highlightColor: Color,
    shimmerProgress: State<Float>?,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(count) {
            Column(
                modifier = Modifier.width(140.dp)
            ) {
                // 卡片封面占位
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(14.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 标题占位行 1
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .height(14.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(4.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 副标题占位行 2
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(12.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(4.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
            }
        }
    }
}

/** ② SONG_LIST：竖向歌曲列表（对标资料库歌曲列表，行高约 60dp，44dp 封面 + 双行文本 + 右侧角标） */
@Composable
private fun SkeletonSongList(
    count: Int,
    baseColor: Color,
    highlightColor: Color,
    shimmerProgress: State<Float>?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 封面小图
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(8.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                // 歌曲名与歌手双行
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.42f)
                            .height(14.dp)
                            .skeletonBlock(
                                shape = RoundedCornerShape(4.dp),
                                baseColor = baseColor,
                                highlightColor = highlightColor,
                                shimmerProgress = shimmerProgress
                            )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .height(11.dp)
                            .skeletonBlock(
                                shape = RoundedCornerShape(4.dp),
                                baseColor = baseColor,
                                highlightColor = highlightColor,
                                shimmerProgress = shimmerProgress
                            )
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                // 右侧操作图标占位
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .skeletonBlock(
                            shape = CircleShape,
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
            }
        }
    }
}

/** ③ ALBUM_GRID：歌单 / 专辑网格（对标全部歌单网格，140×160dp 竖向卡片，14dp 圆角） */
@Composable
private fun SkeletonAlbumGrid(
    count: Int,
    baseColor: Color,
    highlightColor: Color,
    shimmerProgress: State<Float>?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(count) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(14.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(14.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(4.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.50f)
                        .height(11.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(4.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
            }
        }
    }
}

/** ④ HERO_BANNER：页面顶部大横幅（对标发现页「今日为你推荐」「私人漫游」大卡，高约 140dp，16dp 圆角） */
@Composable
private fun SkeletonHeroBanner(
    count: Int,
    baseColor: Color,
    highlightColor: Color,
    shimmerProgress: State<Float>?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 封面方块
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .skeletonBlock(
                            shape = RoundedCornerShape(12.dp),
                            baseColor = baseColor,
                            highlightColor = highlightColor,
                            shimmerProgress = shimmerProgress
                        )
                )
                Spacer(modifier = Modifier.width(16.dp))
                // 文本与按钮条
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(18.dp)
                            .skeletonBlock(
                                shape = RoundedCornerShape(6.dp),
                                baseColor = baseColor,
                                highlightColor = highlightColor,
                                shimmerProgress = shimmerProgress
                            )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(13.dp)
                            .skeletonBlock(
                                shape = RoundedCornerShape(4.dp),
                                baseColor = baseColor,
                                highlightColor = highlightColor,
                                shimmerProgress = shimmerProgress
                            )
                    )
                }
            }
        }
    }
}

// =========================================================================
// 预览区域：覆盖 TV 深色、TV 浅色、车机超宽、内容区尺寸
// =========================================================================

@Preview(name = "TV 深色 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewSkeletonTvDark() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SkeletonPlaceholder(spec = SkeletonSpec(SkeletonKind.HERO_BANNER, itemCount = 2, shimmer = true))
                SkeletonPlaceholder(spec = SkeletonSpec(SkeletonKind.CARD_ROW, itemCount = 5, shimmer = true))
            }
        }
    }
}

@Preview(name = "TV 浅色 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewSkeletonTvLight() {
    LMPlayerTheme(themeMode = AppThemeMode.LIGHT) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SkeletonPlaceholder(spec = SkeletonSpec(SkeletonKind.HERO_BANNER, itemCount = 2, shimmer = true))
                SkeletonPlaceholder(spec = SkeletonSpec(SkeletonKind.CARD_ROW, itemCount = 5, shimmer = true))
            }
        }
    }
}

@Preview(name = "车机超宽深色 - 1280x480", widthDp = 1280, heightDp = 480)
@Composable
private fun PreviewSkeletonCarUltrawide() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SkeletonPlaceholder(
                    spec = SkeletonSpec(SkeletonKind.SONG_LIST, itemCount = 5, shimmer = false),
                    modifier = Modifier.weight(1f)
                )
                SkeletonPlaceholder(
                    spec = SkeletonSpec(SkeletonKind.ALBUM_GRID, itemCount = 3, shimmer = false),
                    modifier = Modifier.weight(1.2f)
                )
            }
        }
    }
}

@Preview(name = "内容区尺寸 - 680x460", widthDp = 680, heightDp = 460)
@Composable
private fun PreviewSkeletonContentArea() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SkeletonPlaceholder(spec = SkeletonSpec(SkeletonKind.CARD_ROW, itemCount = 4, shimmer = true))
                SkeletonPlaceholder(spec = SkeletonSpec(SkeletonKind.SONG_LIST, itemCount = 3, shimmer = true))
            }
        }
    }
}
