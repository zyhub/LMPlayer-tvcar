package com.lm.player.feature.player.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** 胶囊上的按钮焦点（宿主据此把焦点送到指定按钮） */
class CapsuleFocus {
    /** 第一个按钮：从唱片 / 封面 / 唱片架按「下」进入胶囊时落在这里 */
    val favorite = FocusRequester()
    val download = FocusRequester()
    val lyrics = FocusRequester()
    val queue = FocusRequester()
    val more = FocusRequester()
}

/** 数字等宽：时间跳动时不左右抖 */
private val TabularNums = TextStyle(fontFeatureSettings = "tnum")

/**
 * 三个主题共用的底部控制胶囊（2026-11-13 统一为专辑卡片的小胶囊样式）。
 *
 * - 上：进度条贴着胶囊上沿铺满，下面一行小字「当前时间 … 总时长」，两端与进度条对齐；
 * - 下：喜欢、下载（下载中显示进度圈与百分比）、播放模式 ｜ 词、[歌词展开 / 收起，只有专辑卡片有]、待播列表、更多；
 * - 上一首 / 下一首 / 播放暂停交给画面本身：唱片、封面、唱片架上左右滑动或遥控器左右键切歌，点一下或确定键播放暂停；
 * - 收起：一段时间没有操作后缩成「时间 ━━━ 总时长」的细进度胶囊，点一下 / 遥控器按下键展开。
 * - 遥控器焦点：按钮之间的左右、进度条与按钮之间的上下全部显式指定，不交给系统按几何位置去猜。
 */
@Composable
fun TvPlayerCapsule(
    expanded: Boolean,
    showLyricsPaneToggle: Boolean,
    onExpand: () -> Unit,
    songId: String,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    downloadTasks: StateFlow<List<DownloadTask>>?,
    isShuffle: Boolean,
    isRepeat: Boolean,
    progressMsProvider: () -> Long,
    durationMs: Long,
    lyricsOpen: Boolean,
    moreOpen: Boolean,
    queueOpen: Boolean,
    lyricsPaneOpen: Boolean,
    focus: CapsuleFocus,
    /** 进度条再往上：回到唱片 / 封面 / 唱片架 */
    stageFocus: FocusRequester,
    onSeekTo: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onToggleLyricsPane: () -> Unit,
    onLyricsAnchor: (Float) -> Unit,
    onMoreAnchor: (Float) -> Unit,
    onSeekFocusChange: (Boolean) -> Unit,
    downloadMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(260), initialScale = 0.9f)) togetherWith
                (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.9f)) using
                SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.82f, stiffness = 320f) }
        },
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier,
        label = "capsule_expand"
    ) { exp ->
        if (!exp) {
            MiniProgressPill(progressMsProvider, durationMs, onExpand)
        } else {
            UnifiedCapsule(
                showLyricsPaneToggle, songId, isFavorite, isDownloaded, downloadTasks, isShuffle, isRepeat,
                progressMsProvider, durationMs, lyricsOpen, moreOpen, queueOpen, lyricsPaneOpen, focus, stageFocus,
                onSeekTo, onCyclePlayMode, onToggleFavorite, onDownload, onLyrics, onQueue, onMore, onToggleLyricsPane,
                onLyricsAnchor, onMoreAnchor, onSeekFocusChange, downloadMenu
            )
        }
    }
}

private fun playModeOf(isShuffle: Boolean, isRepeat: Boolean, accent: Color, normal: Color): Triple<ImageVector, Color, String> = when {
    isShuffle -> Triple(Icons.Default.Shuffle, accent, "随机播放")
    isRepeat -> Triple(Icons.Default.RepeatOne, accent, "单曲循环")
    else -> Triple(Icons.Default.Repeat, normal, "列表循环")
}

