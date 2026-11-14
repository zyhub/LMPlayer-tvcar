package com.lm.player.feature.player.tv

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.SmoothMode
import com.lm.player.core.designsystem.theme.SystemFontManager
import com.lm.player.core.designsystem.theme.ThrottledFrameClock
import com.lm.player.core.designsystem.theme.rememberThrottledFrameClock
import com.lm.player.core.model.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TV / 车机歌词引擎（2026-11-12 播放页重做）：排版、弹簧波浪滚动、逐字流光全部照手机端
 * `AmllLyricsView` 移植（同一套参数与算法），用于三个播放页主题与主界面黑胶卡。
 *
 * 与手机端的差异只有性能与输入方式上的取舍：
 * - 进度以取数函数传入，当前行用 derivedStateOf 求出 —— 只有换行时本组件才重组，
 *   逐字进度在绘制阶段读取（只重绘当前行），不会让播放页每 0.4 秒整体重组；
 * - 逐字流光挂在全局限帧时钟上（约 30 帧/秒，流畅模式 20 帧），手机端是每帧刷新；
 * - 翻译：解析器把同一时间戳的原文与译文拆成相邻两条，这里配对后把译文以小字显示在原文下方；
 * - 景深模糊用「透明文字 + 同色模糊投影」实现，Android 10 车机同样生效（流畅模式下关闭）。
 */

// =====================================================================================
// 显示设置
// =====================================================================================

/** 歌词展示样式（与手机端同名同参数） */
enum class LyricDisplayStyle(val id: String, val title: String, val subtitle: String) {
    FLOW("amll", "流光", "整屏滚动，当前行全亮"),
    KARAOKE("karaoke", "卡拉 OK", "当前句与下一句，逐字填色");

    companion object {
        fun fromId(id: String?, fallback: LyricDisplayStyle): LyricDisplayStyle =
            entries.firstOrNull { it.id == id } ?: fallback
    }
}

/** 歌词出现在哪里：决定对齐、当前行位置与可见行数（样式由用户选择） */
enum class LyricPlacement {
    /** 黑胶主题右半屏 */
    PANEL,
    /** 全屏封面主题：左侧显示上一句、当前句、下一句共 3 行 */
    COVER,
    /** 主界面左侧黑胶卡：窄、居中、5 行 */
    CARD
}

/**
 * 全局歌词开关（逐字流光 / 景深模糊 / 系统字体 / 翻译）：与手机端同一组 SharedPreferences 键，
 * 播放页三个主题与主界面黑胶卡共用，任意一处切换立刻全部生效。
 */
object TvLyricSettings {
    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_KARAOKE = "lyric_karaoke_enabled"
    private const val KEY_DEPTH_BLUR = "lyric_depth_blur_enabled"
    private const val KEY_SYSTEM_FONT = "lyric_use_system_font"
    private const val KEY_TRANSLATION = "lyric_show_translation"

    private var prefs: SharedPreferences? = null

    var karaoke by mutableStateOf(true)
        private set
    var depthBlur by mutableStateOf(true)
        private set
    var systemFont by mutableStateOf(false)
        private set
    var translation by mutableStateOf(true)
        private set

    fun ensureLoaded(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 旧版 TV 歌词把开关存在 zds_lyrics_prefs，第一次读取时沿用旧选择
        val legacy = context.applicationContext.getSharedPreferences("zds_lyrics_prefs", Context.MODE_PRIVATE)
        karaoke = when {
            p.contains(KEY_KARAOKE) -> p.getBoolean(KEY_KARAOKE, true)
            legacy.contains(KEY_KARAOKE) -> legacy.getBoolean(KEY_KARAOKE, true)
            else -> true
        }
        depthBlur = p.getBoolean(KEY_DEPTH_BLUR, true)
        systemFont = p.getBoolean(KEY_SYSTEM_FONT, false)
        translation = p.getBoolean(KEY_TRANSLATION, true)
        prefs = p
    }

