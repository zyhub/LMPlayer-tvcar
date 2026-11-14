package com.lm.player.feature.player.tv

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.roundToInt
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.rememberThrottledFrameClock
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.PlatformMode
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/** 右侧歌词栏宽度占比（手机端为 1/3，TV 屏更宽，取 0.36） */
private const val LYRICS_PANE_RATIO = 0.36f

/**
 * 主题二：专辑卡片 —— 手机端横屏「怀旧专辑流」的移植（唱片架变换、吸附弹簧、翻页手势、
 * 中心封面播放浮标、歌名顺翻页方向滑入、右侧歌词展开 / 收起，全部照手机端的参数与逻辑）。
 *
 * TV 遥控器：唱片架整体是一个焦点 —— 左右键翻一张（停下即播放那首），确定键播放 / 暂停，
 * 下键进入底部小胶囊。触屏（车机）：左右滑动翻页、点中间封面播放暂停、点侧边封面转过去，
 * 歌词栏左边的竖条点按或横向拖动来展开 / 收起，收起后右侧有「歌词」小签可点开。
 *
 * 为性能做的取舍（相对手机端）：去掉逐帧上浮的光尘与呼吸聚光（整屏每帧重绘），聚光改为静态；
 * 卡片不画阴影；歌词栏开合时歌词排版宽度不变，只做位移和淡入（不会每帧重新排版歌词）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvCardFlowTheme(input: TvThemeInput) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val isCar = LocalPlatformMode.current == PlatformMode.CAR
    val bottom by animateDpAsState(input.controlsSpace, tween(360), label = "card_bottom")
    val top = statusBarTop()
    val song = input.song

    // ---------------- 唱片架滚动（自己实现，不用系统 HorizontalPager） ----------------
    // 手机端用的是 Compose 1.12 的 Pager：翻页松手后用弹簧吸附。TV 端的 Compose 1.6 Pager 在慢速松手时
    // 改用固定 500ms 的缓动曲线、跨多张跳转时还会先瞬移再动画，这就是「不如手机自然」的来源。
    // 这里用一个连续的「当前位置（以张为单位）」驱动整个唱片架，拖动跟手、松手按手指速度推算落点
    // （与手机端同一套样条衰减），再用手机端的吸附弹簧（0.86 / 190）停稳；程序切歌用手机端的 0.88 / 170。
    val currentPlaylist = remember(input.playlist, song.id) {
        if (input.playlist.any { it.id == song.id }) input.playlist else listOf(song)
    }
    val initialIndex = remember { currentPlaylist.indexOfFirst { it.id == song.id }.coerceAtLeast(0) }
    val n = currentPlaylist.size
    val pos = remember { Animatable(initialIndex.toFloat()) }
    val decay = remember(density) { androidx.compose.animation.splineBasedDecay<Float>(density) }
    val currentSpringSpec = remember { spring<Float>(dampingRatio = 0.88f, stiffness = 170f) }
    val snapSpringSpec = remember { spring<Float>(dampingRatio = 0.86f, stiffness = 190f) }
    var dragging by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var selectedByUser by remember { mutableStateOf(false) }
    var animJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val centerIndex by remember(n) { derivedStateOf { pos.value.roundToInt().coerceIn(0, (n - 1).coerceAtLeast(0)) } }

    // 播放 / 暂停浮标：点中间封面后出现，2 秒无操作淡出（手机端逻辑）
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showPlayOverlay by remember { mutableStateOf(true) }
    LaunchedEffect(lastInteractionTime, showPlayOverlay) {
        if (showPlayOverlay) {
            delay(2000L)
            showPlayOverlay = false
        }
    }
    fun poke() {
        showPlayOverlay = true
        lastInteractionTime = System.currentTimeMillis()
    }
    fun togglePlay() {
        poke()
        input.onTogglePlay()
    }

    /** 滚到第 [target] 张；停稳后若不是正在播放的歌就播放它（与手机端「翻到哪张停下就播哪张」一致） */
    fun settleTo(target: Int, spec: androidx.compose.animation.core.AnimationSpec<Float>, velocity: Float = 0f, select: Boolean = true) {
        val t = target.coerceIn(0, (n - 1).coerceAtLeast(0))
        animJob?.cancel()
        moving = true
        poke()
        animJob = scope.launch {
            try {
                pos.animateTo(t.toFloat(), spec, initialVelocity = velocity)
                moving = false
                val selected = currentPlaylist.getOrNull(t)
                if (select && selected != null && selected.id != song.id) {
                    selectedByUser = true
                    input.onSelectSong(selected)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新的拖动 / 翻页打断：交给新的那次处理
            }
        }
    }

    // 外部切歌（胶囊 / 方向盘 / 自动下一首）→ 唱片架转过去
    LaunchedEffect(song.id, n) {
        if (selectedByUser) {
            selectedByUser = false
            return@LaunchedEffect
        }
        val targetIdx = currentPlaylist.indexOfFirst { it.id == song.id }
        if (targetIdx >= 0 && !dragging && pos.targetValue.roundToInt() != targetIdx) {
            settleTo(targetIdx, currentSpringSpec, select = false)
        }
    }

    // 进场：唱片架放大淡入，信息区 / 侧栏随后滑入（手机端 760ms）
    val enterAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enterAnim.animateTo(1f, tween(durationMillis = 760, easing = FastOutSlowInEasing)) }

    // 歌词栏开合（0 收起 / 1 展开）
    val paneProgress by animateFloatAsState(
        targetValue = if (input.lyricsPaneOpen) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 260f),
        label = "card_lyrics_pane"
    )

    // 遥控器：唱片架焦点
    var stageFocused by remember { mutableStateOf(false) }
    // 卡片宽度（像素），手势换算「拖动距离 → 张数」用
    val railCardPx = remember { floatArrayOf(1f) }

    Box(modifier = Modifier.fillMaxSize()) {
        TvCoverBackdrop(song.coverUrl, song.id, scrimAlpha = 0.58f, modifier = Modifier.fillMaxSize())
        // 静态舞台聚光 + 暗角（只画一次）
        val spotColor = if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.50f)
        val vignette = if (colors.isDark) Color.Black.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.08f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spotX = (1f - LYRICS_PANE_RATIO * paneProgress) / 2f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(spotColor, Color.Transparent),
                    center = Offset(size.width * spotX, size.height * 0.42f),
                    radius = size.height * 0.78f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, vignette),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.62f
                )
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = top + dims.scale(16.dp), bottom = bottom)
        ) {
            val fullW = maxWidth
            val paneW = fullW * LYRICS_PANE_RATIO
            val flowW = fullW - paneW * paneProgress

            // ===================== 左：唱片架 + 歌名 =====================
            Column(
                modifier = Modifier
                    .width(flowW)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 与手机端 Pager 一样裁在自己的区域里：歌词展开时唱片架被挤到左边，两侧的卡片不会伸进歌词区
                        .clipToBounds()
                        .graphicsLayer {
                            val p = enterAnim.value
                            alpha = p
                            val s = 0.86f + 0.14f * p
                            scaleX = s
                            scaleY = s
                        }
                        // 遥控器：整个唱片架一个焦点；左右翻页、确定播放暂停、下键进胶囊
                        .focusRequester(input.stageFocus)
                        .focusProperties { up = androidx.compose.ui.focus.FocusRequester.Cancel }
                        .onFocusChanged { stageFocused = it.isFocused }
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when (native.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (native.action == KeyEvent.ACTION_DOWN) {
                                        val dir = if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                                        // 连按：以正在去的那一张为基准累加
                                        settleTo(pos.targetValue.roundToInt() + dir, currentSpringSpec)
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    if (native.action == KeyEvent.ACTION_UP) togglePlay()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (native.action == KeyEvent.ACTION_DOWN) input.onStageDown()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> true
                                else -> false
                            }
                        }
                        .focusable(enabled = !isCar)
                        // 触屏：横向拖动跟手（拖过一张卡宽 = 翻一张），松手按速度推算落点后弹簧吸附。
                        // 挂在唱片架容器上（卡片的父级），手指按在卡片上开始拖动也能接到
                        .pointerInput(n) {
                            val tracker = VelocityTracker()
                            var dragPos = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    animJob?.cancel()
                                    dragging = true
                                    moving = true
                                    poke()
                                    tracker.resetTracking()
                                    dragPos = pos.value
                                },
                                onDragEnd = {
                                    dragging = false
                                    val cardPx = railCardPx[0].coerceAtLeast(1f)
                                    val vx = tracker.calculateVelocity().x
                                    val startPage = dragPos.roundToInt()
                                    // 与手机端一样：按手指速度用样条衰减推算自然停下的位置，再取最近的一张（最多 8 张）
                                    val naturalPx = decay.calculateTargetValue(dragPos * cardPx, -vx)
                                    val target = (naturalPx / cardPx).roundToInt().coerceIn(startPage - 8, startPage + 8)
                                    settleTo(target, snapSpringSpec, velocity = -vx / cardPx)
                                },
                                onDragCancel = {
                                    dragging = false
                                    settleTo(dragPos.roundToInt(), snapSpringSpec)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val cardPx = railCardPx[0].coerceAtLeast(1f)
                                dragPos = (dragPos - dragAmount / cardPx).coerceIn(0f, (n - 1).coerceAtLeast(0).toFloat())
                                val p = dragPos
                                scope.launch { pos.snapTo(p) }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val reflectionRatio = 0.16f
                    val cardSizeDp = ((maxHeight.value - 4f) / (1f + reflectionRatio))
                        .coerceAtMost(maxWidth.value * 0.40f) // 按宽度限制：歌词展开、区域变窄时卡片随之缩小（手机端横屏同样效果）
                        .coerceIn(96f, 420f)
                        .dp
                    val reflectionHeightDp = cardSizeDp * reflectionRatio
                    val cardSizePx = with(density) { cardSizeDp.toPx() }
                    railCardPx[0] = cardSizePx
                    val floorShadow = if (colors.isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.16f)

                    // 唱片架地面：中心封面脚下的椭圆投影
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .drawBehind {
                                val cardTop = (size.height - cardSizePx * (1f + reflectionRatio)) / 2f
                                val floorY = cardTop + cardSizePx
                                val r = cardSizePx * 0.95f
                                val cx = size.width / 2f
                                drawOval(
                                    brush = Brush.radialGradient(
                                        colors = listOf(floorShadow, Color.Transparent),
                                        center = Offset(cx, floorY),
                                        radius = r
                                    ),
                                    topLeft = Offset(cx - r, floorY - r * 0.16f),
                                    size = Size(r * 2f, r * 0.32f)
                                )
                            }
                    )


                    // 只组合中心两侧各 N 张（性能模式：画质 5 / 均衡 3 / 流畅 2）；按离中心的远近排序，近的后画（盖在上面）
                    val sideCount = when (com.lm.player.core.designsystem.theme.SmoothMode.level) {
                        com.lm.player.core.designsystem.theme.PerformanceLevel.QUALITY -> 5
                        com.lm.player.core.designsystem.theme.PerformanceLevel.BALANCED -> 3
                        com.lm.player.core.designsystem.theme.PerformanceLevel.SMOOTH -> 2
                    }
                    val showReflection = !com.lm.player.core.designsystem.theme.SmoothMode.isActive
                    val visible = remember(centerIndex, n, sideCount) {
                        ((centerIndex - sideCount).coerceAtLeast(0)..(centerIndex + sideCount).coerceAtMost(n - 1))
                            .sortedByDescending { abs(it - centerIndex) }
                    }
                    for (index in visible) {
                        key(currentPlaylist.getOrNull(index)?.id ?: index.toString()) {
                            val targetSong = currentPlaylist.getOrNull(index) ?: song
                            val isCenter = centerIndex == index
                            val cardInteraction = remember { MutableInteractionSource() }
                            val cardPressed by cardInteraction.collectIsPressedAsState()
                            val pressScale by animateFloatAsState(
                                targetValue = if (cardPressed && isCenter) 0.965f else 1f,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
                                label = "card_press"
                            )
                            Column(
                                modifier = Modifier
                                    .width(cardSizeDp)
                                    .graphicsLayer {
                                        // 与手机端相同的唱片架变换（pageOffset = 当前位置 - 这一张）
                                        val pageOffset = pos.value - index
                                        val absOffset = abs(pageOffset)
                                        val sign = if (pageOffset > 0) 1f else -1f
                                        // 中心一格内用 smoothstep 缓动：离开 / 回到中心时先慢后快再慢
                                        val near = absOffset.coerceAtMost(1f)
                                        val eased = near * near * (3f - 2f * near)
                                        val far = (absOffset - 1f).coerceAtLeast(0f)
                                        // 中心两侧先展开 0.54 张，再往外每张叠 0.21 张
                                        val visualDist = eased * 0.54f + far * 0.21f
                                        translationX = -sign * visualDist * cardSizePx
                                        rotationY = sign * eased * 60f
                                        val s = (1f - eased * 0.15f - far * 0.018f).coerceIn(0.70f, 1f) * pressScale
                                        scaleX = s
                                        scaleY = s
                                        // 边缘 2.8～4.2 张之间平滑渐隐
                                        alpha = when {
                                            absOffset <= 2.8f -> 1f
                                            absOffset <= 4.2f -> ((4.2f - absOffset) / 1.4f).coerceIn(0f, 1f)
                                            else -> 0f
                                        }
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        cameraDistance = 24f * density.density
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(cardSizeDp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                                        // 只响应触屏，不成为遥控器焦点（遥控器焦点在整个唱片架上）
                                        .focusProperties { canFocus = false }
                                        .clickable(interactionSource = cardInteraction, indication = null) {
                                            if (centerIndex != index) settleTo(index, currentSpringSpec) else togglePlay()
                                        }
                                ) {
                                    AlbumArtworkImage(
                                        model = targetSong.coverUrl,
                                        seedId = targetSong.id,
                                        targetSize = 512,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 12.dp
                                    )
                                    // 侧边封面随距离压暗；转动时一道高光扫过；遥控器选中时中心封面描边
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .drawBehind {
                                                val signed = pos.value - index
                                                val off = abs(signed)
                                                val dim = (off.coerceAtMost(1f) * 0.36f + (off - 1f).coerceAtLeast(0f) * 0.10f).coerceAtMost(0.60f)
                                                if (dim > 0.005f) drawRect(Color.Black.copy(alpha = dim))
                                                val shift = signed.coerceIn(-1.5f, 1.5f) * size.width * 0.7f
                                                drawRect(
                                                    brush = Brush.linearGradient(
                                                        colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                                                        start = Offset(shift - size.width * 0.1f, 0f),
                                                        end = Offset(shift + size.width * 0.55f, size.height * 0.6f)
                                                    )
                                                )
                                                if (stageFocused && isCenter) {
                                                    drawRoundRect(
                                                        color = colors.accent,
                                                        cornerRadius = CornerRadius(12.dp.toPx()),
                                                        style = Stroke(width = 3.dp.toPx())
                                                    )
                                                }
                                            }
                                    )
                                    // 中心封面：播放 / 暂停浮标（2 秒后淡出；滑动中隐藏）
                                    if (isCenter && !moving) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = showPlayOverlay,
                                            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f),
                                            exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.8f),
                                            modifier = Modifier.align(Alignment.Center)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(dims.scale(64.dp))
                                                    .clip(CircleShape)
                                                    .background(Color.Black.copy(alpha = 0.45f))
                                                    .border(1.2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (input.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                    contentDescription = if (input.isPlaying) "暂停" else "播放",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(dims.scale(32.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                                // 倒影
                                // 性能模式：流畅档不画倒影
                                if (showReflection) {
                                Box(
                                    modifier = Modifier
                                        .width(cardSizeDp)
                                        .height(reflectionHeightDp)
                                        .graphicsLayer {
                                            scaleY = -1f
                                            this.alpha = 0.35f
                                            // 透明度乘到每次绘制上，不为每张倒影单独开离屏缓冲（性能优化第 8 项）
                                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    listOf(
                                                        Color.Black.copy(alpha = 0.12f),
                                                        Color.Black.copy(alpha = 0.45f),
                                                        Color.Black.copy(alpha = 0.85f),
                                                        Color.Black
                                                    )
                                                )
                                            )
                                        }
                                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                                ) {
                                    AlbumArtworkImage(
                                        model = targetSong.coverUrl,
                                        seedId = targetSong.id,
                                        targetSize = 512,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 8.dp,
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                }
                            }
                        }
                    }
                }


                // ---- 歌名 / 歌手 — 专辑 + 音质标签（进场时从下方滑入；切歌时顺翻页方向滑入） ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = dims.scale(4.dp))
                        .graphicsLayer {
                            val p = enterAnim.value
                            alpha = p
                            translationY = (1f - p) * 26.dp.toPx()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
                        EqualizerBars(isPlaying = input.isPlaying, color = Color(0xFFFF2D55))
                        Spacer(modifier = Modifier.width(8.dp))
                        AnimatedContent(
                            targetState = song,
                            contentKey = { it.id },
                            transitionSpec = {
                                val from = currentPlaylist.indexOfFirst { it.id == initialState.id }
                                val to = currentPlaylist.indexOfFirst { it.id == targetState.id }
                                swapTransform(if (to >= from) 1 else -1, 0)
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            label = "card_title"
                        ) { s ->
                            Text(
                                text = s.title,
                                fontSize = dims.pageTitleSize,
                                fontWeight = FontWeight.Bold,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, start = 16.dp, end = 16.dp)) {
                        AnimatedContent(
                            targetState = song,
                            contentKey = { it.id },
                            transitionSpec = {
                                val from = currentPlaylist.indexOfFirst { it.id == initialState.id }
                                val to = currentPlaylist.indexOfFirst { it.id == targetState.id }
                                swapTransform(if (to >= from) 1 else -1, 50)
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            label = "card_artist"
                        ) { s ->
                            Text(
                                text = "${s.artist.ifBlank { "未知歌手" }}  —  ${s.album.ifBlank { "精选集" }}",
                                fontSize = dims.bodySize,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        TvQualityTag(rememberQualityInfo(song), fontSize = dims.badgeSize)
                    }
                }
            }

            // ===================== 右：歌词栏（展开 / 收起） =====================
            if (paneProgress > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(paneW)
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = paneProgress * enterAnim.value
                            translationX = (1f - paneProgress) * paneW.toPx() * 0.6f
                        }
                ) {
                    ThemeLyrics(
                        input, PlayerThemeStyle.IPOD_RETRO, LyricPlacement.PANEL,
                        Modifier
                            .fillMaxSize()
                            .padding(start = dims.scale(28.dp), end = dims.scale(36.dp))
                    )
                }
            }
            // 分隔竖条（触屏）：点按或横向拖动展开 / 收起歌词
            PaneHandle(
                open = input.lyricsPaneOpen,
                onToggle = input.onToggleLyricsPane,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = flowW - dims.scale(10.dp))
            )
            // 收起后右侧的「歌词」小签（触屏）
            if (paneProgress < 0.99f) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = dims.scale(14.dp))
                        .graphicsLayer { alpha = 1f - paneProgress }
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.subtleFill)
                        .border(0.6.dp, colors.panelBorder, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) { detectTapGestures { input.onToggleLyricsPane() } }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Lyrics, contentDescription = "展开歌词", tint = Color(0xFFFF2D55), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("歌\n词", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.text, lineHeight = 14.sp)
                }
            }
        }
    }
}

