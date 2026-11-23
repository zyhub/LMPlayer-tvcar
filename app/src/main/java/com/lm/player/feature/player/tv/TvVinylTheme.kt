package com.lm.player.feature.player.tv

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SmoothMode
import com.lm.player.core.designsystem.theme.rememberVinylRotation
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.UnifiedSong
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * 主界面左侧黑胶卡 → 播放页黑胶的「联动」过渡：主界面记下小黑胶在屏幕上的位置，点它进入播放页时
 * 播放页的唱片从那个位置、那个大小飞到自己的位置；关闭时再飞回去。两处共用同一个旋转角度，转动不中断。
 */
object VinylHandoff {
    /** 主界面小黑胶在根坐标系里的位置（像素） */
    @Volatile var sourceRect: androidx.compose.ui.geometry.Rect? = null
    @Volatile private var armed = false

    /** 主界面点小黑胶进入播放页时调用 */
    fun arm() { armed = sourceRect != null }

    /** 播放页黑胶主题第一次显示时取走：true = 需要从主界面飞进来 */
    fun consume(): Boolean = armed.also { armed = false }
}

/**
 * 主题一：黑胶唱片（默认）
 *
 * - 胶囊展开：唱片在左侧、整屏上下居中；歌名 / 歌手·专辑 / 音质在右侧歌词上方列出来显示。
 *   切歌时新唱片在旧唱片上渐变叠入。
 * - 胶囊收起：小唱片一边向左滑一边放大到大唱片的位置（露出约三分之二、上下居中、封面完整可见），转动不停；
 *   歌名 / 音质 / 歌手·专辑改为印在大唱片的黑胶纹路上跟着转（2026-11-13 与展开态对调）；歌词区向右让开。
 *   这种状态下切歌：旧唱片向左滑出，新唱片再从左边滑进来。
 * - 大小唱片都能操作：左右滑动切歌、点一下播放 / 暂停；遥控器左右键切歌（胶囊收起时不展开胶囊）、确定键播放 / 暂停。
 * - 背景的封面模糊底图与唱片同步转动（裁在页面范围内）。
 * - 退出：遥控器返回或手指下滑，唱片都收回主界面卡片上的小黑胶（下滑时跟着手指走，松手过三成就飞回去），页面同时淡出。
 *
 * 性能：唱片按「放大后」的尺寸布局一次，展开态用图层缩小显示；收起 / 展开、飞入 / 飞出的过渡只改图层与位置参数，
 * 动画过程中不重组、不重新排版（歌词排版宽度固定）。
 */
