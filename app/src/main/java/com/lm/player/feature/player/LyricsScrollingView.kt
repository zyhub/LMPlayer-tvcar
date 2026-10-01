package com.lm.player.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LyricTheme
import com.lm.player.core.model.LyricLine

@Composable
fun LyricsScrollingView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    onSeekToLyric: (Long) -> Unit,
    fontSizeSp: Float = 22f,
    lyricsOffsetMs: Long = 0L,
    lyricTheme: LyricTheme = LyricTheme.APPLE_MUSIC,
    onFontSizeChange: (Float) -> Unit = {},
    onOffsetChange: (Long) -> Unit = {},
    onThemeChange: (LyricTheme) -> Unit = {},
    showAdjustButton: Boolean = true,
    visibleLineCount: Int? = null,
    rowHeightMultiplier: Float? = null,
    textAlign: TextAlign = TextAlign.Start,
    overrideActiveColor: Color? = null,
    overrideInactiveColor: Color? = null,
    /** 上下边缘渐隐遮罩底色：歌词卡片改为灰白卡片时需同步覆盖，否则渐隐会露出深色底 */
    overrideFadeColor: Color? = null,
    horizontalPaddingDp: Int = 16,
    adjustButtonFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val cleanLyrics = remember(lyrics) {
        lyrics.filter { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }
    }

    var showAdjustDialog by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val activeColor = overrideActiveColor ?: if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight
    val inactiveColor = overrideInactiveColor ?: if (isDark) lyricTheme.inactiveColorDark else lyricTheme.inactiveColorLight

    val listState = rememberLazyListState()

    // 结合用户快慢偏置时间计算有效播放时间戳
    val effectivePositionMs = (currentPositionMs + lyricsOffsetMs).coerceAtLeast(0L)

    // 计算当前处于哪一行歌词 (采用二分查找匹配对应行)
    val activeIndex = remember(cleanLyrics, effectivePositionMs) {
        if (cleanLyrics.isEmpty()) {
            0
        } else {
            var low = 0
            var high = cleanLyrics.lastIndex
            var best = 0
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (cleanLyrics[mid].timestampMs <= effectivePositionMs) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            best
        }
    }

    // 伴随播放进度自动丝滑滚动至当前歌词
    LaunchedEffect(activeIndex, cleanLyrics.size, visibleLineCount) {
        if (cleanLyrics.isNotEmpty() && activeIndex in cleanLyrics.indices && !listState.isScrollInProgress) {
            if (visibleLineCount != null) {
                listState.animateScrollToItem(
                    index = activeIndex,
                    scrollOffset = 0
                )
            } else {
                listState.animateScrollToItem(
                    index = (activeIndex - 1).coerceAtLeast(0),
                    scrollOffset = 0
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (cleanLyrics.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (textAlign == TextAlign.Start && visibleLineCount == 7) Alignment.CenterStart else Alignment.Center
            ) {
                Column(
                    horizontalAlignment = if (textAlign == TextAlign.Start && visibleLineCount == 7) Alignment.Start else Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "♪ 纯音乐 / 暂无歌词 ♪",
                        fontSize = (fontSizeSp * 0.85f).coerceIn(12f, 20f).sp,
                        fontWeight = FontWeight.Bold,
                        color = activeColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "享受纯净无损音乐时光",
                        fontSize = (fontSizeSp * 0.65f).coerceIn(10f, 14f).sp,
                        color = inactiveColor
                    )
                }
            }
        } else if (visibleLineCount != null && visibleLineCount > 0) {
            // 固定行数滚动窗口（如左侧常驻黑胶卡片 5 列歌词、全屏封面主题右侧 7 列滚动歌词）
            // 采用与手机端一致的上下边缘渐隐淡入淡出遮罩 + 逐行平滑淡入淡出与微缩放过渡，增大字体并缩小行间距
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPaddingDp.dp),
                contentAlignment = Alignment.Center
            ) {
                val lines = visibleLineCount.coerceAtLeast(3)
                val halfPaddingLines = (lines - 1) / 2f
                val maxCompactRowHeight = when {
                    rowHeightMultiplier != null -> (fontSizeSp * rowHeightMultiplier).dp.coerceIn(22.dp, 68.dp)
                    lines <= 5 -> (fontSizeSp * 1.62f).dp.coerceIn(22.dp, 29.dp)
                    else -> (fontSizeSp * 1.82f).dp.coerceIn(32.dp, 46.dp)
                }
                val rowHeight = (maxHeight / lines.toFloat()).coerceIn(20.dp, maxCompactRowHeight)
                val viewportHeight = rowHeight * lines.toFloat()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(viewportHeight),
                    contentPadding = PaddingValues(vertical = rowHeight * halfPaddingLines),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(
                        items = cleanLyrics,
                        key = { index, item -> "${item.timestampMs}_$index" },
                        contentType = { _, _ -> "lyric_item" }
                    ) { index, item ->
                        val distance = kotlin.math.abs(index - activeIndex)
                        val isActive = distance == 0
                        val targetAlpha = when (distance) {
                            0 -> 1.0f
                            1 -> 0.65f
                            2 -> 0.36f
                            3 -> 0.18f
                            else -> 0.08f
                        }
                        val animatedAlpha by animateFloatAsState(
                            targetValue = targetAlpha,
                            animationSpec = tween(
                                durationMillis = 380,
                                easing = FastOutSlowInEasing
                            ),
                            label = "lyric_line_alpha"
                        )
                        val targetScale = if (isActive) 1.0f else if (distance == 1) 0.93f else 0.88f
                        val animatedScale by animateFloatAsState(
                            targetValue = targetScale,
                            animationSpec = tween(
                                durationMillis = 380,
                                easing = FastOutSlowInEasing
                            ),
                            label = "lyric_line_scale"
                        )
                        val baseColor = if (isActive) activeColor else inactiveColor.copy(alpha = 1f)
                        val animatedColor by animateColorAsState(
                            targetValue = baseColor,
                            animationSpec = tween(durationMillis = 340),
                            label = "lyric_line_color"
                        )
                        val currentSize = when (distance) {
                            0 -> fontSizeSp.sp
                            1 -> (fontSizeSp * 0.86f).sp
                            else -> (fontSizeSp * 0.80f).sp
                        }
                        val fontWeight = when (distance) {
                            0 -> FontWeight.ExtraBold
                            1 -> FontWeight.SemiBold
                            else -> FontWeight.Medium
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .graphicsLayer {
                                    alpha = animatedAlpha
                                    scaleX = animatedScale
                                    scaleY = animatedScale
                                    transformOrigin = when (textAlign) {
                                        TextAlign.Center -> TransformOrigin.Center
                                        TextAlign.End -> TransformOrigin(1f, 0.5f)
                                        else -> TransformOrigin(0f, 0.5f)
                                    }
                                }
                                .pointerInput(item.timestampMs, lyricsOffsetMs) {
                                    detectTapGestures {
                                        onSeekToLyric((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L))
                                    }
                                },
                            contentAlignment = when (textAlign) {
                                TextAlign.Center -> Alignment.Center
                                TextAlign.End -> Alignment.CenterEnd
                                else -> Alignment.CenterStart
                            }
                        ) {
                            Text(
                                text = item.text,
                                textAlign = textAlign,
                                style = TextStyle(
                                    fontSize = currentSize,
                                    fontWeight = fontWeight,
                                    color = animatedColor,
                                    lineHeight = (currentSize.value * 1.16f).sp
                                ),
                                maxLines = if (lines <= 5) 1 else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        } else {
            // 经典黑胶模式还原上一版的全高实时滚动歌词
            val activeFontSize = remember(fontSizeSp) { fontSizeSp.sp }
            val inactiveFontSize = remember(fontSizeSp) { (fontSizeSp * 0.74f).sp }
            val activeLineHeight = remember(fontSizeSp) { (fontSizeSp * 1.38f).sp }
            val inactiveLineHeight = remember(fontSizeSp) { (fontSizeSp * 0.74f * 1.38f).sp }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPaddingDp.dp),
                contentPadding = PaddingValues(vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.95f).dp)
            ) {
                itemsIndexed(
                    items = cleanLyrics,
                    key = { index, item -> "${item.timestampMs}_$index" },
                    contentType = { _, _ -> "lyric_item" }
                ) { index, item ->
                    val isActive = index == activeIndex
                    val isNearActive = kotlin.math.abs(index - activeIndex) <= 1
                    val textColor = if (isNearActive) {
                        val animated by animateColorAsState(
                            targetValue = if (isActive) activeColor else inactiveColor,
                            animationSpec = tween(durationMillis = 260),
                            label = "lyric_color"
                        )
                        animated
                    } else {
                        inactiveColor
                    }
                    val currentSize = if (isActive) activeFontSize else inactiveFontSize
                    val currentLineHeight = if (isActive) activeLineHeight else inactiveLineHeight
                    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                    Text(
                        text = item.text,
                        textAlign = textAlign,
                        style = TextStyle(
                            fontSize = currentSize,
                            fontWeight = fontWeight,
                            color = textColor,
                            lineHeight = currentLineHeight
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeekToLyric((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L)) }
                    )
                }
            }

            // 顶部与底部边缘丝滑渐隐淡出遮罩（采用零显存、零渲染层开销的纯净渐变遮罩，杜绝部分 TV/车机硬件不支持 DstIn 引发闪退）
            val fadeBackdropColor = overrideFadeColor ?: if (isDark) Color(0xFF0D1013) else Color(0xFFF7F7FA)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(fadeBackdropColor, Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, fadeBackdropColor)
                        )
                    )
            )
        }

        // 右上角歌词悬浮调节按键 (Aa ⏱) - 支持遥控器方向键选中并绑定确定性上下左右导航
        if (showAdjustButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) Color(0xFF1E1E24).copy(alpha = 0.85f) else Color(0xFFE5E5EA).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .then(if (adjustButtonFocusRequester != null) Modifier.focusRequester(adjustButtonFocusRequester) else Modifier)
                        .tvFocusable(
                            shape = RoundedCornerShape(12.dp),
                            focusedScale = 1.06f,
                            onClick = { showAdjustDialog = true }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "歌词微调",
                            tint = activeColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "歌词调节",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeColor
                        )
                    }
                }

                // 原位浮层音频共享展出卡片
                LyricsAdjustDropdownMenu(
                    expanded = showAdjustDialog,
                    onDismissRequest = {
                        showAdjustDialog = false
                        try { adjustButtonFocusRequester?.requestFocus() } catch (_: Exception) {}
                    },
                    fontSizeSp = fontSizeSp,
                    lyricsOffsetMs = lyricsOffsetMs,
                    lyricTheme = lyricTheme,
                    onFontSizeChange = onFontSizeChange,
                    onOffsetChange = onOffsetChange,
                    onThemeChange = onThemeChange
                )
            }
        }
    }
}

