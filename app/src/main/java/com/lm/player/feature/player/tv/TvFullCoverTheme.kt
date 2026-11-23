package com.lm.player.feature.player.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SmoothMode
import com.lm.player.core.designsystem.theme.scale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState

/**
 * 主题三：全屏封面 + 歌词（2026-11-13 重做）。
 *
 * - 胶囊展开：左侧歌名 / 歌手·专辑 / 音质 + 三行歌词；右侧封面卡片，后面叠着接下来两首歌的卡片。
 * - 胶囊收起：封面卡片向右放大并淡出，全屏封面淡入铺满整个屏幕（2026-11-13 用户要求：右边三分之二看着别扭，改回全屏封面），
 *   左侧压一层渐变；歌名与歌词留在左边，位置和未放大时完全一样。后面叠着的两张卡片随之淡出。
 * - 封面可操作：左右滑动切上一首 / 下一首，点一下播放 / 暂停；遥控器左右键切歌（胶囊收起时不展开胶囊）、确定键播放 / 暂停。
 *
 * 性能：方形卡片按放大后的尺寸布局一次、用图层缩小显示；全屏封面一直在、只改透明度；
 * 放大 / 缩小只改图层参数，过程中不重组、不重新排版，封面不会按新尺寸重新解码。
 */
@Composable
fun TvFullCoverTheme(input: TvThemeInput) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    val density = LocalDensity.current
    val smooth = SmoothMode.isActive
    val expandState = animateFloatAsState(
        targetValue = if (input.controlsExpanded) 0f else 1f,
        animationSpec = tween(if (smooth) 340 else 680, easing = FastOutSlowInEasing),
        label = "cover_expand"
    )
    // 切歌：新封面从后面第一张卡片的位置走到前面
    val switchAnim = remember { Animatable(1f) }
    var firstSong by remember { mutableStateOf(true) }
    LaunchedEffect(input.song.id) {
        if (firstSong) { firstSong = false; return@LaunchedEffect }
        if (!smooth) {
            switchAnim.snapTo(0f)
            switchAnim.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        }
    }
    var stageFocused by remember { mutableStateOf(false) }
    val top = statusBarTop()
    val playlist = input.playlist
    // 后面叠着的两张卡 = 接下来真正要播的两首：随机播放时取播放队列排好的随机计划（下一首就按它播），
    // 顺序播放时取歌单里的后两首（2026-11-13：以前随机时也按歌单顺序显示，和真正要播的对不上）
    val shuffleOn by com.lm.player.core.media.PlaybackQueueManager.isShuffleFlow.collectAsState()
    val shufflePlan by com.lm.player.core.media.PlaybackQueueManager.shufflePlanFlow.collectAsState()
    LaunchedEffect(input.song.id, shuffleOn, playlist) {
        if (shuffleOn) com.lm.player.core.media.PlaybackQueueManager.ensureShufflePlan()
    }
    val behind = remember(input.song.id, playlist, shuffleOn, shufflePlan) {
        val i = playlist.indexOfFirst { it.id == input.song.id }
        when {
            playlist.size < 2 -> emptyList()
            shuffleOn -> shufflePlan.mapNotNull { id -> playlist.firstOrNull { it.id == id } }.take(2)
            i < 0 -> emptyList()
            else -> (1..minOf(2, playlist.size - 1)).map { playlist[(i + it) % playlist.size] }
        }
    }
    // 放大后的全屏封面：慢速推近 + 轻微漂移（2026-11-13 用户选方案 A，取代原来的大幅上下移动）。
    // 封面始终铺满屏幕，24 秒极慢放大约 8%、同时朝一个方向漂一点，再 24 秒慢慢回去，循环；
    // 漂移方向按歌曲换（8 个方向之一），换歌时方向在 3 秒内平滑转过去，不跳。
    // 只在放大、页面可见时跑；流畅模式不跑。只改图层参数，不重组。
    // 2026-11-13 性能优化第 3 项：以前用独立的 60 帧动画，封面一放大整窗就按 60 次/秒重绘；
    // 现在挂在全进程共享的 30 帧时钟上（与黑胶 / 逐字歌词同一帧失效），按时钟时间算出推近进度
    val kenBurnsActive = !input.controlsExpanded && input.animationsActive && !smooth
    val kenBurnsClock = com.lm.player.core.designsystem.theme.rememberThrottledFrameClock(kenBurnsActive)
    val kenBurnsEase = remember { androidx.compose.animation.core.CubicBezierEasing(0.45f, 0f, 0.55f, 1f) }
    /** 0 → 1 → 0 往返，一程 24 秒；只在图层阶段调用 */
    fun kenBurnsValue(): Float {
        if (smooth) return 0f
        val phase = (kenBurnsClock.value % 48_000L) / 48_000f
        val tri = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        return kenBurnsEase.transform(tri)
    }
    val driftAngle = remember(input.song.id) {
        val i = ((input.song.id.hashCode() % 8) + 8) % 8
        (i * 45.0 + 22.5) * Math.PI / 180.0
    }
    val driftX by animateFloatAsState(kotlin.math.cos(driftAngle).toFloat(), tween(3000), label = "cover_drift_x")
    val driftY by animateFloatAsState(kotlin.math.sin(driftAngle).toFloat(), tween(3000), label = "cover_drift_y")

    /** 与主界面卡片之间的过渡进度（不在过渡时为 1，2026-11-22）；只在图层 / 绘制阶段读 */
    fun hp(): Float = PlayerCardHandoff.themeProgress()
    // 本页在根坐标系里的左上角（换算「正在播放那张卡」的根坐标用）
    val themeOrigin = remember { floatArrayOf(0f, 0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { val o = it.positionInRoot(); themeOrigin[0] = o.x; themeOrigin[1] = o.y }
            .clipToBounds()
    ) {
        val w = maxWidth
        val h = maxHeight

        // ---------------- 与主界面卡片之间：直接飞回（方案 A「一镜到底」，2026-11-23） ----------------
        // 不收拢、不翻面，主界面卡片原地不动：
        // - 封面放大铺满时：铺满的封面连续缩进主界面叠卡最前面那张（打开时反过来）；
        // - 正常状态：右侧方卡和后面两张各自飞回叠卡里对应的那张，后两张稍晚起步
        /** 过渡进行中（完全展开时照常显示） */
        fun handoffRunning(): Boolean =
            PlayerCardHandoff.active && PlayerCardHandoff.zoomMode && PlayerCardHandoff.progress < 0.999f

        /** 放大铺满状态的缩放进行中 */
        fun zoomActive(): Boolean = handoffRunning() && PlayerCardHandoff.zoomExpanded

        /** 正常状态的飞回进行中 */
        fun flyActive(): Boolean = handoffRunning() && !PlayerCardHandoff.zoomExpanded

        /**
         * 主界面那张「最前面的封面」（根坐标）：[左, 上, 右, 下, 圆角, 末尾是否淡出]。
         * 叠卡主图时就是最前面那张；固定黑胶时是黑胶中间那张圆形封面（它跟着唱片转，最后一小段淡出）
         */
        fun Density.homeFrontTarget(): FloatArray {
            var target = PlayerCardHandoff.homeCoverRect
            var r = PlayerCardHandoff.homeCoverRadiusPx
            var tail = 0f
            if (target == null) {
                val v = VinylHandoff.sourceRect
                target = if (v != null) {
                    androidx.compose.ui.geometry.Rect(center = v.center, radius = v.width * (106f / 164f) / 2f)
                } else {
                    androidx.compose.ui.geometry.Rect(
                        center = androidx.compose.ui.geometry.Offset(w.toPx() * 0.15f, h.toPx() * 0.45f),
                        radius = h.toPx() * 0.12f
                    )
                }
                r = target.width / 2f
                tail = 1f
            }
            return floatArrayOf(target.left, target.top, target.right, target.bottom, r, tail)
        }

        /** 缩放过渡此刻封面的显示框（本页坐标）：[左, 上, 右, 下, 圆角, 末尾是否淡出] */
        fun Density.zoomFrame(): FloatArray {
            val wPx = w.toPx()
            val hPx = h.toPx()
            val target = homeFrontTarget()
            val r = target[4]
            val tail = target[5]
            val e = PlayerCardHandoff.zoomEase(PlayerCardHandoff.progress)
            val l = target[0] - themeOrigin[0]
            val t = target[1] - themeOrigin[1]
            val rr = target[2] - themeOrigin[0]
            val b = target[3] - themeOrigin[1]
            return floatArrayOf(
                l * (1f - e),
                t * (1f - e),
                rr + (wPx - rr) * e,
                b + (hPx - b) * e,
                r * (1f - e),
                tail
            )
        }

        // 性能优化第 7 项：封面完全放大后，这层模糊背景被全屏封面整个盖住 —— 透明度置 0，系统直接跳过不画。
        // 与主界面卡片之间翻转时随进度淡入淡出；缩放模式下不画（封面缩小时露出的是主界面）
        TvCoverBackdrop(
            input.song.coverUrl, input.song.id, scrimAlpha = 0.50f,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val p = hp()
                alpha = when {
                    zoomActive() -> 0f
                    // 正常状态飞回：封面飞到一半时主界面已经露出来
                    flyActive() -> PlayerCardHandoff.directBackgroundAlpha(p)
                    expandState.value > 0.999f && p >= 0.999f -> 0f
                    else -> PlayerCardHandoff.backgroundAlpha(p)
                }
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
        )

        // 未放大的卡片：右侧、避开底部胶囊后上下居中
        val side = minOf(h * 0.56f, w * 0.34f)
        val cardRight = w * 0.08f
        val cardLeft = w - cardRight - side
        val cardTop = ((h - dims.scale(120.dp)) - side) / 2f + top / 2f
        val radius = dims.scale(22.dp)
        // 前面的卡片、后面的两张卡、全屏封面统一按这个像素尺寸解码：同一首歌的封面只解码一次，
        // 后面那张卡切到前面、全屏封面换歌时直接命中内存缓存，不会先闪一下底色再淡入
        val bigSide = h
        val bigSidePx = with(density) { bigSide.toPx() }
        val coverPx = bigSidePx.roundToInt()
        val shiftStep = dims.scale(40.dp)

        /**
         * 正在播放那张方卡此刻（不算过渡）的中心与缩放：未放大 = 右侧卡片（含切歌时从后面走到前面的那段），
         * 放大 = 屏幕右侧整屏高的方块。返回 [中心 x, 中心 y, 缩放]，本页坐标。
         */
        fun Density.frontRest(): FloatArray {
            val p = expandState.value
            val sw = switchAnim.value
            val s0 = side.toPx() / bigSidePx
            val s = s0 + (1f - s0) * p
            val k = (1f - sw) * (1f - p)
            val sc = s * (1f - 0.08f * k)
            val left = cardLeft.toPx() + ((w - bigSide).toPx() - cardLeft.toPx()) * p +
                (shiftStep.toPx() + side.toPx() * 0.04f) * k
            val topPx = cardTop.toPx() * (1f - p) + side.toPx() * 0.04f * k
            val size = bigSidePx * sc
            return floatArrayOf(left + size / 2f, topPx + size / 2f, sc)
        }

        // ---------------- 后面两张卡片（接下来的两首），未放大时可见 ----------------
        behind.asReversed().forEachIndexed { revIdx, s ->
            val depth = behind.size - revIdx          // 2 = 最后面，1 = 紧挨着前面
            val sc = 1f - 0.08f * depth
            val shift = shiftStep * depth
            Box(
                modifier = Modifier
                    .size(bigSide)
                    .graphicsLayer {
                        val p = expandState.value
                        val restSize = side.toPx() * sc
                        var cx = cardLeft.toPx() + side.toPx() * (1f - sc) / 2f + shift.toPx() + restSize / 2f
                        var cy = cardTop.toPx() + side.toPx() * (1f - sc) / 2f + restSize / 2f
                        var size = restSize
                        var a = (1f - p) * (if (depth == 1) 0.78f else 0.5f)
                        var cornerPx = radius.toPx()
                        if (zoomActive()) {
                            // 放大铺满时它们本来就看不见；主界面那两张原地不动
                            a = 0f
                        } else if (flyActive()) {
                            // 正常状态：飞回主界面叠卡里对应的那张（退出时等前面那张先走一小段再起步）
                            val e = PlayerCardHandoff.zoomEase(PlayerCardHandoff.progress / (1f - 0.08f * depth))
                            val target = PlayerCardHandoff.homeBehindRect(depth, s.id)
                            if (target != null) {
                                val tcx = target.center.x - themeOrigin[0]
                                val tcy = target.center.y - themeOrigin[1]
                                cx = tcx + (cx - tcx) * e
                                cy = tcy + (cy - tcy) * e
                                size = target.width + (restSize - target.width) * e
                                val homeA = PlayerCardHandoff.homeBehindAlpha[depth - 1]
                                a = homeA + (a - homeA) * e
                                cornerPx = PlayerCardHandoff.homeCoverRadiusPx + (cornerPx - PlayerCardHandoff.homeCoverRadiusPx) * e
                            } else {
                                // 主界面没有对应的那张（固定黑胶 / 后面那首对不上）：原地淡掉
                                a *= e
                            }
                        }
                        val scl = size / bigSidePx
                        transformOrigin = TransformOrigin.Center
                        scaleX = scl
                        scaleY = scl
                        translationX = cx - bigSidePx / 2f
                        translationY = cy - bigSidePx / 2f
                        alpha = a
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.foundation.shape.CornerSize(cornerPx / scl.coerceAtLeast(0.01f)))
                        clip = true
                    }
                    // 飞到主界面时逐渐压暗成那边的样子（主界面叠卡后面两张是压暗的），落位时对得上
                    .drawWithContent {
                        drawContent()
                        if (flyActive() && PlayerCardHandoff.homeBehindRect(depth, s.id) != null) {
                            val e = PlayerCardHandoff.zoomEase(PlayerCardHandoff.progress / (1f - 0.08f * depth))
                            val d = PlayerCardHandoff.homeBehindDim[depth - 1] * (1f - e)
                            if (d > 0.005f) drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = d))
                        }
                    }
            ) {
                AlbumArtworkImage(
                    model = s.coverUrl,
                    seedId = s.id,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0.dp,
                    contentScale = ContentScale.Crop,
                    targetSize = coverPx,
                    highQuality = true
                )
            }
        }

        // ---------------- 放大态：全屏封面（裁切铺满），随放大淡入，并慢速推近 + 轻微漂移；整块也是触屏 / 遥控器的操作区 ----------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (zoomActive()) {
                        // 缩放模式：整块裁成那个显示框（圆角随之出现）；缩到黑胶时最后一小段淡出
                        val f = zoomFrame()
                        alpha = if (f[5] > 0f) PlayerCardHandoff.smooth(0f, 0.1f, PlayerCardHandoff.progress) else 1f
                        shape = androidx.compose.foundation.shape.GenericShape { _, _ ->
                            addRoundRect(RoundRect(f[0], f[1], f[2], f[3], CornerRadius(f[4], f[4])))
                        }
                        clip = true
                    } else {
                        alpha = expandState.value
                    }
                }
                .playerStage(input) { stageFocused = it }
                .drawWithContent {
                    drawContent()
                    val p = expandState.value
                    // 左侧压一层渐变（深色主题压暗、浅色主题提亮），底部再压一层，保证歌名与歌词在任何封面上都清楚；
                    // 进出播放页时随歌名歌词一起淡掉
                    val k = PlayerCardHandoff.contentAlpha(hp())
                    if (p > 0f && k > 0.001f) {
                        val shade = colors.scrim
                        drawRect(
                            Brush.horizontalGradient(
                                0f to shade.copy(alpha = 0.82f * k),
                                0.42f to shade.copy(alpha = 0.60f * k),
                                0.70f to shade.copy(alpha = 0f)
                            )
                        )
                        drawRect(
                            Brush.verticalGradient(
                                0.62f to shade.copy(alpha = 0f),
                                1f to shade.copy(alpha = 0.6f * k)
                            )
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    // 2026-11-23：按屏幕宽画成正方形（铺满时只露出中间一条，和以前裁切铺满一样），
                    // 缩放时才能连续地从「中间一条」变成整张方图
                    .requiredSize(w)
                    .graphicsLayer {
                        // 以中心放大 1.00 → 1.08；漂移量不超过放大多出来的边距的 3/4，任何时候都不露边。
                        // 缩放模式下推近在动作开头 / 结尾那一小段里收回原位，缩放时画面才对得上
                        val kb = if (PlayerCardHandoff.active && PlayerCardHandoff.zoomMode) {
                            PlayerCardHandoff.smooth(0.85f, 1f, hp())
                        } else 1f
                        val z = kenBurnsValue() * kb
                        var s = 1f + 0.08f * z
                        var tx = driftX * size.width * 0.03f * z
                        var ty = driftY * h.toPx() * 0.03f * z
                        if (zoomActive()) {
                            // 方图边长 = 显示框的长边，中心对准显示框中心：显示框是方的就是整张图
                            val f = zoomFrame()
                            val side = maxOf(f[2] - f[0], f[3] - f[1])
                            val k = side / size.width
                            tx = tx * k + ((f[0] + f[2]) / 2f - w.toPx() / 2f)
                            ty = ty * k + ((f[1] + f[3]) / 2f - h.toPx() / 2f)
                            s *= k
                        }
                        scaleX = s
                        scaleY = s
                        translationX = tx
                        translationY = ty
                    }
            ) {
                AlbumArtworkImage(
                    model = input.song.coverUrl,
                    seedId = input.song.id,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0.dp,
                    contentScale = ContentScale.Crop,
                    targetSize = coverPx,
                    highQuality = true
                )
            }
        }

        // ---------------- 未放大：完整的方形封面卡片；放大时向右侧放大并淡出，融进全屏封面 ----------------
        val ringColor = colors.accent
        Box(
            modifier = Modifier
                .size(bigSide)
                .graphicsLayer {
                    val p = expandState.value
                    val sw = switchAnim.value
                    // 静止位置：放大 / 切歌（从后面第一张卡的位置走到前面，放大状态下不做）都已算在里面
                    val rest = frontRest()
                    var cx = rest[0]
                    var cy = rest[1]
                    var sc = rest[2]
                    // 后半程淡出，交给全屏封面
                    val fade = ((p - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    var a = (0.6f + 0.4f * sw) * (1f - fade)
                    var cornerPx = radius.toPx() * (1f - p)
                    if (zoomActive()) {
                        // 放大铺满时方卡不参与（铺满的封面自己缩进主界面）
                        a = 0f
                    } else if (flyActive()) {
                        // 正常状态：方卡直接飞到主界面叠卡最前面那张（大小、圆角一起过渡，不翻面）
                        val e = PlayerCardHandoff.zoomEase(PlayerCardHandoff.progress)
                        val t = homeFrontTarget()
                        val tcx = (t[0] + t[2]) / 2f - themeOrigin[0]
                        val tcy = (t[1] + t[3]) / 2f - themeOrigin[1]
                        val tSize = t[2] - t[0]
                        val restSize = bigSidePx * sc
                        cx = tcx + (cx - tcx) * e
                        cy = tcy + (cy - tcy) * e
                        sc = (tSize + (restSize - tSize) * e) / bigSidePx
                        cornerPx = t[4] + (cornerPx - t[4]) * e
                        a += (1f - a) * (1f - e)
                        // 落到黑胶中间那张圆形封面时，最后一小段淡出（那张跟着唱片在转）
                        if (t[5] > 0f) a *= PlayerCardHandoff.smooth(0f, 0.12f, PlayerCardHandoff.progress)
                    }
                    transformOrigin = TransformOrigin.Center
                    scaleX = sc
                    scaleY = sc
                    translationX = cx - bigSidePx / 2f
                    translationY = cy - bigSidePx / 2f
                    alpha = a
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.foundation.shape.CornerSize(cornerPx / sc.coerceAtLeast(0.01f)))
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    // 遥控器选中封面（胶囊展开时）描一圈强调色
                    if (stageFocused && input.controlsExpanded) {
                        val scale = bigSidePx / side.toPx()
                        val stroke = 7.dp.toPx() * scale
                        val r = radius.toPx() * scale
                        drawRoundRect(
                            ringColor,
                            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = stroke)
                        )
                    }
                }
        ) {
            AlbumArtworkImage(
                model = input.song.coverUrl,
                seedId = input.song.id,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                contentScale = ContentScale.Crop,
                targetSize = coverPx,
                highQuality = true
            )
        }

        // 封面卡片中心的播放 / 暂停浮标（与专辑卡片同款）；封面放大铺满时不用
        val badgeSize = dims.scale(64.dp)
        StagePlayBadge(
            isPlaying = input.isPlaying,
            songId = input.song.id,
            allowed = input.controlsExpanded && !input.closing,
            modifier = Modifier.graphicsLayer { alpha = PlayerCardHandoff.contentAlpha(hp()) }.offset {
                val half = badgeSize.toPx() / 2f
                IntOffset(
                    (cardLeft.toPx() + side.toPx() / 2f - half).roundToInt(),
                    (cardTop.toPx() + side.toPx() / 2f - half).roundToInt()
                )
            }
        )

        // ---------------- 左侧：歌名信息（两种状态都在） ----------------
        val margin = w * 0.055f
        TvSongHeader(
            input.song,
            titleSize = dims.pageTitleSize * 1.15f,
            subtitleSize = dims.bodySize * 1.1f,
            shadow = true,
            modifier = Modifier
                .offset { IntOffset(margin.roundToPx(), (top + dims.scale(40.dp)).roundToPx()) }
                .graphicsLayer { alpha = PlayerCardHandoff.contentAlpha(hp()) }
                .width(cardLeft - margin - dims.scale(36.dp))
        )

        // ---------------- 歌词：始终在左侧，封面放大前后位置不变 ----------------
        // 五行歌词：从歌名区下方一直排到底部胶囊上方（当前句在正中，上下各两句）
        val lyricsW = cardLeft - margin - dims.scale(36.dp)
        val lyricsTop = maxOf(h * 0.30f, top + dims.scale(180.dp))
        val lyricsH = (h - lyricsTop - dims.scale(150.dp)).coerceAtLeast(h * 0.40f)
        ThemeLyrics(
            input, PlayerThemeStyle.NETEASE_TV_COVER, LyricPlacement.COVER,
            Modifier
                .offset { IntOffset(margin.roundToPx(), lyricsTop.roundToPx()) }
                .graphicsLayer { alpha = PlayerCardHandoff.contentAlpha(hp()) }
                .width(lyricsW)
                .height(lyricsH)
        )
    }
}