    fun updateKaraoke(value: Boolean) { karaoke = value; prefs?.edit()?.putBoolean(KEY_KARAOKE, value)?.apply() }
    fun updateDepthBlur(value: Boolean) { depthBlur = value; prefs?.edit()?.putBoolean(KEY_DEPTH_BLUR, value)?.apply() }
    fun updateSystemFont(value: Boolean) { systemFont = value; prefs?.edit()?.putBoolean(KEY_SYSTEM_FONT, value)?.apply() }
    fun updateTranslation(value: Boolean) { translation = value; prefs?.edit()?.putBoolean(KEY_TRANSLATION, value)?.apply() }
}

// =====================================================================================
// 歌词行（原文 + 译文配对）
// =====================================================================================

@Immutable
data class LyricRow(val timeMs: Long, val text: String, val translation: String?)

/**
 * 解析器不区分译文：带翻译的 LRC 里原文与译文时间戳相同，排序后是相邻两条。
 * 这里把「同一时间戳的第二条」当作上一条的译文；关闭翻译时直接丢掉译文。
 */
fun buildLyricRows(lines: List<LyricLine>, withTranslation: Boolean): List<LyricRow> {
    val clean = lines.filter { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }
    val out = ArrayList<LyricRow>(clean.size)
    var i = 0
    while (i < clean.size) {
        val cur = clean[i]
        val next = clean.getOrNull(i + 1)
        if (next != null && next.timestampMs == cur.timestampMs && next.text.trim() != cur.text.trim()) {
            out.add(LyricRow(cur.timestampMs, cur.text.trim(), if (withTranslation) next.text.trim() else null))
            i += 2
        } else {
            out.add(LyricRow(cur.timestampMs, cur.text.trim(), null))
            i += 1
        }
    }
    return out
}

// =====================================================================================
// 排版参数
// =====================================================================================

private class LyricSpec(
    val textAlign: TextAlign,
    val origin: TransformOrigin,
    /** 当前行在视口中的位置（自上而下比例） */
    val anchor: Float,
    /** 行间距（相对字号） */
    val gapScale: Float,
    val activeScale: Float,
    val inactiveScale: Float,
    /** 每隔一行增加的景深模糊（dp） */
    val blurPerLine: Float,
    /** 带符号距离（0 = 当前行，正数在下方）→ 目标透明度 */
    val alphaFor: (Int) -> Float,
    /** 逐字填成强调色（卡拉 OK）；否则填成正文色 */
    val accentFill: Boolean,
    /** 左右留白（dp） */
    val sidePadDp: Float
) {
    companion object {
        private val START = TransformOrigin(0f, 0.5f)
        private val CENTER = TransformOrigin(0.5f, 0.5f)

        /** 手机端「流光」的透明度曲线 */
        private val flowAlpha: (Int) -> Float = { d -> if (d == 0) 1f else (0.36f - (abs(d) - 1) * 0.03f).coerceAtLeast(0.20f) }
        /** 手机端「卡拉 OK」的透明度曲线 */
        private val karaokeAlpha: (Int) -> Float = { d -> when (d) { 0 -> 1f; 1 -> 0.55f; -1 -> 0.20f; else -> 0.08f } }

        fun of(style: LyricDisplayStyle, placement: LyricPlacement): LyricSpec = when (placement) {
            LyricPlacement.PANEL -> when (style) {
                LyricDisplayStyle.FLOW -> LyricSpec(TextAlign.Start, START, 0.34f, 0.62f, 1f, 0.95f, 1.1f, flowAlpha, false, 8f)
                LyricDisplayStyle.KARAOKE -> LyricSpec(TextAlign.Center, CENTER, 0.42f, 0.95f, 1.08f, 0.88f, 1.4f, karaokeAlpha, true, 8f)
            }
            LyricPlacement.COVER -> {
                // 五行：当前句居中，上下各两句逐级变淡，其余行完全隐去（2026-11-13 由三行改为五行）
                val fiveLines: (Int) -> Float = { d ->
                    when (d) { 0 -> 1f; 1 -> 0.55f; -1 -> 0.42f; 2 -> 0.30f; -2 -> 0.22f; else -> 0f }
                }
                LyricSpec(TextAlign.Start, START, 0.50f, 0.50f, 1f, 0.9f, 1.4f, fiveLines, style == LyricDisplayStyle.KARAOKE, 0f)
            }
            LyricPlacement.CARD -> when (style) {
                LyricDisplayStyle.FLOW -> LyricSpec(TextAlign.Center, CENTER, 0.42f, 0.45f, 1f, 0.92f, 0f, flowAlpha, false, 2f)
                LyricDisplayStyle.KARAOKE -> LyricSpec(TextAlign.Center, CENTER, 0.42f, 0.45f, 1.04f, 0.88f, 0f, karaokeAlpha, true, 2f)
            }
        }
    }
}

