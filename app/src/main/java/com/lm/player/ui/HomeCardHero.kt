package com.lm.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.lm.player.feature.player.tv.RecordDisc
import com.lm.player.feature.player.tv.SleeveShape
import com.lm.player.feature.player.tv.recordSmooth
import com.lm.player.feature.player.tv.rememberSongSwitch
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppUiPrefs
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SmoothMode
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.model.UnifiedSong
import com.lm.player.feature.player.tv.TvPlayerPrefs
import com.lm.player.feature.player.tv.PlayerCardHandoff
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 主界面左侧播放卡片的主图（2026-11-22）：跟随播放页主题 ——
 * 专辑卡片 → 小号的一排封面（与播放页的唱片架同一顺序），全屏封面 → 叠在一起的封面（与播放页后面那两张卡同一批歌），
 * 黑胶 → 原来的黑胶。设置「主界面播放卡片样式」选「固定黑胶」时始终是黑胶。
 * 切歌时的动作与播放页一致：一排封面整体横滑一格；叠卡前一张往左飞走、后面的往前补位。
 */
enum class HomeHeroStyle { VINYL, COVER_FLOW, STACK, SLEEVE }

@Composable
fun rememberHomeHeroStyle(): HomeHeroStyle {
    val context = LocalContext.current
    remember { TvPlayerPrefs.ensureLoaded(context) }
    if (!AppUiPrefs.homeCardFollowTheme) return HomeHeroStyle.VINYL
    return when (TvPlayerPrefs.theme) {
        PlayerThemeStyle.IPOD_RETRO -> HomeHeroStyle.COVER_FLOW
        PlayerThemeStyle.NETEASE_TV_COVER -> HomeHeroStyle.STACK
        PlayerThemeStyle.RECORD_SLEEVE -> HomeHeroStyle.SLEEVE
        else -> HomeHeroStyle.VINYL
    }
}

/**
 * 唱片封套（2026-11-23）：主界面是封套 + 从右边露出一截的黑胶，播放时黑胶慢慢转（与播放页同一个角度，进出不跳）。
 * 切歌和播放页一样：封套不动，黑胶收回封套 → 新封面加载好后封面换成新歌 → 黑胶再露出来。
 * 位置上报给播放页：进出播放页时封套和黑胶从这里飞出去 / 飞回来（过渡中这两样先藏起来，由播放页那份代替）。
 */
