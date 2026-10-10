package com.lm.player.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.ArtistAvatarImage
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.component.TvSongActionDialog
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.HomeScreenDisplayConfig
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedArtist
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 精准解析本地已下载文件的真实封装格式 (支持魔数头 fLaC 校验)、真实码率与真实物理文件大小
 */
fun resolveRealLocalFormatAndSize(song: UnifiedSong): Triple<String, Int, String> {
    val localPath = song.localFilePath
    val localFile = if (!localPath.isNullOrBlank() && !localPath.startsWith("content://")) {
        try {
            java.io.File(localPath).takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) { null }
    } else null

    val detectedExt = if (localFile != null) {
        val magicExt = try {
            if (localFile.length() >= 12L) {
                val h = ByteArray(12)
                java.io.FileInputStream(localFile).use { it.read(h) }
                when {
                    h[0] == 0x66.toByte() && h[1] == 0x4C.toByte() && h[2] == 0x61.toByte() && h[3] == 0x43.toByte() -> "FLAC"
                    h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() -> "WAV"
                    h[0] == 'O'.code.toByte() && h[1] == 'g'.code.toByte() && h[2] == 'g'.code.toByte() && h[3] == 'S'.code.toByte() -> "OGG"
                    h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() && h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte() -> "M4A"
                    else -> null
                }
            } else null
        } catch (_: Exception) { null }
        magicExt ?: localFile.extension.uppercase().ifBlank { song.format.uppercase().ifBlank { "MP3" } }
    } else {
        song.format.uppercase().ifBlank { "MP3" }
    }

    val isLossless = detectedExt in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF")
    val realBitRate = if (localFile != null && song.durationMs >= 15_000L) {
        ((localFile.length() * 8L) / song.durationMs).toInt().coerceIn(64, 4608)
    } else if (isLossless) {
        song.bitRate.coerceAtLeast(960)
    } else {
        if (song.bitRate > 0) song.bitRate else 320
    }

    val sizeStr = if (localFile != null) {
        "%.1f MB".format(java.util.Locale.US, localFile.length() / (1024.0 * 1024.0))
    } else {
        val durationSec = (song.durationMs / 1000L).coerceAtLeast(180L)
        val rate = if (isLossless) 900 else realBitRate.coerceAtLeast(128)
        val estMb = (durationSec * rate * 1000L / 8L) / (1024.0 * 1024.0)
        "%.1f MB".format(java.util.Locale.US, estMb)
    }

    return Triple(detectedExt, realBitRate, sizeStr)
}

/**
 * 格式化已下载音频的技术参数与真实物理文件大小
 */
fun formatDownloadedSongSpecs(song: UnifiedSong): String {
    val (formatStr, realBitRate, sizeStr) = resolveRealLocalFormatAndSize(song)
    return formatDownloadedSongSpecs(formatStr, realBitRate, sizeStr)
}

/**
 * 同上，但直接消费已经算好的三元组。
 * 供歌曲行使用：行内已经 remember 过同一份三元组，再调上面那个版本等于
 * 把"stat + 打开文件读 12 字节魔数"白做第二遍。
 */
fun formatDownloadedSongSpecs(formatStr: String, realBitRate: Int, sizeStr: String): String {
    val isLossless = formatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realBitRate >= 800
    val qualityTag = when {
        isLossless && realBitRate >= 1200 -> "Hi-Res 无损"
        isLossless -> "无损音质"
        realBitRate >= 320 -> "极高音质"
        else -> "标准音质"
    }
    return "$qualityTag • $formatStr • $realBitRate kbps • $sizeStr"
}

/**
 * 全局通用的歌曲列表顶部「播放全部 + 多选批量下载」操作栏（适配 TV 遥控器焦点）
 */