/** 400ms 一次的播放进度在两次之间按真实时间插值，逐字填色才连贯（只在绘制阶段使用） */
private class PositionSmoother {
    private var lastPos = Long.MIN_VALUE
    private var lastUptime = 0L

    fun smooth(pos: Long, playing: Boolean): Long {
        val now = SystemClock.uptimeMillis()
        if (pos != lastPos) {
            lastPos = pos
            lastUptime = now
        }
        return if (playing) pos + (now - lastUptime).coerceIn(0L, 600L) else pos
    }
}

// =====================================================================================
// 歌词视图
// =====================================================================================

/**
 * @param positionMsProvider 播放进度取数函数（读取发生在 derivedStateOf / 绘制阶段，不让调用方重组）
 * @param fontSizeSp 字号（sp）
 * @param activeColor 正文颜色；逐字底字与非当前行都由它派生
 * @param animationsActive 被其它界面完全盖住时传 false：逐字流光不再订阅动画时钟，换行直接跳到位
 * @param karaokeAllowed 调用方是否允许逐字流光（主界面黑胶卡跟随「主界面黑胶旋转」开关）；最终还要看全局「逐字流光」开关
 */
@Composable
fun TvLyricsView(
    lyrics: List<LyricLine>,
    isSynced: Boolean,
    positionMsProvider: () -> Long,
    isPlaying: Boolean,
    style: LyricDisplayStyle,
    placement: LyricPlacement,
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    activeColor: Color,
    onSeekToLyric: (Long) -> Unit,
    modifier: Modifier = Modifier,
    animationsActive: Boolean = true,
    karaokeAllowed: Boolean = true,
    emptyText: String = "♪ 纯音乐 / 暂无歌词 ♪"
) {
    val context = LocalContext.current
    remember { TvLyricSettings.ensureLoaded(context) }
    val withTranslation = TvLyricSettings.translation
    val rows = remember(lyrics, withTranslation) { buildLyricRows(lyrics, withTranslation) }
    val fontFamily = remember(TvLyricSettings.systemFont) { SystemFontManager.getSystemFontFamily(TvLyricSettings.systemFont) }

    Box(modifier = modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            Text(
                text = emptyText,
                fontSize = (fontSizeSp * 0.7f).coerceIn(14f, 34f).sp,
                fontWeight = FontWeight.Bold,
                color = activeColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(if (placement == LyricPlacement.COVER) Alignment.CenterStart else Alignment.Center)
            )
        } else {
            LyricsBody(
                rows = rows,
                isSynced = isSynced,
                positionMsProvider = positionMsProvider,
                isPlaying = isPlaying,
                spec = remember(style, placement) { LyricSpec.of(style, placement) },
                fontSizeSp = fontSizeSp,
                lyricsOffsetMs = lyricsOffsetMs,
                activeColor = activeColor,
                fontFamily = fontFamily,
                karaokeEnabled = karaokeAllowed && TvLyricSettings.karaoke && isSynced,
                // 2026-11-13 用户要求：「卡拉 OK」样式下景深模糊自动关闭（选项置灰不可点），换回「流光」恢复原设置
                depthBlurEnabled = TvLyricSettings.depthBlur && !SmoothMode.isActive && style != LyricDisplayStyle.KARAOKE,
                animationsActive = animationsActive,
                onSeekToLyric = onSeekToLyric
            )
        }
    }
}

