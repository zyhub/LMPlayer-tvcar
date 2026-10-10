package com.lm.player.feature.library

import android.util.Log
import android.widget.Toast
import java.io.File
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.BatchDownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.MosaicArtworkCollage
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.TvRestoreFocusOnChange
import com.lm.player.core.designsystem.component.tvFocusEntryAnchor
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.*
import com.lm.player.feature.home.SongListItemRow
import com.lm.player.feature.home.SongListPlayAndBatchDownloadBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 现代轻奢音乐资料库 (对标柠檬音乐 Library.vue 架构重构)
 * 包含：
 * 1. 顶部检索与状态指示条 (快速过滤歌曲/歌手/专辑/歌单、刷新同步、新建歌单)
 * 2. 歌单画廊 (我喜欢的音乐、最近播放、自建与云端歌单)
 * 3. 音乐风格流派 (流派气泡筛选)
 * 4. 歌手胶囊流 (歌手头像、曲目数、即点即播)
 * 5. 专辑矩阵 (最新专辑卡片流)
 * 6. 全部歌曲高保真流 (带格式/音质标签、收藏、下载弹窗、多维排序)
 * 7. 页面内无缝下钻视图 (歌单/歌手/专辑/流派详情，不遮挡底部悬浮播放栏)
 */
@Composable
fun LocalLibraryScreen(
    allSongs: List<UnifiedSong>,
    // 「我喜欢的音乐」的数据源由宿主注入：在线模式=服务器收藏，本地/已下载模式=服务器收藏 ∩ 已下载。
    // 为空时回落到 allSongs.filter { isFavorite }，保证断网等异常场景下收藏卡片不会整块消失。
    favoriteSongs: List<UnifiedSong> = emptyList(),
    downloadedSongs: List<UnifiedSong> = emptyList(),
    recentlyPlayedSongs: List<UnifiedSong> = emptyList(),
    playlists: List<UnifiedPlaylist> = emptyList(),
    activeServerConfig: ServerConfig? = null,
    // provider 而非 List：下载进度流每 ~300ms 换一个新 List 实例，传值会让整个曲库页在下载期间
    // 持续重组(本工程 Compose 强跳过未生效)。行内按需读取自己那一条任务。
    activeDownloadTasks: () -> List<DownloadTask> = { emptyList() },
    activeDownloadCount: Int = 0,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = true,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { song, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onBatchDownloadSongsWithOptions: ((List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit)? = null,
    onOpenDownloads: () -> Unit = {},
    onRefreshPlaylists: () -> Unit = {},
    onCreatePlaylist: (name: String, isOnline: Boolean) -> Unit = { _, _ -> },
    onDeletePlaylist: (playlistId: String) -> Unit = {},
    onToggleFavorite: ((UnifiedSong) -> Unit)? = null,
    onFetchPlaylistSongs: (suspend (playlistId: String, isOnline: Boolean) -> List<UnifiedSong>)? = null,
    onFetchServerFolders: (suspend (parentId: String?) -> List<ServerFolderItem>)? = null,
    onFetchServerFolderSongs: (suspend (folderId: String) -> List<UnifiedSong>)? = null,
    onFetchServerGenres: (suspend () -> List<UnifiedGenre>)? = null,
    onFetchServerScanStatus: (suspend () -> LemonScanStatus?)? = null,
    onTriggerServerScan: (suspend () -> Unit)? = null,
    onDeleteLocalFilePath: (suspend (String) -> Unit)? = null,
    onDeleteDownloadedSongs: ((List<UnifiedSong>) -> Unit)? = null,
    initialCategory: LibraryCategory? = null,
    currentServerName: String = "本地模式",
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    onSelectLocalServer: () -> Unit = {},
    onSelectServer: (ServerConfig) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    onSubViewActiveChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    // 排序模式。
    // 程序启动默认按「加入时间」：在线模式 = 服务器文件的 mtime（服务器「最近添加」先后），
    // 本地模式 = 本地下载完成时间。用户点排序按钮后仍可循环切换，重启回到本默认值。
    var songSortMode by remember { mutableStateOf("added") } // added, default, name, artist, duration, source

    // 下钻视图状态：当前正在查看的集合详情 (歌单、歌手、专辑、流派)
    var activeSubViewTitle by remember { mutableStateOf<String?>(null) }
    var activeSubViewSubtitle by remember { mutableStateOf<String>("") }
    var activePlaylistId by remember { mutableStateOf<String?>(null) }
    var activeSubViewSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingSubView by remember { mutableStateOf(false) }
    var isFromAllPlaylists by remember { mutableStateOf(false) }
    var isFromAllFolders by remember { mutableStateOf(false) }
    var isFromAllArtists by remember { mutableStateOf(false) }
    var isFromAllAlbums by remember { mutableStateOf(false) }

    LaunchedEffect(activeSubViewTitle) {
        onSubViewActiveChange(activeSubViewTitle != null)
    }
    DisposableEffect(Unit) {
        onDispose { onSubViewActiveChange(false) }
    }

    // 本地文件夹目录结构动态聚合 (根据相对路径或本地物理路径聚合并提取目录名，过滤服务端 JSON 元数据)
    val localFolders = remember(allSongs) {
        allSongs.mapNotNull { song ->
            val rawRelPath = song.relativeFolderPath?.trim()
            val validRelPath = if (
                !rawRelPath.isNullOrBlank() &&
                !rawRelPath.startsWith("{") &&
                !rawRelPath.contains("\"") &&
                !rawRelPath.contains("_id__") &&
                !rawRelPath.startsWith("http")
            ) rawRelPath else null

            val folderName = when {
                validRelPath != null -> {
                    val p = validRelPath.replace('\\', '/')
                    p.trimEnd('/').substringAfterLast('/')
                }
                !song.localFilePath.isNullOrBlank() -> {
                    val p = song.localFilePath.replace('\\', '/').trimEnd('/')
                    p.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { null }
                }
                else -> null
            }?.trim()?.ifBlank { null }
            if (folderName != null && folderName !in listOf("0", "emulated", "sdcard", "storage")) {
                folderName to song
            } else null
        }.groupBy({ it.first }, { it.second })
        .map { (folderName, songs) ->
            UnifiedFolder(
                id = "folder_${folderName.hashCode()}",
                name = folderName,
                path = songs.firstOrNull()?.let { s ->
                    val rp = s.relativeFolderPath?.trim()
                    if (!rp.isNullOrBlank() && !rp.startsWith("{") && !rp.contains("\"")) rp else s.localFilePath
                } ?: "",
                songCount = songs.size,
                songs = songs
            )
        }.sortedByDescending { it.songCount }
    }

    // 本地下载离线歌曲聚合：直接关联外部已下载全量曲目（与下载管理器对齐），若未传入则从 allSongs 纯内存快速聚合
    val finalDownloadedSongs = remember(allSongs, downloadedSongs) {
        val validLocal = { s: UnifiedSong ->
            !s.localFilePath.isNullOrBlank() && (s.localFilePath!!.startsWith("content://") || java.io.File(s.localFilePath!!).exists())
        }
        if (downloadedSongs.isNotEmpty()) {
            downloadedSongs.filter(validLocal)
        } else {
            allSongs.filter(validLocal)
        }
    }
    // 最近播放聚合：优先使用宿主注入的真实播放足迹，退回本地库前 30 首兜底展示
    // 最近播放：只用真实播放足迹 (在线时为服务器记录，离线时为本地持久化记录)，
    // 不再用「曲库前 30 首」冒充，以免播放历史为空时展示与事实不符的内容
    val recentPlaySongs = recentlyPlayedSongs

    var isDownloadManagementMode by remember { mutableStateOf(false) }
    val selectedDownloadSongIds = remember { mutableStateListOf<String>() }
    var showBatchDeleteLocalDialog by remember { mutableStateOf(false) }

    // 我喜欢的音乐实时聚合与下钻视图联动刷新。
    // 优先用宿主注入的服务器收藏口径，只有宿主拿不到时才退回本地 isFavorite 标记。
    val favSongs = remember(allSongs, favoriteSongs) {
        if (favoriteSongs.isNotEmpty()) favoriteSongs else allSongs.filter { it.isFavorite }
    }
    LaunchedEffect(favSongs) {
        if (activeSubViewTitle == "我喜欢的音乐") {
            activeSubViewSongs = favSongs
            activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
        }
    }

    // 当处于本地下载视图时，若下载任务完成或变动，实时响应刷新
    LaunchedEffect(finalDownloadedSongs) {
        if (activeSubViewTitle == "本地下载") {
            activeSubViewSongs = finalDownloadedSongs
            activeSubViewSubtitle = "本机离线歌曲 · 共 ${finalDownloadedSongs.size} 首"
        }
    }

    // 多选批量下载状态
    var songsForBatchDownloadChoice by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isMainListMultiSelect by remember { mutableStateOf(false) }
    val selectedMainSongIds = remember { mutableStateListOf<String>() }
    var isSubViewMultiSelect by remember { mutableStateOf(false) }
    val selectedSubViewSongIds = remember { mutableStateListOf<String>() }

    // 动态聚合数据
    val artists = remember(allSongs) {
        val countMap = allSongs.groupingBy { it.artist.ifBlank { "未知歌手" } }.eachCount()
        allSongs.groupBy { it.artist.ifBlank { "未知歌手" } }
            .map { (artistName, songs) ->
                val sCount = countMap[artistName] ?: songs.size
                UnifiedArtist(
                    id = "artist_${artistName.hashCode()}",
                    name = artistName,
                    avatarUrl = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: "",
                    albumCount = songs.map { it.album }.distinct().size,
                    songCount = sCount
                )
            }.sortedByDescending { it.songCount }
    }

    val albums = remember(allSongs) {
        allSongs.groupBy { it.album.ifBlank { "单曲精选" } }
            .map { (albumName, songs) ->
                UnifiedAlbum(
                    id = "album_${albumName.hashCode()}",
                    title = albumName,
                    artist = songs.firstOrNull()?.artist ?: "各种艺术家",
                    coverUrl = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: "",
                    songCount = songs.size
                )
            }.sortedByDescending { it.songCount }
    }

    // 自动同步服务器歌单 (进入资料库或服务器配置就绪时自动拉取)
    LaunchedEffect(activeServerConfig?.id) {
        if (activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC) {
            onRefreshPlaylists()
        }
    }

    // 层级返回调度器：如果从「全部歌单」、「全部文件夹」、「全部歌手」或「全部专辑」进入下级详情，先返回上层网格，再返回资料库首页
    val handleSubViewBack: () -> Unit = {
        if (isDownloadManagementMode) {
            isDownloadManagementMode = false
            selectedDownloadSongIds.clear()
        } else if (isSubViewMultiSelect) {
            isSubViewMultiSelect = false
            selectedSubViewSongIds.clear()
        } else if (activeSubViewTitle != "全部歌单" && isFromAllPlaylists) {
            activeSubViewTitle = "全部歌单"
            activeSubViewSubtitle = "共 ${playlists.size + 3} 个歌单"
            isFromAllPlaylists = false
        } else if (activeSubViewTitle != "全部文件夹" && isFromAllFolders) {
            activeSubViewTitle = "全部文件夹"
            activeSubViewSubtitle = "共 ${localFolders.size} 个本地文件夹"
            isFromAllFolders = false
        } else if (activeSubViewTitle != "全部歌手" && isFromAllArtists) {
            activeSubViewTitle = "全部歌手"
            activeSubViewSubtitle = "共 ${artists.size} 位歌手"
            isFromAllArtists = false
        } else if (activeSubViewTitle != "全部专辑" && isFromAllAlbums) {
            activeSubViewTitle = "全部专辑"
            activeSubViewSubtitle = "共 ${albums.size} 张专辑"
            isFromAllAlbums = false
        } else {
            activeSubViewTitle = null
            activePlaylistId = null
            isFromAllPlaylists = false
            isFromAllFolders = false
            isFromAllArtists = false
            isFromAllAlbums = false
        }
    }

    // 触屏滑动返回或物理按键退出下钻视图与多选模式
    BackHandler(enabled = activeSubViewTitle != null || isMainListMultiSelect) {
        if (activeSubViewTitle == null && isMainListMultiSelect) {
            isMainListMultiSelect = false
            selectedMainSongIds.clear()
        } else {
            handleSubViewBack()
        }
    }

    // 下载选择弹窗
    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }

    // 创建歌单弹窗
    var isCreatePlaylistDialogOpen by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistIsOnline by remember { mutableStateOf(activeServerConfig != null && !currentServerName.contains("本地") && !currentServerName.contains("已下载")) }
    val isServerOk = activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC

    // 监听新建歌单弹窗打开事件，动态同步在线开关
    LaunchedEffect(isCreatePlaylistDialogOpen) {
        if (isCreatePlaylistDialogOpen) {
            newPlaylistName = ""
            newPlaylistIsOnline = (activeServerConfig != null && !currentServerName.contains("本地") && !currentServerName.contains("已下载"))
        }
    }

    // 常用风格流派列表 (本地离线兜底)
    val defaultGenres = listOf("流行", "摇滚", "民谣", "电子", "爵士", "古典", "纯音乐", "ACG", "嘻哈", "华语", "欧美")

    var serverGenres by remember { mutableStateOf<List<UnifiedGenre>>(emptyList()) }
    var serverScanStatus by remember { mutableStateOf<LemonScanStatus?>(null) }
    var isTriggeringScan by remember { mutableStateOf(false) }

    LaunchedEffect(activeServerConfig) {
        if (activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC) {
            if (onFetchServerGenres != null) {
                coroutineScope.launch {
                    val g = runCatching { withContext(Dispatchers.IO) { onFetchServerGenres() } }.getOrDefault(emptyList())
                    if (g.isNotEmpty()) serverGenres = g
                }
            }
            if (onFetchServerScanStatus != null) {
                coroutineScope.launch {
                    val s = runCatching { withContext(Dispatchers.IO) { onFetchServerScanStatus() } }.getOrNull()
                    serverScanStatus = s
                }
            }
        } else {
            serverGenres = emptyList()
            serverScanStatus = null
        }
    }

    // 判定曲目的加入方式与来源归属
    fun getSongSourceTypeRank(song: UnifiedSong): Int {
        return when {
            song.serverId in listOf("local_folder", "local_storage", "local_saf") -> 1 // 本地扫描
            song.id.startsWith("lemon_online_") || (song.downloadStatus == DownloadStatus.DOWNLOADED && !song.localFilePath.isNullOrBlank() && song.serverId !in listOf("lemon_music") && !song.serverId.startsWith("srv_")) -> 2 // 在线下载
            song.serverId == "lemon_music" || song.serverId.startsWith("srv_") || (song.serverId.isNotBlank() && song.serverId != "lemon_online") -> 3 // 服务端同步
            else -> 4 // 外部歌单导入
        }
    }

    fun getSongSourceTypeName(song: UnifiedSong): String {
        return when {
            song.serverId in listOf("local_folder", "local_storage", "local_saf") -> "本地目录扫描"
            song.id.startsWith("lemon_online_") || (song.downloadStatus == DownloadStatus.DOWNLOADED && !song.localFilePath.isNullOrBlank() && song.serverId !in listOf("lemon_music") && !song.serverId.startsWith("srv_")) -> "在线下载缓存"
            song.serverId == "lemon_music" || song.serverId.startsWith("srv_") || (song.serverId.isNotBlank() && song.serverId != "lemon_online") -> "云端服务同步"
            else -> "外部歌单导入"
        }
    }

    // 排序后的歌曲列表
    val filteredSongs = remember(allSongs, songSortMode) {
        when (songSortMode) {
            "name" -> allSongs.sortedBy { it.title }
            "artist" -> allSongs.sortedBy { it.artist }
            "duration" -> allSongs.sortedByDescending { it.durationMs }
            "source" -> allSongs.sortedWith(
                compareBy<UnifiedSong> { getSongSourceTypeRank(it) }
                    .thenByDescending { it.addedTimestamp }
                    .thenBy { it.title }
            )
            // 按加入时间倒序（新加入的在前）。时间戳缺失(0)的极少数条目自然排在末尾，属预期。
            // addedTimestamp 的口径由调用方按模式注入，这里不区分：
            //   在线模式 = 服务器文件的 mtime（即服务器「最近添加」的先后）
            //   本地模式 = 本地下载完成时间（纯扫描入库的文件回落到文件修改时间）
            "added" -> allSongs.sortedWith(
                compareByDescending<UnifiedSong> { it.addedTimestamp }.thenBy { it.title }
            )
            else -> allSongs
        }
    }

    // 最近添加歌曲切片 (优先聚合已下载或有明确添加时间戳的曲目，按 addedTimestamp 倒序排序取前20首)
    val recentAddedSongs = remember(allSongs) {
        val downloadedOrTimestamped = allSongs.filter { it.downloadStatus == DownloadStatus.DOWNLOADED || it.addedTimestamp > 0 }
        if (downloadedOrTimestamped.isNotEmpty()) {
            downloadedOrTimestamped.sortedWith(
                compareByDescending<UnifiedSong> { it.addedTimestamp }
                    .thenByDescending { it.downloadStatus == DownloadStatus.DOWNLOADED }
            ).take(20)
        } else {
            allSongs.sortedByDescending { it.addedTimestamp }.take(20)
        }
    }

    // 列表滚动状态必须建在 if 分支**之外**：分支内创建会随子树销毁而丢失，
    // 表现为「下钻后返回，滚动位置回到顶部」；而且焦点恢复依赖目标项在视口内 ——
    // 目标项不在视口内时 LazyList 里根本没有该节点，requestFocus 必然失败。
    val mainListState = rememberLazyListState()
    val subViewListState = rememberLazyListState()
    // 横向板块同理：不提升 state 时，下钻返回后横向滚动位置也会丢，
    // 目标胶囊若被滚出视口则无法恢复焦点。
    val artistRowState = rememberLazyListState()

    // 下钻进入侧锚点令牌（**派生**）。
    //
    // 需求：光标点击文件夹/歌手/专辑/歌单展开列表时，焦点自动落到**列表第一项**。
    //
    // 为什么必须派生而不是「点击时自增」：部分下钻（歌单）的曲目是异步拉取的，
    // 点击那一刻 activeSubViewSongs 还是空的，列表里没有可聚焦的行，锚点必然失败；
    // 等数据回来时令牌早已不再变化。派生令牌恰好在「列表首次真正有内容」的那次重组变化。
    //
    // 用 isLoadingSubView 作为门控：加载中不抢焦点，避免把焦点丢在空列表上。
    // 令牌只反映「进入了某个下钻视图且内容已就绪」，**不带曲目数量**。
    // 带数量会导致列表增删（多选删除、下载状态刷新）时令牌变化、锚点被再次触发，
    // 把用户正在浏览的焦点抢回首行。锚点的语义只是「进入时的落点」。
    val subViewEntryToken by remember {
        derivedStateOf {
            if (activeSubViewTitle != null && !isLoadingSubView && activeSubViewSongs.isNotEmpty()) {
                activeSubViewTitle
            } else null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val isLocalMode = activeServerConfig == null || currentServerName.contains("本地") || currentServerName.contains("已下载")
        // 主视图：浏览「我的 (融合在线曲库与本地缓存)」各大板块 (当没有进入二级下钻时展示)
        if (activeSubViewTitle == null) {
            val goldColor = Color(0xFFFFC947)
            val cardBg = if (isDark) Color(0xFF212532).copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)

            LazyColumn(
                state = mainListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 2.dp,
                    bottom = contentPadding.calculateBottomPadding() + 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ==================== 0. 顶部模式切换卡片 (在线服务器 / 本地模式) + 下载管理卡片 ====================
                item(key = "mine_top_mode_and_download_cards") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimensions.scale(124.dp)),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 左卡：在线服务器模式与本地模式切换卡片
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, if (!isLocalMode) Color(0xFF34C759).copy(alpha = 0.45f) else borderColor),
                            modifier = Modifier
                                .weight(1.38f)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = if (!isLocalMode) {
                                                listOf(Color(0xFF1E2D3D), Color(0xFF17202C))
                                            } else {
                                                listOf(Color(0xFF282634), Color(0xFF1C1B24))
                                            }
                                        )
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (!isLocalMode) Color(0xFF34C759).copy(alpha = 0.2f) else goldColor.copy(alpha = 0.2f),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (!isLocalMode) Icons.Default.CloudDone else Icons.Default.FolderSpecial,
                                                    contentDescription = null,
                                                    tint = if (!isLocalMode) Color(0xFF34C759) else goldColor,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = if (!isLocalMode) {
                                                        "在线服务器模式 · ${activeServerConfig?.name ?: currentServerName}"
                                                    } else {
                                                        "本地缓存模式 · 离线畅听"
                                                    },
                                                    fontSize = dimensions.cardHeaderSize,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (!isLocalMode) Color(0xFF34C759).copy(alpha = 0.22f) else goldColor.copy(alpha = 0.22f)
                                                ) {
                                                    Text(
                                                        text = if (!isLocalMode) "云端已连接" else "纯本地模式",
                                                        fontSize = dimensions.badgeSize,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (!isLocalMode) Color(0xFF34C759) else goldColor,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (!isLocalMode) {
                                                    "当前展示服务器全量曲库 (${allSongs.size} 首) · 本机已缓存 ${finalDownloadedSongs.size} 首"
                                                } else {
                                                    "当前仅显示本机已下载与扫描缓存歌曲 (共 ${filteredSongs.size} 首)"
                                                },
                                                fontSize = dimensions.badgeSize,
                                                color = Color.White.copy(alpha = 0.72f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                // 底部遥控器可聚焦模式切换与同步按钮排
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 按钮 1：切换至在线服务器模式
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (!isLocalMode) AppleRed else Color.White.copy(alpha = 0.10f),
                                        border = BorderStroke(1.dp, if (!isLocalMode) AppleRed else Color.White.copy(alpha = 0.18f)),
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusedScale = 1.05f,
                                            focusedBorderColor = goldColor,
                                            onClick = {
                                                if (configuredServers.isNotEmpty()) {
                                                    val targetServer = if (!isLocalMode && configuredServers.size > 1) {
                                                        val curIdx = configuredServers.indexOfFirst { it.id == activeServerConfig?.id }
                                                        configuredServers[(curIdx + 1) % configuredServers.size]
                                                    } else {
                                                        configuredServers.firstOrNull { it.isCurrentActive } ?: configuredServers.first()
                                                    }
                                                    onSelectServer(targetServer)
                                                } else {
                                                    Toast.makeText(context, "尚未配置服务器，请先在设置中添加柠檬音乐服务", Toast.LENGTH_SHORT).show()
                                                    onGoToSettings()
                                                }
                                            }
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CloudQueue,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = if (!isLocalMode && configuredServers.size > 1) "切换服务器" else "在线服务器模式",
                                                fontSize = dimensions.captionSize,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }

                                    // 按钮 2：切换至本地模式
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isLocalMode) AppleRed else Color.White.copy(alpha = 0.10f),
                                        border = BorderStroke(1.dp, if (isLocalMode) AppleRed else Color.White.copy(alpha = 0.18f)),
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusedScale = 1.05f,
                                            focusedBorderColor = goldColor,
                                            onClick = {
                                                onSelectLocalServer()
                                            }
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Storage,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "本地模式",
                                                fontSize = dimensions.captionSize,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }

                                    // 按钮 3：同步云端曲库 (在线模式下展示)
                                    if (!isLocalMode) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color.White.copy(alpha = 0.08f),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                                            modifier = Modifier.tvFocusable(
                                                shape = RoundedCornerShape(12.dp),
                                                focusedScale = 1.05f,
                                                focusedBorderColor = goldColor,
                                                onClick = onSyncNow
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Sync,
                                                    contentDescription = "同步",
                                                    tint = goldColor,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = "同步曲库",
                                                    fontSize = dimensions.captionSize,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }

                                    // 按钮 4：服务与路径设置
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusedScale = 1.05f,
                                            focusedBorderColor = goldColor,
                                            onClick = onGoToSettings
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = "设置",
                                                tint = Color.White.copy(alpha = 0.85f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "配置",
                                                fontSize = dimensions.captionSize,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 右卡：下载功能卡片（点击进入查看下载进度与已下载列表）
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = cardBg,
                            border = BorderStroke(
                                1.dp,
                                if (activeDownloadCount > 0) goldColor.copy(alpha = 0.6f) else borderColor
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .tvFocusable(
                                    shape = RoundedCornerShape(20.dp),
                                    focusedScale = 1.03f,
                                    focusedBorderColor = goldColor,
                                    onClick = onOpenDownloads
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF2C2235), Color(0xFF1D1927))
                                        )
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "下载管理与缓存",
                                                fontSize = dimensions.cardHeaderSize,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "点击进入查看实时下载进度与已下载歌曲列表 >",
                                            fontSize = dimensions.badgeSize,
                                            color = Color.White.copy(alpha = 0.72f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = AppleRed.copy(alpha = 0.22f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "下载管理",
                                                tint = AppleRed,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Downloading,
                                                contentDescription = null,
                                                tint = if (activeDownloadCount > 0) goldColor else Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "正在下载: $activeDownloadCount 首",
                                                fontSize = dimensions.captionSize,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (activeDownloadCount > 0) goldColor else Color.White.copy(alpha = 0.85f)
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF34C759),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "本地已缓存: ${finalDownloadedSongs.size} 首",
                                                fontSize = dimensions.captionSize,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== 1. 顶部精简四宫格核心入口大卡 (在线与本地模式均显示) ====================
                item(key = "lib_top_bento_cards") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimensions.scale(148.dp)),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 卡片 1：全部歌曲
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                                .tvFocusable(
                                    shape = RoundedCornerShape(20.dp),
                                    focusedScale = 1.04f,
                                    focusedBorderColor = goldColor,
                                    onClick = {
                                        activeSubViewTitle = "全部歌曲"
                                        activeSubViewSubtitle = "资料库曲目 · 共 ${filteredSongs.size} 首"
                                        activeSubViewSongs = filteredSongs
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF293862), Color(0xFF1A233E))
                                        )
                                    )
                                    .padding(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column {
                                            Text(
                                                text = "全部歌曲",
                                                fontSize = dimensions.sectionTitleSize,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "共 ${allSongs.size} 首高保真曲目",
                                                fontSize = dimensions.badgeSize,
                                                color = Color.White.copy(alpha = 0.72f)
                                            )
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.2f),
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AlbumArtworkImage(
                                            model = allSongs.firstOrNull()?.coverUrl,
                                            seedId = allSongs.firstOrNull()?.id ?: "lib_all",
                                            modifier = Modifier.size(48.dp),
                                            cornerRadius = 10.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = allSongs.firstOrNull()?.title ?: "点击浏览全部",
                                                fontSize = dimensions.bodySize,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "按 OK 展开全库列表",
                                                fontSize = dimensions.badgeSize,
                                                color = goldColor,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 卡片 2：最近播放 (原「目录浏览」卡片改造；目录浏览入口已移至「自建与云端歌单」头部)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .tvFocusable(
                                    shape = RoundedCornerShape(20.dp),
                                    focusedScale = 1.04f,
                                    focusedBorderColor = goldColor,
                                    onClick = {
                                        activeSubViewTitle = "最近播放"
                                        activeSubViewSubtitle = "最近聆听足迹 · 共 ${recentPlaySongs.size} 首"
                                        activeSubViewSongs = recentPlaySongs
                                    }
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "最近播放",
                                            fontSize = dimensions.sectionTitleSize,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "共 ${recentPlaySongs.size} 首播放足迹",
                                            fontSize = dimensions.badgeSize,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = Color(0xFFBF5AF2),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val lastPlayed = recentPlaySongs.firstOrNull()
                                    AlbumArtworkImage(
                                        model = lastPlayed?.coverUrl,
                                        seedId = lastPlayed?.id ?: "lib_recent",
                                        modifier = Modifier.size(48.dp),
                                        cornerRadius = 10.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = lastPlayed?.title ?: "暂无播放记录",
                                            fontSize = dimensions.bodySize,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = lastPlayed?.artist?.takeIf { it.isNotBlank() } ?: "按 OK 查看播放足迹 >",
                                            fontSize = dimensions.badgeSize,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // 卡片 3：专辑精选
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .tvFocusable(
                                    shape = RoundedCornerShape(20.dp),
                                    focusedScale = 1.04f,
                                    focusedBorderColor = goldColor,
                                    onClick = {
                                        activeSubViewTitle = "全部专辑"
                                        activeSubViewSubtitle = "共 ${albums.size} 张专辑"
                                        activeSubViewSongs = allSongs
                                        isFromAllAlbums = false
                                    }
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column {
                                        Text(
                                            text = "专辑精选",
                                            fontSize = dimensions.sectionTitleSize,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "共 ${albums.size} 张收录唱片",
                                            fontSize = dimensions.badgeSize,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Album,
                                        contentDescription = null,
                                        tint = Color(0xFF5AC8FA),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val firstAlbum = albums.firstOrNull()
                                    AlbumArtworkImage(
                                        model = firstAlbum?.coverUrl,
                                        seedId = firstAlbum?.id ?: "lib_album",
                                        modifier = Modifier.size(48.dp),
                                        cornerRadius = 10.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = firstAlbum?.title ?: "热门唱片合集",
                                            fontSize = dimensions.bodySize,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = firstAlbum?.artist ?: "按专辑分类浏览 >",
                                            fontSize = dimensions.badgeSize,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // 卡片 4：我喜欢的音乐
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .weight(0.95f)
                                .fillMaxHeight()
                                .tvFocusable(
                                    shape = RoundedCornerShape(20.dp),
                                    focusedScale = 1.04f,
                                    focusedBorderColor = goldColor,
                                    onClick = {
                                        isFromAllPlaylists = false
                                        activeSubViewTitle = "我喜欢的音乐"
                                        activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
                                        activeSubViewSongs = favSongs
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF4B2031), Color(0xFF291520))
                                        )
                                    )
                                    .padding(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column {
                                            Text(
                                                text = "我喜欢",
                                                fontSize = dimensions.sectionTitleSize,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "${favSongs.size} 首红心珍藏",
                                                fontSize = dimensions.badgeSize,
                                                color = Color.White.copy(alpha = 0.75f)
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = AppleRed,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AlbumArtworkImage(
                                            model = favSongs.firstOrNull()?.coverUrl ?: allSongs.firstOrNull()?.coverUrl,
                                            seedId = favSongs.firstOrNull()?.id ?: "lib_fav",
                                            modifier = Modifier.size(48.dp),
                                            cornerRadius = 10.dp
                                        )
                                        Text(
                                            text = if (favSongs.isNotEmpty()) "按 OK 畅听红心歌单" else "点击红心即可收藏",
                                            fontSize = dimensions.badgeSize,
                                            color = Color.White.copy(alpha = 0.85f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== 1.5 歌单列表卡片（位于资料库歌手上方） ====================
                item(key = "lib_playlists_showcase_card") {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = cardBg,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "自建与云端歌单",
                                        fontSize = dimensions.sectionTitleSize,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text(
                                        text = "共 ${playlists.size} 个歌单",
                                        fontSize = dimensions.captionSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 目录浏览入口 (自「最近播放」顶部卡片迁移至此，与「+ 新建歌单」并列)
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.07f),
                                        border = BorderStroke(1.dp, borderColor),
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusedScale = 1.05f,
                                            focusedBorderColor = goldColor,
                                            onClick = {
                                                activeSubViewTitle = "全部文件夹"
                                                activeSubViewSubtitle = "共 ${localFolders.size} 个目录"
                                                isFromAllFolders = false
                                            }
                                        )
                                    ) {
                                        Text(
                                            text = "目录浏览",
                                            fontSize = dimensions.badgeSize,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF5AC8FA),
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.07f),
                                        border = BorderStroke(1.dp, borderColor),
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusedScale = 1.05f,
                                            focusedBorderColor = goldColor,
                                            onClick = {
                                                activeSubViewTitle = "全部歌单"
                                                activeSubViewSubtitle = "共 ${playlists.size} 个自建及云端歌单"
                                                isFromAllPlaylists = false
                                            }
                                        )
                                    ) {
                                        Text(
                                            text = "全部歌单 >",
                                            fontSize = dimensions.badgeSize,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                itemsIndexed(
                                    items = playlists,
                                    key = { _, pl -> "row_pl_${pl.id}" }
                                ) { _, pl ->
                                    PlaylistCardItem(
                                        playlist = pl,
                                        focusKey = "mine_plrow_${pl.name}",
                                        onClick = {
                                            isFromAllPlaylists = false
                                            activeSubViewTitle = pl.name
                                            activePlaylistId = pl.id
                                            activeSubViewSubtitle = if (isLocalMode) {
                                                "本地已缓存 · 在线共 ${pl.songCount} 首"
                                            } else {
                                                "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${pl.songCount} 首"
                                            }
                                            if (onFetchPlaylistSongs != null) {
                                                isLoadingSubView = true
                                                coroutineScope.launch {
                                                    val loaded = onFetchPlaylistSongs(pl.id, pl.isOnline)
                                                    activeSubViewSongs = loaded
                                                    if (isLocalMode) {
                                                        activeSubViewSubtitle = "本地已缓存 · 共 ${loaded.size} 首 (在线共 ${pl.songCount} 首)"
                                                    }
                                                    isLoadingSubView = false
                                                }
                                            } else {
                                                activeSubViewSongs = allSongs.filter { it.album == pl.name }
                                            }
                                        },
                                        modifier = Modifier.width(dimensions.scale(124.dp))
                                    )
                                }
                                item(key = "row_create_playlist_card") {
                                    CreatePlaylistActionCard(
                                        onClick = { isCreatePlaylistDialogOpen = true },
                                        modifier = Modifier.width(dimensions.scale(124.dp))
                                    )
                                }
                            }
                        }
                    }
                }

                // ==================== 2. 资料库歌手（酷我 TV 风格圆形头像横向展示大卡） ====================
                item(key = "lib_artists_showcase_card") {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = cardBg,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "资料库歌手",
                                        fontSize = dimensions.sectionTitleSize,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text(
                                        text = "共 ${artists.size} 位音乐人",
                                        fontSize = dimensions.captionSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 「资料库歌手」区块不再提供「+ 新建歌单」入口 (统一收敛至「自建与云端歌单」卡片)
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.07f),
                                        border = BorderStroke(1.dp, borderColor),
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusedScale = 1.05f,
                                            focusedBorderColor = goldColor,
                                            onClick = {
                                                activeSubViewTitle = "全部歌手"
                                                activeSubViewSubtitle = "共 ${artists.size} 位歌手"
                                                activeSubViewSongs = allSongs
                                            }
                                        )
                                    ) {
                                        Text(
                                            text = "更多歌手 >",
                                            fontSize = dimensions.badgeSize,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (artists.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "暂无歌手数据，下载歌曲后即可在此自动聚合",
                                        fontSize = dimensions.captionSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyRow(
                                    state = artistRowState,
                                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    itemsIndexed(
                                        items = artists.take(18),
                                        key = { _, artist -> "artist_circle_${artist.id}" }
                                    ) { _, artist ->
                                        Column(
                                            modifier = Modifier
                                                .width(88.dp)
                                                .tvFocusable(
                                                    shape = RoundedCornerShape(14.dp),
                                                    focusedScale = 1.06f,
                                                    focusedBorderColor = goldColor,
                                                    // 焦点记忆键：必须带区块前缀。
                                                    // 同一歌手可能同时出现在「歌手胶囊流」与「全部歌手」网格里，
                                                    // 若共用同一个 key，后注册者会覆盖前者，恢复时会落到另一个实例
                                                    // （甚至可能不在视口内，导致恢复失败）。
                                                    focusKey = "mine_artist_pill_${artist.name}",
                                                    onClick = {
                                                        isFromAllArtists = false
                                                        val artistSongs = allSongs.filter { it.artist == artist.name }
                                                        activeSubViewTitle = artist.name
                                                        activeSubViewSubtitle = "歌手专栏 · 共 ${artistSongs.size} 首歌曲"
                                                        activeSubViewSongs = artistSongs
                                                    }
                                                )
                                                .padding(vertical = 6.dp, horizontal = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            AlbumArtworkImage(
                                                model = artist.avatarUrl,
                                                seedId = artist.name,
                                                modifier = Modifier
                                                    .size(dimensions.scale(68.dp))
                                                    .clip(CircleShape),
                                                cornerRadius = dimensions.scale(34.dp)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = artist.name,
                                                fontSize = dimensions.captionSize,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                text = "${artist.songCount} 首",
                                                fontSize = dimensions.badgeSize,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== 3. 曲库曲目列表 / 本地缓存歌曲列表（支持直接点播与一键排序） ====================
                item(key = "lib_songs_header") {
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
                                text = if (isLocalMode) "本地缓存歌曲列表" else "曲库歌曲",
                                fontSize = dimensions.sectionTitleSize,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "${filteredSongs.size} 首",
                                    fontSize = dimensions.badgeSize,
                                    fontWeight = FontWeight.Bold,
                                    color = goldColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (filteredSongs.isNotEmpty()) {
                                SongListPlayAndBatchDownloadBar(
                                    totalCount = filteredSongs.size,
                                    isMultiSelectMode = isMainListMultiSelect,
                                    selectedCount = selectedMainSongIds.size,
                                    onPlayAll = {
                                        onSongClick(filteredSongs.first(), filteredSongs)
                                    },
                                    onEnterMultiSelect = {
                                        isMainListMultiSelect = true
                                        selectedMainSongIds.clear()
                                    },
                                    onExitMultiSelect = {
                                        isMainListMultiSelect = false
                                        selectedMainSongIds.clear()
                                    },
                                    onSelectAllToggle = {
                                        if (selectedMainSongIds.size == filteredSongs.size) {
                                            selectedMainSongIds.clear()
                                        } else {
                                            selectedMainSongIds.clear()
                                            selectedMainSongIds.addAll(filteredSongs.map { it.id })
                                        }
                                    },
                                    onBatchDownloadClick = {
                                        val selected = filteredSongs.filter { it.id in selectedMainSongIds }
                                        if (selected.isNotEmpty()) {
                                            songsForBatchDownloadChoice = selected
                                        }
                                    }
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White.copy(alpha = 0.07f),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier.tvFocusable(
                                    shape = RoundedCornerShape(14.dp),
                                    focusedScale = 1.05f,
                                    focusedBorderColor = goldColor,
                                    onClick = {
                                        songSortMode = when (songSortMode) {
                                            "default" -> "name"
                                            "name" -> "artist"
                                            "artist" -> "duration"
                                            "duration" -> "source"
                                            "source" -> "added"
                                            else -> "default"
                                        }
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = "排序",
                                        tint = goldColor,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = when (songSortMode) {
                                            "name" -> "按歌名"
                                            "artist" -> "按歌手"
                                            "duration" -> "按时长"
                                            "source" -> "按来源"
                                            "added" -> "按加入时间"
                                            else -> "默认排序"
                                        },
                                        fontSize = dimensions.captionSize,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                if (filteredSongs.isEmpty()) {
                    item(key = "lib_empty_songs") {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (isLocalMode) "当前暂无本地缓存歌曲" else "当前曲库暂无曲目",
                                    fontSize = dimensions.itemTitleSize,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isLocalMode) "可在在线曲库或搜索中下载喜欢的音乐，或前往「设置」扫描本机音频目录" else "可点击上方「同步曲库」同步柠檬音乐服务端，或在「设置」中配置服务器",
                                    fontSize = dimensions.captionSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = filteredSongs,
                        key = { _, song -> "libsong_${song.id}" },
                        contentType = { _, _ -> "library_song_item" }
                    ) { idx, song ->
                        val isSelected = song.id in selectedMainSongIds
                        Column {
                            if (songSortMode == "source") {
                                val prevSource = if (idx > 0) getSongSourceTypeName(filteredSongs[idx - 1]) else null
                                val currentSource = getSongSourceTypeName(song)
                                if (prevSource != currentSource) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = currentSource,
                                            fontSize = dimensions.badgeSize,
                                            fontWeight = FontWeight.Bold,
                                            color = AppleRed,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            SongListItemRow(
                                song = song,
                                activeDownloadTasks = activeDownloadTasks,
                                isServerConnected = isServerOk,
                                currentPlayingSong = currentPlayingSong,
                                isPlaying = isPlaying,
                                isMultiSelectMode = isMainListMultiSelect,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    if (isSelected) selectedMainSongIds.remove(song.id) else selectedMainSongIds.add(song.id)
                                },
                                onClick = { onSongClick(song, filteredSongs) },
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

        // 视图切换的焦点恢复：activeSubViewTitle 变化即触发。
        // 进入下钻时由返回键的 tvFocusEntryAnchor 抢焦点（更高优先级）；
        // 返回主语时这里把焦点还原到「用户刚才点击的那一项」（歌手胶囊/专辑卡/歌单卡）。
        TvRestoreFocusOnChange(activeSubViewTitle == null)

        // 二级下钻详情视图 (页面内展示：歌单曲目、歌手曲目、专辑曲目，绝不遮挡底部播放栏)
        if (activeSubViewTitle != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                // 顶部返回与标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = handleSubViewBack,
                        modifier = Modifier
                            // 注意：这里**不再**挂进入侧锚点。
                            // 需求是「展开列表后焦点落到列表第一项」，所以锚点交给下方首行歌曲；
                            // 返回键仍可通过从首行往上按到达，符合电视端的浏览直觉。
                            .tvButtonFocusable(shape = CircleShape, focusedScale = 1.1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeSubViewTitle ?: "",
                            fontSize = dimensions.sectionTitleSize,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = activeSubViewSubtitle,
                            fontSize = dimensions.captionSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (activePlaylistId != null) {
                        var showDeleteConfirm by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.tvButtonFocusable(shape = CircleShape)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "删除歌单", tint = Color(0xFFFF3B30))
                        }
                        if (showDeleteConfirm) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                title = { Text("删除歌单") },
                                text = { Text("确定要删除歌单「$activeSubViewTitle」吗？此操作无法撤销。") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showDeleteConfirm = false
                                        val plId = activePlaylistId
                                        activePlaylistId = null
                                        activeSubViewTitle = null
                                        if (plId != null) onDeletePlaylist(plId)
                                    }) { Text("删除", color = Color(0xFFFF3B30)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                                }
                            )
                        }
                    }

                    // 播放全部与管理按键
                    if (activeSubViewTitle == "本地下载" && activeSubViewSongs.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!isDownloadManagementMode) {
                                OutlinedButton(
                                    onClick = { isDownloadManagementMode = true },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                                ) {
                                    Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("管理", fontSize = dimensions.captionSize, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Button(
                                    onClick = {
                                        activeSubViewSongs.firstOrNull()?.let { onSongClick(it, activeSubViewSongs) }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.tvButtonFocusable(
                                        shape = RoundedCornerShape(16.dp),
                                        focusedBorderColor = Color(0xFFFFD60A)
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("播放全部", fontSize = dimensions.captionSize)
                                }
                            } else {
                                TextButton(
                                    onClick = {
                                        isDownloadManagementMode = false
                                        selectedDownloadSongIds.clear()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(8.dp))
                                ) {
                                    Text("完成", fontSize = dimensions.bodySize, color = AppleRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (activeSubViewSongs.isNotEmpty()) {
                        SongListPlayAndBatchDownloadBar(
                            totalCount = activeSubViewSongs.size,
                            isMultiSelectMode = isSubViewMultiSelect,
                            selectedCount = selectedSubViewSongIds.size,
                            onPlayAll = {
                                activeSubViewSongs.firstOrNull()?.let { onSongClick(it, activeSubViewSongs) }
                            },
                            onEnterMultiSelect = {
                                isSubViewMultiSelect = true
                                selectedSubViewSongIds.clear()
                            },
                            onExitMultiSelect = {
                                isSubViewMultiSelect = false
                                selectedSubViewSongIds.clear()
                            },
                            onSelectAllToggle = {
                                if (selectedSubViewSongIds.size == activeSubViewSongs.size) {
                                    selectedSubViewSongIds.clear()
                                } else {
                                    selectedSubViewSongIds.clear()
                                    selectedSubViewSongIds.addAll(activeSubViewSongs.map { it.id })
                                }
                            },
                            onBatchDownloadClick = {
                                val selected = activeSubViewSongs.filter { it.id in selectedSubViewSongIds }
                                if (selected.isNotEmpty()) {
                                    songsForBatchDownloadChoice = selected
                                }
                            }
                        )
                    }
                }

                if (isDownloadManagementMode && activeSubViewTitle == "本地下载") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isAllSelected = selectedDownloadSongIds.size == activeSubViewSongs.size && activeSubViewSongs.isNotEmpty()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .tvFocusable(
                                        shape = RoundedCornerShape(8.dp),
                                        onClick = {
                                            if (isAllSelected) {
                                                selectedDownloadSongIds.clear()
                                            } else {
                                                selectedDownloadSongIds.clear()
                                                selectedDownloadSongIds.addAll(activeSubViewSongs.map { it.id })
                                            }
                                        }
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = isAllSelected,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAllSelected) "取消全选" else "全选",
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "已选 ${selectedDownloadSongIds.size} 首",
                                    fontSize = dimensions.captionSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Button(
                                onClick = { showBatchDeleteLocalDialog = true },
                                enabled = selectedDownloadSongIds.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.tvButtonFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    focusedBorderColor = Color(0xFFFFD60A)
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("删除 (${selectedDownloadSongIds.size})", fontSize = dimensions.captionSize)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (activeSubViewTitle == "全部歌单") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = dimensions.scale(136.dp)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // A. 新建歌单快捷卡片
                        item {
                            CreatePlaylistActionCard(
                                onClick = { isCreatePlaylistDialogOpen = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // B. 我喜欢的音乐
                        item {
                            PlaylistSpecialCard(
                                title = "我喜欢的音乐",
                                subtitle = "${favSongs.size} 首歌曲",
                                icon = Icons.Default.Favorite,
                                gradient = listOf(Color(0xFFFA233B), Color(0xFFFF5E3A)),
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "我喜欢的音乐"
                                    activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
                                    activeSubViewSongs = favSongs
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // C. 最近播放
                        item {
                            // 用真实播放足迹（与首页那张"最近播放"卡片同源），
                            // 旧实现取的是 allSongs.take(30)——那是"曲库前 30 首"，
                            // 与播放历史无关，正是"最近播放没有被正常记录"的观感来源
                            val recentSongs = recentPlaySongs
                            PlaylistSpecialCard(
                                title = "最近播放",
                                subtitle = "${recentSongs.size} 首歌曲",
                                icon = Icons.Default.History,
                                gradient = listOf(Color(0xFF5856D6), Color(0xFFAF52DE)),
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "最近播放"
                                    activeSubViewSubtitle = "最近聆听足迹 · 共 ${recentSongs.size} 首"
                                    activeSubViewSongs = recentSongs
                                    isDownloadManagementMode = false
                                    selectedDownloadSongIds.clear()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // D. 本地下载
                        item {
                            PlaylistSpecialCard(
                                title = "本地下载",
                                subtitle = "${finalDownloadedSongs.size} 首歌曲",
                                icon = Icons.Default.Folder,
                                gradient = listOf(Color(0xFF007AFF), Color(0xFF5AC8FA)),
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "本地下载"
                                    activeSubViewSubtitle = "本机离线歌曲 · 共 ${finalDownloadedSongs.size} 首"
                                    activeSubViewSongs = finalDownloadedSongs
                                    isDownloadManagementMode = false
                                    selectedDownloadSongIds.clear()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // E. 自建与服务端歌单
                        itemsIndexed(playlists, key = { _, pl -> "grid_pl_${pl.id}" }) { _, pl ->
                            PlaylistCardItem(
                                playlist = pl,
                                focusKey = "mine_plgrid_${pl.name}",
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = pl.name
                                    activePlaylistId = pl.id
                                    activeSubViewSubtitle = if (isLocalMode) {
                                        "本地已缓存 · 在线共 ${pl.songCount} 首"
                                    } else {
                                        "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${pl.songCount} 首"
                                    }
                                    if (onFetchPlaylistSongs != null) {
                                        isLoadingSubView = true
                                        coroutineScope.launch {
                                            val loaded = onFetchPlaylistSongs(pl.id, pl.isOnline)
                                            activeSubViewSongs = loaded
                                            if (isLocalMode) {
                                                activeSubViewSubtitle = "本地已缓存 · 共 ${loaded.size} 首 (在线共 ${pl.songCount} 首)"
                                            }
                                            isLoadingSubView = false
                                        }
                                    } else {
                                        activeSubViewSongs = allSongs.filter { it.album == pl.name }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item(key = "grid_create_playlist_card") {
                            CreatePlaylistActionCard(
                                onClick = { isCreatePlaylistDialogOpen = true },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else if (activeSubViewTitle == "全部文件夹") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = dimensions.scale(160.dp)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(localFolders, key = { _, folder -> "grid_folder_${folder.id}" }) { _, folder ->
                            FolderCardItem(
                                folder = folder,
                                onClick = {
                                    isFromAllFolders = true
                                    activeSubViewTitle = "文件夹 · ${folder.name}"
                                    activeSubViewSubtitle = "本地目录 · 共 ${folder.songCount} 首歌曲"
                                    activeSubViewSongs = folder.songs
                                },
                                modifier = Modifier.fillMaxWidth(),
                                focusKey = "mine_folder_${folder.name}"
                            )
                        }
                    }
                } else if (activeSubViewTitle == "全部歌手") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = dimensions.scale(150.dp)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(artists, key = { _, artist -> "grid_artist_${artist.id}" }) { _, artist ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusable(
                                                    focusKey = "mine_allartist_${artist.name}",
                                        shape = RoundedCornerShape(16.dp),
                                        focusedScale = 1.04f,
                                        onClick = {
                                            val artistSongs = allSongs.filter { it.artist == artist.name }
                                            isFromAllArtists = true
                                            activeSubViewTitle = artist.name
                                            activeSubViewSubtitle = "歌手专栏 · 共 ${artistSongs.size} 首歌曲"
                                            activeSubViewSongs = artistSongs
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AlbumArtworkImage(
                                        model = artist.avatarUrl,
                                        seedId = artist.name,
                                        modifier = Modifier.size(48.dp),
                                        cornerRadius = 24.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = artist.name,
                                            fontSize = dimensions.itemTitleSize,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${artist.songCount} 首 · ${artist.albumCount} 张专辑",
                                            fontSize = dimensions.badgeSize,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (activeSubViewTitle == "全部专辑") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = dimensions.scale(140.dp)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(albums, key = { _, album -> "grid_album_${album.id}" }) { _, album ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusable(
                                                    focusKey = "mine_album_${album.title}",
                                        shape = RoundedCornerShape(14.dp),
                                        focusedScale = 1.04f,
                                        onClick = {
                                            val albumSongs = allSongs.filter { it.album == album.title }
                                            isFromAllAlbums = true
                                            activeSubViewTitle = album.title
                                            activeSubViewSubtitle = "${album.artist} · 共 ${albumSongs.size} 首"
                                            activeSubViewSongs = albumSongs
                                        }
                                    )
                                    .padding(4.dp)
                            ) {
                                AlbumArtworkImage(
                                    model = album.coverUrl,
                                    seedId = album.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    cornerRadius = 12.dp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = album.title,
                                    fontSize = dimensions.bodySize,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${album.artist} · ${album.songCount} 首",
                                    fontSize = dimensions.badgeSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else if (isLoadingSubView) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = AppleRed)
                    }
                } else if (activeSubViewSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = if (isLocalMode) "当前处于本地模式，暂无已下载至本机的歌曲（在线歌单或喜欢音乐下载后即可在此显示）" else "暂无歌曲数据",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = dimensions.itemTitleSize
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = activeSubViewSongs,
                            key = { _, song -> "subsong_${song.id}" },
                            contentType = { _, _ -> "subview_song_row" }
                        ) { idx, song ->
                            if (isDownloadManagementMode && activeSubViewTitle == "本地下载") {
                                val isSelected = song.id in selectedDownloadSongIds
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) AppleRed.copy(alpha = 0.08f) else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusedScale = 1.01f,
                                            onClick = {
                                                if (isSelected) selectedDownloadSongIds.remove(song.id) else selectedDownloadSongIds.add(song.id)
                                            }
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(modifier = Modifier.weight(1f)) {
                                            SongListItemRow(
                                                song = song,
                                                activeDownloadTasks = activeDownloadTasks,
                                                isServerConnected = isServerOk,
                                                onClick = {
                                                    if (isSelected) selectedDownloadSongIds.remove(song.id) else selectedDownloadSongIds.add(song.id)
                                                },
                                                onDownloadClick = {},
                                                onDownloadWithOptions = { _, _, _ -> },
                                                onOpenDownloads = onOpenDownloads
                                            )
                                        }
                                    }
                                }
                            } else {
                                val isSelected = song.id in selectedSubViewSongIds
                                SongListItemRow(
                                    // 进入侧锚点：**必须挂在可聚焦节点上**（SongListItemRow 内部即 tvFocusable）。
                                    // 只在首行挂，且用派生令牌，保证「展开列表 → 焦点落到第一项」且不会反复抢焦点。
                                    modifier = if (idx == 0) {
                                        Modifier.tvFocusEntryAnchor(subViewEntryToken)
                                    } else Modifier,
                                    song = song,
                                    activeDownloadTasks = activeDownloadTasks,
                                    isServerConnected = isServerOk,
                                    currentPlayingSong = currentPlayingSong,
                                    isPlaying = isPlaying,
                                    isMultiSelectMode = isSubViewMultiSelect,
                                    isSelected = isSelected,
                                    onToggleSelect = {
                                        if (isSelected) selectedSubViewSongIds.remove(song.id) else selectedSubViewSongIds.add(song.id)
                                    },
                                    onClick = { onSongClick(song, activeSubViewSongs) },
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
        }
    }

    // 确认删除本地下载歌曲弹窗
    if (showBatchDeleteLocalDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteLocalDialog = false },
            title = { Text("确认删除本地下载歌曲？", fontWeight = FontWeight.Bold) },
            text = {
                Text("您即将从设备中彻底删除选中的 ${selectedDownloadSongIds.size} 首已下载歌曲及伴随歌词文件。此操作将彻底释放手机空间，确定继续吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteLocalDialog = false
                        val idsToDelete = selectedDownloadSongIds.toList()
                        coroutineScope.launch(Dispatchers.IO) {
                            val songsToDelete = idsToDelete.mapNotNull { id ->
                                activeSubViewSongs.find { it.id == id } ?: allSongs.find { it.id == id }
                            }
                            if (onDeleteDownloadedSongs != null && songsToDelete.isNotEmpty()) {
                                try {
                                    onDeleteDownloadedSongs.invoke(songsToDelete)
                                } catch (e: Exception) {
                                    Log.e("LocalLibraryScreen", "Failed to invoke onDeleteDownloadedSongs", e)
                                }
                            }
                            var deletedCount = 0
                            for (song in songsToDelete) {
                                val path = song.localFilePath
                                if (!path.isNullOrBlank()) {
                                    try {
                                        val f = File(path)
                                        if (f.exists()) f.delete()
                                        val lrc = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                                        if (lrc.exists()) lrc.delete()
                                        onDeleteLocalFilePath?.invoke(path)
                                        deletedCount++
                                    } catch (e: Exception) {
                                        Log.e("LocalLibraryScreen", "Failed to delete file $path", e)
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                activeSubViewSongs = activeSubViewSongs.filter { it.id !in idsToDelete }
                                selectedDownloadSongIds.clear()
                                isDownloadManagementMode = false
                                val displayCount = if (deletedCount > 0) deletedCount else songsToDelete.size
                                Toast.makeText(context, "已成功删除 $displayCount 首歌曲并释放空间", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.tvButtonFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedBorderColor = Color(0xFFFFD60A)
                    )
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBatchDeleteLocalDialog = false },
                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
                ) {
                    Text("取消")
                }
            }
        )
    }

    // 全局下载音质与目标（服务器/本地）选择弹窗
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
        BatchDownloadQualityChoiceDialog(
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
                isMainListMultiSelect = false
                selectedMainSongIds.clear()
                isSubViewMultiSelect = false
                selectedSubViewSongIds.clear()
            },
            onDismiss = { songsForBatchDownloadChoice = emptyList() }
        )
    }

    // 新建歌单弹窗
    if (isCreatePlaylistDialogOpen) {
        Dialog(onDismissRequest = { isCreatePlaylistDialogOpen = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF222228) else Color.White,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "新建歌单",
                        fontSize = dimensions.sectionTitleSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("歌单名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(10.dp),
                                onClick = {
                                    if (activeServerConfig != null) {
                                        newPlaylistIsOnline = !newPlaylistIsOnline
                                    }
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("同步至服务端", fontSize = dimensions.itemTitleSize, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (activeServerConfig != null) "与柠檬音乐曲库双向同步" else "未连接服务端，仅保存在本地",
                                fontSize = dimensions.badgeSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = newPlaylistIsOnline && activeServerConfig != null,
                            onCheckedChange = null,
                            enabled = activeServerConfig != null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AppleRed
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isCreatePlaylistDialogOpen = false },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    onCreatePlaylist(newPlaylistName.trim(), newPlaylistIsOnline && activeServerConfig != null)
                                    newPlaylistName = ""
                                    isCreatePlaylistDialogOpen = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier
                                .weight(1f)
                                .tvButtonFocusable(
                                    shape = RoundedCornerShape(12.dp),
                                    focusedBorderColor = Color(0xFFFFD60A)
                                )
                        ) {
                            Text("创建")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 本地文件夹卡片 (极简优雅，展示目录名与歌曲数量)
 */
@Composable
private fun FolderCardItem(
    folder: UnifiedFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusKey: String? = null
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .width(dimensions.scale(176.dp))
            .height(dimensions.scale(64.dp))
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.04f,
                focusKey = focusKey,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF9500), Color(0xFFFF5E3A)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = folder.name,
                    fontSize = dimensions.bodySize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${folder.songCount} 首歌曲",
                    fontSize = dimensions.badgeSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 特色歌单卡片 (我喜欢的音乐 / 最近播放)
 */
@Composable
private fun PlaylistSpecialCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .width(dimensions.scale(148.dp))
            .height(dimensions.scale(148.dp))
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.05f,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradient))
                .padding(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.TopStart)
            )

            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = title,
                    fontSize = dimensions.itemTitleSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = dimensions.badgeSize,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 标准自建/云端歌单卡片
 */
@Composable
private fun PlaylistCardItem(
    playlist: UnifiedPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusKey: String? = null
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = modifier
            .width(dimensions.scale(136.dp))
            .tvFocusable(
                shape = RoundedCornerShape(14.dp),
                focusedScale = 1.05f,
                focusKey = focusKey,
                onClick = onClick
            )
            .padding(4.dp)
    ) {
        // 歌单卡片以文件夹四宫格展示目录下歌曲封面；曲目封面不足时回退到歌单封面
        val mosaicCovers = remember(playlist.previewCovers, playlist.coverUrl) {
            (playlist.previewCovers + playlist.coverUrl)
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
        }
        MosaicArtworkCollage(
            coverUrls = mosaicCovers,
            seedId = playlist.id,
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth(),
            cornerRadius = 14.dp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = playlist.name,
            fontSize = dimensions.bodySize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playlist.isOnline) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = AppleRed.copy(alpha = 0.12f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "云端",
                        fontSize = dimensions.badgeSize,
                        color = AppleRed,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = if (playlist.songCount > 0) "${playlist.songCount} 首" else "歌单",
                fontSize = dimensions.badgeSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 歌单栏首位新建歌单快捷卡片
 */
@Composable
private fun CreatePlaylistActionCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.5.dp, AppleRed.copy(alpha = 0.45f)),
        modifier = modifier
            .width(dimensions.scale(136.dp))
            .height(dimensions.scale(180.dp))
            .tvFocusable(
                shape = RoundedCornerShape(14.dp),
                focusedScale = 1.05f,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AppleRed.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建歌单",
                    tint = AppleRed,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "新建歌单",
                fontSize = dimensions.itemTitleSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "创建专属集合",
                fontSize = dimensions.badgeSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 最近添加歌曲卡片
 */
@Composable
private fun RecentAddedSongCard(
    song: UnifiedSong,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(132.dp)
            .tvFocusable(
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.05f,
                onClick = onClick
            )
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(dimensions.scale(124.dp))
                .clip(RoundedCornerShape(12.dp))
        ) {
            AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 12.dp
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AppleRed),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = song.title,
            fontSize = dimensions.bodySize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = song.artist,
            fontSize = dimensions.badgeSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
