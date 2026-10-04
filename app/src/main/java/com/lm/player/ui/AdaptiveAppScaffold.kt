package com.lm.player.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.R
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.model.PlatformMode
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.SpecBadgeBitrateColor
import com.lm.player.core.designsystem.theme.SpecBadgeQualityColor
import com.lm.player.core.designsystem.theme.SpecBadgeSizeColor
import com.lm.player.core.designsystem.theme.rememberVinylRotation
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.designsystem.theme.specBadgeBorder
import com.lm.player.core.designsystem.theme.specBadgeContainer
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.LyricResult
import com.lm.player.core.model.Screen
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.feature.player.LyricsScrollingView
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * 柠檬音乐 TV 版 — 酷我 TV 风格全局响应式脚手架
 * - 顶部全局导航栏：
 *   * 左上角：程序原生图标 (R.drawable.app_logo) +「柠檬音乐」标题
 *   * 居中五大页面切换按钮：「我的」「搜索」「发现」「曲库」「设置」
 *   * 右上角：「下载管理」按钮（带任务角标）+ 原有逻辑「在线/本地切换按钮」(ServerSwitchDropdownButton)
 * - 下方主区域左右分栏：
 *   * 左侧 30% 常驻黑胶唱片播控大卡（点击封面进入全屏歌词播放页，带旋转黑胶与 5 行滚动歌词、红心收藏、进度展示与上一曲/播放暂停/下一曲）
 *   * 右侧 70% 动态主内容舞台
 */
