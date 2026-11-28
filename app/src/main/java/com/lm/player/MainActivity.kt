package com.lm.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import java.io.File
import org.json.JSONObject
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
import androidx.compose.ui.draw.drawWithContent
import com.lm.player.core.designsystem.component.collectAsStateLifecycleAware
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
import androidx.room.invalidationTrackerFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.component.CrashReportDialog
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.model.PLATFORM_MODE_PREF_KEY
import com.lm.player.core.model.PlatformMode
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
import com.lm.player.core.startup.StableClock
import com.lm.player.core.util.StoragePermissionHelper
import com.lm.player.feature.settings.StoragePermissionDialog
import com.lm.player.feature.settings.StorageDirSourceChooserDialog
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
import com.lm.player.feature.search.LibrarySearchDialog
import com.lm.player.feature.settings.AppUpdateDialog
import com.lm.player.feature.settings.LocalFolderPickerDialog
import com.lm.player.feature.settings.SettingsScreen
import com.lm.player.ui.AdaptiveAppScaffold
import com.lm.player.ui.PlatformModeWizard
import com.lm.player.ui.SplashScreenView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

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

    // 播放进度落盘专用作用域：不绑定 Activity 生命周期，避免 onDestroy 时任务被取消而丢掉最后进度。
    // 任务只捕获 applicationContext 与数值，不持有 Activity 引用。
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 收藏切换去抖表：同一首歌 600ms 内只处理一次，避免并发写 Room 与远端整表同步互相覆盖 */
    private val favoriteToggleGuard = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 启动提速：续播快速通道的结果（true = 已直接按保存的信息续播，组合里的曲库匹配续播据此跳过） */
    private val fastResumeResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

    /** 启动提速：首帧已绘制（用于决定何时放行启动闸门） */
    private val firstFrameDrawn = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * 运行平台模式的 Activity 级可观察状态（null = 尚未选择）。
     *
     * 放在 Activity 而不是 composable 内，是为了让 [dispatchKeyEvent] 这个**组合之外**的
     * 逃生通道能在长按返回键时把模式切回电视，并即时驱动 Compose 侧重组
     * （Compose 读同一个 MutableState 实例，无需额外事件总线）。
     */
    private var platformModeState: MutableState<PlatformMode?> = mutableStateOf(null)

    /** 车机模式下长按返回键切回电视模式的按键计时（-1 = 当前没有按住返回键） */
    private var carEscapeBackDownAt = -1L

    // 运行时权限申请器 (兼容 Android 6.0 ~ Android 14+)
    // 结果必须被处理：此前回调是空的（{ _ -> }），用户拒绝后没有任何说明，
    // 表现为「本地音乐扫不到、还没有任何提示」—— 权限问题因此长期无人察觉。
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) return@registerForActivityResult
        val readable = denied.joinToString("、") { permission ->
            when {
                permission.endsWith("READ_MEDIA_AUDIO") -> "读取本地音乐"
                permission.endsWith("READ_EXTERNAL_STORAGE") -> "读取存储（U盘/移动硬盘扫描）"
                permission.endsWith("WRITE_EXTERNAL_STORAGE") -> "写入存储（自定义下载目录）"
                permission.endsWith("POST_NOTIFICATIONS") -> "发送通知"
                else -> permission.substringAfterLast('.')
            }
        }
        val permanentlyDenied = denied.any { permission ->
            !shouldShowRequestPermissionRationale(permission)
        }
        val tip = buildString {
            append("以下权限被拒绝：")
            append(readable)
            append("。")
            if (denied.any { it.endsWith("READ_MEDIA_AUDIO") || it.endsWith("READ_EXTERNAL_STORAGE") }) {
                append("本地/U盘音乐扫描将无法工作，可在系统设置中开启对应权限后重试。")
            }
            if (permanentlyDenied) append("（系统已不再弹窗，需前往系统设置手动开启）")
        }
        com.lm.player.core.designsystem.component.AppToast.makeText(this, tip, android.widget.Toast.LENGTH_LONG).show()
    }

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

    /**
     * 存储权限引导弹窗内容 (null = 不显示)。放在 Activity 级，便于组合外的权限回调直接驱动界面。
     * [StoragePermissionPrompt.onContinue] 非空时显示「仍然继续」，让用户在没有权限时也能改走系统选择器等路径。
     */
    private data class StoragePermissionPrompt(
        val message: String,
        val onContinue: (() -> Unit)?
    )

    private val storagePermissionPrompt: MutableState<StoragePermissionPrompt?> = mutableStateOf(null)

    /** 权限申请结束后要继续执行的动作 (更换下载目录 / 添加扫描目录 / 开始扫描) */
    private var pendingStorageAction: (() -> Unit)? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingStorageAction
        pendingStorageAction = null
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            action?.invoke()
            return@registerForActivityResult
        }
        // 申请后 shouldShowRequestPermissionRationale 仍为 false = 「不再询问」或被车机系统直接拦截
        val permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
        val labels = denied.joinToString("、") { StoragePermissionHelper.permissionLabel(it) }
        val message = buildString {
            append("未授予「$labels」权限。")
            if (denied.any { it.endsWith("WRITE_EXTERNAL_STORAGE") }) {
                append("没有写入权限时，只能把下载目录设为应用专属目录，或通过系统文件夹选择器授权。")
            }
            if (denied.any { it.endsWith("READ_EXTERNAL_STORAGE") || it.endsWith("READ_MEDIA_AUDIO") }) {
                append("没有读取权限时，本地 / U 盘扫描会找不到歌曲。")
            }
            if (permanentlyDenied) {
                append("\n\n系统已不再弹出申请窗口，请到 系统设置 → 应用 → LMPlayer → 权限 中开启「存储」。")
            }
        }
        storagePermissionPrompt.value = StoragePermissionPrompt(message, action)
    }

    /**
     * 存储相关操作前统一检查权限：已授予直接执行；缺失则先申请，申请结果回来后再继续。
     * 被拒绝时弹出说明 (带「打开应用权限设置」与「仍然继续」)，不再静默失败。
     */
    private fun ensureStoragePermission(needWrite: Boolean, action: () -> Unit) {
        val wanted = LinkedHashSet<String>()
        wanted.addAll(StoragePermissionHelper.readPermissions())
        if (needWrite) wanted.addAll(StoragePermissionHelper.writePermissions())
        val missing = StoragePermissionHelper.missing(this, wanted.toList())
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingStorageAction = action
        try {
            storagePermissionLauncher.launch(missing.toTypedArray())
        } catch (_: Exception) {
            pendingStorageAction = null
            storagePermissionPrompt.value = StoragePermissionPrompt(
                "无法弹出权限申请窗口。请到 系统设置 → 应用 → LMPlayer → 权限 中开启「存储」后重试。",
                action
            )
        }
    }

    private fun openAppPermissionSettings() {
        val intent = StoragePermissionHelper.appDetailsIntent(this)
        if (StoragePermissionHelper.canOpenAppDetails(this)) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        com.lm.player.core.designsystem.component.AppToast.makeText(
            this,
            "本机没有应用权限设置页面。请在车机 系统设置 → 应用管理 → LMPlayer → 权限 中开启存储权限",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Android 11+ 未获「所有文件访问」时打开授权页 (仅在页面存在时)；返回是否已打开 */
    private fun openAllFilesAccessSettingsIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || StoragePermissionHelper.hasAllFilesAccess()) return false
        val intent = StoragePermissionHelper.allFilesAccessIntent(this) ?: return false
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 启动页面（设置 → 播放与外观 → 启动页面）：默认「我的」，只读本地资料库，启动最轻 */
    private fun resolveStartScreen(): Screen = when (com.lm.player.core.designsystem.theme.AppUiPrefs.startPage) {
        com.lm.player.core.designsystem.theme.StartPage.MINE -> Screen.MINE
        com.lm.player.core.designsystem.theme.StartPage.HOME -> Screen.HOME
        com.lm.player.core.designsystem.theme.StartPage.LAST ->
            com.lm.player.core.designsystem.theme.AppUiPrefs.lastPage(this)
                ?.let { name -> runCatching { Screen.valueOf(name) }.getOrNull() }
                ?.takeIf { it == Screen.MINE || it == Screen.HOME || it == Screen.PODCASTS || it == Screen.SETTINGS }
                ?: Screen.MINE
    }

    /**
     * 启动提速：续播快速通道。
     *
     * 上次播放的歌曲、播放队列与进度本来就单独保存在配置里（PlaybackQueueManager.savePlaybackState），
     * 歌曲是本地文件且文件仍在时，无需等整个曲库从数据库读完、映射、匹配之后再续播，
     * 这里直接按保存的信息续播，进程一起来就出声。
     * 不满足条件（不是本地文件 / 文件已不在 / 关闭了启动续播 / 首次运行正在选平台模式 / Activity 重建）时
     * 结果记为 false，交回组合里原有的「曲库加载后匹配续播」流程，行为与以前一致。
     */
    private fun startFastResume(savedInstanceState: Bundle?) {
        if (fastResumeResult.isCompleted) return
        val player = exoPlayer
        if (player != null && player.currentMediaItem != null && player.playWhenReady) {
            // 小组件 / 开机自启已经在播：视为已续播，不再打断
            fastResumeResult.complete(true)
            return
        }
        val autoPlay = getSharedPreferences("zds_auto_play_prefs", Context.MODE_PRIVATE)
            .getBoolean("auto_play_on_startup", true)
        if (savedInstanceState != null || !autoPlay || platformModeState.value == null) {
            fastResumeResult.complete(false)
            return
        }
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = PlaybackQueueManager.getSavedLastSong(this@MainActivity) ?: return@runCatching null
                    if (com.lm.player.core.podcast.PodcastSongs.isPodcast(saved)) return@runCatching null
                    val path = saved.localFilePath?.trim().orEmpty()
                    val fileOk = path.isNotEmpty() &&
                        (path.startsWith("content://") || File(path).let { it.isFile && it.length() > 0L })
                    if (!fileOk) return@runCatching null
                    val savedPos = PlaybackQueueManager.getSavedPositionMs(this@MainActivity)
                    // 与原续播逻辑一致：上次已播到结尾 2 秒内则从头播
                    val resumePos = if (saved.durationMs > 0L && savedPos >= saved.durationMs - 2000L) 0L
                    else savedPos.coerceAtLeast(0L)
                    val song = saved.copy(
                        localFilePath = path,
                        streamUrl = path,
                        downloadStatus = DownloadStatus.DOWNLOADED
                    )
                    val savedQueue = PlaybackQueueManager.getSavedQueue(this@MainActivity)
                        .map { if (it.id == song.id) song else it }
                    val queue = if (savedQueue.none { it.id == song.id }) listOf(song) + savedQueue else savedQueue
                    Triple(song, queue, resumePos)
                }.getOrNull()
            }
            if (prepared == null) {
                fastResumeResult.complete(false)
                return@launch
            }
            val (song, queue, resumePos) = prepared
            PlaybackQueueManager.playSong(
                targetSong = song,
                context = this@MainActivity,
                newPlaylist = queue,
                startPositionMs = resumePos
            )
            fastResumeResult.complete(true)
        }
    }

    /**
     * 启动提速：首帧已绘制、续播已出声（或判定无需续播）后再静置 1.2 秒，放行启动闸门，
     * 之后才开始音源引擎、服务器登录与同步、遗留数据清理等后台工作（见 StartupGate）。
     */
    private fun scheduleStartupGateRelease(savedInstanceState: Bundle?) {
        if (com.lm.player.core.startup.StartupGate.isReleased) return
        lifecycleScope.launch {
            launch {
                PlaybackQueueManager.isPlayingFlow.first { it }
                com.lm.player.core.startup.StartupTrace.mark(com.lm.player.core.startup.StartupTrace.PLAYBACK_STARTED)
            }
            firstFrameDrawn.await()
            if (fastResumeResult.await()) {
                kotlinx.coroutines.withTimeoutOrNull(4_000L) { PlaybackQueueManager.isPlayingFlow.first { it } }
            }
            delay(1_200L)
            com.lm.player.core.startup.StartupGate.release(
                if (savedInstanceState == null) "first_frame_and_resume" else "activity_recreated"
            )
        }
    }

    @kotlin.OptIn(ExperimentalMaterial3WindowSizeClassApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.lm.player.core.startup.StartupTrace.mark(com.lm.player.core.startup.StartupTrace.ACTIVITY_CREATE)

        // 系统底层接管：将实体按键音量控制通道绑定为媒体音量 (解决应用内音量键无效问题)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        // 热启动兜底：进程未被回收时本地音源会话可能已停止，这里补一次缓存恢复（幂等）。
        // 启动提速：等启动闸门放行（首帧 + 续播完成）后再恢复，不和首帧、续播抢 CPU
        lifecycleScope.launch {
            com.lm.player.core.startup.StartupGate.await()
            com.lm.player.core.source.SourceAccelerationController.ensureRestored(this@MainActivity)
        }

        // 桌面搜索小部件带来的请求（搜索关键词 / 打开资料库），界面就绪后由 Compose 层执行
        if (savedInstanceState == null) com.lm.player.core.widget.WidgetLaunch.consume(intent)

        // 崩溃日志守护不在这里安装：Activity 每次重建都会再套一层处理器，
        // 且闭包持有 Activity 导致泄漏。统一由 LMApplication 启动时安装一次
        // (CrashLogger.install)，堆栈写入 filesDir/crash_last.txt 并在下次启动弹窗展示。

        // 1. 初始化核心数据库、路由与下载引擎
        database = ZdsDatabase.getInstance(this)
        playbackRouter = PlaybackRouter(database.downloadDao(), this)
        downloadEngine = DownloadEngine(this, database.downloadDao(), database.songDao(), lifecycleScope)
        lifecycleScope.launch(Dispatchers.IO) {
            // 启动提速：全表扫描去重不是续播必需，等启动闸门放行后再做
            com.lm.player.core.startup.StartupGate.await()
            LocalMediaScanner.cleanupDuplicateLocalSongs(database)
        }

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

        // 4. 申请 Android 6.0 / 13+ 运行时权限
        requestAppPermissions()

        // 按保存的主题偏好先涂好窗口背景与系统栏（深色模式下状态栏不再露出浅色窗口底）
        run {
            val savedMode = runCatching {
                AppThemeMode.valueOf(initSettingsPrefs.getString("app_theme_mode", AppThemeMode.FOLLOW_SYSTEM.name)!!)
            }.getOrDefault(AppThemeMode.FOLLOW_SYSTEM)
            com.lm.player.core.designsystem.theme.SystemBarsStyler.applyInitial(this, savedMode)
        }

        // 第二十三节：在画第一帧之前同步读回已选的平台模式。
        // 原先放在组合里的 LaunchedEffect 中读取 —— 它要等第一帧画完才执行，第一帧模式为空，
        // 于是每次启动都先按电视模式画一遍选择向导再整页重画成车机界面，表现为"一闪而过"。
        // 只读一个 SharedPreferences 值，不耗时；Activity 重建时字段已有值则保留。
        if (platformModeState.value == null) {
            platformModeState.value = PlatformMode.fromKey(settingsPrefs.getString(PLATFORM_MODE_PREF_KEY, null))
        }

        // 语音点歌：应用是被「播放某某」的意图拉起来的 —— 直接播点的歌，
        // 并把续播标记为已完成，避免启动续播把刚点的歌顶掉
        if (savedInstanceState == null && platformModeState.value != null &&
            com.lm.player.core.voice.VoicePlayRouter.consumeIntent(this, intent, runCatching { referrer?.host }.getOrNull())
        ) {
            fastResumeResult.complete(true)
        }
        // 2026-11-24 诊断：别的应用（音乐助手、桌面）打开主界面时记一条，设置页「最近语音指令」里可见
        if (savedInstanceState == null) {
            val caller = runCatching { referrer?.host }.getOrNull()
            // 音乐助手点完歌顺手打开本应用：不做启动续播（会顶掉刚点的歌）；设置为点歌不打开界面时退回后台
            val followUp = com.lm.player.core.voice.VoicePlayRouter.isVoiceFollowUpLaunch(caller)
            val backToBg = followUp && com.lm.player.core.voice.VoicePlayRouter.shouldReturnToBackground(this, caller)
            com.lm.player.core.voice.VoiceDiagnostics.recordAppOpen(
                this, caller, intent, "打开主界面",
                note = if (backToBg) "点歌后顺手打开，已退回后台" else null
            )
            if (followUp) fastResumeResult.complete(true)
            if (backToBg) window.decorView.post { moveTaskToBack(true) }
        }
        // 2026-11-23：语音中转页点完歌再把主界面打开（设置「语音点歌时打开界面」）—— 歌已经在换了，同样不做启动续播
        if (savedInstanceState == null &&
            intent?.getBooleanExtra(com.lm.player.core.voice.VoicePlayRouter.EXTRA_FROM_VOICE, false) == true
        ) {
            fastResumeResult.complete(true)
        }

        // 启动提速：上次的歌是本地文件时，不等曲库加载，直接续播
        startFastResume(savedInstanceState)
        scheduleStartupGateRelease(savedInstanceState)

        // 界面流畅度统计（性能优化第 0 项）：逐帧耗时回调，跑在独立后台线程
        com.lm.player.core.startup.FrameStats.attach(this)
        setContent {
            // 启动提速：首帧已出 → 打点，并参与启动闸门的放行判断
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                com.lm.player.core.startup.StartupTrace.mark(com.lm.player.core.startup.StartupTrace.FIRST_FRAME)
                firstFrameDrawn.complete(Unit)
            }

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

            // 运行平台模式：首次启动由不可跳过的向导强制选择，之后可在设置中切换。
            // null = 尚未选择 → 弹出向导；向导期间按 TV 模式渲染（见 effectivePlatformMode），
            // 保证没有触屏的设备也能用遥控器方向键完成选择。
            // 读 Activity 级状态（见 platformModeState 注释）：dispatchKeyEvent 的
            // 「长按返回键切回电视」逃生通道要在组合之外改动它。
            // 已选模式在 setContent 之前同步读回 (见 onCreate)，首帧就是正确的模式。
            val platformMode by platformModeState
            val effectivePlatformMode = platformMode ?: PlatformMode.TV
            val isCarPlatform = effectivePlatformMode == PlatformMode.CAR

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
            // 「我的」页是因为「服务器离线」自动进入本地模式（而不是用户手动切换）：服务器稳定恢复后才自动切回在线
            var localModeDueToOffline by remember { mutableStateOf(false) }

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
                // 启动提速：更新检查等启动闸门放行后再计时
                com.lm.player.core.startup.StartupGate.await()
                delay(8000L)
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
            // 进程被系统回收后重建时要回到用户原来所在的页面，而不是永远弹回首页。
            // Screen 是枚举，用 Saver 把它当作字符串存取，读写都是同一个状态对象，
            // 因此 `currentScreen = xxx` 这类赋值点无需改动。
            // 启动页面（设置 → 播放与外观 → 启动页面，默认「我的」）。
            // 它同时是返回键意义上的「首页」：在它上面按返回提示退出，其它页面返回到它（原先固定是「发现」）。
            val rootScreen = remember { resolveStartScreen() }
            var currentScreen by rememberSaveable(
                stateSaver = androidx.compose.runtime.saveable.Saver(
                    save = { it.name },
                    restore = { name -> try { Screen.valueOf(name) } catch (_: Exception) { rootScreen } }
                )
            ) { mutableStateOf(rootScreen) }
            val screenHistoryStack = remember { mutableStateListOf<Screen>() }
            // 各顶部页面是否处于下钻子视图。切页保留页面后「我的」「发现」会同时留在组合里，
            // 必须按页面分开记：只有**当前页面**的下钻状态才决定返回键交给谁
            val subViewActiveByPage = remember { mutableStateMapOf<String, Boolean>() }
            val isChildSubViewActive = subViewActiveByPage[
                if (currentScreen == Screen.MINE || currentScreen == Screen.LIBRARY) "mine" else currentScreen.name
            ] == true
            var favoritesRefreshTrigger by remember { mutableStateOf(0) }
            val refreshServerFavorites: () -> Unit = { favoritesRefreshTrigger++ }

            // 「上次离开的页面」：记录最后停留的顶部页面（资料库 / 下载归到「我的」）
            LaunchedEffect(currentScreen) {
                val top = when (currentScreen) {
                    Screen.LIBRARY, Screen.DOWNLOADS -> Screen.MINE
                    Screen.SEARCH -> null
                    else -> currentScreen
                }
                if (top != null) com.lm.player.core.designsystem.theme.AppUiPrefs.recordLastPage(this@MainActivity, top.name)
            }

            val navigateToScreen: (Screen) -> Unit = { target ->
                // 启动提速：用户已开始切页操作，延后的后台任务不再等待
                if (target != rootScreen) com.lm.player.core.startup.StartupGate.release("user_navigation")
                if (target == Screen.MINE || target == Screen.LIBRARY) {
                    favoritesRefreshTrigger++
                }
                if (target != currentScreen) {
                    if (target == rootScreen) {
                        screenHistoryStack.clear()
                    } else {
                        screenHistoryStack.remove(target)
                        screenHistoryStack.add(currentScreen)
                    }
                    currentScreen = target
                }
            }

            val popScreenOrHome: () -> Unit = {
                if (screenHistoryStack.isNotEmpty()) {
                    currentScreen = screenHistoryStack.removeAt(screenHistoryStack.lastIndex)
                } else {
                    currentScreen = rootScreen
                }
            }

            var activeServerName by remember { mutableStateOf("本地 · 已下载") }
            var activeServerId by remember { mutableStateOf("") }
            var serversList by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
            var isSearchDialogOpen by rememberSaveable { mutableStateOf(false) }

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
            var showCustomFolderPickerForDownload by remember { mutableStateOf(false) }
            var showCustomFolderPickerForImport by remember { mutableStateOf(false) }
            var showDownloadDirSourceChooser by remember { mutableStateOf(false) }
            // 「我喜欢的音乐」的口径来源：柠檬服务器收藏 (/api/library/user-data 的 favorites)。
            // 本地 Room 的 isFavorite 只是这份服务器收藏在本机的镜像，用它来枚举收藏会漏掉
            // 「在别的设备/网页端收藏、本机没同步过」的曲目，所以收藏列表一律以服务器为准。
            var serverFavoriteSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            // 每次访问「我的」/「资料库」或触发 favoritesRefreshTrigger 时及时向服务器拉取「我喜欢的音乐」
            val onMinePage = currentScreen == Screen.MINE || currentScreen == Screen.LIBRARY
            LaunchedEffect(serversList, favoritesRefreshTrigger, onMinePage) {
                // 启动提速：服务器收藏只给「我的」页用 —— 不在「我的」页不拉；在的话也等续播出声（启动闸门放行）后再拉
                if (!onMinePage) return@LaunchedEffect
                com.lm.player.core.startup.StartupGate.await()
                val srv = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                    ?: return@LaunchedEffect
                val fetched = withContext(Dispatchers.IO) {
                    try {
                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                        // 2026-11-25：拉到服务器收藏的同时把本机红心对齐过去（别处取消的收藏这边也清掉）
                        com.lm.player.core.network.UserDataMirror.pullFavorites(database, protocol)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Fetching server favorites failed", e)
                        null
                    }
                }
                if (fetched != null) serverFavoriteSongs = fetched
            }
            // 「最近播放」以 PlaybackQueueManager 的 flow 为**唯一真源**。
            // 先同步装载一次本地播放足迹（单次读盘成本与原实现一致），未连接服务器时直接展示这份记录；
            // 之后所有"前移/合并服务器足迹"都走 manager 的方法，界面这里不再各自维护副本
            // —— 之前 6 处手工 `listOf(song) + filter` 正是顺序被反复打乱的原因。
            remember { PlaybackQueueManager.primeRecentPlayedSongs(this@MainActivity) }
            val recentlyPlayedSongs by PlaybackQueueManager.recentPlayedSongsFlow.collectAsStateLifecycleAware()

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
                        // 第二十二节：取「下载记录时间」与「文件修改时间」中较早的一个。
                        // 扫描 / 匹配写的旧记录时间是「匹配那一刻」，直接用会让上千首旧文件冒充刚下载；
                        // 真正下载的歌两者几乎相同，之后重新内嵌标签会让文件时间变新，取较早的仍是下载时间。
                        val recTs = downloadedAtBySongId[s.id]
                        val fileTs = s.localFilePath
                            ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
                            ?.let { runCatching { File(it).lastModified() }.getOrDefault(0L) }
                            ?.takeIf { it > 0L }
                        val localAdded = when {
                            recTs != null && fileTs != null -> minOf(recTs, fileTs)
                            else -> recTs ?: fileTs ?: 0L
                        }
                        val withTime = if (localAdded > 0L && s.addedTimestamp != localAdded) {
                            s.copy(addedTimestamp = localAdded)
                        } else s
                        if (isFav != withTime.isFavorite) withTime.copy(isFavorite = isFav) else withTime
                    }
                    val seenPaths = HashSet<String>()
                    val seenIds = HashSet<String>()
                    // 第二十二节：下载管理「已完成」最新下载的排最前 (与手机端一致)；
                    // 上面已把 addedTimestamp 换成本地下载时间，这里按它倒序
                    combined.filter { s ->
                        if (!seenIds.add(s.id)) return@filter false
                        val p = s.localFilePath?.trim()
                        if (!p.isNullOrEmpty()) seenPaths.add(p) else true
                    }.sortedByDescending { it.addedTimestamp }
                }
            }

            // 全局播放状态委托至 PlaybackQueueManager
            val currentSong by PlaybackQueueManager.currentSongFlow.collectAsStateLifecycleAware()
            val isPlaying by PlaybackQueueManager.isPlayingFlow.collectAsStateLifecycleAware()
            val isShuffle by PlaybackQueueManager.isShuffleFlow.collectAsState()
            val isRepeat by PlaybackQueueManager.isRepeatFlow.collectAsState()
            val currentQueue by PlaybackQueueManager.playlistFlow.collectAsState()

            var currentLyrics by remember { mutableStateOf(LyricResult()) }
            var isFullPlayerVisible by rememberSaveable { mutableStateOf(false) }

            // 全屏播放页隐藏系统顶部状态栏（设置「播放页隐藏状态栏」，默认开启）；离开播放页恢复显示
            val hidePlayerStatusBar = com.lm.player.core.designsystem.theme.AppUiPrefs.hidePlayerStatusBar

            // 桌面搜索小部件：带关键词进入搜索页 / 打开资料库（离开搜索页后清掉关键词，免得下次手动进入还带着）
            var widgetSearchQuery by remember { mutableStateOf("") }
            val widgetLaunchRequest by com.lm.player.core.widget.WidgetLaunch.pending.collectAsState()
            LaunchedEffect(widgetLaunchRequest) {
                when (val req = widgetLaunchRequest) {
                    is com.lm.player.core.widget.WidgetLaunch.Request.Search -> {
                        isFullPlayerVisible = false
                        isSearchDialogOpen = false
                        widgetSearchQuery = req.query
                        navigateToScreen(Screen.SEARCH)
                    }
                    is com.lm.player.core.widget.WidgetLaunch.Request.Library -> {
                        isFullPlayerVisible = false
                        isSearchDialogOpen = false
                        navigateToScreen(Screen.LIBRARY)
                    }
                    null -> return@LaunchedEffect
                }
                com.lm.player.core.widget.WidgetLaunch.pending.value = null
            }
            LaunchedEffect(currentScreen) {
                if (currentScreen != Screen.SEARCH) widgetSearchQuery = ""
            }
            // 性能优化 A1：全屏播放页进场动画已结束 / 播放页未被下拉 (两者同时成立才算完全盖住主界面)
            var fullPlayerEnterSettled by remember { mutableStateOf(false) }
            var fullPlayerSheetCovering by remember { mutableStateOf(true) }
            var isLyricsMode by remember { mutableStateOf(false) }

            // 专辑卡片 / 全屏封面与主界面卡片之间过渡时（2026-11-22）：状态栏等播放页完全盖住主界面后再隐藏、
            // 一开始收回就恢复 —— 状态栏显隐会让主界面上下挪一点，挪动都发生在被盖住的时候，卡片落位后不再跳。
            // 过渡状态在 snapshotFlow 里读，不让整个界面跟着重组
            LaunchedEffect(isFullPlayerVisible, hidePlayerStatusBar) {
                snapshotFlow {
                    isFullPlayerVisible && hidePlayerStatusBar &&
                        (!com.lm.player.feature.player.tv.PlayerCardHandoff.active ||
                            (fullPlayerSheetCovering &&
                                com.lm.player.feature.player.tv.PlayerCardHandoff.coversHome(com.lm.player.feature.player.tv.PlayerCardHandoff.progress)))
                }.collect { applyPlayerStatusBar(it) }
            }

            // 启动后直接进入播放页（设置「启动后进入播放页」，2026-11-23）：只在这次界面全新打开时做一次
            // （转屏 / 被系统回收后恢复的不算）；等上次的歌恢复出来、首次运行的平台选择完成后再打开。
            // 「上次在播放页退出时」要在本次记录播放页开关之前读出上一次的记录
            val openPlayerOnLaunch = remember {
                com.lm.player.core.designsystem.theme.AppUiPrefs.shouldOpenPlayerOnLaunch(this@MainActivity)
            }
            var launchPlayerHandled by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (launchPlayerHandled) return@LaunchedEffect
                launchPlayerHandled = true
                if (!openPlayerOnLaunch) return@LaunchedEffect
                val ready = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    snapshotFlow { currentSong != null && platformModeState.value != null }
                        .first { it }
                }
                if (ready != true) return@LaunchedEffect
                // 让主界面先排好版（左侧播放卡片的位置量出来，播放页才能从卡片翻出来）
                delay(350L)
                if (!isFullPlayerVisible && com.lm.player.core.widget.WidgetLaunch.pending.value == null) {
                    isSearchDialogOpen = false
                    isFullPlayerVisible = true
                }
            }
            LaunchedEffect(isFullPlayerVisible) {
                com.lm.player.core.designsystem.theme.AppUiPrefs.recordPlayerVisible(this@MainActivity, isFullPlayerVisible)
            }

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
            // 界面流畅度统计：按当前在看的界面分开记（设置 → 运行诊断 → 界面流畅度）
            LaunchedEffect(currentScreen, isFullPlayerVisible, playerThemeStyle, isSearchDialogOpen) {
                com.lm.player.core.startup.FrameStats.scope = when {
                    isFullPlayerVisible -> "播放页·" + playerThemeStyle.displayName
                    isSearchDialogOpen -> "搜索"
                    else -> when (currentScreen) {
                        Screen.MINE, Screen.LIBRARY -> "我的"
                        Screen.HOME -> "发现"
                        Screen.SEARCH -> "搜索"
                        Screen.PODCASTS -> "播客"
                        Screen.DOWNLOADS -> "下载管理"
                        Screen.SETTINGS -> "设置"
                    }
                }
            }

            // 双击返回退出程序并彻底停止播放
            var lastBackPressTime by remember { mutableStateOf(0L) }

            BackHandler(enabled = isFullPlayerVisible || isSearchDialogOpen || (!isChildSubViewActive && currentScreen != rootScreen)) {
                if (isFullPlayerVisible) {
                    isFullPlayerVisible = false
                } else if (isSearchDialogOpen) {
                    isSearchDialogOpen = false
                } else if (currentScreen != rootScreen) {
                    popScreenOrHome()
                }
            }

            BackHandler(enabled = !isFullPlayerVisible && !isSearchDialogOpen && !isChildSubViewActive && currentScreen == rootScreen) {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    exitAppCompletely()
                } else {
                    lastBackPressTime = now
                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "再按一次彻底退出程序并停止播放", Toast.LENGTH_SHORT).show()
                }
            }

            // 2026-11-25：服务器歌单对齐到本机库，三处同步共用（串行执行；服务器上已没有的云端歌单——
            // 包括本机建的 pl_ 歌单——一律删掉，别的设备删的歌单才同步得过来；见 ServerPlaylistSync）
            val syncServerPlaylistsWith: suspend (LemonMusicProtocol, ServerConfig) -> Unit = { proto, config ->
                com.lm.player.core.network.ServerPlaylistSync.sync(database, proto, config) { pl ->
                    com.lm.player.core.database.entity.PlaylistEntity(
                        id = pl.id,
                        name = pl.name,
                        coverUrl = pl.coverUrl,
                        serverId = config.id,
                        isOnline = true,
                        songCount = pl.songCount
                    )
                }.onSuccess { plList ->
                    // 云端歌单四宫格封面：服务端 user-data 已随歌单下发 trackSnapshots，无需额外请求
                    val remoteCovers = plList.filter { it.previewCovers.isNotEmpty() }
                        .associate { it.id to it.previewCovers }
                    if (remoteCovers.isNotEmpty()) {
                        playlistPreviewCovers = playlistPreviewCovers + remoteCovers
                    }
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

                        syncServerPlaylistsWith(activeProto, config)
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
                            // 用 Token 替换输入的密码前，先把密码加密存进 saltOrSecret（柠檬服务器未使用该字段）：
                            // 会话过期后 NetworkClientFactory 的 Authenticator 靠它自动重登（C-02）。
                            val previousValue = config.tokenOrApiKey
                            val secretToStore = if (
                                config.type == ServerType.LEMON_MUSIC &&
                                previousValue.isNotBlank() &&
                                !LemonMusicProtocol.looksLikeServerToken(previousValue)
                            ) {
                                com.lm.player.core.security.ServerTokenCipher.encrypt(previousValue)
                            } else {
                                // 内存里的 config 可能来自设置页（saltOrSecret 为空），不能用它覆盖库里已保存的密码
                                val storedSecret = runCatching {
                                    database.serverDao().getAllServers().firstOrNull { it.id == updatedConfig.id }?.saltOrSecret
                                }.getOrNull().orEmpty()
                                updatedConfig.saltOrSecret.ifBlank { storedSecret }
                            }
                            database.serverDao().insertServer(
                                ServerEntity(
                                    id = updatedConfig.id,
                                    name = updatedConfig.name,
                                    type = updatedConfig.type,
                                    serverUrl = updatedConfig.serverUrl,
                                    username = updatedConfig.username,
                                    tokenOrApiKey = updatedConfig.tokenOrApiKey,
                                    saltOrSecret = secretToStore,
                                    syncMode = updatedConfig.syncMode,
                                    isCurrentActive = updatedConfig.isCurrentActive
                                )
                            )
                            LemonMusicProtocol(client, updatedConfig.serverUrl, updatedConfig.username, updatedConfig.tokenOrApiKey)
                        } else protocol

                        // 第二十三节：登录成功后**第一件事**拉取音源并启动本地音源加速。
                        // 此前 TV 端只有进入「设置 → 音源」页才会启动加速，登录后不进设置就一直不生效。
                        // 单独起协程，不阻塞下面的歌单 / 曲库同步；会话已就绪时 start 直接复用，不重复加载。
                        val accelConfig = if (token.isNotBlank() && token != config.tokenOrApiKey) config.copy(tokenOrApiKey = token) else config
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                com.lm.player.core.source.ServerSourceRepository.syncFromServer(accelConfig, activeProto, force = false)
                            } catch (e: Exception) {
                                Log.w("MainActivity", "Syncing server source status failed", e)
                            }
                            try {
                                com.lm.player.core.source.SourceAccelerationController.start(this@MainActivity, accelConfig, activeProto)
                            } catch (e: Exception) {
                                Log.w("MainActivity", "Starting source acceleration failed", e)
                            }
                        }

                        // 优先瞬时同步服务器歌单 (不等大体积曲库请求，秒级完成并更新界面)
                        try {
                            syncServerPlaylistsWith(activeProto, config)
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Pre-sync playlists failed", e)
                        }

                        // ==================== 1. 免扫盘增量比对 (/library/sync-state) ====================
                        val syncStatePrefs = this@MainActivity.getSharedPreferences("lemon_library_sync_state", Context.MODE_PRIVATE)
                        val lastVersion = syncStatePrefs.getLong("last_version_${config.id}", -1L)
                        val lastSongCount = syncStatePrefs.getInt("last_song_count_${config.id}", -1)
                        val lastChecksum = syncStatePrefs.getString("last_checksum_${config.id}", "") ?: ""
                        val lastServerTrackCount = syncStatePrefs.getInt("last_server_track_count_${config.id}", -1)

                        // 在线收藏曲目（lemon_online_*）也挂在本服务器下，但不属于服务器曲库，不计入曲目数比对
                        val localSongIds = database.songDao().getSongIdsByServer(config.id)
                            .filterNot { it.startsWith("lemon_online_") }
                        val hasLocalSongs = localSongIds.isNotEmpty()

                        var skipFullSongSync = false
                        var newServerVersion = -1L
                        var newSongCount = -1
                        var newChecksum = ""

                        try {
                            val syncStateRes = activeProto.checkLibrarySyncState()
                            if (syncStateRes.isSuccess) {
                                val stateObj = syncStateRes.getOrNull() ?: JSONObject()
                                val dataObj = stateObj.optJSONObject("data") ?: stateObj

                                val v1 = dataObj.optLong("version", -1L)
                                val v2 = dataObj.optLong("revision", -1L)
                                val v3 = dataObj.optLong("lastModified", -1L)
                                newServerVersion = if (v1 != -1L) v1 else if (v2 != -1L) v2 else v3
                                val updatedFlag = if (dataObj.has("updated")) dataObj.optInt("updated", 1) else 1
                                // 柠檬服务端 sync-state 返回按当前用户音乐目录统计的 trackCount（songCount/count 为兼容旧字段）
                                val tc = dataObj.optInt("trackCount", -1)
                                val sc = dataObj.optInt("songCount", -1)
                                newSongCount = when {
                                    tc != -1 -> tc
                                    sc != -1 -> sc
                                    else -> dataObj.optInt("count", -1)
                                }
                                newChecksum = dataObj.optString("hash", "").ifBlank { dataObj.optString("checksum", "") }

                                val isVersionMatched = newServerVersion > 0L && newServerVersion == lastVersion
                                val isChecksumMatched = newChecksum.isNotBlank() && newChecksum == lastChecksum
                                val isCountMatched = newSongCount == -1 || newSongCount == localSongIds.size
                                // C-07：服务端 revision 是全局的，管理员修改某个用户的音乐目录时它不会变，
                                // 只看 revision 会一直跳过同步；用该用户 trackCount 与上次同步时的值比对兜底。
                                val serverCountChanged = newSongCount != -1 && lastServerTrackCount != -1 &&
                                    newSongCount != lastServerTrackCount

                                if (hasLocalSongs && !showToast && !serverCountChanged) {
                                    if (updatedFlag == 0 && (isVersionMatched || isChecksumMatched || newServerVersion == -1L)) {
                                        skipFullSongSync = true
                                    } else if (isVersionMatched && isCountMatched) {
                                        skipFullSongSync = true
                                    } else if (isChecksumMatched && isCountMatched) {
                                        skipFullSongSync = true
                                    }
                                }

                                if (skipFullSongSync) {
                                    Log.i("MainActivity", "Library sync-state HIT for ${config.name} (version=$newServerVersion, localCount=${localSongIds.size}). Skipped full disk/library sync.")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "checkLibrarySyncState probe exception, fallback to normal sync", e)
                        }

                        // ==================== 2. 全量曲库拉取与入库合并 (若未命中免扫盘) ====================
                        if (!skipFullSongSync) {
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
                                            addedTimestamp = it.addedTimestamp,
                                            rawMetaJson = it.rawMetaJson
                                        )
                                    }
                                    val mergedCount = SongMatchingResolver.syncAndUpsertServerSongs(
                                        database = database,
                                        incomingServerSongs = entities,
                                        downloadDir = downloadEngine.getDownloadDir(),
                                        targetServerId = config.id
                                    )
                                    Log.i("MainActivity", "Server sync completed. Merged & verified $mergedCount local tracks.")
                                    LocalMediaScanner.matchAndMergeLocalWithServer(database)
                                    LocalMediaScanner.cleanupDuplicateLocalSongs(database)

                                    val effectiveVersion = if (newServerVersion > 0L) newServerVersion else System.currentTimeMillis()
                                    syncStatePrefs.edit()
                                        .putLong("last_version_${config.id}", effectiveVersion)
                                        .putInt("last_song_count_${config.id}", entities.size)
                                        .putInt("last_server_track_count_${config.id}", newSongCount)
                                        .putString("last_checksum_${config.id}", newChecksum)
                                        .apply()
                                }
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
                                                    // 服务器曲库文件的收藏（local:路径）不补入库：曲库同步里没有它，说明文件已被服务端删除，
                                                    // 补回去就会出现「服务器删了、电视曲库还在」。只补在线收藏曲目。
                                                    val isServerFileFavorite = favItem.optString("source") == "local" ||
                                                        favItem.optString("key").startsWith("local:") ||
                                                        favItem.optString("localPath").isNotBlank() ||
                                                        favItem.optString("filePath").isNotBlank()
                                                    if (existing == null && !isServerFileFavorite) {
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
                        // 2026-11-25：上面只会「标为喜欢」；这里再按服务器收藏把别处取消的红心清掉
                        com.lm.player.core.network.UserDataMirror.pullFavorites(database, activeProto)
                            ?.let { favs -> withContext(Dispatchers.Main) { serverFavoriteSongs = favs } }

                        val addedList = activeProto.getRecentlyAdded(limit = 30).getOrNull() ?: emptyList()
                        if (addedList.isNotEmpty()) recentlyAddedSongs = addedList

                        // 2026-11-25：「最近播放」三端统一用服务器那 100 首（本机播放也会写回服务器，见 UserDataMirror）
                        com.lm.player.core.network.UserDataMirror.pullRecent(this@MainActivity, activeProto)

                        // 再次对齐服务器播放列表至数据库（含差量清理）
                        syncServerPlaylistsWith(activeProto, config)

                        // 服务器歌曲加速第 2 期：曲库同步完成后在后台预匹配（每天上限、让路播放，见 ServerTrackPrematcher）
                        com.lm.player.core.source.ServerTrackPrematcher.kick("library_synced")

                        if (showToast) {
                            withContext(Dispatchers.Main) {
                                val msg = if (skipFullSongSync) "已成功同步：曲库已是最新 (免扫盘增量比对命中)" else "已成功从柠檬服务器同步全量媒体数据与歌单"
                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (showToast) {
                        withContext(Dispatchers.Main) {
                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "同步失败: ${authRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            val syncServerSongs: (ServerConfig) -> Unit = { config ->
                syncServerSongsWithToast(config, true)
            }

            // 监听本地数据库中的服务器、播放列表与歌曲
            LaunchedEffect(Unit) {
                // 已查过本机曲目封面的歌单（查不到的也记下，不在每次列表变化时重复查库）
                val localCoverChecked = HashSet<String>()
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
                    // 「我的收藏」与「我的喜欢」重复：歌单列表里不再单独展示（收藏数据本身不变，仍由「我的喜欢」承载）
                    playlistsList = mapped.filter { it.id != "lemon_favorites" && it.name != "我的收藏" }

                    // 本地与自建歌单的封面目录：取歌单内前 4 首有封面的曲目，供卡片四宫格展示
                    val pending = mapped.filter {
                        it.previewCovers.isEmpty() && playlistPreviewCovers[it.id] == null && it.id !in localCoverChecked
                    }
                    if (pending.isNotEmpty()) {
                        pending.forEach { localCoverChecked.add(it.id) }
                        val fetched = withContext(Dispatchers.IO) {
                            pending.associate { pl ->
                                pl.id to runCatching {
                                    database.playlistDao().getPlaylistPreviewCovers(pl.id)
                                }.getOrDefault(emptyList())
                            }
                        }
                        // 2026-11-25：只补本机查到的、且这期间没被服务器同步填上的。
                        // 云端歌单的本机曲目关联通常为空：此前把空列表也写进去，若服务器同步恰好先一步写入了
                        // 四宫格封面，就会被这里的空列表盖掉 → 歌单卡片没封面
                        val current = playlistPreviewCovers
                        val additions = fetched.filter { (id, covers) -> covers.isNotEmpty() && current[id].isNullOrEmpty() }
                        if (additions.isNotEmpty()) {
                            playlistPreviewCovers = current + additions
                        }
                    }
                }
            }

            // 启动时自动深度清理遗留残留数据、孤立记录与物理文件核对
            LaunchedEffect(Unit) {
                // 启动提速：遗留数据清理不是续播必需，等启动闸门放行后再做
                com.lm.player.core.startup.StartupGate.await()
                withContext(Dispatchers.IO) {
                    try {
                        val validServers = database.serverDao().getAllServers().map { it.id }.toList()
                        val purged = LocalMediaScanner.purgeLegacyResidualData(database, validServers, this@MainActivity)
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
                                // 启动提速：服务器链路绑定、登录与曲库/歌单同步都等启动闸门放行
                                // （首帧 + 续播完成）后再做；在此之前资料库按本地模式显示，与原先登录完成前一致
                                com.lm.player.core.startup.StartupGate.await()
                                // 先绑定服务器并开始探活（不依赖本地是否缓存了音源脚本），链路判定与目录网关立即生效
                                if (active.type == ServerType.LEMON_MUSIC) {
                                    withContext(Dispatchers.Main) {
                                        com.lm.player.core.source.SourceAccelerationController.observeServer(this@MainActivity, active)
                                    }
                                }
                                try {
                                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                    val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                                    val authRes = protocol.authenticate(active)
                                    if (authRes.isSuccess) {
                                        Log.i("MainActivity", "Startup server check passed: ${active.name} online. Switching to server home...")
                                        com.lm.player.core.source.ServerPresence.onLoginSucceeded()
                                        withContext(Dispatchers.Main) {
                                            activeServerName = active.name
                                            localModeDueToOffline = false
                                        }
                                        syncServerPlaylists(active)
                                        syncServerSongs(active)
                                    } else {
                                        Log.w("MainActivity", "Startup server check: ${active.name} is unreachable. Remaining in local mode.")
                                        if (active.type == ServerType.LEMON_MUSIC) {
                                            com.lm.player.core.source.SourceAccelerationController.onStartupLoginFailed(active)
                                        }
                                        withContext(Dispatchers.Main) {
                                            activeServerName = "本地 · 已下载"
                                            localModeDueToOffline = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Startup auto server connection check failed", e)
                                    if (active.type == ServerType.LEMON_MUSIC) {
                                        com.lm.player.core.source.SourceAccelerationController.onStartupLoginFailed(active)
                                    }
                                    withContext(Dispatchers.Main) {
                                        activeServerName = "本地 · 已下载"
                                        localModeDueToOffline = true
                                    }
                                }
                            }
                        }
                    } else {
                        activeServerName = "本地 · 已下载"
                    }
                }
            }

            // 2026-11-25：在前台时每次回到前台、以及之后每分钟，轻量检查服务器 user-data 的 revision
            // （服务器对 ?sinceRevision= 没变化只回 unchanged 标记）。别的设备（iOS / 手机 / 网页）建、删歌单后，
            // 车机停在歌单页不切页也能跟上；有变化才整份拉歌单。退到后台即停。
            LaunchedEffect(Unit) {
                com.lm.player.core.startup.StartupGate.await()
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (isActive) {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        val inLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                        if (active != null && !inLocalMode) {
                            val proto = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl, active.username, active.tokenOrApiKey
                            )
                            val changed = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.lm.player.core.network.ServerPlaylistSync.changedSinceLastSync(proto, active)
                                }.getOrDefault(false)
                            }
                            if (changed) {
                                Log.i("MainActivity", "Server user-data changed, re-syncing playlists / favorites / recent plays")
                                syncServerPlaylists(active)
                                // 收藏、最近播放同样以服务器为准（别的设备点红心、听歌后这边跟上）
                                val favs = withContext(Dispatchers.IO) {
                                    runCatching { com.lm.player.core.network.UserDataMirror.pullFavorites(database, proto) }.getOrNull()
                                }
                                if (favs != null) serverFavoriteSongs = favs
                                withContext(Dispatchers.IO) {
                                    runCatching { com.lm.player.core.network.UserDataMirror.pullRecent(this@MainActivity, proto) }
                                }
                            }
                        }
                        delay(60_000L)
                    }
                }
            }

            // 服务器可用性（带防抖 + 持续不稳定自动锁定，见 ServerPresence）驱动「我的」页在线 / 本地模式自动切换：
            // - 持续断联 / 高延迟满阈值：自动锁定本地模式（LOCKED_LOCAL），不再来回抖动，需手动恢复在线；
            // - 关闭自动锁定时沿用原 60s 离线切本地、恢复稳定 30s 自动切回在线；
            // - 发现页、搜索、播放与下载始终继续走本地音源，不受资料库锁定影响。
            val serverPresence by com.lm.player.core.source.ServerPresence.mode.collectAsState()
            LaunchedEffect(serverPresence) {
                val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    ?: return@LaunchedEffect
                val inLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                when (serverPresence) {
                    com.lm.player.core.source.ServerPresence.Mode.LOCKED_LOCAL -> {
                        if (!inLocalMode || activeServerName == "本地 · 服务器离线") {
                            Log.w("MainActivity", "Server ${active.name} unstable/offline: library auto-locked to local mode")
                            activeServerName = "本地 · 网络不稳定已锁定"
                            localModeDueToOffline = true
                        }
                    }
                    com.lm.player.core.source.ServerPresence.Mode.OFFLINE -> if (!inLocalMode) {
                        Log.w("MainActivity", "Server ${active.name} offline (confirmed): library switches to local mode")
                        activeServerName = "本地 · 服务器离线"
                        localModeDueToOffline = true
                    }
                    com.lm.player.core.source.ServerPresence.Mode.ONLINE -> if (inLocalMode && localModeDueToOffline) {
                        Log.i("MainActivity", "Server ${active.name} back online (stable): re-login and switch to online mode")
                        val authRes = withContext(Dispatchers.IO) {
                            runCatching {
                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey).authenticate(active)
                            }.getOrElse { Result.failure(it) }
                        }
                        if (authRes.isSuccess) {
                            activeServerName = active.name
                            localModeDueToOffline = false
                            syncServerPlaylists(active)
                            syncServerSongs(active)
                        } else {
                            com.lm.player.core.source.ServerPresence.onLoginFailed()
                        }
                    }
                    else -> Unit
                }
            }

            LaunchedEffect(Unit) {
                // 不再直接用 Room 的 getAllSongsFlow()（SELECT * 一次返回全表，
                // 大曲库下 CursorWindow 2MB 放不下会抛 IllegalStateException 闪退），
                // 改为 Room 官方 invalidationTrackerFlow 监听 songs 表变化 + 分页读取聚合
                database.invalidationTrackerFlow("songs")
                    .debounce(400L)
                    .map { database.songDao().getAllSongsList() }
                    .collect { songEntities ->
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
                                // 用进程内固定的时刻而不是每次重载都取「现在」：否则每次曲库重载都会产生
                                // 内容不同的列表，资料库各项统计因此每次都要整库重算（排序语义不变：仍排在最前）
                                entity.downloadStatus == DownloadStatus.DOWNLOADED -> StableClock.processStartMs
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
                                rawMetaJson = entity.rawMetaJson ?: entity.relativeFolderPath?.takeIf { it.trim().startsWith("{") },
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
                    com.lm.player.core.startup.StartupTrace.mark(com.lm.player.core.startup.StartupTrace.LIBRARY_READY)
                    // 下载状态即时刷新：数据库镜像更新后，清掉已被数据库追上的即时事件
                    com.lm.player.core.media.SongStatusStore.updateLibrary(mappedSongs)
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
                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "未配置柠檬音乐服务端，无法推送至服务器曲库", Toast.LENGTH_SHORT).show()
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
                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已提交缓存至服务器 [${quality.badge}]: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "缓存至服务器失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
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
                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已加入下载队列 [${quality.badge}]: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                    } else if (com.lm.player.core.media.LocalUpgradeRequests.take(songToDownload.id) != null) {
                        // 本地升级必须能按新音质重新取文件；没有服务端来源时取消，不能拿旧文件「升级」自己
                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "没有可用的服务器音源，无法升级音质：${songToDownload.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        // 纯本地文件：没有可换音质的来源，沿用本地直链下载
                        downloadEngine.startDownload(preparedSong)
                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已加入本地下载: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
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
                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "未配置柠檬音乐服务端，无法推送至服务器曲库", Toast.LENGTH_SHORT).show()
                        } else if (songsNeedingServerPush.isEmpty()) {
                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "所选 $serverAlreadyCount 首歌曲均已在服务器曲库，无需重复下载", Toast.LENGTH_SHORT).show()
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
                                            com.lm.player.core.designsystem.component.AppToast.makeText(
                                                this@MainActivity,
                                                "已提交 ${songsNeedingServerPush.size} 首至服务器缓存 [${quality.badge}]$skipTip",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "批量提交服务器缓存失败", Toast.LENGTH_LONG).show()
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
                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "批量缓存网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        com.lm.player.core.designsystem.component.AppToast.makeText(
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
                // 服务器确认离线/已删除时，默认目标为服务器或双端也一律改为本地下载
                val defaultTarget = (try { DownloadTarget.valueOf(savedTargetName) } catch (_: Exception) { DownloadTarget.LOCAL })
                    .let { if (it != DownloadTarget.LOCAL && com.lm.player.core.source.ServerLinkState.isLost) DownloadTarget.LOCAL else it }
                val savedQualityKey = uiPrefs.getString("default_download_quality", AudioQuality.Q_320K.key) ?: AudioQuality.Q_320K.key
                val defaultQuality = AudioQuality.entries.firstOrNull { it.key == savedQualityKey } ?: AudioQuality.Q_320K
                handleDownloadWithOptions(songToDownload, defaultTarget, defaultQuality)
            }

            val handleToggleFavorite: (UnifiedSong) -> Unit = { songToFav ->
                // 连点去抖：一次收藏会产生「Room 写入 + 服务器整表同步 + 歌单刷新 + 可能的下载任务」，
                // 快速连点会让多个协程并发跑同一套流程，既浪费请求，也会因服务端 user-data 的
                // 整表读改写而彼此覆盖（用户看到「点了没反应」）。同一首歌 600ms 内只处理一次。
                val nowFavMs = System.currentTimeMillis()
                val lastFavMs = favoriteToggleGuard[songToFav.id]
                if (lastFavMs != null && nowFavMs - lastFavMs < 600L) {
                    Log.i("MainActivity", "toggleFavorite debounced for ${songToFav.title}")
                } else {
                    favoriteToggleGuard[songToFav.id] = nowFavMs
                    if (favoriteToggleGuard.size > 256) {
                        favoriteToggleGuard.entries.removeAll { nowFavMs - it.value > 60_000L }
                    }
                val newFav = !songToFav.isFavorite
                val isAutoCache = uiPrefs.getBoolean("auto_cache_on_favorite", false) ||
                    uiPrefs.getBoolean("auto_cache_to_server_on_favorite", false)
                val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                val willCacheToServer = newFav && isAutoCache && activeServer != null
                val targetServerId = activeServer?.id?.ifBlank { "lemon_music" } ?: "lemon_music"
                val effectiveServerId = when {
                    willCacheToServer && (songToFav.serverId.isBlank() || songToFav.serverId == "lemon_online" || songToFav.serverId == "default") -> targetServerId
                    songToFav.serverId.isNotBlank() && songToFav.serverId != "lemon_online" -> songToFav.serverId
                    else -> "lemon_online"
                }
                val updatedSong = songToFav.copy(isFavorite = newFav, serverId = effectiveServerId)
                songList = if (songList.any { it.id == songToFav.id }) {
                    songList.map { if (it.id == songToFav.id) updatedSong else it }
                } else {
                    listOf(updatedSong) + songList
                }
                // 「我喜欢的音乐」以服务器收藏为准，这里先把本机这份镜像改掉，
                // 确保用户点击红心后即时生效
                serverFavoriteSongs = if (newFav) {
                    (listOf(updatedSong) + serverFavoriteSongs).distinctBy { it.id }
                } else {
                    serverFavoriteSongs.filterNot { it.id == songToFav.id }
                }
                if (PlaybackQueueManager.currentSongFlow.value?.id == songToFav.id) {
                    PlaybackQueueManager.updateCurrentSong(updatedSong)
                }
                com.lm.player.core.designsystem.component.AppToast.makeText(
                    this@MainActivity,
                    if (newFav) "已加入「我喜欢的音乐」" else "已从「我喜欢的音乐」移除",
                    Toast.LENGTH_SHORT
                ).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val existing = database.songDao().getSongById(songToFav.id)
                        if (existing == null) {
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
                                        serverId = updatedSong.serverId,
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
                            if (willCacheToServer) {
                                database.songDao().updateServerId(songToFav.id, updatedSong.serverId)
                            }
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

                        if (activeServer != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                activeServer.serverUrl,
                                activeServer.username,
                                activeServer.tokenOrApiKey
                            )
                            val favSync = protocol.toggleFavoriteSongOnServer(updatedSong, newFav)
                            if (favSync.isFailure) {
                                // 服务器同步失败（含「读取服务器收藏失败、为防覆盖已取消」）时明确告知，不再静默吞掉
                                val reason = favSync.exceptionOrNull()?.message.orEmpty()
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    com.lm.player.core.designsystem.component.AppToast.makeText(
                                        this@MainActivity,
                                        "收藏未同步到服务器，请稍后重试${if (reason.isNotBlank()) "：$reason" else ""}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            syncServerPlaylists(activeServer)
                            val favs = protocol.getPlaylistSongs("lemon_favorites").getOrNull()
                            if (favs != null) {
                                val mappedFavs = if (isAutoCache) {
                                    favs.map { s ->
                                        if (s.serverId == "lemon_online" || s.serverId.isBlank() || s.serverId == "default") {
                                            s.copy(serverId = activeServer.id.ifBlank { "lemon_music" })
                                        } else s
                                    }
                                } else favs
                                withContext(Dispatchers.Main) {
                                    serverFavoriteSongs = mappedFavs
                                }
                            }
                            val isAutoCache = uiPrefs.getBoolean("auto_cache_on_favorite", false) ||
                                uiPrefs.getBoolean("auto_cache_to_server_on_favorite", false)
                            if (newFav && isAutoCache) {
                                try {
                                    val qKey = uiPrefs.getString("auto_cache_server_quality", null)
                                        ?: uiPrefs.getString("default_download_quality", AudioQuality.Q_320K.key)
                                        ?: AudioQuality.Q_320K.key
                                    val targetQuality = AudioQuality.fromKey(qKey)
                                    val serverTask = DownloadRequestPlanner.buildServerDownloadTask(updatedSong, targetQuality)
                                    val cacheRes = protocol.addServerDownloadTasks(listOf(serverTask))
                                    withContext(Dispatchers.Main) {
                                        if (cacheRes.isSuccess) {
                                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已同步提交服务器缓存 [${targetQuality.badge}]", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("MainActivity", "autoCacheOnFavorite error", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleToggleFavorite error", e)
                    }
                }
                }
            }

            val handleAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { targetPlaylist, songToAdd ->
                // 立即登记：再打开「加入歌单」时这个歌单马上显示「已加入」（2026-11-13 从手机端移植）
                com.lm.player.core.designsystem.component.PlaylistMembership.add(targetPlaylist.id, songToAdd)
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
                        // 补齐歌单封面（最多拼 4 张，与手机端一致）
                        if (songToAdd.coverUrl.isNotBlank()) {
                            database.playlistDao().getPlaylistById(targetPlaylist.id)?.let { plEntity ->
                                val existingCovers = plEntity.coverUrl.split("|").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                                if (!existingCovers.contains(songToAdd.coverUrl) && existingCovers.size < 4) {
                                    existingCovers.add(songToAdd.coverUrl)
                                    database.playlistDao().insertPlaylist(plEntity.copy(coverUrl = existingCovers.joinToString("|")))
                                }
                            }
                        }

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
                            com.lm.player.core.designsystem.component.AppToast.makeText(
                                this@MainActivity,
                                "已将《${songToAdd.title}》加入「${targetPlaylist.name}」",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleAddToPlaylist error", e)
                        withContext(Dispatchers.Main) {
                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "加入歌单失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                                val res = protocol.createCustomPlaylist(playlistName, coverUrl = songToAdd.coverUrl)
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

            // ==================== 多选「加入歌单」（2026-11-14） ====================
            // 所有带多选的列表共用：PlaylistBatchOps 负责选歌单 / 进度 / 结果窗口，这里负责真正写入。
            // 服务器歌单：一次读出整份 → 逐首核对已存在 → 一次写回（LemonMusicProtocol.addTracksToCustomPlaylistDetailed）；
            // 本地歌单：查本机关联表判断已存在。加入成功的歌再写进本机曲库与歌单关联（与单首加入同一份数据）。
            val batchAddSongsToPlaylist: suspend (UnifiedPlaylist, List<UnifiedSong>, (Float, String) -> Unit) -> List<com.lm.player.core.model.PlaylistAddOutcome> =
                { target, songs, onProgress ->
                    withContext(Dispatchers.IO) {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                            ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                        val isServerPl = target.isOnline
                        var outcomes: List<com.lm.player.core.model.PlaylistAddOutcome> = if (isServerPl) {
                            if (active == null) {
                                songs.map {
                                    com.lm.player.core.model.PlaylistAddOutcome(it, com.lm.player.core.model.PlaylistAddStatus.FAILED, "没有连接服务器，不能加入服务器歌单")
                                }
                            } else {
                                val protocol = LemonMusicProtocol(
                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                    active.serverUrl,
                                    active.username,
                                    active.tokenOrApiKey
                                )
                                protocol.addTracksToCustomPlaylistDetailed(target.id, songs) { f, step -> onProgress(f * 0.8f, step) }
                                    .getOrElse { e ->
                                        songs.map {
                                            com.lm.player.core.model.PlaylistAddOutcome(
                                                it, com.lm.player.core.model.PlaylistAddStatus.FAILED, e.message ?: "读取服务器歌单失败"
                                            )
                                        }
                                    }
                            }
                        } else {
                            onProgress(0.05f, "核对歌单中已有的歌…")
                            val existingIds = runCatching { database.playlistDao().getSongsForPlaylist(target.id).map { it.id }.toHashSet() }
                                .getOrDefault(HashSet())
                            val seen = HashSet<String>()
                            songs.map { s ->
                                when {
                                    s.id in existingIds -> com.lm.player.core.model.PlaylistAddOutcome(s, com.lm.player.core.model.PlaylistAddStatus.EXISTED, "歌单里已经有这首歌")
                                    !seen.add(s.id) -> com.lm.player.core.model.PlaylistAddOutcome(s, com.lm.player.core.model.PlaylistAddStatus.EXISTED, "选中的歌里重复了")
                                    else -> com.lm.player.core.model.PlaylistAddOutcome(s, com.lm.player.core.model.PlaylistAddStatus.ADDED)
                                }
                            }
                        }

                        // 加入成功的歌：写进本机曲库与歌单关联
                        val saveStart = if (isServerPl) 0.8f else 0.1f
                        val toSave = outcomes.filter { it.status == com.lm.player.core.model.PlaylistAddStatus.ADDED }
                        val localFailures = HashMap<String, String>()
                        toSave.forEachIndexed { i, o ->
                            val s = o.song
                            try {
                                val existing = database.songDao().getSongById(s.id)
                                val effectiveServerId = when {
                                    existing != null && existing.serverId.isNotBlank() && existing.serverId != "lemon_online" -> existing.serverId
                                    s.serverId.isNotBlank() && s.serverId != "lemon_online" -> s.serverId
                                    active != null -> active.id
                                    else -> "local_storage"
                                }
                                database.songDao().insertSongs(
                                    listOf(
                                        SongEntity(
                                            id = s.id,
                                            title = s.title.ifBlank { "未知曲目" },
                                            artist = s.artist.ifBlank { "未知歌手" },
                                            artistId = s.artistId.ifBlank { "artist_${s.artist.hashCode()}" },
                                            album = s.album.ifBlank { "单曲精选" },
                                            albumId = s.albumId.ifBlank { "album_${s.album.hashCode()}" },
                                            durationMs = s.durationMs,
                                            coverUrl = s.coverUrl.ifBlank { existing?.coverUrl.orEmpty() },
                                            streamUrl = s.streamUrl.ifBlank { existing?.streamUrl.orEmpty() },
                                            serverId = effectiveServerId,
                                            localFilePath = existing?.localFilePath ?: s.localFilePath,
                                            downloadStatus = existing?.downloadStatus ?: s.downloadStatus,
                                            bitRate = s.bitRate,
                                            format = s.format,
                                            isFavorite = existing?.isFavorite == true || s.isFavorite,
                                            relativeFolderPath = s.rawMetaJson ?: existing?.relativeFolderPath ?: s.relativeFolderPath,
                                            addedTimestamp = existing?.addedTimestamp?.takeIf { it > 0L } ?: System.currentTimeMillis()
                                        )
                                    )
                                )
                                database.playlistDao().addSongToPlaylist(
                                    com.lm.player.core.database.entity.PlaylistSongEntity(playlistId = target.id, songId = s.id)
                                )
                            } catch (e: Exception) {
                                Log.w("MainActivity", "batch add: save locally failed for ${s.title}", e)
                                // 本地歌单只存在本机：存不进去就是失败；服务器歌单已经写进服务器，本机记录缺了不影响
                                if (!isServerPl) localFailures[s.id] = "保存到本机失败：${e.message ?: "未知错误"}"
                            }
                            onProgress(saveStart + (1f - saveStart) * (i + 1) / toSave.size, "保存到本机 ${i + 1}/${toSave.size}")
                        }
                        if (localFailures.isNotEmpty()) {
                            outcomes = outcomes.map { o ->
                                localFailures[o.song.id]?.let { o.copy(status = com.lm.player.core.model.PlaylistAddStatus.FAILED, reason = it) } ?: o
                            }
                        }
                        outcomes.forEach { o ->
                            if (o.status != com.lm.player.core.model.PlaylistAddStatus.FAILED) {
                                com.lm.player.core.designsystem.component.PlaylistMembership.add(target.id, o.song)
                            }
                        }
                        if (toSave.isNotEmpty()) {
                            if (isServerPl) {
                                active?.let { syncServerPlaylists(it) }
                            } else {
                                runCatching {
                                    database.playlistDao().updateSongCount(target.id)
                                    database.playlistDao().getPlaylistById(target.id)?.let { plEntity ->
                                        val covers = plEntity.coverUrl.split("|").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                                        toSave.map { it.song.coverUrl }.filter { it.isNotBlank() }.forEach { c ->
                                            if (covers.size < 4 && !covers.contains(c)) covers.add(c)
                                        }
                                        if (covers.joinToString("|") != plEntity.coverUrl) {
                                            database.playlistDao().insertPlaylist(plEntity.copy(coverUrl = covers.joinToString("|")))
                                        }
                                    }
                                }
                            }
                        }
                        onProgress(1f, "完成")
                        outcomes
                    }
                }

            // 多选「加入歌单」里的「新建歌单」：有服务器且不是本地模式时建在服务器上，否则建在本机
            val createPlaylistForBatch: suspend (String) -> UnifiedPlaylist? = { playlistName ->
                withContext(Dispatchers.IO) {
                    val isCurrentLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                    val active = if (isCurrentLocalMode) null else (serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC })
                    var newPlId = "pl_${System.currentTimeMillis()}"
                    var isOnlinePl = false
                    if (active != null && !com.lm.player.core.source.ServerLinkState.isLost) {
                        runCatching {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl,
                                active.username,
                                active.tokenOrApiKey
                            )
                            protocol.createCustomPlaylist(playlistName).getOrNull()?.let {
                                newPlId = it.id
                                isOnlinePl = true
                            }
                        }
                    }
                    val serverIdForPl = if (isOnlinePl && active != null) active.id else "local_storage"
                    runCatching {
                        database.playlistDao().insertPlaylist(
                            com.lm.player.core.database.entity.PlaylistEntity(
                                id = newPlId,
                                name = playlistName,
                                serverId = serverIdForPl,
                                isOnline = isOnlinePl,
                                songCount = 0
                            )
                        )
                        UnifiedPlaylist(id = newPlId, name = playlistName, isOnline = isOnlinePl, serverId = serverIdForPl, songCount = 0)
                    }.getOrNull()
                }
            }
            LaunchedEffect(Unit) {
                com.lm.player.core.designsystem.component.PlaylistBatchOps.addHandler = batchAddSongsToPlaylist
                com.lm.player.core.designsystem.component.PlaylistBatchOps.createHandler = createPlaylistForBatch
            }
            LaunchedEffect(playlistsList, serversList) {
                com.lm.player.core.designsystem.component.PlaylistBatchOps.playlists.value = playlistsList.filter {
                    !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_")
                }
                com.lm.player.core.designsystem.component.PlaylistBatchOps.serverConfigured.value =
                    serversList.any { it.type == ServerType.LEMON_MUSIC }
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
                // 首次启动的平台向导尚未完成时不自动播放：向导是模态的，此间不应擅自出声
                if (platformMode == null) return@LaunchedEffect
                if (!autoPlayOnStartup || hasAutoPlayedOnStartup) return@LaunchedEffect
                // 启动提速：快速通道已直接续播（本地文件），不再按曲库匹配重复续播
                if (fastResumeResult.await()) {
                    hasAutoPlayedOnStartup = true
                    return@LaunchedEffect
                }
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
            // 2026-11-25：在线时「最近播放」整份用服务器那 100 首（三端一致），本机播放也写回服务器；
            // 本地模式不连服务器，播放先攒着，回到在线再补推（见 UserDataMirror）
            LaunchedEffect(serversList, activeServerName) {
                val inLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                val active = if (inLocalMode) null else (
                    serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                    )
                com.lm.player.core.network.UserDataMirror.setServer(this@MainActivity, active)
                if (active == null) return@LaunchedEffect
                // 启动提速：服务器最近播放同步等启动闸门放行后再做
                com.lm.player.core.startup.StartupGate.await()
                withContext(Dispatchers.IO) {
                    runCatching {
                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                        val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                        com.lm.player.core.network.UserDataMirror.pullRecent(this@MainActivity, protocol)
                    }
                }
            }

            // 歌曲切换时动态从音乐文件/NAS提取解析歌词 (支持秒级内存预载与防并发竞争)
            LaunchedEffect(currentSong?.id) {
                val targetSong = currentSong ?: return@LaunchedEffect
                // 播客单集没有歌词，不去各平台匹配
                if (com.lm.player.core.podcast.PodcastSongs.isPodcast(targetSong)) {
                    currentLyrics = LyricResult(emptyList())
                    return@LaunchedEffect
                }
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

            // 监听 ExoPlayer 在线容灾回退与中途断流无缝断点续播及音质降级
            var lastStreamRetrySongId by remember { mutableStateOf("") }
            var lastStreamRetryTimeMs by remember { mutableStateOf(0L) }
            var lastStreamRetryQualityIdx by remember { mutableStateOf(0) }
            DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("MainActivity", "Player Error encountered: ${error.message}", error)
                        val targetSong = currentSong ?: return
                        val resumePos = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)

                        lifecycleScope.launch(Dispatchers.IO) {
                            // 1. 优先尝试切换至本地离线音频文件并保留当前播放进度（严格核对版本与时长）
                            // 纯异步 IO 线程池检测，杜绝主线程磁盘 stat 阻塞与单线程池泄漏 (TV-P0-02)
                            if (autoFallbackToLocal) {
                                val fallbackCandidates = songList + completedDownloadedSongs
                                val matchedLocalSong = fallbackCandidates.firstOrNull {
                                    val hasFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
                                    hasFile && (it.id == targetSong.id || SongMatchingResolver.isSongMatch(
                                        it.title, it.artist, it.durationMs,
                                        targetSong.title, targetSong.artist, targetSong.durationMs,
                                        it.album, targetSong.album
                                    ))
                                }
                                if (matchedLocalSong != null && matchedLocalSong.localFilePath != targetSong.localFilePath) {
                                    withContext(Dispatchers.Main) {
                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "在线音频缓冲受阻，已无缝切换至本地离线版本", Toast.LENGTH_SHORT).show()
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
                                    }
                                    return@launch
                                }
                            }

                            // 1.5 C-12：链接过期（401/403/410）不是音质问题——按原音质重新取链并原位续播，
                            //     不再直接降音质并提示「已降至 xxx」。同一首 60 秒内只重试一次。
                            val expiredCode = PlaybackQueueManager.expiredLinkHttpCode(error)
                            if (expiredCode != null && PlaybackQueueManager.tryClaimLinkRefresh(targetSong.id)) {
                                Log.w("MainActivity", "Stream link expired (HTTP $expiredCode) for ${targetSong.title}, re-resolving at same quality")
                                withContext(Dispatchers.Main) {
                                    PlaybackQueueManager.playSong(
                                        targetSong = targetSong,
                                        context = this@MainActivity,
                                        startPositionMs = resumePos,
                                        forceRefresh = true
                                    )
                                }
                                return@launch
                            }

                            // 2. 音质降级与换源重试：若在线流或高音质(Hi-Res/FLAC)播放中途断流/无法缓冲，
                            // 参考柠檬音乐机制按音质候选链 (flac24bit -> flac -> 320k -> 128k) 依次换源降低音质重试并提示用户
                            val preferredQuality = LemonMusicProtocol.getPreferredStreamQuality(this@MainActivity)
                            val fallbackQualities = LemonMusicProtocol.getFallbackQualities(preferredQuality)
                            val currentDowngradeIdx = if (lastStreamRetrySongId == targetSong.id) lastStreamRetryQualityIdx else 0
                            val nextQualityIdx = currentDowngradeIdx + 1
                            if (nextQualityIdx < fallbackQualities.size) {
                                withContext(Dispatchers.Main) {
                                    lastStreamRetrySongId = targetSong.id
                                    lastStreamRetryTimeMs = System.currentTimeMillis()
                                    lastStreamRetryQualityIdx = nextQualityIdx
                                    val nextQualityKey = fallbackQualities[nextQualityIdx]
                                    val nextQualityLabel = AudioQuality.fromKey(nextQualityKey).label
                                    Log.i("MainActivity", "Auto-downgrading quality for ${targetSong.title} to $nextQualityKey ($nextQualityLabel) at ${resumePos}ms")
                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "当前音质无法缓冲，已自动为您换源降至【$nextQualityLabel】播放", Toast.LENGTH_SHORT).show()
                                    PlaybackQueueManager.playSong(
                                        targetSong = targetSong,
                                        context = this@MainActivity,
                                        startPositionMs = resumePos,
                                        forceRefresh = true,
                                        overrideQuality = nextQualityKey
                                    )
                                }
                                return@launch
                            }

                            // 3. 若降级仍失败，尝试回退至服务器资料库内同版本已归档曲目并保留播放进度
                            if (autoFallbackToLocal) {
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
                                    withContext(Dispatchers.Main) {
                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "在线音源缓冲受阻，已自动切换至服务器资料库同名版本", Toast.LENGTH_SHORT).show()
                                        PlaybackQueueManager.playSong(
                                            targetSong = matchedServerSong,
                                            context = this@MainActivity,
                                            startPositionMs = resumePos
                                        )
                                    }
                                    return@launch
                                }
                            }

                            withContext(Dispatchers.Main) {
                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "当前歌曲《${targetSong.title.ifBlank { "未知" }}》音频无法缓冲，请检查网络或服务端连接", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                exoPlayer?.addListener(listener)
                onDispose {
                    exoPlayer?.removeListener(listener)
                }
            }

            // ⚠️ LocalPlatformMode 必须挂在这一层（而不是下面只包住 AdaptiveAppScaffold 的
            // 第二层 provider）：全屏播放页 FullscreenPlayerSheet 在该层之外，
            // 挂低了它内部的焦点修饰符就读不到车机模式。
            CompositionLocalProvider(
                LocalAppDimensions provides appDimensions,
                LocalPlatformMode provides effectivePlatformMode
            ) {
                ZDSPlayerTheme(themeMode = currentThemeMode) {
                    // 全局触屏边缘向右滑动返回手势监听 (适配现代全面屏返回手势)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    val edgeThreshold = 44.dp.toPx()
                                    while (true) {
                                        // 用 Main 阶段（而不是 Initial）：Main 阶段是**子节点先处理、父节点后处理**，
                                        // 因此这里读到的是子节点处理完之后的消费状态 —— 这是判断
                                        // "这次拖动是否已被子节点认领" 的唯一可靠时机。
                                        // Initial 阶段父节点先收到，isConsumed 恒为 false，判断会完全失效。
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                                        if (down.position.x <= edgeThreshold) {
                                            var totalDx = 0f
                                            var totalDy = 0f
                                            var triggered = false
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Main)
                                                val drag = event.changes.firstOrNull { it.id == down.id } ?: break

                                                // 子节点（进度条 / 横向滚动区）已经认领了这次拖动 → 放弃返回手势。
                                                // 否则在屏幕左缘按住播放进度条往右拖，会被判成"边缘右滑返回"而直接退出页面。
                                                // 消费标记在同一次事件内对命中路径上的所有节点可见，所以这里立刻就能读到，
                                                // 远早于 totalDx 累计到 65dp 的触发阈值。
                                                if (drag.isConsumed) break

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
                        var listenTickCount by remember { mutableIntStateOf(0) }
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
                                    if (isPlaying) {
                                        listenTickCount++
                                        if (listenTickCount >= 20) {
                                            listenTickCount = 0
                                            val statsPrefs = this@MainActivity.getSharedPreferences("lemon_listening_stats", Context.MODE_PRIVATE)
                                            val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
                                            val todayKey = "today_listen_seconds_$todayStr"
                                            val curTotal = statsPrefs.getLong("total_listen_seconds", 0L)
                                            val curToday = statsPrefs.getLong(todayKey, 0L)
                                            statsPrefs.edit()
                                                .putLong("total_listen_seconds", curTotal + 10L)
                                                .putLong(todayKey, curToday + 10L)
                                                .apply()
                                        }
                                    }
                                    delay(if (isPlaying) 500L else 1000L)
                                }
                            }
                        }

                        // 响应式脚手架 (顶部导航栏 + 左侧常驻黑胶播控大卡 + 右侧主内容舞台；全屏播放器打开时自动冻结底层焦点)
                        // 性能优化 A1：全屏播放页进场动画结束、且未被下拉时，它会完全盖住主界面 (背景不透明)。
                        // HWUI 没有遮挡剔除，被盖住的主界面 (列表、封面、黑胶卡片) 原本仍每帧照画一遍，
                        // 这里在绘制阶段直接跳过；状态只在 draw 阶段读取，不会引起重组。焦点冻结逻辑不变。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    val covered = isFullPlayerVisible && fullPlayerEnterSettled && fullPlayerSheetCovering &&
                                        // 与主界面卡片之间收拢 / 翻转时，播放页背景完全不透明之前主界面要照画
                                        (!com.lm.player.feature.player.tv.PlayerCardHandoff.active ||
                                            com.lm.player.feature.player.tv.PlayerCardHandoff.coversHome(com.lm.player.feature.player.tv.PlayerCardHandoff.progress))
                                    if (!covered) drawContent()
                                }
                        ) {
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
                                        refreshServerFavorites()
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
                                                refreshServerFavorites()
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
                            com.lm.player.core.source.ServerPresence.clearAutoLockForManualLocal()
                            activeServerName = "本地 · 已下载"
                            localModeDueToOffline = false
                            activeServerId = ""
                            lifecycleScope.launch(Dispatchers.IO) {
                                database.serverDao().clearActiveServer()
                            }
                        },
                        onSelectServer = { selectedServer ->
                            com.lm.player.core.source.ServerPresence.unlockToOnline()
                            activeServerName = selectedServer.name
                            activeServerId = selectedServer.id
                            localModeDueToOffline = false
                            lifecycleScope.launch(Dispatchers.IO) {
                                com.lm.player.core.source.ServerLinkState.probeNow()
                                database.serverDao().setActiveServer(selectedServer.id)
                                syncServerPlaylists(selectedServer)
                                syncServerSongs(selectedServer)
                            }
                        },
                        onSyncNow = {
                            val active = serversList.firstOrNull { it.isCurrentActive }
                            if (active != null) {
                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "正在从柠檬音乐同步全量曲库...", Toast.LENGTH_SHORT).show()
                                syncServerPlaylists(active)
                                syncServerSongs(active)
                                refreshServerFavorites()
                            } else {
                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
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
                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已切换为：随机播放", Toast.LENGTH_SHORT).show()
                                }
                                isShuffle -> {
                                    PlaybackQueueManager.setShuffle(false)
                                    PlaybackQueueManager.setRepeat(true)
                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已切换为：单曲循环", Toast.LENGTH_SHORT).show()
                                }
                                else -> {
                                    PlaybackQueueManager.setShuffle(false)
                                    PlaybackQueueManager.setRepeat(false)
                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已切换为：列表循环", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!isSearchDialogOpen) {
                                // 切页提速：「我的」「发现」进入过一次后保留在组合里，再切回来不用重建（见 KeepAlivePageHost）；
                                // 其余页面与原先一样淡入淡出、离开即销毁。设置 → 播放与外观 →「切页保留页面」可关
                                com.lm.player.ui.KeepAlivePageHost(
                                current = currentScreen,
                                keepAliveKeyOf = { screen ->
                                    when (screen) {
                                        Screen.MINE, Screen.LIBRARY -> "mine"
                                        Screen.HOME -> "home"
                                        else -> null
                                    }
                                },
                                enabled = com.lm.player.core.designsystem.theme.AppUiPrefs.keepPagesAlive
                            ) { targetScreen ->
                                when (targetScreen) {
                                // Screen.MINE 与 Screen.LIBRARY 已融合为统一的「我的 (含在线曲库与本地缓存)」页面

                                Screen.SEARCH -> {
                                    val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                                    // page/limit 透传给服务端，由服务端做翻页；一页拉满即认为还有下一页。
                                    // 搜索：本地音源可用时本地优先（不经服务器），本地无结果且服务器未离线时服务器兜底；都不可用时不提供在线搜索
                                    // TV 按需启动：本机缓存了音源脚本即可本地搜索（目录不需要 JS 引擎；收集状态以便缓存变化时重组）
                                    val localSearchReady = com.lm.player.core.source.SourceAccelerationSession.status.collectAsState().value.isReady ||
                                        com.lm.player.core.source.SourceAccelerationController.scriptsCached.collectAsState().value ||
                                        com.lm.player.core.source.catalog.OfflineCatalogService.isAvailable()
                                    val onlineSearchCallback: (suspend (String, OnlineMusicSource, Int, Int) -> List<UnifiedSong>)? = remember(activeServer, localSearchReady) {
                                        val srv = activeServer
                                        if (srv != null || localSearchReady) {
                                            { keyword, source, page, limit ->
                                                catalogLocalFirst(
                                                    local = { com.lm.player.core.source.catalog.OfflineCatalogService.searchSongs(keyword, source.key, page, limit) },
                                                    server = srv?.let { s ->
                                                        suspend {
                                                            val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                            LemonMusicProtocol(client, s.serverUrl, s.username, s.tokenOrApiKey)
                                                                .searchOnline(keyword, source = source.key, page = page, limit = limit).getOrNull().orEmpty()
                                                        }
                                                    }
                                                )
                                            }
                                        } else null
                                    }
                                    val parsePlaylistCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeServer, localSearchReady) {
                                        val srv = activeServer
                                        if (srv != null || localSearchReady) {
                                            { url, source ->
                                                catalogLocalFirst(
                                                    local = { com.lm.player.core.source.catalog.OfflineCatalogService.parseExternalPlaylist(url, source.key) },
                                                    server = srv?.let { s ->
                                                        suspend {
                                                            val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                            LemonMusicProtocol(client, s.serverUrl, s.username, s.tokenOrApiKey)
                                                                .parseExternalPlaylist(urlOrId = url, source = source.key).getOrNull().orEmpty()
                                                        }
                                                    }
                                                )
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
                                        isServerConnected = (activeServer != null || com.lm.player.core.source.catalog.OfflineCatalogService.isAvailable()),
                                        contentPadding = innerPadding,
                                        initialQuery = widgetSearchQuery,
                                        onDismiss = { navigateToScreen(rootScreen) }
                                    )
                                }

                                Screen.HOME -> {
                                    val isLocalMode = activeServerName.contains("本地")
                                    val activeServer = if (isLocalMode) null else (serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC })

                                    // 首页规则（用户 2026-11-07 确认）：本地音源可用时，不论服务器在线、离线还是已删除，都显示和在线一样的
                                    // 发现页（数据来自本地音源）；本地音源不可用时，服务器在线显示在线首页，否则显示纯本地首页。
                                    val detachedLocalSource by com.lm.player.core.source.SourceAccelerationController.detached.collectAsState()
                                    // TV 按需启动：本机缓存了音源脚本就直接显示发现页（目录走本地 Kotlin 目录服务，不需要先加载 JS 引擎），
                                    // 不再先显示本地首页、等引擎加载好再整页切换
                                    val localScriptsCached by com.lm.player.core.source.SourceAccelerationController.scriptsCached.collectAsState()
                                    val localSourceReady = com.lm.player.core.source.SourceAccelerationSession.status.collectAsState().value.isReady || localScriptsCached
                                    if (activeServer != null || localSourceReady || detachedLocalSource) {
                                        LemonDiscoverHomeScreen(
                                            serverName = if (activeServer == null) "本地音源模式" else activeServerName,
                                            configuredServers = serversList,
                                            currentSource = currentOnlineSource,
                                            onSourceChange = { newSrc ->
                                                currentOnlineSource = newSrc
                                                onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                            },
                                                                allCachedSongs = remember(songList) { LocalSongsDedupeCache.get(songList) },
                                            activeDownloadTasks = activeDownloadTasksProvider,
                                            activeDownloadCount = activeDownloadCount,
                                            currentPlayingSong = currentSong,
                                            isPlaying = isPlaying,
                                            onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                            onDownloadSong = handleDownloadSong,
                                            onDownloadSongWithOptions = handleDownloadWithOptions,
                                            onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                            onSelectLocalServer = {
                                                com.lm.player.core.source.ServerPresence.clearAutoLockForManualLocal()
                                                activeServerName = "本地 · 已下载"
                                                activeServerId = ""
                                                localModeDueToOffline = false
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    database.serverDao().clearActiveServer()
                                                }
                                            },
                                            onSelectServer = { selectedServer ->
                                                com.lm.player.core.source.ServerPresence.unlockToOnline()
                                                activeServerName = selectedServer.name
                                                activeServerId = selectedServer.id
                                                localModeDueToOffline = false
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    com.lm.player.core.source.ServerLinkState.probeNow()
                                                    database.serverDao().setActiveServer(selectedServer.id)
                                                    syncServerPlaylists(selectedServer)
                                                    syncServerSongs(selectedServer)
                                                }
                                            },
                                            onSyncNow = {
                                                val srv = activeServer
                                                if (srv != null) {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "正在从柠檬音乐同步全量曲库...", Toast.LENGTH_SHORT).show()
                                                    syncServerSongs(srv)
                                                    refreshServerFavorites()
                                                } else {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "当前为本地音源模式（服务器离线或已删除）", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onOpenDownloads = { navigateToScreen(Screen.DOWNLOADS) },
                                            onGoToSettings = { navigateToScreen(Screen.SETTINGS) },
                                            onSearchClick = { navigateToScreen(Screen.SEARCH) },
                                            // 发现页数据：本地音源优先，本地失败或空结果、且服务器未离线时服务器兜底（catalogLocalFirst）
                                            onFetchDiscoverPlaylists = { src ->
                                                val proto = activeServer?.let { LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), it.serverUrl, it.username, it.tokenOrApiKey) }
                                                catalogLocalFirst(
                                                    local = { com.lm.player.core.source.catalog.OfflineCatalogService.recommendPlaylists(src.key, 1, 60) },
                                                    server = proto?.let { p -> suspend { p.getDiscoverRecommendPlaylists(source = src.key, page = 1, limit = 60).getOrNull().orEmpty() } }
                                                )
                                            },
                                            onFetchDiscoverToplists = { src ->
                                                val proto = activeServer?.let { LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), it.serverUrl, it.username, it.tokenOrApiKey) }
                                                catalogLocalFirst(
                                                    local = { com.lm.player.core.source.catalog.OfflineCatalogService.toplists(src.key) },
                                                    server = proto?.let { p -> suspend { p.getDiscoverToplists(source = src.key).getOrNull().orEmpty() } }
                                                )
                                            },
                                            onFetchDiscoverNewSongs = { src ->
                                                val proto = activeServer?.let { LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), it.serverUrl, it.username, it.tokenOrApiKey) }
                                                catalogLocalFirst(
                                                    local = { com.lm.player.core.source.catalog.OfflineCatalogService.newSongs(src.key, 60) },
                                                    server = proto?.let { p -> suspend { p.getDiscoverNewSongs(source = src.key, limit = 60).getOrNull().orEmpty() } }
                                                )
                                            },
                                            onFetchDiscoverNewAlbums = { src ->
                                                val proto = activeServer?.let { LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), it.serverUrl, it.username, it.tokenOrApiKey) }
                                                catalogLocalFirst(
                                                    local = { com.lm.player.core.source.catalog.OfflineCatalogService.newAlbums(src.key) },
                                                    server = proto?.let { p -> suspend { p.getDiscoverNewAlbums(source = src.key).getOrNull().orEmpty() } }
                                                )
                                            },
                                            onParseExternalPlaylist = { url, src ->
                                                val proto = activeServer?.let { LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), it.serverUrl, it.username, it.tokenOrApiKey) }
                                                catalogLocalFirst(
                                                    local = { com.lm.player.core.source.catalog.OfflineCatalogService.parseExternalPlaylist(url, src.key) },
                                                    server = proto?.let { p -> suspend { p.parseExternalPlaylist(urlOrId = url, source = src.key).getOrNull().orEmpty() } }
                                                )
                                            },
                                            onFetchCollectionSongs = { collectionId, src ->
                                                val proto = activeServer?.let { LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), it.serverUrl, it.username, it.tokenOrApiKey) }
                                                if (collectionId.startsWith("lemon_toplist_")) {
                                                    val rawId = collectionId.substringAfterLast('_')
                                                    catalogLocalFirst(
                                                        local = { com.lm.player.core.source.catalog.OfflineCatalogService.toplistSongs(rawId, src.key) },
                                                        server = proto?.let { p -> suspend { p.getDiscoverToplistSongs(toplistId = rawId, source = src.key).getOrNull().orEmpty() } }
                                                    )
                                                } else if (collectionId.startsWith("lemon_rec_") || collectionId.startsWith("lemon_discover_")) {
                                                    // 在线推荐歌单 / 专辑：本地音源优先，服务器兜底
                                                    catalogLocalFirst(
                                                        local = { com.lm.player.core.source.catalog.OfflineCatalogService.collectionSongs(collectionId, src.key) },
                                                        server = proto?.let { p -> suspend { p.getPlaylistSongs(collectionId).getOrNull().orEmpty() } }
                                                    )
                                                } else {
                                                    // 服务器自己的歌单等：只能问服务器
                                                    proto?.getPlaylistSongs(collectionId)?.getOrNull().orEmpty()
                                                }
                                            },
                                            onSubViewActiveChange = { subViewActiveByPage[Screen.HOME.name] = it },
                                            contentPadding = innerPadding
                                        )
                                    } else {
                                        LocalMusicHomeScreen(
                                            serverName = activeServerName,
                                            recentSongs = remember(songList) { LocalSongsDedupeCache.get(songList) },
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
                                                com.lm.player.core.source.ServerPresence.clearAutoLockForManualLocal()
                                                activeServerName = "本地 · 已下载"
                                                localModeDueToOffline = false
                                                activeServerId = ""
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    database.serverDao().clearActiveServer()
                                                }
                                            },
                                            onSelectServer = { selectedServer ->
                                                com.lm.player.core.source.ServerPresence.unlockToOnline()
                                                activeServerName = selectedServer.name
                                                activeServerId = selectedServer.id
                                                localModeDueToOffline = false
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    com.lm.player.core.source.ServerLinkState.probeNow()
                                                    database.serverDao().setActiveServer(selectedServer.id)
                                                    syncServerSongs(selectedServer)
                                                }
                                            },
                                            onSyncNow = {
                                                val active = serversList.firstOrNull { it.isCurrentActive }
                                                if (active != null) {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "正在从柠檬音乐同步曲库与收藏...", Toast.LENGTH_SHORT).show()
                                                    syncServerSongs(active)
                                                    refreshServerFavorites()
                                                } else {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
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
                                            onSubViewActiveChange = { subViewActiveByPage[Screen.HOME.name] = it },
                                            contentPadding = innerPadding
                                        )
                                    }
                                }

                                Screen.MINE, Screen.LIBRARY -> {
                                    // 服务器确认离线 / 长时间不稳定锁定 / 已删除：「我的」页面自动按本地模式展示
                                    val presenceMode = com.lm.player.core.source.ServerPresence.mode.collectAsState().value
                                    val libraryAutoLocked = presenceMode == com.lm.player.core.source.ServerPresence.Mode.LOCKED_LOCAL
                                    val libraryServerLost = presenceMode == com.lm.player.core.source.ServerPresence.Mode.OFFLINE || libraryAutoLocked
                                    val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载") || libraryServerLost
                                    val activeConfig = if (isLocalMode) null else (serversList.firstOrNull { it.isCurrentActive } ?: serversList.firstOrNull())
                                    // 切页提速：这一整段（全库过滤 + 按路径 / 歌名歌手去重）原先每次进「我的」都在主线程重算，
                                    // 而且每次都产出新列表，导致资料库页的歌手 / 专辑 / 文件夹统计也跟着整库重算。
                                    // 现在按输入实例缓存：曲库与下载列表没变化时，切回「我的」直接复用同一份结果。
                                    val librarySongs = remember(songList, completedDownloadedSongs, activeConfig, isLocalMode) { LibrarySongsCache.get(songList, completedDownloadedSongs, activeConfig, isLocalMode) {
                                        if (isLocalMode || activeConfig == null) {
                                            val rawLocal = songList.filter {
                                                it.downloadStatus == DownloadStatus.DOWNLOADED ||
                                                it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                                                !it.localFilePath.isNullOrBlank()
                                            }
                                            val byPath = LinkedHashMap<String, UnifiedSong>(rawLocal.size)
                                            for (song in rawLocal) {
                                                val key = song.localFilePath?.trim()?.replace('\\', '/')?.lowercase()?.takeIf { it.isNotBlank() }
                                                    ?: "id:${song.id}"
                                                val existing = byPath[key]
                                                if (existing == null) {
                                                    byPath[key] = song
                                                } else {
                                                    val existingIsLocalOnly = existing.serverId in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                                                    val incomingIsServer = song.serverId.isNotBlank() && song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                                                    if (existingIsLocalOnly && incomingIsServer) {
                                                        byPath[key] = song
                                                    }
                                                }
                                            }
                                            byPath.values.distinctBy { it.id }
                                        } else {
                                            // 在线模式：展示属于当前服务器的曲目、收藏曲目，并融合本地独有曲目，彻底杜绝本地歌曲重复两首的问题
                                            // 「曲库歌曲」只放两类：服务器曲库里真实存在的文件（流地址是 /api/play/local*），以及本机文件。
                                            // 此前凡是挂在当前服务器下或已收藏的都算进来，而收藏/加歌单/提交服务器缓存的
                                            // **在线歌曲**也会以当前服务器 id 写入本地歌曲表，于是它们以「仅服务器」显示在曲库里，
                                            // 服务端删掉文件后也一直残留（重装后收藏同步又会写回）。在线收藏只在「我喜欢」里展示，与手机端一致。
                                            val rawCandidates = songList.filter {
                                                val isLocalFile = it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                                                    it.downloadStatus == DownloadStatus.DOWNLOADED ||
                                                    !it.localFilePath.isNullOrBlank()
                                                val belongsToServer = it.serverId == activeConfig.id ||
                                                    it.serverId == activeConfig.serverUrl ||
                                                    (activeConfig.type == ServerType.LEMON_MUSIC && it.serverId == "lemon_music")
                                                val isServerLibraryTrack = it.streamUrl.contains("/api/play/local")
                                                isLocalFile || (belongsToServer && isServerLibraryTrack)
                                            }
                                            // 1. 本地物理文件路径去重
                                            val byPath = LinkedHashMap<String, UnifiedSong>(rawCandidates.size)
                                            val noPathList = mutableListOf<UnifiedSong>()
                                            for (song in rawCandidates) {
                                                val normPath = song.localFilePath?.trim()?.replace('\\', '/')?.lowercase()?.takeIf { it.isNotBlank() }
                                                if (normPath != null) {
                                                    val existing = byPath[normPath]
                                                    if (existing == null) {
                                                        byPath[normPath] = song
                                                    } else {
                                                        val existingIsLocalOnly = existing.serverId in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                                                        val incomingIsServer = song.serverId.isNotBlank() && song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                                                        if (existingIsLocalOnly && incomingIsServer) {
                                                            byPath[normPath] = song.copy(
                                                                localFilePath = existing.localFilePath ?: song.localFilePath,
                                                                downloadStatus = DownloadStatus.DOWNLOADED
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    noPathList.add(song)
                                                }
                                            }
                                            val mergedByPath = byPath.values + noPathList
                                            // 2. 歌名 + 歌手去重：服务器歌曲与本地歌曲是同一首时合并，保留服务器歌曲并挂上本地文件。
                                            // 第二十一节：原先只有「本地在前、服务器在后」才合并；服务器在前时本地那条被直接丢掉，
                                            // 结果服务器歌曲显示「仅服务器」、本地文件在在线曲库里消失。现在与顺序无关，
                                            // 并且用与数据库匹配相同的规则 (isSongMatch) 判断是否同一首；不是同一首 (如不同版本) 两条都保留。
                                            val localOnlyIds = listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                                            val byTitleArtist = LinkedHashMap<String, MutableList<UnifiedSong>>(mergedByPath.size)
                                            for (song in mergedByPath) {
                                                val t = SongMatchingResolver.normalizeTrackTitle(song.title).lowercase()
                                                val a = SongMatchingResolver.normalizeArtist(song.artist).lowercase()
                                                val key = if (t.isNotBlank() && a.isNotBlank()) "$t|||$a" else "id:${song.id}"
                                                val bucket = byTitleArtist.getOrPut(key) { ArrayList(1) }
                                                val songIsLocalOnly = song.serverId in localOnlyIds
                                                val songHasLocal = !song.localFilePath.isNullOrBlank()
                                                // 「另一方」：一方是纯本地、另一方是还没挂本地文件的服务器歌曲 (返回 本地 to 服务器)
                                                fun crossPair(other: UnifiedSong): Pair<UnifiedSong, UnifiedSong>? {
                                                    val otherIsLocalOnly = other.serverId in localOnlyIds
                                                    return when {
                                                        songIsLocalOnly && !otherIsLocalOnly && songHasLocal && other.localFilePath.isNullOrBlank() -> song to other
                                                        !songIsLocalOnly && otherIsLocalOnly && !other.localFilePath.isNullOrBlank() && song.localFilePath.isNullOrBlank() -> other to song
                                                        else -> null
                                                    }
                                                }
                                                var hasCrossCandidate = false
                                                val partnerIdx = bucket.indexOfFirst { other ->
                                                    val pair = crossPair(other) ?: return@indexOfFirst false
                                                    hasCrossCandidate = true
                                                    SongMatchingResolver.isSongMatch(
                                                        pair.first.title, pair.first.artist, pair.first.durationMs,
                                                        pair.second.title, pair.second.artist, pair.second.durationMs,
                                                        pair.first.album, pair.second.album
                                                    )
                                                }
                                                if (partnerIdx < 0) {
                                                    // 同类重复 (两条本地 / 两条服务器 / 服务器已挂了别的文件) 仍与原先一样只保留第一条；
                                                    // 本地与服务器判定为不同版本时两条都显示
                                                    when {
                                                        bucket.isEmpty() || hasCrossCandidate -> bucket.add(song)
                                                        !songIsLocalOnly && bucket.none { it.serverId !in localOnlyIds } -> {
                                                            // 桶里只有本地条目、服务器歌曲已自带本地文件：与原先一致由服务器歌曲顶替
                                                            val idx = bucket.indexOfFirst { it.serverId in localOnlyIds }
                                                            val existing = bucket[idx]
                                                            bucket[idx] = song.copy(
                                                                localFilePath = song.localFilePath ?: existing.localFilePath,
                                                                downloadStatus = if (!existing.localFilePath.isNullOrBlank()) DownloadStatus.DOWNLOADED else song.downloadStatus
                                                            )
                                                        }
                                                        else -> Unit
                                                    }
                                                } else {
                                                    val other = bucket[partnerIdx]
                                                    val (local, server) = if (songIsLocalOnly) song to other else other to song
                                                    bucket[partnerIdx] = server.copy(
                                                        localFilePath = local.localFilePath,
                                                        downloadStatus = DownloadStatus.DOWNLOADED
                                                    )
                                                }
                                            }
                                            byTitleArtist.values.flatten().distinctBy { it.id }
                                        }
                                    } } // LibrarySongsCache
                                    // 「我喜欢的音乐」的数据口径：
                                    //   在线模式 —— 直接以柠檬服务器收藏为准，能对上库内曲目的用库内数据，其余沿用服务器记录；
                                    //   本地/已下载 —— 从服务器收藏中枚举出"已下载"的曲目，没下载的不显示。
                                    // 拉不到服务器收藏时回落到本地 isFavorite 标记，避免收藏卡片整块变空。
                                    val favoriteSongs = remember(
                                        serverFavoriteSongs, librarySongs, completedDownloadedSongs, isLocalMode
                                    ) {
                                        when {
                                            serverFavoriteSongs.isEmpty() -> librarySongs.filter { it.isFavorite }
                                            !isLocalMode -> serverFavoriteSongs.map { fav ->
                                                librarySongs.firstOrNull { it.id == fav.id } ?: fav
                                            }
                                            else -> serverFavoriteSongs.mapNotNull { fav ->
                                                completedDownloadedSongs.firstOrNull { local ->
                                                    local.id == fav.id || SongMatchingResolver.isSongMatch(
                                                        title1 = local.title,
                                                        artist1 = local.artist,
                                                        durationMs1 = local.durationMs,
                                                        title2 = fav.title,
                                                        artist2 = fav.artist,
                                                        durationMs2 = fav.durationMs,
                                                        album1 = local.album,
                                                        album2 = fav.album
                                                    )
                                                }
                                            }.distinctBy { it.id }
                                        }
                                    }
                                    LocalLibraryScreen(
                                        allSongs = librarySongs,
                                        favoriteSongs = favoriteSongs,
                                        downloadedSongs = completedDownloadedSongs,
                                        recentlyPlayedSongs = recentlyPlayedSongs,
                                        playlists = if (isLocalMode) {
                                            playlistsWithCovers.filter { !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") &&
                                                // 服务器离线时不展示服务器歌单，只保留本地自建歌单
                                                !(libraryServerLost && it.isOnline) }
                                        } else {
                                            playlistsWithCovers.filter {
                                                !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") &&
                                                (it.serverId == activeConfig?.id || it.serverId == "lemon_music" || it.serverId.startsWith("lemon_") || it.serverId == activeConfig?.serverUrl || !it.isOnline)
                                            }
                                        },
                                        activeServerConfig = activeConfig,
                                        activeDownloadTasks = activeDownloadTasksProvider,
                                        activeDownloadCount = activeDownloadCount,
                                        currentServerName = when {
                                            libraryAutoLocked && !activeServerName.contains("本地") -> "本地 · 网络不稳定已锁定"
                                            libraryServerLost && !activeServerName.contains("本地") -> "本地 · 服务器离线"
                                            else -> activeServerName
                                        },
                                        configuredServers = serversList,
                                                        currentPlayingSong = currentSong,
                                        isPlaying = isPlaying,
                                        onSelectLocalServer = {
                                            com.lm.player.core.source.ServerPresence.clearAutoLockForManualLocal()
                                            activeServerName = "本地 · 已下载"
                                            localModeDueToOffline = false
                                            activeServerId = ""
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().clearActiveServer()
                                            }
                                        },
                                        onSelectServer = { selectedServer ->
                                            com.lm.player.core.source.ServerPresence.unlockToOnline()
                                            activeServerName = selectedServer.name
                                            activeServerId = selectedServer.id
                                            localModeDueToOffline = false
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                com.lm.player.core.source.ServerLinkState.probeNow()
                                                database.serverDao().setActiveServer(selectedServer.id)
                                                syncServerPlaylists(selectedServer)
                                                syncServerSongs(selectedServer)
                                            }
                                        },
                                        onSyncNow = {
                                            val active = serversList.firstOrNull { it.isCurrentActive } ?: serversList.firstOrNull()
                                            if (active != null) {
                                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "正在从柠檬音乐同步曲库与收藏...", Toast.LENGTH_SHORT).show()
                                                syncServerPlaylists(active)
                                                syncServerSongs(active)
                                                refreshServerFavorites()
                                            } else {
                                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onGoToSettings = { navigateToScreen(Screen.SETTINGS) },
                                        onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                        onDownloadSong = handleDownloadSong,
                                        onDownloadSongWithOptions = handleDownloadWithOptions,
                                        onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            val deletedIds = songsToDelete.map { it.id }.toSet()
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
                                            songList = songList.map { if (it.id in deletedIds) it.copy(localFilePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED) else it }
                                        },
                                        onToggleFavorite = { songToToggle ->
                                            handleToggleFavorite(songToToggle)
                                        },
                                        onOpenDownloads = { navigateToScreen(Screen.DOWNLOADS) },
                                        onRefreshPlaylists = {
                                            if (activeConfig != null) {
                                                syncServerPlaylists(activeConfig)
                                            } else {
                                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "当前处于本地模式，暂无在线歌单", Toast.LENGTH_SHORT).show()
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
                                        // 2026-11-13：把歌曲移出歌单。服务器歌单先改服务器（失败就整体失败、界面不动），
                                        // 再删本机的关联记录（否则下次打开歌单时本机记录又把它合并回来）
                                        onRemoveSongsFromPlaylist = { playlistId, isOnline, songsToRemove, onProgress ->
                                            if (playlistId == "lemon_favorites") {
                                                // 「我的收藏」移出 = 取消收藏：走收藏按钮同一条全链路（本机 + 服务器）
                                                withContext(Dispatchers.Main) {
                                                    songsToRemove.forEach { handleToggleFavorite(it.copy(isFavorite = true)) }
                                                }
                                                onProgress(1f, "完成")
                                                Result.success(songsToRemove.size)
                                            } else withContext(Dispatchers.IO) {
                                                try {
                                                    val serverConfig = activeConfig
                                                        ?: serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                                                    if (isOnline) {
                                                        if (serverConfig == null) {
                                                            return@withContext Result.failure(Exception("没有连接服务器，不能修改服务器歌单"))
                                                        }
                                                        val protocol = LemonMusicProtocol(
                                                            NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                            serverConfig.serverUrl,
                                                            serverConfig.username,
                                                            serverConfig.tokenOrApiKey
                                                        )
                                                        val res = protocol.removeTracksFromCustomPlaylist(playlistId, songsToRemove) { f, step ->
                                                            onProgress(f * 0.85f, step)
                                                        }
                                                        if (res.isFailure) {
                                                            return@withContext Result.failure(res.exceptionOrNull() ?: Exception("移出失败"))
                                                        }
                                                    }
                                                    val localStart = if (isOnline) 0.85f else 0.05f
                                                    songsToRemove.forEachIndexed { i, s ->
                                                        database.playlistDao().removeSongFromPlaylist(playlistId, s.id)
                                                        onProgress(localStart + (1f - localStart) * (i + 1) / songsToRemove.size, "更新本机记录 ${i + 1}/${songsToRemove.size}")
                                                    }
                                                    if (isOnline) {
                                                        // 歌曲数 / 封面以服务器为准：重新同步歌单列表
                                                        serverConfig?.let { syncServerPlaylists(it) }
                                                    } else {
                                                        database.playlistDao().updateSongCount(playlistId)
                                                    }
                                                    Result.success(songsToRemove.size)
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Log.w("MainActivity", "remove songs from playlist failed", e)
                                                    Result.failure(e)
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
                                                // 登记这个歌单里有哪些歌：从它播放的歌在「加入歌单」里显示已加入（服务器歌单不进本地表）
                                                com.lm.player.core.designsystem.component.PlaylistMembership.record(playlistId, rawList + resolved)
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
                                        onSubViewActiveChange = { subViewActiveByPage["mine"] = it },
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
                                            val deletedIds = setOf(songToDelete.id)
                                            downloadEngine.deleteDownloadedSong(songToDelete)
                                            songList = songList.map { if (it.id in deletedIds) it.copy(localFilePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED) else it }
                                        },
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            val deletedIds = songsToDelete.map { it.id }.toSet()
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
                                            songList = songList.map { if (it.id in deletedIds) it.copy(localFilePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED) else it }
                                        },
                                        onReEmbedSong = { songToFix ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val ok = downloadEngine.reEmbedSongMetadata(songToFix)
                                                withContext(Dispatchers.Main) {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, if (ok) "已成功为《${songToFix.title}》重新嵌入封面与歌词" else "重新嵌入失败，文件未找到", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onReEmbedAll = {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                withContext(Dispatchers.Main) {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "正在批量为所有已下载歌曲补全封面与歌词标签...", Toast.LENGTH_SHORT).show()
                                                }
                                                val (success, fail) = downloadEngine.reEmbedAllDownloadedSongs()
                                                withContext(Dispatchers.Main) {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "标签补全完成：成功 $success 首，失败 $fail 首", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        downloadPath = downloadSettings.customDownloadPath.ifBlank { getExternalFilesDir(null)?.absolutePath ?: "" },
                                        onBack = { popScreenOrHome() },
                                        contentPadding = innerPadding
                                    )
                                }

                                Screen.PODCASTS -> {
                                    com.lm.player.core.podcast.TvPodcastScreenHost(
                                        contentPadding = innerPadding,
                                        onExit = {
                                            com.lm.player.core.podcast.PodcastController.resetToHome()
                                            popScreenOrHome()
                                        },
                                        onGoToServerSettings = { navigateToScreen(Screen.SETTINGS) },
                                        onOpenPlayer = { if (currentSong != null) isFullPlayerVisible = true }
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
                                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "正在扫描离线目录并与线上曲库比对同步...", Toast.LENGTH_SHORT).show()
                                                    }
                                                    // 1. 递归扫描该离线目录中的所有音频文件
                                                    val scanned = LocalMediaScanner.scanCustomDirectory(this@MainActivity, newSettings.customDownloadPath, database)
                                                    // 2. 与线上曲库进行物理文件真实性对比与同步
                                                    val updated = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, java.io.File(newSettings.customDownloadPath))
                                                    withContext(Dispatchers.Main) {
                                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "离线目录同步完成：扫描 $scanned 首，匹配同步 $updated 首", Toast.LENGTH_LONG).show()
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
                                                com.lm.player.core.designsystem.component.AppToast.makeText(
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
                                        onPlatformModeChange = { mode ->
                                            // 写 prefs 后更新状态：LocalPlatformMode 变更会让两个焦点修饰符重算，
                                            // 以及 dispatchKeyEvent 的按键闸门立即生效，无需重启。
                                            uiPrefs.edit().putString(PLATFORM_MODE_PREF_KEY, mode.key).apply()
                                            platformModeState.value = mode
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
                                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "试听音质已更新，正在按新音质无缝重载当前歌曲...", Toast.LENGTH_SHORT).show()
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
                                            com.lm.player.core.source.ServerPresence.unlockToOnline()
                                            activeServerName = server.name
                                            activeServerId = server.id
                                            localModeDueToOffline = false
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                com.lm.player.core.source.ServerLinkState.probeNow()
                                                database.serverDao().setActiveServer(server.id)
                                                syncServerPlaylists(server)
                                                syncServerSongs(server)
                                            }
                                        },
                                        onAddOrUpdateServer = { serverConfig ->
                                            activeServerName = serverConfig.name
                                            activeServerId = serverConfig.id
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                // 柠檬服务器：用户输入的是密码则加密保存到 saltOrSecret 供会话过期后自动重登；
                                                // 编辑时沿用了已有 Token（未重新输入密码）则保留原来保存的密码，避免被清空。
                                                val enteredValue = serverConfig.tokenOrApiKey
                                                val existingSecret = runCatching {
                                                    database.serverDao().getAllServers().firstOrNull { it.id == serverConfig.id }?.saltOrSecret
                                                }.getOrNull().orEmpty()
                                                val secretToStore = when {
                                                    serverConfig.type != ServerType.LEMON_MUSIC -> serverConfig.saltOrSecret
                                                    enteredValue.isNotBlank() && !LemonMusicProtocol.looksLikeServerToken(enteredValue) ->
                                                        com.lm.player.core.security.ServerTokenCipher.encrypt(enteredValue)
                                                    else -> existingSecret.ifBlank { serverConfig.saltOrSecret }
                                                }
                                                database.serverDao().insertServer(
                                                    ServerEntity(
                                                        id = serverConfig.id,
                                                        name = serverConfig.name,
                                                        type = serverConfig.type,
                                                        serverUrl = serverConfig.serverUrl,
                                                        username = serverConfig.username,
                                                        tokenOrApiKey = serverConfig.tokenOrApiKey,
                                                        saltOrSecret = secretToStore,
                                                        syncMode = serverConfig.syncMode,
                                                        isCurrentActive = true
                                                    )
                                                )
                                                database.serverDao().setActiveServer(serverConfig.id)
                                                syncServerPlaylists(serverConfig)
                                                syncServerSongs(serverConfig)
                                            }
                                        },
                                        onDeleteServer = { serverIdToDelete ->
                                            // 删除服务器后保留本地音源（不重启时持续可用；30 分钟内无登录则在关闭程序时清理）
                                            com.lm.player.core.source.SourceAccelerationController.detach(this@MainActivity, serverIdToDelete)
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().deleteServer(serverIdToDelete)
                                                // 第二十三节：有本地文件的歌先转成本地歌曲，再删其余服务器曲目
                                                database.songDao().migrateDownloadedSongsToLocal(serverIdToDelete)
                                                database.songDao().deleteSongsByServer(serverIdToDelete)
                                                database.playlistDao().deletePlaylistsByServer(serverIdToDelete)
                                                // 服务器歌曲加速表：这台服务器的平台对应、歌词、平台封面一并删掉
                                                com.lm.player.core.source.ServerTrackAccelStore.deleteForServer(serverIdToDelete)
                                            }
                                            if (activeServerId == serverIdToDelete) {
                                                activeServerName = "本地 · 已下载"
                                                localModeDueToOffline = false
                                                activeServerId = ""
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    database.serverDao().clearActiveServer()
                                                }
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
                                                val treeUriStr = uri.toString()
                                                val isWritable = downloadEngine.testDirectoryWritable(resolvedPath, treeUriStr)
                                                if (isWritable) {
                                                    val updated = downloadEngine.downloadSettings.value.copy(
                                                        customDownloadPath = resolvedPath,
                                                        customDownloadTreeUri = treeUriStr
                                                    )
                                                    downloadEngine.updateSettings(updated)
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已成功设定并验证下载存储目录：$resolvedPath", Toast.LENGTH_SHORT).show()
                                                    if (resolvedPath.isNotBlank()) {
                                                        lifecycleScope.launch(Dispatchers.IO) {
                                                            val scanned = LocalMediaScanner.scanCustomDirectory(this@MainActivity, resolvedPath, database)
                                                            val matched = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, java.io.File(resolvedPath))
                                                            if (scanned > 0 || matched > 0) {
                                                                withContext(Dispatchers.Main) {
                                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "离线目录同步完成：扫描 $scanned 首，匹配 $matched 首", Toast.LENGTH_LONG).show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(
                                                        this@MainActivity,
                                                        StoragePermissionHelper.explainWriteFailure(this@MainActivity, resolvedPath),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                            // 第十八节：先确认权限，再按顺序打开**一个**选择界面 (此前会同时弹出授权页与选择器)。
                                            // 系统文件夹选择器存在时让用户二选一：车机上系统选择器常常不完整，
                                            // 而内置选择器里有「U 盘专属目录」这条不依赖系统组件的可写路径。
                                            ensureStoragePermission(needWrite = true) {
                                                if (StoragePermissionHelper.canOpenDocumentTree(this@MainActivity)) {
                                                    showDownloadDirSourceChooser = true
                                                } else {
                                                    openAllFilesAccessSettingsIfNeeded()
                                                    showCustomFolderPickerForDownload = true
                                                }
                                            }
                                        },
                                        onOpenLocalFolderPicker = {
                                            ensureStoragePermission(needWrite = false) {
                                                openAllFilesAccessSettingsIfNeeded()
                                                showCustomFolderPickerForImport = true
                                            }
                                        },
                                        requestStoragePermission = { needWrite, onReady ->
                                            ensureStoragePermission(needWrite, onReady)
                                        },
                                        onOpenAppPermissionSettings = { openAppPermissionSettings() },
                                        onImportCustomFolder = {
                                            onImportFolderResult = { uri ->
                                                val resolvedFolder = DownloadEngine.resolveFilesystemPath(uri.toString())
                                                if (resolvedFolder.isNotBlank()) {
                                                    val defaultScanFolders = setOf("/storage/emulated/0/Music", "/storage/emulated/0/Download")
                                                    val existingFolders = uiPrefs.getStringSet("local_music_scan_folders", defaultScanFolders)?.toSet() ?: defaultScanFolders
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
                                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "扫描完成！成功导入 $count 首本地歌曲，比对匹配 $matched 首服务器歌曲已标为本地已下载", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                            ensureStoragePermission(needWrite = false) {
                                                val launched = StoragePermissionHelper.canOpenDocumentTree(this@MainActivity) &&
                                                    try {
                                                        importFolderLauncher.launch(null)
                                                        true
                                                    } catch (_: Exception) {
                                                        false
                                                    }
                                                if (!launched) {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "本机没有系统文件夹选择器，已改用内置选择器", Toast.LENGTH_SHORT).show()
                                                    showCustomFolderPickerForImport = true
                                                }
                                            }
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
                        } // 性能优化 A1：底层主界面绘制开关 Box

                        // 全屏全局沉浸式搜索面板 (内嵌于脚手架内容层中，点击左侧导航栏任意其他页面自动退出)
                        if (isSearchDialogOpen) {
                            val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                            // page/limit 透传给服务端，由服务端做翻页；一页拉满即认为还有下一页。
                            // 搜索：本地音源可用时本地优先（不经服务器），本地无结果且服务器未离线时服务器兜底；都不可用时不提供在线搜索
                            val localSearchReady = com.lm.player.core.source.SourceAccelerationSession.status.collectAsState().value.isReady ||
                                com.lm.player.core.source.SourceAccelerationController.scriptsCached.collectAsState().value ||
                                com.lm.player.core.source.catalog.OfflineCatalogService.isAvailable()
                            val onlineSearchCallback: (suspend (String, OnlineMusicSource, Int, Int) -> List<UnifiedSong>)? = remember(activeServer, localSearchReady) {
                                val srv = activeServer
                                if (srv != null || localSearchReady) {
                                    { keyword, source, page, limit ->
                                        catalogLocalFirst(
                                            local = { com.lm.player.core.source.catalog.OfflineCatalogService.searchSongs(keyword, source.key, page, limit) },
                                            server = srv?.let { s ->
                                                suspend {
                                                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                    LemonMusicProtocol(client, s.serverUrl, s.username, s.tokenOrApiKey)
                                                        .searchOnline(keyword, source = source.key, page = page, limit = limit).getOrNull().orEmpty()
                                                }
                                            }
                                        )
                                    }
                                } else null
                            }
                            val parsePlaylistCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeServer, localSearchReady) {
                                val srv = activeServer
                                if (srv != null || localSearchReady) {
                                    { url, source ->
                                        catalogLocalFirst(
                                            local = { com.lm.player.core.source.catalog.OfflineCatalogService.parseExternalPlaylist(url, source.key) },
                                            server = srv?.let { s ->
                                                suspend {
                                                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                    LemonMusicProtocol(client, s.serverUrl, s.username, s.tokenOrApiKey)
                                                        .parseExternalPlaylist(urlOrId = url, source = source.key).getOrNull().orEmpty()
                                                }
                                            }
                                        )
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
                                isServerConnected = (activeServer != null || com.lm.player.core.source.catalog.OfflineCatalogService.isAvailable()),
                                contentPadding = innerPadding,
                                onDismiss = { isSearchDialogOpen = false }
                            )
                        }

                        if (showDownloadDirSourceChooser) {
                            StorageDirSourceChooserDialog(
                                onSystemPicker = {
                                    showDownloadDirSourceChooser = false
                                    val launched = try {
                                        chooseDownloadDirectoryLauncher.launch(null)
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                    if (!launched) {
                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "系统文件夹选择器无法打开，已改用内置选择器", Toast.LENGTH_SHORT).show()
                                        openAllFilesAccessSettingsIfNeeded()
                                        showCustomFolderPickerForDownload = true
                                    }
                                },
                                onBuiltInPicker = {
                                    showDownloadDirSourceChooser = false
                                    openAllFilesAccessSettingsIfNeeded()
                                    showCustomFolderPickerForDownload = true
                                },
                                onDismiss = { showDownloadDirSourceChooser = false }
                            )
                        }

                        storagePermissionPrompt.value?.let { prompt ->
                            StoragePermissionDialog(
                                message = prompt.message,
                                showContinue = prompt.onContinue != null,
                                onOpenSettings = {
                                    storagePermissionPrompt.value = null
                                    openAppPermissionSettings()
                                },
                                onContinue = {
                                    storagePermissionPrompt.value = null
                                    prompt.onContinue?.invoke()
                                },
                                onDismiss = { storagePermissionPrompt.value = null }
                            )
                        }

                        if (showCustomFolderPickerForDownload) {
                            LocalFolderPickerDialog(
                                initialPath = downloadEngine.downloadSettings.value.customDownloadPath,
                                showAppSpecificShortcuts = true,
                                onDismiss = { showCustomFolderPickerForDownload = false },
                                onConfirm = { selectedPath ->
                                    showCustomFolderPickerForDownload = false
                                    if (selectedPath.isNotBlank()) {
                                        if (downloadEngine.testDirectoryWritable(selectedPath, null)) {
                                            // 改走内置选择器时清掉旧的系统选择器授权，避免下载时又被复制回旧目录
                                            val updated = downloadEngine.downloadSettings.value.copy(
                                                customDownloadPath = selectedPath,
                                                customDownloadTreeUri = ""
                                            )
                                            downloadEngine.updateSettings(updated)
                                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已成功设定并保存下载存储目录：$selectedPath", Toast.LENGTH_SHORT).show()
                                        } else {
                                            com.lm.player.core.designsystem.component.AppToast.makeText(
                                                this@MainActivity,
                                                StoragePermissionHelper.explainWriteFailure(this@MainActivity, selectedPath),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        val updated = downloadEngine.downloadSettings.value.copy(customDownloadPath = "", customDownloadTreeUri = "")
                                        downloadEngine.updateSettings(updated)
                                        com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已恢复为默认应用私有下载目录", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        if (showCustomFolderPickerForImport) {
                            LocalFolderPickerDialog(
                                initialPath = "",
                                forScan = true,
                                onDismiss = { showCustomFolderPickerForImport = false },
                                onConfirm = { selectedPath ->
                                    showCustomFolderPickerForImport = false
                                    if (selectedPath.isNotBlank()) {
                                        val defaultScanFolders = setOf("/storage/emulated/0/Music", "/storage/emulated/0/Download")
                                        val existingFolders = uiPrefs.getStringSet("local_music_scan_folders", defaultScanFolders)?.toSet() ?: defaultScanFolders
                                        val updatedFolders = existingFolders + selectedPath
                                        uiPrefs.edit().remove("local_music_scan_folders").apply()
                                        uiPrefs.edit().putStringSet("local_music_scan_folders", updatedFolders).apply()
                                        // 第十九节：交给后台扫描引擎 (进度显示在 设置 →「本地音乐路径」)
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val dlDir = downloadEngine.getDownloadDir()
                                            withContext(Dispatchers.Main) {
                                                if (com.lm.player.core.media.LocalScanEngine.start(this@MainActivity, listOf(selectedPath), includeMediaStore = false, downloadDir = dlDir)) {
                                                    com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已添加扫描目录，正在后台扫描，进度见「本地音乐路径」", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        }
                    }
                        }

                    // 全屏现代高保真音乐播放器弹窗 (支持横屏分屏歌词与多主题联动)
                    // 「全屏封面」主题采用自底部滑入/滑出的转场，其余主题保持淡入缩放
                    val useSlideTransition = playerThemeStyle ==
                        com.lm.player.core.designsystem.theme.PlayerThemeStyle.NETEASE_TV_COVER ||
                        playerThemeStyle == com.lm.player.core.designsystem.theme.PlayerThemeStyle.KARAOKE_COVER
                    // 「专辑卡片」「全屏封面」与主界面左侧播放卡片之间的收拢 / 翻转过渡（2026-11-22）：
                    // 画面全由播放页自己做，外层不再淡入缩放 / 滑入；退场时画面已经收回主界面，只留一个很短的淡出兜底
                    remember { com.lm.player.feature.player.tv.TvPlayerPrefs.ensureLoaded(this@MainActivity) }
                    val useCardHandoff = !com.lm.player.core.podcast.PodcastSongs.isPodcast(currentSong) &&
                        com.lm.player.feature.player.tv.PlayerCardHandoff.canRun(com.lm.player.feature.player.tv.TvPlayerPrefs.theme)
                    AnimatedVisibility(
                        visible = isFullPlayerVisible && currentSong != null,
                        enter = if (useCardHandoff) {
                            androidx.compose.animation.EnterTransition.None
                        } else if (useSlideTransition) {
                            slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = androidx.compose.animation.core.tween(320)
                            )
                        } else {
                            fadeIn(animationSpec = androidx.compose.animation.core.tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = androidx.compose.animation.core.tween(220))
                        },
                        exit = if (useCardHandoff) {
                            fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
                        } else if (useSlideTransition) {
                            slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = androidx.compose.animation.core.tween(260)
                            )
                        } else {
                            fadeOut(animationSpec = androidx.compose.animation.core.tween(180)) + scaleOut(targetScale = 0.96f, animationSpec = androidx.compose.animation.core.tween(180))
                        }
                    ) {
                        // 性能优化 A1：进场转场真正结束后才允许跳过底层主界面绘制；
                        // 一开始退场 (targetState 变为 PostExit) 立即恢复绘制，保证退场动画能看到主界面
                        TrackFullPlayerEnterSettled { fullPlayerEnterSettled = it }
                        DisposableEffect(Unit) {
                            onDispose {
                                fullPlayerEnterSettled = false
                                fullPlayerSheetCovering = true
                            }
                        }
                        val activeSong = currentSong
                        if (activeSong != null && com.lm.player.core.podcast.PodcastSongs.isPodcast(activeSong)) {
                            // 正在播放播客单集：显示播客专用大屏播放页（倍速、±秒跳转、Shownotes、待播）
                            com.lm.player.core.podcast.TvPodcastPlayerHost(
                                song = activeSong,
                                isPlaying = isPlaying,
                                onDismiss = { isFullPlayerVisible = false },
                                onOpenShow = { feedId ->
                                    isFullPlayerVisible = false
                                    com.lm.player.core.podcast.PodcastController.openShowFromPlayer(feedId)
                                    navigateToScreen(Screen.PODCASTS)
                                }
                            )
                        } else if (activeSong != null) {
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

                            // 2026-11-12 播放页重做：三个主题（黑胶唱片 / 大字歌词 / 全屏封面 + 歌词）+ 共用控制胶囊，
                            // 歌词改用手机端移植的歌词引擎（见 feature/player/tv）
                            com.lm.player.feature.player.tv.TvPlayerScreen(
                                song = song,
                                playlist = currentQueue.ifEmpty { songList },
                                isPlaying = isPlaying,
                                // 以取数函数传入：值在这里被读取会让整个 Activity 组合树每 0.4~0.8 秒重组一次
                                progressMsProvider = { localProgressMs },
                                totalDurationMs = localTotalDurationMs,
                                lyrics = currentLyrics,
                                isShuffle = isShuffle,
                                isRepeat = isRepeat,
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
                                onToggleFavorite = {
                                    handleToggleFavorite(song)
                                },
                                onDownloadSongWithOptions = handleDownloadWithOptions,
                                onAddToPlaylist = handleAddToPlaylist,
                                onCreatePlaylistAndAddSong = handleCreatePlaylistAndAddSong,
                                onPlayerThemeStyleChange = { playerThemeStyle = it },
                                onCoversBackgroundChanged = { fullPlayerSheetCovering = it },
                                onDismiss = { isFullPlayerVisible = false },
                                downloadTasks = downloadEngine.activeTasksFlow
                            )
                        }
                    }

                    // 全软件统一的系统提示气泡（顶部居中，盖在全屏播放页之上；见 AppToast）
                    com.lm.player.core.designsystem.component.AppToastHost()

                    // 多选「加入歌单」的选择 / 进度 / 结果窗口（2026-11-14，所有列表共用）
                    com.lm.player.core.designsystem.component.PlaylistBatchOpsHost()

                    // 启动 Logo 过渡动画图层 (开屏丝滑淡出)
                    SplashScreenView(
                        visible = isSplashVisible,
                        onSplashFinished = { isSplashVisible = false }
                    )

                    // 崩溃自诊断：上次运行闪退过则原样展示堆栈，用户截图即可回传（展示一次后不再打扰）
                    var lastCrashReport by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        // 读崩溃日志是磁盘 I/O，放到 IO 线程避免首帧期间阻塞 UI
                        // 先等启动时的「上次异常退出」检查做完（最多 1.5 秒）：没有 Java 崩溃日志的闪退也能弹出来
                        com.lm.player.core.util.ExitDiagnostics.awaitStartupCheck()
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
                                com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "已记录，不再提示 v${info.latestVersion} 更新", Toast.LENGTH_SHORT).show()
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
                                            // 传服务端声明的大小与哈希：下载完成后做完整性校验，
                                            // 短包 / 被替换的安装包会在落盘后被识别并删除
                                            expectedSizeBytes = info.apkSizeBytes,
                                            expectedSha256 = info.apkSha256,
                                            onProgress = { progress, _, _ -> downloadStartupProgress = progress }
                                        ).onSuccess { apkFile ->
                                            isDownloadingStartupApk = false
                                            downloadedStartupApkFile = apkFile
                                            isStartupApkReady = apkFile.exists() && apkFile.length() > 0L
                                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "安装包下载完成，正在调起安装...", Toast.LENGTH_SHORT).show()
                                            AppUpdateManager.installApk(this@MainActivity, apkFile)
                                        }.onFailure { error ->
                                            isDownloadingStartupApk = false
                                            com.lm.player.core.designsystem.component.AppToast.makeText(this@MainActivity, "下载更新失败: ${error.message}", Toast.LENGTH_LONG).show()
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

                    // 首次启动的平台模式向导（不可跳过）。
                    // 放在根 Box 内容区的最后一个子节点，确保盖住脚手架与全屏播放页；
                    // 选择后写入偏好 → 状态更新 → 进入「我的」页。
                    if (platformMode == null) {
                        PlatformModeWizard(
                            onSelect = { mode ->
                                uiPrefs.edit().putString(PLATFORM_MODE_PREF_KEY, mode.key).apply()
                                platformModeState.value = mode
                                navigateToScreen(Screen.MINE)
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
        // 程序关闭：服务器删除后 30 分钟内没有登录连接则清理本地音源脚本
        com.lm.player.core.source.SourceAccelerationController.onAppClosing(this)
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



    /**
     * 媒体键 / 音量键的**按键级**防抖时间戳。
     *
     * 早前是一个全局单时间戳（`lastActivityKeyTimestamp`）：250ms 内先按「下一首」再按「上一首」，
     * 或切歌后立刻调音量，第二条命令会被静默吞掉 —— 表现就是"方向盘按了没反应"。
     * 改成一键一记后，只压同一颗键的连击与长按重复，不同键互不影响。
     */
    private val lastKeyTimestampByCode = HashMap<Int, Long>()

    /**
     * 已在 ACTION_DOWN 上执行过动作、正在等待配对 UP 的按键码集合。
     * 用集合而非单个变量：方向盘上「下一首」与「上一首」可能交叠按下，
     * 单变量会把先按那颗键的 UP 判成"未配对"而泄漏给 MediaSession，又切一次歌。
     */
    private val pendingDownKeyCodes = HashSet<Int>()

    /** 与组合层同一份设置存储（同名 SharedPreferences 是进程内单例）。 */
    private val settingsPrefs by lazy { getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE) }

    /**
     * 当前是否处于车机模式 —— 供 [dispatchKeyEvent] 这类**组合之外**的代码使用。
     * 直接读 SharedPreferences 而不缓存到字段：切换模式时正是写这份 prefs，
     * `apply()` 会同步更新内存值，因此这里读到的永远是最新状态，不需要额外的同步机制。
     */
    private val isCarPlatformActive: Boolean
        get() = PlatformMode.fromKey(settingsPrefs.getString(PLATFORM_MODE_PREF_KEY, null)) == PlatformMode.CAR

    /**
     * 车机模式长按返回键的逃生入口：切回电视模式并恢复遥控焦点体系。
     * 同时更新偏好与 Activity 级状态，立即生效、无需重启。
     */
    private fun switchBackToTvPlatformMode() {
        try {
            settingsPrefs.edit().putString(PLATFORM_MODE_PREF_KEY, PlatformMode.TV.key).apply()
            platformModeState.value = PlatformMode.TV
            com.lm.player.core.designsystem.component.AppToast.makeText(this, "已通过长按返回键切回「电视 / 机顶盒」模式，遥控器已恢复", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /** 电视导航键：车机模式下需要被整体掐断的按键集合（不含 BACK、媒体键、音量键）。 */
    private fun isTvNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU -> true
            else -> false
        }
    }

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

    /**
     * 「借位」媒体键：电视遥控器的**频道 / 翻页 / 导航**键被当作上一首、下一首的那批映射。
     *
     * 车上这些键位通常归旋钮、收音机预置键或车机自身功能使用，继续当成切歌键判读，
     * 表现为"没碰音乐却莫名跳歌"。车机模式下**放行而不消费**，归属交给系统与其它应用；
     * 电视模式完全不受影响。注意**不含** MEDIA_NEXT/PREVIOUS 与 BUTTON_L1/R1 ——
     * 那是方向盘与手柄的正规映射，恰好是要保住的那条路。
     */
    private fun isTvStyleMediaKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> true
            else -> false
        }
    }

    private fun handleMediaKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (!isMediaOrVolumeKey(keyCode)) return false

        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

        // 长按产生的重复事件：媒体键一律丢弃（按住不放会连跳好几首歌），
        // 但要 return true 把它消费掉，否则它会被算作"未配对"而漏给 MediaSession。
        // 音量键保留 —— 按住音量键连续调节是系统约定，压掉会让手感变木。
        if (event.repeatCount > 0 && !isVolumeKey) return true

        val now = System.currentTimeMillis()
        if (!isVolumeKey) {
            if (now - (lastKeyTimestampByCode[keyCode] ?: 0L) < 250) return true
            lastKeyTimestampByCode[keyCode] = now
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                com.lm.player.core.media.SkipLatencyStats.noteSource("方向盘 / 媒体按键")
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
                com.lm.player.core.media.SkipLatencyStats.noteSource("方向盘 / 媒体按键")
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

    /**
     * 目录数据（搜索 / 发现页 / 歌单 / 榜单 / 专辑）的统一走向（用户 2026-11-07 确认）：
     * - 本地音源可用：本地目录服务优先（不经服务器、不先登录，服务器停掉时也不会卡在连接超时上）；
     *   本地失败或空结果、且服务器没有离线时，再问服务器兜底；
     * - 本地音源不可用：走服务器；服务器已离线则不再请求，直接返回空。
     */
    private suspend fun <T> catalogLocalFirst(
        local: suspend () -> List<T>,
        server: (suspend () -> List<T>)?
    ): List<T> {
        fun swallow(e: Throwable): List<T> {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("MainActivity", "catalog request failed: ${e.message}")
            return emptyList()
        }
        if (com.lm.player.core.source.catalog.OfflineCatalogService.isAvailable()) {
            val localResult = try { local() } catch (e: Throwable) { swallow(e) }
            if (localResult.isNotEmpty() || server == null || com.lm.player.core.source.ServerLinkState.isLost) return localResult
        }
        if (server == null || com.lm.player.core.source.ServerLinkState.isLost) return emptyList()
        return try { server() } catch (e: Throwable) { swallow(e) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.lm.player.core.widget.WidgetLaunch.consume(intent)
        // 语音点歌：应用已在运行时收到「播放某某」的意图
        val caller = runCatching { referrer?.host }.getOrNull()
        // 2026-11-24：音乐助手点完歌会再用 MAIN 把本应用切到前台（一次两次）；
        // 设置为点歌不打开界面、且点歌前界面本来就在后台时，马上退回后台，不打断导航
        val backToBg = com.lm.player.core.voice.VoicePlayRouter.shouldReturnToBackground(this, caller)
        com.lm.player.core.voice.VoiceDiagnostics.recordAppOpen(
            this, caller, intent, "切回主界面",
            note = if (backToBg) "点歌后顺手打开，已退回后台" else null
        )
        com.lm.player.core.voice.VoicePlayRouter.consumeIntent(this, intent, caller)
        if (backToBg) window.decorView.post { moveTaskToBack(true) }
    }

    /** 播放页是否要求隐藏状态栏（窗口重新获得焦点时据此补一次，部分系统会在弹窗 / 下拉通知后把状态栏放回来） */
    private var playerStatusBarHidden = false

    private fun applyPlayerStatusBar(hide: Boolean) {
        playerStatusBarHidden = hide
        runCatching {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (hide) {
                // 从屏幕顶部下滑可临时呼出，过一会儿自动收起
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playerStatusBarHidden) applyPlayerStatusBar(true)
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        // 闪退诊断：记下「界面在前台」，下次启动时据此判断上次是不是在前台时突然没了
        com.lm.player.core.util.ExitDiagnostics.markForeground(this, true)
        com.lm.player.core.media.SkipLatencyStats.appInForeground = true
        com.lm.player.core.voice.VoicePlayRouter.onUiResumed()
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
        com.lm.player.core.voice.VoicePlayRouter.onUiPaused()
        super.onPause()
    }

    override fun onStop() {
        persistPlaybackStateSync()
        com.lm.player.core.util.ExitDiagnostics.markForeground(this, false)
        com.lm.player.core.media.SkipLatencyStats.appInForeground = false
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 车机模式：从 Activity 层直接掐断整条电视导航键链路（方向键 / 确定 / 菜单）。
        // 放在这里而不是 Compose 根节点上，是因为：① Activity 早于整个 View 树收到按键，
        // 能覆盖 Compose、Dialog、Popup 全部窗口；② 车机模式下焦点体系已停用，
        // 不能保证有节点持有焦点，挂在 Compose 上的拦截器可能根本收不到事件。
        // BACK、媒体键、音量键一概不受影响，陀螺仪外的手势与触控也照常。
        if (isCarPlatformActive && isTvNavKey(event.keyCode)) return true

        // 车机模式的**遥控器逃生通道**：长按返回键 2 秒切回电视模式。
        // 车机模式会停用整套 Compose 焦点体系并在此处吞掉全部 D-Pad 事件，无触屏电视/盒子
        // 一旦被切到车机（设置页误按、他人误操作）就再也进不了任何界面。BACK 键是本模式下
        // 唯一仍被放行的按键，因此用它承载逃生入口，且短按返回的原有语义完全不受影响。
        if (isCarPlatformActive && event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (carEscapeBackDownAt < 0L) carEscapeBackDownAt = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - carEscapeBackDownAt >= 2000L) {
                        carEscapeBackDownAt = -1L
                        switchBackToTvPlatformMode()
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> carEscapeBackDownAt = -1L
            }
        }

        // 车机模式下不认「频道 / 翻页 / 导航」这类电视遥控器的借位映射（见 isTvStyleMediaKey）：
        // 车机上这些键位常被旋钮、预置键占用，继续当切歌键判读会表现为"莫名跳歌"。
        // 放行而不消费 —— 归属交给系统与其它应用；电视模式不受影响。
        if (isCarPlatformActive && isTvStyleMediaKey(event.keyCode)) return safeSuperDispatchKeyEvent(event)

        if (isMediaOrVolumeKey(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (handleMediaKeyEvent(event)) {
                        // 记下这个键，供配对的 UP 判定使用
                        pendingDownKeyCodes.add(event.keyCode)
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    // 关键修复：DOWN 与配对的 UP 必须**成对**吞掉。此前只拦 DOWN，未被消费的 UP
                    // 会继续下发给当前 MediaSession，由 PlaybackService.onMediaButtonEvent
                    // 再执行一次切歌，导致方向盘按一下连跳 2~3 首。
                    //
                    // 但只吞**配对**的那一个：设备若只上报 ACTION_UP（部分车机 CAN 适配器如此），
                    // 这里放行给 MediaSession，由 PlaybackService 的 UP 兜底分支执行，
                    // 否则会出现"按了完全没反应"。
                    if (pendingDownKeyCodes.remove(event.keyCode)) return true
                }
                else -> return true
            }
        }
        return safeSuperDispatchKeyEvent(event)
    }

    private fun safeSuperDispatchKeyEvent(event: KeyEvent): Boolean {
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            // Compose 1.6 焦点树失配兜底（2026-11-13，小米 Android 15 实测崩溃）：
            // 焦点树里根节点记着「有子节点持有焦点」(ActiveParent)，却找不到真正持焦点的节点，
            // 此后**任何**按键（方向键、确定、音量）进到 Compose 都会抛
            // "Event can't be processed because we do not have an active focus target."。
            // Compose 1.7 起改为直接记录持焦节点，不再有这种状态；1.6 只能在这里修复：
            // 让持焦的 ComposeView 先失焦再取回焦点，根节点被重置成「自己持有焦点」，
            // 然后把这次按键重新派发一次（方向键因此照常把光标移进界面，不用多按一下）。
            if (e.message?.contains("active focus target", ignoreCase = true) == true) {
                android.util.Log.w("MainActivity", "Recovering Compose focus tree with no active target (key=${event.keyCode})")
                return recoverComposeFocusAndRedispatch(event)
            }
            // Compose 焦点系统缺陷兜底：在歌曲列表等 LazyColumn 快速移动或到达顶部边界时，
            // Compose 内部 2D 焦点查找算法访问了正在 detached 的 LayoutCoordinates 节点，
            // 会抛出 "LayoutCoordinate operations are only valid when isAttached is true"。
            // 吞掉此异常并安全消费该次按键，避免整机进程崩溃。
            if (e.message?.contains("isAttached", ignoreCase = true) == true ||
                e.message?.contains("LayoutCoordinate", ignoreCase = true) == true
            ) {
                android.util.Log.w("MainActivity", "Safely swallowed Compose focus detached crash during rapid D-Pad move: ${e.message}")
                true
            } else {
                throw e
            }
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Focus", ignoreCase = true) == true) {
                android.util.Log.w("MainActivity", "Safely swallowed Compose focus traversal crash: ${e.message}")
                true
            } else {
                throw e
            }
        }
    }

    /** 见 [safeSuperDispatchKeyEvent]：重置 Compose 焦点根节点后重发一次按键；仍失败就吞掉这一次，绝不让进程崩溃 */
    private fun recoverComposeFocusAndRedispatch(event: KeyEvent): Boolean {
        val focused = window?.decorView?.findFocus()
        if (focused != null) {
            runCatching {
                // 失焦：Compose releaseFocus 强制清空整棵焦点树（失配的 ActiveParent 也能清掉）；
                // 取回：takeFocus 让根节点自己持有焦点 —— 与 focusManager.clearFocus() 之后的状态相同
                focused.clearFocus()
                if (!focused.isFocused) focused.requestFocus()
            }
        }
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: RuntimeException) {
            android.util.Log.w("MainActivity", "Compose focus recovery failed, key swallowed: ${e.message}")
            true
        }
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
                // Android 10 及以下：写下载目录需要 WRITE_EXTERNAL_STORAGE (旧版存储模式)。
                // 此前从不申请，Android 10 车机上更换下载目录一律失败。
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            } else {
                // Android 13 (API 33) 起 READ_EXTERNAL_STORAGE 对媒体文件完全失效，
                // 必须改用按类型划分的 READ_MEDIA_AUDIO。此前这里只处理了 API ≤ 32 的分支，
                // 导致新系统车机 / 电视上装了也扫不到任何本地音乐（MediaStore 查询返回空、
                // File.exists() 对外置目录一律返回 false）。
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                }
            }

            // Android 13+ 前台播放服务的媒体通知需要通知权限才会显示（没有它通知静默不出现）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (permissions.isNotEmpty()) {
                permissionLauncher.launch(permissions.toTypedArray())
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        com.lm.player.core.startup.FrameStats.detach(this)
        // 必须先摘掉换绑回调：Media3Factory 的监听列表是进程级静态的，
        // 不移除会让旧 Activity 一直被持有
        try { Media3Factory.removePlayerSwapListener(playerSwapListener) } catch (_: Exception) {}
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

/**
 * 切页提速：去重结果按曲库列表实例缓存。曲库只在数据库变化时才换新列表，
 * 每次切回首页 / 资料库不再整库重新去重（原先每次进页都在主线程重算一遍）。
 */
private object LocalSongsDedupeCache {
    private var input: List<UnifiedSong>? = null
    private var output: List<UnifiedSong> = emptyList()

    @Synchronized
    fun get(list: List<UnifiedSong>): List<UnifiedSong> {
        if (list === input) return output
        val result = deduplicateLocalSongs(list)
        input = list
        output = result
        return result
    }
}

/**
 * 切页提速：「我的」页曲库列表按输入实例缓存（曲库列表、已下载列表按实例比较，服务器配置与模式按值比较）。
 */
private object LibrarySongsCache {
    private var songsRef: List<UnifiedSong>? = null
    private var downloadsRef: List<UnifiedSong>? = null
    private var configKey: ServerConfig? = null
    private var localModeKey: Boolean? = null
    private var output: List<UnifiedSong> = emptyList()

    @Synchronized
    fun get(
        songs: List<UnifiedSong>,
        downloads: List<UnifiedSong>,
        config: ServerConfig?,
        localMode: Boolean,
        compute: () -> List<UnifiedSong>
    ): List<UnifiedSong> {
        if (songs === songsRef && downloads === downloadsRef && config == configKey && localMode == localModeKey) {
            return output
        }
        val result = compute()
        songsRef = songs
        downloadsRef = downloads
        configKey = config
        localModeKey = localMode
        output = result
        return result
    }
}

private fun deduplicateLocalSongs(rawList: List<UnifiedSong>): List<UnifiedSong> {
    val byPath = LinkedHashMap<String, UnifiedSong>(rawList.size)
    for (s in rawList) {
        val p = s.localFilePath?.trim()?.replace('\\', '/')?.lowercase() ?: "id:${s.id}"
        val existing = byPath[p]
        if (existing == null) {
            byPath[p] = s
        } else {
            val existingIsLocalOnly = existing.serverId in listOf("local_storage", "local_folder", "local_saf")
            val incomingIsServer = s.serverId !in listOf("local_storage", "local_folder", "local_saf")
            if (existingIsLocalOnly && incomingIsServer) {
                byPath[p] = s
            }
        }
    }
    return byPath.values.distinctBy { it.id }
}

/**
 * 性能优化 A1：上报全屏播放页的进场转场是否已完全结束。
 * 仅当 currentState 与 targetState 同为 Visible 时为 true；一开始退场立即变为 false。
 * 用 snapshotFlow 观察，不在组合期读取转场状态，因此不会引起播放页重组。
 */
@kotlin.OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
private fun androidx.compose.animation.AnimatedVisibilityScope.TrackFullPlayerEnterSettled(
    onSettledChanged: (Boolean) -> Unit
) {
    val currentCallback by rememberUpdatedState(onSettledChanged)
    val playerTransition = transition
    LaunchedEffect(playerTransition) {
        snapshotFlow {
            playerTransition.currentState == androidx.compose.animation.EnterExitState.Visible &&
                playerTransition.targetState == androidx.compose.animation.EnterExitState.Visible
        }.collect { currentCallback(it) }
    }
}