/**
 * 原位音频共享展出方式的歌词微调菜单 (字号大小、时间偏置、6 套主题预设)
 */
@Composable
fun LyricsAdjustDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    lyricTheme: LyricTheme,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    onThemeChange: (LyricTheme) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                kotlinx.coroutines.delay(60L)
                firstFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 270.dp, max = 320.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = AppleRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "歌词调节与外观",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                }
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(26.dp)
                        .tvButtonFocusable(shape = RoundedCornerShape(13.dp))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = secondaryText, modifier = Modifier.size(14.dp))
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 1. 字号大小调节 (移除无光标环的原生 Slider，改为 A- / A+ 遥控器按钮与可视进度条)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字体大小", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
                Text("${fontSizeSp.toInt()} sp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleRed)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onFontSizeChange((fontSizeSp - 2f).coerceAtLeast(16f)) },
                    modifier = Modifier
                        .size(width = 44.dp, height = 28.dp)
                        .focusRequester(firstFocusRequester)
                        .tvButtonFocusable(shape = RoundedCornerShape(8.dp)),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("A-", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryText)
                }
                val fontRatio = ((fontSizeSp - 16f) / 20f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(primaryText.copy(alpha = 0.16f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fontRatio)
                            .clip(RoundedCornerShape(3.dp))
                            .background(AppleRed)
                    )
                }
                OutlinedButton(
                    onClick = { onFontSizeChange((fontSizeSp + 2f).coerceAtMost(36f)) },
                    modifier = Modifier
                        .size(width = 44.dp, height = 28.dp)
                        .tvButtonFocusable(shape = RoundedCornerShape(8.dp)),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("A+", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryText)
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // 2. 歌词时间偏置快慢
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("时间同步", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
                val offsetSec = lyricsOffsetMs / 1000.0
                Text(
                    text = if (lyricsOffsetMs > 0) "+%.1fs (提前)".format(offsetSec) else if (lyricsOffsetMs < 0) "%.1fs (延后)".format(offsetSec) else "0.0s (标准)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (lyricsOffsetMs != 0L) AppleRed else secondaryText
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { onOffsetChange((lyricsOffsetMs - 500L).coerceAtLeast(-5000L)) },
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .tvButtonFocusable(shape = RoundedCornerShape(6.dp)),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("延后 0.5s", fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { onOffsetChange(0L) },
                    modifier = Modifier
                        .weight(0.7f)
                        .height(28.dp)
                        .tvButtonFocusable(shape = RoundedCornerShape(6.dp)),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("归零", fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { onOffsetChange((lyricsOffsetMs + 500L).coerceAtMost(5000L)) },
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .tvButtonFocusable(shape = RoundedCornerShape(6.dp)),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("提前 0.5s", fontSize = 10.sp)
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // 3. 歌词主题颜色
            Text("主题配色", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LyricTheme.entries.forEach { theme ->
                    val isSelected = theme == lyricTheme
                    val themeColor = if (isDark) theme.activeColorDark else theme.activeColorLight
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                        border = if (isSelected) BorderStroke(1.5.dp, AppleRed) else null,
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .tvFocusable(
                                shape = RoundedCornerShape(8.dp),
                                focusedScale = 1.08f,
                                onClick = { onThemeChange(theme) }
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(themeColor)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${lyricTheme.displayName} • ${lyricTheme.description}",
                fontSize = 10.sp,
                color = secondaryText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 现代毛玻璃歌词微调控制浮窗 (字号大小、时间快慢偏置、6 套主题预设)
 */
@Composable
fun LyricsAdjustDialog(
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    lyricTheme: LyricTheme,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    onThemeChange: (LyricTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF1E1E24).copy(alpha = 0.96f) else Color(0xFFFFFFFF).copy(alpha = 0.96f),
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
            shadowElevation = 24.dp,
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.92f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppleRed.copy(alpha = 0.15f),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("歌词效果与微调", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = primaryText)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = secondaryText, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 1. 歌词字号大小调节 (16sp ~ 36sp)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("歌词字号大小", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                        Text("${fontSizeSp.toInt()} sp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onFontSizeChange((fontSizeSp - 2f).coerceAtLeast(16f)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("A-", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryText)
                        }
                        Slider(
                            value = fontSizeSp,
                            onValueChange = { onFontSizeChange(it) },
                            valueRange = 16f..36f,
                            steps = 9,
                            colors = SliderDefaults.colors(thumbColor = AppleRed, activeTrackColor = AppleRed),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onFontSizeChange((fontSizeSp + 2f).coerceAtMost(36f)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryText)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. 歌词时间快慢偏置 (-5.0s ~ +5.0s)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("歌词时间快慢偏置", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                        val offsetSec = lyricsOffsetMs / 1000.0
                        Text(
                            text = if (lyricsOffsetMs > 0) "+%.1fs (提前)".format(offsetSec) else if (lyricsOffsetMs < 0) "%.1fs (延后)".format(offsetSec) else "0.0s (标准)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (lyricsOffsetMs != 0L) AppleRed else secondaryText
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onOffsetChange((lyricsOffsetMs - 500L).coerceAtLeast(-5000L)) },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("延后 0.5s", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onOffsetChange(0L) },
                            modifier = Modifier.weight(0.8f).height(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("归零", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onOffsetChange((lyricsOffsetMs + 500L).coerceAtMost(5000L)) },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("提前 0.5s", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. 歌词主题颜色快速切换
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("歌词主题配色", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LyricTheme.entries.forEach { theme ->
                            val isSelected = theme == lyricTheme
                            val themeColor = if (isDark) theme.activeColorDark else theme.activeColorLight
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                                border = if (isSelected) BorderStroke(1.5.dp, AppleRed) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onThemeChange(theme) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(themeColor)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "当前主题：${lyricTheme.displayName} • ${lyricTheme.description}",
                        fontSize = 11.sp,
                        color = secondaryText,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text("完成", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