@Composable
private fun LyricsBody(
    rows: List<LyricRow>,
    isSynced: Boolean,
    positionMsProvider: () -> Long,
    isPlaying: Boolean,
    spec: LyricSpec,
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    activeColor: Color,
    fontFamily: FontFamily,
    karaokeEnabled: Boolean,
    depthBlurEnabled: Boolean,
    animationsActive: Boolean,
    onSeekToLyric: (Long) -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentProvider by rememberUpdatedState(positionMsProvider)
    val currentOffset by rememberUpdatedState(lyricsOffsetMs)

    // 当前行：只在换行时变化 → 本组件每句只重组一次
    val activeIndexState: State<Int> = remember(rows, isSynced) {
        derivedStateOf {
            if (!isSynced) -1 else {
                val pos = (currentProvider() + currentOffset).coerceAtLeast(0L)
                var low = 0
                var high = rows.lastIndex
                var best = 0
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    if (rows[mid].timeMs <= pos) { best = mid; low = mid + 1 } else high = mid - 1
                }
                best
            }
        }
    }
    val activeIndex = activeIndexState.value

    val frameClock = rememberThrottledFrameClock(karaokeEnabled && isPlaying && animationsActive)
    val smoother = remember { PositionSmoother() }

    val textStyle = remember(fontSizeSp, fontFamily, spec) {
        TextStyle(
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.28f).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = fontFamily,
            letterSpacing = (-0.01f).em,
            textAlign = spec.textAlign
        )
    }
    val transStyle = remember(fontSizeSp, fontFamily, spec) {
        TextStyle(
            fontSize = (fontSizeSp * 0.56f).sp,
            lineHeight = (fontSizeSp * 0.56f * 1.3f).sp,
            fontWeight = FontWeight.Medium,
            fontFamily = fontFamily,
            textAlign = spec.textAlign
        )
    }
    val karaokeBase = if (spec.accentFill) activeColor.copy(alpha = 0.45f) else activeColor.copy(alpha = 0.38f)
    val karaokeFill = if (spec.accentFill) AppleRed else activeColor
    val transGapPx = with(density) { (fontSizeSp * 0.16f).sp.toPx() }
    val lineGapPx = with(density) { (fontSizeSp * spec.gapScale).sp.toPx() }
    val fontResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current
    val userScroll = remember { Animatable(0f) }
    var userResetJob by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // 上下边缘渐隐：2026-11-13 性能优化第 1 项 —— 以前整块歌词区先画进离屏缓冲再用渐变遮罩合成，
            // 逐字填色每秒推进 30 次，这套离屏流程就跟着做 30 次。现在改为每行按自己在视口里的位置
            // 降低透明度（见下面每行的 graphicsLayer），效果相同、不再需要离屏缓冲
    ) {
        val sidePadPx = with(density) { spec.sidePadDp.dp.toPx() }
        val textWidthPx = (constraints.maxWidth - sidePadPx * 2f).toInt().coerceAtLeast(1)
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val anchorPx = viewportHeightPx * (if (isSynced) spec.anchor else 0.06f)

        // 每行（含译文）的高度 → 每行顶部位置。2026-11-13 起放到后台线程量：以前切歌时在主线程把整首歌
        // 每行都排版一遍（左侧黑胶卡与播放页各一遍），是切歌那一帧卡住的主要原因。
        // 量好之前这一块先空着（通常不到 0.1 秒）；同一首歌只是宽度 / 字号变了时先用旧位置，量好再换
        val topsKey = remember(rows, textStyle, transStyle, textWidthPx, lineGapPx, transGapPx, density) {
            LyricTopsKey(rows, textStyle, transStyle, textWidthPx, lineGapPx, transGapPx, density.density, density.fontScale)
        }
        val cachedTops = remember(topsKey) { LyricTopsCache.get(topsKey) }
        var measured by remember { mutableStateOf<Pair<LyricTopsKey, FloatArray>?>(null) }
        LaunchedEffect(topsKey) {
            if (cachedTops != null) {
                measured = topsKey to cachedTops
                return@LaunchedEffect
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    measureLyricTops(TextMeasurer(fontResolver, density, layoutDirection, cacheSize = 0), topsKey)
                }.getOrNull()
            } ?: return@LaunchedEffect
            LyricTopsCache.put(topsKey, result)
            measured = topsKey to result
        }
        val tops: FloatArray? = cachedTops
            ?: measured?.takeIf { it.first == topsKey }?.second
            ?: measured?.takeIf { it.first.rows == rows }?.second

        if (tops != null) {
        val focusIndex = activeIndex.coerceAtLeast(0)
        val scrollTarget = tops.getOrElse(focusIndex) { 0f } - anchorPx
        // 换行过渡：滚动（每行独立弹簧、下方各行逐行延迟的波浪跟随）、透明度、缩放，曲线与以前相同。
        // 2026-11-13：以前每行一个 60 帧动画，每次换行整窗按 60 次/秒重画 1 秒多；现在按同样的弹簧公式
        // 算出每行此刻的值，挂在全进程共享的 30 帧时钟上（与黑胶 / 逐字流光同一帧重画），过渡结束即停
        val motion = remember(rows, tops, spec, isSynced) {
            LyricMotion(
                count = rows.size,
                initialTarget = scrollTarget,
                initialFocus = focusIndex,
                alphaTarget = { i, f -> if (isSynced) spec.alphaFor(i - f) else 0.8f },
                scaleTarget = { i, f -> if (!isSynced) 1f else if (i == f) spec.activeScale else spec.inactiveScale }
            )
        }
        LyricMotionClock(motion, animationsActive)
        LaunchedEffect(motion, focusIndex, scrollTarget) {
            if (motion.isAt(focusIndex, scrollTarget)) return@LaunchedEffect
            if (!animationsActive) {
                // 被其它界面盖住：看不见，直接跳到位
                motion.jumpTo(focusIndex, scrollTarget)
                return@LaunchedEffect
            }
            val now = withFrameMillis { it }
            // 流畅模式：滚动直接跳到位（与以前一致），透明度 / 缩放仍有短过渡
            motion.retarget(focusIndex, scrollTarget, now, snapScroll = SmoothMode.isActive)
            delay(motion.durationMs)
            motion.finish()
        }
        /** 过渡中返回共享时钟的帧时间（读它 → 每次时钟推进重新摆放 / 重画）；只能在摆放 / 绘制阶段调用 */
        fun motionNow(): Long {
            motion.generation.intValue
            return if (motion.running) ThrottledFrameClock.frameTimeMs.value else 0L
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(rows) {
                    // 触屏：上下拖动浏览，松手 3 秒后回到当前行
                    detectVerticalDragGestures(
                        onDragStart = { userResetJob?.cancel() },
                        onDragEnd = {
                            userResetJob = scope.launch {
                                delay(3000)
                                userScroll.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 120f))
                            }
                        },
                        onDragCancel = {
                            userResetJob = scope.launch {
                                delay(3000)
                                userScroll.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 120f))
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { userScroll.snapTo(userScroll.value - dragAmount) }
                    }
                }
        ) {
            // 只组合视口附近的行（2026-11-13 性能优化第 2 项：以前上方一屏 + 视口 + 下方一屏约 3 屏都组合并绘制，
            // 现在只要视口上下各多留 1/4 屏；触屏上下拖动浏览时跟着拖动位置走）
            val visibleRange = run {
                val shift = userScroll.value
                val margin = viewportHeightPx * 0.25f
                val top = scrollTarget + shift - margin
                val bottom = scrollTarget + shift + viewportHeightPx + margin
                var first = 0
                while (first < tops.lastIndex && tops[first + 1] < top) first++
                var last = first
                while (last < tops.lastIndex && tops[last] < bottom) last++
                first..last
            }
            for (i in visibleRange) {
                val row = rows[i]
                // 2026-11-13 修复「景深模糊的下几句不是待播歌词」：每行按「第几行 + 内容」区分，
                // 行内记住的状态（模糊字图、逐字流光的排版）跟着这句歌词走；以前按显示位置记，
                // 换行后可见范围下移一行，同一位置换成了另一句，却还显示着上一句的模糊图
                key(i, row.text) {
                val signed = if (isSynced) i - activeIndex else 0
                val distance = abs(signed)
                val isActive = isSynced && i == activeIndex
                // 景深模糊：手机端用 RenderEffect（只有 Android 12+ 有），车机 Android 10 上不可用。
                // 2026-11-13 起改为「后台生成半分辨率模糊字图」（见 LyricBlur.kt）：以前的「偏移叠画 6～12 遍」
                // 偏移大时能看出分开的字影（重影），每帧还要画很多遍字；现在是连续的柔和模糊，每帧只画一张图。
                // 离当前行越远越糊（3 级）。
                // 2026-11-13 修复：以前只给当前行上下各 3 行做模糊，以为更远的行很淡、又在边缘渐隐区里；
                // 但专辑卡片主题歌词栏高、字小，当前行下方能看到 4 行以外的歌词，底部就露出几行清晰的字。
                // 现在所有看得见的行都模糊，4 行以外统一按第 4 级（同一级共用一张图，生成量有限）；
                // 完全看不见的行（全屏封面主题上下各 2 行以外透明度为 0）不做
                val blurLevel = distance.coerceAtMost(4)
                val visibleLine = !isSynced || spec.alphaFor(signed) > 0.01f
                val blurPx = if (!depthBlurEnabled || isActive || spec.blurPerLine <= 0f || !visibleLine) 0f
                else with(density) { (blurLevel * spec.blurPerLine * 1.6f).dp.toPx() }
                val blurring = blurPx > 0.8f
                val nextTime = rows.getOrNull(i + 1)?.timeMs ?: (row.timeMs + 4000L)
                val lineDuration = (nextTime - row.timeMs).coerceIn(800L, 12000L)

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                sidePadPx.roundToInt(),
                                (tops[i] - motion.scrollAt(i, motionNow()) - userScroll.value).roundToInt()
                            )
                        }
                        .width(with(density) { textWidthPx.toDp() })
                        .graphicsLayer {
                            val now = motionNow()
                            // 上下边缘渐隐：行中心落在视口顶部 8% / 底部 14% 内时逐渐变透明（与原遮罩同一比例）
                            val y = tops[i] - motion.scrollAt(i, now) - userScroll.value
                            val c = (y + size.height / 2f) / viewportHeightPx.coerceAtLeast(1f)
                            val edge = when {
                                c < 0.08f -> c / 0.08f
                                c > 0.86f -> (1f - c) / 0.14f
                                else -> 1f
                            }.coerceIn(0f, 1f)
                            this.alpha = motion.alphaAt(i, now) * edge
                            val scale = motion.scaleAt(i, now)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = spec.origin
                            // 透明度直接乘到每次绘制上，不为半透明的行单独开离屏缓冲（每行一块）
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        // 点一行跳转：只响应触屏。不能用 clickable —— 它会让每一行都成为遥控器焦点目标，
                        // 方向键会把焦点送到看不见的歌词行上（「光标消失」）
                        .pointerInput(row.timeMs, isSynced) {
                            if (!isSynced) return@pointerInput
                            detectTapGestures {
                                scope.launch { userScroll.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 200f)) }
                                onSeekToLyric((row.timeMs - currentOffset).coerceAtLeast(0L))
                            }
                        }
                ) {
                    // 景深模糊：真正的模糊字图（后台生成、按颜色着色），还没生成好时先显示清晰的字
                    val blurredLine = if (blurring) {
                        rememberBlurredLyricLine(
                            text = row.text,
                            translation = row.translation,
                            style = textStyle,
                            transStyle = transStyle,
                            transGapPx = transGapPx,
                            widthPx = textWidthPx,
                            level = blurLevel,
                            radiusPx = blurPx
                        )
                    } else null
                    if (blurredLine != null) {
                        BlurredLyricLineView(blurredLine, activeColor)
                    } else Column(modifier = Modifier.fillMaxWidth()) {
                        if (isActive && karaokeEnabled) {
                            KaraokeLine(
                                text = row.text,
                                style = textStyle,
                                baseColor = karaokeBase,
                                fillColor = karaokeFill,
                                progress = {
                                    @Suppress("UNUSED_VARIABLE") val tick = frameClock.value
                                    val pos = smoother.smooth(currentProvider() + currentOffset, isPlaying)
                                    ((pos - row.timeMs).toFloat() / lineDuration).coerceIn(0f, 1f)
                                }
                            )
                        } else {
                            Text(text = row.text, style = textStyle.copy(color = activeColor), modifier = Modifier.fillMaxWidth())
                        }
                        if (row.translation != null) {
                            Spacer(Modifier.height(with(density) { transGapPx.toDp() }))
                            Text(
                                text = row.translation,
                                style = transStyle.copy(color = activeColor.copy(alpha = if (isActive) 0.72f else 0.85f)),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                } // key
            }
        }
        } // tops != null
    }
}