@Composable
fun HomeSleeveHero(
    song: UnifiedSong?,
    isPlaying: Boolean,
    rotation: () -> Float,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) { onDispose { PlayerCardHandoff.homeCoverRect = null } }
    val density = LocalDensity.current
    val radiusPx = with(density) { 6.dp.toPx() }
    val play = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(if (SmoothMode.isActive) 300 else 600, easing = FastOutSlowInEasing),
        label = "home_sleeve_play"
    )
    var coverReadyId by remember { mutableStateOf<String?>(null) }
    val switch = rememberSongSwitch(
        song,
        durationMs = { if (SmoothMode.isActive) 450 else 900 },
        isReady = { id -> coverReadyId == id || song?.coverUrl.isNullOrBlank() }
    )
    BoxWithConstraints(modifier = modifier) {
        val side = minOf(maxHeight * 0.86f, maxWidth * 0.62f)
        // 封套 + 露出的黑胶整组居中（按播放中黑胶抽出最多时算）
        val left = (maxWidth - side * (0.5f + SleeveShape.HOME_RIGHT_EXTENT)) / 2f
        val sidePx = with(density) { side.toPx() }
        // 切歌期间同时有两首：离开的在下面、新的在上面；同一个调用点按歌曲区分，换位置不重建、封面不重新加载
        val songs = listOfNotNull(switch.from, song)

        // 黑胶（在封套后面）：与封套同大小的方框，缩放 + 往右平移
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = left)
                .size(side)
                .graphicsLayer {
                    alpha = if (PlayerCardHandoff.hidesHomeCover()) 0f else 1f
                    var ox = SleeveShape.homeDiscOffset(play.value)
                    if (switch.from != null) ox *= SleeveShape.switchReveal(switch.t())
                    translationX = ox * sidePx
                    scaleX = SleeveShape.DISC
                    scaleY = SleeveShape.DISC
                }
        ) {
            if (songs.isEmpty()) {
                RecordDisc(null, "tv_vinyl_idle", Modifier.fillMaxSize(), rotation = rotation)
            }
            for (s in songs) {
                key(s.id) {
                    val current = s.id == song?.id
                    RecordDisc(
                        s.coverUrl, s.id,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            // 藏进封套的那一刻（走到一半）把中间的封面换成新歌
                            val swapped = switch.from == null || switch.t() >= 0.5f
                            alpha = if (current == swapped) 1f else 0f
                        },
                        rotation = rotation
                    )
                }
            }
        }

        // 封套
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = left)
                .size(side)
                .onGloballyPositioned {
                    PlayerCardHandoff.homeCoverRect = it.boundsInRoot()
                    PlayerCardHandoff.homeCoverRadiusPx = radiusPx
                    PlayerCardHandoff.homeBehindCount = 0
                }
                .graphicsLayer { alpha = if (PlayerCardHandoff.hidesHomeCover()) 0f else 1f }
                .clip(RoundedCornerShape(6.dp))
        ) {
            if (songs.isEmpty()) {
                AlbumArtworkImage(
                    model = null, seedId = "tv_vinyl_idle", targetSize = 384,
                    modifier = Modifier.fillMaxSize(), cornerRadius = 0.dp, highQuality = true, allowFadeIn = false
                )
            }
            for (s in songs) {
                key(s.id) {
                    val current = s.id == song?.id
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 黑胶藏进去、新封面也加载好以后，新封面淡入盖住旧的
                                alpha = if (!current || switch.from == null) 1f else recordSmooth(0.5f, 0.62f, switch.t())
                            }
                    ) {
                        AlbumArtworkImage(
                            model = s.coverUrl,
                            seedId = s.id,
                            targetSize = 384,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 0.dp,
                            highQuality = true,
                            allowFadeIn = false,
                            onImageSize = if (current) ({ _, _ -> coverReadyId = s.id }) else null
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一排封面：上一首 / 当前 / 下一首。
 * 顺序播放按播放队列的顺序（与播放页专辑卡片的唱片架一致）；随机播放时右边是队列排好的「真正要播的下一首」、
 * 左边是刚播过的那首（2026-11-23：以前随机时也按队列顺序摆，下一首跟摆着的对不上，切歌变成整排交叉刷新）
 */
@Composable
fun HomeCoverFlowHero(song: UnifiedSong?, modifier: Modifier = Modifier) {
    val playlist by PlaybackQueueManager.playlistFlow.collectAsState()
    val shuffleOn by PlaybackQueueManager.isShuffleFlow.collectAsState()
    val shufflePlan by PlaybackQueueManager.shufflePlanFlow.collectAsState()
    LaunchedEffect(song?.id, shuffleOn, playlist) {
        if (shuffleOn && song != null) PlaybackQueueManager.ensureShufflePlan()
    }
    // 刚播过的那首：[0] = 上次看到的当前歌曲，[1] = 再上一首（只按歌曲变化推进）
    val history = remember { arrayOfNulls<UnifiedSong>(2) }
    if (song != null && history[0]?.id != song.id) {
        history[1] = history[0]
        history[0] = song
    }
    val playedBefore = history[1]
    val plannedNext = rememberStickyShufflePlan(song?.id, shuffleOn, playlist, shufflePlan, want = 1).firstOrNull()
    val slots = remember(song?.id, playlist, shuffleOn, plannedNext, playedBefore?.id) {
        if (song == null) emptyList<Pair<Int, UnifiedSong>>() else {
            val i = playlist.indexOfFirst { it.id == song.id }
            val prev: UnifiedSong?
            val next: UnifiedSong?
            if (shuffleOn) {
                prev = playedBefore?.takeIf { p -> playlist.any { it.id == p.id } }
                next = plannedNext?.let { id -> playlist.firstOrNull { it.id == id } }
            } else {
                prev = if (i > 0) playlist[i - 1] else null
                next = if (i >= 0 && i < playlist.size - 1) playlist[i + 1] else null
            }
            buildList<Pair<Int, UnifiedSong>> {
                add(0 to song)
                prev?.takeIf { it.id != song.id }?.let { add(-1 to it) }
                next?.takeIf { it.id != song.id && none { p -> p.second.id == it.id } }?.let { add(1 to it) }
            }
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val cover = minOf(maxHeight * 0.80f, maxWidth * 0.52f)
        val coverTop = (maxHeight - cover) * 0.30f
        val floorShadow = Color.Black.copy(alpha = 0.40f)
        // 中间封面脚下的椭圆投影（静态，只画一次）
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val r = cover.toPx() * 0.62f
                    val cx = size.width / 2f
                    val floorY = coverTop.toPx() + cover.toPx()
                    drawOval(
                        brush = Brush.radialGradient(listOf(floorShadow, Color.Transparent), center = Offset(cx, floorY), radius = r),
                        topLeft = Offset(cx - r, floorY - r * 0.12f),
                        size = Size(r * 2f, r * 0.24f)
                    )
                }
        )
        if (song == null) {
            HeroCover(null, "tv_vinyl_idle", cover, Modifier.align(Alignment.TopCenter).offset(y = coverTop))
        } else {
            HeroSlots(
                target = slots,
                drawOrder = { -abs(it) }
            ) { s, slot, alpha ->
                HeroCover(
                    s.coverUrl, s.id, cover,
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = coverTop)
                        .graphicsLayer {
                            val v = slot()
                            val a = abs(v)
                            val near = a.coerceAtMost(1f)
                            val eased = near * near * (3f - 2f * near)
                            val far = (a - 1f).coerceAtLeast(0f)
                            val sign = if (v > 0f) 1f else -1f
                            translationX = sign * (eased * 0.60f + far * 0.30f) * cover.toPx()
                            rotationY = -sign * eased * 52f
                            val sc = 1f - eased * 0.22f - far * 0.06f
                            scaleX = sc
                            scaleY = sc
                            this.alpha = alpha() * (1f - far).coerceIn(0f, 1f)
                            cameraDistance = 14f * density
                        },
                    dim = { (abs(slot()).coerceAtMost(1f) * 0.42f) }
                )
            }
        }
    }
}

/** 叠在一起的封面：当前 + 接下来要播的两首（随机播放时取队列排好的随机计划，与播放页「全屏封面」一致） */
@Composable
fun HomeStackHero(song: UnifiedSong?, modifier: Modifier = Modifier) {
    val playlist by PlaybackQueueManager.playlistFlow.collectAsState()
    val shuffleOn by PlaybackQueueManager.isShuffleFlow.collectAsState()
    val shufflePlan by PlaybackQueueManager.shufflePlanFlow.collectAsState()
    LaunchedEffect(song?.id, shuffleOn, playlist) {
        if (shuffleOn && song != null) PlaybackQueueManager.ensureShufflePlan()
    }
    val planned = rememberStickyShufflePlan(song?.id, shuffleOn, playlist, shufflePlan, want = 2)
    val slots = remember(song?.id, playlist, shuffleOn, planned) {
        if (song == null) emptyList<Pair<Int, UnifiedSong>>() else {
            val i = playlist.indexOfFirst { it.id == song.id }
            val behind = when {
                playlist.size < 2 -> emptyList()
                shuffleOn -> planned.mapNotNull { id -> playlist.firstOrNull { it.id == id } }.take(2)
                i < 0 -> emptyList()
                else -> (1..minOf(2, playlist.size - 1)).map { playlist[(i + it) % playlist.size] }
            }
            buildList<Pair<Int, UnifiedSong>> {
                add(0 to song)
                behind.forEachIndexed { idx, s -> if (none { it.second.id == s.id }) add(idx + 1 to s) }
            }
        }
    }
    // 叠卡不在了（换了主题 / 样式）：清掉上报的封面位置，播放页改用黑胶中心那张封面
    DisposableEffect(Unit) {
        onDispose {
            PlayerCardHandoff.homeCoverRect = null
            PlayerCardHandoff.homeBehindCount = 0
        }
    }
    val coverRadiusPx = with(LocalDensity.current) { 10.dp.toPx() }
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val cover = minOf(maxHeight * 0.82f, maxWidth * 0.56f)
        val step = cover * 0.13f
        // 整摞居中：最前面那张往左让出后两张露出来的宽度的一半
        val baseX = -step
        val coverTop = (maxHeight - cover) / 2f + step * 0.5f
        // 告诉播放页后面两张摆在哪、是哪两首（「全屏封面」正常状态进出时，播放页后面两张各自飞到这里，2026-11-23）
        PlayerCardHandoff.homeStackStepPx = with(density) { step.toPx() }
        PlayerCardHandoff.homeBehindCount = slots.count { it.first in 1..2 }
        for (d in 1..2) PlayerCardHandoff.homeBehindIds[d - 1] = slots.firstOrNull { it.first == d }?.second?.id
        if (song == null) {
            HeroCover(null, "tv_vinyl_idle", cover, Modifier.align(Alignment.TopCenter).offset(x = baseX, y = coverTop))
        } else {
            HeroSlots(
                target = slots,
                // 后面的先画；往左飞走的那张最后画（在最上面）
                drawOrder = { -it }
            ) { s, slot, alpha ->
                val isCurrent = s.id == song.id
                val isBehind = !isCurrent && slots.any { it.first in 1..2 && it.second.id == s.id }
                HeroCover(
                    s.coverUrl, s.id, cover,
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = baseX, y = coverTop)
                        // 正在播放那张的静止位置：播放页「全屏封面」放大状态进出时，铺满的封面从这里缩放（2026-11-23）
                        .then(
                            if (isCurrent) Modifier.onGloballyPositioned {
                                PlayerCardHandoff.homeCoverRect = it.boundsInRoot()
                                PlayerCardHandoff.homeCoverRadiusPx = coverRadiusPx
                            } else Modifier
                        )
                        .graphicsLayer {
                            val v = slot()
                            if (v >= 0f) {
                                translationX = v * step.toPx()
                                translationY = -v * step.toPx() * 0.55f
                                val sc = 1f - 0.08f * v
                                scaleX = sc
                                scaleY = sc
                                // 与播放页「全屏封面」之间直接飞回进行中：由播放页那几张在飞 / 缩放的封面代替，先藏起来
                                val hide = (isCurrent && PlayerCardHandoff.hidesHomeCover()) ||
                                    (isBehind && PlayerCardHandoff.hidesHomeBehind())
                                this.alpha = if (hide) 0f else alpha() * (1f - 0.25f * v).coerceIn(0f, 1f)
                            } else {
                                // 切到下一首：最前面那张往左飞走、淡出
                                translationX = v * cover.toPx() * 0.75f
                                rotationZ = v * 10f
                                this.alpha = alpha() * (1f + v).coerceIn(0f, 1f)
                            }
                        },
                    dim = { (slot().coerceAtLeast(0f) * 0.22f).coerceAtMost(0.5f) }
                )
            }
        }
    }
}

