package com.lm.player.feature.player.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 「专辑卡片」「全屏封面」两个主题与主界面左侧播放卡片之间的过渡（2026-11-22，用户选 A 完整版）：
 *
 * - 退出播放页：播放页里的卡片先**收拢**成正在播放的那一张，这张卡再一边飞向主界面左侧播放卡片的位置、
 *   一边绕竖轴**翻面**；翻到侧面最薄的那一刻换成背面 —— 背面就是主界面那张播放卡片本身，继续翻正、
 *   长到卡片大小，落进卡槽时弹簧轻弹一下（磁吸落位）。
 * - 打开播放页：倒着放。
 * - 手势：播放页里往下拖、主界面卡片往右 / 往上拉，整套动作跟手；松手按距离和速度决定吸回还是展开。
 *
 * 一个进度 [progress] 驱动全部画面：0 = 卡片在主界面卡槽；[FLIGHT_END] = 卡片在播放页里自己的位置、
 * 其余卡片收拢在它后面；1 = 播放页完全展开。弹簧回弹时会略超出 0 / 1。
 * 前半程（播放页那张卡翻到侧面之前）由播放页主题画，后半程由主界面那张真正的播放卡片自己翻过来，
 * 所以落地后没有「截图换成真卡片」的跳变。全部只改图层参数，不重组、不重新排版。
 */
object PlayerCardHandoff {
    /** 进度里「翻转飞行」占的前一段；之后是卡片收拢 / 展开 */
    const val FLIGHT_END = 0.6f

    /**
     * 自动播放（点开 / 返回 / 松手后接着走）的整段时长（2026-11-23 用户反馈「太快、不像黑胶那样线性」）：
     * 进度按时间匀速走，两段（收拢 / 展开、翻转飞行）各自先慢后快再慢，飞行段约 0.6 秒，与黑胶飞入相当
     */
    private const val FULL_DURATION_MS = 1050
    private const val FULL_DURATION_SMOOTH_MS = 600

    /** 缩放模式（全屏封面放大状态，见 [zoomMode]）是一段连续动作，整段稍短 */
    private const val ZOOM_DURATION_MS = 900
    private const val ZOOM_DURATION_SMOOTH_MS = 520

    /** 从当前进度走到目标要用的时长（毫秒） */
    fun durationMs(delta: Float, smooth: Boolean): Int {
        val full = when {
            zoomMode -> if (smooth) ZOOM_DURATION_SMOOTH_MS else ZOOM_DURATION_MS
            smooth -> FULL_DURATION_SMOOTH_MS
            else -> FULL_DURATION_MS
        }
        return (full * kotlin.math.abs(delta)).toInt().coerceAtLeast(160)
    }

    private val phaseEasing = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** 段内缓动：0～1 之间先慢后快再慢，超出部分（手势边界）保持线性 */
    private fun ease(x: Float): Float = if (x in 0f..1f) phaseEasing.transform(x) else x

    /** 进度到这里以上，播放页背景已完全不透明：主界面可以不画、状态栏可以隐藏 */
    const val COVER_FROM = 0.7f

    /** 0 = 卡片在主界面卡槽，1 = 播放页完全展开（只在 [active] 时有意义） */
    var progress by mutableFloatStateOf(1f)

    /** 这一次播放页是否用这套过渡 */
    var active by mutableStateOf(false)
        private set

    /** 主界面左侧播放卡片在根坐标系里的位置（未做任何图层变换时） */
    @Volatile var homeCardRect: Rect? = null

    /** 播放页里「正在播放那一张卡」在根坐标系里的位置（静止时），由主题上报 */
    @Volatile var playerCardRect: Rect? = null

    /**
     * 直接飞回模式（「全屏封面」主题专用，2026-11-23 用户在五个方案里选 A「一镜到底」）：不收拢、不翻面，
     * 主界面卡片原地不动，播放页的封面直接飞回 / 缩回主界面叠卡里对应的那张；打开时反过来。
     * - 封面放大铺满时（[zoomExpanded]）：铺满的封面连续缩进最前面那张（显示范围从横向中间一条连续变成整张方图、
     *   圆角慢慢出现），后两张在主界面原地露出来；
     * - 正常状态：右侧方卡和后面两张卡各自飞回叠卡里对应的那张，后两张稍晚起步。
     * 每次开始过渡时由播放页决定。
     */
    var zoomMode by mutableStateOf(false)

