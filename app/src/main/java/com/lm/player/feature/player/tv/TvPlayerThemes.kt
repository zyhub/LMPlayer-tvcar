package com.lm.player.feature.player.tv

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.model.PlatformMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.model.LyricResult
import com.lm.player.core.model.UnifiedSong

/**
 * 三个主题共用的输入。进度以取数函数传入，主题本身不在组合期读取进度。
 * @param controlsSpace 底部留给控制胶囊的高度（胶囊收起成进度条时变小，歌词区随之向下舒展）
 * @param controlsExpanded 控制胶囊是否展开（黑胶主题据此在「唱片居左」与「放大到最左边」之间切换）
 */
class TvThemeInput(
    val song: UnifiedSong,
    val playlist: List<UnifiedSong>,
    val lyrics: LyricResult,
    val isPlaying: Boolean,
    val positionMsProvider: () -> Long,
    val onSeekTo: (Long) -> Unit,
    val onSelectSong: (UnifiedSong) -> Unit,
    val onTogglePlay: () -> Unit,
    /** 黑胶 / 全屏封面：在唱片或封面上左右滑动、遥控器左右键切歌 */
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val controlsSpace: Dp,
    val controlsExpanded: Boolean,
    /** 唱片 / 封面 / 唱片架的遥控器焦点（三个主题都有）；按「下」时交给胶囊 */
    val stageFocus: FocusRequester,
    val onStageDown: () -> Unit,
    /** 专辑卡片：右侧歌词展开 / 收起 */
    val lyricsPaneOpen: Boolean,
    val onToggleLyricsPane: () -> Unit,
    /** 本页被其它界面盖住时为 false（逐字流光停止订阅动画时钟） */
    val animationsActive: Boolean,
    /** 正在关闭：黑胶主题先把唱片飞回主界面小黑胶的位置，完成后调用 [onCloseAnimationDone] */
    val closing: Boolean = false,
    val onCloseAnimationDone: () -> Unit = {},
    /** 黑胶主题手指下滑收回的进度（0 = 原位，1 = 已回到主界面小黑胶）；只在图层 / 绘制阶段读取 */
    val recallProgress: () -> Float = { 0f }
)

/**
 * 黑胶唱片 / 全屏封面的「舞台」操作（2026-11-13，与专辑卡片的唱片架同一套）：
 * - 触屏：在唱片 / 封面上左右滑动切上一首 / 下一首，点一下播放 / 暂停；
 * - 遥控器：舞台是一个焦点，左右键切歌（胶囊收起时也不展开胶囊）、确定键播放 / 暂停、下键进胶囊。
 */
@Composable
internal fun Modifier.playerStage(input: TvThemeInput, onFocusChange: (Boolean) -> Unit = {}): Modifier {
    val cur by rememberUpdatedState(input)
    val isCar = LocalPlatformMode.current == PlatformMode.CAR
    val swipePx = with(LocalDensity.current) { 56.dp.toPx() }
    return this
        .focusRequester(input.stageFocus)
        .focusProperties { up = androidx.compose.ui.focus.FocusRequester.Cancel }
        .onFocusChanged { onFocusChange(it.isFocused) }
        .onKeyEvent { event ->
            val native = event.nativeKeyEvent
            val down = native.action == KeyEvent.ACTION_DOWN
            when (native.keyCode) {
                // 按住不放不连跳：只认第一下
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down && native.repeatCount == 0) cur.onPrevious(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down && native.repeatCount == 0) cur.onNext(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (native.action == KeyEvent.ACTION_UP) cur.onTogglePlay()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) cur.onStageDown(); true }
                KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }
        .focusable(enabled = !isCar)
        .pointerInput(Unit) { detectTapGestures { cur.onTogglePlay() } }
        .pointerInput(Unit) {
            var acc = 0f
            detectHorizontalDragGestures(
                onDragStart = { acc = 0f },
                onDragEnd = {
                    when {
                        acc <= -swipePx -> cur.onNext()
                        acc >= swipePx -> cur.onPrevious()
                    }
                },
                onDragCancel = { acc = 0f }
            ) { change, dx ->
                change.consume()
                acc += dx
            }
        }
}

/**
 * 唱片 / 封面中心的「播放 / 暂停」浮标（2026-11-13，与专辑卡片同款）：进页面、播放状态变化、切歌时出现，
 * 2 秒后淡出。只是提示，不接收点击（点唱片 / 封面本身就是播放暂停），所以不会挡住手势。
 * @param allowed 为 false 时不显示（全屏封面放大时不用这个浮标）
 */