@Composable
fun SongListPlayAndBatchDownloadBar(
    totalCount: Int,
    isMultiSelectMode: Boolean,
    selectedCount: Int,
    onPlayAll: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    onExitMultiSelect: () -> Unit,
    onSelectAllToggle: () -> Unit,
    onBatchDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val goldColor = Color(0xFFFFC947)
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!isMultiSelectMode) {
            Button(
                onClick = onPlayAll,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.tvButtonFocusable(
                    shape = RoundedCornerShape(16.dp),
                    focusedBorderColor = goldColor
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("播放全部 ($totalCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onEnterMultiSelect,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.tvButtonFocusable(
                    shape = RoundedCornerShape(16.dp),
                    focusedBorderColor = goldColor
                )
            ) {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "多选下载",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            val isAllSelected = selectedCount == totalCount && totalCount > 0
            OutlinedButton(
                onClick = onSelectAllToggle,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.tvButtonFocusable(
                    shape = RoundedCornerShape(14.dp),
                    focusedBorderColor = goldColor
                )
            ) {
                Text(
                    if (isAllSelected) "取消全选" else "全选 ($totalCount)",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Button(
                onClick = onBatchDownloadClick,
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                modifier = Modifier.tvButtonFocusable(
                    shape = RoundedCornerShape(14.dp),
                    focusedBorderColor = goldColor
                )
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("下载 ($selectedCount)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = onExitMultiSelect,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
            ) {
                Text("完成", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleRed)
            }
        }
    }
}

/**
 * 歌曲列表单行（TV 遥控器整行获焦 + OK 播放 + 菜单键呼出歌曲操作面板 + 正在播放音波高亮 + 多选批量下载勾选）
 * 全局通用行组件，用于首页、资料库、搜索与歌单
 */
@Composable
fun SongListItemRow(
    song: UnifiedSong,
    isLocalOfflineMode: Boolean = false,
    // provider 而非 List：下载进度流每 ~300ms 换一个新 List 实例，传值会让本行每次进度推进都重组
    // (本工程 Compose 强跳过未生效)。行内用 derivedStateOf 只挑属于自己那一条任务，
    // 没有下载任务的行永远不会因为别人的进度而重组。
    activeDownloadTasks: () -> List<DownloadTask> = { emptyList() },
    isServerConnected: Boolean = true,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = true,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
    onDownloadWithOptions: ((UnifiedSong, DownloadTarget, AudioQuality) -> Unit)? = null,
    onOpenDownloads: () -> Unit = {},
    /** 外部附加修饰符（例如 TV 焦点进入锚点），必须能透传到行根节点上 */
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dimensions = LocalAppDimensions.current
    // 只挑属于自己的那条任务：derivedStateOf 的读取方仅在**本行的结果**变化时才失效，
    // 因此下载别的歌时这一行完全不会被卷进重组；同时仍然能实时拿到自己的下载进度
    val activeTask by remember(song.id, activeDownloadTasks) {
        derivedStateOf { activeDownloadTasks().firstOrNull { it.song.id == song.id } }
    }
    // 磁盘存在性检查并入 remember：原先每次重组都 stat 一次
    val isDownloaded = remember(song.id, song.localFilePath, song.downloadStatus) {
        val hasLocal = !song.localFilePath.isNullOrBlank() &&
                (song.localFilePath.startsWith("content://") || java.io.File(song.localFilePath).exists())
        hasLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
    }
    val isDownloading = activeTask != null || song.downloadStatus == DownloadStatus.DOWNLOADING
    val currentProgress = activeTask?.progress ?: song.downloadProgress
    var showTvActionMenu by remember { mutableStateOf(false) }

    val globalCurrentSong by com.lm.player.core.media.PlaybackQueueManager.currentSongFlow.collectAsState()
    val effectiveCurrentSong = currentPlayingSong ?: globalCurrentSong
    val isCurrentPlaying = com.lm.player.core.designsystem.component.isSamePlayingSong(song, effectiveCurrentSong)
    val streamQualityVer by com.lm.player.core.network.LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    val onlineStreamQualityTag = remember(context, streamQualityVer) {
        val preferredKey = com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context)
        AudioQuality.fromKey(preferredKey).onlineStreamTag()
    }

    if (showTvActionMenu) {
        TvSongActionDialog(
            song = song,
            onDismiss = { showTvActionMenu = false },
            onPlayNow = onClick,
            onDownload = onDownloadClick
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = when {
                    isMultiSelectMode && isSelected -> AppleRed.copy(alpha = 0.10f)
                    isCurrentPlaying -> AppleRed.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(12.dp)
            )
            .tvFocusable(
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.015f,
                onMenuKey = onDownloadClick,
                onClick = {
                    if (isMultiSelectMode && onToggleSelect != null) {
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        AlbumArtworkImage(
            model = song.coverUrl,
            seedId = song.id,
            modifier = Modifier.size(48.dp),
            cornerRadius = 10.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = dimensions.itemTitleSize,
                    fontWeight = if (isCurrentPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrentPlaying) AppleRed else MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.height(2.dp))

            if (isDownloaded) {
                val (realFormatStr, realBitRate, realSizeStr) = remember(song.id, song.localFilePath, song.format, song.bitRate) {
                    resolveRealLocalFormatAndSize(song)
                }
                val isLosslessLocal = realFormatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realBitRate >= 800
                // 已下载歌曲：展示真实音质、码率、大小参数
                Text(
                    text = "${song.artist} • ${if (song.album.isNotBlank()) song.album else "单曲"} ($realFormatStr · $realSizeStr)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = dimensions.captionSize,
                        fontWeight = FontWeight.Normal,
                        color = if (isCurrentPlaying) AppleRed.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF34C759).copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = if (isLosslessLocal && realBitRate >= 1200) "Hi-Res" else if (isLosslessLocal) "无损" else "已离线",
                            color = Color(0xFF34C759),
                            fontSize = dimensions.badgeSize,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        // 复用本行上面已经 remember 过的三元组，避免重复 stat 与二次开文件读魔数
                        text = formatDownloadedSongSpecs(realFormatStr, realBitRate, realSizeStr),
                        fontSize = dimensions.badgeSize,
                        color = Color(0xFF34C759),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = "${song.artist} • ${if (song.album.isNotBlank()) song.album else "单曲"} ($onlineStreamQualityTag)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = dimensions.captionSize,
                        fontWeight = FontWeight.Normal,
                        color = if (isCurrentPlaying) AppleRed.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        if (isCurrentPlaying) {
            Spacer(modifier = Modifier.width(6.dp))
            com.lm.player.core.designsystem.component.NowPlayingWaveIndicator(
                isPlaying = isPlaying
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        val hasLocal = (song.downloadStatus == DownloadStatus.DOWNLOADED) ||
                (!song.localFilePath.isNullOrBlank()) ||
                (song.serverId in listOf("local_storage", "local_folder", "local_saf"))
        val hasServer = (song.serverId == "lemon_music" ||
                (isServerConnected && song.serverId.isNotBlank() && song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")))

        if (onDownloadWithOptions != null) {
            com.lm.player.core.designsystem.component.SongSyncStatusTrailing(
                song = song,
                isDownloading = isDownloading,
                downloadProgress = currentProgress,
                isServerConnected = isServerConnected,
                hasLocal = hasLocal,
                hasServer = hasServer,
                onOpenDownloads = onOpenDownloads,
                onDownloadWithOptions = onDownloadWithOptions
            )
        } else if (isDownloading) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AppleRed.copy(alpha = 0.12f),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenDownloads() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        progress = { currentProgress },
                        modifier = Modifier.size(16.dp),
                        color = AppleRed,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${(currentProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed
                    )
                }
            }
        } else if (isDownloaded) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已离线",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            IconButton(
                onClick = onDownloadClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowCircleDown,
                    contentDescription = "下载歌曲",
                    tint = AppleRed,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * 柠檬音乐 TV 版 —「我的」页面 (展示服务器连接情况、云端同步状态、本地缓存歌曲与最近播放)
 */
@Composable
fun LocalMusicHomeScreen(
    serverName: String = "本地模式",
    recentSongs: List<UnifiedSong>,
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    recentlyAddedSongs: List<UnifiedSong> = emptyList(),
    recentlyPlayedSongs: List<UnifiedSong> = emptyList(),
    discoverPlaylists: List<UnifiedPlaylist> = emptyList(),
    homeDisplayConfig: HomeScreenDisplayConfig = HomeScreenDisplayConfig(),
    // provider 而非 List：理由同 SongListItemRow —— 传值会让整个首页在下载期间每 300ms 重组。
    // activeDownloadCount 走结构流(数量变化才发射)，可以安全地按值传。
    activeDownloadTasks: () -> List<DownloadTask> = { emptyList() },
    activeDownloadCount: Int = 0,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = true,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onBatchDownloadSongsWithOptions: ((List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit)? = null,
    onSelectLocalServer: () -> Unit = {},
    onSelectServer: (ServerConfig) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    onPlaylistClick: ((UnifiedPlaylist) -> Unit)? = null,
    onScanLocalMedia: (() -> Unit)? = null,
    onSubViewActiveChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val cardBgColor = if (isDark) Color(0xFF212532).copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val goldColor = Color(0xFFFFC947)

    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }
    var songsForBatchDownloadChoice by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedSongIds = remember { mutableStateListOf<String>() }
    // 0: 本地已缓存歌曲, 1: 最近播放, 2: 最近入库
    var selectedTabIndex by remember { mutableStateOf(0) }

    val activeLemonServer = remember(configuredServers, serverName) {
        val isLocalMode = serverName.contains("本地") || serverName.contains("已下载")
        if (isLocalMode) {
            null
        } else {
            configuredServers.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                ?: configuredServers.firstOrNull { it.type == ServerType.LEMON_MUSIC }
        }
    }
    val anyConfiguredLemonServer = remember(configuredServers) {
        configuredServers.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
            ?: configuredServers.firstOrNull { it.type == ServerType.LEMON_MUSIC }
    }
    val isOnlineConnected = activeLemonServer != null

    // 本地已下载/缓存的歌曲列表
    val localDownloadedSongs = remember(recentSongs) {
        recentSongs.filter {
            val hasValidFile = !it.localFilePath.isNullOrBlank()
            (it.downloadStatus == DownloadStatus.DOWNLOADED && hasValidFile) ||
                (it.serverId in listOf("local_storage", "local_saf", "local_folder") && hasValidFile)
        }
    }

    // 云端资料库歌曲数量
    val cloudLibrarySongCount = remember(recentSongs, anyConfiguredLemonServer) {
        if (anyConfiguredLemonServer == null) {
            0
        } else {
            recentSongs.count {
                it.serverId == anyConfiguredLemonServer.id ||
                    it.serverId == anyConfiguredLemonServer.serverUrl ||
                    it.serverId == "lemon_music"
            }
        }
    }

    // 估算本地缓存总空间
    // 用 produceState + IO 而不是 remember：曲目多时这里是"每首一次 File.length()"的磁盘 IO，
    // 放在组合期等于卡主线程（与 DownloadManagerScreen 的既有写法保持一致）
    val localCacheSizeText by produceState(initialValue = "0.0 MB", localDownloadedSongs) {
        value = if (localDownloadedSongs.isEmpty()) {
            "0.0 MB"
        } else {
            withContext(Dispatchers.IO) {
                var totalBytes = 0L
                for (s in localDownloadedSongs) {
                    val path = s.localFilePath
                    val fileLen = if (!path.isNullOrBlank() && !path.startsWith("content://")) {
                        try { java.io.File(path).length() } catch (_: Exception) { 0L }
                    } else 0L
                    if (fileLen > 0L) {
                        totalBytes += fileLen
                    } else {
                        val durSec = (s.durationMs / 1000L).coerceAtLeast(180L)
                        val rate = if (s.format.equals("flac", true)) 900L else s.bitRate.coerceAtLeast(128).toLong()
                        totalBytes += (durSec * rate * 1000L) / 8L
                    }
                }
                val mb = totalBytes / (1024.0 * 1024.0)
                if (mb >= 1024.0) {
                    String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
                } else {
                    String.format(java.util.Locale.US, "%.1f MB", mb)
                }
            }
        }
    }

    val effectiveRecentAdded = remember(recentSongs, recentlyAddedSongs) {
        if (recentlyAddedSongs.isNotEmpty()) recentlyAddedSongs else recentSongs.sortedByDescending { it.addedTimestamp }.take(40)
    }

    val displayedSongs = when (selectedTabIndex) {
        0 -> localDownloadedSongs
        1 -> recentlyPlayedSongs
        else -> effectiveRecentAdded
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==================== 1. 顶部双联状态大卡（服务器连接情况 + 本地缓存歌曲概览） ====================
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 左卡：柠檬音乐服务器连接状态卡
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = cardBgColor,
                        border = BorderStroke(
                            1.dp,
                            if (isOnlineConnected) Color(0xFF34C759).copy(alpha = 0.45f) else borderColor
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(178.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isOnlineConnected) Color(0xFF34C759).copy(alpha = 0.18f) else goldColor.copy(alpha = 0.18f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isOnlineConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                                contentDescription = null,
                                                tint = if (isOnlineConnected) Color(0xFF34C759) else goldColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = anyConfiguredLemonServer?.name ?: "柠檬音乐服务端",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = anyConfiguredLemonServer?.serverUrl ?: "暂未配置服务器地址",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isOnlineConnected) Color(0xFF34C759).copy(alpha = 0.18f) else goldColor.copy(alpha = 0.18f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(if (isOnlineConnected) Color(0xFF34C759) else goldColor)
                                        )
                                        Text(
                                            text = if (isOnlineConnected) "在线已连接" else "本地离线模式",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isOnlineConnected) Color(0xFF34C759) else goldColor
                                        )
                                    }
                                }
                            }

                            // 中部云端统计
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "$cloudLibrarySongCount 首",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "服务器云端曲目",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column {
                                    Text(
                                        text = if (anyConfiguredLemonServer != null) (anyConfiguredLemonServer.username.ifBlank { "默认账户" }) else "未登录",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "绑定账号",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 底部操作按钮行
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MineCardActionButton(
                                    icon = Icons.Default.Sync,
                                    label = "同步曲库",
                                    isPrimary = true,
                                    modifier = Modifier.weight(1f),
                                    onClick = onSyncNow
                                )
                                MineCardActionButton(
                                    icon = if (isOnlineConnected) Icons.Default.OfflinePin else Icons.Default.CloudQueue,
                                    label = if (isOnlineConnected) "切至本地" else "切至在线",
                                    isPrimary = false,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (isOnlineConnected) {
                                            onSelectLocalServer()
                                        } else if (anyConfiguredLemonServer != null) {
                                            onSelectServer(anyConfiguredLemonServer)
                                        } else {
                                            onGoToSettings()
                                        }
                                    }
                                )
                                MineCardActionButton(
                                    icon = Icons.Default.Settings,
                                    label = "配置",
                                    isPrimary = false,
                                    modifier = Modifier.weight(0.75f),
                                    onClick = onGoToSettings
                                )
                            }
                        }
                    }

                    // 右卡：本地缓存歌曲与存储管理卡
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = cardBgColor,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(178.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = AppleRed.copy(alpha = 0.18f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.DownloadDone,
                                                contentDescription = null,
                                                tint = AppleRed,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = "本地缓存与离线曲库",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "断网环境下仍可零等待高保真秒播",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (activeDownloadCount > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = AppleRed.copy(alpha = 0.18f)
                                    ) {
                                        Text(
                                            text = "正在下载 $activeDownloadCount 首",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppleRed,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // 中部统计指标
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${localDownloadedSongs.size} 首",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "本机已缓存歌曲",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column {
                                    Text(
                                        text = localCacheSizeText,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = goldColor
                                    )
                                    Text(
                                        text = "已用本地空间",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column {
                                    Text(
                                        text = "${recentlyPlayedSongs.size} 首",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "最近播放记录",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 底部操作按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MineCardActionButton(
                                    icon = Icons.Default.PlayArrow,
                                    label = "播放本地缓存",
                                    isPrimary = true,
                                    modifier = Modifier.weight(1.1f),
                                    onClick = {
                                        val first = localDownloadedSongs.firstOrNull()
                                        if (first != null) {
                                            onSongClick(first, localDownloadedSongs)
                                        }
                                    }
                                )
                                MineCardActionButton(
                                    icon = Icons.Default.FileDownload,
                                    label = "下载管理",
                                    isPrimary = false,
                                    modifier = Modifier.weight(0.95f),
                                    onClick = onOpenDownloads
                                )
                                MineCardActionButton(
                                    icon = Icons.Default.FolderOpen,
                                    label = "扫描存储",
                                    isPrimary = false,
                                    modifier = Modifier.weight(0.95f),
                                    onClick = { (onScanLocalMedia ?: onGoToSettings).invoke() }
                                )
                            }
                        }
                    }
                }
            }

            // ==================== 2. 下半部分：本地缓存歌曲 / 最近播放 / 最近入库 列表 ====================
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MineFilterTabPill(
                            title = "本地已缓存 (${localDownloadedSongs.size})",
                            isSelected = selectedTabIndex == 0,
                            onClick = { selectedTabIndex = 0 }
                        )
                        MineFilterTabPill(
                            title = "最近播放 (${recentlyPlayedSongs.size})",
                            isSelected = selectedTabIndex == 1,
                            onClick = { selectedTabIndex = 1 }
                        )
                        MineFilterTabPill(
                            title = "最近入库 (${effectiveRecentAdded.size})",
                            isSelected = selectedTabIndex == 2,
                            onClick = { selectedTabIndex = 2 }
                        )
                    }

                    if (displayedSongs.isNotEmpty()) {
                        SongListPlayAndBatchDownloadBar(
                            totalCount = displayedSongs.size,
                            isMultiSelectMode = isMultiSelectMode,
                            selectedCount = selectedSongIds.size,
                            onPlayAll = { onSongClick(displayedSongs.first(), displayedSongs) },
                            onEnterMultiSelect = {
                                isMultiSelectMode = true
                                selectedSongIds.clear()
                            },
                            onExitMultiSelect = {
                                isMultiSelectMode = false
                                selectedSongIds.clear()
                            },
                            onSelectAllToggle = {
                                if (selectedSongIds.size == displayedSongs.size) {
                                    selectedSongIds.clear()
                                } else {
                                    selectedSongIds.clear()
                                    selectedSongIds.addAll(displayedSongs.map { it.id })
                                }
                            },
                            onBatchDownloadClick = {
                                val selected = displayedSongs.filter { it.id in selectedSongIds }
                                if (selected.isNotEmpty()) {
                                    songsForBatchDownloadChoice = selected
                                }
                            }
                        )
                    }
                }
            }

            if (displayedSongs.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = cardBgColor,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.LibraryMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = when (selectedTabIndex) {
                                    0 -> "暂无本地已缓存歌曲，可在「发现」或「曲库」中下载歌曲离线聆听"
                                    1 -> "暂无最近播放记录，点播任意歌曲后将自动收录于此"
                                    else -> "暂无曲目记录，请先同步柠檬音乐服务器或扫描本地音频"
                                },
                                fontSize = dimensions.bodySize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = displayedSongs,
                    key = { idx, song -> "mine_song_${selectedTabIndex}_${idx}_${song.id}" },
                    contentType = { _, _ -> "song_row" }
                ) { _, song ->
                    val isServerOk = configuredServers.any { it.type == ServerType.LEMON_MUSIC }
                    val isSelected = song.id in selectedSongIds
                    SongListItemRow(
                        song = song,
                        isLocalOfflineMode = selectedTabIndex == 0,
                        activeDownloadTasks = activeDownloadTasks,
                        isServerConnected = isServerOk,
                        currentPlayingSong = currentPlayingSong,
                        isPlaying = isPlaying,
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = isSelected,
                        onToggleSelect = {
                            if (isSelected) selectedSongIds.remove(song.id) else selectedSongIds.add(song.id)
                        },
                        onClick = { onSongClick(song, displayedSongs) },
                        onDownloadClick = { songForDownloadChoice = song },
                        onDownloadWithOptions = { s, target, quality ->
                            onDownloadSongWithOptions(s, target, quality)
                        },
                        onOpenDownloads = onOpenDownloads
                    )
                }
            }
        }

        if (songForDownloadChoice != null) {
            val isServerOk = configuredServers.any { it.type == ServerType.LEMON_MUSIC }
            DownloadQualityChoiceDialog(
                song = songForDownloadChoice!!,
                isServerConnected = isServerOk,
                onDismiss = { songForDownloadChoice = null },
                onConfirm = { target, quality ->
                    onDownloadSongWithOptions(songForDownloadChoice!!, target, quality)
                    songForDownloadChoice = null
                }
            )
        }

        if (songsForBatchDownloadChoice.isNotEmpty()) {
            val isServerOk = configuredServers.any { it.type == ServerType.LEMON_MUSIC }
            com.lm.player.core.designsystem.component.BatchDownloadQualityChoiceDialog(
                selectedSongs = songsForBatchDownloadChoice,
                isServerConnected = isServerOk,
                onConfirm = { target, quality ->
                    val batchList = songsForBatchDownloadChoice
                    if (onBatchDownloadSongsWithOptions != null) {
                        onBatchDownloadSongsWithOptions(batchList, target, quality)
                    } else {
                        batchList.forEach { s -> onDownloadSongWithOptions(s, target, quality) }
                    }
                    songsForBatchDownloadChoice = emptyList()
                    isMultiSelectMode = false
                    selectedSongIds.clear()
                },
                onDismiss = { songsForBatchDownloadChoice = emptyList() }
            )
        }
    }
}

@Composable
private fun MineCardActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val goldColor = Color(0xFFFFC947)
    val bgColor = if (isPrimary) AppleRed else Color.White.copy(alpha = 0.08f)
    val contentColor = if (isPrimary) Color.White else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = if (isPrimary) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = modifier
            .height(36.dp)
            .tvFocusable(
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.05f,
                focusedBorderColor = goldColor,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MineFilterTabPill(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val goldColor = Color(0xFFFFC947)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) goldColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(
            1.dp,
            if (isSelected) goldColor else Color.White.copy(alpha = 0.12f)
        ),
        modifier = Modifier.tvFocusable(
            shape = RoundedCornerShape(16.dp),
            focusedScale = 1.05f,
            focusedBorderColor = goldColor,
            onClick = onClick
        )
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (isSelected) goldColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