    /** 直接飞回模式下，封面此刻是放大铺满的 */
    var zoomExpanded by mutableStateOf(false)

    /** 主界面卡片里最前面那张封面在根坐标系里的位置（叠卡主图时上报，离开时清空） */
    @Volatile var homeCoverRect: Rect? = null

    /** 主界面叠卡每往后一张错开的距离（像素）与后面实际有几张（HomeStackHero 在组合时写入） */
    @Volatile var homeStackStepPx: Float = 0f
    @Volatile var homeBehindCount: Int = 0

    /** 主界面叠卡后面两张各是哪首歌（对不上就不飞过去，原地淡掉） */
    val homeBehindIds: Array<String?> = arrayOfNulls(2)

    /**
     * 主界面叠卡后面第 [depth] 张（1 = 紧挨着的下一首，2 = 再下一首）在根坐标系里的位置：
     * 与 HomeStackHero 的摆法一致（往右上错开、每张小 8%）。那张不是 [songId] 这首歌时返回 null
     */
    fun homeBehindRect(depth: Int, songId: String): Rect? {
        val f = homeCoverRect ?: return null
        if (depth < 1 || depth > homeBehindCount) return null
        if (homeBehindIds[depth - 1] != songId) return null
        val s = homeStackStepPx
        val size = f.width * (1f - 0.08f * depth)
        return Rect(
            center = androidx.compose.ui.geometry.Offset(f.center.x + depth * s, f.center.y - depth * s * 0.55f),
            radius = size / 2f
        )
    }

    /** 主界面叠卡后面两张的透明度与压暗（与 HomeStackHero 的摆法一致，飞到位时对得上） */
    val homeBehindAlpha = floatArrayOf(0.75f, 0.5f)
    val homeBehindDim = floatArrayOf(0.22f, 0.44f)

    /** 那张封面的圆角（像素） */
    @Volatile var homeCoverRadiusPx: Float = 0f

    /** 主界面卡片正被手指拉出：播放页此时不自己动，等松手 */
    var homeDragging by mutableStateOf(false)
        private set

    /** 主界面卡片拉出后松手：目标（0 = 吸回，1 = 展开）与速度（每秒多少进度） */
    class Release(val target: Float, val velocity: Float)

    var release by mutableStateOf<Release?>(null)

    fun supports(theme: PlayerThemeStyle): Boolean =
        theme == PlayerThemeStyle.IPOD_RETRO || isDirectTheme(theme)

    /**
     * 走「直接飞回」的主题（首页卡片不翻面，播放页的主体直接飞回首页卡片里对应的那一份）：
     * 全屏封面、唱片封套（2026-11-23）
     */
    fun isDirectTheme(theme: PlayerThemeStyle): Boolean =
        theme == PlayerThemeStyle.NETEASE_TV_COVER || theme == PlayerThemeStyle.RECORD_SLEEVE

    /** 胶囊收起（放大）后封面铺满全屏的主题：全屏封面（唱片封套 11-23 定为「歌词内页」，不铺满） */
    fun expandsToFullScreen(theme: PlayerThemeStyle): Boolean =
        theme == PlayerThemeStyle.NETEASE_TV_COVER

    /** 这个主题现在能不能用这套过渡（主界面卡片的位置得先量出来） */
    fun canRun(theme: PlayerThemeStyle): Boolean = supports(theme) && homeCardRect != null

    fun begin(startProgress: Float) {
        progress = startProgress
        active = true
    }

    fun end() {
        active = false
        zoomMode = false
        zoomExpanded = false
        homeDragging = false
        release = null
        progress = 1f
    }

    /** 主界面卡片开始被拉出 */
    fun startHomeDrag() {
        begin(0f)
        // 全屏封面主题走直接飞回；播放页这次会不会直接放大铺满（上次退出时是放大的），与播放页进来时的判断一致
        zoomMode = isDirectTheme(TvPlayerPrefs.theme)
        zoomExpanded = expandsToFullScreen(TvPlayerPrefs.theme) && TvPlayerPrefs.capsuleCollapsed
        release = null
        homeDragging = true
    }

    fun releaseHomeDrag(target: Float, velocity: Float) {
        homeDragging = false
        release = Release(target, velocity)
    }

    /** 主题读的进度：没在过渡时当作完全展开 */
    fun themeProgress(): Float = if (active) progress else 1f