@Composable
fun TvVinylTheme(input: TvThemeInput) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    val density = LocalDensity.current
    val collapseState = animateFloatAsState(
        targetValue = if (input.controlsExpanded) 0f else 1f,
        animationSpec = tween(if (SmoothMode.isActive) 360 else 720, easing = FastOutSlowInEasing),
        label = "vinyl_collapse"
    )
    // 从主界面小黑胶飞入（0 = 在主界面小黑胶的位置，1 = 到位）
    val flight = remember { Animatable(if (VinylHandoff.consume()) 0f else 1f) }
    // 实际用的飞行进度：再乘上手指下滑收回的进度（下滑时唱片跟着手指飞回主界面小黑胶）。
    // 2026-11-22 磁吸落位：飞入 / 飞回改用带轻微回弹的弹簧，唱片到位时会多走一点再回来；
    // flRaw 可略超出 0～1（只用于唱片的位置与大小），fl 截在 0～1（用于透明度）
    fun flRaw(): Float = flight.value * (1f - input.recallProgress())
    fun fl(): Float = flRaw().coerceIn(0f, 1f)
    LaunchedEffect(Unit) {
        if (flight.value < 1f) {
            flight.animateTo(
                1f,
                if (SmoothMode.isActive) tween(280, easing = FastOutSlowInEasing)
                else androidx.compose.animation.core.spring(dampingRatio = 0.74f, stiffness = 140f)
            )
        }
    }
    // 关闭：先飞回主界面小黑胶的位置（页面其余部分同时淡出、露出主界面），落到小黑胶上后隐藏，再真正关闭
    var discLanded by remember { mutableStateOf(false) }
    LaunchedEffect(input.closing) {
        if (input.closing) {
            if (VinylHandoff.sourceRect != null) {
                flight.animateTo(
                    0f,
                    if (SmoothMode.isActive) tween(210, easing = FastOutSlowInEasing)
                    else androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 200f)
                )
                discLanded = true
            }
            input.onCloseAnimationDone()
        }
    }
    val rotation = rememberVinylRotation(input.isPlaying)
    var stageFocused by remember { mutableStateOf(false) }
    val top = statusBarTop() + dims.scale(24.dp)
    // 展开态显示在歌词上方的歌名区高度
    val headerH = dims.scale(112.dp)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val w = maxWidth
        val h = maxHeight
        // 背景：封面模糊底图跟着唱片一起转（画在比屏幕对角线还大的方块上，转到任何角度都铺满）。
        // 性能模式：只有「画质优先」才转 —— 转的时候每帧要铺满约 2.3 个屏幕的面积；
        // 均衡 / 流畅档背景静止，按屏幕大小画（2026-11-13 性能优化第 8 项）
        val rotateBackdrop = SmoothMode.isQuality
        val diag = kotlin.math.sqrt(w.value * w.value + h.value * h.value).dp
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .then(if (rotateBackdrop) Modifier.requiredSize(diag) else Modifier.fillMaxSize())
                .graphicsLayer {
                    rotationZ = if (rotateBackdrop) rotation.value else 0f
                    alpha = fl()
                }
        ) {
            TvCoverBackdrop(input.song.coverUrl, input.song.id, scrimAlpha = 0.52f, modifier = Modifier.fillMaxSize())
        }

        // 收起态：直径 = 屏高，圆心距左边缘 0.28 个直径 → 露出约 78%（封面完整可见），上下居中
        val big = h
        val collapsedCx = big * 0.28f
        val collapsedCy = h / 2f
        // 展开态：整屏上下居中，大小以不碰到底部控制胶囊为准
        val expandedReserve = dims.scale(170.dp)
        val small = minOf(h - expandedReserve * 2f, w * 0.36f).coerceAtLeast(dims.scale(160.dp))
        val expandedCx = w * 0.205f
        val expandedCy = h / 2f
        val bigPx = with(density) { big.toPx() }
        val startScale = small / big
        // 主界面小黑胶（飞入起点）
        val src = VinylHandoff.sourceRect
        val ringColor = colors.accent
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (expandedCx.toPx() - bigPx / 2f).roundToInt(),
                        (expandedCy.toPx() - bigPx / 2f).roundToInt()
                    )
                }
                .size(big)
                .graphicsLayer {
                    // 收起：一边向左滑一边放大到大唱片的位置
                    val p = collapseState.value
                    var tx = (collapsedCx.toPx() - expandedCx.toPx()) * p
                    var ty = (collapsedCy.toPx() - expandedCy.toPx()) * p
                    var s = startScale + (1f - startScale) * p
                    // 飞入 / 飞出：从主界面小黑胶的位置与大小过渡到上面算出的位置与大小（回弹时略超出两端）
                    val f = flRaw()
                    if (f != 1f && src != null) {
                        val fromTx = src.center.x - expandedCx.toPx()
                        val fromTy = src.center.y - expandedCy.toPx()
                        val fromS = src.width / bigPx
                        tx = fromTx + (tx - fromTx) * f
                        ty = fromTy + (ty - fromTy) * f
                        s = fromS + (s - fromS) * f
                    }
                    translationX = tx
                    translationY = ty
                    scaleX = s
                    scaleY = s
                    alpha = if (discLanded) 0f else 1f
                }
                .playerStage(input) { stageFocused = it }
                // 遥控器选中唱片时（胶囊展开）描一圈强调色；收起态整页就是唱片，不画
                .drawWithContent {
                    drawContent()
                    if (stageFocused && input.controlsExpanded) {
                        val sw = 6.dp.toPx() / startScale
                        drawCircle(ringColor, radius = size.minDimension / 2f - sw / 2f, style = Stroke(width = sw))
                    }
                }
        ) {
            VinylStage(
                song = input.song,
                isPlaying = input.isPlaying,
                disc = big,
                collapsedMode = !input.controlsExpanded,
                printAlpha = { collapseState.value }
            )
        }

        // 唱片中心的播放 / 暂停浮标（小唱片、大唱片都有；跟着唱片的位置走，收回时隐藏）
        val badgeSize = dims.scale(64.dp)
        StagePlayBadge(
            isPlaying = input.isPlaying,
            songId = input.song.id,
            allowed = !input.closing,
            modifier = Modifier
                .offset {
                    val p = collapseState.value
                    var cx = expandedCx.toPx() + (collapsedCx.toPx() - expandedCx.toPx()) * p
                    var cy = expandedCy.toPx()
                    val f = flRaw()
                    if (f != 1f && src != null) {
                        cx = src.center.x + (cx - src.center.x) * f
                        cy = src.center.y + (cy - src.center.y) * f
                    }
                    val half = badgeSize.toPx() / 2f
                    IntOffset((cx - half).roundToInt(), (cy - half).roundToInt())
                }
                .graphicsLayer { alpha = fl() }
        )

        // 歌词：展开时从 40% 处开始；收起时向右让到大唱片右边缘之外，排版宽度两种状态相同（不重新排版）
        val expandedLyricsX = w * 0.40f + dims.scale(12.dp)
        // 2026-11-13：放大后歌词离唱片太近 —— 与大唱片右边缘的间距 28dp → 72dp，并整体下移 56dp（不贴着顶部）
        val collapsedLyricsX = maxOf(expandedLyricsX, collapsedCx + big / 2f + dims.scale(72.dp))
        val collapsedLyricsDrop = dims.scale(56.dp)
        val lyricsW = (w - collapsedLyricsX - dims.scale(48.dp)).coerceAtLeast(dims.scale(240.dp))
        val lyricsH = (h - top - headerH - dims.scale(80.dp)).coerceAtLeast(dims.scale(120.dp))
        // 展开态：歌名 / 歌手 · 专辑 / 音质列在歌词上方（收起后淡出，改印在大唱片上）
        Box(
            modifier = Modifier
                .offset { IntOffset(expandedLyricsX.roundToPx(), top.roundToPx()) }
                .width(lyricsW)
                .height(headerH)
                .graphicsLayer {
                    val p = (1f - collapseState.value) * fl()
                    alpha = p
                    translationY = (1f - p) * (-18).dp.toPx()
                }
        ) {
            TvSongHeader(
                input.song,
                titleSize = dims.pageTitleSize * 1.1f,
                subtitleSize = dims.bodySize * 1.05f,
                shadow = colors.isDark
            )
        }
        ThemeLyrics(
            input, PlayerThemeStyle.MODERN, LyricPlacement.PANEL,
            Modifier
                .offset {
                    val p = collapseState.value
                    val x = expandedLyricsX + (collapsedLyricsX - expandedLyricsX) * p
                    IntOffset(x.roundToPx(), (top + headerH * (1f - p) + collapsedLyricsDrop * p).roundToPx())
                }
                .width(lyricsW)
                .height(lyricsH)
                .graphicsLayer { alpha = fl() }
        )
    }
}

