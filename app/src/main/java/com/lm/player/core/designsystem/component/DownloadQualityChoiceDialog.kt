package com.lm.player.core.designsystem.component

import com.lm.player.core.designsystem.component.TvDialog as Dialog
import com.lm.player.core.designsystem.component.TvDropdownMenu as DropdownMenu
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.UnifiedSong
import androidx.compose.ui.text.TextStyle
import com.lm.player.core.designsystem.theme.scale

/**
 * 歌曲行尾的原位下载菜单（2026-11-13 起内容统一为 [DownloadChoicePanel]，
 * 与播放页、各页面的下载对话框、批量下载同一份界面与逻辑，含本地升级音质并替换旧文件）。
 */
@Composable
fun DownloadQualityDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong,
    isServerConnected: Boolean = true,
    hasLocal: Boolean = false,
    hasServer: Boolean = false,
    @Suppress("UNUSED_PARAMETER") initialTarget: DownloadTarget = DownloadTarget.LOCAL,
    onConfirm: (target: DownloadTarget, quality: AudioQuality) -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = com.lm.player.core.designsystem.theme.LocalAppDimensions.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(dims.scale(330.dp))
    ) {
        DownloadChoicePanel(
            song = song,
            isServerConnected = isServerConnected,
            hasLocal = hasLocal,
            hasServer = hasServer,
            onConfirm = onConfirm,
            onDismiss = onDismissRequest
        )
    }
}

/**
 * 「已在本地」判定口径（单首与批量共用同一份定义，避免两处口径跑偏）。
 */
fun isSongDownloadedLocally(song: UnifiedSong): Boolean =
    song.downloadStatus == com.lm.player.core.model.DownloadStatus.DOWNLOADED ||
        !song.localFilePath.isNullOrBlank() ||
        song.serverId in listOf("local_storage", "local_folder", "local_saf")

/**
 * 「已在柠檬服务器曲库」判定口径（单首与批量共用同一份定义）。
 * 注意 lemon_online 是全网在线曲目、并非服务器曲库已缓存，故不计入。
 */
fun isSongOnLemonServer(song: UnifiedSong, isServerConnected: Boolean): Boolean =
    song.serverId == "lemon_music" ||
        (isServerConnected && song.serverId.isNotBlank() &&
            song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online"))

/**
 * 各页面的下载对话框（2026-11-13 起内容统一为 [DownloadChoicePanel]，与行尾菜单、播放页同一份）。
 * 批量下载复用：[estimateSongs] 按所选歌曲真实总时长汇总预估大小，[titleOverride]/[subtitleOverride] 换成整批措辞；
 * 批量时不提供本地升级（没有单个旧文件可替换）。
 */
@Composable
fun DownloadQualityChoiceDialog(
    song: UnifiedSong,
    isServerConnected: Boolean = true,
    hasLocal: Boolean = isSongDownloadedLocally(song),
    hasServer: Boolean = isSongOnLemonServer(song, isServerConnected),
    @Suppress("UNUSED_PARAMETER") initialTarget: DownloadTarget = DownloadTarget.LOCAL,
    @Suppress("UNUSED_PARAMETER") initialQuality: AudioQuality = AudioQuality.Q_320K,
    estimateSongs: List<UnifiedSong>? = null,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
    onConfirm: (target: DownloadTarget, quality: AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val dims = com.lm.player.core.designsystem.theme.LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceBg = if (isDark) Color(0xFF222228) else Color.White
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceBg,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 24.dp,
            modifier = Modifier.width(dims.scale(380.dp))
        ) {
            DownloadChoicePanel(
                song = song,
                isServerConnected = isServerConnected,
                hasLocal = hasLocal,
                hasServer = hasServer,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                modifier = Modifier.padding(vertical = 10.dp),
                title = titleOverride ?: "下载",
                subtitle = subtitleOverride ?: "${song.title} · ${song.artist}",
                estimateSongs = estimateSongs,
                allowUpgrade = estimateSongs == null,
                showClose = true
            )
        }
    }
}

