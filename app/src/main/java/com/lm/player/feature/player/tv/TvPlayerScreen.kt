package com.lm.player.feature.player.tv

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SystemBarsColor
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.DownloadTask
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import com.lm.player.core.model.LyricResult
import com.lm.player.core.model.PlatformMode
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * TV / 车机全屏播放页（2026-11-12 重做，只做横屏）。
 *
 * 三个主题（黑胶唱片 / 专辑卡片 / 全屏封面 + 歌词）各自一个文件，共用底部控制胶囊；
 * 歌词全部用从手机端移植的歌词引擎（[TvLyricsView]）。配色跟随软件的浅色 / 深色主题（[TvPlayerColors]）。
 *
 * 本文件只负责「外壳」：主题切换、胶囊自动收起、各浮层（词 / 更多 / 待播列表 / 下载菜单）的开合、
 * 遥控器按键与返回键分层、触屏点外围收回、下拉关闭。画面延伸到系统状态栏下面（与手机端一致）。
 */
@Composable
fun TvPlayerScreen(
    song: UnifiedSong,
    playlist: List<UnifiedSong>,
    isPlaying: Boolean,
    progressMsProvider: () -> Long,
    totalDurationMs: Long,
    lyrics: LyricResult,
    isShuffle: Boolean,
    isRepeat: Boolean,
    allPlaylists: List<UnifiedPlaylist>,
    isServerConnected: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSongFromQueue: (UnifiedSong) -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit,
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit,
    onCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit,
    onPlayerThemeStyleChange: (PlayerThemeStyle) -> Unit,
    onCoversBackgroundChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** 下载任务流：胶囊上的下载按钮显示当前歌曲的下载进度圈 */
    downloadTasks: StateFlow<List<DownloadTask>>? = null
) {
    val colors = currentTvPlayerColors()
    CompositionLocalProvider(LocalTvPlayerColors provides colors) {
        PlayerShell(
            song, playlist, isPlaying, progressMsProvider, totalDurationMs, lyrics, isShuffle, isRepeat, allPlaylists,
            isServerConnected, onTogglePlayPause, onNext, onPrevious, onSeekTo, onSelectSongFromQueue, onToggleFavorite,
            onDownloadSongWithOptions, onAddToPlaylist, onCreatePlaylistAndAddSong, onPlayerThemeStyleChange,
            onCoversBackgroundChanged, onDismiss, downloadTasks
        )
    }
}

