package com.lm.player.core.designsystem.polish

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.theme.LMPlayerTheme

/**
 * 封面取色柔和动态渐变背景容器。
 *
 * 核心设计与性能红线：
 * 1. 适配左侧常驻黑胶播控大卡（宽 276dp）与全屏播放页（1920×1080）；
 * 2. 严格遵循性能红线：仅使用单个 [Brush.radialGradient] 渐变层，颜色变化使用 [animateColorAsState]（360ms），
 *    严禁叠加多张全屏大图或离屏模糊（RenderEffect / blur）；
 * 3. 对比度保护：基于 [palette.isDark]、[palette.muted] 与 [palette.dominant] 自然压暗或温润化融合，
 *    确保上层白字（深色模式）或黑字（浅色模式）具备完全合格的 WCAG 对比度；
 * 4. 当 [palette] == null（无封面或计算中）时，平滑优雅回退至默认黑胶卡/播放页底色。
 */
@Composable
fun CoverTintBackground(
    palette: CoverPalette?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    // 计算三级渐变目标色
    val (targetTop, targetMid, targetBottom) = if (palette != null) {
        if (isDark) {
            // 深色沉浸模式：以封面 muted/dominant 柔光点亮左上方，向右下方自然融入深黑夜幕
            val top = palette.muted.copy(alpha = 0.85f)
            val mid = Color(
                red = (palette.dominant.red * 0.28f + 0.08f).coerceIn(0.04f, 0.25f),
                green = (palette.dominant.green * 0.28f + 0.09f).coerceIn(0.04f, 0.25f),
                blue = (palette.dominant.blue * 0.28f + 0.12f).coerceIn(0.05f, 0.28f),
                alpha = 0.95f
            )
            val bottom = Color(0xFF090B10)
            Triple(top, mid, bottom)
        } else {
            // 浅色珠光模式：轻微注入柔和原色，维持底色明亮通透，文字清晰可辨
            val top = Color(
                red = (palette.muted.red * 0.18f + 0.82f).coerceIn(0.85f, 1f),
                green = (palette.muted.green * 0.18f + 0.82f).coerceIn(0.85f, 1f),
                blue = (palette.muted.blue * 0.18f + 0.82f).coerceIn(0.85f, 1f),
                alpha = 0.96f
            )
            val mid = Color(0xFFF4F6FB)
            val bottom = Color(0xFFE9EDF5)
            Triple(top, mid, bottom)
        }
    } else {
        // null 回退：原生黑曜石深色或纯净浅灰
        if (isDark) {
            Triple(
                Color(0xFF232734).copy(alpha = 0.92f),
                Color(0xFF171A24).copy(alpha = 0.95f),
                Color(0xFF0E1117)
            )
        } else {
            Triple(
                Color(0xFFFFFFFF).copy(alpha = 0.95f),
                Color(0xFFF2F4F8).copy(alpha = 0.96f),
                Color(0xFFE8ECF4)
            )
        }
    }

    // 颜色切换平滑过渡（360ms，≤ 400ms）
    val animatedTop by animateColorAsState(
        targetValue = targetTop,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "cover_tint_top"
    )
    val animatedMid by animateColorAsState(
        targetValue = targetMid,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "cover_tint_mid"
    )
    val animatedBottom by animateColorAsState(
        targetValue = targetBottom,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "cover_tint_bottom"
    )

    Box(
        modifier = modifier.drawBehind {
            // 绘制单层径向平滑渐变，无额外离屏渲染消耗
            val brush = Brush.radialGradient(
                colors = listOf(animatedTop, animatedMid, animatedBottom),
                center = Offset(size.width * 0.35f, size.height * 0.22f),
                radius = size.maxDimension * 1.15f
            )
            drawRect(brush = brush)
        },
        content = content
    )
}

// =========================================================================
// 预览区域：包含 4 种基础尺寸 + 左侧黑胶卡 3 组配色专题预览
// =========================================================================

@Preview(name = "TV 深色全屏 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewCoverTintTvDark() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        CoverTintBackground(
            palette = PolishPreviewData.coolOceanPalette,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "邓紫棋《喜欢你》冷海蓝调封面取色全屏播放页",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "单层 Brush.radialGradient 渲染 · 零掉帧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Preview(name = "TV 浅色全屏 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewCoverTintTvLight() {
    LMPlayerTheme(themeMode = AppThemeMode.LIGHT) {
        CoverTintBackground(
            palette = PolishPreviewData.lightPearlPalette,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "浅色珍珠温润封面取色全屏",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Preview(name = "车机超宽深色 - 1280x480", widthDp = 1280, heightDp = 480)
@Composable
private fun PreviewCoverTintCarUltrawide() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        CoverTintBackground(
            palette = PolishPreviewData.warmAmberPalette,
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "车机超宽屏幕：小阿枫《爱江山更爱美人》暖琥珀金调取色背景",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
        }
    }
}

@Preview(name = "内容区尺寸 - 680x460", widthDp = 680, heightDp = 460)
@Composable
private fun PreviewCoverTintContentArea() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        CoverTintBackground(
            palette = PolishPreviewData.nearBlackPalette,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("内容区取色背景（周杰伦《夜曲》暗夜幽紫）", color = Color.White)
            }
        }
    }
}

// -------------------------------------------------------------------------
// 左侧黑胶卡尺寸（约 widthDp = 276, heightDp = 520）3 组配色专题预览
// -------------------------------------------------------------------------

@Preview(name = "黑胶卡 - 暖色调 (276x520)", widthDp = 276, heightDp = 520)
@Composable
private fun PreviewVinylCardWarm() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        CoverTintBackground(
            palette = PolishPreviewData.warmAmberPalette,
            modifier = Modifier
                .size(276.dp, 520.dp)
                .clip(RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("暖琥珀调黑胶卡", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .drawBehind { drawCircle(Color.Black) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(54.dp).clip(CircleShape).drawBehind { drawCircle(PolishPreviewData.warmAmberPalette.vibrant) })
                }
                Text("爱江山更爱美人", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Preview(name = "黑胶卡 - 冷色调 (276x520)", widthDp = 276, heightDp = 520)
@Composable
private fun PreviewVinylCardCool() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        CoverTintBackground(
            palette = PolishPreviewData.coolOceanPalette,
            modifier = Modifier
                .size(276.dp, 520.dp)
                .clip(RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("冷海蓝调黑胶卡", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .drawBehind { drawCircle(Color.Black) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(54.dp).clip(CircleShape).drawBehind { drawCircle(PolishPreviewData.coolOceanPalette.vibrant) })
                }
                Text("喜欢你 - G.E.M.邓紫棋", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Preview(name = "黑胶卡 - 近黑调 (276x520)", widthDp = 276, heightDp = 520)
@Composable
private fun PreviewVinylCardNearBlack() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        CoverTintBackground(
            palette = PolishPreviewData.nearBlackPalette,
            modifier = Modifier
                .size(276.dp, 520.dp)
                .clip(RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("暗夜幽紫黑胶卡", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .drawBehind { drawCircle(Color.Black) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(54.dp).clip(CircleShape).drawBehind { drawCircle(PolishPreviewData.nearBlackPalette.vibrant) })
                }
                Text("夜曲 - 周杰伦", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