    /** 收拢 / 展开进度：1 = 展开，0 = 收拢（可略超出） */
    fun gather(p: Float): Float = ease((p - FLIGHT_END) / (1f - FLIGHT_END))

    fun smooth(a: Float, b: Float, t: Float): Float {
        val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 播放页背景（模糊底图等）：翻转后半程时已完全透明，主界面卡片翻回来时不被盖住 */
    fun backgroundAlpha(p: Float): Float = smooth(0.28f, 0.62f, p)

    /**
     * 播放页最底下那层纯色底：缩放模式下封面一缩小主界面就要露出来，这层只当作一点压暗（完全展开时照旧不透明）
     */
    fun screenBackgroundAlpha(p: Float): Float = when {
        !zoomMode || p >= 0.999f -> backgroundAlpha(p)
        zoomExpanded -> 0.45f * smooth(0.2f, 1f, p)
        // 正常状态：底色随模糊背景一起淡掉（前半程就露出主界面），最后只剩一点压暗
        else -> maxOf(0.45f * smooth(0.2f, 1f, p), directBackgroundAlpha(p))
    }

    /** 直接飞回模式正常状态下，播放页模糊背景的透明度：封面飞到一半时主界面已经露出来 */
    fun directBackgroundAlpha(p: Float): Float = smooth(0.25f, 0.7f, p)

    /** 播放页此刻是否已完全盖住主界面（主界面可以不画、状态栏可以隐藏） */
    fun coversHome(p: Float): Boolean = if (zoomMode) p >= 0.999f else p >= COVER_FROM

    /** 缩放模式的进度缓动：整段先慢后快再慢 */
    fun zoomEase(p: Float): Float = ease(p.coerceIn(0f, 1f))

    /** 直接飞回进行中：主界面卡片里最前面那张封面先藏起来（由播放页那张在飞 / 缩放的封面代替） */
    fun hidesHomeCover(): Boolean = active && zoomMode && progress > 0.001f

    /** 正常状态的直接飞回进行中：叠卡后面两张也由播放页那两张代替（放大铺满时它们原地不动、被封面盖着） */
    fun hidesHomeBehind(): Boolean = active && zoomMode && !zoomExpanded && progress > 0.001f

    /** 播放页上的文字、歌词、胶囊：收拢一开始就淡出 */
    fun contentAlpha(p: Float): Float = smooth(0.78f, 1f, p)

    /** 收拢在一起的那一下，正在播放的卡轻轻「鼓」一下 */
    fun mergePop(p: Float): Float = 1f + 0.035f * sin(PI.toFloat() * ((p - (FLIGHT_END - 0.05f)) / 0.2f).coerceIn(0f, 1f))

    /** 飞行中这张卡的状态（根坐标） */
    class Flight(
        val cx: Float,
        val cy: Float,
        /** 正面（播放页那张方卡）的边长 */
        val frontSize: Float,
        /** 背面（主界面卡片）相对原尺寸的缩放 */
        val backScale: Float,
        /** 正面朝外时为 true（由播放页画），否则背面朝外（由主界面卡片自己画） */
        val frontVisible: Boolean,
        val frontRotationY: Float,
        val backRotationY: Float
    )

    /**
     * @param home 主界面卡片（静止位置）
     * @param player 播放页那张卡（静止位置）
     */
    fun flightAt(p: Float, home: Rect, player: Rect): Flight {
        val q = ease(min(p / FLIGHT_END, 1f)) // 1 = 在播放页的位置，0 = 在卡槽
        val cx = home.center.x + (player.center.x - home.center.x) * q
        val cy = home.center.y + (player.center.y - home.center.y) * q
        // 0 = 正面朝外，180 = 背面朝外；落位回弹时多翻一点再回正
        val angle = (180f * (1f - q)).coerceIn(0f, 190f)
        val front = (home.width + (player.width - home.width) * q) * mergePop(p)
        // 翻到侧面那一刻，背面的高度与正面边长相同：换面时看不出跳
        val mid = (home.width + player.width) / 2f
        val kk = max(0.05f, 2f * mid / home.height.coerceAtLeast(1f) - 1f)
        val back = 1f + (kk - 1f) * q
        return Flight(
            cx = cx,
            cy = cy,
            frontSize = front,
            backScale = back,
            frontVisible = angle <= 90f,
            frontRotationY = -angle,
            backRotationY = 180f - angle
        )
    }
}