@Composable
private fun PlayerShell(
    song: UnifiedSong,
    playlist: List<UnifiedSong>,
    isPlaying: Boolean,
    progressMsProvider: () -> Long,
    totalDurationMs: Long,
    lyrics: LyricResult,
    isShuffle: Boolean,
    isRepeat: Boolean,
    allPlaylists: List<UnifiedPlaylist>,
    isServerConnected: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSongFromQueue: (UnifiedSong) -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit,
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit,
    onCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit,
    onPlayerThemeStyleChange: (PlayerThemeStyle) -> Unit,
    onCoversBackgroundChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    downloadTasks: StateFlow<List<DownloadTask>>?
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    remember { TvPlayerPrefs.ensureLoaded(context); TvLyricSettings.ensureLoaded(context) }
    val theme = TvPlayerPrefs.theme
    val isCardTheme = theme == PlayerThemeStyle.IPOD_RETRO
    // 触屏手势处理器只创建一次，读主题要走这个最新值
    val cardThemeNow = rememberUpdatedState(isCardTheme)
    val isCar = LocalPlatformMode.current == PlatformMode.CAR

    // ---------------- 浮层状态 ----------------
    var lyricsPanel by remember { mutableIntStateOf(LyricsPanel.CLOSED) }
    var moreOpen by remember { mutableStateOf(false) }
    var moreSubMenuOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var downloadMenuOpen by remember { mutableStateOf(false) }
    val anyOverlay = lyricsPanel != LyricsPanel.CLOSED || moreOpen || queueOpen || downloadMenuOpen
    // 弹出窗口（下载 / 歌单 / 定时 / 音质菜单）在独立窗口里，打开期间本页没有焦点属于正常
    val popupWindowOpen = downloadMenuOpen || moreSubMenuOpen

    // ---------------- 胶囊自动收起（所有主题：5 秒无操作缩成底部的细进度胶囊） ----------------
    // controlsVisible：true = 展开的控制胶囊，false = 收起成「时间 ━━ 总时长」
    // 上次退出时是放大（胶囊收起）状态，这次进来直接保持放大
    var controlsVisible by remember { mutableStateOf(!TvPlayerPrefs.capsuleCollapsed) }
    // 返回键把胶囊收起只是「退出前的一步」，不算用户选择了放大：退出时不记成放大
    var collapsedByBack by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible) { if (controlsVisible) collapsedByBack = false }
    // 按返回收起后又停留了一会儿（没接着退出），就当作用户选择了放大
    LaunchedEffect(collapsedByBack) { if (collapsedByBack) { delay(4000L); collapsedByBack = false } }
    val collapsedForNextEntry = rememberUpdatedState(!controlsVisible && !collapsedByBack)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { TvPlayerPrefs.updateCapsuleCollapsed(collapsedForNextEntry.value) }
    }
    var interactionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(interactionTick, controlsVisible, anyOverlay) {
        if (controlsVisible && !anyOverlay) {
            delay(5000L)
            controlsVisible = false
        }
    }
    fun touchInteraction() {
        interactionTick++
        if (!controlsVisible) controlsVisible = true
    }

    // ---------------- 提示：统一走全软件的顶部气泡（AppToast） ----------------
    fun showHud(text: String) {
        com.lm.player.core.designsystem.component.AppToast.show(context, text)
    }

    // ---------------- 焦点 ----------------
    val capsuleFocus = remember { CapsuleFocus() }
    val stageFocus = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }
    var seekFocused by remember { mutableStateOf(false) }
    // 从唱片 / 封面 / 唱片架按「下」进入胶囊：胶囊展开后把焦点放到它的第一个按钮
    var pendingCapsuleFocus by remember { mutableStateOf(false) }
    fun focus(r: FocusRequester) { try { r.requestFocus() } catch (_: Exception) {} }
    /**
     * 没有浮层时焦点应该在的地方（2026-11-13 三个主题统一）：画面本身 —— 黑胶唱片 / 全屏封面 / 专辑卡片的唱片架。
     * 左右键切歌、确定键播放暂停、下键进胶囊；胶囊收起时同样如此（左右切歌不会展开胶囊）。
     */
    val primaryFocus = stageFocus

    LaunchedEffect(controlsVisible, theme) {
        if (isCar) return@LaunchedEffect
        delay(80L)
        when {
            controlsVisible && pendingCapsuleFocus -> { pendingCapsuleFocus = false; focus(capsuleFocus.favorite) }
            controlsVisible -> if (!anyOverlay) focus(primaryFocus)
            else -> focus(stageFocus)
        }
    }
    // 焦点防丢：没有浮层时焦点意外丢失，拉回主焦点（弹出窗口打开期间不拉）
    LaunchedEffect(pageHasFocus, anyOverlay, popupWindowOpen, controlsVisible, theme) {
        if (!isCar && !pageHasFocus && !popupWindowOpen) {
            delay(150L)
            if (!pageHasFocus && !popupWindowOpen && lyricsPanel == LyricsPanel.CLOSED && !moreOpen) {
                focus(primaryFocus)
            }
        }
    }

    // ---------------- 状态 ----------------
    var localFavorite by remember(song.id, song.isFavorite) { mutableStateOf(song.isFavorite) }
    // 下载状态即时刷新：在播放页里下载完成，下载按钮立刻变成「已下载」
    val liveSong = com.lm.player.core.media.SongStatusStore.rememberLive(song)
    // 性能优化第 6 项：文件是否存在放到 IO 线程核实，界面先用上次结果
    val isDownloaded = com.lm.player.feature.home.rememberLocalFileExists(liveSong.localFilePath)
    val activeTimerMinutes by PlaybackQueueManager.sleepTimerMinutesFlow.collectAsState()
    val sleepRemainingSec by PlaybackQueueManager.sleepTimerRemainingSecFlow.collectAsState()
    val quality = rememberQualityInfo(song)
    val duration = if (totalDurationMs > 0L) totalDurationMs else song.durationMs.coerceAtLeast(0L)

    val cyclePlayMode: () -> Unit = {
        when {
            !isShuffle && !isRepeat -> {
                PlaybackQueueManager.setShuffle(true); PlaybackQueueManager.setRepeat(false)
                showHud("已切换为：随机播放")
            }
            isShuffle -> {
                PlaybackQueueManager.setShuffle(false); PlaybackQueueManager.setRepeat(true)
                showHud("已切换为：单曲循环")
            }
            else -> {
                PlaybackQueueManager.setShuffle(false); PlaybackQueueManager.setRepeat(false)
                showHud("已切换为：列表循环")
            }
        }
    }
    val toggleFavorite: () -> Unit = {
        localFavorite = !localFavorite
        onToggleFavorite()
        showHud(if (localFavorite) "已收藏" else "已取消收藏")
    }
    val selectTheme: (PlayerThemeStyle) -> Unit = { t ->
        if (t != theme) {
            TvPlayerPrefs.updateTheme(t)
            onPlayerThemeStyleChange(t)
            showHud("已切换主题：${t.displayName}")
        }
        controlsVisible = true
        interactionTick++
    }

    var closing by remember { mutableStateOf(false) }

    // ---------------- 与主界面卡片之间的收拢 / 翻转过渡（专辑卡片 / 全屏封面，2026-11-22） ----------------
    // 进来时决定：主题支持、主界面卡片的位置已量出 → 用这套过渡（MainActivity 同样的条件下关掉了外层进出场动画）。
    // 主界面卡片被手指拉出时已经开始了（进度由手指给），这里不再从 0 开始
    /** 按此刻的样子选过渡：全屏封面主题 → 直接飞回（封面铺满着就是缩放）；专辑卡片 → 收拢 + 翻转（2026-11-23） */
    fun chooseHandoffMode() {
        val t = TvPlayerPrefs.theme
        // 全屏封面 / 唱片封套 / 唱机唱臂：直接飞回（首页卡片不翻面）；封面铺满全屏的那两个在放大时走缩放
        PlayerCardHandoff.zoomMode = PlayerCardHandoff.isDirectTheme(t)
        PlayerCardHandoff.zoomExpanded = PlayerCardHandoff.expandsToFullScreen(t) && !controlsVisible
    }
    remember {
        if (PlayerCardHandoff.canRun(theme)) {
            if (!PlayerCardHandoff.active) PlayerCardHandoff.begin(0f)
            chooseHandoffMode()
        } else if (PlayerCardHandoff.active) {
            PlayerCardHandoff.end()
        }
        Unit
    }
    val handoffScope = androidx.compose.runtime.rememberCoroutineScope()
    val handoffJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    /** 正在收回主界面卡片（返回键、下拖松手、拉出后又松回去） */
    var handoffClosing by remember { mutableStateOf(false) }
    val dismissForHandoff by rememberUpdatedState(onDismiss)
    /**
     * 带到目标：1 = 展开，0 = 收回主界面（到了才真正关闭）；可从任意进度接着走（中途反向、松手后也是它）。
     * 2026-11-23：由弹簧改为按时间匀速推进进度（剩下多少走多久），两段各自的缓动在 [PlayerCardHandoff] 里做，
     * 整体更慢、更连贯（用户反馈弹簧版太快）
     */
    @Suppress("UNUSED_PARAMETER")
    fun runHandoff(target: Float, velocity: Float = 0f) {
        handoffJob[0]?.cancel()
        handoffJob[0] = handoffScope.launch {
            val start = PlayerCardHandoff.progress
            val duration = PlayerCardHandoff.durationMs(target - start, com.lm.player.core.designsystem.theme.SmoothMode.isActive)
            androidx.compose.animation.core.animate(
                start, target,
                animationSpec = tween(duration, easing = androidx.compose.animation.core.LinearEasing)
            ) { v, _ ->
                PlayerCardHandoff.progress = v
            }
            if (target <= 0f) dismissForHandoff()
        }
    }
    LaunchedEffect(Unit) {
        if (PlayerCardHandoff.active && !PlayerCardHandoff.homeDragging && PlayerCardHandoff.release == null &&
            PlayerCardHandoff.progress < 1f
        ) runHandoff(1f)
    }
    // 主界面卡片拉出后松手：接着展开或吸回
    LaunchedEffect(Unit) {
        snapshotFlow { PlayerCardHandoff.release }.collect { r ->
            if (r != null) {
                PlayerCardHandoff.release = null
                handoffClosing = r.target <= 0f
                runHandoff(r.target, r.velocity)
            }
        }
    }
    // 在播放页里换了主题：换到这两个主题就接上（已完全展开），换走就结束
    LaunchedEffect(theme) {
        if (PlayerCardHandoff.supports(theme)) {
            if (!PlayerCardHandoff.active && PlayerCardHandoff.homeCardRect != null) {
                PlayerCardHandoff.begin(1f)
                PlayerCardHandoff.zoomMode = false
                PlayerCardHandoff.zoomExpanded = false
            }
        } else if (PlayerCardHandoff.active) {
            handoffJob[0]?.cancel()
            handoffClosing = false
            PlayerCardHandoff.end()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            handoffJob[0]?.cancel()
            PlayerCardHandoff.end()
        }
    }
    fun startHandoffClose(velocity: Float = 0f) {
        // 从完全展开开始收回时按此刻的样子选：全屏封面放大着 → 缩放模式；否则收拢 + 翻转（中途反向保持原来的模式）
        if (PlayerCardHandoff.progress >= 0.999f) chooseHandoffMode()
        handoffClosing = true
        runHandoff(0f, velocity)
    }

    /**
     * 返回键的固定顺序（2026-11-13 用户要求：只有没有任何二级窗口时，返回才退出播放页）：
     * 1. 弹出窗口（下载 / 加入歌单 / 睡眠定时 / 音质信息菜单）—— 它们在自己的窗口里先收到返回并自行关闭；
     *    万一返回落到了这里，也先关它们；
     * 2. 「词」：子面板 → 竖条 → 收起；
     * 3. 「更多」面板；
     * 4. 待播列表；
     * 5. 展开的控制胶囊 → 收起成细进度胶囊；
     * 6. 以上都没有 → 退出播放页（黑胶主题先让唱片飞回主界面）。
     */
    fun handleBack() {
        interactionTick++
        when {
            // 正在翻进来 / 收拢展开还没到位：直接从当前位置反向收回
            PlayerCardHandoff.active && !handoffClosing && PlayerCardHandoff.progress < 0.999f -> startHandoffClose()
            handoffClosing -> Unit
            downloadMenuOpen -> { downloadMenuOpen = false; focus(capsuleFocus.download) }
            lyricsPanel > LyricsPanel.STRIP -> lyricsPanel = LyricsPanel.STRIP
            lyricsPanel == LyricsPanel.STRIP -> { lyricsPanel = LyricsPanel.CLOSED; focus(capsuleFocus.lyrics) }
            moreOpen -> { moreOpen = false; focus(capsuleFocus.more) }
            queueOpen -> { queueOpen = false; focus(primaryFocus) }
            controlsVisible -> { controlsVisible = false; collapsedByBack = true }
            // 专辑卡片 / 全屏封面：卡片收拢，再翻回主界面左侧播放卡片（到位后才真正关闭）
            PlayerCardHandoff.active -> startHandoffClose()
            // 黑胶主题：先让唱片飞回主界面小黑胶的位置（主题里做完后回调真正关闭）
            theme == PlayerThemeStyle.MODERN && !closing -> closing = true
            closing -> Unit
            else -> onDismiss()
        }
    }
    // Android 13+（清单开启了 enableOnBackInvokedCallback）返回键不再作为按键事件下发，只能走 BackHandler
    BackHandler(enabled = true) { handleBack() }

    // ---------------- 下拉关闭（触屏） ----------------
    // 松手超过阈值：页面继续往下滑出屏幕，滑到底再关闭（以前是先弹回原位再由外层退场，看起来像被「拽回去」）
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var swipeDismissing by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val animatedOffsetY by animateFloatAsState(
        dragOffsetY,
        animationSpec = if (swipeDismissing) tween(240) else androidx.compose.animation.core.spring(),
        label = "player_drag",
        finishedListener = { if (swipeDismissing) currentDismiss() }
    )
    // 黑胶主题按返回：唱片飞回主界面的同时，页面其余部分淡出、露出主界面
    val closeFade by animateFloatAsState(
        if (closing) 0f else 1f,
        tween(if (closing) 420 else 0),
        label = "player_close_fade"
    )
    // 黑胶主题手指下滑 = 收回：唱片跟着手指飞回主界面的小黑胶（0 = 原位，1 = 已回到小黑胶），页面同时淡出
    val recallModeNow = rememberUpdatedState(theme == PlayerThemeStyle.MODERN && VinylHandoff.sourceRect != null)
    var recallTarget by remember { mutableFloatStateOf(0f) }
    val recallAnim by animateFloatAsState(
        recallTarget,
        animationSpec = androidx.compose.animation.core.spring(stiffness = 900f),
        label = "vinyl_recall"
    )
    val currentCovers by rememberUpdatedState(onCoversBackgroundChanged)
    LaunchedEffect(Unit) {
        snapshotFlow {
            animatedOffsetY < 0.5f && !closing && recallAnim < 0.001f &&
                // 与主界面卡片之间过渡时：背景完全不透明之后才算盖住主界面
                (!PlayerCardHandoff.active || PlayerCardHandoff.coversHome(PlayerCardHandoff.progress))
        }.collect { currentCovers(it) }
    }

    // 状态栏透明（画面延伸到它下面），图标深浅随播放页配色
    SystemBarsColor(statusBar = colors.background, navigationBar = colors.background)

    // 浮层区域（根坐标），用于「点外围收回」
    val stripBounds = remember { arrayOfNulls<Rect>(1) }
    val moreBounds = remember { arrayOfNulls<Rect>(1) }
    val queueBounds = remember { arrayOfNulls<Rect>(1) }
    var lyricsAnchorX by remember { mutableFloatStateOf(0f) }
    var moreAnchorX by remember { mutableFloatStateOf(0f) }

    val capsuleBottom = dims.scale(22.dp)
    // 主题底部为胶囊留出的空间：展开时约为胶囊高度，收起成细进度胶囊后只留一条
    val expandedSpace = dims.scale(116.dp)
    val controlsSpace = if (controlsVisible) capsuleBottom + expandedSpace else capsuleBottom + dims.scale(52.dp)
    // 胶囊上沿在根坐标系里的位置（「词」竖条与「更多」面板贴着它向上展开）
    var capsuleTopPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffsetY.roundToInt().coerceAtLeast(0)) }
            .drawBehind {
                drawRect(
                    colors.background,
                    alpha = closeFade * (1f - recallAnim) * PlayerCardHandoff.screenBackgroundAlpha(PlayerCardHandoff.themeProgress())
                )
            }
            .onFocusChanged { pageHasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                interactionTick++
                val isBack = native.keyCode == KeyEvent.KEYCODE_BACK || native.keyCode == KeyEvent.KEYCODE_ESCAPE
                // 胶囊收起时：左右 / 确定照常交给画面（切歌、播放暂停，不展开胶囊）；只有按「下」展开胶囊并进入
                if (!controlsVisible && !isBack && native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    pendingCapsuleFocus = true
                    controlsVisible = true
                    return@onPreviewKeyEvent true
                }
                when (native.keyCode) {
                    KeyEvent.KEYCODE_MENU -> {
                        queueOpen = !queueOpen
                        lyricsPanel = LyricsPanel.CLOSED
                        moreOpen = false
                        if (!queueOpen) focus(primaryFocus)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { onPrevious(); showHud("上一首"); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { onNext(); showHud("下一首"); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        val willPlay = !isPlaying
                        onTogglePlayPause()
                        showHud(if (willPlay) "继续播放" else "已暂停")
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { handleBack(); true }
                    else -> false
                }
            }
            // 触屏：任何按下都算一次操作（展开胶囊）；浮层展开时按在浮层外 → 收回并吃掉这次手势
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 胶囊收起后：滑动 / 点唱片、封面、卡片都不展开胶囊，只有点细进度胶囊才展开（三个主题统一）
                    if (!controlsVisible) interactionTick++ else touchInteraction()
                    val p = down.position
                    // 正在收回主界面卡片时点一下：从当前位置反向，重新展开（卡片已快落进卡槽时不再拦）
                    if (handoffClosing && PlayerCardHandoff.active && PlayerCardHandoff.progress > 0.3f) {
                        handoffClosing = false
                        runHandoff(1f)
                        down.consume()
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { it.consume() }
                            if (e.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }
                    var dismissed = false
                    if (lyricsPanel != LyricsPanel.CLOSED && stripBounds[0]?.contains(p) != true) {
                        lyricsPanel = LyricsPanel.CLOSED; dismissed = true
                    } else if (moreOpen && !moreSubMenuOpen && moreBounds[0]?.contains(p) != true) {
                        moreOpen = false; dismissed = true
                    } else if (queueOpen && queueBounds[0]?.contains(p) != true) {
                        queueOpen = false; dismissed = true
                    }
                    if (dismissed) {
                        down.consume()
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { it.consume() }
                            if (e.changes.none { it.pressed }) break
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var recallDragPx = 0f
                // 专辑卡片 / 全屏封面：往下拖 = 卡片收拢、再翻回主界面卡片，跟着手指走（2026-11-22）
                var handoffDrag = false
                var handoffDragPx = 0f
                val handoffTracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        recallDragPx = 0f
                        handoffDrag = PlayerCardHandoff.active && !handoffClosing && PlayerCardHandoff.progress > 0.98f
                        if (handoffDrag) {
                            handoffJob[0]?.cancel()
                            chooseHandoffMode()
                            handoffDragPx = 0f
                            handoffTracker.resetTracking()
                        }
                    },
                    onVerticalDrag = { change, amount ->
                        if (handoffDrag) {
                            handoffDragPx = (handoffDragPx + amount).coerceAtLeast(0f)
                            PlayerCardHandoff.progress = (1f - handoffDragPx / (size.height * 0.8f)).coerceIn(0f, 1f)
                            handoffTracker.addPosition(change.uptimeMillis, change.position)
                        } else if (recallModeNow.value && !closing) {
                            // 黑胶：页面不跟着往下走，而是唱片跟着手指收回主界面小黑胶、页面同时淡出
                            recallDragPx = (recallDragPx + amount).coerceAtLeast(0f)
                            recallTarget = (recallDragPx / (size.height * 0.45f)).coerceIn(0f, 1f)
                        } else if (amount > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + amount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (handoffDrag) {
                            handoffDrag = false
                            val vp = (-handoffTracker.calculateVelocity().y / (size.height * 0.8f)).coerceIn(-8f, 8f)
                            // 拉过约两成（屏高的六分之一）或往下一甩：吸回主界面；不够就重新展开
                            if (PlayerCardHandoff.progress < 0.8f || vp < -1.2f) startHandoffClose(vp) else runHandoff(1f, vp)
                        } else if (recallModeNow.value && recallTarget > 0f) {
                            // 拉过三成：接着飞回去（与遥控器返回键同一段收回动画）；不够就弹回
                            if (recallTarget > 0.3f) closing = true else recallTarget = 0f
                        } else if (dragOffsetY > with(density) { 160.dp.toPx() }) {
                            // 其它主题：继续往下滑出屏幕，滑到底（finishedListener）再关闭
                            swipeDismissing = true
                            dragOffsetY = size.height.toFloat()
                        } else {
                            dragOffsetY = 0f
                        }
                    },
                    onDragCancel = {
                        if (handoffDrag) {
                            handoffDrag = false
                            runHandoff(1f)
                        }
                        dragOffsetY = 0f
                        if (!closing) recallTarget = 0f
                    }
                )
            }
    ) {
        val screenW = maxWidth
        val rootH = maxHeight
        TvPlayerThemeContent(
            theme = theme,
            input = TvThemeInput(
                song = song,
                playlist = playlist,
                lyrics = lyrics,
                isPlaying = isPlaying,
                positionMsProvider = progressMsProvider,
                onSeekTo = onSeekTo,
                onSelectSong = { s -> onSelectSongFromQueue(s) },
                onTogglePlay = {
                    val willPlay = !isPlaying
                    onTogglePlayPause()
                    showHud(if (willPlay) "继续播放" else "已暂停")
                },
                onPrevious = { onPrevious(); showHud("上一首") },
                onNext = { onNext(); showHud("下一首") },
                controlsSpace = controlsSpace,
                controlsExpanded = controlsVisible,
                stageFocus = stageFocus,
                onStageDown = {
                    if (controlsVisible) focus(capsuleFocus.favorite) else {
                        pendingCapsuleFocus = true
                        controlsVisible = true
                    }
                },
                lyricsPaneOpen = TvPlayerPrefs.cardLyricsOpen,
                onToggleLyricsPane = { TvPlayerPrefs.updateCardLyricsOpen(!TvPlayerPrefs.cardLyricsOpen) },
                animationsActive = true,
                closing = closing || handoffClosing,
                onCloseAnimationDone = { onDismiss() },
                recallProgress = { recallAnim }
            )
        )

        // ---------------- 控制胶囊（底部居中） ----------------
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = capsuleBottom)
                .onGloballyPositioned { capsuleTopPx = it.boundsInRoot().top }
                .graphicsLayer {
                    alpha = closeFade * (1f - recallAnim) * PlayerCardHandoff.contentAlpha(PlayerCardHandoff.themeProgress())
                }
        ) {
            TvPlayerCapsule(
                expanded = controlsVisible,
                // 「歌词展开 / 收起」只有专辑卡片有（其它主题的歌词一直显示）
                showLyricsPaneToggle = isCardTheme,
                onExpand = { touchInteraction() },
                songId = song.id,
                isFavorite = localFavorite,
                isDownloaded = isDownloaded,
                downloadTasks = downloadTasks,
                isShuffle = isShuffle,
                isRepeat = isRepeat,
                progressMsProvider = progressMsProvider,
                durationMs = duration,
                lyricsOpen = lyricsPanel != LyricsPanel.CLOSED,
                moreOpen = moreOpen,
                queueOpen = queueOpen,
                lyricsPaneOpen = TvPlayerPrefs.cardLyricsOpen,
                focus = capsuleFocus,
                stageFocus = stageFocus,
                onSeekTo = onSeekTo,
                onCyclePlayMode = cyclePlayMode,
                onToggleFavorite = toggleFavorite,
                onDownload = {
                    lyricsPanel = LyricsPanel.CLOSED; moreOpen = false
                    downloadMenuOpen = true
                },
                onLyrics = {
                    moreOpen = false; queueOpen = false
                    lyricsPanel = if (lyricsPanel == LyricsPanel.CLOSED) LyricsPanel.STRIP else LyricsPanel.CLOSED
                },
                onQueue = {
                    lyricsPanel = LyricsPanel.CLOSED; moreOpen = false
                    queueOpen = !queueOpen
                },
                onMore = {
                    lyricsPanel = LyricsPanel.CLOSED; queueOpen = false
                    moreOpen = !moreOpen
                },
                onToggleLyricsPane = { TvPlayerPrefs.updateCardLyricsOpen(!TvPlayerPrefs.cardLyricsOpen) },
                onLyricsAnchor = { lyricsAnchorX = it },
                onMoreAnchor = { moreAnchorX = it },
                onSeekFocusChange = { seekFocused = it },
                downloadMenu = {
                    TvDownloadPopup(
                        expanded = downloadMenuOpen,
                        onDismissRequest = { downloadMenuOpen = false; focus(capsuleFocus.download) },
                        // 用带最新下载状态的歌曲：本地升级要读到当前的本地文件
                        song = liveSong,
                        isServerConnected = isServerConnected,
                        hasLocal = isDownloaded,
                        hasServer = com.lm.player.core.designsystem.component.isSongOnLemonServer(liveSong, isServerConnected),
                        onConfirm = { target, q ->
                            downloadMenuOpen = false
                            onDownloadSongWithOptions(song, target, q)
                            focus(capsuleFocus.download)
                        }
                    )
                }
            )
        }

        // 面板底边距根布局底边的距离 = 根高度 - 胶囊上沿 + 间隙
        fun aboveCapsulePx(d: Density): Int = with(d) {
            (rootH.toPx() - capsuleTopPx + dims.scale(16.dp).toPx()).roundToInt()
        }

        // ---------------- 「词」竖条（锚在「词」按钮正上方，向上长） ----------------
        val stripW = dims.scale(60.dp)
        val wrapperW = dims.scale(300.dp)
        val wrapperH = dims.scale(420.dp)
        AnimatedVisibility(
            visible = lyricsPanel != LyricsPanel.CLOSED && controlsVisible,
            enter = fadeIn(tween(160)) + expandVertically(tween(260), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Bottom),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    val right = lyricsAnchorX + stripW.toPx() / 2f
                    IntOffset((right - wrapperW.toPx()).roundToInt(), -aboveCapsulePx(this))
                }
                .width(wrapperW)
                .height(wrapperH)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                TvLyricsAdjustStrip(
                    panel = if (lyricsPanel == LyricsPanel.CLOSED) LyricsPanel.STRIP else lyricsPanel,
                    theme = theme,
                    onPanelChange = { lyricsPanel = it },
                    modifier = Modifier.onGloballyPositioned { stripBounds[0] = it.boundsInRoot() }
                )
            }
        }

        // ---------------- 「更多」面板 ----------------
        AnimatedVisibility(
            visible = moreOpen && controlsVisible,
            enter = fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.94f),
            exit = fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    val panelW = dims.scale(560.dp).toPx()
                    val right = (moreAnchorX + dims.scale(40.dp).toPx()).coerceAtMost(screenW.toPx() - 16.dp.toPx())
                    IntOffset((right - panelW).roundToInt().coerceAtLeast(16.dp.roundToPx()), -aboveCapsulePx(this))
                }
                .wrapContentSize()
        ) {
            TvMorePanel(
                song = song,
                theme = theme,
                quality = quality,
                allPlaylists = allPlaylists,
                isServerConnected = isServerConnected,
                activeTimerMinutes = activeTimerMinutes,
                sleepRemainingSec = sleepRemainingSec,
                // 收藏 / 下载已放到胶囊上（三个主题统一），「更多」里不再重复
                playbackExtras = null,
                onSelectTheme = selectTheme,
                onSelectTimer = { min ->
                    PlaybackQueueManager.setSleepTimer(min, context)
                    val msg = when {
                        min > 0 -> "已设置：${min}分钟后停止播放"
                        min == -1 -> "已设置：播完当前曲目后停止"
                        else -> "已关闭定时器"
                    }
                    com.lm.player.core.designsystem.component.AppToast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                onAddToPlaylist = onAddToPlaylist,
                onCreatePlaylistAndAdd = onCreatePlaylistAndAddSong,
                onSubMenuOpenChange = { moreSubMenuOpen = it },
                modifier = Modifier.onGloballyPositioned { moreBounds[0] = it.boundsInRoot() }
            )
        }

        // ---------------- 待播列表（右侧抽屉） ----------------
        AnimatedVisibility(
            visible = queueOpen,
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(160)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(screenW * 0.34f)
        ) {
            TvQueueDrawer(
                playlist = playlist,
                currentSongId = song.id,
                onSelect = { s ->
                    onSelectSongFromQueue(s)
                    showHud("正在播放：${s.title}")
                },
                modifier = Modifier.onGloballyPositioned { queueBounds[0] = it.boundsInRoot() }
            )
        }

    }
}