/** 换行过渡的时钟订阅放在单独的小组件里：开始 / 结束时只重组这一处，不牵动整块歌词 */
@Composable
private fun LyricMotionClock(motion: LyricMotion, animationsActive: Boolean) {
    rememberThrottledFrameClock(motion.runningState.value && animationsActive)
}

// =====================================================================================
// 换行过渡（挂在共享 30 帧时钟上）
// =====================================================================================

/**
 * 一首歌（同一组行位置）的换行过渡状态，只在主线程使用。
 *
 * 每次换行记下每行「此刻显示的值」作为起点，之后每帧按弹簧公式算出：
 * - 滚动：阻尼 0.83、刚度 100（与以前每行一个 Animatable 的参数相同）；当前行下方的行每行晚 35ms 起步
 *   （最多 350ms），形成波浪跟随；离当前行超过 14 行的直接到位；
 * - 透明度：阻尼 1、刚度 300；缩放：阻尼 0.75、刚度 220（与以前 animateFloatAsState 相同）。
 * 中途又换行时从当时显示的值重新起步（与以前相比只少了速度的衔接，换行间隔都在秒级，看不出差别）。
 */
private class LyricMotion(
    private val count: Int,
    initialTarget: Float,
    initialFocus: Int,
    private val alphaTarget: (Int, Int) -> Float,
    private val scaleTarget: (Int, Int) -> Float
) {
    private var fromScroll = FloatArray(count) { initialTarget }
    private var fromAlpha = FloatArray(count) { alphaTarget(it, initialFocus) }
    private var fromScale = FloatArray(count) { scaleTarget(it, initialFocus) }
    private var toScroll = initialTarget
    private var focus = initialFocus
    private var startMs = 0L
    private var snapScroll = false

    /** 过渡进行中（绘制阶段读这个普通字段；变化时同时改下面两个状态） */
    var running = false
        private set

    /** 给时钟订阅用（组合阶段读） */
    val runningState = mutableStateOf(false)

    /** 换目标 / 结束时 +1：摆放、绘制阶段读它，让各行按新的起点 / 终点重新算 */
    val generation = mutableIntStateOf(0)

    /** 本次过渡总时长：最长 350ms 起步延迟 + 弹簧约 1 秒走完；滚动直接到位时只剩透明度 / 缩放约 0.5 秒 */
    val durationMs: Long get() = if (snapScroll) 500L else 1_400L

    fun isAt(newFocus: Int, newTarget: Float): Boolean = newFocus == focus && newTarget == toScroll

    fun scrollAt(i: Int, now: Long): Float {
        if (!running || snapScroll) return toScroll
        val d = i - focus
        if (abs(d) > 14) return toScroll
        val delayMs = if (d <= 0) 0L else (d * 35L).coerceAtMost(350L)
        val from = fromScroll.getOrElse(i) { toScroll }
        return from + (toScroll - from) * springStep((now - startMs - delayMs) / 1000f, 0.83f, SCROLL_OMEGA)
    }

    fun alphaAt(i: Int, now: Long): Float {
        val to = alphaTarget(i, focus)
        if (!running) return to
        val from = fromAlpha.getOrElse(i) { to }
        return from + (to - from) * springStep((now - startMs) / 1000f, 1f, ALPHA_OMEGA)
    }

    fun scaleAt(i: Int, now: Long): Float {
        val to = scaleTarget(i, focus)
        if (!running) return to
        val from = fromScale.getOrElse(i) { to }
        return from + (to - from) * springStep((now - startMs) / 1000f, 0.75f, SCALE_OMEGA)
    }

    /** 开始一次过渡：以 [now]（帧时间）时各行显示的值为起点 */
    fun retarget(newFocus: Int, newTarget: Float, now: Long, snapScroll: Boolean) {
        val s = FloatArray(count) { scrollAt(it, now) }
        val a = FloatArray(count) { alphaAt(it, now) }
        val c = FloatArray(count) { scaleAt(it, now) }
        fromScroll = s
        fromAlpha = a
        fromScale = c
        focus = newFocus
        toScroll = newTarget
        startMs = now
        this.snapScroll = snapScroll
        setRunning(true)
    }

    /** 不要过渡，直接到位（界面被盖住时） */
    fun jumpTo(newFocus: Int, newTarget: Float) {
        focus = newFocus
        toScroll = newTarget
        setRunning(false)
    }

    fun finish() = setRunning(false)

    private fun setRunning(value: Boolean) {
        running = value
        runningState.value = value
        generation.intValue++
    }

    private companion object {
        val SCROLL_OMEGA = sqrt(100f)
        val ALPHA_OMEGA = sqrt(300f)
        val SCALE_OMEGA = sqrt(220f)
    }
}

