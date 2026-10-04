package com.lm.player.feature.player

import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.lm.player.core.designsystem.component.AddToPlaylistDialog
import com.lm.player.core.designsystem.component.AddToPlaylistDropdownMenu
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SpecBadgeBitrateColor
import com.lm.player.core.designsystem.theme.SpecBadgeQualityColor
import com.lm.player.core.designsystem.theme.SpecBadgeSizeColor
import com.lm.player.core.designsystem.theme.specBadgeBorder
import com.lm.player.core.designsystem.theme.specBadgeContainer
import com.lm.player.core.model.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 现代全屏音乐播放界面 (全面支持横竖屏自适应与界面内嵌融合面板)
 * - 竖屏模式：顶部 Segmented 切换 [ 歌曲 | 歌词 ]、支持左右滑动无缝切换歌词、下拉手势丝滑最小化
 * - 横屏模式：超大专辑封面展示 + 左侧全功能控制器与工具栏 + 右侧视窗支持 [ 歌词 ⇄ 待播列表 ] 顶部右上角一键无缝切换
 * - 底部工具条：[ ≡ 待播列表 ] [ ⏱ 定时关闭 ] [ ⓘ 音频参数详情 ] (取消倍速展示，界面融为一体)
 * - 遥控器交互：方向键上下左右自由选择各功能按钮，确定键操作按钮，长按左右键直接切换上一首/下一首
 */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun FullscreenPlayerSheet(
    song: UnifiedSong,
    playlist: List<UnifiedSong> = emptyList(),
    isPlaying: Boolean,
    /**
     * 播放进度以取数函数传入，而不是直接传值。
     *
     * 传值意味着调用方 (MainActivity 的组合作用域) 必须先读一次进度状态，
     * 于是每次进度刷新都会让**整个 Activity 组合树**重组一遍 (连底层脚手架一起)；
     * 传函数则订阅范围只落在本播放页内部。
     */
    progressMsProvider: () -> Long,
    totalDurationMs: Long,
    lyrics: LyricResult,
    isLyricsMode: Boolean,
    isShuffle: Boolean,
    isRepeat: Boolean,
    playbackSpeed: Float = 1.0f,
    allPlaylists: List<UnifiedPlaylist> = emptyList(),
    // 原先这里有个 activeDownloadTasks: List<DownloadTask> 参数，但整个播放页从未读过它
    // (编译期 "Parameter is never used")。删掉它顺带消掉了根作用域对 3Hz 下载流的又一次读取 ——
    // 根作用域每多一次这种读取，整棵页面树就多一次每 300ms 的无谓重组。
    isServerConnected: Boolean = true,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSongFromQueue: (UnifiedSong) -> Unit = {},
    onToggleLyricsMode: (Boolean) -> Unit = {},
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onChangePlaybackSpeed: (Float) -> Unit = {},
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { s, _, _ -> onDownloadSong(s) },
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { _, _ -> },
    onCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit = { _, _ -> },
    /** 播放页主题切换回调：宿主据此决定进入/退出播放页的转场动画 (如「全屏封面」走底部滑入滑出) */
    onPlayerThemeStyleChange: (PlayerThemeStyle) -> Unit = {},
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dimensions = LocalAppDimensions.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 竖屏 Pager 状态：0 为歌曲封面大图，1 为实时滚动歌词
    val pagerState = rememberPagerState(initialPage = if (isLyricsMode) 1 else 0, pageCount = { 2 })

    // 横屏右侧视窗显示模式：0 为实时歌词，1 为待播队列
    var landscapeRightPaneMode by remember { mutableStateOf(0) }

    // 监听外部歌词模式变化并联动 Pager
    LaunchedEffect(isLyricsMode) {
        val targetPage = if (isLyricsMode) 1 else 0
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // 监听 Pager 滑动并反向同步状态
    LaunchedEffect(pagerState.currentPage) {
        onToggleLyricsMode(pagerState.currentPage == 1)
    }

    // 下拉滑动最小化手势位移
    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(targetValue = dragOffsetY, label = "dismiss_drag_offset")

    // 界面内嵌浮层状态 (融为一体，取消弹窗)
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerPanel by remember { mutableStateOf(false) }
    var showAudioSpecsPanel by remember { mutableStateOf(false) }
    var showLandscapeAddToPlaylistMenu by remember { mutableStateOf(false) }
    var showPortraitAddToPlaylistMenu by remember { mutableStateOf(false) }
    var showDownloadMenu by remember { mutableStateOf(false) }
    var showLandscapeDownloadMenu by remember { mutableStateOf(false) }
    var showPlayerThemeMenu by remember { mutableStateOf(false) }
    var activeTimerMinutes by remember { mutableStateOf(0) }
    var localIsFavorite by remember(song.id, song.isFavorite) { mutableStateOf(song.isFavorite) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    // 进度条拖动中的临时比例：拖动期间由手势接管显示，松手后才真正 seek，
    // 避免「外部进度值不刷新 → 滑块被弹回原位」导致的手感失效
    var seekDragFraction by remember(song.id) { mutableStateOf<Float?>(null) }

    // 歌词偏好设置 (字号大小、时间快慢偏置、6 套主题预设)
    val lyricsPrefs = remember { context.getSharedPreferences("zds_lyrics_prefs", android.content.Context.MODE_PRIVATE) }
    var lyricsFontSize by remember { mutableStateOf(lyricsPrefs.getFloat("lyrics_font_size", 22f)) }
    var lyricsOffsetMs by remember { mutableStateOf(lyricsPrefs.getLong("lyrics_offset_ms", 0L)) }
    var currentLyricTheme by remember {
        mutableStateOf(
            com.lm.player.core.designsystem.theme.LyricTheme.fromId(
                lyricsPrefs.getString("lyrics_theme_id", com.lm.player.core.designsystem.theme.LyricTheme.APPLE_MUSIC.id)
                    ?: com.lm.player.core.designsystem.theme.LyricTheme.APPLE_MUSIC.id
            )
        )
    }
    var playerThemeStyle by remember {
        mutableStateOf(
            com.lm.player.core.designsystem.theme.PlayerThemeStyle.fromId(
                lyricsPrefs.getString("player_theme_style", com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN.id)
                    ?: com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN.id
            )
        )
    }
    // 歌词区宽度已固定为黄金分割比例，不再提供「显示范围调节条」(原左右拖拽分割手柄已移除)

    val updateFontSize: (Float) -> Unit = { newSize ->
        lyricsFontSize = newSize
        lyricsPrefs.edit().putFloat("lyrics_font_size", newSize).apply()
    }
    val updateOffset: (Long) -> Unit = { newOffset ->
        lyricsOffsetMs = newOffset
        lyricsPrefs.edit().putLong("lyrics_offset_ms", newOffset).apply()
    }
    val updateTheme: (com.lm.player.core.designsystem.theme.LyricTheme) -> Unit = { newTheme ->
        currentLyricTheme = newTheme
        lyricsPrefs.edit().putString("lyrics_theme_id", newTheme.id).apply()
    }
    val updatePlayerThemeStyle: (com.lm.player.core.designsystem.theme.PlayerThemeStyle) -> Unit = { newStyle ->
        playerThemeStyle = newStyle
        lyricsPrefs.edit().putString("player_theme_style", newStyle.id).apply()
        onPlayerThemeStyleChange(newStyle)
    }

    // 动态主题渐变背景 (深色沉浸黑曜石，浅色纯净白)。
    // remember 住 Brush：播放页重组频繁，每次都新建会持续产生 shader 垃圾
    val playerBackdrop = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF14171A),
                    Color(0xFF0D1013),
                    Color(0xFF08090B)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF7F7FA),
                    Color(0xFFEDEDF2)
                )
            )
        }
    }

    val primaryTextColor = if (isDark) Color.White else Color(0xFF111113)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.65f) else Color(0xFF636366)
    val surfaceGlassColor = if (isDark) Color(0xFF2C2C30).copy(alpha = 0.75f) else Color(0xFFEAEAEE).copy(alpha = 0.85f)
    val circleButtonBg = if (isDark) Color(0xFF25252A) else Color(0xFFF2F2F7)

    // 「经典黑胶」主题右栏卡片卡片底色与文字色随主题联动：
    // 浅色主题为灰白卡片 + 深色歌词，深色主题整体转为黑色卡片 + 浅色歌词，
    // 避免深色模式下整屏播放界面里突兀地嵌一块高亮白卡，也保证卡内文字始终可读。
    val lyricsCardBg = if (isDark) Color(0xFF16161A).copy(alpha = 0.95f) else Color(0xFFEBEBF0).copy(alpha = 0.95f)
    val lyricsCardPrimary = if (isDark) Color(0xFFF2F2F7) else Color(0xFF1C1C1E)
    val lyricsCardSecondary = if (isDark) Color(0xFF9A9AA4) else Color(0xFF6B6B74)
    val lyricsCardPillBg = if (isDark) Color(0xFF2A2A31) else Color(0xFFD9D9E0)
    val lyricsCardPillSelectedBg = if (isDark) Color(0xFF3C3C45) else Color(0xFFFFFFFF)

    // 手机版同款 3 大音频规格标签（音质、码率、文件大小）
    val isLocalOffline = !song.localFilePath.isNullOrBlank() && 
        (song.localFilePath!!.startsWith("content://") || runCatching { java.io.File(song.localFilePath!!).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
    val (realExt, realBitRate, realSizeStr) = remember(song.id, song.localFilePath, song.format) {
        com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
    }
    val streamQualityVer by com.lm.player.core.network.LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    val streamQuality = remember(song.id, isLocalOffline, streamQualityVer) {
        AudioQuality.fromKey(com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context))
    }
    val (displayQualityBadge, displayBitrateBadge, displaySizeBadge) = remember(
        song.id, song.format, song.bitRate, song.durationMs, song.serverId, song.streamUrl, isLocalOffline, realExt, realBitRate, realSizeStr, streamQuality
    ) {
        if (isLocalOffline) {
            val isLossless = realExt in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realBitRate >= 800
            val qLabel = when {
                isLossless && realBitRate >= 1200 -> "Hi-Res $realExt"
                isLossless -> "无损 $realExt"
                realBitRate >= 256 -> "高品质 $realExt"
                else -> "标准 $realExt"
            }
            val bLabel = "${realBitRate.coerceAtLeast(128)} kbps"
            val sLabel = realSizeStr.ifBlank {
                AudioQuality.fromKey(if (isLossless) "flac" else "320k").estimateSizeText(song)
            }
            Triple(qLabel, bLabel, sLabel)
        } else {
            val isOnlineTrial = song.id.startsWith("lemon_online_") ||
                song.serverId == "lemon_online" ||
                song.streamUrl.startsWith("lemon_online://") ||
                song.streamUrl.contains("/api/play/proxy")
            if (isOnlineTrial) {
                val qLabel = "${streamQuality.badge} ${streamQuality.format.uppercase()}"
                val bLabel = "${streamQuality.bitrate} kbps"
                val sLabel = streamQuality.estimateSizeText(song)
                Triple(qLabel, bLabel, sLabel)
            } else {
                val fmt = song.format.uppercase().ifBlank { "FLAC" }
                val isLossless = fmt in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || song.bitRate >= 800
                val br = if (song.bitRate > 0) song.bitRate else if (isLossless) 960 else 320
                val qLabel = when {
                    isLossless && br >= 1200 -> "Hi-Res $fmt"
                    isLossless -> "无损 $fmt"
                    br >= 256 -> "高品质 $fmt"
                    else -> "标准 $fmt"
                }
                val bLabel = "$br kbps"
                val durSec = (song.durationMs / 1000L).coerceIn(30L, 3600L)
                val mb = (durSec * br * 1000.0) / 8.0 / (1024.0 * 1024.0)
                val sLabel = String.format(java.util.Locale.US, "%.1f MB", mb)
                Triple(qLabel, bLabel, sLabel)
            }
        }
    }

    // TV 遥控器全屏焦点矩阵（绑定全部按钮确定性 2D 焦点导航，根治光标移出屏幕或消失问题）
    val minimizeFocusRequester = remember { FocusRequester() }
    val addPlaylistFocusRequester = remember { FocusRequester() }
    val downloadFocusRequester = remember { FocusRequester() }
    val favoriteFocusRequester = remember { FocusRequester() }
    val seekBarFocusRequester = remember { FocusRequester() }
    val shuffleFocusRequester = remember { FocusRequester() }
    val prevFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val nextFocusRequester = remember { FocusRequester() }
    val repeatFocusRequester = remember { FocusRequester() }
    val queueBtnFocusRequester = remember { FocusRequester() }
    val timerBtnFocusRequester = remember { FocusRequester() }
    val infoBtnFocusRequester = remember { FocusRequester() }
    val themeBtnFocusRequester = remember { FocusRequester() }
    val lyricsTabFocusRequester = remember { FocusRequester() }
    val queueTabFocusRequester = remember { FocusRequester() }
    val lyricsAdjustFocusRequester = remember { FocusRequester() }
    val firstQueueItemFocusRequester = remember { FocusRequester() }

    var isSeekBarFocused by remember { mutableStateOf(false) }
    var sheetHasFocus by remember { mutableStateOf(false) }
    var tvHudMessage by remember { mutableStateOf<String?>(null) }
    var tvHudTrigger by remember { mutableStateOf(0) }
    var longPressHandledForCurrentHold by remember { mutableStateOf(false) }

    // 全屏封面模式：5 秒无任何操作自动隐藏控制栏进入纯净全屏状态，有任何操作立刻恢复
    val isNeteaseCoverMode = playerThemeStyle == com.lm.player.core.designsystem.theme.PlayerThemeStyle.NETEASE_TV_COVER
    var isNeteaseControlsVisible by remember { mutableStateOf(true) }
    var lastUserInteractionTick by remember { mutableStateOf(0L) }

    // 经典黑胶唱片旋转角度：与主界面播控卡共享同一全局角度，两处黑胶始终同步。
    // 返回的是只读 State，取值必须在 graphicsLayer 内延迟进行；网易云全屏封面主题下没有黑胶，
    // 直接不担任驱动方，省掉这份逐帧动画开销。
    val vinylRotation = com.lm.player.core.designsystem.theme.rememberVinylRotation(isPlaying && !isNeteaseCoverMode)

    // 进度状态在这里才被读取：订阅范围收窄到本播放页，不再牵连调用方的整棵组合树
    val progressMs = progressMsProvider()

    val anyPopupExpanded = showLandscapeAddToPlaylistMenu || showLandscapeDownloadMenu || showSleepTimerPanel || showAudioSpecsPanel || showPlayerThemeMenu

    LaunchedEffect(lastUserInteractionTick, isNeteaseCoverMode, isNeteaseControlsVisible, anyPopupExpanded, landscapeRightPaneMode) {
        if (isNeteaseCoverMode && isNeteaseControlsVisible && !anyPopupExpanded && landscapeRightPaneMode == 0) {
            kotlinx.coroutines.delay(5000L)
            isNeteaseControlsVisible = false
        }
    }

    LaunchedEffect(isNeteaseCoverMode, isNeteaseControlsVisible) {
        if (!isNeteaseCoverMode || isNeteaseControlsVisible) {
            try {
                kotlinx.coroutines.delay(60L)
                playPauseFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // 焦点防丢守护协程：若全屏播放界面处于打开状态且无浮层菜单弹出，一旦焦点意外丢失则立即拉回主控键
    LaunchedEffect(sheetHasFocus, anyPopupExpanded, isNeteaseCoverMode, isNeteaseControlsVisible) {
        if (!sheetHasFocus && !anyPopupExpanded && (!isNeteaseCoverMode || isNeteaseControlsVisible)) {
            kotlinx.coroutines.delay(120L)
            if (!sheetHasFocus && !anyPopupExpanded && (!isNeteaseCoverMode || isNeteaseControlsVisible)) {
                try {
                    playPauseFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(tvHudTrigger) {
        if (tvHudMessage != null) {
            kotlinx.coroutines.delay(1600L)
            tvHudMessage = null
        }
    }

    val showTvHud: (String) -> Unit = { msg ->
        tvHudMessage = msg
        tvHudTrigger++
    }

    val selectPlayerTheme: (com.lm.player.core.designsystem.theme.PlayerThemeStyle) -> Unit = { targetTheme ->
        showPlayerThemeMenu = false
        isNeteaseControlsVisible = true
        lastUserInteractionTick++
        updatePlayerThemeStyle(targetTheme)
        showTvHud("🎨 已切换主题：${targetTheme.displayName}")
    }

    // 车机模式：本页是全应用唯一的「裸 focusable」节点（不经过 tvFocusable），需单独关掉，
    // 否则方向键仍能落进这一层并触发下方的 onPreviewKeyEvent 快捷键。
    val isCarPlatform = LocalPlatformMode.current == PlatformMode.CAR

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffsetY.roundToInt().coerceAtLeast(0)) }
            .background(playerBackdrop)
            .focusable(enabled = isNeteaseCoverMode && !isNeteaseControlsVisible && !isCarPlatform)
            .onFocusChanged { state ->
                sheetHasFocus = state.hasFocus
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == android.view.KeyEvent.ACTION_UP) {
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                        native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    ) {
                        longPressHandledForCurrentHold = false
                    }
                    return@onPreviewKeyEvent false
                }
                if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                    lastUserInteractionTick++
                    // 在网易云 TV 全屏纯净状态下，按下任何非返回按键立即唤醒控制栏（恢复第一张图状态）
                    if (isNeteaseCoverMode && !isNeteaseControlsVisible) {
                        isNeteaseControlsVisible = true
                        if (native.keyCode != android.view.KeyEvent.KEYCODE_BACK &&
                            native.keyCode != android.view.KeyEvent.KEYCODE_ESCAPE
                        ) {
                            return@onPreviewKeyEvent true
                        }
                    }
                    when (native.keyCode) {
                        android.view.KeyEvent.KEYCODE_MENU -> {
                            landscapeRightPaneMode = if (landscapeRightPaneMode == 1) 0 else 1
                            showTvHud(
                                if (landscapeRightPaneMode == 1) "☰ 待播队列 (上下键选歌 · OK播放)"
                                else "🎵 实时同步歌词模式"
                            )
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            // 光标在进度条上时，短按与长按左键均交由进度条执行连续快退 10 秒
                            if (isSeekBarFocused) {
                                false
                            } else if (native.repeatCount == 0) {
                                longPressHandledForCurrentHold = false
                                false
                            } else {
                                if (!longPressHandledForCurrentHold) {
                                    longPressHandledForCurrentHold = true
                                    onPrevious()
                                    showTvHud("⏮ 长按左键 · 切换上一首")
                                }
                                true
                            }
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            // 光标在进度条上时，短按与长按右键均交由进度条执行连续快进 10 秒
                            if (isSeekBarFocused) {
                                false
                            } else if (native.repeatCount == 0) {
                                longPressHandledForCurrentHold = false
                                false
                            } else {
                                if (!longPressHandledForCurrentHold) {
                                    longPressHandledForCurrentHold = true
                                    onNext()
                                    showTvHud("⏭ 长按右键 · 切换下一首")
                                }
                                true
                            }
                        }
                        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            onPrevious()
                            showTvHud("⏮ 切换上一首")
                            true
                        }
                        android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            onNext()
                            showTvHud("⏭ 切换下一首")
                            true
                        }
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            val nextPlaying = !isPlaying
                            onTogglePlayPause()
                            showTvHud(if (nextPlaying) "▶ 继续播放" else "⏸ 已暂停播放")
                            true
                        }
                        android.view.KeyEvent.KEYCODE_BACK,
                        android.view.KeyEvent.KEYCODE_ESCAPE -> {
                            if (landscapeRightPaneMode == 1) {
                                landscapeRightPaneMode = 0
                                try { playPauseFocusRequester.requestFocus() } catch (_: Exception) {}
                                showTvHud("已返回歌词视图 (再按返回退出全屏)")
                            } else {
                                onDismiss()
                            }
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .pointerInput(isNeteaseCoverMode) {
                detectTapGestures {
                    lastUserInteractionTick++
                    if (isNeteaseCoverMode) {
                        isNeteaseControlsVisible = true
                    }
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        lastUserInteractionTick++
                        if (isNeteaseCoverMode) isNeteaseControlsVisible = true
                        if (dragAmount > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (dragOffsetY > 160f) {
                            onDismiss()
                        }
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        dragOffsetY = 0f
                    }
                )
            }
    ) {
        if (true) {
            // =========================================================================
            // TV 横屏客厅影院级布局：支持「经典黑胶分屏 (MODERN)」与「网易云全屏封面 (NETEASE_TV_COVER)」一键切换
            // =========================================================================
            // 歌词视窗宽度固定为 48%（已移除原可左右拖拽的「显示范围调节条」）
            val effectiveLyricsRatio = 0.48f
            val playerWeight = (1.0f - effectiveLyricsRatio).coerceIn(0.50f, 0.58f)
            val isLyricsVisible = true
            val rightPaneTargetFocus = if (landscapeRightPaneMode == 0) {
                lyricsAdjustFocusRequester
            } else if (playlist.isNotEmpty()) {
                firstQueueItemFocusRequester
            } else {
                queueTabFocusRequester
            }

            if (isNeteaseCoverMode) {
                // =====================================================================
                // 主题 2：全屏封面主题 (NETEASE_TV_COVER)
                // - 删除黑胶唱片圆圈，左半边显示歌曲高清封面图，右半边加渐变阴影
                // - 右半边上方显示歌曲名称、音质角标与歌手信息，下方显示 7 列滚动歌词
                // - 5 秒无任何操作自动隐藏底部控制栏，有任何操作立刻恢复
                // =====================================================================
                val effectiveTotalDurationMs = if (totalDurationMs > 0L) totalDurationMs else song.durationMs.coerceAtLeast(1L)
                var neteaseDragFraction by remember { mutableStateOf<Float?>(null) }
                var neteaseTrackWidthPx by remember { mutableFloatStateOf(1f) }
                val progressFraction = neteaseDragFraction ?: if (effectiveTotalDurationMs > 0L) {
                    (progressMs.toFloat() / effectiveTotalDurationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val displayedProgressMs = if (neteaseDragFraction != null) {
                    (neteaseDragFraction!! * effectiveTotalDurationMs).toLong().coerceIn(0L, effectiveTotalDurationMs)
                } else {
                    progressMs
                }
                val shortQualityTag = remember(displayQualityBadge) {
                    displayQualityBadge.substringBefore(" ").ifBlank { displayQualityBadge }
                }

                // 隐藏按钮时平滑线性放大，出现按钮时平滑线性缩小，幅度严格对称防止抽搐
                val neteaseZoomScale by animateFloatAsState(
                    targetValue = if (isNeteaseControlsVisible) 1.0f else 1.06f,
                    animationSpec = tween(durationMillis = 420, easing = LinearEasing),
                    label = "netease_linear_zoom_scale"
                )
                val neteaseLyricsBottomPad by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (isNeteaseControlsVisible) 132.dp else 52.dp,
                    animationSpec = tween(durationMillis = 420, easing = LinearEasing),
                    label = "netease_lyrics_bottom_pad"
                )
                val neteaseBottomShadeAlpha by animateFloatAsState(
                    targetValue = if (isNeteaseControlsVisible) 1.0f else 0.0f,
                    animationSpec = tween(durationMillis = 420, easing = LinearEasing),
                    label = "netease_bottom_shade_alpha"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF212A44))
                ) {
                    // 缩放舞台层（包含背景、左侧渐变切换封面与右侧歌词，随控制栏显隐进行平滑线性缩放）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = neteaseZoomScale
                                scaleY = neteaseZoomScale
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                            }
                    ) {
                        // 1. 底层氛围背景（取自封面的深色暗化衬底，支持切歌渐变）
                        androidx.compose.animation.Crossfade(
                            targetState = Pair(song.id, song.coverUrl),
                            animationSpec = tween(durationMillis = 650, easing = LinearEasing),
                            label = "netease_bg_cover_crossfade",
                            modifier = Modifier.fillMaxSize()
                        ) { (seedId, coverUrl) ->
                            // 解码尺寸与左侧大封面保持一致 (1080)：Coil 的内存缓存按 (url, size) 建键，
                            // 用同一个尺寸能让背景与主封面命中同一份位图，避免同图解两遍、两张位图同时常驻
                            AlbumArtworkImage(
                                model = coverUrl,
                                seedId = seedId,
                                targetSize = 1080,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 0.dp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF232D49).copy(alpha = 0.88f))
                        )

                        // 2. 左半边：歌曲超清封面大图（占据左半屏，切歌时平滑渐变切换）
                        androidx.compose.animation.Crossfade(
                            targetState = Pair(song.id, song.coverUrl),
                            animationSpec = tween(durationMillis = 650, easing = LinearEasing),
                            label = "netease_left_cover_crossfade",
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.56f)
                                .align(Alignment.CenterStart)
                        ) { (seedId, coverUrl) ->
                            AlbumArtworkImage(
                                model = coverUrl,
                                seedId = seedId,
                                targetSize = 1080,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 0.dp
                            )
                        }

                        // 3. 左半边向右侧过渡的沉浸式水平阴影渐变遮罩
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    remember {
                                        Brush.horizontalGradient(
                                            0.0f to Color.Black.copy(alpha = 0.05f),
                                            0.30f to Color.Transparent,
                                            0.43f to Color(0xFF26304C).copy(alpha = 0.68f),
                                            0.54f to Color(0xFF26304C).copy(alpha = 0.96f),
                                            1.0f to Color(0xFF232C47).copy(alpha = 0.99f)
                                        )
                                    }
                                )
                        )

                        // 4. 右半边：右上角歌曲标题+音质标签+歌手信息 + 下方 7 列滚动歌词（向右微移，保持更舒展间距）
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.46f)
                                .align(Alignment.CenterEnd)
                                .systemBarsPadding()
                                .padding(
                                    start = 24.dp,
                                    top = 52.dp,
                                    end = 28.dp,
                                    bottom = neteaseLyricsBottomPad
                                ),
                            verticalArrangement = Arrangement.Top,
                            horizontalAlignment = Alignment.Start
                        ) {
                            // 右上：歌名 + 音质小标签
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = song.title,
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.White.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = shortQualityTag,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.78f),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // 歌手名称
                            Text(
                                text = song.artist,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.48f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // 右半边 7 列滚动歌词 (行高倍数放宽，歌词行间距更大更透气)
                            LyricsScrollingView(
                                lyrics = lyrics.lines,
                                currentPositionMs = progressMs,
                                onSeekToLyric = onSeekTo,
                                fontSizeSp = 24f,
                                lyricsOffsetMs = lyricsOffsetMs,
                                lyricTheme = currentLyricTheme,
                                showAdjustButton = false,
                                visibleLineCount = 7,
                                rowHeightMultiplier = 2.15f,
                                textAlign = TextAlign.Start,
                                overrideActiveColor = Color.White,
                                overrideInactiveColor = Color.White.copy(alpha = 0.46f),
                                horizontalPaddingDp = 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }

                    // 底部控制栏展开时的柔和底部暗角遮罩
                    if (neteaseBottomShadeAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp)
                                .align(Alignment.BottomCenter)
                                .graphicsLayer { alpha = neteaseBottomShadeAlpha }
                                .background(
                                    remember {
                                        Brush.verticalGradient(
                                            0.0f to Color.Transparent,
                                            0.45f to Color.Black.copy(alpha = 0.52f),
                                            1.0f to Color.Black.copy(alpha = 0.82f)
                                        )
                                    }
                                )
                        )
                    }

                    // 5. 底部进度条与操作按键栏（5秒无操作自动隐藏，任意按键立即恢复）
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .systemBarsPadding()
                            .padding(
                                start = 42.dp,
                                end = 42.dp,
                                bottom = 22.dp
                            )
                    ) {
                        AnimatedVisibility(
                            visible = isNeteaseControlsVisible,
                            enter = fadeIn(tween(420, easing = LinearEasing)),
                            exit = fadeOut(tween(420, easing = LinearEasing))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                // 进度条行：左侧当前时间 + 中间可拖动/可快进快退进度条 + 右侧总时长
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(seekBarFocusRequester)
                                        .focusProperties {
                                            up = FocusRequester.Cancel
                                            down = playPauseFocusRequester
                                            left = FocusRequester.Cancel
                                            right = FocusRequester.Cancel
                                        }
                                        .tvFocusable(
                                            shape = RoundedCornerShape(8.dp),
                                            focusedScale = 1.0f,
                                            onFocusChange = { isSeekBarFocused = it },
                                            onLeftKey = { repeatCount ->
                                                lastUserInteractionTick++
                                                val step = if (repeatCount > 3) 15_000L else 5_000L
                                                val target = (displayedProgressMs - step).coerceAtLeast(0L)
                                                neteaseDragFraction = null
                                                onSeekTo(target)
                                                showTvHud("⏪ 快退 ${step / 1000} 秒 · ${formatDuration(target)}")
                                                true
                                            },
                                            onRightKey = { repeatCount ->
                                                lastUserInteractionTick++
                                                val step = if (repeatCount > 3) 15_000L else 5_000L
                                                val target = (displayedProgressMs + step).coerceAtMost(effectiveTotalDurationMs)
                                                neteaseDragFraction = null
                                                onSeekTo(target)
                                                showTvHud("⏩ 快进 ${step / 1000} 秒 · ${formatDuration(target)}")
                                                true
                                            },
                                            onClick = {
                                                lastUserInteractionTick++
                                                val nextPlaying = !isPlaying
                                                onTogglePlayPause()
                                                showTvHud(if (nextPlaying) "▶ 继续播放" else "⏸ 已暂停播放")
                                            }
                                        )
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = formatDuration(displayedProgressMs),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(22.dp)
                                            .onSizeChanged {
                                                if (it.width > 0) {
                                                    neteaseTrackWidthPx = it.width.toFloat()
                                                }
                                            }
                                            .pointerInput(effectiveTotalDurationMs) {
                                                detectTapGestures { offset ->
                                                    lastUserInteractionTick++
                                                    val frac = (offset.x / neteaseTrackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                                    val targetMs = (frac * effectiveTotalDurationMs).toLong()
                                                    neteaseDragFraction = null
                                                    onSeekTo(targetMs)
                                                    showTvHud("跳转至 ${formatDuration(targetMs)}")
                                                }
                                            }
                                            .pointerInput(effectiveTotalDurationMs) {
                                                detectHorizontalDragGestures(
                                                    onDragStart = { offset ->
                                                        lastUserInteractionTick++
                                                        neteaseDragFraction = (offset.x / neteaseTrackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                                    },
                                                    onHorizontalDrag = { change, _ ->
                                                        change.consume()
                                                        lastUserInteractionTick++
                                                        neteaseDragFraction = (change.position.x / neteaseTrackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                                    },
                                                    onDragEnd = {
                                                        lastUserInteractionTick++
                                                        val finalFrac = neteaseDragFraction
                                                        neteaseDragFraction = null
                                                        if (finalFrac != null) {
                                                            val targetMs = (finalFrac * effectiveTotalDurationMs).toLong()
                                                            onSeekTo(targetMs)
                                                            showTvHud("跳转至 ${formatDuration(targetMs)}")
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        neteaseDragFraction = null
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        val trackH = if (isSeekBarFocused || neteaseDragFraction != null) 6.dp else 3.5.dp
                                        val thumbD = if (isSeekBarFocused || neteaseDragFraction != null) 14.dp else 10.dp
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(trackH)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color.White.copy(alpha = 0.28f))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(progressFraction)
                                                .height(trackH)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(if (isSeekBarFocused || neteaseDragFraction != null) AppleRed else Color.White)
                                        )
                                        val availW = (maxWidth - thumbD).coerceAtLeast(0.dp)
                                        Box(
                                            modifier = Modifier
                                                .offset(x = availW * progressFraction)
                                                .size(thumbD)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                    Text(
                                        text = formatDuration(effectiveTotalDurationMs),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // 底部操作按键排：左侧 [上一首 | 播放/暂停 | 下一首] + 右侧 [红心 | 随机 | 循环 | 下载 | 收藏到歌单 | 主题衣服图标 | 音质详情]
                                val neteaseBtnBg = Color.White.copy(alpha = 0.16f)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左侧三大播放控制键
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .focusRequester(prevFocusRequester)
                                                .focusProperties {
                                                    up = seekBarFocusRequester
                                                    down = FocusRequester.Cancel
                                                    left = FocusRequester.Cancel
                                                    right = playPauseFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = CircleShape,
                                                    focusedScale = 1.12f,
                                                    onClick = {
                                                        lastUserInteractionTick++
                                                        onPrevious()
                                                        showTvHud("⏮ 切换上一首")
                                                    }
                                                )
                                                .clip(CircleShape)
                                                .background(neteaseBtnBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = Color.White, modifier = Modifier.size(26.dp))
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .focusRequester(playPauseFocusRequester)
                                                .focusProperties {
                                                    up = seekBarFocusRequester
                                                    down = FocusRequester.Cancel
                                                    left = prevFocusRequester
                                                    right = nextFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = CircleShape,
                                                    focusedScale = 1.14f,
                                                    focusedBorderColor = AppleRed,
                                                    onClick = {
                                                        lastUserInteractionTick++
                                                        val nextPlaying = !isPlaying
                                                        onTogglePlayPause()
                                                        showTvHud(if (nextPlaying) "▶ 继续播放" else "⏸ 已暂停播放")
                                                    }
                                                )
                                                .clip(CircleShape)
                                                .background(AppleRed),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                contentDescription = "播放/暂停",
                                                tint = Color.White,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .focusRequester(nextFocusRequester)
                                                .focusProperties {
                                                    up = seekBarFocusRequester
                                                    down = FocusRequester.Cancel
                                                    left = playPauseFocusRequester
                                                    right = favoriteFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = CircleShape,
                                                    focusedScale = 1.12f,
                                                    onClick = {
                                                        lastUserInteractionTick++
                                                        onNext()
                                                        showTvHud("⏭ 切换下一首")
                                                    }
                                                )
                                                .clip(CircleShape)
                                                .background(neteaseBtnBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = Color.White, modifier = Modifier.size(26.dp))
                                        }
                                    }

                                    // 右侧功能与主题切换按键组
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. 红心喜欢
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .focusRequester(favoriteFocusRequester)
                                                .focusProperties {
                                                    up = seekBarFocusRequester
                                                    down = FocusRequester.Cancel
                                                    left = nextFocusRequester
                                                    right = shuffleFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = CircleShape,
                                                    focusedScale = 1.12f,
                                                    onClick = {
                                                        lastUserInteractionTick++
                                                        localIsFavorite = !localIsFavorite
                                                        onToggleFavorite()
                                                        showTvHud(if (localIsFavorite) "❤️ 已加入我喜欢" else "🤍 已取消喜欢")
                                                    }
                                                )
                                                .clip(CircleShape)
                                                .background(neteaseBtnBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (localIsFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                                contentDescription = "喜欢",
                                                tint = if (localIsFavorite) AppleRed else Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }

                                        // 2. 随机播放
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .focusRequester(shuffleFocusRequester)
                                                .focusProperties {
                                                    up = seekBarFocusRequester
                                                    down = FocusRequester.Cancel
                                                    left = favoriteFocusRequester
                                                    right = repeatFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = CircleShape,
                                                    focusedScale = 1.12f,
                                                    onClick = {
                                                        lastUserInteractionTick++
                                                        onToggleShuffle()
                                                        showTvHud(if (!isShuffle) "🔀 已开启随机播放" else "➡️ 已切换顺序播放")
                                                    }
                                                )
                                                .clip(CircleShape)
                                                .background(if (isShuffle) AppleRed.copy(alpha = 0.38f) else neteaseBtnBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Shuffle, contentDescription = "随机", tint = Color.White, modifier = Modifier.size(21.dp))
                                        }

                                        // 3. 单曲循环
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .focusRequester(repeatFocusRequester)
                                                .focusProperties {
                                                    up = seekBarFocusRequester
                                                    down = FocusRequester.Cancel
                                                    left = shuffleFocusRequester
                                                    right = downloadFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = CircleShape,
                                                    focusedScale = 1.12f,
                                                    onClick = {
                                                        lastUserInteractionTick++
                                                        onToggleRepeat()
                                                        showTvHud(if (!isRepeat) "🔂 已开启单曲循环" else "🔁 已切换列表循环")
                                                    }
                                                )
                                                .clip(CircleShape)
                                                .background(if (isRepeat) AppleRed.copy(alpha = 0.38f) else neteaseBtnBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Repeat, contentDescription = "循环", tint = Color.White, modifier = Modifier.size(21.dp))
                                        }

                                        // 4. 下载与缓存
                                        val hasPhysicalLocal = !song.localFilePath.isNullOrBlank() && 
                                            (song.localFilePath!!.startsWith("content://") || runCatching { java.io.File(song.localFilePath!!).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .focusRequester(downloadFocusRequester)
                                                    .focusProperties {
                                                        up = seekBarFocusRequester
                                                        down = FocusRequester.Cancel
                                                        left = repeatFocusRequester
                                                        right = addPlaylistFocusRequester
                                                    }
                                                    .tvFocusable(
                                                        shape = CircleShape,
                                                        focusedScale = 1.12f,
                                                        onClick = {
                                                            lastUserInteractionTick++
                                                            showLandscapeDownloadMenu = true
                                                        }
                                                    )
                                                    .clip(CircleShape)
                                                    .background(neteaseBtnBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val isDownloaded = hasPhysicalLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
                                                Icon(
                                                    imageVector = if (isDownloaded) Icons.Default.CheckCircle else Icons.Default.FileDownload,
                                                    contentDescription = "下载",
                                                    tint = if (isDownloaded) Color(0xFF34C759) else Color.White,
                                                    modifier = Modifier.size(21.dp)
                                                )
                                            }
                                            DownloadQualityDropdownMenu(
                                                expanded = showLandscapeDownloadMenu,
                                                onDismissRequest = {
                                                    showLandscapeDownloadMenu = false
                                                    try { downloadFocusRequester.requestFocus() } catch (_: Exception) {}
                                                },
                                                song = song,
                                                isServerConnected = isServerConnected,
                                                hasLocal = hasPhysicalLocal,
                                                hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                                                onConfirm = { target, quality ->
                                                    showLandscapeDownloadMenu = false
                                                    onDownloadSongWithOptions(song, target, quality)
                                                    try { downloadFocusRequester.requestFocus() } catch (_: Exception) {}
                                                }
                                            )
                                        }

                                        // 5. 加入歌单
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .focusRequester(addPlaylistFocusRequester)
                                                    .focusProperties {
                                                        up = seekBarFocusRequester
                                                        down = FocusRequester.Cancel
                                                        left = downloadFocusRequester
                                                        right = themeBtnFocusRequester
                                                    }
                                                    .tvFocusable(
                                                        shape = CircleShape,
                                                        focusedScale = 1.12f,
                                                        onClick = {
                                                            lastUserInteractionTick++
                                                            showLandscapeAddToPlaylistMenu = true
                                                        }
                                                    )
                                                    .clip(CircleShape)
                                                    .background(neteaseBtnBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "加入歌单", tint = Color.White, modifier = Modifier.size(21.dp))
                                            }
                                            AddToPlaylistDropdownMenu(
                                                expanded = showLandscapeAddToPlaylistMenu,
                                                onDismissRequest = {
                                                    showLandscapeAddToPlaylistMenu = false
                                                    try { addPlaylistFocusRequester.requestFocus() } catch (_: Exception) {}
                                                },
                                                song = song,
                                                playlists = allPlaylists,
                                                isServerConnected = isServerConnected,
                                                onSelectPlaylist = { pl, s ->
                                                    onAddToPlaylist(pl, s)
                                                },
                                                onCreatePlaylistAndAdd = { name, s ->
                                                    onCreatePlaylistAndAddSong(name, s)
                                                }
                                            )
                                        }

                                        // 6. 主题切换按钮（小衣服图标，紧挨收藏到歌单按钮旁边，点击弹出列表选择）
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .focusRequester(themeBtnFocusRequester)
                                                    .focusProperties {
                                                        up = seekBarFocusRequester
                                                        down = FocusRequester.Cancel
                                                        left = addPlaylistFocusRequester
                                                        right = infoBtnFocusRequester
                                                    }
                                                    .tvFocusable(
                                                        shape = CircleShape,
                                                        focusedScale = 1.12f,
                                                        onClick = {
                                                            lastUserInteractionTick++
                                                            showPlayerThemeMenu = true
                                                        }
                                                    )
                                                    .clip(CircleShape)
                                                    .background(neteaseBtnBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = androidx.compose.ui.res.painterResource(id = com.lm.player.R.drawable.ic_theme_shirt),
                                                    contentDescription = "主题选择",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(21.dp)
                                                )
                                            }
                                            PlayerThemeDropdownMenu(
                                                expanded = showPlayerThemeMenu,
                                                currentTheme = playerThemeStyle,
                                                onDismissRequest = {
                                                    showPlayerThemeMenu = false
                                                    try { themeBtnFocusRequester.requestFocus() } catch (_: Exception) {}
                                                },
                                                onSelectTheme = { selected ->
                                                    selectPlayerTheme(selected)
                                                }
                                            )
                                        }

                                        // 7. 音频参数详情
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .focusRequester(infoBtnFocusRequester)
                                                    .focusProperties {
                                                        up = seekBarFocusRequester
                                                        down = FocusRequester.Cancel
                                                        left = themeBtnFocusRequester
                                                        right = FocusRequester.Cancel
                                                    }
                                                    .tvFocusable(
                                                        shape = CircleShape,
                                                        focusedScale = 1.12f,
                                                        onClick = {
                                                            lastUserInteractionTick++
                                                            showAudioSpecsPanel = true
                                                        }
                                                    )
                                                    .clip(CircleShape)
                                                    .background(neteaseBtnBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Outlined.Info, contentDescription = "音频详情", tint = Color.White, modifier = Modifier.size(21.dp))
                                            }
                                            AudioSpecsDropdownMenu(
                                                expanded = showAudioSpecsPanel,
                                                onDismissRequest = {
                                                    showAudioSpecsPanel = false
                                                    try { infoBtnFocusRequester.requestFocus() } catch (_: Exception) {}
                                                },
                                                song = song
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧黑胶唱片与控制器面板
                Column(
                    modifier = Modifier
                        .weight(playerWeight)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. 顶部 Header (返回键 + 音源模式标签 + 收藏到歌单/主题小衣服/下载/喜欢按钮)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(150f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .focusRequester(minimizeFocusRequester)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = addPlaylistFocusRequester
                                    down = seekBarFocusRequester
                                }
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.12f,
                                    onClick = onDismiss
                                )
                                .clip(CircleShape)
                                .background(circleButtonBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回", tint = primaryTextColor, modifier = Modifier.size(24.dp))
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = circleButtonBg
                        ) {
                            Text(
                                text = if (song.localFilePath != null) "本地高保真音频" else "在线高保真流媒体",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryTextColor,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 收藏到歌单
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .focusRequester(addPlaylistFocusRequester)
                                        .focusProperties {
                                            up = FocusRequester.Cancel
                                            left = minimizeFocusRequester
                                            right = themeBtnFocusRequester
                                            down = seekBarFocusRequester
                                        }
                                        .tvFocusable(
                                            shape = CircleShape,
                                            focusedScale = 1.12f,
                                            onClick = { showLandscapeAddToPlaylistMenu = true }
                                        )
                                        .clip(CircleShape)
                                        .background(circleButtonBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "加入歌单", tint = primaryTextColor, modifier = Modifier.size(20.dp))
                                }
                                AddToPlaylistDropdownMenu(
                                    expanded = showLandscapeAddToPlaylistMenu,
                                    onDismissRequest = {
                                        showLandscapeAddToPlaylistMenu = false
                                        try { addPlaylistFocusRequester.requestFocus() } catch (_: Exception) {}
                                    },
                                    song = song,
                                    playlists = allPlaylists,
                                    isServerConnected = isServerConnected,
                                    onSelectPlaylist = { pl, s ->
                                        onAddToPlaylist(pl, s)
                                    },
                                    onCreatePlaylistAndAdd = { name, s ->
                                        onCreatePlaylistAndAddSong(name, s)
                                    }
                                )
                            }

                            // 主题切换按钮（放置在收藏到歌单按钮旁边，以小衣服图标代替，点击后弹出列表选择）
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .focusRequester(themeBtnFocusRequester)
                                        .focusProperties {
                                            up = FocusRequester.Cancel
                                            left = addPlaylistFocusRequester
                                            right = downloadFocusRequester
                                            down = seekBarFocusRequester
                                        }
                                        .tvFocusable(
                                            shape = CircleShape,
                                            focusedScale = 1.12f,
                                            onClick = { showPlayerThemeMenu = true }
                                        )
                                        .clip(CircleShape)
                                        .background(circleButtonBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = com.lm.player.R.drawable.ic_theme_shirt),
                                        contentDescription = "主题选择",
                                        tint = primaryTextColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                PlayerThemeDropdownMenu(
                                    expanded = showPlayerThemeMenu,
                                    currentTheme = playerThemeStyle,
                                    onDismissRequest = {
                                        showPlayerThemeMenu = false
                                        try { themeBtnFocusRequester.requestFocus() } catch (_: Exception) {}
                                    },
                                    onSelectTheme = { selected ->
                                        selectPlayerTheme(selected)
                                    }
                                )
                            }

                            // 缓存与下载
                            val hasPhysicalLocal = !song.localFilePath.isNullOrBlank() && 
                                (song.localFilePath!!.startsWith("content://") || runCatching { java.io.File(song.localFilePath!!).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .focusRequester(downloadFocusRequester)
                                        .focusProperties {
                                            up = FocusRequester.Cancel
                                            left = themeBtnFocusRequester
                                            right = favoriteFocusRequester
                                            down = seekBarFocusRequester
                                        }
                                        .tvFocusable(
                                            shape = CircleShape,
                                            focusedScale = 1.12f,
                                            onClick = { showLandscapeDownloadMenu = true }
                                        )
                                        .clip(CircleShape)
                                        .background(circleButtonBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val isDownloaded = hasPhysicalLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
                                    val isServerCached = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online"
                                    if (isDownloaded && isServerCached) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "双端已同步", tint = Color(0xFF34C759), modifier = Modifier.size(20.dp))
                                    } else if (isDownloaded || isServerCached) {
                                        Icon(Icons.Default.CheckCircleOutline, contentDescription = "单端已缓存", tint = Color(0xFF34C759), modifier = Modifier.size(20.dp))
                                    } else {
                                        Icon(Icons.Default.FileDownload, contentDescription = "下载", tint = primaryTextColor, modifier = Modifier.size(20.dp))
                                    }
                                }
                                DownloadQualityDropdownMenu(
                                    expanded = showLandscapeDownloadMenu,
                                    onDismissRequest = {
                                        showLandscapeDownloadMenu = false
                                        try { downloadFocusRequester.requestFocus() } catch (_: Exception) {}
                                    },
                                    song = song,
                                    isServerConnected = isServerConnected,
                                    hasLocal = hasPhysicalLocal,
                                    hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                                    onConfirm = { target, quality ->
                                        showLandscapeDownloadMenu = false
                                        onDownloadSongWithOptions(song, target, quality)
                                        try { downloadFocusRequester.requestFocus() } catch (_: Exception) {}
                                    }
                                )
                            }

                            // 红心喜欢
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .focusRequester(favoriteFocusRequester)
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                        left = downloadFocusRequester
                                        right = lyricsTabFocusRequester
                                        down = seekBarFocusRequester
                                    }
                                    .tvFocusable(
                                        shape = CircleShape,
                                        focusedScale = 1.12f,
                                        onClick = {
                                            localIsFavorite = !localIsFavorite
                                            onToggleFavorite()
                                            showTvHud(if (localIsFavorite) "❤️ 已加入我喜欢" else "🤍 已取消喜欢")
                                        }
                                    )
                                    .clip(CircleShape)
                                    .background(circleButtonBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (localIsFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "喜欢",
                                    tint = if (localIsFavorite) AppleRed else primaryTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // 2. 酷我 TV 风格超大圆形黑胶唱片封面（播放时平滑旋转） + 歌名歌手与无损音质标签
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        // 大尺寸圆形黑胶唱片（带平滑旋转动画）。
                        // 角度在 graphicsLayer 的 block 内读取，属于延迟读取：每帧只失效这一层，
                        // 不会重组整个播放页。
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .shadow(20.dp, CircleShape)
                                .clip(CircleShape)
                                .graphicsLayer { rotationZ = vinylRotation.value }
                                .background(
                                    remember {
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFF2E3240),
                                                Color(0xFF14161E),
                                                Color(0xFF242834),
                                                Color(0xFF0E1016)
                                            )
                                        )
                                    }
                                )
                                .border(2.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // 唱片同心纹理环
                            Box(
                                modifier = Modifier
                                    .size(196.dp)
                                    .border(0.8.dp, Color.White.copy(alpha = 0.07f), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(174.dp)
                                    .border(0.8.dp, Color.White.copy(alpha = 0.07f), CircleShape)
                            )
                            // 中央圆形专辑封面
                            AlbumArtworkImage(
                                model = song.coverUrl,
                                seedId = song.id,
                                targetSize = 480,
                                modifier = Modifier
                                    .size(150.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, Color.Black.copy(alpha = 0.45f), CircleShape),
                                cornerRadius = 75.dp
                            )
                            // 黑胶中心轴孔
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF181B24))
                                    .border(2.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = song.title,
                                style = TextStyle(
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = primaryTextColor
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = song.artist,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = secondaryTextColor
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "专辑：${song.album.ifBlank { "单曲精选" }}",
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = secondaryTextColor.copy(alpha = 0.8f)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 标签 1：音质 (如 无损 FLAC / Hi-Res FLAC) —— 统一淡黄
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = specBadgeContainer(SpecBadgeQualityColor),
                                    border = BorderStroke(0.8.dp, specBadgeBorder(SpecBadgeQualityColor))
                                ) {
                                    Text(
                                        text = displayQualityBadge,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = SpecBadgeQualityColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                                // 标签 2：码率 (如 960 kbps / 320 kbps) —— 统一淡绿
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = specBadgeContainer(SpecBadgeBitrateColor),
                                    border = BorderStroke(0.8.dp, specBadgeBorder(SpecBadgeBitrateColor))
                                ) {
                                    Text(
                                        text = displayBitrateBadge,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SpecBadgeBitrateColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                                // 标签 3：文件大小 (如 28.4 MB) —— 统一淡红
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = specBadgeContainer(SpecBadgeSizeColor),
                                    border = BorderStroke(0.8.dp, specBadgeBorder(SpecBadgeSizeColor))
                                ) {
                                    Text(
                                        text = displaySizeBadge,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SpecBadgeSizeColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3. 进度条与时间（定制无内部焦点抢夺的 TV 专属进度条：光标置于进度条时按左右键直接快退/快进 10 秒）
                    // 时长兜底：内核上报时长为 0 时退回本地元数据时长，避免 seek 目标被算成 0
                    val seekDurationMs = if (totalDurationMs > 0L) totalDurationMs else song.durationMs.coerceAtLeast(1L)
                    val progressFraction = seekDragFraction
                        ?: (progressMs.toFloat() / seekDurationMs.toFloat()).coerceIn(0f, 1f)
                    val seekDisplayMs = if (seekDragFraction != null) {
                        (seekDragFraction!! * seekDurationMs).toLong()
                    } else {
                        progressMs
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(seekBarFocusRequester)
                            .focusProperties {
                                up = minimizeFocusRequester
                                down = playPauseFocusRequester
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .tvFocusable(
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.01f,
                                onFocusChange = { isSeekBarFocused = it },
                                onLeftKey = { _ ->
                                    val target = (progressMs - 10_000L).coerceAtLeast(0L)
                                    onSeekTo(target)
                                    showTvHud("⏪ 快退 10 秒 · ${formatDuration(target)}")
                                    true
                                },
                                onRightKey = { _ ->
                                    val maxDur = if (totalDurationMs > 0L) totalDurationMs else Long.MAX_VALUE
                                    val target = (progressMs + 10_000L).coerceAtMost(maxDur)
                                    onSeekTo(target)
                                    showTvHud("⏩ 快进 10 秒 · ${formatDuration(target)}")
                                    true
                                },
                                onClick = {
                                    val nextPlaying = !isPlaying
                                    onTogglePlayPause()
                                    showTvHud(if (nextPlaying) "▶ 继续播放" else "⏸ 已暂停播放")
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                // 轻点立即跳转；按住左右拖动时由本地比例实时接管滑块，松手才提交 seek
                                .pointerInput(seekDurationMs) {
                                    detectTapGestures { offset ->
                                        if (size.width > 0) {
                                            val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            onSeekTo((ratio * seekDurationMs).toLong().coerceAtLeast(0L))
                                        }
                                    }
                                }
                                .pointerInput(seekDurationMs) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            if (size.width > 0) {
                                                seekDragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            }
                                        },
                                        onDragEnd = {
                                            seekDragFraction?.let { ratio ->
                                                onSeekTo((ratio * seekDurationMs).toLong().coerceAtLeast(0L))
                                            }
                                            seekDragFraction = null
                                        },
                                        onDragCancel = { seekDragFraction = null }
                                    ) { change, _ ->
                                        if (size.width > 0) {
                                            seekDragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        }
                                        change.consume()
                                    }
                                },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            val trackHeight = if (isSeekBarFocused) 6.dp else 4.dp
                            val thumbSize = if (isSeekBarFocused) 14.dp else 10.dp
                            val activeColor = if (isSeekBarFocused) AppleRed else primaryTextColor

                            // 底部完整轨道
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(trackHeight)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(primaryTextColor.copy(alpha = 0.22f))
                            )
                            // 已播放高亮轨道
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction)
                                    .height(trackHeight)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(activeColor)
                            )
                            // 进度圆点指示器
                            val availableWidth = (maxWidth - thumbSize).coerceAtLeast(0.dp)
                            Box(
                                modifier = Modifier
                                    .offset(x = availableWidth * progressFraction)
                                    .size(thumbSize)
                                    .clip(CircleShape)
                                    .background(if (isSeekBarFocused) Color.White else activeColor)
                                    .padding(if (isSeekBarFocused) 2.dp else 0.dp)
                                    .clip(CircleShape)
                                    .background(activeColor)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = formatDuration(seekDisplayMs), fontSize = 11.sp, color = secondaryTextColor)
                            if (isSeekBarFocused) {
                                Text(text = "◀ ▶ 左右键快退/快进 10 秒 · OK键暂停/播放", fontSize = 10.5.sp, color = AppleRed, fontWeight = FontWeight.Bold)
                            }
                            Text(text = formatDuration(seekDurationMs), fontSize = 11.sp, color = secondaryTextColor)
                        }
                    }

                    // 4. 5 大主控播放按键（支持遥控器方向键左右移动选中、OK 键操作）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .focusRequester(shuffleFocusRequester)
                                .focusProperties {
                                    up = seekBarFocusRequester
                                    down = queueBtnFocusRequester
                                    left = FocusRequester.Cancel
                                    right = prevFocusRequester
                                }
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.12f,
                                    onClick = {
                                        onToggleShuffle()
                                        showTvHud(if (!isShuffle) "🔀 已开启随机播放" else "➡️ 已切换顺序播放")
                                    }
                                )
                                .clip(CircleShape)
                                .background(if (isShuffle) AppleRed.copy(alpha = 0.18f) else circleButtonBg.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = "随机", tint = if (isShuffle) AppleRed else secondaryTextColor, modifier = Modifier.size(22.dp))
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .focusRequester(prevFocusRequester)
                                .focusProperties {
                                    up = seekBarFocusRequester
                                    down = queueBtnFocusRequester
                                    left = shuffleFocusRequester
                                    right = playPauseFocusRequester
                                }
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.12f,
                                    onClick = {
                                        onPrevious()
                                        showTvHud("⏮ 切换上一首")
                                    }
                                )
                                .clip(CircleShape)
                                .background(circleButtonBg.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = primaryTextColor, modifier = Modifier.size(30.dp))
                        }

                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .focusRequester(playPauseFocusRequester)
                                .focusProperties {
                                    up = seekBarFocusRequester
                                    down = timerBtnFocusRequester
                                    left = prevFocusRequester
                                    right = nextFocusRequester
                                }
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.12f,
                                    focusedBorderColor = AppleRed,
                                    onClick = {
                                        val nextPlaying = !isPlaying
                                        onTogglePlayPause()
                                        showTvHud(if (nextPlaying) "▶ 继续播放" else "⏸ 已暂停播放")
                                    }
                                )
                                .clip(CircleShape)
                                .background(primaryTextColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "播放/暂停", tint = if (isDark) Color.Black else Color.White, modifier = Modifier.size(30.dp))
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .focusRequester(nextFocusRequester)
                                .focusProperties {
                                    up = seekBarFocusRequester
                                    down = infoBtnFocusRequester
                                    left = playPauseFocusRequester
                                    right = repeatFocusRequester
                                }
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.12f,
                                    onClick = {
                                        onNext()
                                        showTvHud("⏭ 切换下一首")
                                    }
                                )
                                .clip(CircleShape)
                                .background(circleButtonBg.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = primaryTextColor, modifier = Modifier.size(30.dp))
                        }

                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .focusRequester(repeatFocusRequester)
                                .focusProperties {
                                    up = seekBarFocusRequester
                                    down = infoBtnFocusRequester
                                    left = nextFocusRequester
                                    right = rightPaneTargetFocus
                                }
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.12f,
                                    onClick = {
                                        onToggleRepeat()
                                        showTvHud(if (!isRepeat) "🔂 已开启单曲循环" else "🔁 已切换列表循环")
                                    }
                                )
                                .clip(CircleShape)
                                .background(if (isRepeat) AppleRed.copy(alpha = 0.18f) else circleButtonBg.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Repeat, contentDescription = "循环", tint = if (isRepeat) AppleRed else secondaryTextColor, modifier = Modifier.size(22.dp))
                        }
                    }

                    // 5. 底部功能工具栏
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                        color = surfaceGlassColor
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .focusRequester(queueBtnFocusRequester)
                                    .focusProperties {
                                        up = shuffleFocusRequester
                                        down = FocusRequester.Cancel
                                        left = FocusRequester.Cancel
                                        right = timerBtnFocusRequester
                                    }
                                    .tvFocusable(
                                        shape = CircleShape,
                                        focusedScale = 1.12f,
                                        onClick = {
                                            landscapeRightPaneMode = if (landscapeRightPaneMode == 1) 0 else 1
                                        }
                                    )
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "待播列表",
                                    tint = if (landscapeRightPaneMode == 1) AppleRed else secondaryTextColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .focusRequester(timerBtnFocusRequester)
                                        .focusProperties {
                                            up = playPauseFocusRequester
                                            down = FocusRequester.Cancel
                                            left = queueBtnFocusRequester
                                            right = infoBtnFocusRequester
                                        }
                                        .tvFocusable(
                                            shape = CircleShape,
                                            focusedScale = 1.12f,
                                            onClick = { showSleepTimerPanel = true }
                                        )
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "定时关闭",
                                        tint = if (activeTimerMinutes > 0) AppleRed else secondaryTextColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                SleepTimerDropdownMenu(
                                    expanded = showSleepTimerPanel,
                                    onDismissRequest = {
                                        showSleepTimerPanel = false
                                        try { timerBtnFocusRequester.requestFocus() } catch (_: Exception) {}
                                    },
                                    activeTimerMinutes = activeTimerMinutes,
                                    onSelectTimer = { min ->
                                        activeTimerMinutes = min
                                        if (min > 0) {
                                            Toast.makeText(context, "已设置：${min}分钟后停止播放", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "已关闭定时器", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }

                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .focusRequester(infoBtnFocusRequester)
                                        .focusProperties {
                                            up = repeatFocusRequester
                                            down = FocusRequester.Cancel
                                            left = timerBtnFocusRequester
                                            right = rightPaneTargetFocus
                                        }
                                        .tvFocusable(
                                            shape = CircleShape,
                                            focusedScale = 1.12f,
                                            onClick = { showAudioSpecsPanel = true }
                                        )
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Info, contentDescription = "参数详情", tint = secondaryTextColor, modifier = Modifier.size(22.dp))
                                }
                                AudioSpecsDropdownMenu(
                                    expanded = showAudioSpecsPanel,
                                    onDismissRequest = {
                                        showAudioSpecsPanel = false
                                        try { infoBtnFocusRequester.requestFocus() } catch (_: Exception) {}
                                    },
                                    song = song
                                )
                            }
                        }
                    }
                }

                // 中间固定间距（原「歌词显示范围调节条 / 左右拖拽分割手柄」已按需求移除，歌词区宽度固定不再可调）
                Spacer(modifier = Modifier.width(14.dp).fillMaxHeight())

                // 右侧共用视窗：[ 实时歌词 ⇄ 待播列表 ] (自适应占比 0% ~ 33.3%)
                if (isLyricsVisible) {
                    Surface(
                        modifier = Modifier
                            .weight(effectiveLyricsRatio)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp)),
                        color = lyricsCardBg
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                        ) {
                            // 右侧视窗 Header 与右上角切换胶囊
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (landscapeRightPaneMode == 0) "实时歌词" else "待播 (${playlist.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = lyricsCardPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                // 右上角一键切换胶囊按键 [ 歌词 | 列表 ]
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = lyricsCardPillBg,
                                    modifier = Modifier
                                        .height(30.dp)
                                        .width(116.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .focusRequester(lyricsTabFocusRequester)
                                                .focusProperties {
                                                    up = FocusRequester.Cancel
                                                    left = favoriteFocusRequester
                                                    right = queueTabFocusRequester
                                                    down = if (landscapeRightPaneMode == 0) lyricsAdjustFocusRequester else if (playlist.isNotEmpty()) firstQueueItemFocusRequester else repeatFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = RoundedCornerShape(10.dp),
                                                    focusedScale = 1.05f,
                                                    onClick = { landscapeRightPaneMode = 0 }
                                                )
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (landscapeRightPaneMode == 0) lyricsCardPillSelectedBg else Color.Transparent),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "歌词",
                                                fontSize = 11.sp,
                                                fontWeight = if (landscapeRightPaneMode == 0) FontWeight.Bold else FontWeight.Normal,
                                                color = if (landscapeRightPaneMode == 0) lyricsCardPrimary else lyricsCardSecondary
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .focusRequester(queueTabFocusRequester)
                                                .focusProperties {
                                                    up = FocusRequester.Cancel
                                                    left = lyricsTabFocusRequester
                                                    right = FocusRequester.Cancel
                                                    down = if (landscapeRightPaneMode == 0) lyricsAdjustFocusRequester else if (playlist.isNotEmpty()) firstQueueItemFocusRequester else repeatFocusRequester
                                                }
                                                .tvFocusable(
                                                    shape = RoundedCornerShape(10.dp),
                                                    focusedScale = 1.05f,
                                                    onClick = { landscapeRightPaneMode = 1 }
                                                )
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (landscapeRightPaneMode == 1) lyricsCardPillSelectedBg else Color.Transparent),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "列表",
                                                fontSize = 11.sp,
                                                fontWeight = if (landscapeRightPaneMode == 1) FontWeight.Bold else FontWeight.Normal,
                                                color = if (landscapeRightPaneMode == 1) lyricsCardPrimary else lyricsCardSecondary
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // 右侧共用视窗内容展示
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (landscapeRightPaneMode == 0) {
                                    LyricsScrollingView(
                                        lyrics = lyrics.lines,
                                        currentPositionMs = progressMs,
                                        onSeekToLyric = onSeekTo,
                                        fontSizeSp = lyricsFontSize,
                                        lyricsOffsetMs = lyricsOffsetMs,
                                        lyricTheme = currentLyricTheme,
                                        onFontSizeChange = updateFontSize,
                                        onOffsetChange = updateOffset,
                                        onThemeChange = updateTheme,
                                        showAdjustButton = true,
                                        // 灰白卡片固定使用深色歌词，保证浅色卡片上的对比度
                                        overrideActiveColor = lyricsCardPrimary,
                                        overrideInactiveColor = lyricsCardSecondary.copy(alpha = 0.72f),
                                        overrideFadeColor = lyricsCardBg,
                                        adjustButtonFocusRequester = lyricsAdjustFocusRequester,
                                        upFocusRequester = lyricsTabFocusRequester,
                                        leftFocusRequester = repeatFocusRequester,
                                        downFocusRequester = infoBtnFocusRequester,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    if (playlist.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("待播队列为空", color = lyricsCardSecondary, fontSize = 13.sp)
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            itemsIndexed(playlist, key = { index, item -> "q_${index}_${item.id}" }) { index, item ->
                                                val isCurrent = item.id == song.id
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .then(if (index == 0) Modifier.focusRequester(firstQueueItemFocusRequester) else Modifier)
                                                        .focusProperties {
                                                            left = repeatFocusRequester
                                                            right = FocusRequester.Cancel
                                                            if (index == 0) up = queueTabFocusRequester
                                                            if (index == playlist.lastIndex) down = FocusRequester.Cancel
                                                        }
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(if (isCurrent) AppleRed.copy(alpha = 0.18f) else Color.Transparent)
                                                        .tvFocusable(
                                                            shape = RoundedCornerShape(10.dp),
                                                            focusedScale = 1.02f,
                                                            onClick = { onSelectSongFromQueue(item) }
                                                        )
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("${index + 1}", color = if (isCurrent) AppleRed else lyricsCardSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(26.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(item.title, color = if (isCurrent) AppleRed else lyricsCardPrimary, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(item.artist, color = lyricsCardSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                    if (isCurrent) {
                                                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) Color(0x28FFFFFF) else Color(0x18000000),
                        border = BorderStroke(0.6.dp, if (isDark) Color(0x33FFFFFF) else Color(0x22000000)),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Lyrics, contentDescription = "展开歌词", tint = AppleRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("歌\n词", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = primaryTextColor, lineHeight = 12.sp)
                        }
                    }
                }
            }
            } // end if (isNeteaseCoverMode) else

            // TV 遥控器动作 HUD 实时反馈气泡 (居中偏上悬浮)
            AnimatedVisibility(
                visible = tvHudMessage != null,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.92f),
                exit = fadeOut(tween(200)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .zIndex(200f)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xEE181A22),
                    border = BorderStroke(1.5.dp, AppleRed.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = tvHudMessage.orEmpty(),
                        fontSize = dimensions.sectionTitleSize,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                }
            }
        } else {
            // =========================================================================
            // 竖屏标准布局：滑动指示器 + 分段切换 + 大图/歌词 Pager + 底部控制器与 3 大工具按键
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. 顶部操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(circleButtonBg)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "最小化播放栏",
                            tint = primaryTextColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 顶部 Segmented Control 胶囊切换: [ 歌曲 | 歌词 ]
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = circleButtonBg,
                        modifier = Modifier
                            .height(34.dp)
                            .width(116.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (pagerState.currentPage == 0) (if (isDark) Color(0xFF3A3A3C) else Color.White) else Color.Transparent)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(0)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "歌曲",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal,
                                        color = if (pagerState.currentPage == 0) primaryTextColor else secondaryTextColor
                                    )
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (pagerState.currentPage == 1) (if (isDark) Color(0xFF3A3A3C) else Color.White) else Color.Transparent)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(1)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "歌词",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal,
                                        color = if (pagerState.currentPage == 1) primaryTextColor else secondaryTextColor
                                    )
                                )
                            }
                        }
                    }

                    // 顶部右侧：收藏按键
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(circleButtonBg)
                                .clickable {
                                    localIsFavorite = !localIsFavorite
                                    onToggleFavorite()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (localIsFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "红心喜欢",
                                tint = if (localIsFavorite) AppleRed else primaryTextColor,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                // 2. 中间 Pager (支持左右手势直接切换 [ 歌曲封面大图 ⇄ 实时全屏歌词 ])
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                ) { page ->
                    if (page == 0) {
                        // 页面 0: 居中大封面展示
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val artworkSize = (configuration.screenWidthDp - 48).dp.coerceIn(280.dp, 400.dp)
                            AlbumArtworkImage(
                                model = song.coverUrl,
                                seedId = song.id,
                                targetSize = 512,
                                modifier = Modifier
                                    .size(artworkSize)
                                    .clip(RoundedCornerShape(24.dp)),
                                cornerRadius = 24.dp
                            )
                        }
                    } else {
                        // 页面 1: 实时滚动全屏歌词
                        LyricsScrollingView(
                            lyrics = lyrics.lines,
                            currentPositionMs = progressMs,
                            onSeekToLyric = onSeekTo,
                            fontSizeSp = lyricsFontSize,
                            lyricsOffsetMs = lyricsOffsetMs,
                            lyricTheme = currentLyricTheme,
                            onFontSizeChange = updateFontSize,
                            onOffsetChange = updateOffset,
                            onThemeChange = updateTheme,
                            showAdjustButton = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 3. 歌曲元数据：标题、歌手与专辑 + 加入歌单与下载按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = song.title,
                            style = TextStyle(
                                fontSize = 22.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold,
                                color = primaryTextColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${song.artist} — ${if (song.album.isNotBlank()) song.album else "精选单曲"}",
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = secondaryTextColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 加入歌单按钮 (音频共享式展出)
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showPortraitAddToPlaylistMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "加入歌单",
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            AddToPlaylistDropdownMenu(
                                expanded = showPortraitAddToPlaylistMenu,
                                onDismissRequest = { showPortraitAddToPlaylistMenu = false },
                                song = song,
                                playlists = allPlaylists,
                                isServerConnected = isServerConnected,
                                onSelectPlaylist = { pl, s ->
                                    onAddToPlaylist(pl, s)
                                    Toast.makeText(context, "已添加至歌单: ${pl.name}", Toast.LENGTH_SHORT).show()
                                },
                                onCreatePlaylistAndAdd = { name, s ->
                                    onCreatePlaylistAndAddSong(name, s)
                                    Toast.makeText(context, "已创建歌单 \"$name\" 并添加歌曲", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        // 下载与缓存选择按钮 (支持本地/服务器/双端同步选择)
                        val hasPhysicalLocal = !song.localFilePath.isNullOrBlank() && 
                            (song.localFilePath!!.startsWith("content://") || runCatching { java.io.File(song.localFilePath!!).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showDownloadMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                val isDownloaded = hasPhysicalLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
                                val isServerCached = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online"
                                if (isDownloaded && isServerCached) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "双端已同步",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else if (isDownloaded || isServerCached) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircleOutline,
                                        contentDescription = "单端已缓存",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.FileDownload,
                                        contentDescription = "下载",
                                        tint = primaryTextColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            DownloadQualityDropdownMenu(
                                expanded = showDownloadMenu,
                                onDismissRequest = { showDownloadMenu = false },
                                song = song,
                                isServerConnected = isServerConnected,
                                hasLocal = hasPhysicalLocal,
                                hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                                onConfirm = { target, quality ->
                                    showDownloadMenu = false
                                    onDownloadSongWithOptions(song, target, quality)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. 进度条与播放时间
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 时长兜底：部分流媒体内核上报时长为 0，退回本地元数据时长，避免 seek 目标被算成 0
                    val seekDurationMs = if (totalDurationMs > 0L) totalDurationMs else song.durationMs.coerceAtLeast(1L)
                    val progressRatio = seekDragFraction
                        ?: (progressMs.toFloat() / seekDurationMs.toFloat()).coerceIn(0f, 1f)
                    val displayProgressMs = if (seekDragFraction != null) {
                        (seekDragFraction!! * seekDurationMs).toLong()
                    } else {
                        progressMs
                    }
                    Slider(
                        value = progressRatio,
                        onValueChange = { ratio -> seekDragFraction = ratio },
                        onValueChangeFinished = {
                            seekDragFraction?.let { ratio ->
                                onSeekTo((ratio * seekDurationMs).toLong().coerceAtLeast(0L))
                            }
                            seekDragFraction = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = primaryTextColor,
                            activeTrackColor = primaryTextColor,
                            inactiveTrackColor = primaryTextColor.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth().height(20.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(displayProgressMs),
                            style = TextStyle(fontSize = 12.sp, color = secondaryTextColor)
                        )
                        Text(
                            text = formatDuration(seekDurationMs),
                            style = TextStyle(fontSize = 12.sp, color = secondaryTextColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5. 核心 5 大播放控制按键
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "随机播放",
                            tint = if (isShuffle) AppleRed else primaryTextColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(onClick = onPrevious) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            tint = primaryTextColor,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // 大号实心主播放键
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(primaryTextColor)
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = if (isDark) Color.Black else Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    IconButton(onClick = onNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            tint = primaryTextColor,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    IconButton(onClick = onToggleRepeat) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = "单曲循环",
                            tint = if (isRepeat) AppleRed else primaryTextColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 6. 底部 3 大功能工具栏: [ ≡ 播放列表 ] [ ⏱ 定时关闭 ] [ ⓘ 参数详情 ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "播放列表",
                            tint = secondaryTextColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showSleepTimerPanel = true }) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "定时关闭",
                                tint = if (activeTimerMinutes > 0) AppleRed else secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        SleepTimerDropdownMenu(
                            expanded = showSleepTimerPanel,
                            onDismissRequest = { showSleepTimerPanel = false },
                            activeTimerMinutes = activeTimerMinutes,
                            onSelectTimer = { min ->
                                activeTimerMinutes = min
                                if (min > 0) {
                                    Toast.makeText(context, "已设置：${min}分钟后停止播放", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "已关闭定时器", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    Box {
                        IconButton(onClick = { showAudioSpecsPanel = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "音频参数详情",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        AudioSpecsDropdownMenu(
                            expanded = showAudioSpecsPanel,
                            onDismissRequest = { showAudioSpecsPanel = false },
                            song = song
                        )
                    }
                }
            }
        }



        // 4. 待播队列弹窗 (竖屏)
        if (showQueueSheet) {
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "待播队列 (${playlist.size} 首)",
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight(0.6f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(playlist, key = { index, item -> "qp_${index}_${item.id}" }) { index, item ->
                            val isCurrent = item.id == song.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isCurrent) AppleRed.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable {
                                        onSelectSongFromQueue(item)
                                        showQueueSheet = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${index + 1}", color = if (isCurrent) AppleRed else secondaryTextColor, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, color = if (isCurrent) AppleRed else primaryTextColor, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                    Text(item.artist, color = secondaryTextColor, fontSize = 11.sp, maxLines = 1)
                                }
                                if (isCurrent) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }


    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
}

@Composable
private fun SpecRowItem(
    label: String,
    value: String,
    isBold: Boolean = false,
    isPath: Boolean = false,
    valueColor: Color? = null
) {
    val primaryTextColor = if (MaterialTheme.colorScheme.background.red < 0.5f) Color.White else Color.Black
    val secondaryTextColor = if (MaterialTheme.colorScheme.background.red < 0.5f) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (isPath) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = secondaryTextColor,
            modifier = Modifier.width(68.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor ?: primaryTextColor,
            textAlign = TextAlign.End,
            maxLines = if (isPath) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SleepTimerDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    activeTimerMinutes: Int,
    onSelectTimer: (Int) -> Unit
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                kotlinx.coroutines.delay(60L)
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 220.dp, max = 280.dp)
    ) {
        Text(
            text = if (activeTimerMinutes > 0) "已设置：${activeTimerMinutes}分钟后停止" else "睡眠定时关闭",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

        val presets = listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟", 90 to "90 分钟", -1 to "播完当前曲")
        presets.forEachIndexed { index, (min, label) ->
            val isSelected = activeTimerMinutes == min
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(24.dp)) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = label,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                },
                onClick = {
                    onSelectTimer(min)
                    onDismissRequest()
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                    .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(10.dp))
            )
        }

        if (activeTimerMinutes > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
            DropdownMenuItem(
                text = {
                    Text(
                        text = "关闭定时器",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                },
                onClick = {
                    onSelectTimer(0)
                    onDismissRequest()
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(10.dp))
            )
        }
    }
}

@Composable
fun AudioSpecsDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong
) {
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                kotlinx.coroutines.delay(60L)
                closeFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val context = LocalContext.current
    val isLocal = !song.localFilePath.isNullOrBlank()
    val streamQualityVer by com.lm.player.core.network.LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    val (formatStr, effectiveBitRate, sizeText) = remember(song.id, song.localFilePath, song.format, song.bitRate, song.serverId, song.streamUrl, isLocal, streamQualityVer) {
        val isOnlineTrial = !isLocal && (
            song.serverId == "lemon_online" ||
            song.id.startsWith("lemon_online_") ||
            song.streamUrl.contains("/api/play/proxy")
        )
        when {
            isLocal -> com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
            isOnlineTrial -> {
                val q = AudioQuality.fromKey(com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context))
                Triple(q.format.uppercase(), q.bitrate, q.estimateSizeText(song))
            }
            else -> com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
        }
    }
    val isLossless = formatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || effectiveBitRate >= 800
    val qualityTag = if (isLossless) "Hi-Res 无损母带" else if (effectiveBitRate >= 320) "极高品质音频" else "标准音频"

    val locationText: String = if (isLocal) (song.localFilePath ?: "本地存储") else (song.streamUrl.takeIf { it.isNotBlank() } ?: "柠檬在线流")

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .widthIn(min = 270.dp, max = 330.dp)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFD4AF37).copy(alpha = 0.2f)) {
                    Text(if (isLossless) "Hi-Res" else "Audio", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4AF37), modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("音频参数详情", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .size(26.dp)
                    .focusRequester(closeFocusRequester)
                    .tvButtonFocusable(shape = RoundedCornerShape(13.dp))
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SpecRowItem("音质等级", qualityTag, valueColor = if (isLossless) Color(0xFFD4AF37) else AppleRed)
            SpecRowItem("编码格式", formatStr, isBold = true)
            SpecRowItem("音频码率", "$effectiveBitRate kbps")
            SpecRowItem("文件大小", sizeText, isBold = true, valueColor = AppleRed)
            SpecRowItem("存储位置", locationText, isPath = true)
            SpecRowItem("播放通道", if (isLocal) "本地硬件直解" else "柠檬无损推流", valueColor = AppleRed)
        }
    }
}

@Composable
fun AudioOutputDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong
) {
    val context = LocalContext.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 240.dp, max = 300.dp)
    ) {
        Text(
            text = "音频输出与共享",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("本机扬声器 (当前通道)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("高保真硬件直出", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            onClick = {
                Toast.makeText(context, "当前已在使用设备扬声器输出", Toast.LENGTH_SHORT).show()
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("车载蓝牙 / 无线耳机", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("随系统音频路由自动切换", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            onClick = {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "请在系统设置中连接车载蓝牙", Toast.LENGTH_SHORT).show()
                }
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cast, contentDescription = null, tint = Color(0xFF5856D6), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("无线投屏 (Cast / AirPlay)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("投送至车机大屏或家庭音响", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            onClick = {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "请在系统控制中心选择投屏设备", Toast.LENGTH_SHORT).show()
                }
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("分享当前歌曲", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${song.title} — ${song.artist}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            onClick = {
                try {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "正在播放: ${song.title}")
                        putExtra(android.content.Intent.EXTRA_TEXT, "我正在使用 ZDS 车载音乐播放器收听 《${song.title}》 - ${song.artist}")
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "分享歌曲至"))
                } catch (_: Exception) {
                    Toast.makeText(context, "无法启动系统分享", Toast.LENGTH_SHORT).show()
                }
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )
    }
}

/**
 * 播放界面主题选择下拉列表菜单（如同“收藏到歌单”的列表选择方式）
 */
@Composable
fun PlayerThemeDropdownMenu(
    expanded: Boolean,
    currentTheme: com.lm.player.core.designsystem.theme.PlayerThemeStyle,
    onDismissRequest: () -> Unit,
    onSelectTheme: (com.lm.player.core.designsystem.theme.PlayerThemeStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocusRequester = remember { FocusRequester() }
    val selectableThemes = remember {
        listOf(
            com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN,
            com.lm.player.core.designsystem.theme.PlayerThemeStyle.NETEASE_TV_COVER
        )
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                kotlinx.coroutines.delay(60L)
                firstFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 270.dp, max = 330.dp)
    ) {
        Text(
            text = "播放界面主题",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
        Text(
            text = "选择您偏好的全屏播放视觉样式",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        selectableThemes.forEachIndexed { index, themeStyle ->
            val isSelected = themeStyle == currentTheme
            val iconVec = when (themeStyle) {
                com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN -> Icons.Default.Album
                com.lm.player.core.designsystem.theme.PlayerThemeStyle.NETEASE_TV_COVER -> Icons.Default.Wallpaper
                else -> Icons.Default.Palette
            }
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) AppleRed.copy(alpha = 0.16f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = iconVec,
                                    contentDescription = null,
                                    tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = themeStyle.displayName,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = themeStyle.description,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = {
                    onSelectTheme(themeStyle)
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                    .tvButtonFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}


