package com.lm.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.ServerEntity
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.component.CrashReportDialog
import com.lm.player.core.designsystem.component.LocalTvBackgroundFocusEnabled
import com.lm.player.core.designsystem.component.isSongOnLemonServer
import com.lm.player.core.designsystem.theme.DefaultUiScalePercent
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.MaxUiScalePercent
import com.lm.player.core.designsystem.theme.MinUiScalePercent
import com.lm.player.core.designsystem.theme.ZDSPlayerTheme
import com.lm.player.core.designsystem.theme.rememberAppDimensions
import com.lm.player.core.media.BootCompletedReceiver
import com.lm.player.core.media.DownloadEngine
import com.lm.player.core.media.DownloadRequestPlanner
import com.lm.player.core.util.CrashLogger
import com.lm.player.core.media.LocalMediaScanner
import com.lm.player.core.media.LyricsManager
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.PlaybackRouter
import com.lm.player.core.media.PlaybackService
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.*
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import com.lm.player.core.update.AppUpdateManager
import com.lm.player.core.update.UpdateInfo
import com.lm.player.feature.download.DownloadManagerScreen
import com.lm.player.feature.home.LemonDiscoverHomeScreen
import com.lm.player.feature.home.LocalMusicHomeScreen
import com.lm.player.feature.library.LocalLibraryScreen
import com.lm.player.feature.player.FullscreenPlayerSheet
import com.lm.player.feature.search.LibrarySearchDialog
import com.lm.player.feature.settings.AppUpdateDialog
import com.lm.player.feature.settings.SettingsScreen
import com.lm.player.ui.AdaptiveAppScaffold
import com.lm.player.ui.SplashScreenView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null

    // 播放内核被重建 (解码降级) 时的换绑回调。
    // 必须持有为字段：Media3Factory 内部是进程级静态列表，匿名 lambda 无法在 onDestroy 里移除，
    // 每次 Activity 重建都会残留一个持有旧实例的回调，既泄漏 Activity 又会让换绑回调越积越多。
    private val playerSwapListener: (ExoPlayer) -> Unit = { newPlayer -> exoPlayer = newPlayer }

    private lateinit var database: ZdsDatabase
    private lateinit var playbackRouter: PlaybackRouter
    private lateinit var downloadEngine: DownloadEngine

    // 方向盘按键与 MediaSession 回调动作句柄
    private var playNextAction: (() -> Unit)? = null
    private var playPreviousAction: (() -> Unit)? = null
    private var togglePlayAction: (() -> Unit)? = null
    private var mediaCommandReceiver: BroadcastReceiver? = null

    // 播放进度落盘专用作用域：不绑定 Activity 生命周期，避免 onDestroy 时任务被取消而丢掉最后进度。
    // 任务只捕获 applicationContext 与数值，不持有 Activity 引用。
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 运行时权限申请器 (兼容 Android 6.0 ~ Android 14+)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private var onChooseDownloadFolderResult: ((android.net.Uri) -> Unit)? = null
    private val chooseDownloadDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onChooseDownloadFolderResult?.invoke(it)
        }
    }

    private var onImportFolderResult: ((android.net.Uri) -> Unit)? = null
    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onImportFolderResult?.invoke(it)
        }
    }

    @kotlin.OptIn(ExperimentalMaterial3WindowSizeClassApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 系统底层接管：将实体按键音量控制通道绑定为媒体音量 (解决应用内音量键无效问题)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        // 崩溃日志守护不在这里安装：Activity 每次重建都会再套一层处理器，
        // 且闭包持有 Activity 导致泄漏。统一由 LMApplication 启动时安装一次
        // (CrashLogger.install)，堆栈写入 filesDir/crash_last.txt 并在下次启动弹窗展示。

        // 1. 初始化核心数据库、路由与下载引擎
        database = ZdsDatabase.getInstance(this)
        playbackRouter = PlaybackRouter(database.downloadDao(), this)
        downloadEngine = DownloadEngine(this, database.downloadDao(), database.songDao(), lifecycleScope)

        // 2. 获取全局唯一共享 ExoPlayer 实例并启动前台播放服务（默认关闭在线边听边存）
        val initSettingsPrefs = getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
        Media3Factory.setCacheEnabled(initSettingsPrefs.getBoolean("stream_cache_enabled_v2", false))
        PlaybackQueueManager.initFromPrefs(this)
        exoPlayer = Media3Factory.getSharedExoPlayer(this)
        // 硬件解码异常时播放内核会被整体重建 (旧实例已 release)，
        // 必须把界面持有的引用换绑到新实例，否则进度条读取的永远是已释放播放器的 0 位置、seek 也会静默失效
        Media3Factory.addPlayerSwapListener(playerSwapListener)
        val initialSpeed = initSettingsPrefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
        if (initialSpeed != 1.0f) {
            exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(initialSpeed)
        }
        startPlaybackService()

        // 3. 注册方向盘按键与车载控制广播监听器
        registerMediaCommandReceiver()

        // 4. 申请 Android 6.0 / 13+ 运行时权限
        requestAppPermissions()

        setContent {
            // 注意：calculateWindowSizeClass() 内部**没有** remember (已用 javap 核实过字节码：
            // 每次组合都会重走 WindowMetricsCalculator.computeCurrentWindowMetrics)，
            // 但它本身是 @Composable，无法放进 remember { }。它的等价手写版本需要
            // WindowSizeClass.calculateFromSize()，而那是 @ExperimentalMaterial3WindowSizeClassApi，
            // 稳妥起见仍按原样调用，此处的重复计算保留为已知项。
            val windowSizeClass = calculateWindowSizeClass(this)
            val uiPrefs = remember { getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE) }
            // 全局字体与视距规格：50% ~ 150% 连续调节 (默认 100%)。
            // 旧版本使用固定预设 (ui_scale_mode)，此处按最近档位一次性迁移到百分比，保留用户原有的观感。
            val savedScalePercent = remember {
                val raw = if (uiPrefs.contains("ui_scale_percent")) {
                    uiPrefs.getInt("ui_scale_percent", DefaultUiScalePercent)
                } else {
                    when (uiPrefs.getString("ui_scale_mode", null)) {
                        "STANDARD_PHONE" -> 100
                        "CAR_LARGE" -> 120
                        "CAR_EXTRA_LARGE" -> 130
                        "AUTO" -> 115
                        else -> DefaultUiScalePercent
                    }
                }
                raw.coerceIn(MinUiScalePercent, MaxUiScalePercent)
            }
            var uiScalePercent by remember { mutableStateOf(savedScalePercent) }
            val appDimensions = rememberAppDimensions(uiScalePercent)

            // 启动过渡状态 (默认 false 确保 0ms 瞬间秒开呈现主屏)
            var isSplashVisible by remember { mutableStateOf(false) }

            // 外观主题与动效状态 (支持本地持久化记忆)
            val savedThemeName = remember { uiPrefs.getString("app_theme_mode", AppThemeMode.FOLLOW_SYSTEM.name) ?: AppThemeMode.FOLLOW_SYSTEM.name }
            var currentThemeMode by remember {
                mutableStateOf(try { AppThemeMode.valueOf(savedThemeName) } catch (_: Exception) { AppThemeMode.FOLLOW_SYSTEM })
            }
            var enableBottomBarAnimation by remember { mutableStateOf(uiPrefs.getBoolean("enable_bottom_bar_anim", true)) }

            // 启动自动播放与在线容灾配置
            val autoPlayPrefs = remember { getSharedPreferences("zds_auto_play_prefs", Context.MODE_PRIVATE) }
            var autoPlayOnStartup by remember { mutableStateOf(autoPlayPrefs.getBoolean("auto_play_on_startup", true)) }
            var autoFallbackToLocal by remember { mutableStateOf(autoPlayPrefs.getBoolean("auto_fallback_to_local", true)) }
            // 开机自启开关：默认关闭。必须走 BootCompletedReceiver 的双存储读写
            // (设备保护存储 + 凭据存储)，否则开机早期 (尚未解锁) 读回的是默认 false
            var autoLaunchOnBoot by remember {
                mutableStateOf(BootCompletedReceiver.isAutoLaunchOnBootEnabled(this@MainActivity))
            }
            var hasAutoPlayedOnStartup by remember { mutableStateOf(false) }
            var hasAutoCheckedServerOnStartup by remember { mutableStateOf(false) }

            // 启动 10s 延迟自动检查软件更新状态
            var startupUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
            var showStartupUpdateDialog by remember { mutableStateOf(false) }
            var isDownloadingStartupApk by remember { mutableStateOf(false) }
            var downloadStartupProgress by remember { mutableStateOf(0f) }
            var downloadedStartupApkFile by remember { mutableStateOf<java.io.File?>(null) }
            // 安装包是否已就绪：下载成功时判定一次即可，避免在组合期反复对文件做磁盘 stat
            var isStartupApkReady by remember { mutableStateOf(false) }

            // 启动 10 秒后自动后台检查版本更新 (支持稍后、永不与立即更新)
            LaunchedEffect(Unit) {
                delay(10000L)
                launch(Dispatchers.IO) {
                    try {
                        val res = AppUpdateManager.checkForUpdates(this@MainActivity)
                        if (res.isSuccess) {
                            val info = res.getOrNull()
                            if (info != null && info.hasUpdate) {
                                val isIgnored = AppUpdateManager.isUpdateIgnored(this@MainActivity, info.latestVersion)
                                if (!isIgnored) {
                                    withContext(Dispatchers.Main) {
                                        startupUpdateInfo = info
                                        showStartupUpdateDialog = true
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Startup 10s auto update check failed", e)
                    }
                }
            }

            // UI 状态机与多层级页面回退历史栈
            var currentScreen by remember { mutableStateOf(Screen.HOME) }
            val screenHistoryStack = remember { mutableStateListOf<Screen>() }
            var isChildSubViewActive by remember { mutableStateOf(false) }

            val navigateToScreen: (Screen) -> Unit = { target ->
                if (target != currentScreen) {
                    if (target == Screen.HOME) {
                        screenHistoryStack.clear()
                    } else {
                        screenHistoryStack.remove(target)
                        screenHistoryStack.add(currentScreen)
                    }
                    isChildSubViewActive = false
                    currentScreen = target
                }
            }

            val popScreenOrHome: () -> Unit = {
                if (screenHistoryStack.isNotEmpty()) {
                    currentScreen = screenHistoryStack.removeAt(screenHistoryStack.lastIndex)
                } else {
                    currentScreen = Screen.HOME
                }
            }

            var activeServerName by remember { mutableStateOf("本地 · 已下载") }
            var activeServerId by remember { mutableStateOf("") }
            var serversList by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
            var isSearchDialogOpen by remember { mutableStateOf(false) }

            // 首页展示自定义配置 (支持本地持久化记忆)
            var homeDisplayConfig by remember {
                mutableStateOf(
                    HomeScreenDisplayConfig(
                        showRecentlyPlayed = uiPrefs.getBoolean("home_show_recently_played", true),
                        showRecentlyAdded = uiPrefs.getBoolean("home_show_recently_added", true),
                        showAlbums = uiPrefs.getBoolean("home_show_albums", false),
                        showArtists = uiPrefs.getBoolean("home_show_artists", false),
                        showFavorites = uiPrefs.getBoolean("home_show_favorites", false)
                    )
                )
            }

            // 在线模式操作音源偏好 (酷我/网易云/QQ音乐/酷狗/咪咕)
            val onlinePrefs = remember { getSharedPreferences("zds_online_prefs", Context.MODE_PRIVATE) }
            val savedSourceName = remember { onlinePrefs.getString("selected_source", OnlineMusicSource.KUWO.name) ?: OnlineMusicSource.KUWO.name }
            var currentOnlineSource by remember {
                mutableStateOf(try { OnlineMusicSource.valueOf(savedSourceName) } catch (_: Exception) { OnlineMusicSource.KUWO })
            }

            // 实时下载状态与设置
            //
            // 性能约定（改动前请先读完）：
            // activeTasksFlow 每 ~300ms 就会为活动任务发一次进度，值是**全新的 List**。
            // 本工程 Compose 强跳过未生效，只要在 setContent 的**根作用域**读到它，
            // 整棵页面树就会跟着每 300ms 重组一次。所以这里**不再用 by 解包**，
            // 而是保留 State 对象本身，只把它的惰性读取交给真正需要进度的叶子节点：
            //   · 需要"当前有几个下载"的地方 → 读 activeDownloadCount（结构流，数量变化才发射）
            //   · 需要逐行进度的行组件 → 传 () -> List<DownloadTask> provider，
            //     行内用 derivedStateOf 只挑自己那一条任务，无任务的行走不到重组
            // 传值（List）而不是传 provider 会让接收方可组合函数每 tick 都不可跳过 —— 请不要改回去。
            val activeDownloadTasksState = downloadEngine.activeTasksFlow.collectAsState(initial = emptyList())
            val activeDownloadCount by downloadEngine.activeDownloadCountFlow.collectAsState()
            val activeDownloadTasksProvider: () -> List<DownloadTask> =
                remember(activeDownloadTasksState) { { activeDownloadTasksState.value } }
            val downloadSettings by downloadEngine.downloadSettings.collectAsState()

            // 歌曲列表与专项「最近添加」「最近播放」「播放列表」数据集
            var songList by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            var playlistsList by remember { mutableStateOf<List<UnifiedPlaylist>>(emptyList()) }
            // 歌单卡片四宫格封面缓存 (playlistId -> 前 4 张曲目封面)：
            // 在线歌单由服务端 user-data 的 trackSnapshots 直接下发；本地/自建歌单按需从 Room 查询。
            // 不写入 playlists 表，避免为展示字段触发数据库版本升级导致用户数据被清空。
            var playlistPreviewCovers by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
            var recentlyAddedSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            // 「最近播放」以 PlaybackQueueManager 的 flow 为**唯一真源**。
            // 先同步装载一次本地播放足迹（单次读盘成本与原实现一致），未连接服务器时直接展示这份记录；
            // 之后所有"前移/合并服务器足迹"都走 manager 的方法，界面这里不再各自维护副本
            // —— 之前 6 处手工 `listOf(song) + filter` 正是顺序被反复打乱的原因。
            remember { PlaybackQueueManager.primeRecentPlayedSongs(this@MainActivity) }
            val recentlyPlayedSongs by PlaybackQueueManager.recentPlayedSongsFlow.collectAsState()

            // 合并「服务端直下」与「本地查询」两路封面，供歌单卡片四宫格渲染
            val playlistsWithCovers = remember(playlistsList, playlistPreviewCovers) {
                if (playlistPreviewCovers.isEmpty()) playlistsList
                else playlistsList.map { pl ->
                    val covers = pl.previewCovers.ifEmpty { playlistPreviewCovers[pl.id].orEmpty() }
                    if (covers.isEmpty()) pl else pl.copy(previewCovers = covers)
                }
            }

            // 仅监听已完成的下载记录变化，彻底阻断下载进度百分比高频刷新引发的根级重组
            val completedDownloadsFlow = remember {
                database.downloadDao().getAllDownloadsFlow()
                    .map { list -> list.filter { it.status == DownloadStatus.DOWNLOADED } }
                    .distinctUntilChanged()
            }
            val allDownloads by completedDownloadsFlow.collectAsState(initial = emptyList())
            val completedDownloadedSongs by produceState(
                initialValue = emptyList<UnifiedSong>(),
                key1 = songList,
                key2 = allDownloads
            ) {
                value = withContext(Dispatchers.IO) {
                    val downloadedFromSongList = songList.filter {
                        (it.downloadStatus == DownloadStatus.DOWNLOADED ||
                         it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                         !it.localFilePath.isNullOrBlank()) &&
                        (it.localFilePath?.let { p -> p.startsWith("content://") || File(p).exists() } ?: (it.downloadStatus == DownloadStatus.DOWNLOADED))
                    }

                    val songIdsInList = downloadedFromSongList.map { it.id }.toSet()
                    val localPathsInList = downloadedFromSongList.mapNotNull { it.localFilePath }.toSet()

                    val complementaryFromDownloads = allDownloads.filter { record ->
                        record.status == DownloadStatus.DOWNLOADED &&
                        !songIdsInList.contains(record.songId) &&
                        (record.localFilePath == null || !localPathsInList.contains(record.localFilePath)) &&
                        (record.localFilePath != null && File(record.localFilePath).exists())
                    }.map { record ->
                        val file = record.localFilePath?.let { File(it) }
                        val ext = file?.extension?.ifBlank { "mp3" } ?: "mp3"
                        UnifiedSong(
                            id = record.songId,
                            title = record.title,
                            artist = record.artist,
                            artistId = "artist_${record.artist.hashCode()}",
                            album = "已下载歌曲",
                            albumId = "album_downloaded",
                            durationMs = 0L,
                            coverUrl = record.coverUrl,
                            streamUrl = record.localFilePath ?: record.remoteUrl,
                            serverId = "local_storage",
                            localFilePath = record.localFilePath,
                            downloadStatus = DownloadStatus.DOWNLOADED,
                            bitRate = 320,
                            format = ext,
                            isFavorite = false,
                            addedTimestamp = record.completedTimestamp
                        )
                    }

                    // 本地模式「按加入时间」= 本地最近下载：下载记录里的完成时间优先，
                    // 纯扫描入库（没有下载记录）的文件回落到物理文件的修改时间。
                    val downloadedAtBySongId = allDownloads
                        .filter { it.completedTimestamp > 0L }
                        .associate { it.songId to it.completedTimestamp }

                    val favSongsInDb = songList.filter { it.isFavorite }
                    val combined = (downloadedFromSongList + complementaryFromDownloads).map { s ->
                        val isFav = s.isFavorite || favSongsInDb.any { fav ->
                            fav.id == s.id || SongMatchingResolver.isSongMatch(
                                s.title, s.artist, s.durationMs,
                                fav.title, fav.artist, fav.durationMs,
                                s.album, fav.album
                            )
                        }
                        val localAdded = downloadedAtBySongId[s.id]
                            ?: s.localFilePath
                                ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
                                ?.let { runCatching { File(it).lastModified() }.getOrDefault(0L) }
                                ?.takeIf { it > 0L }
                            ?: 0L
                        val withTime = if (localAdded > 0L && s.addedTimestamp != localAdded) {
                            s.copy(addedTimestamp = localAdded)
                        } else s
                        if (isFav != withTime.isFavorite) withTime.copy(isFavorite = isFav) else withTime
                    }
                    val seenPaths = HashSet<String>()
                    val seenIds = HashSet<String>()
                    combined.filter { s ->
                        if (!seenIds.add(s.id)) return@filter false
                        val p = s.localFilePath?.trim()
                        if (!p.isNullOrEmpty()) seenPaths.add(p) else true
                    }
                }
            }

            // 全局播放状态委托至 PlaybackQueueManager
            val currentSong by PlaybackQueueManager.currentSongFlow.collectAsState()
            val isPlaying by PlaybackQueueManager.isPlayingFlow.collectAsState()
            val isShuffle by PlaybackQueueManager.isShuffleFlow.collectAsState()
            val isRepeat by PlaybackQueueManager.isRepeatFlow.collectAsState()
            val currentQueue by PlaybackQueueManager.playlistFlow.collectAsState()

            var currentLyrics by remember { mutableStateOf(LyricResult()) }
            var isFullPlayerVisible by remember { mutableStateOf(false) }
            var isLyricsMode by remember { mutableStateOf(false) }

            // 播放页主题风格 (决定进入/退出播放页的转场方式：「全屏封面」走底部滑入滑出)
            var playerThemeStyle by remember {
                val lyricsPrefs = getSharedPreferences("zds_lyrics_prefs", Context.MODE_PRIVATE)
                mutableStateOf(
                    com.lm.player.core.designsystem.theme.PlayerThemeStyle.fromId(
                        lyricsPrefs.getString(
                            "player_theme_style",
                            com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN.id
                        ) ?: com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN.id
                    )
                )
            }

            // 双击返回退出程序并彻底停止播放
            var lastBackPressTime by remember { mutableStateOf(0L) }

            BackHandler(enabled = isFullPlayerVisible || isSearchDialogOpen || (!isChildSubViewActive && currentScreen != Screen.HOME)) {
                if (isFullPlayerVisible) {
                    isFullPlayerVisible = false
                } else if (isSearchDialogOpen) {
                    isSearchDialogOpen = false
                } else if (currentScreen != Screen.HOME) {
                    popScreenOrHome()
                }
            }

            BackHandler(enabled = !isFullPlayerVisible && !isSearchDialogOpen && !isChildSubViewActive && currentScreen == Screen.HOME) {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    exitAppCompletely()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this@MainActivity, "再按一次彻底退出程序并停止播放", Toast.LENGTH_SHORT).show()
                }
            }

            // 极速同步服务器歌单 (轻量级秒级同步，完全解耦于大体量歌曲全量同步)
            val syncServerPlaylists: (ServerConfig) -> Unit = { config ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                        val protocol = LemonMusicProtocol(client, config.serverUrl, config.username, config.tokenOrApiKey)
                        val authRes = protocol.authenticate(config)
                        val effectiveToken = authRes.getOrNull() ?: config.tokenOrApiKey
                        val activeProto = if (effectiveToken.isNotBlank() && effectiveToken != config.tokenOrApiKey) {
                            LemonMusicProtocol(client, config.serverUrl, config.username, effectiveToken)
                        } else protocol

                        val playlistRes = activeProto.getPlaylists(targetServerId = config.id)
                        if (playlistRes.isSuccess) {
                            val plList = playlistRes.getOrNull() ?: emptyList()
                            // 云端歌单四宫格封面：服务端 user-data 已随歌单下发 trackSnapshots，无需额外请求
                            val remoteCovers = plList.filter { it.previewCovers.isNotEmpty() }
                                .associate { it.id to it.previewCovers }
                            if (remoteCovers.isNotEmpty()) {
                                playlistPreviewCovers = playlistPreviewCovers + remoteCovers
                            }
                            val incomingIds = plList.map { it.id }.toSet()
                            val existingPlaylists = database.playlistDao().getAllPlaylists()
                                .filter { it.serverId == config.id || it.serverId == "lemon_music" || it.serverId == config.serverUrl }
                            for (oldPl in existingPlaylists) {
                                if (!incomingIds.contains(oldPl.id) && oldPl.isOnline && !oldPl.id.startsWith("pl_")) {
                                    database.playlistDao().deletePlaylist(oldPl.id)
                                }
                            }
                            val plEntities = plList.map { pl ->
                                com.lm.player.core.database.entity.PlaylistEntity(
                                    id = pl.id,
                                    name = pl.name,
                                    coverUrl = pl.coverUrl,
                                    serverId = config.id,
                                    isOnline = true,
                                    songCount = pl.songCount
                                )
                            }
                            database.playlistDao().insertPlaylists(plEntities)
                            for (pl in plList) {
                                try {
                                    val plSongs = activeProto.getPlaylistSongs(pl.id).getOrNull()
                                    if (!plSongs.isNullOrEmpty()) {
                                        plSongs.forEachIndexed { idx, s ->
                                            database.playlistDao().addSongToPlaylist(
                                                com.lm.player.core.database.entity.PlaylistSongEntity(
                                                    playlistId = pl.id,
                                                    songId = s.id,
                                                    orderIndex = idx
                                                )
                                            )
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            Log.i("MainActivity", "Successfully synced ${plEntities.size} server playlists for server ${config.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "syncServerPlaylists failed", e)
                    }
                }
            }

            // 同步远程柠檬音乐歌曲至本地 Room 数据库 (并行异步加速，全量同步，支持静默后台刷新与显式 Toast 提示)
            val syncServerSongsWithToast: (ServerConfig, Boolean) -> Unit = { config, showToast ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                    val protocol = LemonMusicProtocol(client, config.serverUrl, config.username, config.tokenOrApiKey)
                    val authRes = protocol.authenticate(config)
                    if (authRes.isSuccess) {
                        val token = authRes.getOrNull() ?: ""
                        val activeProto = if (token.isNotBlank() && token != config.tokenOrApiKey) {
                            val updatedConfig = config.copy(tokenOrApiKey = token)
                            database.serverDao().insertServer(
                                ServerEntity(
                                    id = updatedConfig.id,
                                    name = updatedConfig.name,
                                    type = updatedConfig.type,
                                    serverUrl = updatedConfig.serverUrl,
                                    username = updatedConfig.username,
                                    tokenOrApiKey = updatedConfig.tokenOrApiKey,
                                    saltOrSecret = updatedConfig.saltOrSecret,
                                    syncMode = updatedConfig.syncMode,
                                    isCurrentActive = updatedConfig.isCurrentActive
                                )
                            )
                            LemonMusicProtocol(client, updatedConfig.serverUrl, updatedConfig.username, updatedConfig.tokenOrApiKey)
                        } else protocol

                        // 优先瞬时同步服务器歌单 (不等大体积曲库请求，秒级完成并更新界面)
                        try {
                            val playlistRes = activeProto.getPlaylists(targetServerId = config.id)
                            if (playlistRes.isSuccess) {
                                val plList = playlistRes.getOrNull() ?: emptyList()
                                val incomingIds = plList.map { it.id }.toSet()
                                val existingPlaylists = database.playlistDao().getAllPlaylists()
                                    .filter { it.serverId == config.id || it.serverId == "lemon_music" || it.serverId == config.serverUrl }
                                for (oldPl in existingPlaylists) {
                                    if (!incomingIds.contains(oldPl.id) && oldPl.isOnline && !oldPl.id.startsWith("pl_")) {
                                        database.playlistDao().deletePlaylist(oldPl.id)
                                    }
                                }
                                val plEntities = plList.map { pl ->
                                    com.lm.player.core.database.entity.PlaylistEntity(
                                        id = pl.id,
                                        name = pl.name,
                                        coverUrl = pl.coverUrl,
                                        serverId = config.id,
                                        isOnline = true,
                                        songCount = pl.songCount
                                    )
                                }
                                database.playlistDao().insertPlaylists(plEntities)
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Pre-sync playlists failed", e)
                        }

                        // 极速轻量化同步全量歌曲与最近添加、最近播放
                        val songsRes = activeProto.getSongList(offset = 0, limit = 0)
                        if (songsRes.isSuccess) {
                            val list = songsRes.getOrNull() ?: emptyList()
                            if (list.isNotEmpty()) {
                                val entities = list.map {
                                    SongEntity(
                                        id = it.id,
                                        title = it.title,
                                        artist = it.artist,
                                        artistId = it.artistId,
                                        album = it.album,
                                        albumId = it.albumId,
                                        durationMs = it.durationMs,
                                        coverUrl = it.coverUrl,
                                        streamUrl = it.streamUrl,
                                        serverId = config.id,
                                        localFilePath = it.localFilePath,
                                        downloadStatus = it.downloadStatus,
                                        bitRate = it.bitRate,
                                        format = it.format,
                                        isFavorite = it.isFavorite,
                                        relativeFolderPath = it.relativeFolderPath,
                                        // 服务器文件时间 (getSongList 里由 mtime 推得)：曲库「按加入时间」
                                        // 在线模式即以此为准。此前这里漏传，导致全部退化成 SongEntity 的
                                        // 默认值 = 同步那一刻，所有歌时间并列，排序自然看不出"最近添加"。
                                        addedTimestamp = it.addedTimestamp
                                    )
                                }
                                // 智能保护性同步：保留已有本地已下载路径、收藏与层级，杜绝覆盖重置，并进行差量清理
                                val mergedCount = SongMatchingResolver.syncAndUpsertServerSongs(
                                    database = database,
                                    incomingServerSongs = entities,
                                    downloadDir = downloadEngine.getDownloadDir(),
                                    targetServerId = config.id
                                )
                                Log.i("MainActivity", "Server sync completed. Merged & verified $mergedCount local tracks.")
                            }
                        }

                        // 同步用户收藏与用户数据 (/api/library/user-data)
                        try {
                            val userDataRes = activeProto.getLibraryUserData()
                            if (userDataRes.isSuccess) {
                                val userDataObj = userDataRes.getOrNull()
                                val favArr = userDataObj?.optJSONArray("favorites")
                                if (favArr != null && favArr.length() > 0) {
                                    for (k in 0 until favArr.length()) {
                                        val favItem = favArr.opt(k)
                                        when (favItem) {
                                            is String -> {
                                                val cleanPath = favItem.removePrefix("local:").trim()
                                                if (cleanPath.isNotBlank()) {
                                                    val directId = if (cleanPath.startsWith("lemon_")) cleanPath else "lemon_${LemonMusicProtocol.md5(cleanPath)}"
                                                    database.songDao().updateFavorite(directId, true)
                                                    database.songDao().updateFavoriteByIdOrPath(cleanPath, true)
                                                }
                                            }
                                            is org.json.JSONObject -> {
                                                val favPath = favItem.optString("localPath")
                                                    .ifBlank { favItem.optString("filePath") }
                                                    .ifBlank { favItem.optString("key") }
                                                    .ifBlank { favItem.optString("id") }
                                                    .trim()
                                                if (favPath.isNotBlank()) {
                                                    val cleanPath = favPath.removePrefix("local:").trim()
                                                    val directId = if (cleanPath.startsWith("lemon_")) cleanPath else "lemon_${LemonMusicProtocol.md5(cleanPath)}"
                                                    database.songDao().updateFavorite(directId, true)
                                                    database.songDao().updateFavoriteByIdOrPath(cleanPath, true)

                                                    // 若为在线收藏曲目且尚未入库本地 songs 表，自动补全入库以确保「我喜欢」可见
                                                    val candidateId = favItem.optString("id").takeIf { it.startsWith("lemon_") } ?: directId
                                                    val existing = database.songDao().getSongById(candidateId)
                                                    if (existing == null) {
                                                        val title = favItem.optString("title").ifBlank { favItem.optString("name") }.trim()
                                                        val artist = favItem.optString("artist").ifBlank { favItem.optString("singer") }.trim()
                                                        if (title.isNotBlank()) {
                                                            val src = favItem.optString("source").ifBlank { "kw" }
                                                            val rawSongId = favItem.optString("songId").ifBlank { candidateId.removePrefix("lemon_online_${src}_") }
                                                            val cover = favItem.optString("coverUrl").ifBlank { favItem.optString("pic") }
                                                            val stream = favItem.optString("streamUrl").ifBlank {
                                                                if (candidateId.startsWith("lemon_online_")) "lemon_online://$src/$rawSongId" else ""
                                                            }
                                                            database.songDao().insertSongs(
                                                                listOf(
                                                                    SongEntity(
                                                                        id = candidateId,
                                                                        title = title,
                                                                        artist = artist.ifBlank { "未知歌手" },
                                                                        artistId = "artist_${artist.hashCode()}",
                                                                        album = favItem.optString("album").ifBlank { "单曲精选" },
                                                                        albumId = "album_${favItem.optString("album").hashCode()}",
                                                                        durationMs = (favItem.optLong("duration", 0L).let { if (it in 1..10000) it * 1000L else it }),
                                                                        coverUrl = cover,
                                                                        streamUrl = stream,
                                                                        serverId = config.id,
                                                                        localFilePath = null,
                                                                        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                                                                        bitRate = 320,
                                                                        format = "mp3",
                                                                        isFavorite = true,
                                                                        relativeFolderPath = favItem.toString(),
                                                                        addedTimestamp = System.currentTimeMillis()
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Syncing user favorites failed", e)
                        }

                        val addedList = activeProto.getRecentlyAdded(limit = 30).getOrNull() ?: emptyList()
                        if (addedList.isNotEmpty()) recentlyAddedSongs = addedList

                        // 服务器「最近播放」足迹：与本地持久化足迹按**真实播放时间**合并而非整段拼接。
                        // 旧写法 `playedList + local.filter{...}` 会把本地独有的播放记录全部压到底部，
                        // 而服务器数组本身没有顺序保证 —— 这正是"最近播放看不出最近顺序"的直接原因。
                        val playedList = activeProto.getServerRecentPlays(limit = 30).getOrNull() ?: emptyList()
                        if (playedList.isNotEmpty()) {
                            PlaybackQueueManager.mergeServerRecentPlays(this@MainActivity, playedList)
                        }

                        // 再次对齐服务器播放列表至数据库（含差量清理）
                        val playlistRes = activeProto.getPlaylists(targetServerId = config.id)
                        if (playlistRes.isSuccess) {
                            val plList = playlistRes.getOrNull() ?: emptyList()
                            // 云端歌单四宫格封面：服务端 user-data 已随歌单下发 trackSnapshots，无需额外请求
                            val remoteCovers = plList.filter { it.previewCovers.isNotEmpty() }
                                .associate { it.id to it.previewCovers }
                            if (remoteCovers.isNotEmpty()) {
                                playlistPreviewCovers = playlistPreviewCovers + remoteCovers
                            }
                            val incomingIds = plList.map { it.id }.toSet()
                            val existingPlaylists = database.playlistDao().getAllPlaylists()
                                .filter { it.serverId == config.id || it.serverId == "lemon_music" || it.serverId == config.serverUrl }
                            for (oldPl in existingPlaylists) {
                                if (!incomingIds.contains(oldPl.id) && oldPl.isOnline && !oldPl.id.startsWith("pl_")) {
                                    database.playlistDao().deletePlaylist(oldPl.id)
                                }
                            }
                            val plEntities = plList.map { pl ->
                                com.lm.player.core.database.entity.PlaylistEntity(
                                    id = pl.id,
                                    name = pl.name,
                                    coverUrl = pl.coverUrl,
                                    serverId = config.id,
                                    isOnline = true,
                                    songCount = pl.songCount
                                )
                            }
                            database.playlistDao().insertPlaylists(plEntities)
                        }

                        if (showToast) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "已成功从柠檬服务器同步全量媒体数据与歌单", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (showToast) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "同步失败: ${authRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            val syncServerSongs: (ServerConfig) -> Unit = { config ->
                syncServerSongsWithToast(config, true)
            }

            // 监听本地数据库中的服务器、播放列表与歌曲
            LaunchedEffect(Unit) {
                database.playlistDao().getAllPlaylistsFlow().collect { entities ->
                    val mapped = entities.map { entity ->
                        UnifiedPlaylist(
                            id = entity.id,
                            name = entity.name,
                            coverUrl = entity.coverUrl,
                            songCount = entity.songCount,
                            isOnline = entity.isOnline,
                            serverId = entity.serverId,
                            isDiscover = entity.id.startsWith("discover_")
                        )
                    }
                    playlistsList = mapped

                    // 本地与自建歌单的封面目录：取歌单内前 4 首有封面的曲目，供卡片四宫格展示
                    val pending = mapped.filter { it.previewCovers.isEmpty() && playlistPreviewCovers[it.id] == null }
                    if (pending.isNotEmpty()) {
                        val fetched = withContext(Dispatchers.IO) {
                            pending.associate { pl ->
                                pl.id to runCatching {
                                    database.playlistDao().getPlaylistPreviewCovers(pl.id)
                                }.getOrDefault(emptyList())
                            }
                        }
                        if (fetched.isNotEmpty()) {
                            playlistPreviewCovers = playlistPreviewCovers + fetched
                        }
                    }
                }
            }

            // 启动时自动深度清理遗留残留数据、孤立记录与物理文件核对
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val validServers = database.serverDao().getAllServers().map { it.id }.toList()
                        val purged = LocalMediaScanner.purgeLegacyResidualData(database, validServers)
                        if (purged > 0) {
                            Log.i("MainActivity", "Startup purged $purged residual legacy records")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Startup legacy data purge error", e)
                    }
                }
            }

            LaunchedEffect(Unit) {
                database.serverDao().getAllServersFlow().collect { entities ->
                    serversList = entities.map {
                        ServerConfig(
                            id = it.id,
                            name = it.name,
                            type = it.type,
                            serverUrl = it.serverUrl,
                            username = it.username,
                            tokenOrApiKey = it.tokenOrApiKey,
                            saltOrSecret = it.saltOrSecret,
                            syncMode = it.syncMode,
                            isCurrentActive = it.isCurrentActive
                        )
                    }
                    val active = serversList.firstOrNull { it.isCurrentActive }
                    if (active != null) {
                        activeServerId = active.id

                        // 启动时优先展示本地离线界面 (activeServerName 默认为 "本地 · 已下载")
                        // 在后台异步检测服务器在线与认证状态，连接就绪后直接无缝切换至服务器首页并触发全量同步
                        if (!hasAutoCheckedServerOnStartup) {
                            hasAutoCheckedServerOnStartup = true
                            lifecycleScope.launch(Dispatchers.IO) {
                                delay(1200L)
                                try {
                                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                    val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                                    val authRes = protocol.authenticate(active)
                                    if (authRes.isSuccess) {
                                        Log.i("MainActivity", "Startup server check passed: ${active.name} online. Switching to server home...")
                                        withContext(Dispatchers.Main) {
                                            activeServerName = active.name
                                        }
                                        syncServerPlaylists(active)
                                        syncServerSongs(active)
                                    } else {
                                        Log.w("MainActivity", "Startup server check: ${active.name} is unreachable. Remaining in local mode.")
                                        withContext(Dispatchers.Main) {
                                            activeServerName = "本地 · 已下载"
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Startup auto server connection check failed", e)
                                    withContext(Dispatchers.Main) {
                                        activeServerName = "本地 · 已下载"
                                    }
                                }
                            }
                        }
                    } else {
                        activeServerName = "本地 · 已下载"
                    }
                }
            }

            LaunchedEffect(Unit) {
                database.songDao().getAllSongsFlow().collect { songEntities ->
                    val (mappedSongs, recAdded) = withContext(Dispatchers.IO) {
                        val downloadsMap = try {
                            database.downloadDao().getAllDownloadsList().associateBy { it.songId }
                        } catch (_: Exception) {
                            emptyMap()
                        }
                        val mapped = songEntities.map { entity ->
                            val dlRecord = downloadsMap[entity.id]
                            val resolvedTimestamp = when {
                                entity.addedTimestamp > 0 -> entity.addedTimestamp
                                dlRecord != null && dlRecord.completedTimestamp > 0 -> dlRecord.completedTimestamp
                                entity.downloadStatus == DownloadStatus.DOWNLOADED -> System.currentTimeMillis()
                                else -> 0L
                            }
                            val effectiveLocalPath = entity.localFilePath?.takeIf { it.isNotBlank() }
                                ?: dlRecord?.localFilePath?.takeIf { it.isNotBlank() }
                            val localExt = effectiveLocalPath
                                ?.substringAfterLast('.', "")
                                ?.substringBefore('?')
                                ?.lowercase()
                                ?.takeIf { it in setOf("flac", "wav", "ape", "alac", "m4a", "aac", "ogg", "opus", "mp3", "wma", "dsf", "dff") }
                            val resolvedFormat = localExt?.uppercase() ?: entity.format
                            val resolvedBitRate = when (localExt) {
                                "flac", "wav", "ape", "alac", "dsf", "dff" -> if (entity.bitRate >= 900) entity.bitRate else 1000
                                "m4a", "aac", "ogg", "opus", "mp3", "wma" -> if (entity.bitRate in 64..512) entity.bitRate else 320
                                else -> entity.bitRate
                            }
                            UnifiedSong(
                                id = entity.id,
                                title = entity.title,
                                artist = entity.artist,
                                artistId = entity.artistId,
                                album = entity.album,
                                albumId = entity.albumId,
                                durationMs = entity.durationMs,
                                coverUrl = entity.coverUrl,
                                streamUrl = entity.streamUrl,
                                serverId = entity.serverId,
                                localFilePath = effectiveLocalPath ?: entity.localFilePath,
                                downloadStatus = entity.downloadStatus,
                                bitRate = resolvedBitRate,
                                format = resolvedFormat,
                                isFavorite = entity.isFavorite,
                                relativeFolderPath = entity.relativeFolderPath,
                                rawMetaJson = entity.relativeFolderPath?.takeIf { it.trim().startsWith("{") },
                                addedTimestamp = resolvedTimestamp
                            )
                        }
                        val rec = mapped.filter { it.downloadStatus == DownloadStatus.DOWNLOADED || it.addedTimestamp > 0 }
                            .sortedByDescending { it.addedTimestamp }
                            .take(20)
                            .ifEmpty { mapped.sortedByDescending { it.addedTimestamp }.take(20) }
                        Pair(mapped, rec)
                    }
                    songList = mappedSongs
                    PlaybackQueueManager.updateMetadata(mappedSongs)
                    recentlyAddedSongs = recAdded
                    // 注意：此处不再用「曲库前 10 首」冒充最近播放，
                    // 真实足迹来自 PlaybackQueueManager 的本地持久化记录 (见下方 currentSong 监听)
                    if (currentSong == null) {
                        val savedSong = PlaybackQueueManager.getSavedLastSong(this@MainActivity)
                        val savedQueue = PlaybackQueueManager.getSavedQueue(this@MainActivity)
                        val lastPlayedSongId = autoPlayPrefs.getString("last_played_song_id", "")?.trim().orEmpty()
                        val targetSong = savedSong
                            ?: (if (lastPlayedSongId.isNotEmpty()) mappedSongs.firstOrNull { it.id == lastPlayedSongId } else null)
                            ?: (if (lastPlayedSongId.isEmpty()) mappedSongs.firstOrNull() else null)
                        if (targetSong != null) {
                            PlaybackQueueManager.setInitialSongIfAbsent(
                                targetSong,
                                savedQueue.ifEmpty { mappedSongs }
                            )
                        }
                    }
                }
            }

            // 统一解析上次关闭前正在播放的歌曲与所属播放列表（兼容本地曲库、已下载列表、发现页/搜索在线曲目及云端歌单）
            val resolveSavedSongAndQueue: (List<UnifiedSong>) -> Pair<UnifiedSong?, List<UnifiedSong>> = { candidates ->
                val savedSong = PlaybackQueueManager.getSavedLastSong(this@MainActivity)
                val savedQueue = PlaybackQueueManager.getSavedQueue(this@MainActivity)
                val lastPlayedId = autoPlayPrefs.getString("last_played_song_id", "")?.trim().orEmpty()
                    .ifEmpty { savedSong?.id.orEmpty() }
                val lastPlayedTitle = autoPlayPrefs.getString("last_played_song_title", "")?.trim().orEmpty()
                    .ifEmpty { savedSong?.title.orEmpty() }
                val lastPlayedArtist = autoPlayPrefs.getString("last_played_song_artist", "")?.trim().orEmpty()
                    .ifEmpty { savedSong?.artist.orEmpty() }

                val matchedById = if (lastPlayedId.isNotEmpty()) {
                    candidates.firstOrNull { it.id == lastPlayedId }
                } else null

                val matchedByMeta = if (matchedById == null && lastPlayedTitle.isNotEmpty()) {
                    val allMatches = candidates.filter { s ->
                        SongMatchingResolver.isSongMatch(
                            s.title, s.artist, s.durationMs,
                            lastPlayedTitle, lastPlayedArtist, savedSong?.durationMs ?: 0L,
                            s.album, savedSong?.album ?: ""
                        )
                    }
                    allMatches.firstOrNull { s ->
                        !s.localFilePath.isNullOrBlank() &&
                        (s.localFilePath.startsWith("content://") || File(s.localFilePath).exists())
                    } ?: allMatches.firstOrNull()
                } else null

                val matchedCandidate = matchedById ?: matchedByMeta

                val resolvedTarget: UnifiedSong? = when {
                    savedSong != null && matchedCandidate != null -> {
                        val validCandidateLocal = matchedCandidate.localFilePath?.takeIf {
                            it.isNotBlank() && (it.startsWith("content://") || File(it).exists())
                        }
                        val validSavedLocal = savedSong.localFilePath?.takeIf {
                            it.isNotBlank() && (it.startsWith("content://") || File(it).exists())
                        }
                        val effectiveLocal = validCandidateLocal ?: validSavedLocal
                        val effectiveStream = when {
                            !effectiveLocal.isNullOrBlank() -> effectiveLocal
                            matchedCandidate.streamUrl.isNotBlank() && !matchedCandidate.streamUrl.startsWith("lemon_online://") -> matchedCandidate.streamUrl
                            savedSong.streamUrl.isNotBlank() -> savedSong.streamUrl
                            else -> matchedCandidate.streamUrl
                        }
                        savedSong.copy(
                            localFilePath = effectiveLocal,
                            streamUrl = effectiveStream,
                            downloadStatus = if (!effectiveLocal.isNullOrBlank()) DownloadStatus.DOWNLOADED else matchedCandidate.downloadStatus,
                            coverUrl = savedSong.coverUrl.ifBlank { matchedCandidate.coverUrl },
                            durationMs = if (savedSong.durationMs > 0L) savedSong.durationMs else matchedCandidate.durationMs,
                            rawMetaJson = savedSong.rawMetaJson ?: matchedCandidate.rawMetaJson
                        )
                    }
                    savedSong != null -> savedSong
                    matchedCandidate != null -> matchedCandidate
                    lastPlayedId.isEmpty() -> candidates.firstOrNull()
                    else -> candidates.firstOrNull()
                }

                val resolvedQueue: List<UnifiedSong> = when {
                    savedQueue.isNotEmpty() -> {
                        val enriched = if (candidates.isNotEmpty()) {
                            SongMatchingResolver.resolveSongList(
                                incomingSongs = savedQueue,
                                allCachedSongs = candidates
                            )
                        } else {
                            savedQueue
                        }
                        if (resolvedTarget != null && enriched.none { it.id == resolvedTarget.id }) {
                            listOf(resolvedTarget) + enriched
                        } else if (resolvedTarget != null) {
                            enriched.map { if (it.id == resolvedTarget.id) resolvedTarget else it }
                        } else {
                            enriched
                        }
                    }
                    candidates.isNotEmpty() -> {
                        if (resolvedTarget != null && candidates.none { it.id == resolvedTarget.id }) {
                            listOf(resolvedTarget) + candidates
                        } else {
                            candidates
                        }
                    }
                    resolvedTarget != null -> listOf(resolvedTarget)
                    else -> emptyList()
                }

                Pair(resolvedTarget, resolvedQueue)
            }

            // 核心业务函数：播放指定歌曲 (委托至 PlaybackQueueManager 调度，支持动态上下文队列、断点进度与全局本地优先调用)
            val playSongWithQueueAndPosition: (UnifiedSong, List<UnifiedSong>?, Long) -> Unit = { targetSong, contextQueue, startPositionMs ->
                val activeQueue = when {
                    !contextQueue.isNullOrEmpty() -> contextQueue
                    PlaybackQueueManager.playlistFlow.value.isNotEmpty() -> PlaybackQueueManager.playlistFlow.value
                    else -> songList
                }

                // 核心调度：无论歌曲来自线上模式、全网搜索还是资料库，播放前统一优先核验本地物理文件
                val validDirectPath = if (!targetSong.localFilePath.isNullOrBlank() && java.io.File(targetSong.localFilePath).let { it.exists() && it.length() > 0 }) {
                    targetSong.localFilePath
                } else null

                // 若未直接携带本地路径，快速从当前已收录歌曲库与已下载列表匹配（严格校验 Live/伴奏/黑胶等版本与时长）
                val resolvedLocalPath = validDirectPath ?: run {
                    val allLocalCandidates = songList + completedDownloadedSongs
                    val matchedLocal = allLocalCandidates.firstOrNull {
                        val hasFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
                        hasFile && (it.id == targetSong.id || SongMatchingResolver.isSongMatch(
                            it.title, it.artist, it.durationMs,
                            targetSong.title, targetSong.artist, targetSong.durationMs,
                            it.album, targetSong.album
                        ))
                    }
                    matchedLocal?.localFilePath
                }

                if (resolvedLocalPath != null) {
                    val localSong = targetSong.copy(
                        localFilePath = resolvedLocalPath,
                        streamUrl = resolvedLocalPath,
                        downloadStatus = DownloadStatus.DOWNLOADED
                    )
                    // 「最近播放」前移由 playSong 内部统一完成（PlaybackQueueManager.promoteToRecentPlay）
                    val resolvedQueue = activeQueue.map { if (it.id == localSong.id) localSong else it }
                    PlaybackQueueManager.playSong(
                        targetSong = localSong,
                        context = this@MainActivity,
                        newPlaylist = resolvedQueue,
                        startPositionMs = startPositionMs
                    )
                } else if (
                    (targetSong.serverId == "lemon_online" || targetSong.id.startsWith("lemon_online_")) &&
                    !targetSong.streamUrl.contains("/api/play/local")
                ) {
                    // 立即同步当前歌曲状态与持久化，确保异步解析流地址期间界面与状态一致
                    PlaybackQueueManager.updateCurrentSong(targetSong)
                    if (!contextQueue.isNullOrEmpty()) {
                        PlaybackQueueManager.setQueue(contextQueue)
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                            ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                            ?: run {
                                val entity = try {
                                    database.serverDao().getActiveServer()
                                        ?: database.serverDao().getAllServers().firstOrNull { it.type == ServerType.LEMON_MUSIC }
                                } catch (_: Exception) { null }
                                entity?.let {
                                    ServerConfig(
                                        id = it.id,
                                        name = it.name,
                                        type = it.type,
                                        serverUrl = it.serverUrl,
                                        username = it.username,
                                        tokenOrApiKey = it.tokenOrApiKey,
                                        saltOrSecret = it.saltOrSecret,
                                        syncMode = it.syncMode,
                                        isCurrentActive = it.isCurrentActive
                                    )
                                }
                            }
                        if (active != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl,
                                active.username,
                                active.tokenOrApiKey
                            )
                            val cleanId = targetSong.id.removePrefix("lemon_online_")
                            val source = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
                            val meta = targetSong.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                ?: targetSong.relativeFolderPath?.takeIf { it.trim().startsWith("{") }

                            // 依据当前网络状态 (Wi-Fi/VPN vs 移动流量) 智能选择试听音质并阶梯探测
                            val preferredQuality = LemonMusicProtocol.getPreferredStreamQuality(this@MainActivity)
                            val resolvedStream = protocol.resolveOnlineStreamWithQuality(
                                songId = targetSong.id,
                                source = source,
                                preferredQuality = preferredQuality,
                                metaJson = meta,
                                fallbackTitle = targetSong.title,
                                fallbackArtist = targetSong.artist
                            ).getOrNull()

                            var realUrl = resolvedStream?.url

                            if (realUrl.isNullOrBlank()) {
                                // 若第三方在线源暂时无法返回直链，自动回退匹配资料库中完全同版本的服务端歌曲有效流地址
                                val matchedServerSong = songList.firstOrNull {
                                    (it.streamUrl.startsWith("http://") || it.streamUrl.startsWith("https://")) &&
                                    SongMatchingResolver.isSongMatch(
                                        it.title, it.artist, it.durationMs,
                                        targetSong.title, targetSong.artist, targetSong.durationMs,
                                        it.album, targetSong.album
                                    )
                                }
                                if (matchedServerSong != null) {
                                    val srvPath = LemonMusicProtocol.getServerFilePath(
                                        matchedServerSong.id,
                                        matchedServerSong.streamUrl,
                                        matchedServerSong.coverUrl
                                    )
                                    realUrl = if (!srvPath.isNullOrBlank()) {
                                        protocol.ensureAuthenticated()
                                        protocol.getStreamUrlForPath(srvPath)
                                    } else {
                                        matchedServerSong.streamUrl
                                    }
                                }
                            }

                            if (!realUrl.isNullOrBlank()) {
                                val resolvedSong = targetSong.copy(
                                    streamUrl = realUrl,
                                    format = resolvedStream?.format ?: targetSong.format,
                                    bitRate = resolvedStream?.bitRate ?: targetSong.bitRate
                                )
                                withContext(Dispatchers.Main) {
                                    // 先点的歌后落位守卫：在线解析期间用户可能已经点了别的歌，
                                    // 此时当前曲目已不是 targetSong，直接放弃这次播放（与歌词副作用同款写法）
                                    if (PlaybackQueueManager.currentSongFlow.value?.id != targetSong.id) return@withContext
                                    val resolvedQueue = activeQueue.map { if (it.id == resolvedSong.id) resolvedSong else it }
                                    PlaybackQueueManager.playSong(
                                        targetSong = resolvedSong,
                                        context = this@MainActivity,
                                        newPlaylist = resolvedQueue,
                                        startPositionMs = startPositionMs,
                                        // 该直链刚在上方完成解析，播放器侧无需再走一次网络解析
                                        streamPreResolved = true
                                    )
                                }
                                return@launch
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (PlaybackQueueManager.currentSongFlow.value?.id != targetSong.id) return@withContext
                            PlaybackQueueManager.playSong(
                                targetSong = targetSong,
                                context = this@MainActivity,
                                newPlaylist = activeQueue,
                                startPositionMs = startPositionMs
                            )
                        }
                    }
                } else {
                    PlaybackQueueManager.playSong(
                        targetSong = targetSong,
                        context = this@MainActivity,
                        newPlaylist = activeQueue,
                        startPositionMs = startPositionMs
                    )
                }
            }

            val playSongWithQueue: (UnifiedSong, List<UnifiedSong>?) -> Unit = { targetSong, contextQueue ->
                playSongWithQueueAndPosition(targetSong, contextQueue, 0L)
            }

            val playSong: (UnifiedSong) -> Unit = { targetSong ->
                playSongWithQueueAndPosition(targetSong, null, 0L)
            }

            // 多选音质与下载端点调度 (支持缓存至本地 / 缓存至服务器 / 双方同步缓存)
            val handleDownloadWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { songToDownload, target, quality ->
                val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }

                // 1. 服务端缓存调度 (SERVER 或 BOTH)
                if (target == DownloadTarget.SERVER || target == DownloadTarget.BOTH) {
                    if (activeServer == null) {
                        Toast.makeText(this@MainActivity, "未配置柠檬音乐服务端，无法推送至服务器曲库", Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val protocol = LemonMusicProtocol(
                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                    activeServer.serverUrl,
                                    activeServer.username,
                                    activeServer.tokenOrApiKey
                                )
                                val rawJsonStr = songToDownload.rawMetaJson ?: songToDownload.relativeFolderPath
                                // 服务器任务构造与批量下载共用同一份实现 (DownloadRequestPlanner)
                                val serverTask = DownloadRequestPlanner.buildServerDownloadTask(songToDownload, quality)
                                val res = protocol.addServerDownloadTasks(listOf(serverTask))
                                withContext(Dispatchers.Main) {
                                    if (res.isSuccess) {
                                        Toast.makeText(this@MainActivity, "已提交缓存至服务器 [${quality.badge}]: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "缓存至服务器失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                if (res.isSuccess) {
                                    // 1. 立即在本地 Room 数据库中为该歌曲建档（标记 serverId 为 activeServer.id），使其即时在程序资料库显现
                                    // 同时确保 streamUrl 不为空，防止在服务端完成扫描前同名搜索结果被匹配后无法播放
                                    val existingEntity = database.songDao().getSongById(songToDownload.id)
                                    val resolvedStreamForRecord = songToDownload.streamUrl.takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
                                        ?: existingEntity?.streamUrl?.takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
                                        ?: protocol.resolveOnlineStreamUrl(
                                            songId = songToDownload.id,
                                            source = DownloadRequestPlanner.resolveOnlineSource(songToDownload),
                                            quality = quality.key,
                                            metaJson = rawJsonStr,
                                            fallbackTitle = songToDownload.title,
                                            fallbackArtist = songToDownload.artist
                                        ).getOrNull().orEmpty()
                                    val serverSongEntity = SongEntity(
                                        id = songToDownload.id,
                                        title = songToDownload.title.ifBlank { "未知曲目" },
                                        artist = songToDownload.artist.ifBlank { "未知歌手" },
                                        artistId = songToDownload.artistId.ifBlank { "artist_${songToDownload.artist.hashCode()}" },
                                        album = songToDownload.album.ifBlank { "单曲精选" },
                                        albumId = songToDownload.albumId.ifBlank { "album_${songToDownload.album.hashCode()}" },
                                        durationMs = songToDownload.durationMs,
                                        coverUrl = songToDownload.coverUrl,
                                        streamUrl = resolvedStreamForRecord,
                                        serverId = activeServer.id,
                                        localFilePath = existingEntity?.localFilePath ?: songToDownload.localFilePath,
                                        downloadStatus = existingEntity?.downloadStatus ?: songToDownload.downloadStatus,
                                        bitRate = quality.bitrate,
                                        format = quality.format.lowercase(),
                                        isFavorite = existingEntity?.isFavorite ?: songToDownload.isFavorite,
                                        relativeFolderPath = songToDownload.relativeFolderPath?.takeIf { !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100 },
                                        addedTimestamp = System.currentTimeMillis()
                                    )
                                    database.songDao().insertSongs(listOf(serverSongEntity))

                                    // 2. 触发后台异步自动对账：延迟触发服务端扫描与本地曲库双向同步，无缝挂载物理文件
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        delay(1500L)
                                        protocol.triggerServerScan()
                                        delay(2500L)
                                        syncServerSongsWithToast(activeServer, false)
                                        delay(5000L)
                                        syncServerSongsWithToast(activeServer, false)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                // 2. 本地离线下载调度 (LOCAL 或 BOTH)
                if (target == DownloadTarget.LOCAL || target == DownloadTarget.BOTH) {
                    val safeFormat = quality.format.lowercase()
                    val preparedSong = songToDownload.copy(format = safeFormat, bitRate = quality.bitrate)

                    // 有柠檬服务端、且歌曲能按指定音质重新获取时，一律走音质决策解析器。
                    // 关键修复：服务器曲库歌曲的 streamUrl 是 /api/play/local?path=…&token=…，
                    // **URL 里没有音质参数**，以前被当作"直链歌曲"直接下载，拉到的其实是服务器上的
                    // 原文件（可能是无损）——选 320K 却下到无损就是这么来的。
                    if (activeServer != null && DownloadRequestPlanner.hasRemoteSource(songToDownload)) {
                        downloadEngine.startDownload(
                            song = preparedSong,
                            urlResolver = { resolveDownloadUrlFor(this@MainActivity, activeServer, songToDownload, quality) }
                        )
                        Toast.makeText(this@MainActivity, "已加入下载队列 [${quality.badge}]: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        // 纯本地文件：没有可换音质的来源，沿用本地直链下载
                        downloadEngine.startDownload(preparedSong)
                        Toast.makeText(this@MainActivity, "已加入本地下载: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 批量下载调度 (对齐柠檬音乐服务器端 POST /api/download/tasks 批量数组接口 + 本地并发下载池)
            val handleBatchDownloadWithOptions: (List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit = { songsToDownload, target, quality ->
                val distinctSongs = songsToDownload.distinctBy { it.id }
                if (distinctSongs.isNotEmpty()) {
                    val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }

                    // 已在服务器曲库的歌曲不再重复推送 —— 这正是"明明已在服务器却还提示下载到服务器"的根因
                    val songsNeedingServerPush = distinctSongs.filterNot { isSongOnLemonServer(it, activeServer != null) }
                    val serverAlreadyCount = distinctSongs.size - songsNeedingServerPush.size

                    // 1. 服务端批量缓存调度 (SERVER 或 BOTH)
                    if (target == DownloadTarget.SERVER || target == DownloadTarget.BOTH) {
                        if (activeServer == null) {
                            Toast.makeText(this@MainActivity, "未配置柠檬音乐服务端，无法推送至服务器曲库", Toast.LENGTH_SHORT).show()
                        } else if (songsNeedingServerPush.isEmpty()) {
                            Toast.makeText(this@MainActivity, "所选 $serverAlreadyCount 首歌曲均已在服务器曲库，无需重复下载", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val protocol = LemonMusicProtocol(
                                        NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                        activeServer.serverUrl,
                                        activeServer.username,
                                        activeServer.tokenOrApiKey
                                    )
                                    // 任务构造与单首下载共用同一份实现 (DownloadRequestPlanner)
                                    val serverTasks = songsNeedingServerPush.map {
                                        DownloadRequestPlanner.buildServerDownloadTask(it, quality)
                                    }

                                    var successChunks = 0
                                    for (chunk in serverTasks.chunked(50)) {
                                        if (protocol.addServerDownloadTasks(chunk).isSuccess) {
                                            successChunks++
                                        }
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (successChunks > 0) {
                                            val skipTip = if (serverAlreadyCount > 0) "，跳过 $serverAlreadyCount 首（已在服务器）" else ""
                                            Toast.makeText(
                                                this@MainActivity,
                                                "已提交 ${songsNeedingServerPush.size} 首至服务器缓存 [${quality.badge}]$skipTip",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "批量提交服务器缓存失败", Toast.LENGTH_LONG).show()
                                        }
                                    }

                                    if (successChunks > 0) {
                                        val nowTs = System.currentTimeMillis()
                                        val entities = songsNeedingServerPush.mapIndexed { idx, s ->
                                            val existingEntity = database.songDao().getSongById(s.id)
                                            SongEntity(
                                                id = s.id,
                                                title = s.title.ifBlank { "未知曲目" },
                                                artist = s.artist.ifBlank { "未知歌手" },
                                                artistId = s.artistId.ifBlank { "artist_${s.artist.hashCode()}" },
                                                album = s.album.ifBlank { "单曲精选" },
                                                albumId = s.albumId.ifBlank { "album_${s.album.hashCode()}" },
                                                durationMs = s.durationMs,
                                                coverUrl = s.coverUrl,
                                                streamUrl = s.streamUrl.takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
                                                    ?: existingEntity?.streamUrl.orEmpty(),
                                                serverId = activeServer.id,
                                                localFilePath = existingEntity?.localFilePath ?: s.localFilePath,
                                                downloadStatus = existingEntity?.downloadStatus ?: s.downloadStatus,
                                                bitRate = quality.bitrate,
                                                format = quality.format.lowercase(),
                                                isFavorite = existingEntity?.isFavorite ?: s.isFavorite,
                                                relativeFolderPath = s.relativeFolderPath?.takeIf { !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100 },
                                                addedTimestamp = nowTs + idx
                                            )
                                        }
                                        database.songDao().insertSongs(entities)

                                        lifecycleScope.launch(Dispatchers.IO) {
                                            delay(2500L)
                                            protocol.triggerServerScan()
                                            delay(3000L)
                                            syncServerSongsWithToast(activeServer, false)
                                            delay(6000L)
                                            syncServerSongsWithToast(activeServer, false)
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "批量缓存网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }

                    // 2. 本地批量离线下载调度 (LOCAL 或 BOTH)
                    if (target == DownloadTarget.LOCAL || target == DownloadTarget.BOTH) {
                        val safeFormat = quality.format.lowercase()
                        var enqueuedCount = 0
                        var skippedCount = 0
                        distinctSongs.forEach { songToDownload ->
                            val localPath = songToDownload.localFilePath
                            val hasLocalFile = !localPath.isNullOrBlank() &&
                                (localPath.startsWith("content://") || File(localPath).exists())
                            // 能按指定音质重新获取的来源（在线曲目/服务器曲库曲目）：已有文件的音质档次
                            // 与目标不符时不能算"已下载"，否则选 320K 会一直命中本地那份无损而被跳过
                            val serverForRefetch = activeServer?.takeIf {
                                DownloadRequestPlanner.hasRemoteSource(songToDownload)
                            }
                            val alreadyHaveTarget = hasLocalFile && (
                                serverForRefetch == null ||
                                    DownloadRequestPlanner.existingLocalFileSatisfies(songToDownload, quality)
                                )
                            if (alreadyHaveTarget) {
                                skippedCount++
                                return@forEach
                            }
                            enqueuedCount++
                            val preparedSong = songToDownload.copy(format = safeFormat, bitRate = quality.bitrate)
                            if (serverForRefetch != null) {
                                downloadEngine.startDownload(
                                    song = preparedSong,
                                    urlResolver = { resolveDownloadUrlFor(this@MainActivity, serverForRefetch, songToDownload, quality) }
                                )
                            } else {
                                downloadEngine.startDownload(preparedSong)
                            }
                        }
                        val skipTip = if (skippedCount > 0) "，跳过 $skippedCount 首（已存在该音质）" else ""
                        Toast.makeText(
                            this@MainActivity,
                            if (enqueuedCount > 0) {
                                "已将 $enqueuedCount 首歌曲加入本地下载队列 [${quality.badge}]$skipTip"
                            } else {
                                "所选 $skippedCount 首歌曲均已下载至本地"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            val handleDownloadSong: (UnifiedSong) -> Unit = { songToDownload ->
                val savedTargetName = uiPrefs.getString("default_download_target", DownloadTarget.LOCAL.name) ?: DownloadTarget.LOCAL.name
                val defaultTarget = try { DownloadTarget.valueOf(savedTargetName) } catch (_: Exception) { DownloadTarget.LOCAL }
                val savedQualityKey = uiPrefs.getString("default_download_quality", AudioQuality.Q_320K.key) ?: AudioQuality.Q_320K.key
                val defaultQuality = AudioQuality.entries.firstOrNull { it.key == savedQualityKey } ?: AudioQuality.Q_320K
                handleDownloadWithOptions(songToDownload, defaultTarget, defaultQuality)
            }

            val handleToggleFavorite: (UnifiedSong) -> Unit = { songToFav ->
                val newFav = !songToFav.isFavorite
                val updatedSong = songToFav.copy(isFavorite = newFav)
                songList = if (songList.any { it.id == songToFav.id }) {
                    songList.map { if (it.id == songToFav.id) updatedSong else it }
                } else {
                    listOf(updatedSong) + songList
                }
                if (PlaybackQueueManager.currentSongFlow.value?.id == songToFav.id) {
                    PlaybackQueueManager.updateCurrentSong(updatedSong)
                }
                Toast.makeText(
                    this@MainActivity,
                    if (newFav) "已加入「我喜欢的音乐」" else "已从「我喜欢的音乐」移除",
                    Toast.LENGTH_SHORT
                ).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val existing = database.songDao().getSongById(songToFav.id)
                        if (existing == null) {
                            val activeSrv = serversList.firstOrNull { it.isCurrentActive } ?: serversList.firstOrNull()
                            val effectiveServerId = when {
                                songToFav.serverId.isNotBlank() && songToFav.serverId != "lemon_online" -> songToFav.serverId
                                activeSrv != null -> activeSrv.id
                                else -> "local_storage"
                            }
                            database.songDao().insertSongs(
                                listOf(
                                    SongEntity(
                                        id = updatedSong.id,
                                        title = updatedSong.title.ifBlank { "未知曲目" },
                                        artist = updatedSong.artist.ifBlank { "未知歌手" },
                                        artistId = updatedSong.artistId.ifBlank { "artist_${updatedSong.artist.hashCode()}" },
                                        album = updatedSong.album.ifBlank { "单曲精选" },
                                        albumId = updatedSong.albumId.ifBlank { "album_${updatedSong.album.hashCode()}" },
                                        durationMs = updatedSong.durationMs,
                                        coverUrl = updatedSong.coverUrl,
                                        streamUrl = updatedSong.streamUrl,
                                        serverId = effectiveServerId,
                                        localFilePath = updatedSong.localFilePath,
                                        downloadStatus = updatedSong.downloadStatus,
                                        bitRate = updatedSong.bitRate,
                                        format = updatedSong.format,
                                        isFavorite = newFav,
                                        relativeFolderPath = updatedSong.rawMetaJson ?: updatedSong.relativeFolderPath,
                                        addedTimestamp = System.currentTimeMillis()
                                    )
                                )
                            )
                        } else {
                            database.songDao().updateFavorite(songToFav.id, newFav)
                        }
                        if (newFav) {
                            database.playlistDao().addSongToPlaylist(
                                com.lm.player.core.database.entity.PlaylistSongEntity(
                                    playlistId = "lemon_favorites",
                                    songId = songToFav.id
                                )
                            )
                        } else {
                            database.playlistDao().removeSongFromPlaylist("lemon_favorites", songToFav.id)
                        }
                        database.playlistDao().updateSongCount("lemon_favorites")

                        val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                            ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                        if (activeServer != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                activeServer.serverUrl,
                                activeServer.username,
                                activeServer.tokenOrApiKey
                            )
                            protocol.toggleFavoriteSongOnServer(updatedSong, newFav)
                            syncServerPlaylists(activeServer)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleToggleFavorite error", e)
                    }
                }
            }

            val handleAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { targetPlaylist, songToAdd ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                            ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                        val isFavPlaylist = targetPlaylist.id == "lemon_favorites" || targetPlaylist.name == "我的收藏" || targetPlaylist.name == "我喜欢" || targetPlaylist.name == "我喜欢的音乐"

                        // 1. 确保歌曲在本地 song 表中持久化
                        val existing = database.songDao().getSongById(songToAdd.id)
                        val effectiveServerId = when {
                            existing != null && existing.serverId.isNotBlank() && existing.serverId != "lemon_online" -> existing.serverId
                            songToAdd.serverId.isNotBlank() && songToAdd.serverId != "lemon_online" -> songToAdd.serverId
                            active != null -> active.id
                            else -> "local_storage"
                        }
                        val finalFav = existing?.isFavorite == true || songToAdd.isFavorite || isFavPlaylist
                        database.songDao().insertSongs(
                            listOf(
                                SongEntity(
                                    id = songToAdd.id,
                                    title = songToAdd.title.ifBlank { "未知曲目" },
                                    artist = songToAdd.artist.ifBlank { "未知歌手" },
                                    artistId = songToAdd.artistId.ifBlank { "artist_${songToAdd.artist.hashCode()}" },
                                    album = songToAdd.album.ifBlank { "单曲精选" },
                                    albumId = songToAdd.albumId.ifBlank { "album_${songToAdd.album.hashCode()}" },
                                    durationMs = songToAdd.durationMs,
                                    coverUrl = songToAdd.coverUrl.ifBlank { existing?.coverUrl.orEmpty() },
                                    streamUrl = songToAdd.streamUrl.ifBlank { existing?.streamUrl.orEmpty() },
                                    serverId = effectiveServerId,
                                    localFilePath = existing?.localFilePath ?: songToAdd.localFilePath,
                                    downloadStatus = existing?.downloadStatus ?: songToAdd.downloadStatus,
                                    bitRate = songToAdd.bitRate,
                                    format = songToAdd.format,
                                    isFavorite = finalFav,
                                    relativeFolderPath = songToAdd.rawMetaJson ?: existing?.relativeFolderPath ?: songToAdd.relativeFolderPath,
                                    addedTimestamp = existing?.addedTimestamp?.takeIf { it > 0L } ?: System.currentTimeMillis()
                                )
                            )
                        )

                        if (isFavPlaylist) {
                            withContext(Dispatchers.Main) {
                                val updatedSong = songToAdd.copy(isFavorite = true)
                                songList = if (songList.any { it.id == songToAdd.id }) {
                                    songList.map { if (it.id == songToAdd.id) updatedSong else it }
                                } else {
                                    listOf(updatedSong) + songList
                                }
                                if (PlaybackQueueManager.currentSongFlow.value?.id == songToAdd.id) {
                                    PlaybackQueueManager.updateCurrentSong(updatedSong)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                if (songList.none { it.id == songToAdd.id }) {
                                    songList = listOf(songToAdd.copy(serverId = effectiveServerId)) + songList
                                }
                            }
                        }

                        // 2. 本地插入 playlist_songs 关联
                        database.playlistDao().addSongToPlaylist(
                            com.lm.player.core.database.entity.PlaylistSongEntity(
                                playlistId = targetPlaylist.id,
                                songId = songToAdd.id
                            )
                        )
                        database.playlistDao().updateSongCount(targetPlaylist.id)

                        // 3. 若配置了服务端，同步至服务器 customPlaylists / favorites
                        if (active != null && (targetPlaylist.isOnline || targetPlaylist.serverId == active.id || targetPlaylist.serverId == "lemon_music" || isFavPlaylist)) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl,
                                active.username,
                                active.tokenOrApiKey
                            )
                            val serverPath = LemonMusicProtocol.getServerFilePath(songToAdd.id, songToAdd.streamUrl, songToAdd.coverUrl)
                            val key = when {
                                !serverPath.isNullOrBlank() -> "local:$serverPath"
                                songToAdd.id.startsWith("lemon_") && songToAdd.streamUrl.contains("path=") -> {
                                    val decoded = try {
                                        java.net.URLDecoder.decode(songToAdd.streamUrl.substringAfter("path=").substringBefore("&"), "UTF-8")
                                    } catch (_: Exception) { null }
                                    if (decoded != null) "local:$decoded" else songToAdd.id
                                }
                                else -> songToAdd.id
                            }
                            protocol.addTracksToCustomPlaylist(targetPlaylist.id, listOf(key), listOf(songToAdd))
                            if (isFavPlaylist) {
                                protocol.toggleFavoriteSongOnServer(songToAdd.copy(isFavorite = true), true)
                            }
                            syncServerPlaylists(active)
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "已将《${songToAdd.title}》加入「${targetPlaylist.name}」",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleAddToPlaylist error", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "加入歌单失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            val handleCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit = { playlistName, songToAdd ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val isCurrentLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                        val active = if (isCurrentLocalMode) null else (serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC } ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC })
                        var newPlId = "pl_${System.currentTimeMillis()}"
                        var isOnlinePl = active != null

                        if (isOnlinePl && active != null) {
                            try {
                                val protocol = LemonMusicProtocol(
                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                    active.serverUrl,
                                    active.username,
                                    active.tokenOrApiKey
                                )
                                val res = protocol.createCustomPlaylist(playlistName)
                                if (res.isSuccess) {
                                    res.getOrNull()?.let {
                                        newPlId = it.id
                                        isOnlinePl = true
                                    }
                                } else {
                                    isOnlinePl = false
                                }
                            } catch (e: Exception) {
                                Log.w("MainActivity", "Create server playlist failed, fallback to local", e)
                                isOnlinePl = false
                            }
                        }

                        database.playlistDao().insertPlaylist(
                            com.lm.player.core.database.entity.PlaylistEntity(
                                id = newPlId,
                                name = playlistName,
                                coverUrl = songToAdd.coverUrl,
                                serverId = if (isOnlinePl && active != null) active.id else "local_storage",
                                isOnline = isOnlinePl,
                                songCount = 1
                            )
                        )

                        val createdPlaylist = UnifiedPlaylist(
                            id = newPlId,
                            name = playlistName,
                            coverUrl = songToAdd.coverUrl,
                            isOnline = isOnlinePl,
                            serverId = if (isOnlinePl && active != null) active.id else "local_storage",
                            songCount = 1
                        )
                        handleAddToPlaylist(createdPlaylist, songToAdd)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleCreatePlaylistAndAddSong error", e)
                    }
                }
            }

            val playNext: () -> Unit = {
                PlaybackQueueManager.playNext(this@MainActivity)
            }

            val playPrevious: () -> Unit = {
                PlaybackQueueManager.playPrevious(this@MainActivity)
            }

            val togglePlayPause: () -> Unit = {
                PlaybackQueueManager.togglePlay(this@MainActivity)
            }

            // 挂载全局方向盘控制与按键动作
            LaunchedEffect(Unit) {
                playNextAction = playNext
                playPreviousAction = playPrevious
                togglePlayAction = togglePlayPause
            }

            // 启动自动播放与断点恢复逻辑 (启动时精准恢复并继续播放上一次关闭前的那一首歌曲与进度)
            LaunchedEffect(songList, completedDownloadedSongs, autoPlayOnStartup) {
                if (!autoPlayOnStartup || hasAutoPlayedOnStartup) return@LaunchedEffect
                if (exoPlayer?.isPlaying == true && currentSong != null) {
                    hasAutoPlayedOnStartup = true
                    return@LaunchedEffect
                }
                if (songList.isEmpty() && completedDownloadedSongs.isEmpty()) {
                    delay(300L)
                }
                if (hasAutoPlayedOnStartup) return@LaunchedEffect
                val combinedCandidates = (songList + completedDownloadedSongs).distinctBy { it.id }
                val (targetSong, targetQueue) = resolveSavedSongAndQueue(combinedCandidates)
                if (targetSong != null) {
                    hasAutoPlayedOnStartup = true
                    val savedPos = PlaybackQueueManager.getSavedPositionMs(this@MainActivity)
                    val resumePos = if (targetSong.durationMs > 0L && savedPos >= targetSong.durationMs - 2000L) {
                        0L
                    } else {
                        savedPos.coerceAtLeast(0L)
                    }
                    playSongWithQueueAndPosition(
                        targetSong,
                        targetQueue.ifEmpty { listOf(targetSong) },
                        resumePos
                    )
                }
            }

            // 播放期间的 5 秒周期落盘已由 PlaybackQueueManager.startPeriodicPositionSave 统一负责，
            // 这里原先还有一份完全等价的循环：两者每 5 秒各序列化一次最多 120 首的播放队列并各写一次
            // SharedPreferences，重复且无必要，故删除此处这份。

            // 实时维护「最近播放」足迹：真正开始播放后才记录，
            // 启动时仅预置状态 (setInitialSongIfAbsent) 而未播放的曲目不会被计入播放历史
            LaunchedEffect(currentSong?.id, isPlaying) {
                if (!isPlaying) return@LaunchedEffect
                val played = currentSong ?: return@LaunchedEffect
                if (played.id.isBlank() && played.title.isBlank()) return@LaunchedEffect
                // 这里只作兜底：真正的记录发生在 playSong 内。若当前曲目已经是足迹头条，
                // 说明本次播放已记录过（或因"暂停→续播"重新触发本效应），不再重复前移，
                // 否则续播会把当前歌的时间刷成"现在"，打乱与其他设备播放记录的相对顺序。
                if (PlaybackQueueManager.recentPlayedSongsFlow.value.firstOrNull()?.id == played.id) {
                    return@LaunchedEffect
                }
                PlaybackQueueManager.promoteToRecentPlay(this@MainActivity, played)
            }

            // 在线状态下以服务器记录的最近播放为准 (离线时回落到上面的本地足迹)。
            // 本地模式下不请求服务器足迹，避免把本地歌单外的在线曲目混进「最近播放」。
            LaunchedEffect(serversList, activeServerName) {
                if (activeServerName.contains("本地") || activeServerName.contains("已下载")) return@LaunchedEffect
                val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                    ?: return@LaunchedEffect
                val serverRecent = try {
                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                    val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                    protocol.getServerRecentPlays(limit = 30).getOrNull().orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                if (serverRecent.isNotEmpty()) {
                    // 与本地足迹按真实播放时间合并：本地已记录的歌曲保留自己的时间，
                    // 服务器独有的历史从当前最新时间起依次插入，两者顺序都不被对方整体覆盖
                    PlaybackQueueManager.mergeServerRecentPlays(this@MainActivity, serverRecent)
                }
            }

            // 歌曲切换时动态从音乐文件/NAS提取解析歌词 (支持秒级内存预载与防并发竞争)
            LaunchedEffect(currentSong?.id) {
                val targetSong = currentSong ?: return@LaunchedEffect
                val cached = LyricsManager.getCachedLyrics(targetSong.id)
                if (cached != null && cached.lines.isNotEmpty()) {
                    currentLyrics = cached
                } else {
                    currentLyrics = LyricResult(emptyList())
                    val activeServer = serversList.firstOrNull { it.isCurrentActive }
                    val loaded = withContext(Dispatchers.IO) {
                        LyricsManager.loadLyrics(targetSong, this@MainActivity, activeServer)
                    }
                    if (currentSong?.id == targetSong.id) {
                        currentLyrics = loaded
                    }
                }
            }

            // 监听 ExoPlayer 在线容灾回退与中途断流无缝断点续播
            var lastStreamRetrySongId by remember { mutableStateOf("") }
            var lastStreamRetryTimeMs by remember { mutableStateOf(0L) }
            DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("MainActivity", "Player Error encountered: ${error.message}", error)
                        val targetSong = currentSong
                        val resumePos = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
                        if (targetSong != null) {
                            if (autoFallbackToLocal) {
                                // 1. 优先尝试切换至本地离线音频文件并保留当前播放进度（严格核对版本与时长）
                                val matchedLocalSong = (songList + completedDownloadedSongs).firstOrNull {
                                    val hasFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
                                    hasFile && (it.id == targetSong.id || SongMatchingResolver.isSongMatch(
                                        it.title, it.artist, it.durationMs,
                                        targetSong.title, targetSong.artist, targetSong.durationMs,
                                        it.album, targetSong.album
                                    ))
                                }
                                if (matchedLocalSong != null && matchedLocalSong.localFilePath != targetSong.localFilePath) {
                                    Toast.makeText(this@MainActivity, "在线音频缓冲受阻，已无缝切换至本地离线版本", Toast.LENGTH_SHORT).show()
                                    val localSong = targetSong.copy(
                                        localFilePath = matchedLocalSong.localFilePath,
                                        streamUrl = matchedLocalSong.localFilePath ?: "",
                                        downloadStatus = DownloadStatus.DOWNLOADED
                                    )
                                    PlaybackQueueManager.playSong(
                                        targetSong = localSong,
                                        context = this@MainActivity,
                                        startPositionMs = resumePos
                                    )
                                    return
                                }
                            }

                            // 2. 若在线流或柠檬代理流播放中途断流/超时，自动向柠檬服务器重新换取最新流地址并从当前秒数无缝续播
                            val now = System.currentTimeMillis()
                            val canRetryRefresh = lastStreamRetrySongId != targetSong.id || (now - lastStreamRetryTimeMs) > 10_000L
                            if (canRetryRefresh) {
                                lastStreamRetrySongId = targetSong.id
                                lastStreamRetryTimeMs = now
                                Log.i("MainActivity", "Auto-recovering stream for ${targetSong.title} at ${resumePos}ms")
                                PlaybackQueueManager.playSong(
                                    targetSong = targetSong,
                                    context = this@MainActivity,
                                    startPositionMs = resumePos,
                                    forceRefresh = true
                                )
                                return
                            }

                            if (autoFallbackToLocal) {
                                // 3. 若重新刷新仍失败，尝试回退至服务器资料库内同版本已归档曲目并保留播放进度
                                val matchedServerSong = songList.firstOrNull {
                                    it.id != targetSong.id &&
                                    it.streamUrl != targetSong.streamUrl &&
                                    (it.streamUrl.contains("/api/play/local") || it.streamUrl.startsWith("http")) &&
                                    SongMatchingResolver.isSongMatch(
                                        it.title, it.artist, it.durationMs,
                                        targetSong.title, targetSong.artist, targetSong.durationMs,
                                        it.album, targetSong.album
                                    )
                                }
                                if (matchedServerSong != null) {
                                    Toast.makeText(this@MainActivity, "在线音源缓冲受阻，已自动切换至服务器资料库同名版本", Toast.LENGTH_SHORT).show()
                                    PlaybackQueueManager.playSong(
                                        targetSong = matchedServerSong,
                                        context = this@MainActivity,
                                        startPositionMs = resumePos
                                    )
                                    return
                                }
                            }
                        }
                        Toast.makeText(this@MainActivity, "当前歌曲《${currentSong?.title ?: "未知"}》音频无法缓冲，请检查网络或服务端连接", Toast.LENGTH_SHORT).show()
                    }
                }
                exoPlayer?.addListener(listener)
                onDispose {
                    exoPlayer?.removeListener(listener)
                }
            }

            CompositionLocalProvider(LocalAppDimensions provides appDimensions) {
                ZDSPlayerTheme(themeMode = currentThemeMode) {
                    // 全局触屏边缘向右滑动返回手势监听 (适配现代全面屏返回手势)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    val edgeThreshold = 44.dp.toPx()
                                    while (true) {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        if (down.position.x <= edgeThreshold) {
                                            var totalDx = 0f
                                            var totalDy = 0f
                                            var triggered = false
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                val drag = event.changes.firstOrNull { it.id == down.id } ?: break
                                                totalDx += (drag.position.x - drag.previousPosition.x)
                                                totalDy += (drag.position.y - drag.previousPosition.y)

                                                if (!triggered && totalDx > 65.dp.toPx() && totalDx > kotlin.math.abs(totalDy) * 1.4f) {
                                                    triggered = true
                                                    drag.consume()
                                                    onBackPressedDispatcher.onBackPressed()
                                                    break
                                                }
                                                if (drag.changedToUp() || !drag.pressed) break
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        // 当切换至任意其他页面时，若全网搜索处于打开状态则自动退出搜索
                        LaunchedEffect(currentScreen) {
                            if (isSearchDialogOpen) {
                                isSearchDialogOpen = false
                            }
                        }

                        // 常驻黑胶播控大卡实时进度轮询
                        var scaffoldProgressMs by remember(currentSong?.id) {
                            mutableLongStateOf(exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L)
                        }
                        var scaffoldDurationMs by remember(currentSong?.id) {
                            mutableLongStateOf(
                                (exoPlayer?.duration?.takeIf { it > 0L } ?: currentSong?.durationMs ?: 0L).coerceAtLeast(0L)
                            )
                        }
                        // 同样不依赖 isPlaying：暂停/拖动后主界面卡片的进度条也要实时反映真实播放位置。
                        // 但这里做了三重收敛，避免「暂停/后台/全屏播放页已打开」时仍在空转：
                        // 1) 仅在前台可见时轮询 (repeatOnLifecycle)，退到后台立即停止；
                        // 2) 全屏播放页自己会刷新进度，此时底层脚手架不再重复轮询；
                        // 3) 值未变化时不写状态，暂停时因此完全不产生重组。
                        LaunchedEffect(currentSong?.id, isFullPlayerVisible) {
                            if (isFullPlayerVisible) return@LaunchedEffect
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                while (isActive) {
                                    val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                    if (pos != scaffoldProgressMs) scaffoldProgressMs = pos
                                    val dur = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                                    val resolvedDur = if (dur > 0L) dur else (currentSong?.durationMs ?: 0L)
                                    if (resolvedDur > 0L && resolvedDur != scaffoldDurationMs) {
                                        scaffoldDurationMs = resolvedDur
                                    }
                                    delay(if (isPlaying) 500L else 1000L)
                                }
                            }
                        }

                        // 响应式脚手架 (顶部导航栏 + 左侧常驻黑胶播控大卡 + 右侧主内容舞台；全屏播放器打开时自动冻结底层焦点)
                        CompositionLocalProvider(LocalTvBackgroundFocusEnabled provides !isFullPlayerVisible) {
                        AdaptiveAppScaffold(
                        windowSizeClass = windowSizeClass.widthSizeClass,
                        currentScreen = currentScreen,
                        isSearchOpen = isSearchDialogOpen,
                        isFullPlayerVisible = isFullPlayerVisible,
                        currentPlayingSong = currentSong,
                        isPlaying = isPlaying,
                        // 传取数函数而不是值：值一旦在这里被读出，整个 Activity 组合树
                        // 就会被登记成进度状态的观察者，每 0.5~1 秒重组一次 (3200+ 行)
                        progressMsProvider = { scaffoldProgressMs },
                        totalDurationMs = scaffoldDurationMs,
                        currentLyrics = currentLyrics,
                        onSeekTo = { seekPos ->
                            exoPlayer?.seekTo(seekPos)
                            scaffoldProgressMs = seekPos
                        },
                        currentServerName = activeServerName,
                        configuredServers = serversList,
                        // 读结构流（数量变化才发射），不再从 3Hz 的任务列表里取 size —— 这是根作用域，
                        // 读它会连带整棵页面树每 300ms 重组一次
                        activeDownloadCount = activeDownloadCount,
                        onOpenDownloads = { navigateToScreen(Screen.DOWNLOADS) },
                        enableBottomBarAnimation = enableBottomBarAnimation,
                        useLinearAnimation = true,
                        onNavigate = { targetScreen ->
                            navigateToScreen(targetScreen)
                            isSearchDialogOpen = false
                            if (targetScreen == Screen.MINE || targetScreen == Screen.LIBRARY) {
                                // 每次点击「我的」时都进行服务器端链接的刷新与数据同步，保证数据实时同步
                                val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                                val active = serversList.firstOrNull { it.isCurrentActive } ?: serversList.firstOrNull()
                                if (active != null && !isLocalMode) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        try {
                                            val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                            val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                                            val authRes = protocol.authenticate(active)
                                            if (authRes.isSuccess) {
                                                withContext(Dispatchers.Main) {
                                                    activeServerName = active.name
                                                    activeServerId = active.id
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("MainActivity", "Refresh server connection on MINE click failed", e)
                                        }
                                        syncServerPlaylists(active)
                                        syncServerSongsWithToast(active, false)
                                    }
                                } else if (active != null && isLocalMode) {
                                    // 若已配置服务器但此前断网处于本地模式，点击「我的」时自动尝试重连刷新服务器
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        try {
                                            val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                            val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                                            val authRes = protocol.authenticate(active)
                                            if (authRes.isSuccess) {
                                                withContext(Dispatchers.Main) {
                                                    activeServerName = active.name
                                                    activeServerId = active.id
                                                }
                                                syncServerPlaylists(active)
                                                syncServerSongsWithToast(active, false)
                                                return@launch
                                            }
                                        } catch (_: Exception) {}
                                        val dlDir = downloadEngine.getDownloadDir()
                                        LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, dlDir)
                                    }
                                } else {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val dlDir = downloadEngine.getDownloadDir()
                                        LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, dlDir)
                                    }
                                }
                            }
                        },
                        onPlayPauseToggle = togglePlayPause,
                        onPrevious = playPrevious,
                        onNext = playNext,
                        onOpenFullPlayer = {
                            isSearchDialogOpen = false
                            isFullPlayerVisible = true
                        },
                        onSearchClick = {
                            isSearchDialogOpen = false
                            navigateToScreen(Screen.SEARCH)
                        },
                        onSelectLocalServer = {
                            activeServerName = "本地 · 已下载"
                        },
                        onSelectServer = { selectedServer ->
                            activeServerName = selectedServer.name
                            activeServerId = selectedServer.id
                            lifecycleScope.launch(Dispatchers.IO) {
                                database.serverDao().setActiveServer(selectedServer.id)
                                syncServerPlaylists(selectedServer)
                                syncServerSongs(selectedServer)
                            }
                        },
                        onSyncNow = {
                            val active = serversList.firstOrNull { it.isCurrentActive }
                            if (active != null) {
                                Toast.makeText(this@MainActivity, "正在从柠檬音乐同步全量曲库...", Toast.LENGTH_SHORT).show()
                                syncServerPlaylists(active)
                                syncServerSongs(active)
                            } else {
                                Toast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onToggleFavorite = {
                            currentSong?.let { handleToggleFavorite(it) }
                        },
                        isShuffle = isShuffle,
                        isRepeat = isRepeat,
                        onTogglePlayMode = {
                            when {
                                !isShuffle && !isRepeat -> {
                                    PlaybackQueueManager.setShuffle(true)
                                    PlaybackQueueManager.setRepeat(false)
                                    Toast.makeText(this@MainActivity, "已切换为：随机播放", Toast.LENGTH_SHORT).show()
                                }
                                isShuffle -> {
                                    PlaybackQueueManager.setShuffle(false)
                                    PlaybackQueueManager.setRepeat(true)
                                    Toast.makeText(this@MainActivity, "已切换为：单曲循环", Toast.LENGTH_SHORT).show()
                                }
                                else -> {
                                    PlaybackQueueManager.setShuffle(false)
                                    PlaybackQueueManager.setRepeat(false)
                                    Toast.makeText(this@MainActivity, "已切换为：列表循环", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!isSearchDialogOpen) {
                                AnimatedContent(
                                targetState = currentScreen,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing)) togetherWith
                                            fadeOut(animationSpec = tween(120, easing = FastOutSlowInEasing))
                                },
                                label = "screen_transition"
                            ) { targetScreen ->
                                when (targetScreen) {
                                // Screen.MINE 与 Screen.LIBRARY 已融合为统一的「我的 (含在线曲库与本地缓存)」页面

                                Screen.SEARCH -> {
                                    val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                                    val onlineSearchCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeServer) {
                                        val srv = activeServer
                                        if (srv != null) {
                                            { keyword, source ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                                protocol.searchOnline(keyword, source = source.key).getOrNull() ?: emptyList()
                                            }
                                        } else null
                                    }
                                    val parsePlaylistCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeServer) {
                                        val srv = activeServer
                                        if (srv != null) {
                                            { url, source ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                                protocol.parseExternalPlaylist(urlOrId = url, source = source.key).getOrNull() ?: emptyList()
                                            }
                                        } else null
                                    }
                                    LibrarySearchDialog(
                                        allSongs = songList,
                                        activeDownloadTasks = activeDownloadTasksProvider,
                                        currentPlayingSong = currentSong,
                                        isPlaying = isPlaying,
                                        onSongClick = { targetSong, queue ->
                                            playSongWithQueue(targetSong, queue)
                                        },
                                        onDownloadSong = handleDownloadSong,
                                        onDownloadSongWithOptions = handleDownloadWithOptions,
                                        initialOnlineSource = currentOnlineSource,
                                        onOnlineSourceChanged = { newSrc ->
                                            currentOnlineSource = newSrc
                                            onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                        },
                                        onOnlineSearch = onlineSearchCallback,
                                        onParseExternalPlaylist = parsePlaylistCallback,
                                        isServerConnected = (activeServer != null),
                                        contentPadding = innerPadding,
                                        onDismiss = { navigateToScreen(Screen.HOME) }
                                    )
                                }

                                Screen.HOME -> {
                                    val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }

                                    if (activeServer != null) {
                                        LemonDiscoverHomeScreen(
                                            serverName = activeServerName,
                                            configuredServers = serversList,
                                            currentSource = currentOnlineSource,
                                            onSourceChange = { newSrc ->
                                                currentOnlineSource = newSrc
                                                onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                            },
                                                                allCachedSongs = songList,
                                            activeDownloadTasks = activeDownloadTasksProvider,
                                            activeDownloadCount = activeDownloadCount,
                                            currentPlayingSong = currentSong,
                                            isPlaying = isPlaying,
                                            onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                            onDownloadSong = handleDownloadSong,
                                            onDownloadSongWithOptions = handleDownloadWithOptions,
                                            onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                            onSelectLocalServer = {
                                                activeServerName = "本地 · 已下载"
                                            },
                                            onSelectServer = { selectedServer ->
                                                activeServerName = selectedServer.name
                                                activeServerId = selectedServer.id
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    database.serverDao().setActiveServer(selectedServer.id)
                                                    syncServerPlaylists(selectedServer)
                                                    syncServerSongs(selectedServer)
                                                }
                                            },
                                            onSyncNow = {
                                                Toast.makeText(this@MainActivity, "正在从柠檬音乐同步全量曲库...", Toast.LENGTH_SHORT).show()
                                                syncServerSongs(activeServer)
                                            },
                                            onOpenDownloads = { navigateToScreen(Screen.DOWNLOADS) },
                                            onGoToSettings = { navigateToScreen(Screen.SETTINGS) },
                                            onSearchClick = { navigateToScreen(Screen.SEARCH) },
                                            onFetchDiscoverPlaylists = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverRecommendPlaylists(source = src.key, page = 1, limit = 60).getOrNull() ?: emptyList()
                                            },
                                            onFetchDiscoverToplists = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverToplists(source = src.key).getOrNull() ?: emptyList()
                                            },
                                            onFetchDiscoverNewSongs = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverNewSongs(source = src.key, limit = 60).getOrNull() ?: emptyList()
                                            },
                                            onFetchDiscoverNewAlbums = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverNewAlbums(source = src.key).getOrNull() ?: emptyList()
                                            },
                                            onParseExternalPlaylist = { url, src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.parseExternalPlaylist(urlOrId = url, source = src.key).getOrNull() ?: emptyList()
                                            },
                                            onFetchCollectionSongs = { collectionId, src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                if (collectionId.startsWith("lemon_toplist_")) {
                                                    val rawId = collectionId.substringAfterLast('_')
                                                    proto.getDiscoverToplistSongs(toplistId = rawId, source = src.key).getOrNull() ?: emptyList()
                                                } else {
                                                    proto.getPlaylistSongs(collectionId).getOrNull() ?: emptyList()
                                                }
                                            },
                                            onSubViewActiveChange = { isChildSubViewActive = it },
                                            contentPadding = innerPadding
                                        )
                                    } else {
                                        LocalMusicHomeScreen(
                                            serverName = activeServerName,
                                            recentSongs = songList,
                                            configuredServers = serversList,
                                                                recentlyAddedSongs = recentlyAddedSongs,
                                            recentlyPlayedSongs = recentlyPlayedSongs,
                                            discoverPlaylists = playlistsList.filter { it.isDiscover || it.id.startsWith("discover_") },
                                            homeDisplayConfig = homeDisplayConfig,
                                            activeDownloadTasks = activeDownloadTasksProvider,
                                            activeDownloadCount = activeDownloadCount,
                                            currentPlayingSong = currentSong,
                                            isPlaying = isPlaying,
                                            onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                            onDownloadSong = handleDownloadSong,
                                            onDownloadSongWithOptions = handleDownloadWithOptions,
                                            onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                            onSelectLocalServer = {
                                                activeServerName = "本地模式"
                                            },
                                            onSelectServer = { selectedServer ->
                                                activeServerName = selectedServer.name
                                                activeServerId = selectedServer.id
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    database.serverDao().setActiveServer(selectedServer.id)
                                                    syncServerSongs(selectedServer)
                                                }
                                            },
                                            onSyncNow = {
                                                val active = serversList.firstOrNull { it.isCurrentActive }
                                                if (active != null) {
                                                    Toast.makeText(this@MainActivity, "正在从柠檬音乐同步曲库...", Toast.LENGTH_SHORT).show()
                                                    syncServerSongs(active)
                                                } else {
                                                    Toast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onOpenDownloads = {
                                                navigateToScreen(Screen.DOWNLOADS)
                                            },
                                            onGoToSettings = {
                                                navigateToScreen(Screen.SETTINGS)
                                            },
                                            onPlaylistClick = {
                                                navigateToScreen(Screen.LIBRARY)
                                            },
                                            onScanLocalMedia = {
                                                navigateToScreen(Screen.SETTINGS)
                                            },
                                            onSubViewActiveChange = { isChildSubViewActive = it },
                                            contentPadding = innerPadding
                                        )
                                    }
                                }

                                Screen.MINE, Screen.LIBRARY -> {
                                    val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                                    val activeConfig = if (isLocalMode) null else (serversList.firstOrNull { it.isCurrentActive } ?: serversList.firstOrNull())
                                    val librarySongs = remember(songList, completedDownloadedSongs, activeConfig, isLocalMode) {
                                        if (isLocalMode || activeConfig == null) {
                                            if (completedDownloadedSongs.isNotEmpty()) {
                                                completedDownloadedSongs
                                            } else {
                                                songList.filter {
                                                    it.downloadStatus == DownloadStatus.DOWNLOADED ||
                                                    it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                                                    !it.localFilePath.isNullOrBlank()
                                                }
                                            }
                                        } else {
                                            // 在线模式：展示属于当前服务器的真实曲目及所有已收藏曲目
                                            songList.filter {
                                                it.serverId == activeConfig.id ||
                                                it.serverId == activeConfig.serverUrl ||
                                                it.isFavorite ||
                                                (activeConfig.type == ServerType.LEMON_MUSIC && (it.serverId == "lemon_music" || it.serverId == activeConfig.id))
                                            }
                                        }
                                    }
                                    LocalLibraryScreen(
                                        allSongs = librarySongs,
                                        downloadedSongs = completedDownloadedSongs,
                                        recentlyPlayedSongs = recentlyPlayedSongs,
                                        playlists = if (isLocalMode) {
                                            playlistsWithCovers.filter { !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") }
                                        } else {
                                            playlistsWithCovers.filter {
                                                !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") &&
                                                (it.serverId == activeConfig?.id || it.serverId == "lemon_music" || it.serverId.startsWith("lemon_") || it.serverId == activeConfig?.serverUrl || !it.isOnline)
                                            }
                                        },
                                        activeServerConfig = activeConfig,
                                        activeDownloadTasks = activeDownloadTasksProvider,
                                        activeDownloadCount = activeDownloadCount,
                                        currentServerName = activeServerName,
                                        configuredServers = serversList,
                                                        currentPlayingSong = currentSong,
                                        isPlaying = isPlaying,
                                        onSelectLocalServer = {
                                            activeServerName = "本地 · 已下载"
                                        },
                                        onSelectServer = { selectedServer ->
                                            activeServerName = selectedServer.name
                                            activeServerId = selectedServer.id
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().setActiveServer(selectedServer.id)
                                                syncServerPlaylists(selectedServer)
                                                syncServerSongs(selectedServer)
                                            }
                                        },
                                        onSyncNow = {
                                            val active = serversList.firstOrNull { it.isCurrentActive } ?: serversList.firstOrNull()
                                            if (active != null) {
                                                Toast.makeText(this@MainActivity, "正在从柠檬音乐同步曲库...", Toast.LENGTH_SHORT).show()
                                                syncServerPlaylists(active)
                                                syncServerSongs(active)
                                            } else {
                                                Toast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onGoToSettings = { navigateToScreen(Screen.SETTINGS) },
                                        onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                        onDownloadSong = handleDownloadSong,
                                        onDownloadSongWithOptions = handleDownloadWithOptions,
                                        onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
                                        },
                                        onToggleFavorite = { songToToggle ->
                                            handleToggleFavorite(songToToggle)
                                        },
                                        onOpenDownloads = { navigateToScreen(Screen.DOWNLOADS) },
                                        onRefreshPlaylists = {
                                            if (activeConfig != null) {
                                                Toast.makeText(this@MainActivity, "正在同步在线播放列表...", Toast.LENGTH_SHORT).show()
                                                syncServerPlaylists(activeConfig)
                                            } else {
                                                Toast.makeText(this@MainActivity, "当前处于本地模式，暂无在线歌单", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onCreatePlaylist = { name, isOnline ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                var finalId = "pl_${System.currentTimeMillis()}"
                                                var finalIsOnline = isOnline && activeConfig != null
                                                if (finalIsOnline && activeConfig != null) {
                                                    try {
                                                        val protocol = LemonMusicProtocol(
                                                            NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                            activeConfig.serverUrl,
                                                            activeConfig.username,
                                                            activeConfig.tokenOrApiKey
                                                        )
                                                        val res = protocol.createCustomPlaylist(name)
                                                        if (res.isSuccess) {
                                                            val pl = res.getOrNull()
                                                            if (pl != null) {
                                                                finalId = pl.id
                                                                finalIsOnline = true
                                                            }
                                                        } else {
                                                            finalIsOnline = false
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.w("MainActivity", "Create server playlist failed", e)
                                                        finalIsOnline = false
                                                    }
                                                }
                                                database.playlistDao().insertPlaylist(
                                                    com.lm.player.core.database.entity.PlaylistEntity(
                                                        id = finalId,
                                                        name = name,
                                                        serverId = if (finalIsOnline && activeConfig != null) activeConfig.id else "local_storage",
                                                        isOnline = finalIsOnline,
                                                        songCount = 0
                                                    )
                                                )
                                                if (finalIsOnline && activeConfig != null) {
                                                    syncServerPlaylists(activeConfig)
                                                }
                                            }
                                        },
                                        onDeletePlaylist = { playlistId ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                if (activeConfig != null) {
                                                    try {
                                                        val protocol = LemonMusicProtocol(
                                                            NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                            activeConfig.serverUrl,
                                                            activeConfig.username,
                                                            activeConfig.tokenOrApiKey
                                                        )
                                                        protocol.deleteCustomPlaylist(playlistId)
                                                    } catch (e: Exception) {
                                                        Log.w("MainActivity", "Delete server playlist failed", e)
                                                    }
                                                }
                                                database.playlistDao().deletePlaylist(playlistId)
                                                if (activeConfig != null) {
                                                    syncServerPlaylists(activeConfig)
                                                }
                                            }
                                        },
                                        onFetchPlaylistSongs = { playlistId, isOnline ->
                                            withContext(Dispatchers.IO) {
                                                val localMapped = database.playlistDao().getSongsForPlaylist(playlistId).map { entity ->
                                                    UnifiedSong(
                                                        id = entity.id,
                                                        title = entity.title,
                                                        artist = entity.artist,
                                                        artistId = entity.artistId,
                                                        album = entity.album,
                                                        albumId = entity.albumId,
                                                        durationMs = entity.durationMs,
                                                        coverUrl = entity.coverUrl,
                                                        streamUrl = entity.streamUrl,
                                                        serverId = entity.serverId,
                                                        localFilePath = entity.localFilePath,
                                                        downloadStatus = entity.downloadStatus,
                                                        bitRate = entity.bitRate,
                                                        format = entity.format,
                                                        isFavorite = entity.isFavorite,
                                                        relativeFolderPath = entity.relativeFolderPath,
                                                        rawMetaJson = entity.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
                                                    )
                                                }
                                                val effectiveServerConfig = activeConfig
                                                    ?: serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                                    ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                                                val rawList = if (isOnline && effectiveServerConfig != null) {
                                                    val protocol = LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), effectiveServerConfig.serverUrl, effectiveServerConfig.username, effectiveServerConfig.tokenOrApiKey)
                                                    val fetched = protocol.getPlaylistSongs(playlistId).getOrNull() ?: emptyList()
                                                    val favExtras = if (playlistId == "lemon_favorites") {
                                                        if (isLocalMode) completedDownloadedSongs.filter { it.isFavorite } else songList.filter { it.isFavorite }
                                                    } else emptyList()
                                                    val merged = (fetched + localMapped + favExtras).distinctBy { it.id }
                                                    if (merged.isNotEmpty()) {
                                                        try {
                                                            database.playlistDao().getPlaylistById(playlistId)?.let { entity ->
                                                                if (entity.songCount != merged.size) {
                                                                    database.playlistDao().insertPlaylist(entity.copy(songCount = merged.size))
                                                                }
                                                            }
                                                        } catch (_: Exception) { }
                                                    }
                                                    merged
                                                } else {
                                                    val favExtras = if (playlistId == "lemon_favorites") {
                                                        if (isLocalMode) completedDownloadedSongs.filter { it.isFavorite } else songList.filter { it.isFavorite }
                                                    } else emptyList()
                                                    (localMapped + favExtras).distinctBy { it.id }
                                                }
                                                // 全局统一匹配：将歌单曲目与本地缓存/下载物理文件即时比对并挂载
                                                val resolved = SongMatchingResolver.resolveSongList(
                                                    incomingSongs = rawList,
                                                    allCachedSongs = (songList + completedDownloadedSongs).distinctBy { it.id },
                                                    activeTasks = activeDownloadTasksState.value,
                                                    downloadDir = downloadEngine.getDownloadDir()
                                                )
                                                if (isLocalMode) {
                                                    resolved.filter { s ->
                                                        s.downloadStatus == DownloadStatus.DOWNLOADED ||
                                                        (!s.localFilePath.isNullOrBlank() && (s.localFilePath.startsWith("content://") || File(s.localFilePath).exists()))
                                                    }
                                                } else {
                                                    resolved
                                                }
                                            }
                                        },
                                        onFetchServerFolders = { parentId ->
                                            withContext(Dispatchers.IO) {
                                                if (activeConfig != null) {
                                                    val protocol = LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                    protocol.getFolders(parentId).getOrNull() ?: emptyList()
                                                } else {
                                                    emptyList()
                                                }
                                            }
                                        },
                                        onFetchServerFolderSongs = { folderId ->
                                            withContext(Dispatchers.IO) {
                                                val rawFolderSongs = if (activeConfig != null) {
                                                    val protocol = LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                    protocol.getFolderSongs(folderId).getOrNull() ?: emptyList()
                                                } else {
                                                    emptyList()
                                                }
                                                // 全局统一匹配：将服务器文件夹内的曲目与本地缓存即时比对并挂载
                                                SongMatchingResolver.resolveSongList(
                                                    incomingSongs = rawFolderSongs,
                                                    allCachedSongs = songList,
                                                    activeTasks = activeDownloadTasksState.value,
                                                    downloadDir = downloadEngine.getDownloadDir()
                                                )
                                            }
                                        },
                                        onDeleteLocalFilePath = { path ->
                                            LocalMediaScanner.deleteLocalAudioFile(database, path)
                                        },
                                        onFetchServerGenres = {
                                            if (activeConfig != null) {
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                proto.getGenres().getOrNull() ?: emptyList()
                                            } else {
                                                emptyList()
                                            }
                                        },
                                        onFetchServerScanStatus = {
                                            if (activeConfig != null) {
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                proto.getServerScanStatus().getOrNull()
                                            } else {
                                                null
                                            }
                                        },
                                        onTriggerServerScan = {
                                            if (activeConfig != null) {
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                proto.triggerServerScan()
                                                syncServerSongs(activeConfig)
                                            }
                                        },
                                        onSubViewActiveChange = { isChildSubViewActive = it },
                                        contentPadding = innerPadding
                                    )
                                }

                                Screen.DOWNLOADS -> {
                                    DownloadManagerScreen(
                                        activeTasksProvider = activeDownloadTasksProvider,
                                        completedSongs = completedDownloadedSongs,
                                        onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                        onCancelTask = { downloadEngine.cancelTask(it) },
                                        onPauseTask = { downloadEngine.pauseTask(it) },
                                        onResumeTask = { downloadEngine.resumeTask(it) },
                                        onPauseTasks = { downloadEngine.pauseTasks(it) },
                                        onResumeTasks = { downloadEngine.resumeTasks(it) },
                                        onCancelTasks = { downloadEngine.cancelTasks(it) },
                                        onPauseAll = { downloadEngine.pauseAll() },
                                        onResumeAll = { downloadEngine.resumeAll() },
                                        onDeleteDownloadedSong = { songToDelete ->
                                            downloadEngine.deleteDownloadedSong(songToDelete)
                                        },
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
                                        },
                                        onReEmbedSong = { songToFix ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val ok = downloadEngine.reEmbedSongMetadata(songToFix)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, if (ok) "已成功为《${songToFix.title}》重新嵌入封面与歌词" else "重新嵌入失败，文件未找到", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onReEmbedAll = {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "正在批量为所有已下载歌曲补全封面与歌词标签...", Toast.LENGTH_SHORT).show()
                                                }
                                                val (success, fail) = downloadEngine.reEmbedAllDownloadedSongs()
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "标签补全完成：成功 $success 首，失败 $fail 首", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        downloadPath = downloadSettings.customDownloadPath.ifBlank { getExternalFilesDir(null)?.absolutePath ?: "" },
                                        onBack = { popScreenOrHome() },
                                        contentPadding = innerPadding
                                    )
                                }

                                Screen.SETTINGS -> {
                                    SettingsScreen(
                                        servers = serversList,
                                        activeServerId = activeServerId,
                                        downloadSettings = downloadSettings,
                                        onDownloadSettingsChange = { newSettings ->
                                            val oldPath = downloadSettings.customDownloadPath
                                            val pathChanged = newSettings.customDownloadPath != oldPath && newSettings.customDownloadPath.isNotBlank()
                                            downloadEngine.updateSettings(newSettings)

                                            if (pathChanged) {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "正在扫描离线目录并与线上曲库比对同步...", Toast.LENGTH_SHORT).show()
                                                    }
                                                    // 1. 递归扫描该离线目录中的所有音频文件
                                                    val scanned = LocalMediaScanner.scanCustomDirectory(this@MainActivity, newSettings.customDownloadPath, database)
                                                    // 2. 与线上曲库进行物理文件真实性对比与同步
                                                    val updated = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, java.io.File(newSettings.customDownloadPath))
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "离线目录同步完成：扫描 $scanned 首，匹配同步 $updated 首", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        },
                                        homeDisplayConfig = homeDisplayConfig,
                                        onHomeDisplayConfigChange = { newConfig ->
                                            homeDisplayConfig = newConfig
                                            uiPrefs.edit()
                                                .putBoolean("home_show_recently_played", newConfig.showRecentlyPlayed)
                                                .putBoolean("home_show_recently_added", newConfig.showRecentlyAdded)
                                                .putBoolean("home_show_albums", newConfig.showAlbums)
                                                .putBoolean("home_show_artists", newConfig.showArtists)
                                                .putBoolean("home_show_favorites", newConfig.showFavorites)
                                                .apply()
                                        },
                                        uiScalePercent = uiScalePercent,
                                        onUiScalePercentChange = { newPercent ->
                                            uiScalePercent = newPercent
                                            uiPrefs.edit().putInt("ui_scale_percent", newPercent).apply()
                                        },
                                        themeMode = currentThemeMode,
                                        onThemeModeChange = { newThemeMode ->
                                            currentThemeMode = newThemeMode
                                            uiPrefs.edit().putString("app_theme_mode", newThemeMode.name).apply()
                                        },
                                        enableBottomBarAnimation = enableBottomBarAnimation,
                                        onEnableBottomBarAnimationChange = { isEnabled ->
                                            enableBottomBarAnimation = isEnabled
                                            uiPrefs.edit().putBoolean("enable_bottom_bar_anim", isEnabled).apply()
                                        },
                                        autoPlayOnStartup = autoPlayOnStartup,
                                        onAutoPlayOnStartupChange = { isAuto ->
                                            autoPlayOnStartup = isAuto
                                            autoPlayPrefs.edit().putBoolean("auto_play_on_startup", isAuto).apply()
                                        },
                                        autoLaunchOnBoot = autoLaunchOnBoot,
                                        onAutoLaunchOnBootChange = { isEnabled ->
                                            autoLaunchOnBoot = isEnabled
                                            // 必须双存储写入：开机广播常早于用户解锁，
                                            // 只写凭据保护的 prefs 会让开机早期读回默认 false
                                            BootCompletedReceiver.setAutoLaunchOnBootEnabled(this@MainActivity, isEnabled)
                                            if (isEnabled) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "已开启开机自启动，若盒子上不生效请在系统安全中心放行本应用的自启动",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        },
                                        autoFallbackToLocal = autoFallbackToLocal,
                                        onAutoFallbackToLocalChange = { isFallback ->
                                            autoFallbackToLocal = isFallback
                                            autoPlayPrefs.edit().putBoolean("auto_fallback_to_local", isFallback).apply()
                                        },
                                        onStreamQualityChanged = {
                                            LemonMusicProtocol.notifyStreamQualityConfigChanged()
                                            val activeSong = currentSong
                                            if (
                                                activeSong != null &&
                                                activeSong.localFilePath.isNullOrBlank() &&
                                                activeSong.downloadStatus != DownloadStatus.DOWNLOADED
                                            ) {
                                                val resumePos = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
                                                Toast.makeText(this@MainActivity, "试听音质已更新，正在按新音质无缝重载当前歌曲...", Toast.LENGTH_SHORT).show()
                                                PlaybackQueueManager.playSong(
                                                    targetSong = activeSong,
                                                    context = this@MainActivity,
                                                    startPositionMs = resumePos,
                                                    forceRefresh = true
                                                )
                                            }
                                        },
                                        currentOnlineSource = currentOnlineSource,
                                        onOnlineSourceChange = { newSrc ->
                                            currentOnlineSource = newSrc
                                            onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                        },
                                        onSelectServer = { server ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().setActiveServer(server.id)
                                                syncServerSongs(server)
                                            }
                                        },
                                        onAddOrUpdateServer = { serverConfig ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().insertServer(
                                                    ServerEntity(
                                                        id = serverConfig.id,
                                                        name = serverConfig.name,
                                                        type = serverConfig.type,
                                                        serverUrl = serverConfig.serverUrl,
                                                        username = serverConfig.username,
                                                        tokenOrApiKey = serverConfig.tokenOrApiKey,
                                                        saltOrSecret = serverConfig.saltOrSecret,
                                                        syncMode = serverConfig.syncMode,
                                                        isCurrentActive = true
                                                    )
                                                )
                                                database.serverDao().setActiveServer(serverConfig.id)
                                                syncServerSongs(serverConfig)
                                            }
                                        },
                                        onDeleteServer = { serverIdToDelete ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().deleteServer(serverIdToDelete)
                                            }
                                        },
                                        onOpenDownloads = {
                                            navigateToScreen(Screen.DOWNLOADS)
                                        },
                                        onLocalScanCompleted = {
                                            // 触发歌曲刷新
                                        },
                                        onChooseDownloadDirectory = {
                                            onChooseDownloadFolderResult = { uri ->
                                                val resolvedPath = DownloadEngine.resolveFilesystemPath(uri.toString())
                                                val updated = downloadEngine.downloadSettings.value.copy(customDownloadPath = resolvedPath)
                                                downloadEngine.updateSettings(updated)
                                                Toast.makeText(this@MainActivity, "已成功设定并保存下载存储目录：$resolvedPath", Toast.LENGTH_SHORT).show()
                                            }
                                            chooseDownloadDirectoryLauncher.launch(null)
                                        },
                                        onImportCustomFolder = {
                                            onImportFolderResult = { uri ->
                                                val resolvedFolder = DownloadEngine.resolveFilesystemPath(uri.toString())
                                                if (resolvedFolder.isNotBlank()) {
                                                    val existingFolders = uiPrefs.getStringSet("local_music_scan_folders", emptySet())?.toSet() ?: emptySet()
                                                    val updatedFolders = existingFolders + resolvedFolder
                                                    uiPrefs.edit().remove("local_music_scan_folders").apply()
                                                    uiPrefs.edit().putStringSet("local_music_scan_folders", updatedFolders).apply()
                                                }
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val count = LocalMediaScanner.scanDocumentTree(this@MainActivity, uri, database)
                                                    val dlDir = downloadEngine.getDownloadDir()
                                                    val matched = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, dlDir)
                                                    LocalMediaScanner.matchAndMergeLocalWithServer(database)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "扫描完成！成功导入 $count 首本地歌曲，比对匹配 $matched 首服务器歌曲已标为本地已下载", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                            importFolderLauncher.launch(null)
                                        },
                                        onExitAppCompletely = {
                                            exitAppCompletely()
                                        },
                                        contentPadding = innerPadding
                                    )
                                }
                            }
                        }
                        }

                        // 全屏全局沉浸式搜索面板 (内嵌于脚手架内容层中，点击左侧导航栏任意其他页面自动退出)
                        if (isSearchDialogOpen) {
                            val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                            val onlineSearchCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeServer) {
                                val srv = activeServer
                                if (srv != null) {
                                    { keyword, source ->
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        protocol.searchOnline(keyword, source = source.key).getOrNull() ?: emptyList()
                                    }
                                } else null
                            }
                            val parsePlaylistCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeServer) {
                                val srv = activeServer
                                if (srv != null) {
                                    { url, source ->
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        protocol.parseExternalPlaylist(urlOrId = url, source = source.key).getOrNull() ?: emptyList()
                                    }
                                } else null
                            }
                            LibrarySearchDialog(
                                allSongs = songList,
                                activeDownloadTasks = activeDownloadTasksProvider,
                                currentPlayingSong = currentSong,
                                isPlaying = isPlaying,
                                onSongClick = { targetSong, queue ->
                                    playSongWithQueue(targetSong, queue)
                                },
                                onDownloadSong = handleDownloadSong,
                                onDownloadSongWithOptions = handleDownloadWithOptions,
                                initialOnlineSource = currentOnlineSource,
                                onOnlineSourceChanged = { newSrc ->
                                    currentOnlineSource = newSrc
                                    onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                },
                                onOnlineSearch = onlineSearchCallback,
                                onParseExternalPlaylist = parsePlaylistCallback,
                                isServerConnected = (activeServer != null),
                                contentPadding = innerPadding,
                                onDismiss = { isSearchDialogOpen = false }
                            )
                        }
                        }
                    }
                        }

                    // 全屏现代高保真音乐播放器弹窗 (支持横屏分屏歌词与多主题联动)
                    // 「全屏封面」主题采用自底部滑入/滑出的转场，其余主题保持淡入缩放
                    val useSlideTransition = playerThemeStyle ==
                        com.lm.player.core.designsystem.theme.PlayerThemeStyle.NETEASE_TV_COVER
                    AnimatedVisibility(
                        visible = isFullPlayerVisible && currentSong != null,
                        enter = if (useSlideTransition) {
                            slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = androidx.compose.animation.core.tween(320)
                            )
                        } else {
                            fadeIn(animationSpec = androidx.compose.animation.core.tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = androidx.compose.animation.core.tween(220))
                        },
                        exit = if (useSlideTransition) {
                            slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = androidx.compose.animation.core.tween(260)
                            )
                        } else {
                            fadeOut(animationSpec = androidx.compose.animation.core.tween(180)) + scaleOut(targetScale = 0.96f, animationSpec = androidx.compose.animation.core.tween(180))
                        }
                    ) {
                        val activeSong = currentSong
                        if (activeSong != null) {
                            val song = activeSong
                            var localProgressMs by remember { mutableStateOf(exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L) }
                            var localTotalDurationMs by remember { mutableStateOf(exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L) }
                            var prebufferedSongId by remember { mutableStateOf("") }

                            // 进度轮询不再以 isPlaying 为条件：暂停时也需要跟随 seek 与真实播放位置刷新，
                            // 否则拖动进度条后界面会停留在旧位置，表现为「进度条无动作」。
                            // 但退到后台时停止轮询，且值未变化时不写状态 (暂停时因此完全不产生重组)。
                            LaunchedEffect(song.id) {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    while (isActive) {
                                        val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                        if (pos != localProgressMs) localProgressMs = pos
                                        val dur = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                                        if (dur > 0L) {
                                            if (dur != localTotalDurationMs) localTotalDurationMs = dur
                                            // 智能切歌预热：播放进度达到 85% 且本曲尚未预热时，后台异步预热下一首歌曲解析与歌词
                                            if (pos.toFloat() / dur > 0.85f && prebufferedSongId != song.id && songList.isNotEmpty()) {
                                                prebufferedSongId = song.id
                                                val currentQueueList = currentQueue.ifEmpty { songList }
                                                launch(Dispatchers.Default) {
                                                    val nextIndex = (currentQueueList.indexOfFirst { it.id == song.id } + 1).takeIf { it < currentQueueList.size } ?: 0
                                                    val nextCandidate = if (isShuffle) (currentQueueList.filter { it.id != song.id }.randomOrNull() ?: song) else currentQueueList[nextIndex]
                                                    try {
                                                        val activeServer = serversList.firstOrNull { it.isCurrentActive }
                                                        LyricsManager.loadLyrics(nextCandidate, this@MainActivity, activeServer)
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                        delay(if (isPlaying) 400L else 800L)
                                    }
                                }
                            }

                            var playbackSpeed by remember {
                                mutableStateOf(uiPrefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f))
                            }

                            FullscreenPlayerSheet(
                                song = song,
                                playlist = currentQueue.ifEmpty { songList },
                                isPlaying = isPlaying,
                                // 同样以取数函数传入：值在这里被读取会让整个 Activity 组合树
                                // 每 0.4~0.8 秒重组一次 (含底层脚手架)，即使全屏播放页就在最上层
                                progressMsProvider = { localProgressMs },
                                totalDurationMs = localTotalDurationMs,
                                lyrics = currentLyrics,
                                isLyricsMode = isLyricsMode,
                                isShuffle = isShuffle,
                                isRepeat = isRepeat,
                                playbackSpeed = playbackSpeed,
                                allPlaylists = playlistsList,
                                isServerConnected = (serversList.any { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }),
                                onTogglePlayPause = togglePlayPause,
                                onNext = playNext,
                                onPrevious = playPrevious,
                                onSeekTo = { seekPosition ->
                                    exoPlayer?.seekTo(seekPosition)
                                    localProgressMs = seekPosition
                                },
                                onSelectSongFromQueue = { queueSong ->
                                    playSongWithQueue(queueSong, currentQueue.ifEmpty { songList })
                                },
                                onToggleLyricsMode = { isLyricsMode = it },
                                onToggleFavorite = {
                                    handleToggleFavorite(song)
                                },
                                onToggleShuffle = { PlaybackQueueManager.setShuffle(!isShuffle) },
                                onToggleRepeat = { PlaybackQueueManager.setRepeat(!isRepeat) },
                                onChangePlaybackSpeed = { speed ->
                                    playbackSpeed = speed
                                    uiPrefs.edit().putFloat("playback_speed", speed).apply()
                                    exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
                                },
                                onDownloadSong = handleDownloadSong,
                                onDownloadSongWithOptions = handleDownloadWithOptions,
                                onAddToPlaylist = handleAddToPlaylist,
                                onCreatePlaylistAndAddSong = handleCreatePlaylistAndAddSong,
                                onPlayerThemeStyleChange = { playerThemeStyle = it },
                                onDismiss = { isFullPlayerVisible = false }
                            )
                        }
                    }

                    // 启动 Logo 过渡动画图层 (开屏丝滑淡出)
                    SplashScreenView(
                        visible = isSplashVisible,
                        onSplashFinished = { isSplashVisible = false }
                    )

                    // 崩溃自诊断：上次运行闪退过则原样展示堆栈，用户截图即可回传（展示一次后不再打扰）
                    var lastCrashReport by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        // 读崩溃日志是磁盘 I/O，放到 IO 线程避免首帧期间阻塞 UI
                        lastCrashReport = withContext(Dispatchers.IO) {
                            CrashLogger.pendingReport(this@MainActivity)
                        }
                    }
                    val crashReportText = lastCrashReport
                    if (crashReportText != null) {
                        CrashReportDialog(
                            report = crashReportText,
                            onDismiss = {
                                CrashLogger.markReportShown(this@MainActivity)
                                lastCrashReport = null
                            }
                        )
                    }

                    // 启动后 10 秒检测到新版本的全屏弹窗 (支持滑动说明与 3 选交互)
                    if (showStartupUpdateDialog && startupUpdateInfo != null) {
                        val info = startupUpdateInfo!!
                        AppUpdateDialog(
                            updateInfo = info,
                            isDownloading = isDownloadingStartupApk,
                            downloadProgress = downloadStartupProgress,
                            isDownloaded = isStartupApkReady,
                            onDismiss = { showStartupUpdateDialog = false },
                            onNeverUpdate = {
                                AppUpdateManager.setSkipVersion(this@MainActivity, info.latestVersion)
                                Toast.makeText(this@MainActivity, "已记录，不再提示 v${info.latestVersion} 更新", Toast.LENGTH_SHORT).show()
                                showStartupUpdateDialog = false
                            },
                            onStartDownload = {
                                if (info.downloadUrl.isNotBlank() && !isDownloadingStartupApk) {
                                    isDownloadingStartupApk = true
                                    downloadStartupProgress = 0f
                                    lifecycleScope.launch {
                                        AppUpdateManager.downloadApk(
                                            context = this@MainActivity,
                                            downloadUrl = info.downloadUrl,
                                            onProgress = { progress, _, _ -> downloadStartupProgress = progress }
                                        ).onSuccess { apkFile ->
                                            isDownloadingStartupApk = false
                                            downloadedStartupApkFile = apkFile
                                            isStartupApkReady = apkFile.exists() && apkFile.length() > 0L
                                            Toast.makeText(this@MainActivity, "安装包下载完成，正在调起安装...", Toast.LENGTH_SHORT).show()
                                            AppUpdateManager.installApk(this@MainActivity, apkFile)
                                        }.onFailure { error ->
                                            isDownloadingStartupApk = false
                                            Toast.makeText(this@MainActivity, "下载更新失败: ${error.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onInstall = {
                                downloadedStartupApkFile?.let { apkFile ->
                                    AppUpdateManager.installApk(this@MainActivity, apkFile)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

    /**
     * 彻底关闭程序与播放服务
     */
    private fun exitAppCompletely() {
        try {
            val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
            PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            PlaybackService.stopServiceAndPlayback(this)
            finishAffinity()
            finishAndRemoveTask()
        } catch (e: Exception) {
            finish()
        }
    }

    private fun registerMediaCommandReceiver() {
        try {
            mediaCommandReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val cmd = intent?.getStringExtra(PlaybackService.EXTRA_COMMAND)
                    when (cmd) {
                        PlaybackService.CMD_NEXT -> playNextAction?.invoke()
                        PlaybackService.CMD_PREV -> playPreviousAction?.invoke()
                        PlaybackService.CMD_TOGGLE -> togglePlayAction?.invoke()
                        PlaybackService.CMD_PLAY -> if (exoPlayer?.isPlaying != true) togglePlayAction?.invoke()
                        PlaybackService.CMD_PAUSE -> if (exoPlayer?.isPlaying == true) togglePlayAction?.invoke()
                    }
                }
            }
            val filter = IntentFilter(PlaybackService.ACTION_MEDIA_COMMAND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mediaCommandReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(mediaCommandReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to register mediaCommandReceiver", e)
        }
    }

    private var lastActivityKeyTimestamp = 0L

    // 针对电视遥控器媒体键、车载中控硬件方向盘按键与蓝牙多功能键的硬件按键分发 (严禁拦截方向键与返回键)
    private fun isMediaOrVolumeKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> true
            else -> false
        }
    }

    private fun handleMediaKeyEvent(keyCode: Int): Boolean {
        if (!isMediaOrVolumeKey(keyCode)) return false
        val now = System.currentTimeMillis()
        if (now - lastActivityKeyTimestamp < 250) return true
        lastActivityKeyTimestamp = now
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                PlaybackQueueManager.playNext(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP -> {
                PlaybackQueueManager.playPrevious(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_BUTTON_START -> {
                PlaybackQueueManager.togglePlay(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                val p = Media3Factory.getSharedExoPlayer(this@MainActivity)
                if (!p.isPlaying) PlaybackQueueManager.togglePlay(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                val p = Media3Factory.getSharedExoPlayer(this@MainActivity)
                if (p.isPlaying) PlaybackQueueManager.togglePlay(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                return true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
    }

    /**
     * 把当前进度同步落盘，但不在 UI 线程上做 fsync。
     * 电视盒子 eMMC 较慢，主线程 commit() 会直接造成切页/退后台卡顿甚至 ANR。
     */
    private fun persistPlaybackStateSync() {
        val pos = exoPlayer?.currentPosition?.takeIf { it > 0L }
        val ctx = applicationContext
        persistScope.launch {
            PlaybackQueueManager.savePlaybackState(ctx, positionMs = pos, commitSync = true)
        }
    }

    override fun onPause() {
        persistPlaybackStateSync()
        super.onPause()
    }

    override fun onStop() {
        persistPlaybackStateSync()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (handleMediaKeyEvent(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startPlaybackService() {
        try {
            val serviceIntent = Intent(this, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun requestAppPermissions() {
        try {
            val permissions = mutableListOf<String>()

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }

            if (permissions.isNotEmpty()) {
                permissionLauncher.launch(permissions.toTypedArray())
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        // 必须先摘掉换绑回调：Media3Factory 的监听列表是进程级静态的，
        // 不移除会让旧 Activity 一直被持有
        try { Media3Factory.removePlayerSwapListener(playerSwapListener) } catch (_: Exception) {}
        mediaCommandReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        if (isFinishing) {
            try {
                persistPlaybackStateSync()
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                PlaybackService.stopServiceAndPlayback(this)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}

/**
 * 下载直链解析器（单首下载与多选批量下载**共用同一份实现**）。
 *
 * 把播放链路 PlaybackRouter 的音质决策原样搬到下载路径上，这是"选 320K 却下到无损"的根治点：
 * 服务器曲库歌曲的 streamUrl 形如 `/api/play/local?path=…&token=…`，**URL 里没有音质参数**，
 * 以前被当作直链歌曲直接下载，拉到的永远是该曲目的**服务器原文件**（可能是无损）。
 * 现在统一按目标音质解析：服务器原文件音质匹配就用原文件，否则先向音源取对应音质
 * （音源内部自带 flac24bit→flac→320k→128k 逐级降级与同名搜索回退），
 * 仍未命中再退回服务器本地流并带上 quality 参数让服务端给对应音质。
 */
private suspend fun resolveDownloadUrlFor(
    context: Context,
    server: ServerConfig,
    song: UnifiedSong,
    quality: AudioQuality
): String? {
    return try {
        val protocol = LemonMusicProtocol(
            NetworkClientFactory.createOkHttpClient(context),
            server.serverUrl,
            server.username,
            server.tokenOrApiKey
        )
        DownloadRequestPlanner.resolveStreamAtQuality(protocol, song, quality)?.url
    } catch (e: Exception) {
        Log.w("MainActivity", "Resolve download stream failed for ${song.title}", e)
        null
    }
}