/** 弹簧从 0 走到 1 的位置（质量 1、初速度 0）：[zeta] 阻尼比，[omega] = √刚度，[t] 秒 */
private fun springStep(t: Float, zeta: Float, omega: Float): Float {
    if (t <= 0f) return 0f
    return if (zeta < 1f) {
        val wd = omega * sqrt(1f - zeta * zeta)
        1f - exp(-zeta * omega * t) * (cos(wd * t) + zeta * omega / wd * sin(wd * t))
    } else {
        1f - exp(-omega * t) * (1f + omega * t)
    }
}

// =====================================================================================
// 行位置（后台线程测量 + 小缓存）
// =====================================================================================

private data class LyricTopsKey(
    val rows: List<LyricRow>,
    val style: TextStyle,
    val transStyle: TextStyle,
    val widthPx: Int,
    val lineGapPx: Float,
    val transGapPx: Float,
    val density: Float,
    val fontScale: Float
)

/** 最近 8 组行位置：来回切主题 / 重开播放页 / 切回刚听过的歌时直接用，不用再量 */
private object LyricTopsCache {
    private val map = object : LinkedHashMap<LyricTopsKey, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricTopsKey, FloatArray>?): Boolean = size > 8
    }

    @Synchronized
    fun get(key: LyricTopsKey): FloatArray? = map[key]

    @Synchronized
    fun put(key: LyricTopsKey, tops: FloatArray) {
        map[key] = tops
    }
}