/** 歌词栏左边的竖条：点按切换，横向拖动超过一小段也切换（手机端同款手感，TV 上不参与焦点） */
@Composable
private fun PaneHandle(open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val colors = LocalTvPlayerColors.current
    var dragging by remember { mutableStateOf(false) }
    val handleHeight by animateDpAsState(if (dragging) 56.dp else 36.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "handle_h")
    var accumulated by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .width(20.dp)
            .height(120.dp)
            .pointerInput(open) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; accumulated = 0f },
                    onDragEnd = {
                        dragging = false
                        // 向右拖收起、向左拖展开
                        if ((open && accumulated > 40f) || (!open && accumulated < -40f)) onToggle()
                    },
                    onDragCancel = { dragging = false }
                ) { change, amount ->
                    change.consume()
                    accumulated += amount
                }
            }
            .pointerInput(Unit) { detectTapGestures { onToggle() } },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(if (dragging) 5.dp else 4.dp)
                .height(handleHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(if (dragging) Color(0xFFFF2D55) else colors.text.copy(alpha = 0.35f))
        )
    }
}

private fun swapTransform(direction: Int, delayMs: Int): ContentTransform =
    ContentTransform(
        targetContentEnter = slideInHorizontally(tween(durationMillis = 440, delayMillis = delayMs, easing = FastOutSlowInEasing)) { w -> direction * w / 5 } +
            fadeIn(tween(durationMillis = 360, delayMillis = delayMs + 60)),
        initialContentExit = slideOutHorizontally(tween(durationMillis = 300, easing = FastOutSlowInEasing)) { w -> -direction * w / 5 } +
            fadeOut(tween(durationMillis = 200)),
        sizeTransform = SizeTransform(clip = false)
    )

private val EQ_IDLE = floatArrayOf(0.35f, 0.6f, 0.25f)

/** 「正在播放」三根跳动小竖条（手机端同款）；挂在全局限帧时钟上，暂停时停成静止的高低条 */
@Composable
private fun EqualizerBars(isPlaying: Boolean, color: Color) {
    val clock = rememberThrottledFrameClock(isPlaying)
    Canvas(modifier = Modifier.size(width = 14.dp, height = 14.dp)) {
        val t = clock.value / 1000f
        val barW = size.width / 5f
        for (i in 0 until 3) {
            val level = if (isPlaying) (0.35f + 0.65f * ((sin(t * (5.2f + i * 1.7f) + i * 1.3f) + 1f) / 2f)) else EQ_IDLE[i]
            val h = size.height * level
            drawRoundRect(
                color = color,
                topLeft = Offset(i * barW * 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}