@Composable
fun AdaptiveAppScaffold(
    windowSizeClass: WindowWidthSizeClass,
    currentScreen: Screen,
    isSearchOpen: Boolean = false,
    isFullPlayerVisible: Boolean = false,
    currentPlayingSong: UnifiedSong?,
    isPlaying: Boolean,
    /**
     * 播放进度**以取数函数传入**，不在参数位置上取值。
     *
     * 进度每 0.5~1 秒就变一次，如果在这里收的是一个 Long，调用方 (MainActivity 的顶层组合作用域)
     * 就必须先读一次状态才能把值传进来 —— 那一读等于把整个 Activity 组合树登记成了进度的观察者，
     * 每次进度刷新都会重组整棵树 (3200+ 行，含所有页面)。
     * 传函数则只有下面真正取值的播控卡会被重组。
     */
    progressMsProvider: () -> Long = { 0L },
    totalDurationMs: Long = 0L,
    currentLyrics: LyricResult = LyricResult(),
    blurAlpha: Float = 0.85f,
    enableBottomBarAnimation: Boolean = true,
    useLinearAnimation: Boolean = true,
    currentServerName: String = "柠檬音乐",
    configuredServers: List<ServerConfig> = emptyList(),
    activeDownloadCount: Int = 0,
    onSelectLocalServer: () -> Unit = {},
    onSelectServer: (ServerConfig) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    isShuffle: Boolean = false,
    isRepeat: Boolean = false,
    onTogglePlayMode: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onNavigate: (Screen) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val dimensions = LocalAppDimensions.current
    val discoverTabFocusRequester = remember { FocusRequester() }
    val vinylCardFocusRequester = remember { FocusRequester() }
    var wasFullPlayerVisible by remember { mutableStateOf(false) }

    // 首次启动默认将遥控器焦点定位于顶部「发现」Tab
    LaunchedEffect(Unit) {
        try {
            discoverTabFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // 从全屏播放器退出时，自动将焦点归还到左侧黑胶大卡
    LaunchedEffect(isFullPlayerVisible) {
        if (isFullPlayerVisible) {
            wasFullPlayerVisible = true
        } else if (wasFullPlayerVisible) {
            wasFullPlayerVisible = false
            try {
                kotlinx.coroutines.delay(60L)
                if (currentPlayingSong != null) {
                    vinylCardFocusRequester.requestFocus()
                } else {
                    discoverTabFocusRequester.requestFocus()
                }
            } catch (_: Exception) {}
        }
    }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    // remember 住 Brush：脚手架每次重组都会重算，否则会持续产生 shader 垃圾
    val bgBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF161922),
                    Color(0xFF0E1017),
                    Color(0xFF090B10)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFF4F5F9),
                    Color(0xFFE9ECF4)
                )
            )
        }
    }

    val effectiveScreen = if (isSearchOpen) Screen.SEARCH else currentScreen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        // ==================== 1. 顶部全局导航栏 ====================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensions.scale(54.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左上角：程序原生图标 +「柠檬音乐」标题 + 平台版本标识。
            // 标识随「设置 → 使用平台模式」切换：车机模式显示「车机版」，电视模式显示「TV版」。
            // 读的是 LocalPlatformMode，切模式后立即重组，无需重启。
            val isCarPlatformMode = LocalPlatformMode.current == PlatformMode.CAR
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = if (isCarPlatformMode) "柠檬音乐 车机版" else "柠檬音乐 TV版",
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Text(
                    text = "柠檬音乐",
                    fontSize = dimensions.pageTitleSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AppleRed.copy(alpha = 0.14f),
                    border = BorderStroke(0.8.dp, AppleRed.copy(alpha = 0.40f))
                ) {
                    Text(
                        text = if (isCarPlatformMode) "车机版" else "TV版",
                        fontSize = dimensions.badgeSize,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // 中间：四大核心页面切换按钮（我的 · 搜索 · 发现 · 设置），光标移到即自动切换
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                KuwoTopTabButton(
                    label = "我的",
                    isSelected = effectiveScreen == Screen.MINE || effectiveScreen == Screen.LIBRARY || effectiveScreen == Screen.DOWNLOADS,
                    onClick = { onNavigate(Screen.MINE) }
                )
                KuwoTopTabButton(
                    label = "搜索",
                    isSelected = effectiveScreen == Screen.SEARCH,
                    onClick = { onNavigate(Screen.SEARCH) }
                )
                KuwoTopTabButton(
                    label = "发现",
                    isSelected = effectiveScreen == Screen.HOME,
                    modifier = Modifier.focusRequester(discoverTabFocusRequester),
                    onClick = { onNavigate(Screen.HOME) }
                )
                KuwoTopTabButton(
                    label = "设置",
                    isSelected = effectiveScreen == Screen.SETTINGS,
                    onClick = { onNavigate(Screen.SETTINGS) }
                )
            }

            // 右上角：平衡占位（原服务器切换与下载按钮已融合至「我的」页面卡片中）
            Box(modifier = Modifier.width(dimensions.scale(116.dp)))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ==================== 2. 下方主舞台：左侧 30% 常驻黑胶播控大卡 + 右侧 70% 内容区 ====================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 左侧常驻黑胶唱片播控大卡 (~29% 宽度)
            TvPersistentVinylPlayerCard(
                song = currentPlayingSong,
                isPlaying = isPlaying,
                progressMsProvider = progressMsProvider,
                totalDurationMs = totalDurationMs,
                currentLyrics = currentLyrics,
                blurAlpha = blurAlpha,
                cardFocusRequester = vinylCardFocusRequester,
                onOpenFullPlayer = onOpenFullPlayer,
                onToggleFavorite = onToggleFavorite,
                isShuffle = isShuffle,
                isRepeat = isRepeat,
                onTogglePlayMode = onTogglePlayMode,
                onSeekTo = onSeekTo,
                onPrevious = onPrevious,
                onTogglePlay = onPlayPauseToggle,
                onNext = onNext,
                modifier = Modifier
                    .width(dimensions.scale(276.dp))
                    .fillMaxHeight()
            )

            // 右侧 70% 动态主内容舞台
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                content(PaddingValues(bottom = 12.dp))
            }
        }
    }
}

/**
 * 酷我 TV 风格顶部导航按钮（带底部金色/高亮指示条，遥控器光标移入即自动切换页面）
 */