@Composable
private fun UnifiedCapsule(
    showLyricsPaneToggle: Boolean,
    songId: String,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    downloadTasks: StateFlow<List<DownloadTask>>?,
    isShuffle: Boolean,
    isRepeat: Boolean,
    progressMsProvider: () -> Long,
    durationMs: Long,
    lyricsOpen: Boolean,
    moreOpen: Boolean,
    queueOpen: Boolean,
    lyricsPaneOpen: Boolean,
    focus: CapsuleFocus,
    stageFocus: FocusRequester,
    onSeekTo: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onToggleLyricsPane: () -> Unit,
    onLyricsAnchor: (Float) -> Unit,
    onMoreAnchor: (Float) -> Unit,
    onSeekFocusChange: (Boolean) -> Unit,
    downloadMenu: @Composable () -> Unit
) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    val btn = dims.scale(46.dp)
    val icon = dims.scale(23.dp)
    val seekFocus = remember { FocusRequester() }
    val modeFocus = remember { FocusRequester() }
    val paneFocus = remember { FocusRequester() }
    val order = buildList {
        add(focus.favorite); add(focus.download); add(modeFocus); add(focus.lyrics)
        if (showLyricsPaneToggle) add(paneFocus)
        add(focus.queue); add(focus.more)
    }
    fun nav(r: FocusRequester): Modifier {
        val i = order.indexOf(r)
        return Modifier.focusProperties {
            left = order.getOrNull(i - 1) ?: FocusRequester.Cancel
            right = order.getOrNull(i + 1) ?: FocusRequester.Cancel
            up = seekFocus
            down = FocusRequester.Cancel
        }
    }
    val shape = RoundedCornerShape(dims.scale(28.dp))
    Column(
        modifier = Modifier
            .width(dims.scale(if (showLyricsPaneToggle) 540.dp else 490.dp))
            .background(colors.panel, shape)
            .border(1.dp, colors.panelBorder, shape)
            .padding(start = dims.scale(20.dp), end = dims.scale(20.dp), top = dims.scale(10.dp), bottom = dims.scale(4.dp))
    ) {
        // 进度：进度条铺满，时间小字贴在它下面两端
        SeekBar(
            progressMsProvider = progressMsProvider,
            durationMs = durationMs,
            onSeekTo = onSeekTo,
            focusRequester = seekFocus,
            downTarget = focus.favorite,
            upTarget = stageFocus,
            onFocusChange = onSeekFocusChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(dims.scale(18.dp))
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ElapsedText(progressMsProvider, Modifier)
            Spacer(Modifier.weight(1f))
            Text(
                formatPlayerTime(durationMs),
                style = TabularNums,
                fontSize = dims.badgeSize,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                maxLines = 1
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(dims.scale(54.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CapsuleIcon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (isFavorite) "取消收藏" else "收藏",
                if (isFavorite) Color(0xFFFF2D55) else colors.text,
                btn, icon, focus.favorite, nav(focus.favorite), onToggleFavorite
            )
            Box {
                DownloadCapsuleButton(songId, isDownloaded, downloadTasks, btn, icon, focus.download, nav(focus.download), onDownload)
                downloadMenu()
            }
            val (modeIcon, modeTint, modeDesc) = playModeOf(isShuffle, isRepeat, colors.accent, colors.text)
            CapsuleIcon(modeIcon, modeDesc, modeTint, btn, icon, modeFocus, nav(modeFocus), onCyclePlayMode)
            Box(Modifier.width(1.dp).height(dims.scale(24.dp)).background(colors.track))
            LyricsWordButton(lyricsOpen, btn, focus.lyrics, nav(focus.lyrics), onLyricsAnchor, onLyrics)
            if (showLyricsPaneToggle) {
                CapsuleIcon(
                    Icons.Filled.Lyrics, if (lyricsPaneOpen) "收起歌词" else "展开歌词",
                    if (lyricsPaneOpen) colors.accent else colors.text, btn, icon, paneFocus, nav(paneFocus), onToggleLyricsPane
                )
            }
            CapsuleIcon(
                Icons.AutoMirrored.Filled.QueueMusic, "待播列表",
                if (queueOpen) colors.accent else colors.text, btn, icon, focus.queue, nav(focus.queue), onQueue
            )
            Box(Modifier.onGloballyPositioned { c -> onMoreAnchor(c.positionInRoot().x + c.size.width / 2f) }) {
                CapsuleIcon(
                    Icons.Filled.MoreHoriz, "更多",
                    if (moreOpen) colors.accent else colors.text, btn, icon, focus.more, nav(focus.more), onMore
                )
            }
        }
    }
}

/**
 * 下载按钮：下载中把图标换成「进度圈 + 百分比」；已下载显示绿色勾。
 * 只在这一个按钮里订阅下载进度（约 3 次 / 秒），不会带着整个播放页一起重组。
 */
@Composable
private fun DownloadCapsuleButton(
    songId: String,
    isDownloaded: Boolean,
    downloadTasks: StateFlow<List<DownloadTask>>?,
    size: Dp,
    iconSize: Dp,
    focusRequester: FocusRequester,
    navigation: Modifier,
    onClick: () -> Unit
) {
    val colors = LocalTvPlayerColors.current
    val progress: Float? = if (downloadTasks == null) null else {
        val tasks = downloadTasks.collectAsState()
        val p by remember(songId, tasks) {
            derivedStateOf {
                tasks.value.firstOrNull { it.song.id == songId && it.status != DownloadStatus.FAILED }
                    ?.progress?.coerceIn(0f, 1f)
            }
        }
        p
    }
    val ringTrack = colors.track
    val ringColor = colors.accent
    Box(
        modifier = Modifier
            .size(size)
            .focusRequester(focusRequester)
            .then(navigation)
            .tvFocusable(shape = CircleShape, focusedScale = 1.12f, focusedBorderColor = colors.accent, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (progress != null) {
            Box(
                modifier = Modifier
                    .size(size * 0.86f)
                    .drawBehind {
                        val sw = 3.dp.toPx()
                        val inset = sw / 2f
                        val arcSize = Size(this.size.width - sw, this.size.height - sw)
                        drawArc(ringTrack, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
                        drawArc(ringColor, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = TabularNums,
                    fontSize = (size.value * 0.24f).sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                    maxLines = 1
                )
            }
        } else {
            Icon(
                if (isDownloaded) Icons.Filled.CheckCircle else Icons.Filled.FileDownload,
                contentDescription = if (isDownloaded) "已下载" else "下载",
                tint = if (isDownloaded) Color(0xFF34C759) else colors.text,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun LyricsWordButton(
    open: Boolean,
    size: Dp,
    focusRequester: FocusRequester,
    navigation: Modifier,
    onAnchor: (Float) -> Unit,
    onClick: () -> Unit
) {
    val colors = LocalTvPlayerColors.current
    Box(
        modifier = Modifier
            .size(size)
            .onGloballyPositioned { c -> onAnchor(c.positionInRoot().x + c.size.width / 2f) }
            .focusRequester(focusRequester)
            .then(navigation)
            .tvFocusable(shape = CircleShape, focusedScale = 1.12f, focusedBorderColor = colors.accent, onClick = onClick)
            .background(if (open) colors.accent.copy(alpha = 0.18f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "词",
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Bold,
            color = if (open) colors.accent else colors.text
        )
    }
}

@Composable
private fun CapsuleIcon(
    image: ImageVector,
    description: String,
    tint: Color,
    size: Dp,
    iconSize: Dp,
    focusRequester: FocusRequester,
    navigation: Modifier,
    onClick: () -> Unit
) {
    val colors = LocalTvPlayerColors.current
    Box(
        modifier = Modifier
            .size(size)
            .focusRequester(focusRequester)
            .then(navigation)
            .tvFocusable(shape = CircleShape, focusedScale = 1.12f, focusedBorderColor = colors.accent, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(image, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 当前时间单独成组件：每次进度刷新只重组这一小块 */
@Composable
private fun ElapsedText(progressMsProvider: () -> Long, modifier: Modifier) {
    val dims = LocalAppDimensions.current
    Text(
        text = formatPlayerTime(progressMsProvider()),
        style = TabularNums,
        fontSize = dims.badgeSize,
        fontWeight = FontWeight.Medium,
        color = LocalTvPlayerColors.current.textSecondary,
        maxLines = 1,
        modifier = modifier
    )
}

/**
 * 进度条：平时是一条 4dp 细线；遥控器选中时加粗并显示圆点与目标时间；触屏点按 / 拖动跳转。
 * 遥控器左右键每次 ±10 秒，连按时以「目标位置」累加。
 */
@Composable
private fun SeekBar(
    progressMsProvider: () -> Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    focusRequester: FocusRequester,
    downTarget: FocusRequester,
    upTarget: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier
) {
    val colors = LocalTvPlayerColors.current
    var focused by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val duration = durationMs.coerceAtLeast(1L)
    val currentSeek by rememberUpdatedState(onSeekTo)
    val currentProvider by rememberUpdatedState(progressMsProvider)
    var pendingTarget by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingTarget) {
        if (pendingTarget != null) {
            delay(1500L)
            pendingTarget = null
        }
    }

    fun seekBy(deltaMs: Long): Boolean {
        val base = pendingTarget ?: currentProvider()
        val target = (base + deltaMs).coerceIn(0L, duration)
        pendingTarget = target
        currentSeek(target)
        return true
    }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .focusRequester(focusRequester)
            .focusProperties {
                down = downTarget
                up = upTarget ?: FocusRequester.Cancel
            }
            .tvFocusable(
                shape = RoundedCornerShape(50),
                focusedScale = 1f,
                focusedBorderColor = Color.Transparent,
                focusedBackgroundAlpha = 0f,
                borderWidth = 0.dp,
                onFocusChange = { f ->
                    focused = f
                    if (!f) pendingTarget = null
                    onFocusChange(f)
                },
                onLeftKey = { seekBy(-10_000L) },
                onRightKey = { seekBy(10_000L) },
                onClick = {}
            )
            .pointerInput(duration) {
                detectTapGestures { pos -> currentSeek(((pos.x / widthPx).coerceIn(0f, 1f) * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { pos -> dragFraction = (pos.x / widthPx).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { currentSeek((it * duration).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null }
                ) { change, amount ->
                    change.consume()
                    dragFraction = ((dragFraction ?: 0f) + amount / widthPx).coerceIn(0f, 1f)
                }
            }
            .drawBehind {
                val active = focused || dragFraction != null
                val fraction = dragFraction ?: ((pendingTarget ?: currentProvider()).toFloat() / duration).coerceIn(0f, 1f)
                val thick = if (active) 6.dp.toPx() else 4.dp.toPx()
                val cy = size.height / 2f
                drawRoundRect(colors.track, Offset(0f, cy - thick / 2f), Size(size.width, thick), CornerRadius(thick / 2f))
                drawRoundRect(colors.accent, Offset(0f, cy - thick / 2f), Size(size.width * fraction, thick), CornerRadius(thick / 2f))
                if (active) {
                    drawCircle(colors.text, radius = 8.dp.toPx(), center = Offset(size.width * fraction, cy))
                    drawCircle(colors.accent, radius = 5.dp.toPx(), center = Offset(size.width * fraction, cy))
                }
            }
    ) {
        if (focused || dragFraction != null) {
            val f = dragFraction
            Text(
                text = formatPlayerTime(if (f != null) (f * duration).toLong() else (pendingTarget ?: progressMsProvider())),
                style = TabularNums,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-36).dp)
                    .background(colors.panel, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/** 收起态：「时间 ━━━ 总时长」细进度胶囊（手机端横屏同款），点一下展开 */
@Composable
private fun MiniProgressPill(progressMsProvider: () -> Long, durationMs: Long, onExpand: () -> Unit) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    val duration = durationMs.coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .width(dims.scale(440.dp))
            .height(dims.scale(36.dp))
            .background(colors.panel.copy(alpha = colors.panel.alpha * 0.8f), RoundedCornerShape(50))
            .border(1.dp, colors.panelBorder, RoundedCornerShape(50))
            // 只响应触屏（不成为遥控器焦点目标；遥控器按下键展开由播放页统一处理）
            .pointerInput(Unit) { detectTapGestures { onExpand() } }
            .padding(horizontal = dims.scale(16.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ElapsedText(progressMsProvider, Modifier)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(dims.scale(36.dp))
                .padding(horizontal = dims.scale(12.dp))
                .drawBehind {
                    val th = 3.dp.toPx()
                    val y = size.height / 2f
                    val fraction = (progressMsProvider().toFloat() / duration).coerceIn(0f, 1f)
                    drawRoundRect(colors.track, Offset(0f, y - th / 2f), Size(size.width, th), CornerRadius(th / 2f))
                    drawRoundRect(colors.text, Offset(0f, y - th / 2f), Size(size.width * fraction, th), CornerRadius(th / 2f))
                }
        )
        Text(formatPlayerTime(durationMs), style = TabularNums, fontSize = dims.badgeSize, color = colors.textSecondary, maxLines = 1)
    }
}