/**
 * 唱片本体 + 切歌动画：展开态渐变叠入；收起态旧唱片向左滑出、新唱片随后从左边滑入。
 * @param printAlpha 唱片上印字的不透明度（收起放大时淡入，展开时淡出、信息改在歌词上方列出）
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun VinylStage(song: UnifiedSong, isPlaying: Boolean, disc: Dp, collapsedMode: Boolean, printAlpha: () -> Float) {
    val rotation = rememberVinylRotation(isPlaying)
    val smooth = SmoothMode.isActive
    AnimatedContent(
        targetState = song,
        contentKey = { it.id },
        transitionSpec = {
            when {
                smooth -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                collapsedMode ->
                    slideInHorizontally(tween(480, delayMillis = 380, easing = LinearOutSlowInEasing)) { -it } togetherWith
                        slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it }
                else -> fadeIn(tween(700)) togetherWith fadeOut(tween(700))
            } using SizeTransform(clip = false)
        },
        modifier = Modifier.fillMaxSize(),
        label = "vinyl_song"
    ) { s ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value },
            contentAlignment = Alignment.Center
        ) {
            VinylBody()
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = printAlpha() }) {
                RecordPrint(s, disc)
            }
            AlbumArtworkImage(
                model = s.coverUrl,
                seedId = s.id,
                modifier = Modifier.size(disc * 0.52f),
                cornerRadius = disc * 0.26f,
                highQuality = true
            )
            Box(
                modifier = Modifier
                    .size(disc * 0.05f)
                    .clip(CircleShape)
                    .background(Color(0xFFD9D9DE))
            )
        }
    }
}

@Composable
private fun VinylBody() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val r = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF2A2D35),
                0.55f to Color(0xFF121318),
                0.92f to Color(0xFF0A0B0E),
                1f to Color(0xFF22242C),
                radius = r
            ),
            radius = r
        )
        for (f in listOf(0.97f, 0.91f, 0.75f, 0.64f)) {
            drawCircle(Color.White.copy(alpha = 0.05f), radius = r * f, style = Stroke(width = 1.dp.toPx()))
        }
        // 一道高光，转动时看得出唱片在转
        drawArc(
            color = Color.White.copy(alpha = 0.06f),
            startAngle = -60f,
            sweepAngle = 36f,
            useCenter = true,
            topLeft = Offset.Zero,
            size = size
        )
    }
}

/**
 * 印在黑胶纹路上的文字：上弧是歌名（大）与音质（小，靠内圈），下弧是「歌手 · 专辑」。
 * 文字沿圆弧排布，和纹路融为一体；整层随唱片旋转（旋转在图层上做，文字只画一次，不会每帧重绘）。
 */
