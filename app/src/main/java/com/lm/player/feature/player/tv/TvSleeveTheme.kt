package com.lm.player.feature.player.tv

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SmoothMode
import com.lm.player.core.designsystem.theme.rememberVinylRotation
import com.lm.player.core.designsystem.theme.scale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 主题四：唱片封套（2026-11-23 定稿，放大用调研方案 2「歌词内页」）。
 *
 * - 主界面小播放器：封套 + 从右边露出一截的黑胶，播放时慢慢转（11-23 用户要求；共用 [SleeveShape] 的尺寸，进出播放页接得上）。
 * - 播放页（胶囊展开）：左边一张封套，黑胶从封套右侧抽出约三分之一在转；右边歌名和歌词。暂停时黑胶缩回一些、停转。
 * - 放大（胶囊收起）：封套放大并向左倾斜一点，黑胶改从封套右上角露出一截（在封套后面）；
 *   从封套里抽出一张米色「歌词内页」，略向右倾地摆在右边，歌名 / 歌手·专辑 / 音质和歌词印在纸上（深色字）。
 *   深色主题下纸换成暖灰色，晚上不刺眼。暂停时黑胶缩回一些、停转。
 * - 切歌：封套不动。黑胶按原路收回封套，藏进去后等新封面加载好，封面淡入换成新歌、黑胶中间的封面也换掉，再按原路露出来；
 *   内页上的字跟着换。
 * - 进出播放页：封套直接从主界面卡片飞过来 / 飞回去（主界面卡片不翻面）；放大状态进出时内页在封套到位后再抽出 / 先收回。
 *
 * 性能：封套、黑胶、内页都按放大后的尺寸布局一次，其余状态用图层缩放 / 旋转；所有动画只改图层参数，不重组、不重新排版。
 * 右侧一栏与内页上的文字只在各自看得见时组合。
 */
