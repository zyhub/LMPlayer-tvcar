package com.lm.player.core.designsystem.polish

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.theme.LMPlayerTheme

/**
 * TV / 车机端焦点跑马灯滚动文本组件。
 *
 * 性能与交互设计：
 * 1. 当 [focused] == false 时：保持标准单行静态文本，末尾省略号（[TextOverflow.Ellipsis]），完全不附加任何动画，零开销；
 * 2. 当 [focused] == true 且文字超出容器宽度时：静止停顿约 1.0 秒（避免遥控器快速换焦时眩晕），随后平滑横向循环滚动；
 * 3. 滚动状态下左右两端施加 14dp 柔和淡出渐变遮罩，彻底消除文字进出的生硬硬切感；
 * 4. 放得下时无论是否获焦，永远保持静止。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvMarqueeText(
    text: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    // 记录文字在当前宽度约束下是否产生截断溢出
    var didOverflow by remember(text) { mutableStateOf(false) }

    // 只有同时满足「获得焦点」且「文字确实放不下」两个条件，才激活滚动
    val shouldScroll = focused && didOverflow

    val marqueeModifier = if (shouldScroll) {
        Modifier
            // 离屏缓冲层：用于实现左右边缘平滑渐隐遮罩
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                val fadeWidth = 14.dp.toPx()
                if (size.width > fadeWidth * 2) {
                    // 左侧淡入遮罩
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = fadeWidth
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    // 右侧淡出遮罩
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = size.width - fadeWidth,
                            endX = size.width
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                animationMode = MarqueeAnimationMode.Immediately,
                delayMillis = 1000,
                initialDelayMillis = 1000,
                spacing = MarqueeSpacing(32.dp),
                velocity = 35.dp
            )
    } else {
        Modifier
    }

    Text(
        text = text,
        modifier = modifier.then(marqueeModifier),
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = if (shouldScroll) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { layoutResult ->
            // Claude 接线修正：滚动中 basicMarquee 以无限宽度排版文字，此时永远「不溢出」；
            // 若照常回写会关掉滚动 → 恢复正常宽度又溢出 → 再打开滚动，每帧来回切换。
            // 所以只在未滚动（正常宽度、省略号排版）时判断是否放得下。
            if (!shouldScroll) {
                val overflow = layoutResult.hasVisualOverflow || layoutResult.didOverflowWidth
                if (didOverflow != overflow) {
                    didOverflow = overflow
                }
            }
        }
    )
}

// =========================================================================
// 预览区域：覆盖 TV 深色、TV 浅色、车机超宽、内容区尺寸
// =========================================================================

@Preview(name = "TV 深色 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewTvMarqueeDark() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "【TV 遥控器焦点滚动标题演示 (深色)】",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // 卡片 1：获焦超长标题（停顿 1s 后平滑循环滚动）
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "获焦超长标题 (focused=true):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TvMarqueeText(
                            text = PolishPreviewData.SAMPLE_LONG_TITLE,
                            focused = true,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // 卡片 2：未获焦超长标题（单行普通省略号，0 开销）
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "未获焦超长标题 (focused=false):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TvMarqueeText(
                            text = PolishPreviewData.SAMPLE_LONG_TITLE,
                            focused = false,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // 卡片 3：获焦但放得下的短标题（永远静止）
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "放得下的短标题 (静止):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TvMarqueeText(
                            text = PolishPreviewData.SAMPLE_SHORT_TITLE,
                            focused = true,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "TV 浅色 - 960x540", widthDp = 960, heightDp = 540)
@Composable
private fun PreviewTvMarqueeLight() {
    LMPlayerTheme(themeMode = AppThemeMode.LIGHT) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "【TV 遥控器焦点滚动标题演示 (浅色)】",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        modifier = Modifier
                            .width(240.dp)
                            .padding(12.dp)
                    ) {
                        TvMarqueeText(
                            text = PolishPreviewData.SAMPLE_LONG_TITLE,
                            focused = true,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Column(
                        modifier = Modifier
                            .width(240.dp)
                            .padding(12.dp)
                    ) {
                        TvMarqueeText(
                            text = PolishPreviewData.SAMPLE_LONG_TITLE,
                            focused = false,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "车机超宽深色 - 1280x480", widthDp = 1280, heightDp = 480)
@Composable
private fun PreviewTvMarqueeCarUltrawide() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.width(360.dp)) {
                    Text("车机模式长标题滚动测试：", style = MaterialTheme.typography.labelSmall)
                    TvMarqueeText(
                        text = PolishPreviewData.SAMPLE_LONG_TITLE,
                        focused = true,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
        }
    }
}

@Preview(name = "内容区尺寸 - 680x460", widthDp = 680, heightDp = 460)
@Composable
private fun PreviewTvMarqueeContentArea() {
    LMPlayerTheme(themeMode = AppThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TvMarqueeText(
                    text = PolishPreviewData.SAMPLE_LONG_TITLE,
                    focused = true,
                    style = MaterialTheme.typography.titleMedium
                )
                TvMarqueeText(
                    text = PolishPreviewData.SAMPLE_SHORT_TITLE,
                    focused = false,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