@Composable
private fun RecordPrint(song: UnifiedSong, disc: Dp) {
    val quality = rememberQualityInfo(song)
    val density = LocalDensity.current
    val discPx = with(density) { disc.toPx() }
    val r = discPx / 2f
    val titlePaint = remember(discPx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.White.copy(alpha = 0.90f).toArgb()
            textSize = r * 0.080f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.04f
        }
    }
    val subPaint = remember(discPx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.White.copy(alpha = 0.58f).toArgb()
            textSize = r * 0.056f
            letterSpacing = 0.06f
        }
    }
    val qualityPaint = remember(discPx, quality.tier) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tvQualityColor(quality.tier).copy(alpha = 0.85f).toArgb()
            textSize = r * 0.045f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        }
    }
    val subtitle = remember(song.artist, song.album) {
        listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString("  ·  ")
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            drawArcText(canvas, song.title, titlePaint, cx, cy, r * 0.84f, top = true)
            drawArcText(canvas, quality.label, qualityPaint, cx, cy, r * 0.68f, top = true)
            if (subtitle.isNotBlank()) drawArcText(canvas, subtitle, subPaint, cx, cy, r * 0.83f, top = false)
        }
    }
}

/** 沿半圆排字（上弧从左到右读；下弧同样从左到右、字头朝圆心），过长时截断加省略号 */
private fun drawArcText(
    canvas: android.graphics.Canvas,
    raw: String,
    paint: Paint,
    cx: Float,
    cy: Float,
    radius: Float,
    top: Boolean
) {
    if (raw.isBlank()) return
    val arcLen = (PI * radius).toFloat() * 0.80f
    var text = raw
    if (paint.measureText(text) > arcLen) {
        while (text.length > 1 && paint.measureText("$text…") > arcLen) text = text.dropLast(1)
        text = "$text…"
    }
    val path = Path()
    val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
    // 上弧：180° → 360°（顺时针经过正上方）；下弧：180° → 0°（逆时针经过正下方）
    if (top) path.addArc(oval, 180f, 180f) else path.addArc(oval, 180f, -180f)
    val half = (PI * radius).toFloat()
    val hOffset = (half - paint.measureText(text)) / 2f
    // 让字身中线落在半径上（上弧向圆心偏，下弧向外偏）
    val vOffset = paint.textSize * 0.36f
    canvas.drawTextOnPath(text, path, hOffset, vOffset, paint)
}