@Composable
internal fun StagePlayBadge(isPlaying: Boolean, songId: String, allowed: Boolean, modifier: Modifier = Modifier) {
    val dims = LocalAppDimensions.current
    var show by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(isPlaying, songId) {
        show = true
        kotlinx.coroutines.delay(2000L)
        show = false
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = show && allowed,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
            androidx.compose.animation.scaleIn(initialScale = 0.8f),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)) +
            androidx.compose.animation.scaleOut(targetScale = 0.8f),
        modifier = modifier
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(dims.scale(64.dp))
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape)
                .border(1.2.dp, Color.White.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = if (isPlaying) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(dims.scale(32.dp))
            )
        }
    }
}


/** 系统状态栏高度：播放页画面延伸到状态栏下面，文字与按钮要让开这一段 */
@Composable
fun statusBarTop(): Dp =
    // 设置「播放页隐藏状态栏」开启时状态栏已隐藏，内容不必再让出顶部这一段（2026-11-13）
    if (com.lm.player.core.designsystem.theme.AppUiPrefs.hidePlayerStatusBar) 0.dp
    else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
internal fun ThemeLyrics(input: TvThemeInput, theme: PlayerThemeStyle, placement: LyricPlacement, modifier: Modifier) {
    val dims = LocalAppDimensions.current
    TvLyricsView(
        lyrics = input.lyrics.lines,
        isSynced = input.lyrics.isSynced,
        positionMsProvider = input.positionMsProvider,
        isPlaying = input.isPlaying,
        style = TvPlayerPrefs.styleFor(theme),
        placement = placement,
        fontSizeSp = TvPlayerPrefs.fontFor(theme) * dims.fontScale,
        lyricsOffsetMs = TvPlayerPrefs.offsetMs,
        activeColor = LocalTvPlayerColors.current.text,
        onSeekToLyric = input.onSeekTo,
        animationsActive = input.animationsActive,
        modifier = modifier
    )
}

/** 主题与对应界面的映射（未知主题回到黑胶） */
@Composable
fun TvPlayerThemeContent(theme: PlayerThemeStyle, input: TvThemeInput) {
    when (theme) {
        PlayerThemeStyle.IPOD_RETRO -> TvCardFlowTheme(input)
        PlayerThemeStyle.NETEASE_TV_COVER -> TvFullCoverTheme(input)
        PlayerThemeStyle.RECORD_SLEEVE -> TvSleeveTheme(input)
        else -> TvVinylTheme(input)
    }
}

/** 主界面黑胶卡里的 5 行歌词（同一套引擎，窄而居中） */
@Composable
fun TvCardLyrics(
    lyrics: LyricResult,
    positionMsProvider: () -> Long,
    isPlaying: Boolean,
    fontSizeSp: Float,
    activeColor: Color,
    animationsActive: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否逐字流光：跟随「主界面黑胶旋转」开关（关掉后主界面播放时基本不重画） */
    karaokeAllowed: Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    remember { TvPlayerPrefs.ensureLoaded(context) }
    // 被全屏播放页盖住时（animationsActive = false）停在盖住那一刻的歌词和那一行（2026-11-13）：
    // 播放页里切歌时不再为看不见的卡片重排整首歌词；播放页关上、卡片露出来时再换成当前歌曲
    val frozenLyrics = remember(animationsActive) { lyrics }
    val frozenPositionMs = remember(animationsActive) {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { positionMsProvider() }
    }
    val frozenProvider = remember(frozenPositionMs) { { frozenPositionMs } }
    val shownLyrics = if (animationsActive) lyrics else frozenLyrics
    TvLyricsView(
        lyrics = shownLyrics.lines,
        isSynced = shownLyrics.isSynced,
        positionMsProvider = if (animationsActive) positionMsProvider else frozenProvider,
        isPlaying = isPlaying,
        // 卡片太窄放不下两种样式的差别：跟随黑胶主题的样式
        style = TvPlayerPrefs.styleFor(PlayerThemeStyle.MODERN),
        placement = LyricPlacement.CARD,
        fontSizeSp = fontSizeSp,
        lyricsOffsetMs = TvPlayerPrefs.offsetMs,
        activeColor = activeColor,
        onSeekToLyric = onSeekTo,
        animationsActive = animationsActive,
        karaokeAllowed = karaokeAllowed,
        emptyText = "♪ 纯音乐 / 暂无歌词 ♪",
        modifier = modifier
    )
}
