package com.lm.player.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.lm.player.core.designsystem.theme.rememberThrottledFrameClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * 跳动音波（2026-11-13 性能优化第 4 项）：挂在全进程共享的 30 帧时钟上，只在绘制阶段读时钟。
 *
 * 以前列表「正在播放」与播客单集的音波各自是几条 60 帧的无限动画，而且每帧改的是条形的**高度**
 * （要重新测量、排版）：只要列表里看得到正在播放的歌，整窗就按 60 次/秒重绘。
 * 现在与黑胶旋转、逐字歌词同一帧失效，整窗仍是 30 次/秒，且只重画这一小块，不重新排版。
 *
 * @param bars 每条的 (最低比例, 最高比例, 往返周期毫秒, 相位)
 * @param animate false 时画静止的条形、不订阅时钟
 */
@Composable
fun ClockWaveBars(
    animate: Boolean,
    color: Color,
    barWidth: Dp,
    gap: Dp,
    bars: List<WaveBarSpec>,
    staticHeights: List<Float>,
    modifier: Modifier = Modifier
) {
    val clock = rememberThrottledFrameClock(animate)
    Canvas(modifier = modifier) {
        val t = if (animate) clock.value else 0L
        val w = barWidth.toPx()
        val g = gap.toPx()
        val radius = CornerRadius(w / 2f, w / 2f)
        val total = bars.size * w + (bars.size - 1).coerceAtLeast(0) * g
        var x = (size.width - total) / 2f
        bars.forEachIndexed { i, spec ->
            val ratio = if (animate) {
                val s = 0.5f + 0.5f * sin(2.0 * PI * t / spec.periodMs + spec.phase).toFloat()
                spec.low + (spec.high - spec.low) * s
            } else staticHeights.getOrElse(i) { 0.4f }
            val h = size.height * ratio.coerceIn(0.15f, 1f)
            drawRoundRect(color, topLeft = Offset(x, size.height - h), size = Size(w, h), cornerRadius = radius)
            x += w + g
        }
    }
}

class WaveBarSpec(val low: Float, val high: Float, val periodMs: Float, val phase: Double)