@Composable
private fun KuwoTopTabButton(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val goldColor = Color(0xFFFFC947)
    val dimensions = LocalAppDimensions.current
    val textColor = if (isSelected) {
        goldColor
    } else {
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
    }

    // 触屏点按与「焦点即切换」会各触发一次 onClick —— 手指点一下顶部导航会切换两次
    // （重复入栈、重复触发同步）。用指针按压标记区分来源：手指按下 → 本次焦点变化不执行切换，
    // 交给点击本身处理；遥控器方向键不产生按压事件 → 焦点变化照常切换。
    // 抬手即复位，避免标记残留把下一次遥控器切换误吞。
    var switchSuppressedByTouch by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Color.White.copy(alpha = 0.08f) else Color.Transparent,
        modifier = modifier
            // 用 Initial 阶段：父节点先于内部 focusable 的求焦处理收到事件，标记一定先置位
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        switchSuppressedByTouch = true
                        // 持续到本次手势抬手为止：手指移出组件后指针仍由本节点持有，一定能收到 UP
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.none { it.pressed }) break
                        }
                        switchSuppressedByTouch = false
                    }
                }
            }
            .onFocusChanged { focusState ->
                if (focusState.isFocused && !isSelected) {
                    // 手指按压引起的焦点变化不执行切换，交给点击自身触发，避免一次点击切换两次
                    if (!switchSuppressedByTouch) onClick()
                }
            }
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.06f,
                focusedBorderColor = goldColor,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = if (isSelected) dimensions.sectionTitleSize else dimensions.cardHeaderSize,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = textColor
            )
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .width(if (isSelected) 22.dp else 0.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isSelected) goldColor else Color.Transparent)
            )
        }
    }
}

/**
 * 左侧常驻黑胶唱片播控大卡（带连续旋转黑胶唱片 + 5列实时歌词滚动）
 */