/**
 * 随机计划按「当前这首歌」定住（2026-11-23）：切下一首时队列会先取走计划里的第一首、稍后才换歌，
 * 直接跟着计划变会让卡片先交叉刷新一下再滑动。换歌（或换队列 / 开关随机）后从当时的计划重新取，之后只补不换。
 */
@Composable
private fun rememberStickyShufflePlan(
    songId: String?,
    shuffleOn: Boolean,
    playlist: List<UnifiedSong>,
    livePlan: List<String>,
    want: Int
): List<String> {
    val holder = remember(songId, shuffleOn, playlist) { ArrayList<String>() }
    if (shuffleOn && holder.size < want) {
        for (id in livePlan) {
            if (holder.size >= want) break
            if (id != songId && id !in holder) holder.add(id)
        }
    }
    return holder.toList()
}

@Composable
private fun HeroCover(url: String?, seedId: String, size: Dp, modifier: Modifier, dim: () -> Float = { 0f }) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
    ) {
        AlbumArtworkImage(
            model = url,
            seedId = seedId,
            targetSize = 384,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 10.dp,
            highQuality = true,
            // 不淡入：卡片被播放页盖住时加载好的封面，翻回来时直接就在（切歌本身已有滑入 / 淡入动作）
            allowFadeIn = false
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    val d = dim()
                    if (d > 0.005f) drawRect(Color.Black.copy(alpha = d))
                }
        )
    }
}