@Composable
fun TvSleeveTheme(input: TvThemeInput) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    val density = LocalDensity.current
    val smooth = SmoothMode.isActive
    val expandState = animateFloatAsState(
        targetValue = if (input.controlsExpanded) 0f else 1f,
        animationSpec = tween(if (smooth) 500 else 1000, easing = FastOutSlowInEasing),
        label = "sleeve_expand"
    )
    val playState = animateFloatAsState(
        targetValue = if (input.isPlaying) 1f else 0f,
        animationSpec = tween(if (smooth) 300 else 600, easing = FastOutSlowInEasing),
        label = "sleeve_play"
    )
    val rotation = rememberVinylRotation(input.isPlaying)
    // 新封面加载好了没有：切歌时黑胶藏进封套后等它（见 rememberSongSwitch）
    var coverReadyId by remember { mutableStateOf<String?>(null) }
    val switch = rememberSongSwitch(
        input.song,
        durationMs = { if (SmoothMode.isActive) 450 else 900 },
        // 没有封面的歌不用等
        isReady = { id -> coverReadyId == id || input.song.coverUrl.isNullOrBlank() }
    )
    var stageFocused by remember { mutableStateOf(false) }
    val top = statusBarTop()
    val themeOrigin = remember { floatArrayOf(0f, 0f) }
    fun hp(): Float = PlayerCardHandoff.themeProgress()
    fun handoffRunning(): Boolean =
        PlayerCardHandoff.active && PlayerCardHandoff.zoomMode && PlayerCardHandoff.progress < 0.999f
    fun sm(a: Float, b: Float, t: Float): Float = PlayerCardHandoff.smooth(a, b, t)
    // 两处文字（正常：右侧一栏；放大：歌词内页）只在看得见时组合
    val columnTextOn by remember { derivedStateOf { expandState.value < 0.999f } }
    val insertTextOn by remember { derivedStateOf { expandState.value > 0.6f } }
    // 内页的纸色：浅色主题米色；深色主题暖灰（晚上不刺眼）
    val paperColor = if (colors.isDark) Color(0xFFD3CABA) else Color(0xFFF3EEE4)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { val o = it.positionInRoot(); themeOrigin[0] = o.x; themeOrigin[1] = o.y }
            .clipToBounds()
    ) {
        val w = maxWidth
        val h = maxHeight
        val wPx = with(density) { w.toPx() }
        val hPx = with(density) { h.toPx() }
        // 正常状态的封套：左侧、避开底部胶囊后上下居中
        val sideN = with(density) { minOf(h * 0.56f, w * 0.34f).toPx() }
        val nCx = wPx * 0.23f
        val nCy = with(density) { (h * 0.46f + top / 2f).toPx() }
        // 放大：封套放大、左倾 4°；内页在右边、右倾 2.5°
        val sideE = with(density) { minOf(h * 0.72f, w * 0.4f).toPx() }
        val eCx = wPx * 0.25f
        val eCy = with(density) { (h * 0.49f + top / 2f).toPx() }
        val tiltE = -4f
        val inL = wPx * 0.48f
        val inT = with(density) { (h * 0.11f + top / 2f).toPx() }
        val inW = wPx * 0.45f
        val inH = hPx * 0.76f
        val inTilt = 2.5f
        val radiusPx = with(density) { 6.dp.toPx() }
        val coverPx = sideE.roundToInt()
        val discMax = sideE * 0.94f

        fun expandEase(): Float = sm(0f, 1f, expandState.value)

        /**
         * 此刻的样子（本页像素坐标）：
         * [0 封套中心 x, 1 中心 y, 2 边长, 3 倾斜, 4 圆角, 5 黑胶中心 x, 6 黑胶中心 y, 7 黑胶直径,
         *  8 内页中心 x, 9 内页中心 y, 10 内页宽, 11 内页高, 12 内页倾斜, 13 内页透明度]
         */
        fun pose(): FloatArray {
            val e = expandEase()
            val p = playState.value
            var cx = nCx + (eCx - nCx) * e
            var cy = nCy + (eCy - nCy) * e
            var s = sideN + (sideE - sideN) * e
            var rot = tiltE * e
            var rad = radiusPx
            // 黑胶相对封套中心的偏移（封套自己的坐标，单位是封套边长）：
            // 正常 = 往右抽出 0.2～0.37；放大 = 从右上角露出（暂停时缩回一些）
            var ox = (0.2f + 0.17f * p) + ((0.12f + 0.2f * p) - (0.2f + 0.17f * p)) * e
            var oy = (-0.16f - 0.2f * p) * e
            // 放大时黑胶略小一点
            var shrink = 0.08f * e
            // 内页：从封套里抽出来（e 0.3 → 1）
            var ip = sm(0.3f, 1f, e)
            var ia = sm(0.25f, 0.4f, e)
            if (handoffRunning()) {
                val home = PlayerCardHandoff.homeCoverRect
                if (home != null) {
                    // 主界面是封套 + 露出一截的黑胶：两样一起从主界面飞过来 / 飞回去，黑胶从主界面露出的位置接着抽出 / 收回；
                    // 内页等封套到位后再抽出
                    val k = PlayerCardHandoff.zoomEase(PlayerCardHandoff.progress)
                    val hcx = home.center.x - themeOrigin[0]
                    val hcy = home.center.y - themeOrigin[1]
                    cx = hcx + (cx - hcx) * k
                    cy = hcy + (cy - hcy) * k
                    s = home.width + (s - home.width) * k
                    rot *= k
                    val homeR = PlayerCardHandoff.homeCoverRadiusPx
                    rad = homeR + (rad - homeR) * k
                    val homeOx = SleeveShape.homeDiscOffset(p)
                    ox = homeOx + (ox - homeOx) * k
                    oy *= k
                    shrink *= k
                    ip *= sm(0.4f, 1f, k)
                    ia *= sm(0.3f, 0.5f, k)
                }
            }
            // 切歌：黑胶按原路收回封套，换图后再按原路露出来
            if (switch.from != null) {
                val r = SleeveShape.switchReveal(switch.t())
                ox *= r
                oy *= r
            }
            val a = Math.toRadians(rot.toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val dcx = cx + (ox * ca - oy * sa) * s
            val dcy = cy + (ox * sa + oy * ca) * s
            val dd = s * SleeveShape.DISC * (1f - shrink)
            // 内页：起点就是封套本身（藏在封套后面），终点在右边
            val icx = cx + (inL + inW / 2f - cx) * ip
            val icy = cy + (inT + inH / 2f - cy) * ip
            val iw = s + (inW - s) * ip
            val ih = s + (inH - s) * ip
            val irot = rot + (inTilt - rot) * ip
            return floatArrayOf(cx, cy, s, rot, rad, dcx, dcy, dd, icx, icy, iw, ih, irot, ia)
        }

        // ---------------- 背景：封面取色 ----------------
        RecordTintBackdrop(
            input.song.coverUrl,
            center = { val f = pose(); Offset(f[0], f[1]) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (handoffRunning()) PlayerCardHandoff.directBackgroundAlpha(PlayerCardHandoff.progress) else 1f
                }
        )

        // ---------------- 黑胶（在内页和封套后面） ----------------
        Box(
            modifier = Modifier
                .size(with(density) { discMax.toDp() })
                .graphicsLayer {
                    val f = pose()
                    val sc = f[7] / discMax
                    scaleX = sc
                    scaleY = sc
                    translationX = f[5] - discMax / 2f
                    translationY = f[6] - discMax / 2f
                }
        ) {
            // 同一个调用点按歌曲区分（切歌期间离开的在下、新的在上）：切歌开始 / 结束时原来那张只是换位置，不重建、不重新加载
            for (s in listOfNotNull(switch.from, input.song)) {
                key(s.id) {
                    val current = s.id == input.song.id
                    RecordDisc(
                        s.coverUrl, s.id,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            // 藏进封套的那一刻（走到一半）换成新歌
                            val swapped = switch.from == null || switch.t() >= 0.5f
                            alpha = if (current == swapped) 1f else 0f
                        },
                        rotation = { rotation.value },
                        labelTargetSize = 512
                    )
                }
            }
        }

        // ---------------- 歌词内页（按放大后的尺寸布局一次；在封套后面抽出来） ----------------
        val inPad = with(density) { (inW * 0.07f).toDp() }
        Box(
            modifier = Modifier
                .size(with(density) { inW.toDp() }, with(density) { inH.toDp() })
                .graphicsLayer {
                    val f = pose()
                    scaleX = f[10] / inW
                    scaleY = f[11] / inH
                    translationX = f[8] - inW / 2f
                    translationY = f[9] - inH / 2f
                    rotationZ = f[12]
                    alpha = f[13]
                    shape = RoundedCornerShape(3.dp)
                    clip = true
                }
                .background(paperColor)
        ) {
            if (insertTextOn) {
                // 纸上印深色字
                CompositionLocalProvider(LocalTvPlayerColors provides TvPlayerColors.Light) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = inPad, end = inPad, top = inPad * 0.8f, bottom = inPad * 0.6f)
                            .graphicsLayer { alpha = sm(0.75f, 1f, expandEase()) * PlayerCardHandoff.contentAlpha(hp()) }
                    ) {
                        TvSongHeader(
                            input.song,
                            titleSize = dims.pageTitleSize * 1.05f,
                            subtitleSize = dims.bodySize,
                            modifier = Modifier.height(dims.scale(104.dp))
                        )
                        Spacer(Modifier.height(dims.scale(8.dp)))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x40281E18)))
                        Spacer(Modifier.height(dims.scale(10.dp)))
                        ThemeLyrics(
                            input, PlayerThemeStyle.RECORD_SLEEVE, LyricPlacement.PANEL,
                            Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                }
            }
        }

        // ---------------- 封套（按放大后的边长布局一次，用图层缩放 / 移动 / 倾斜） ----------------
        val ringColor = colors.accent
        Box(
            modifier = Modifier
                .zIndex(1f)
                .size(with(density) { sideE.toDp() })
                .graphicsLayer {
                    val f = pose()
                    val sc = f[2] / sideE
                    scaleX = sc
                    scaleY = sc
                    translationX = f[0] - sideE / 2f
                    translationY = f[1] - sideE / 2f
                    rotationZ = f[3]
                    shape = RoundedCornerShape(CornerSize(f[4] / sc.coerceAtLeast(0.01f)))
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    // 遥控器选中封套（胶囊展开时）描一圈强调色
                    if (stageFocused && input.controlsExpanded) {
                        val sw = 5.dp.toPx() * sideE / sideN
                        drawRoundRect(
                            ringColor,
                            topLeft = Offset(sw / 2f, sw / 2f),
                            size = Size(size.width - sw, size.height - sw),
                            cornerRadius = CornerRadius(radiusPx * sideE / sideN),
                            style = Stroke(width = sw)
                        )
                    }
                }
        ) {
            // 同一个调用点按歌曲区分（切歌期间离开的在下、新的在上）：
            // 2026-11-23 修闪烁 —— 以前离开的那张放在另一个调用点，切歌一开始旧封面整个重建、重新加载，先露一下底色
            for (s in listOfNotNull(switch.from, input.song)) {
                key(s.id) {
                    val current = s.id == input.song.id
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 黑胶藏进封套、新封面也加载好以后，新封面淡入盖住旧的
                                alpha = if (!current || switch.from == null) 1f else sm(0.5f, 0.62f, switch.t())
                            }
                    ) {
                        AlbumArtworkImage(
                            model = s.coverUrl,
                            seedId = s.id,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentScale = ContentScale.Crop,
                            targetSize = coverPx,
                            highQuality = true,
                            // 淡入由切歌动画控制；加载好了告诉切歌动画可以换出来了
                            allowFadeIn = false,
                            onImageSize = if (current) ({ _, _ -> coverReadyId = s.id }) else null
                        )
                    }
                }
            }
        }

        // ---------------- 遥控器 / 触屏操作区：封套所在的位置（文字在上层，点歌词照样能跳句） ----------------
        Box(
            modifier = Modifier
                .zIndex(2.5f)
                .offset {
                    if (input.controlsExpanded) IntOffset((nCx - sideN / 2f).roundToInt(), (nCy - sideN / 2f).roundToInt())
                    else IntOffset((eCx - sideE / 2f).roundToInt(), (eCy - sideE / 2f).roundToInt())
                }
                .size(with(density) { (if (input.controlsExpanded) sideN else sideE).toDp() })
                .playerStage(input) { stageFocused = it }
        )

        // 封套中心的播放 / 暂停浮标（正常状态才有）
        val badgeSize = dims.scale(64.dp)
        StagePlayBadge(
            isPlaying = input.isPlaying,
            songId = input.song.id,
            allowed = input.controlsExpanded && !input.closing,
            modifier = Modifier
                .zIndex(3f)
                .graphicsLayer { alpha = PlayerCardHandoff.contentAlpha(hp()) }
                .offset {
                    val half = badgeSize.toPx() / 2f
                    IntOffset((nCx - half).roundToInt(), (nCy - half).roundToInt())
                }
        )

        // ---------------- 正常状态：右侧一栏的歌名 + 歌词（放大一开始就淡出，改印在内页上） ----------------
        if (columnTextOn) {
            val headerH = dims.scale(112.dp)
            val colW = w * 0.40f
            val headerTop = top + dims.scale(56.dp)
            val lyricsH = (h - top - headerH - dims.scale(190.dp)).coerceAtLeast(dims.scale(120.dp))
            Column(
                modifier = Modifier
                    .zIndex(3f)
                    .offset { IntOffset((wPx * 0.5f).roundToInt(), headerTop.roundToPx()) }
                    .width(colW)
                    .graphicsLayer { alpha = (1f - sm(0f, 0.3f, expandEase())) * PlayerCardHandoff.contentAlpha(hp()) }
            ) {
                TvSongHeader(
                    input.song,
                    titleSize = dims.pageTitleSize * 1.1f,
                    subtitleSize = dims.bodySize * 1.05f,
                    shadow = true,
                    modifier = Modifier.height(headerH)
                )
                ThemeLyrics(
                    input, PlayerThemeStyle.RECORD_SLEEVE, LyricPlacement.PANEL,
                    Modifier.width(colW).height(lyricsH)
                )
            }
        }
    }
}