/** 在后台线程逐行排版，得出每行顶部位置（[measurer] 须为本次单独创建的实例） */
private suspend fun measureLyricTops(measurer: TextMeasurer, key: LyricTopsKey): FloatArray {
    val context = currentCoroutineContext()
    val constraints = Constraints(maxWidth = key.widthPx)
    val out = FloatArray(key.rows.size)
    var y = 0f
    for (i in key.rows.indices) {
        if (i % 16 == 0) context.ensureActive()
        out[i] = y
        val row = key.rows[i]
        var h = measurer.measure(row.text, key.style, constraints = constraints).size.height.toFloat()
        if (row.translation != null) {
            h += key.transGapPx + measurer.measure(row.translation, key.transStyle, constraints = constraints).size.height
        }
        y += h + key.lineGapPx
    }
    return out
}

/** 逐字流光：底层为半透明字，上层亮字按视觉行依次揭开，前沿带柔和渐变（手机端同款） */
@Composable
private fun KaraokeLine(
    text: String,
    style: TextStyle,
    baseColor: Color,
    fillColor: Color,
    progress: () -> Float
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = style.copy(color = baseColor),
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = text,
            style = style.copy(color = fillColor),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val l = layout ?: return@drawWithContent
                    val p = progress()
                    val total = text.length.coerceAtLeast(1)
                    val sung = p * total
                    val edge = style.fontSize.toPx() * 0.9f
                    for (line in 0 until l.lineCount) {
                        val start = l.getLineStart(line)
                        val end = l.getLineEnd(line, visibleEnd = true)
                        val left = l.getLineLeft(line)
                        val right = l.getLineRight(line)
                        val fillX = when {
                            sung >= end -> right + edge
                            sung <= start -> left - edge
                            else -> {
                                val idx = sung.toInt().coerceIn(start, (end - 1).coerceAtLeast(start))
                                val x0 = l.getHorizontalPosition(idx, usePrimaryDirection = true)
                                val x1 = l.getHorizontalPosition((idx + 1).coerceAtMost(end), usePrimaryDirection = true)
                                x0 + (x1 - x0) * (sung - idx)
                            }
                        }
                        val top = l.getLineTop(line)
                        val bottom = l.getLineBottom(line)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Black,
                                1f to Color.Transparent,
                                startX = fillX - edge / 2f,
                                endX = fillX + edge / 2f
                            ),
                            topLeft = Offset(0f, top),
                            size = androidx.compose.ui.geometry.Size(size.width, bottom - top),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        )
    }
}