@Composable
private fun TvPersistentVinylPlayerCard(
    song: UnifiedSong?,
    isPlaying: Boolean,
    progressMsProvider: () -> Long,
    totalDurationMs: Long,
    currentLyrics: LyricResult,
    blurAlpha: Float,
    cardFocusRequester: FocusRequester,
    onOpenFullPlayer: () -> Unit,
    onToggleFavorite: () -> Unit,
    isShuffle: Boolean = false,
    isRepeat: Boolean = false,
    onTogglePlayMode: () -> Unit = {},
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    // remember 住 Brush：播控卡处于逐帧动画作用域内，每次重组都新建 shader 代价可观
    val cardBg = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF232734).copy(alpha = 0.92f),
                    Color(0xFF171A24).copy(alpha = 0.95f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFFFFF).copy(alpha = 0.94f),
                    Color(0xFFF2F4F8).copy(alpha = 0.96f)
                )
            )
        }
    }

    // 黑胶唱片旋转角度与全屏播放页共享同一全局角度，两处转速与相位始终一致。
    // 返回只读 State：在 graphicsLayer 内延迟读取，逐帧只失效那一层，不触发整卡/整页重组。
    val vinylRotation = rememberVinylRotation(isPlaying)

    // 进度状态在这里才被真正读取：订阅范围因此收窄到本卡片，
    // 而不是上一层的整个 Activity 组合树 (见 progressMsProvider 参数的注释)。
    val progressMs = progressMsProvider()

    val streamQualityVer by LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    val (qualityBadge, bitrateBadge, sizeBadge) = remember(
        song?.id,
        song?.localFilePath,
        song?.downloadStatus,
        song?.format,
        song?.bitRate,
        song?.durationMs,
        song?.serverId,
        song?.streamUrl,
        streamQualityVer
    ) {
        if (song == null) {
            Triple("Hi-Fi", "", "")
        } else {
            val hasLocalFile = !song.localFilePath.isNullOrBlank() &&
                (song.localFilePath.startsWith("content://") || java.io.File(song.localFilePath).exists())
            if (hasLocalFile) {
                val (realExt, realBitRate, realSizeStr) = com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
                val isLossless = realExt in listOf("FLAC", "WAV", "APE", "ALAC", "DSD", "DSF") || realBitRate >= 800
                val qLabel = when {
                    isLossless && realBitRate >= 1200 -> "Hi-Res"
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
                val q = AudioQuality.fromKey(LemonMusicProtocol.getPreferredStreamQuality(context))
                val qLabel = when (q) {
                    AudioQuality.Q_HIRES -> "Hi-Res"
                    AudioQuality.Q_FLAC -> "无损 FLAC"
                    AudioQuality.Q_320K -> "高品质 320K"
                    AudioQuality.Q_128K -> "标准 128K"
                }
                val bLabel = "${q.bitrate} kbps"
                val sLabel = q.estimateSizeText(song)
                Triple(qLabel, bLabel, sLabel)
            }
        }
    }

    val effectiveDuration = if (totalDurationMs > 0L) totalDurationMs else (song?.durationMs ?: 0L)
    // 进度条支持点击/拖动跳转：拖动期间由手势比例接管显示，松手才真正 seek，避免被外部进度弹回
    var cardSeekDragFraction by remember(song?.id) { mutableStateOf<Float?>(null) }
    val actualProgressFraction = if (effectiveDuration > 0L) {
        (progressMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val progressFraction = cardSeekDragFraction ?: actualProgressFraction
    val displayedProgressMs = if (cardSeekDragFraction != null && effectiveDuration > 0L) {
        (cardSeekDragFraction!! * effectiveDuration).toLong()
    } else {
        progressMs
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (isPlaying) Color(0xFFFFC947).copy(alpha = 0.32f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(cardBg)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. 顶部圆形黑胶唱片 + 下方 5 列滚动歌词展示区（移除原「按 OK 展开全屏歌词」提示，在此处放置 5 列歌词）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(dimensions.scale(164.dp))
                        .focusRequester(cardFocusRequester)
                        .tvFocusable(
                            shape = CircleShape,
                            focusedScale = 1.05f,
                            focusedBorderColor = Color(0xFFFFC947),
                            onClick = {
                                if (song != null) {
                                    try {
                                        onOpenFullPlayer()
                                    } catch (_: Exception) {}
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = vinylRotation.value },
                        contentAlignment = Alignment.Center
                    ) {
                        // 黑胶外盘与同心圆纹理
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val radius = size.minDimension / 2f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF2A2D35),
                                        Color(0xFF14161B),
                                        Color(0xFF0B0C10),
                                        Color(0xFF1E2028)
                                    )
                                ),
                                radius = radius
                            )
                            // 黑胶唱片同心音轨圈
                            val ringRadii = listOf(0.92f, 0.85f, 0.78f, 0.71f)
                            for (rFactor in ringRadii) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.06f),
                                    radius = radius * rFactor,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        }

                        // 中心圆形专辑封面
                        Box(
                            modifier = Modifier
                                .size(dimensions.scale(106.dp))
                                .clip(CircleShape)
                                .border(3.dp, Color(0xFF2B2E3B), CircleShape)
                        ) {
                            AlbumArtworkImage(
                                model = song?.coverUrl,
                                seedId = song?.id ?: "tv_vinyl_idle",
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 54.dp
                            )
                        }

                        // 中心黑胶轴孔
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF15171E))
                                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 5列实时滚动歌词（淡入淡出、增大字体、缩小行距）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (song != null) {
                        LyricsScrollingView(
                            lyrics = currentLyrics.lines,
                            currentPositionMs = progressMs,
                            onSeekToLyric = onSeekTo,
                            fontSizeSp = dimensions.cardHeaderSize.value,
                            showAdjustButton = false,
                            visibleLineCount = 5,
                            textAlign = TextAlign.Center,
                            horizontalPaddingDp = 2,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "♪ 选择歌曲开始播放 ♪",
                            fontSize = dimensions.bodySize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 2. 中部歌曲标题、红心收藏+播放模式切换、音质+码率+大小标签、歌手与进度条
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song?.title ?: "柠檬音乐 · 畅享好音质",
                            fontSize = dimensions.cardHeaderSize,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song?.artist ?: "点击右侧歌曲即可播放",
                            fontSize = dimensions.captionSize,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (song != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 喜欢按钮
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .tvFocusable(
                                        shape = CircleShape,
                                        focusedScale = 1.12f,
                                        onClick = onToggleFavorite
                                    )
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (song.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "收藏",
                                    tint = if (song.isFavorite) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            // 喜欢按钮旁边新增：随机播放 / 循环播放 切换按钮
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .tvFocusable(
                                        shape = CircleShape,
                                        focusedScale = 1.12f,
                                        onClick = onTogglePlayMode
                                    )
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                val modeIcon = when {
                                    isShuffle -> Icons.Default.Shuffle
                                    isRepeat -> Icons.Default.RepeatOne
                                    else -> Icons.Default.Repeat
                                }
                                val modeTint = if (isShuffle || isRepeat) {
                                    Color(0xFFFFC947)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Icon(
                                    imageVector = modeIcon,
                                    contentDescription = when {
                                        isShuffle -> "随机播放"
                                        isRepeat -> "单曲循环"
                                        else -> "列表循环"
                                    },
                                    tint = modeTint,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }

                // 音质标签 + 码率标签 + 文件大小标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // 三档规格标签统一配色：音质淡黄 / 码率淡绿 / 大小淡红
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = specBadgeContainer(SpecBadgeQualityColor),
                        border = BorderStroke(0.8.dp, specBadgeBorder(SpecBadgeQualityColor))
                    ) {
                        Text(
                            text = qualityBadge,
                            fontSize = dimensions.badgeSize,
                            fontWeight = FontWeight.Bold,
                            color = SpecBadgeQualityColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                        )
                    }
                    if (bitrateBadge.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = specBadgeContainer(SpecBadgeBitrateColor),
                            border = BorderStroke(0.8.dp, specBadgeBorder(SpecBadgeBitrateColor))
                        ) {
                            Text(
                                text = bitrateBadge,
                                fontSize = dimensions.badgeSize,
                                fontWeight = FontWeight.Bold,
                                color = SpecBadgeBitrateColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                    if (sizeBadge.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = specBadgeContainer(SpecBadgeSizeColor),
                            border = BorderStroke(0.8.dp, specBadgeBorder(SpecBadgeSizeColor))
                        ) {
                            Text(
                                text = sizeBadge,
                                fontSize = dimensions.badgeSize,
                                fontWeight = FontWeight.Bold,
                                color = SpecBadgeSizeColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 进度条（外观与上一版保持一致，仅新增点击/左右拖动跳转能力）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .pointerInput(song?.id, effectiveDuration) {
                            detectTapGestures { offset ->
                                if (size.width > 0 && effectiveDuration > 0L) {
                                    val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    onSeekTo((ratio * effectiveDuration).toLong().coerceAtLeast(0L))
                                }
                            }
                        }
                        .pointerInput(song?.id, effectiveDuration) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    if (size.width > 0 && effectiveDuration > 0L) {
                                        cardSeekDragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    }
                                },
                                onDragEnd = {
                                    cardSeekDragFraction?.let { ratio ->
                                        if (effectiveDuration > 0L) {
                                            onSeekTo((ratio * effectiveDuration).toLong().coerceAtLeast(0L))
                                        }
                                    }
                                    cardSeekDragFraction = null
                                },
                                onDragCancel = { cardSeekDragFraction = null }
                            ) { change, _ ->
                                if (size.width > 0 && effectiveDuration > 0L) {
                                    cardSeekDragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                }
                                change.consume()
                            }
                        }
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFFFC947))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatVinylTime(displayedProgressMs),
                        fontSize = dimensions.badgeSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatVinylTime(effectiveDuration),
                        fontSize = dimensions.badgeSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. 底部三个圆形播控按钮（上一首 · 播放/暂停 · 下一首）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvVinylTransportButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDesc = "上一首",
                    sizeDp = 46,
                    isPrimary = false,
                    onClick = onPrevious
                )
                TvVinylTransportButton(
                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDesc = if (isPlaying) "暂停" else "播放",
                    sizeDp = 56,
                    isPrimary = true,
                    onClick = onTogglePlay
                )
                TvVinylTransportButton(
                    icon = Icons.Default.SkipNext,
                    contentDesc = "下一首",
                    sizeDp = 46,
                    isPrimary = false,
                    onClick = onNext
                )
            }
        }
    }
}

@Composable
private fun TvVinylTransportButton(
    icon: ImageVector,
    contentDesc: String,
    sizeDp: Int,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isPrimary) {
        AppleRed
    } else {
        AppleRed.copy(alpha = 0.88f)
    }

    Surface(
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(
            1.5.dp,
            if (isPrimary) Color.White.copy(alpha = 0.48f) else AppleRed
        ),
        shadowElevation = if (isPrimary) 6.dp else 3.dp,
        modifier = Modifier
            .size(sizeDp.dp)
            .tvFocusable(
                shape = CircleShape,
                focusedScale = 1.14f,
                focusedBorderColor = Color(0xFFFFC947),
                onClick = onClick
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDesc,
                tint = Color.White,
                modifier = Modifier.size(if (isPrimary) 28.dp else 22.dp)
            )
        }
    }
}

private fun formatVinylTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = (ms / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