private class HeroItem(
    val song: UnifiedSong,
    val from: Float,
    val to: Float,
    val fromAlpha: Float,
    val toAlpha: Float
) {
    fun slotAt(t: Float): Float = from + (to - from) * t
    fun alphaAt(t: Float): Float = fromAlpha + (toAlpha - fromAlpha) * t
}

/**
 * 按「位置号」摆放的几张封面（0 = 当前歌曲）。切歌时：两边都有的封面从旧位置走到新位置，
 * 新出现的从外侧进来淡入，离开的往外侧走并淡出；和上一组毫无交集（跳着点歌）时原地交叉淡入淡出。
 * 动画途中又切歌（2026-11-23）：每张封面从它此刻画在的位置 / 透明度接着走，不跳回去。
 */
@Composable
private fun HeroSlots(
    target: List<Pair<Int, UnifiedSong>>,
    drawOrder: (Float) -> Float,
    content: @Composable (song: UnifiedSong, slot: () -> Float, alpha: () -> Float) -> Unit
) {
    val rest = remember(target) { target.map { HeroItem(it.second, it.first.toFloat(), it.first.toFloat(), 1f, 1f) } }
    var items by remember { mutableStateOf(rest) }
    var lastTarget by remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    val targetKey = target.joinToString("|") { "${it.first}:${it.second.id}" }
    LaunchedEffect(targetKey) {
        val old = lastTarget
        lastTarget = target
        val oldSlots = old.associate { it.second.id to it.first }
        val newSlots = target.associate { it.second.id to it.first }
        if (oldSlots == newSlots || SmoothMode.isActive || old.isEmpty()) {
            items = rest
            t.snapTo(1f)
            return@LaunchedEffect
        }
        val common = newSlots.keys.filter { it in oldSlots }
        val shift = if (common.isEmpty()) 0
        else (common.sumOf { newSlots.getValue(it) - oldSlots.getValue(it) }.toFloat() / common.size).roundToInt().coerceIn(-1, 1)
        // 每张封面此刻画在哪、多透明（上一段动画可能还没走完）
        val tv = t.value
        val shownNow = items.associateBy { it.song.id }
        val list = ArrayList<HeroItem>()
        for ((slot, s) in target) {
            val c = shownNow[s.id]
            list += if (c != null) HeroItem(s, c.slotAt(tv), slot.toFloat(), c.alphaAt(tv), 1f)
            else HeroItem(s, (slot - shift).toFloat(), slot.toFloat(), 0f, 1f)
        }
        for (c in items) {
            if (c.song.id in newSlots) continue
            val a = c.alphaAt(tv)
            if (a <= 0.01f) continue
            val s = c.slotAt(tv)
            list += HeroItem(c.song, s, s + shift, a, 0f)
        }
        items = list
        t.snapTo(0f)
        t.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        items = rest
    }
    // 目标刚变、动画还没开始的那一帧仍按旧位置画（就是动画的起点），不会闪
    for (item in items.sortedBy { drawOrder(it.to) }) {
        key(item.song.id) {
            content(
                item.song,
                { item.slotAt(t.value) },
                { item.alphaAt(t.value) }
            )
        }
    }
}