/**
 * 半截绿色勾图标 (代表仅单方已存/部分已同步)
 */
@Composable
fun HalfGreenCheckIcon(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 18.dp,
    color: Color = Color(0xFF34C759)
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 1.8.dp.toPx()
            // 绘制绿色半圈 (代表单边同步)
            drawArc(
                color = color,
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
            )
            drawArc(
                color = color.copy(alpha = 0.25f),
                startAngle = 270f,
                sweepAngle = 180f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
            )
        }
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "单方已存",
            tint = color,
            modifier = Modifier.size(size * 0.72f)
        )
    }
}

/**
 * 统一的歌曲末尾同步与下载状态操作栏：
 * 1. 双方同步：绿色勾并显示 [服务器 · 本地]
 * 2. 单方下载：半截绿色勾并显示 [仅本地] 或 [仅服务器]，点击弹出置灰单方并展示未下载方的补全下载菜单
 * 3. 双方未下载：常规下载图标，点击展开三端下载菜单
 * 4. 下载中：旋转进度环与百分比
 */
@Composable
fun SongSyncStatusTrailing(
    song: UnifiedSong,
    isDownloading: Boolean,
    downloadProgress: Float,
    isServerConnected: Boolean,
    hasLocal: Boolean,
    hasServer: Boolean,
    onOpenDownloads: () -> Unit,
    onDownloadWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit,
    modifier: Modifier = Modifier
) {
    val isBoth = hasLocal && hasServer
    val isOnlyOne = hasLocal xor hasServer
    var isMenuOpen by remember { mutableStateOf(false) }

    if (isDownloading) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AppleRed.copy(alpha = 0.12f),
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .tvFocusable(
                    shape = RoundedCornerShape(12.dp),
                    onClick = { onOpenDownloads() }
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(16.dp),
                    color = AppleRed,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "${(downloadProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleRed
                )
            }
        }
    } else if (isBoth) {
        // 双端同步：绿色勾，并显示存储位置（服务器、本地）
        Box(modifier = modifier) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .tvFocusable(
                        shape = RoundedCornerShape(8.dp),
                        onClick = { isMenuOpen = true }
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF34C759).copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "服务器 · 本地",
                        color = Color(0xFF34C759),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "双端同步已存储",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(20.dp)
                )
            }

            DownloadQualityDropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                song = song,
                isServerConnected = isServerConnected,
                hasLocal = true,
                hasServer = true,
                onConfirm = { target, quality ->
                    isMenuOpen = false
                    onDownloadWithOptions(song, target, quality)
                }
            )
        }
    } else if (isOnlyOne) {
        // 仅存在一方下载：半截绿色勾，并显示存储位置；点击后显示未下载的一方，已下载的一方显示灰色
        Box(modifier = modifier) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .tvFocusable(
                        shape = RoundedCornerShape(8.dp),
                        onClick = { isMenuOpen = true }
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF34C759).copy(alpha = 0.10f)
                ) {
                    Text(
                        text = if (hasLocal) "仅本地" else "仅服务器",
                        color = Color(0xFF34C759),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                HalfGreenCheckIcon(
                    size = 18.dp,
                    color = Color(0xFF34C759)
                )
            }

            DownloadQualityDropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                song = song,
                isServerConnected = isServerConnected,
                hasLocal = hasLocal,
                hasServer = hasServer,
                onConfirm = { target, quality ->
                    isMenuOpen = false
                    onDownloadWithOptions(song, target, quality)
                }
            )
        }
    } else {
        // 双方均未下载：显示下载按键
        Box(modifier = modifier) {
            IconButton(
                onClick = { isMenuOpen = true },
                modifier = Modifier
                    .size(36.dp)
                    .tvButtonFocusable(shape = RoundedCornerShape(18.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowCircleDown,
                    contentDescription = "下载歌曲",
                    tint = AppleRed,
                    modifier = Modifier.size(22.dp)
                )
            }

            DownloadQualityDropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                song = song,
                isServerConnected = isServerConnected,
                hasLocal = false,
                hasServer = false,
                onConfirm = { target, quality ->
                    isMenuOpen = false
                    onDownloadWithOptions(song, target, quality)
                }
            )
        }
    }
}

