package com.lm.player.feature.player.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.polish.rememberCoverPalette
import com.lm.player.core.designsystem.theme.SmoothMode
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.model.UnifiedSong
import kotlin.math.abs
import kotlinx.coroutines.flow.first

/*
 * 「唱片封套」主题（2026-11-23）与主界面卡片共用的零件：
 * 黑胶唱片、切歌状态、封面取色底色。全部只改图层参数做动画，不重组、不重新排版。
 */

/** 两点之间的平滑过渡（先慢后快再慢），超出范围取端点 */
internal fun recordSmooth(a: Float, b: Float, t: Float): Float = PlayerCardHandoff.smooth(a, b, t)

/**
 * 唱片封套：主界面与播放页共用的尺寸（单位都是封套边长），两边对得上，进出播放页时黑胶才能接着飞。
 */
object SleeveShape {
    /** 黑胶直径 / 封套边长 */
    const val DISC = 0.94f

    /** 主界面：黑胶中心往右偏多少（暂停 0.2，播放中抽出到 0.28） */
    fun homeDiscOffset(play: Float): Float = 0.2f + 0.08f * play

    /** 主界面黑胶最远伸到哪（封套中心往右）：排版时整组居中用 */
    const val HOME_RIGHT_EXTENT = 0.28f + DISC / 2f

    /** 切歌时黑胶露出多少（1 = 原位，0 = 整张藏进封套）：前半收回、后半露出来 */
    fun switchReveal(t: Float): Float = (1f - recordSmooth(0f, 0.45f, t)) + recordSmooth(0.55f, 1f, t)
}

/**
 * 一张黑胶唱片：纹路 + 一道反光（转起来看得出在转）+ 中间圆形封面 + 轴孔。
 * 按最大会用到的尺寸布局一次，其余大小用图层缩放显示。
 */
@Composable
fun RecordDisc(
    coverUrl: String?,
    seedId: String,
    modifier: Modifier = Modifier,
    rotation: () -> Float = { 0f },
    labelFraction: Float = 0.36f,
    labelTargetSize: Int = 384
) {
    Box(
        modifier = modifier.graphicsLayer { rotationZ = rotation() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF2A2D35), Color(0xFF14161B), Color(0xFF0B0C10), Color(0xFF1E2028)),
                    radius = r
                ),
                radius = r
            )
            val ring = Stroke(width = 1.dp.toPx())
            for (f in floatArrayOf(0.95f, 0.88f, 0.81f, 0.74f, 0.66f, 0.58f, 0.5f)) {
                drawCircle(Color.White.copy(alpha = 0.05f), radius = r * f, style = ring)
            }
            // 一道扇形反光
            drawArc(
                color = Color.White.copy(alpha = 0.06f),
                startAngle = -62f,
                sweepAngle = 26f,
                useCenter = true
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize(labelFraction)
                .clip(CircleShape)
                .border(2.dp, Color(0xFF2B2E3B), CircleShape)
        ) {
            AlbumArtworkImage(
                model = coverUrl,
                seedId = seedId,
                targetSize = labelTargetSize,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                highQuality = true,
                allowFadeIn = false
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize(0.045f)
                .clip(CircleShape)
                .background(Color(0xFF15171E))
        )
    }
}

/**
 * 切歌状态：歌一换，立刻记下离开的那首（同一帧里画面仍停在旧歌），再用 [durationMs] 走完 0 → 1。
 * 走到一半（旧的已经藏起来）时等新封面加载好（[isReady]，最多 1.2 秒）再走后半段，换图不会落在动画之后。
 * 2026-11-23：流畅模式下也做（以前流畅模式直接跳过，车机在流畅档时看起来就是「切歌动画失效」），由调用方给短一点的时长。
 */
@Stable
class SongSwitchState(initial: UnifiedSong?) {
    internal var last: UnifiedSong? = initial
    /** 离开的那首；null = 没在切歌 */
    var from by mutableStateOf<UnifiedSong?>(null)
        internal set
    /** 1 = 下一首，-1 = 上一首 */
    var dir by mutableIntStateOf(1)
        internal set
    internal var pending = false
    internal val anim = Animatable(1f)

    /** 切歌进度（只在图层 / 绘制阶段读） */
    fun t(): Float = if (pending) 0f else anim.value
}

@Composable
fun rememberSongSwitch(
    song: UnifiedSong?,
    durationMs: () -> Int,
    isReady: (String) -> Boolean = { true }
): SongSwitchState {
    val st = remember { SongSwitchState(song) }
    val old = st.last
    if (song != null && old != null && old.id != song.id) {
        st.last = song
        st.dir = switchDirection(old, song)
        st.from = old
        st.pending = true
    } else if (old == null) {
        st.last = song
    }
    val switching = st.from
    LaunchedEffect(switching?.id, song?.id) {
        if (st.from == null) return@LaunchedEffect
        val half = (durationMs() / 2).coerceAtLeast(80)
        st.anim.snapTo(0f)
        st.pending = false
        st.anim.animateTo(0.5f, tween(half, easing = LinearEasing))
        // 旧的已经藏起来：等新封面加载好再换出来（加载失败 / 太慢就最多等 1.2 秒）
        val id = song?.id
        if (id != null && !isReady(id)) {
            kotlinx.coroutines.withTimeoutOrNull(1200L) {
                androidx.compose.runtime.snapshotFlow { isReady(id) }.first { it }
            }
        }
        st.anim.animateTo(1f, tween(half, easing = LinearEasing))
        st.from = null
    }
    return st
}

/** 新歌是不是播放队列里的上一首（是 → -1），其余都按下一首算 */
private fun switchDirection(old: UnifiedSong, new: UnifiedSong): Int {
    val list = PlaybackQueueManager.playlistFlow.value
    if (list.size < 2) return 1
    val i = list.indexOfFirst { it.id == old.id }
    val j = list.indexOfFirst { it.id == new.id }
    if (i < 0 || j < 0) return 1
    val prev = (i - 1 + list.size) % list.size
    val next = (i + 1) % list.size
    return if (j == prev && j != next) -1 else 1
}

/**
 * 封面取色底色：深色（浅色主题为浅色）底 + 以 [center] 为中心的一团封面颜色。换歌时颜色 0.6 秒平滑过渡。
 */
@Composable
fun RecordTintBackdrop(coverUrl: String?, center: () -> Offset, modifier: Modifier = Modifier) {
    val palette = rememberCoverPalette(coverUrl)
    val colors = LocalTvPlayerColors.current
    val base = colors.background
    val accent = palette?.let { p ->
        val d = p.dominant
        val gray = abs(d.red - d.green) + abs(d.green - d.blue) < 0.12f
        if (gray) p.vibrant else d
    } ?: if (colors.isDark) Color(0xFF3A3F52) else Color(0xFFD5DAE6)
    val color by animateColorAsState(accent, tween(600), label = "record_tint")
    val strength = if (colors.isDark) 0.62f else 0.45f
    Spacer(
        modifier = modifier.drawBehind {
            drawRect(base)
            drawRect(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = strength),
                    0.5f to color.copy(alpha = strength * 0.45f),
                    1f to color.copy(alpha = 0f),
                    center = center(),
                    radius = size.maxDimension * 0.85f
                )
            )
        }
    )
}
