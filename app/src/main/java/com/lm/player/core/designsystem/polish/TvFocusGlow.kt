package com.lm.player.core.designsystem.polish

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.theme.LMPlayerTheme

/**
 * 全应用统一的遥控器焦点光晕绘制修饰符。
 *
 * 核心设计与性能红线：
 * 1. 纯绘制层修饰符：不改变布局尺寸、不处理键盘事件、不处理缩放（缩放由宿主或 tvFocusable 负责）；
 * 2. 严禁使用 [Modifier.blur]、[RenderEffect] 或大面积 shadow，纯靠 [drawBehind] 梯度多层描边实现柔和发光，低端电视零掉帧；
 * 3. 动画值仅在 [drawBehind] 的绘制回调中读取，动画过渡期间宿主组件零重组；
 * 4. 内嵌纯白超细高光衬底 + 暖金核心环（[spec.borderColor]），在深色、浅色以及纯红/复杂封面上均 100% 清晰浮现；
 * 5. 失焦平滑淡出时长 ≤ 150ms。
 */
fun Modifier.tvFocusGlow(
    focused: Boolean,
    shape: Shape,
    spec: TvFocusStyleSpec = TvFocusStyleSpec()
): Modifier = composed {
    // 失焦淡出动画（≤ 150ms）
    val glowProgress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (focused) 130 else 110,
            easing = LinearOutSlowInEasing
        ),
        label = "tv_focus_glow_fade"
    )

    this.drawBehind {
        val progress = glowProgress.value
        if (progress <= 0.005f) return@drawBehind

        val outline = shape.createOutline(size, layoutDirection, this)
        val borderWidthPx = spec.borderWidth.toPx()
        val glowRadiusPx = spec.glowRadius.toPx()
        val baseAlpha = spec.glowAlpha * progress

        // 1. 外发光晕层：采用 3 级同心描边扩散，无模糊滤镜开销，效果柔和自然
        if (glowRadiusPx > 0.5f && baseAlpha > 0.005f) {
            // 最外层扩散柔光
            val stroke3 = borderWidthPx + glowRadiusPx * 2.0f
            drawOutline(
                outline = outline,
                color = spec.borderColor.copy(alpha = (baseAlpha * 0.16f).coerceIn(0f, 1f)),
                style = Stroke(width = stroke3)
            )
            // 中层环境晕光
            val stroke2 = borderWidthPx + glowRadiusPx * 1.1f
            drawOutline(
                outline = outline,
                color = spec.borderColor.copy(alpha = (baseAlpha * 0.38f).coerceIn(0f, 1f)),
                style = Stroke(width = stroke2)
            )
            // 近缘强光层
            val stroke1 = borderWidthPx + glowRadiusPx * 0.5f
            drawOutline(
                outline = outline,
                color = spec.borderColor.copy(alpha = (baseAlpha * 0.72f).coerceIn(0f, 1f)),
                style = Stroke(width = stroke1)
            )
        }

        // 2. 双层高对比度焦点环：
        // 纯白超细高光衬边（确保在纯红背景、暗色背景、浅色背景上立体浮现）
        val whiteStrokePx = borderWidthPx + 1.5.dp.toPx()
        drawOutline(
            outline = outline,
            color = Color.White.copy(alpha = (0.90f * progress).coerceIn(0f, 1f)),
            style = Stroke(width = whiteStrokePx)
        )

        // 核心环（暖金色，高辨识度）
        drawOutline(
            outline = outline,
            color = spec.borderColor.copy(alpha = progress),
            style = Stroke(width = borderWidthPx)
        )
    }
}

// =========================================================================
// 预览区域：覆盖 TV 深色、TV 浅色、车机超宽、内容区尺寸
// =========================================================================

@Preview(name = "TV 深色 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewTvFocusGlowDark() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "【统一遥控器焦点光晕演示 (深色背景)】",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // 状态 1：获焦卡片（双层暖金光晕）
                    Box(
                        modifier = Modifier
                            .size(160.dp, 100.dp)
                            .graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }
                            .tvFocusGlow(focused = true, shape = RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("获焦状态 (Focused)", style = MaterialTheme.typography.titleMedium)
                    }

                    // 状态 2：未获焦卡片
                    Box(
                        modifier = Modifier
                            .size(160.dp, 100.dp)
                            .tvFocusGlow(focused = false, shape = RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未获焦状态", style = MaterialTheme.typography.titleMedium)
                    }

                    // 状态 3：圆形按钮获焦（验证 Shape 适配）
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f }
                            .tvFocusGlow(focused = true, shape = CircleShape)
                            .clip(CircleShape)
                            .background(AppleRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▶", color = Color.White)
                    }
                }
            }
        }
    }
}

@Preview(name = "TV 浅色 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewTvFocusGlowLight() {
    LMPlayerTheme(themeMode = AppThemeMode.LIGHT) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "【统一遥控器焦点光晕演示 (浅色背景)】",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(
                        modifier = Modifier
                            .size(160.dp, 100.dp)
                            .graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }
                            .tvFocusGlow(focused = true, shape = RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("浅色获焦光晕", style = MaterialTheme.typography.titleMedium)
                    }

                    Box(
                        modifier = Modifier
                            .size(160.dp, 100.dp)
                            .tvFocusGlow(focused = false, shape = RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("浅色未获焦", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Preview(name = "车机超宽深色 - 1280x480", widthDp = 1280, heightDp = 480)
@Composable
private fun PreviewTvFocusGlowCarUltrawide() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(220.dp, 120.dp)
                        .graphicsLayer { scaleX = 1.05f; scaleY = 1.05f }
                        .tvFocusGlow(focused = true, shape = RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("超宽屏获焦卡片", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Preview(name = "内容区尺寸 - 680x460", widthDp = 680, heightDp = 460)
@Composable
private fun PreviewTvFocusGlowContentArea() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp, 90.dp)
                        .tvFocusGlow(focused = true, shape = RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("内容区获焦展示", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