/**
 * 批量下载与缓存选择对话框 (支持多选、全选歌曲一键下载到本地/服务器/双端同步)
 *
 * **完全复用单首下载的对话框与判定口径**：以前这里会造一个 `batch_download_N` 的假歌并
 * 硬编码 `hasLocal=false / hasServer=false`，导致无论选了什么，三个存储目标永远全部可点 ——
 * 全是已在服务器的歌也照样提示"下载到服务器"。现在改为按传入的 [selectedSongs] 真实判定：
 * 整批都已缓存的一端会被隐藏/置灰，"双端"在只剩一端时同样置灰，与单首行为完全一致。
 */
@Composable
fun BatchDownloadQualityChoiceDialog(
    selectedSongs: List<UnifiedSong> = emptyList(),
    selectedCount: Int = selectedSongs.size,
    isServerConnected: Boolean = true,
    initialTarget: DownloadTarget = DownloadTarget.LOCAL,
    initialQuality: AudioQuality = AudioQuality.Q_320K,
    onConfirm: (target: DownloadTarget, quality: AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val songs = remember(selectedSongs) { selectedSongs.distinctBy { it.id } }
    val localCount = remember(songs) { songs.count { isSongDownloadedLocally(it) } }
    val serverCount = remember(songs, isServerConnected) { songs.count { isSongOnLemonServer(it, isServerConnected) } }
    // 与单首相同语义：只有"整批都已在某一端"时才算该端已完成
    val hasLocal = songs.isNotEmpty() && localCount == songs.size
    val hasServer = songs.isNotEmpty() && serverCount == songs.size

    val subtitle = remember(selectedCount, localCount, serverCount) {
        buildString {
            append("已选中 $selectedCount 首")
            if (serverCount > 0) append(" · $serverCount 首已在服务器")
            if (localCount > 0) append(" · $localCount 首已在本地")
        }
    }

    // 仅用于承载标题/副标题与大小汇总，不再参与任何判定
    val batchSong = remember(selectedCount) {
        UnifiedSong(
            id = "batch_download_$selectedCount",
            title = "已选中 $selectedCount 首歌曲",
            artist = "批量下载队列",
            album = "",
            durationMs = 0L,
            coverUrl = "",
            streamUrl = "",
            serverId = "lemon_online"
        )
    }

    DownloadQualityChoiceDialog(
        song = batchSong,
        isServerConnected = isServerConnected,
        hasLocal = hasLocal,
        hasServer = hasServer,
        initialTarget = initialTarget,
        initialQuality = initialQuality,
        estimateSongs = songs,
        titleOverride = "批量下载与音质选择",
        subtitleOverride = subtitle,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * 判断列表项歌曲是否为当前正在播放的歌曲 (支持 ID 精确匹配及跨端同版本精确匹配，杜绝 Live/黑胶/伴奏/不同版本误高亮)
 */
fun isSamePlayingSong(song: UnifiedSong, currentPlayingSong: UnifiedSong?): Boolean {
    if (currentPlayingSong == null) return false
    if (song.id == currentPlayingSong.id) return true
    if (!song.localFilePath.isNullOrBlank() && song.localFilePath == currentPlayingSong.localFilePath) {
        return com.lm.player.core.media.SongMatchingResolver.isSongMatch(
            title1 = song.title,
            artist1 = song.artist,
            durationMs1 = song.durationMs,
            title2 = currentPlayingSong.title,
            artist2 = currentPlayingSong.artist,
            durationMs2 = currentPlayingSong.durationMs,
            album1 = song.album,
            album2 = currentPlayingSong.album
        )
    }
    if (!song.streamUrl.isNullOrBlank() &&
        !song.streamUrl.startsWith("lemon_online://") &&
        !song.streamUrl.contains("/api/play/proxy") &&
        song.streamUrl == currentPlayingSong.streamUrl
    ) {
        return com.lm.player.core.media.SongMatchingResolver.isSongMatch(
            title1 = song.title,
            artist1 = song.artist,
            durationMs1 = song.durationMs,
            title2 = currentPlayingSong.title,
            artist2 = currentPlayingSong.artist,
            durationMs2 = currentPlayingSong.durationMs,
            album1 = song.album,
            album2 = currentPlayingSong.album
        )
    }
    val songIsOnline = song.id.startsWith("lemon_online_") || song.serverId == "lemon_online"
    val currentIsOnline = currentPlayingSong.id.startsWith("lemon_online_") || currentPlayingSong.serverId == "lemon_online"
    // 若双方均为全网在线曲目但 ID 不同，或双方均为本地/服务端入库曲目但 ID 不同，绝不跨曲目误高亮
    if (songIsOnline && currentIsOnline) return false
    if (!songIsOnline && !currentIsOnline) return false

    // 若一方为全网在线曲目、另一方为曲库曲目，涉及 Live 现场版时必须同时具备相同非空专辑与相近有效时长才视为同一首
    val songMarkers = com.lm.player.core.media.SongMatchingResolver.extractEditionMarkers(song.title, song.album)
    val currentMarkers = com.lm.player.core.media.SongMatchingResolver.extractEditionMarkers(currentPlayingSong.title, currentPlayingSong.album)
    if ("live" in songMarkers || "live" in currentMarkers) {
        val normAlbum1 = com.lm.player.core.media.SongMatchingResolver.normalizeAlbum(song.album)
        val normAlbum2 = com.lm.player.core.media.SongMatchingResolver.normalizeAlbum(currentPlayingSong.album)
        if (normAlbum1.isBlank() || normAlbum2.isBlank() || normAlbum1 != normAlbum2) return false
        if (song.durationMs <= 0L || currentPlayingSong.durationMs <= 0L ||
            kotlin.math.abs(song.durationMs - currentPlayingSong.durationMs) > 2000L
        ) {
            return false
        }
    }

    return com.lm.player.core.media.SongMatchingResolver.isSongMatch(
        title1 = song.title,
        artist1 = song.artist,
        durationMs1 = song.durationMs,
        title2 = currentPlayingSong.title,
        artist2 = currentPlayingSong.artist,
        durationMs2 = currentPlayingSong.durationMs,
        album1 = song.album,
        album2 = currentPlayingSong.album
    )
}

/**
 * 歌曲后方「正在播放」跳动音波（2026-11-13 性能优化第 4 项）：
 * 以前是 4 条 60 帧的无限动画，而且**暂停时也一直在跑**、每帧改条形高度要重新排版 ——
 * 列表里看得到正在播放的歌时整窗按 60 次/秒重绘。现在挂在共享的 30 帧时钟上、只重画这一小块；
 * 暂停或退到后台时画静止的条形，不订阅时钟。
 */
@Composable
fun NowPlayingWaveIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = AppleRed
) {
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> isAppResumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> isAppResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        modifier = modifier
    ) {
        ClockWaveBars(
            // 性能模式「流畅优先」时静止
            animate = isPlaying && isAppResumed && !com.lm.player.core.designsystem.theme.SmoothMode.isActive,
            color = color,
            barWidth = 2.4.dp,
            gap = 2.2.dp,
            bars = NowPlayingBars,
            staticHeights = listOf(0.35f, 0.65f, 0.45f, 0.30f),
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .size(width = 16.2.dp, height = 14.dp)
        )
    }
}

/** 与原来 4 条动画的高度范围和快慢一致（往返周期 = 原单程时长 × 2） */
private val NowPlayingBars = listOf(
    WaveBarSpec(0.25f, 1.00f, 840f, 0.0),
    WaveBarSpec(0.30f, 0.95f, 720f, 2.1),
    WaveBarSpec(0.40f, 0.95f, 960f, 4.2),
    WaveBarSpec(0.25f, 0.75f, 780f, 1.3)
)
