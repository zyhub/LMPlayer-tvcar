package com.lm.player.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.media.DownloadEngine
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 柠檬音乐 TV 版 — 酷我 TV 风格「发现」主页 (Discover Home)
 * - 顶部：操作音源一键切换胶囊排（酷我 / 网易 / QQ / 酷狗 / 咪咕）+ 刷新按钮
 * - 上半部 Bento 推荐大卡矩阵：
 *   1. 「今日为你推荐 · 新歌速递」大卡（展示封面、主打新歌、一键畅听与展开列表）
 *   2. 「私人漫游 · 随心听」暖色渐变大卡（一键随机漫游播放）
 *   3. 右侧上下叠放：「官方权威排行榜」快捷卡 + 「新碟首发」快捷卡
 * - 下半部：「精选推荐歌单」大封面横滑画廊、「官方权威排行榜」画廊、「今日新歌首发」曲目流
 */
@Composable
fun LemonDiscoverHomeScreen(
    serverName: String,
    configuredServers: List<ServerConfig> = emptyList(),
    currentSource: OnlineMusicSource,
    onSourceChange: (OnlineMusicSource) -> Unit,
    blurAlpha: Float = 0.85f,
    allCachedSongs: List<UnifiedSong> = emptyList(),
    // provider 而非 List：下载进度流每 ~300ms 换一个新 List 实例，传值会让整页在下载期间持续重组
    // (本工程 Compose 强跳过未生效)。进度交给行内按需读取。
    activeDownloadTasks: () -> List<DownloadTask> = { emptyList() },
    activeDownloadCount: Int = 0,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = true,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onBatchDownloadSongsWithOptions: ((List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit)? = null,
    onSelectLocalServer: () -> Unit,
    onSelectServer: (ServerConfig) -> Unit,
    onSyncNow: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onFetchDiscoverPlaylists: suspend (OnlineMusicSource) -> List<UnifiedPlaylist>,
    onFetchDiscoverToplists: suspend (OnlineMusicSource) -> List<LemonToplist>,
    onFetchDiscoverNewSongs: suspend (OnlineMusicSource) -> List<UnifiedSong>,
    onFetchDiscoverNewAlbums: (suspend (OnlineMusicSource) -> List<UnifiedAlbum>)? = null,
    onParseExternalPlaylist: (suspend (url: String, source: OnlineMusicSource) -> List<UnifiedSong>)? = null,
    onFetchCollectionSongs: suspend (id: String, source: OnlineMusicSource) -> List<UnifiedSong>,
    onSubViewActiveChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val context = androidx.compose.ui.platform.LocalContext.current
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = blurAlpha)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    val goldColor = Color(0xFFFFC947)
    val coroutineScope = rememberCoroutineScope()

    var recommendPlaylists by remember { mutableStateOf<List<UnifiedPlaylist>>(emptyList()) }
    var toplists by remember { mutableStateOf<List<LemonToplist>>(emptyList()) }
    var newSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var newAlbums by remember { mutableStateOf<List<UnifiedAlbum>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadTrigger by remember { mutableStateOf(0) }

    // 缓存下载音质与目标选择弹窗 & 批量下载多选状态
    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }
    var songsForBatchDownloadChoice by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isNewSongsMultiSelect by remember { mutableStateOf(false) }
    val selectedNewSongIds = remember { mutableStateListOf<String>() }
    var isCollectionMultiSelect by remember { mutableStateOf(false) }
    val selectedCollectionSongIds = remember { mutableStateListOf<String>() }

    // 歌单/榜单下钻曲目视图状态
    var activeCollectionTitle by remember { mutableStateOf<String?>(null) }
    var activeCollectionCover by remember { mutableStateOf("") }
    var activeCollectionSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingCollection by remember { mutableStateOf(false) }

    LaunchedEffect(activeCollectionTitle) {
        onSubViewActiveChange(activeCollectionTitle != null)
        if (activeCollectionTitle == null) {
            isCollectionMultiSelect = false
            selectedCollectionSongIds.clear()
        }
    }
    DisposableEffect(Unit) {
        onDispose { onSubViewActiveChange(false) }
    }
    BackHandler(enabled = activeCollectionTitle != null) {
        if (isCollectionMultiSelect) {
            isCollectionMultiSelect = false
            selectedCollectionSongIds.clear()
        } else {
            activeCollectionTitle = null
        }
    }

    val isServerOk = configuredServers.any { it.type == ServerType.LEMON_MUSIC }

    // 记忆键用结构键(歌曲集合+下载状态)，而不是每 300ms 换实例的任务列表：
    // Compose 的 remember 按 equals 比较 key，所以进度推进不会触发这两次整库匹配与磁盘校验，
    // 任务增删或状态变化(如下载完成)时键值变化 → 该重算的立刻重算。
    val activeTaskMatchKey = DownloadEngine.structuralMatchKey(activeDownloadTasks())

    val resolvedNewSongs = remember(newSongs, allCachedSongs, activeTaskMatchKey) {
        val tasks = activeDownloadTasks()
        if (allCachedSongs.isEmpty()) newSongs
        else SongMatchingResolver.resolveSongList(newSongs, allCachedSongs, tasks, downloadDir = null)
    }

    val resolvedCollectionSongs = remember(activeCollectionSongs, allCachedSongs, activeTaskMatchKey, activeCollectionCover) {
        val tasks = activeDownloadTasks()
        val songsWithCover = activeCollectionSongs.map { s ->
            if (s.coverUrl.isNullOrBlank() && !activeCollectionCover.isNullOrBlank()) {
                s.copy(coverUrl = activeCollectionCover)
            } else {
                s
            }
        }
        if (allCachedSongs.isEmpty()) songsWithCover
        else SongMatchingResolver.resolveSongList(songsWithCover, allCachedSongs, tasks, downloadDir = null)
    }

    LaunchedEffect(currentSource, reloadTrigger) {
        if (recommendPlaylists.isEmpty() && toplists.isEmpty() && newSongs.isEmpty() && newAlbums.isEmpty()) {
            isLoading = true
        }
        coroutineScope {
            launch(Dispatchers.IO) {
                val pl = runCatching { onFetchDiscoverPlaylists(currentSource) }.getOrDefault(emptyList())
                if (pl.isNotEmpty()) {
                    val finalPl = if (reloadTrigger > 0) pl.shuffled() else pl
                    withContext(Dispatchers.Main) {
                        recommendPlaylists = finalPl
                        isLoading = false
                    }
                }
            }
            launch(Dispatchers.IO) {
                val tl = runCatching { onFetchDiscoverToplists(currentSource) }.getOrDefault(emptyList())
                if (tl.isNotEmpty()) {
                    val finalTl = if (reloadTrigger > 0) tl.shuffled() else tl
                    withContext(Dispatchers.Main) {
                        toplists = finalTl
                        isLoading = false
                    }
                }
            }
            launch(Dispatchers.IO) {
                val ns = runCatching { onFetchDiscoverNewSongs(currentSource) }.getOrDefault(emptyList())
                if (ns.isNotEmpty()) {
                    val finalNs = if (reloadTrigger > 0) ns.shuffled() else ns
                    withContext(Dispatchers.Main) {
                        newSongs = finalNs
                        isLoading = false
                    }
                }
            }
            if (onFetchDiscoverNewAlbums != null) {
                launch(Dispatchers.IO) {
                    val na = runCatching { onFetchDiscoverNewAlbums(currentSource) }.getOrDefault(emptyList())
                    if (na.isNotEmpty()) {
                        val finalNa = if (reloadTrigger > 0) na.shuffled() else na
                        withContext(Dispatchers.Main) {
                            newAlbums = finalNa
                            isLoading = false
                        }
                    }
                }
            }
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (activeCollectionTitle == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 2.dp,
                    bottom = contentPadding.calculateBottomPadding() + 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ==================== 1. 顶部音源快捷切换条 ====================
                item(key = "discover_source_bar") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "推荐音源:",
                                fontSize = dimensions.bodySize,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OnlineMusicSource.entries.forEach { src ->
                                val selected = src == currentSource
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) goldColor.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) goldColor else Color.White.copy(alpha = 0.12f)
                                    ),
                                    modifier = Modifier.tvFocusable(
                                        shape = RoundedCornerShape(14.dp),
                                        focusedScale = 1.06f,
                                        focusedBorderColor = goldColor,
                                        onClick = { onSourceChange(src) }
                                    )
                                ) {
                                    Text(
                                        text = src.shortName,
                                        fontSize = dimensions.captionSize,
                                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (selected) goldColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.07f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            modifier = Modifier.tvFocusable(
                                shape = RoundedCornerShape(14.dp),
                                focusedScale = 1.06f,
                                focusedBorderColor = goldColor,
                                onClick = {
                                    com.lm.player.core.network.LemonMusicProtocol.clearDiscoverCache()
                                    if (recommendPlaylists.size > 1) {
                                        recommendPlaylists = recommendPlaylists.shuffled()
                                    }
                                    if (toplists.size > 1) {
                                        toplists = toplists.shuffled()
                                    }
                                    if (newSongs.size > 1) {
                                        newSongs = newSongs.shuffled()
                                    }
                                    reloadTrigger++
                                    android.widget.Toast.makeText(context, "已为您换一批推荐内容", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "刷新推荐",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "换一批",
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // ==================== 2. 酷我 TV 风格上半部 Bento 推荐大卡矩阵 ====================
                item(key = "discover_bento_hero") {
                    val featuredSong = resolvedNewSongs.firstOrNull() ?: allCachedSongs.firstOrNull()
                    val secondSong = resolvedNewSongs.getOrNull(1) ?: allCachedSongs.getOrNull(1) ?: featuredSong
                    val topRank = toplists.firstOrNull()
                    val topAlbum = newAlbums.firstOrNull()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimensions.scale(178.dp)),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 大卡 1：今日为你推荐 / 新歌首发
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            modifier = Modifier
                                .weight(1.15f)
                                .fillMaxHeight()
                                .tvFocusable(
                                    shape = RoundedCornerShape(22.dp),
                                    focusedScale = 1.03f,
                                    focusedBorderColor = goldColor,
                                    onClick = {
                                        if (resolvedNewSongs.isNotEmpty()) {
                                            onSongClick(resolvedNewSongs.first(), resolvedNewSongs)
                                        } else if (allCachedSongs.isNotEmpty()) {
                                            onSongClick(allCachedSongs.first(), allCachedSongs)
                                        }
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFF2B3964),
                                                Color(0xFF1B2440)
                                            )
                                        )
                                    )
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "今日为你推荐",
                                            fontSize = dimensions.sectionTitleSize,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "${currentSource.displayName} · 新歌速递 (${resolvedNewSongs.size}首)",
                                            fontSize = dimensions.badgeSize,
                                            color = Color.White.copy(alpha = 0.72f)
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        AlbumArtworkImage(
                                            model = featuredSong?.coverUrl,
                                            seedId = featuredSong?.id ?: "hero_rec",
                                            modifier = Modifier.size(dimensions.scale(72.dp)),
                                            cornerRadius = 14.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = featuredSong?.title ?: "正在加载新歌推荐...",
                                                fontSize = dimensions.itemTitleSize,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = featuredSong?.artist ?: "按 OK 立即畅听",
                                                fontSize = dimensions.captionSize,
                                                color = Color.White.copy(alpha = 0.75f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.22f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "播放",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 大卡 2：私人漫游 / 猜你喜欢
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            modifier = Modifier
                                .weight(1.05f)
                                .fillMaxHeight()
                                .tvFocusable(
                                    shape = RoundedCornerShape(22.dp),
                                    focusedScale = 1.03f,
                                    focusedBorderColor = goldColor,
                                    onClick = {
                                        val pool = (resolvedNewSongs + allCachedSongs).distinctBy { it.id }.shuffled()
                                        pool.firstOrNull()?.let { onSongClick(it, pool) }
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFF563B2D),
                                                Color(0xFF33221A)
                                            )
                                        )
                                    )
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "私人漫游",
                                            fontSize = dimensions.sectionTitleSize,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "从喜欢的歌听起 · 随机漫游",
                                            fontSize = dimensions.badgeSize,
                                            color = Color.White.copy(alpha = 0.72f)
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
                                            AlbumArtworkImage(
                                                model = secondSong?.coverUrl,
                                                seedId = secondSong?.id ?: "roam_1",
                                                modifier = Modifier
                                                    .size(68.dp)
                                                    .border(2.dp, Color(0xFF33221A), RoundedCornerShape(14.dp)),
                                                cornerRadius = 14.dp
                                            )
                                            val thirdSong = resolvedNewSongs.getOrNull(2) ?: allCachedSongs.getOrNull(2)
                                            if (thirdSong != null) {
                                                AlbumArtworkImage(
                                                    model = thirdSong.coverUrl,
                                                    seedId = thirdSong.id,
                                                    modifier = Modifier
                                                        .size(68.dp)
                                                        .border(2.dp, Color(0xFF33221A), RoundedCornerShape(14.dp)),
                                                    cornerRadius = 14.dp
                                                )
                                            }
                                        }

                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 10.dp)
                                        ) {
                                            Text(
                                                text = secondSong?.title ?: "随心漫游电台",
                                                fontSize = dimensions.itemTitleSize,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "按 OK 键开启漫游",
                                                fontSize = dimensions.badgeSize,
                                                color = goldColor,
                                                maxLines = 1
                                            )
                                        }

                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.22f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Shuffle,
                                                    contentDescription = "漫游",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 右侧上下叠放两张快捷卡：排行榜 + 新歌/新碟列表
                        Column(
                            modifier = Modifier
                                .weight(0.92f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 右上卡：热门排行榜
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xFF282D3E),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .tvFocusable(
                                        shape = RoundedCornerShape(18.dp),
                                        focusedScale = 1.04f,
                                        focusedBorderColor = goldColor,
                                        onClick = {
                                            val targetToplist = topRank
                                            if (targetToplist != null) {
                                                activeCollectionTitle = targetToplist.name
                                                activeCollectionCover = targetToplist.coverUrl
                                                isLoadingCollection = true
                                                val tlId = "lemon_toplist_${targetToplist.source}_${targetToplist.id}"
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val fetched = runCatching { onFetchCollectionSongs(tlId, currentSource) }.getOrDefault(emptyList())
                                                    withContext(Dispatchers.Main) {
                                                        activeCollectionSongs = fetched
                                                        isLoadingCollection = false
                                                    }
                                                }
                                            }
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "排行榜",
                                            fontSize = dimensions.cardHeaderSize,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = topRank?.name ?: "${currentSource.shortName}巅峰热歌榜",
                                            fontSize = dimensions.badgeSize,
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    AlbumArtworkImage(
                                        model = topRank?.coverUrl,
                                        seedId = topRank?.id ?: "rank_hero",
                                        modifier = Modifier.size(dimensions.scale(54.dp)),
                                        cornerRadius = 12.dp
                                    )
                                }
                            }

                            // 右下卡：新歌首发全集 / 新碟首发
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xFF282D3E),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .tvFocusable(
                                        shape = RoundedCornerShape(18.dp),
                                        focusedScale = 1.04f,
                                        focusedBorderColor = goldColor,
                                        onClick = {
                                            if (newSongs.isNotEmpty()) {
                                                activeCollectionTitle = "${currentSource.displayName} · 新歌首发"
                                                activeCollectionCover = newSongs.firstOrNull()?.coverUrl.orEmpty()
                                                activeCollectionSongs = newSongs
                                                isLoadingCollection = false
                                            } else if (topAlbum != null) {
                                                activeCollectionTitle = topAlbum.title
                                                activeCollectionCover = topAlbum.coverUrl
                                                isLoadingCollection = true
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val fetched = runCatching { onFetchCollectionSongs(topAlbum.id, currentSource) }.getOrDefault(emptyList())
                                                    withContext(Dispatchers.Main) {
                                                        activeCollectionSongs = fetched
                                                        isLoadingCollection = false
                                                    }
                                                }
                                            }
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "新歌首发",
                                            fontSize = dimensions.cardHeaderSize,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "共 ${resolvedNewSongs.size} 首潮流新歌 >",
                                            fontSize = dimensions.badgeSize,
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    AlbumArtworkImage(
                                        model = topAlbum?.coverUrl ?: featuredSong?.coverUrl,
                                        seedId = topAlbum?.id ?: "album_hero",
                                        modifier = Modifier.size(dimensions.scale(54.dp)),
                                        cornerRadius = 12.dp
                                    )
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    item(key = "discover_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(34.dp), color = goldColor)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "正在同步 ${currentSource.displayName} 推荐歌单与榜单...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = dimensions.bodySize
                                )
                            }
                        }
                    }
                } else {
                    // ==================== 3. 推荐歌单（酷我 TV 风格大封面卡片行） ====================
                    if (recommendPlaylists.isNotEmpty()) {
                        item(key = "discover_rec_playlists_section") {
                            Column {
                                SectionHeader(
                                    title = "推荐歌单",
                                    subtitle = "来自 ${currentSource.displayName} 精选歌单 · 按 OK 展开曲目"
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    itemsIndexed(
                                        items = recommendPlaylists,
                                        key = { index, item -> "rec_pl_${index}_${item.id}" },
                                        contentType = { _, _ -> "discover_playlist" }
                                    ) { _, playlist ->
                                        DiscoverPlaylistCard(
                                            playlist = playlist,
                                            onClick = {
                                                activeCollectionTitle = playlist.name
                                                activeCollectionCover = playlist.coverUrl
                                                isLoadingCollection = true
                                                val plId = playlist.id
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val fetched = runCatching { onFetchCollectionSongs(plId, currentSource) }.getOrDefault(emptyList())
                                                    withContext(Dispatchers.Main) {
                                                        activeCollectionSongs = fetched
                                                        isLoadingCollection = false
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ==================== 4. 官方权威排行榜 ====================
                    if (toplists.isNotEmpty()) {
                        item(key = "discover_toplists_section") {
                            Column {
                                SectionHeader(
                                    title = "官方权威排行榜",
                                    subtitle = "${currentSource.displayName} 潮流热播榜单"
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    itemsIndexed(
                                        items = toplists,
                                        key = { index, item -> "toplist_${index}_${item.id}" },
                                        contentType = { _, _ -> "discover_toplist" }
                                    ) { _, toplist ->
                                        DiscoverToplistCard(
                                            toplist = toplist,
                                            onClick = {
                                                activeCollectionTitle = toplist.name
                                                activeCollectionCover = toplist.coverUrl
                                                isLoadingCollection = true
                                                val tlId = "lemon_toplist_${toplist.source}_${toplist.id}"
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val fetched = runCatching { onFetchCollectionSongs(tlId, currentSource) }.getOrDefault(emptyList())
                                                    withContext(Dispatchers.Main) {
                                                        activeCollectionSongs = fetched
                                                        isLoadingCollection = false
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ==================== 5. 新歌首发即点即播 ====================
                    if (resolvedNewSongs.isNotEmpty()) {
                        val displayNewSongs = resolvedNewSongs.take(20)
                        item(key = "discover_new_songs_header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionHeader(
                                    title = "新歌首发 · 即点即播",
                                    subtitle = "遥控器选中按 OK 键立即播放，按菜单键可下载缓存"
                                )
                                SongListPlayAndBatchDownloadBar(
                                    totalCount = displayNewSongs.size,
                                    isMultiSelectMode = isNewSongsMultiSelect,
                                    selectedCount = selectedNewSongIds.size,
                                    onPlayAll = {
                                        displayNewSongs.firstOrNull()?.let { onSongClick(it, displayNewSongs) }
                                    },
                                    onEnterMultiSelect = {
                                        isNewSongsMultiSelect = true
                                        selectedNewSongIds.clear()
                                    },
                                    onExitMultiSelect = {
                                        isNewSongsMultiSelect = false
                                        selectedNewSongIds.clear()
                                    },
                                    onSelectAllToggle = {
                                        if (selectedNewSongIds.size == displayNewSongs.size) {
                                            selectedNewSongIds.clear()
                                        } else {
                                            selectedNewSongIds.clear()
                                            selectedNewSongIds.addAll(displayNewSongs.map { it.id })
                                        }
                                    },
                                    onBatchDownloadClick = {
                                        val selected = displayNewSongs.filter { it.id in selectedNewSongIds }
                                        if (selected.isNotEmpty()) {
                                            songsForBatchDownloadChoice = selected
                                        }
                                    }
                                )
                            }
                        }

                        itemsIndexed(
                            items = displayNewSongs,
                            key = { index, item -> "new_song_${index}_${item.id}" },
                            contentType = { _, _ -> "new_song_row" }
                        ) { _, song ->
                            val isSelected = song.id in selectedNewSongIds
                            SongListItemRow(
                                song = song,
                                activeDownloadTasks = activeDownloadTasks,
                                isServerConnected = isServerOk,
                                currentPlayingSong = currentPlayingSong,
                                isPlaying = isPlaying,
                                isMultiSelectMode = isNewSongsMultiSelect,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    if (isSelected) selectedNewSongIds.remove(song.id) else selectedNewSongIds.add(song.id)
                                },
                                onClick = { onSongClick(song, resolvedNewSongs) },
                                onDownloadClick = { songForDownloadChoice = song },
                                onDownloadWithOptions = { s, target, quality ->
                                    onDownloadSongWithOptions(s, target, quality)
                                },
                                onOpenDownloads = onOpenDownloads
                            )
                        }
                    }

                    // 空状态提示
                    if (recommendPlaylists.isEmpty() && toplists.isEmpty() && newSongs.isEmpty()) {
                        item(key = "discover_empty_state") {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = surfaceColor,
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MusicOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(dimensions.scale(44.dp))
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "暂未载入发现内容",
                                        fontSize = dimensions.cardHeaderSize,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "请确认已连接柠檬音乐服务端，或尝试切换上方音源（酷我/网易/QQ/酷狗/咪咕）",
                                        fontSize = dimensions.captionSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = {
                                                com.lm.player.core.network.LemonMusicProtocol.clearDiscoverCache()
                                                reloadTrigger++
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.tvButtonFocusable(
                                                shape = RoundedCornerShape(12.dp),
                                                focusedBorderColor = goldColor
                                            )
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("刷新重试", fontSize = dimensions.bodySize)
                                        }
                                        OutlinedButton(
                                            onClick = onGoToSettings,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("前往设置", fontSize = dimensions.bodySize)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ==================== 歌单/榜单下钻曲目详情视图 ====================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier
                            .size(dimensions.scale(40.dp))
                            .tvFocusable(
                                shape = CircleShape,
                                focusedScale = 1.1f,
                                focusedBorderColor = goldColor,
                                onClick = { activeCollectionTitle = null }
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeCollectionTitle ?: "",
                            fontSize = dimensions.sectionTitleSize,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentSource.displayName} · 共 ${activeCollectionSongs.size} 首歌曲",
                            fontSize = dimensions.captionSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (resolvedCollectionSongs.isNotEmpty()) {
                        SongListPlayAndBatchDownloadBar(
                            totalCount = resolvedCollectionSongs.size,
                            isMultiSelectMode = isCollectionMultiSelect,
                            selectedCount = selectedCollectionSongIds.size,
                            onPlayAll = {
                                resolvedCollectionSongs.firstOrNull()?.let { onSongClick(it, resolvedCollectionSongs) }
                            },
                            onEnterMultiSelect = {
                                isCollectionMultiSelect = true
                                selectedCollectionSongIds.clear()
                            },
                            onExitMultiSelect = {
                                isCollectionMultiSelect = false
                                selectedCollectionSongIds.clear()
                            },
                            onSelectAllToggle = {
                                if (selectedCollectionSongIds.size == resolvedCollectionSongs.size) {
                                    selectedCollectionSongIds.clear()
                                } else {
                                    selectedCollectionSongIds.clear()
                                    selectedCollectionSongIds.addAll(resolvedCollectionSongs.map { it.id })
                                }
                            },
                            onBatchDownloadClick = {
                                val selected = resolvedCollectionSongs.filter { it.id in selectedCollectionSongIds }
                                if (selected.isNotEmpty()) {
                                    songsForBatchDownloadChoice = selected
                                }
                            }
                        )
                    }
                }

                if (isLoadingCollection) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(34.dp), color = goldColor)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("正在加载歌单曲目...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = dimensions.bodySize)
                        }
                    }
                } else if (resolvedCollectionSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text("暂无曲目数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = dimensions.bodySize)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = resolvedCollectionSongs,
                            key = { index, item -> "col_song_${index}_${item.id}" },
                            contentType = { _, _ -> "collection_song_row" }
                        ) { _, song ->
                            val isSelected = song.id in selectedCollectionSongIds
                            SongListItemRow(
                                song = song,
                                activeDownloadTasks = activeDownloadTasks,
                                isServerConnected = isServerOk,
                                currentPlayingSong = currentPlayingSong,
                                isPlaying = isPlaying,
                                isMultiSelectMode = isCollectionMultiSelect,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    if (isSelected) selectedCollectionSongIds.remove(song.id) else selectedCollectionSongIds.add(song.id)
                                },
                                onClick = { onSongClick(song, resolvedCollectionSongs) },
                                onDownloadClick = { songForDownloadChoice = song },
                                onDownloadWithOptions = { s, target, quality ->
                                    onDownloadSongWithOptions(s, target, quality)
                                },
                                onOpenDownloads = onOpenDownloads
                            )
                        }
                    }
                }
            }
        }

        if (songForDownloadChoice != null) {
            DownloadQualityChoiceDialog(
                song = songForDownloadChoice!!,
                isServerConnected = isServerOk,
                onConfirm = { target, quality ->
                    onDownloadSongWithOptions(songForDownloadChoice!!, target, quality)
                    songForDownloadChoice = null
                },
                onDismiss = { songForDownloadChoice = null }
            )
        }

        if (songsForBatchDownloadChoice.isNotEmpty()) {
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
                    isNewSongsMultiSelect = false
                    selectedNewSongIds.clear()
                    isCollectionMultiSelect = false
                    selectedCollectionSongIds.clear()
                },
                onDismiss = { songsForBatchDownloadChoice = emptyList() }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String = ""
) {
    val dimensions = LocalAppDimensions.current
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = dimensions.sectionTitleSize,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = dimensions.captionSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

/**
 * 酷我 TV 风格推荐歌单大封面卡片（带右下角播放圆标）
 */
@Composable
private fun DiscoverPlaylistCard(
    playlist: UnifiedPlaylist,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(dimensions.scale(152.dp))
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.05f,
                focusedBorderColor = Color(0xFFFFC947),
                onClick = onClick
            )
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.size(dimensions.scale(144.dp))) {
            AlbumArtworkImage(
                model = playlist.coverUrl,
                seedId = playlist.id,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 16.dp
            )
            // 右下角播放圆钮角标
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = playlist.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = (dimensions.bodySize.value * 1.25f).sp
            )
        )
    }
}

@Composable
private fun DiscoverToplistCard(
    toplist: LemonToplist,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(dimensions.scale(152.dp))
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.05f,
                focusedBorderColor = Color(0xFFFFC947),
                onClick = onClick
            )
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.size(dimensions.scale(144.dp))) {
            AlbumArtworkImage(
                model = toplist.coverUrl,
                seedId = toplist.id,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 16.dp
            )
            if (toplist.updateFrequency.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 10.dp, topEnd = 16.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = toplist.updateFrequency,
                        color = Color.White,
                        fontSize = dimensions.badgeSize,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = toplist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

/**
 * 仿音频输出与共享展出方式的在线音源平台切换下拉菜单
 */
@Composable
fun OnlineSourceDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    currentSource: OnlineMusicSource,
    onSourceSelect: (OnlineMusicSource) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 250.dp, max = 310.dp)
    ) {
        OnlineMusicSource.entries.forEach { src ->
            val isSelected = src == currentSource
            DropdownMenuItem(
                text = {
                    Text(
                        text = src.displayName,
                        fontSize = dimensions.bodySize,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = { onSourceSelect(src) },
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .tvButtonFocusable(shape = RoundedCornerShape(10.dp))
            )
        }
    }
}
