package com.lm.player.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lm.player.MainActivity
import com.lm.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /**
     * 用户是否已明确要求「彻底停止播放」。
     *
     * 播放器是进程级单例，服务销毁后仍可能存活；若没有这个标记，
     * 彻底关闭后残留的状态回调仍会调用 notify(NOTIFICATION_ID, ...)，
     * 把媒体通知**重新贴回**通知栏/电视媒体卡片。
     *
     * 放在 companion 里是因为 [stopServiceAndPlayback] 是静态入口，实例字段访问不到。
     */
    private var isExplicitStopping: Boolean
        get() = companionIsExplicitStopping
        set(value) { companionIsExplicitStopping = value }

    /** 保活看门狗与屏保广播的协程作用域 (Main)，随服务销毁取消 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var keepAliveWatchdogJob: Job? = null

    /** 熄屏广播接收器：ACTION_SCREEN_OFF/ON 无法在 manifest 里静态注册，只能动态注册 */
    private var screenStateReceiver: BroadcastReceiver? = null

    /** 前台通知刷新监听 (播放器实例被重建后需重新挂载到新实例) */
    private val notificationListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateForegroundNotification(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateForegroundNotification(exoPlayer?.isPlaying == true)
        }
    }

    /** 硬件解码降级后播放内核被重建，此处把 MediaSession 换绑到新实例，保证遥控器/通知栏控制不失效 */
    private val playerSwapListener: (ExoPlayer) -> Unit = { newPlayer ->
        try {
            exoPlayer = newPlayer
            mediaSession?.setPlayer(buildForwardingPlayer(newPlayer))
            newPlayer.addListener(notificationListener)
            Log.w(TAG, "播放内核已换绑为软件解码实例")
        } catch (e: Throwable) {
            Log.e(TAG, "换绑软件解码播放器失败", e)
        }
    }

    /**
     * 使用 ForwardingPlayer 包装 ExoPlayer，向系统 MediaSession 声明始终支持上一首/下一首/拖拽指令
     */
    private fun buildForwardingPlayer(rawPlayer: Player): ForwardingPlayer {
        return object : ForwardingPlayer(rawPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_TIMELINE -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            private var lastSeekTimestamp = 0L

            override fun seekToNext() {
                val now = System.currentTimeMillis()
                if (now - lastSeekTimestamp < 300L) return
                lastSeekTimestamp = now
                PlaybackQueueManager.playNext(this@PlaybackService)
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun seekToPrevious() {
                val now = System.currentTimeMillis()
                if (now - lastSeekTimestamp < 300L) return
                lastSeekTimestamp = now
                PlaybackQueueManager.playPrevious(this@PlaybackService)
            }

            override fun seekToPreviousMediaItem() {
                seekToPrevious()
            }

            override fun play() {
                val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                if (!p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
            }

            override fun pause() {
                val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                if (p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        /** 「彻底停止播放」标记的存储位置：静态入口与实例方法都需要访问 */
        @Volatile
        private var companionIsExplicitStopping = false
        const val CHANNEL_ID = "zds_player_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_MEDIA_COMMAND = "com.lm.player.ACTION_MEDIA_COMMAND"
        const val ACTION_STOP_SERVICE = "com.lm.player.ACTION_STOP_SERVICE"
        const val EXTRA_COMMAND = "EXTRA_COMMAND"
        const val CMD_PLAY = "CMD_PLAY"
        const val CMD_PAUSE = "CMD_PAUSE"
        const val CMD_TOGGLE = "CMD_TOGGLE"
        const val CMD_NEXT = "CMD_NEXT"
        const val CMD_PREV = "CMD_PREV"

        /** 保活看门狗节奏：5 秒一轮，连续 3 轮停滞 (≈15 秒) 才动作；单次停滞最多修 3 次 */
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val WATCHDOG_STALL_TICKS = 3
        private const val WATCHDOG_MAX_REPAIRS = 3

        /**
         * 彻底关闭播放服务并停止音乐播放
         */
        fun stopServiceAndPlayback(context: Context) {
            try {
                companionIsExplicitStopping = true
                val player = Media3Factory.getSharedExoPlayer(context)
                val currentPos = player.currentPosition.coerceAtLeast(0L)
                PlaybackQueueManager.savePlaybackState(context, positionMs = currentPos, commitSync = true)
                player.stop()
                player.clearMediaItems()
                // 释放进程级播放器：否则 setWakeMode(C.WAKE_MODE_NETWORK) 的
                // PARTIAL_WAKE_LOCK 会随静态单例一直持有到进程结束（电视长期插电场景耗电）。
                Media3Factory.releaseSharedPlayer()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback", e)
            }

            // 停服务必须**独立 try**：Android 8+ 在后台调用 startService 会抛
            // IllegalStateException，若与 stopService 共用同一个 try，异常会把
            // stopService 一并跳过 —— 服务、通知、唤醒锁的清理全部失效。
            try {
                val stopIntent = Intent(context, PlaybackService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(stopIntent)
            } catch (e: Exception) {
                Log.w(TAG, "startService(ACTION_STOP_SERVICE) 失败，直接 stopService", e)
            }
            try {
                context.stopService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping PlaybackService", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()

            // 0. **必须最先建立前台状态**：服务由 startForegroundService 拉起后要在 5 秒内调用
            //    startForeground，否则系统抛 ForegroundServiceDidNotStartInTimeException 崩溃。
            //    此前该调用位于本 try 块末尾，一旦 ExoPlayer / MediaSession / 通知构建任一步抛异常，
            //    异常被外层 catch(Throwable) 吞掉后 startForeground 永不执行 → 必崩。
            //    此处 mediaSession / exoPlayer 仍为 null，DynamicIslandManager 会走无 Session 兜底通知。
            startImmediateForeground()

            // 1. 获取全局单例 ExoPlayer
            val rawPlayer = Media3Factory.getSharedExoPlayer(this)
            exoPlayer = rawPlayer

            // 2. 使用 ForwardingPlayer 包装 ExoPlayer，向系统 MediaSession 声明始终支持上一首/下一首/拖拽指令
            //    封装为方法，便于硬件解码失败重建播放器后把 MediaSession 换绑到新实例
            val forwardingPlayer = buildForwardingPlayer(rawPlayer)

            // 3. 建立 MediaSession 并挂载遥控器/媒体按键回调
            val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val sessionActivityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                sessionActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val sessionCallback = object : MediaSession.Callback {
                /** 按键级防抖时间戳：全局单时间戳会把 250ms 内的第二颗不同按键静默吞掉。 */
                private val lastKeyTimestampByCode = HashMap<Int, Long>()

                /** 已在 ACTION_DOWN 上执行过动作、正在等待配对 UP 的按键码（集合以容忍交叠按键）。 */
                private val pendingDownKeyCodes = HashSet<Int>()

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_NEXT, Bundle.EMPTY))
                        .add(SessionCommand(CMD_PREV, Bundle.EMPTY))
                        .add(SessionCommand(CMD_TOGGLE, Bundle.EMPTY))
                        .add(SessionCommand(CMD_PLAY, Bundle.EMPTY))
                        .add(SessionCommand(CMD_PAUSE, Bundle.EMPTY))
                        .build()
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_PLAY_PAUSE)
                        .add(Player.COMMAND_PREPARE)
                        .add(Player.COMMAND_STOP)
                        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                        .add(Player.COMMAND_GET_METADATA)
                        .add(Player.COMMAND_GET_TIMELINE)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_NEXT -> {
                            PlaybackQueueManager.playNext(this@PlaybackService)
                        }
                        CMD_PREV -> {
                            PlaybackQueueManager.playPrevious(this@PlaybackService)
                        }
                        CMD_TOGGLE -> {
                            PlaybackQueueManager.togglePlay(this@PlaybackService)
                        }
                        CMD_PLAY -> {
                            val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                            if (!p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                        }
                        CMD_PAUSE -> {
                            val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                            if (p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    if (keyEvent != null) {
                        val now = System.currentTimeMillis()
                        val isDown = keyEvent.action == KeyEvent.ACTION_DOWN
                        val isUp = keyEvent.action == KeyEvent.ACTION_UP && keyEvent.repeatCount == 0
                        if (isDown || isUp) {
                            // 一次按下只执行一次动作：
                            // ① 该键的 DOWN 已经执行过 → 配对的 UP 直接吞掉不再执行，
                            //    否则一次按下会切两首歌（与 Activity 侧的 UP 泄漏叠加时实测连跳 3 首）；
                            // ② 仅当**从未收到该键的 DOWN** 时才在 UP 上执行，
                            //    兼容个别只上报 ACTION_UP 的车机 / 蓝牙设备。
                            if (isUp && pendingDownKeyCodes.remove(keyEvent.keyCode)) {
                                return true
                            }
                            // 长按重复：媒体键压掉，音量键不在本分支内。仍需 return true 消费，
                            // 否则会被后续逻辑当作首次按下而执行。
                            if (keyEvent.repeatCount > 0) return true
                            if (now - (lastKeyTimestampByCode[keyEvent.keyCode] ?: 0L) < 250) {
                                return true
                            }
                            lastKeyTimestampByCode[keyEvent.keyCode] = now
                            if (isDown) pendingDownKeyCodes.add(keyEvent.keyCode)
                            Log.i(TAG, "Received MediaButton KeyEvent: ${keyEvent.keyCode}, action: ${keyEvent.action}")
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                                KeyEvent.KEYCODE_BUTTON_R1,
                                KeyEvent.KEYCODE_CHANNEL_UP,
                                KeyEvent.KEYCODE_NAVIGATE_NEXT,
                                KeyEvent.KEYCODE_PAGE_DOWN -> {
                                    PlaybackQueueManager.playNext(this@PlaybackService)
                                    dispatchBroadcast(CMD_NEXT)
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
                                    PlaybackQueueManager.playPrevious(this@PlaybackService)
                                    dispatchBroadcast(CMD_PREV)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_BUTTON_START -> {
                                    PlaybackQueueManager.togglePlay(this@PlaybackService)
                                    dispatchBroadcast(CMD_TOGGLE)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                                    if (!p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                                    dispatchBroadcast(CMD_PLAY)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                                    if (p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                                    dispatchBroadcast(CMD_PAUSE)
                                    return true
                                }
                            }
                        }
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }

                @Deprecated("Deprecated in Java")
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    when (playerCommand) {
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_PLAY_PAUSE -> {
                            // 仅授权命令执行，实际播放播控与防抖切歌统一由 forwardingPlayer 代理执行，消除重复触发
                            return SessionResult.RESULT_SUCCESS
                        }
                    }
                    return super.onPlayerCommandRequest(session, controllerInfo, playerCommand)
                }
            }

            val session = MediaSession.Builder(this, forwardingPlayer)
                .setSessionActivity(sessionActivityPendingIntent)
                .setCallback(sessionCallback)
                .build()
            mediaSession = session

            addSession(session)

            // 4. 前台状态已在本 try 块「步骤 0」建立（必须早于任何可能抛异常的步骤），
            //    此处仅补一次刷新，把新建立的 MediaSession 绑定进媒体通知。
            startImmediateForeground()

            // 5. 监听播放状态与曲目切换动态刷新前台通知
            rawPlayer.addListener(notificationListener)

            // 6. 硬件解码异常时播放内核会被重建为纯软件解码，此处把 MediaSession 与通知监听换绑到新实例
            Media3Factory.addPlayerSwapListener(playerSwapListener)

            // 7. 服务可能先于界面被拉起 (开机自启、通知栏恢复等)，此时同样需要武装队列侧的
            //    播放监听：解码看门狗与周期进度落盘都在其中 (幂等，界面侧重复调用无副作用)
            PlaybackQueueManager.ensurePlayerListener(this)

            // 8. 保活看门狗：仅修复"该播却卡住"的停滞，绝不主动起播
            startKeepAliveWatchdog()

            // 9. 熄屏兜底：电视进入屏保/待机时确保前台状态与唤醒锁都在
            registerScreenStateReceiver()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize PlaybackService", e)
        }
    }

    private fun dispatchBroadcast(command: String) {
        try {
            val intent = Intent(ACTION_MEDIA_COMMAND).apply {
                putExtra(EXTRA_COMMAND, command)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media command broadcast: $command", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            isExplicitStopping = true
            try {
                val currentPos = exoPlayer?.currentPosition?.coerceAtLeast(0L)
                PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
            } catch (_: Exception) {
            } finally {
                // 这是用户显式"彻底关闭"的路径，看门狗必须停下、唤醒锁必须释放，
                // 否则一个已经没有任何播放理由的进程会继续攥着 CPU 锁不放
                stopKeepAliveWatchdog()
                unregisterScreenStateReceiver()
                PlaybackWakeLockManager.release()
                // 撤销前台与自停必须放在 finally：前面任一步抛异常被吞掉后，
                // 服务会一直停留在前台并常驻媒体通知
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MEDIA_COMMAND) {
            val cmd = intent.getStringExtra(EXTRA_COMMAND)
            when (cmd) {
                CMD_NEXT -> {
                    PlaybackQueueManager.playNext(this)
                }
                CMD_PREV -> {
                    PlaybackQueueManager.playPrevious(this)
                }
                CMD_TOGGLE -> {
                    PlaybackQueueManager.togglePlay(this)
                }
                CMD_PLAY -> {
                    // 用 playWhenReady 而非 isPlaying 判定：缓冲期 isPlaying 为 false，
                    // 用 isPlaying 会误判并执行反向操作
                    val p = Media3Factory.getSharedExoPlayer(this)
                    if (!p.playWhenReady) PlaybackQueueManager.togglePlay(this)
                }
                CMD_PAUSE -> {
                    val p = Media3Factory.getSharedExoPlayer(this)
                    if (p.playWhenReady) PlaybackQueueManager.togglePlay(this)
                }
            }
            updateForegroundNotification(exoPlayer?.isPlaying == true)
            return START_STICKY
        }
        // 常规启动（界面 startService / 开机自启 / 媒体键唤醒）说明用户又需要播放了，
        // 复位「彻底停止」标记，否则通知刷新会被永久挡住
        isExplicitStopping = false
        startImmediateForeground()
        // 无 action 的常规启动 (界面 startService / 开机自启) 同样返回 START_STICKY：
        // 落到 super 会得到不可恢复的默认值，服务一旦被系统回收就很难再拉起来
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * 任务栈被移除（电视回到桌面、进入屏保、切到别的应用）时系统回调。
     *
     * **这里绝对不能停播**：这正是旧版"电视出屏保就暂停播放"的确定性根因 ——
     * 原来的实现在此处 `stop()` + `clearMediaItems()` + `stopSelf()`，等于亲手把正在听的歌掐掉。
     * 现在改为：把进度异步落盘（放 IO，避免 eMMC fsync 卡主线程）→ 确保唤醒锁与前台状态在位 → 返回，
     * 让播放继续。搭配 manifest 的 `android:stopWithTask="false"`，服务不会被一并销毁。
     *
     * 用户真正想停播时走的是 [stopServiceAndPlayback]（ACTION_STOP_SERVICE）这条显式路径，
     * 不会被本方法拦截。**请勿把停播逻辑"改回来"。**
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val player = exoPlayer
            // 判「本来就没在播」不能只看 STATE_IDLE：用户点歌后立刻回桌面时，
            // playSong 仍在异步解析真实地址（弱网可达数秒），此刻播放器正好是 IDLE，
            // 若就此收摊，随后的 setMediaItem/play 会得到一个已经停掉的前台服务 ——
            // 退化成「无通知的后台播放」，很快被系统冻结。
            // 因此只要还有 playWhenReady 意图或存在待播放曲目，就必须保留服务。
            val hasPlaybackIntent = player != null &&
                (player.playWhenReady || PlaybackQueueManager.currentSongFlow.value != null)
            if (player == null || (player.playbackState == Player.STATE_IDLE && !hasPlaybackIntent)) {
                // 确实没有任何播放意图：保留服务没有意义，按常规收摊
                stopKeepAliveWatchdog()
                unregisterScreenStateReceiver()
                PlaybackWakeLockManager.release()
                stopForegroundCompat()
                stopSelf()
                return
            }
            val currentPos = player.currentPosition.coerceAtLeast(0L)
            // 落盘放到 IO 线程：commitSync 会强制 fsync，电视盒子的 eMMC 上可能耗时数十毫秒，
            // 放在主线程正是"回桌面瞬间卡一下"的来源
            serviceScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        PlaybackQueueManager.savePlaybackState(
                            applicationContext,
                            positionMs = currentPos,
                            commitSync = true
                        )
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "保存播放进度失败 (onTaskRemoved)", e)
                }
            }
            // 前台状态与唤醒锁都要在位，否则回到桌面后系统可能立刻冻结本进程
            startImmediateForeground()
            PlaybackWakeLockManager.ensureHeld(this)
            Log.i(TAG, "onTaskRemoved: 保持后台播放 (pos=$currentPos)")
        } catch (e: Throwable) {
            // 任何异常都不能演变成停播
            Log.e(TAG, "onTaskRemoved handling failed", e)
        }
    }

    /**
     * 保活看门狗：只修"该播却卡住"，不做任何自作主张的播放。
     *
     * 动作条件必须**同时**满足（5 秒一轮，连续 3 轮 ≈15 秒才动作）：
     * - `playWhenReady == true`：用户/队列明确要求播放（暂停时绝不复活）
     * - `state ∈ {READY, BUFFERING}`：排除 IDLE(没准备好) 与 ENDED(单曲播完不循环，属正常结束，不重播)
     * - `!isPlaying`：确实没有在出声
     * - 抑制原因为 NONE：排除音频焦点被抢、HDMI 路由切换等**系统层面的正常抑制**，
     *   这类情况抢播会与 Media3 争夺控制权，反而制造"暂停后又自己响"的怪异现象
     * - 播放器仍是当前共享单例：软解重建期间的短暂错位不误判
     *
     * 动作只有**一个** `player.play()` —— 不 prepare、不换 MediaItem、不动播放列表与进度。
     * 同一个停滞窗口最多修 3 次，一旦恢复播放立刻清零；看门狗自身也不做任何 UI 操作。
     *
     * 与 [PlaybackQueueManager] 里的解码看门狗**谓词互斥**：那个只在真正的硬解故障
     * (缓冲充足但位置长期不前进) 时把播放器重建为软解并自行退出，不会与本看门狗同时发力。
     */
    private fun startKeepAliveWatchdog() {
        if (keepAliveWatchdogJob?.isActive == true) return
        keepAliveWatchdogJob = serviceScope.launch {
            var stalledTicks = 0
            var repairCount = 0
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    val player = exoPlayer ?: continue
                    if (player !== Media3Factory.getSharedExoPlayer(this@PlaybackService)) {
                        stalledTicks = 0
                        continue
                    }
                    val shouldBePlaying = player.playWhenReady &&
                        (player.playbackState == Player.STATE_READY ||
                            player.playbackState == Player.STATE_BUFFERING) &&
                        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    if (!shouldBePlaying || player.isPlaying) {
                        // 恢复正常（或本就不该播）→ 计数清零，配额也随之恢复
                        if (player.isPlaying) repairCount = 0
                        stalledTicks = 0
                        continue
                    }
                    stalledTicks++
                    if (stalledTicks < WATCHDOG_STALL_TICKS) continue
                    stalledTicks = 0
                    if (repairCount >= WATCHDOG_MAX_REPAIRS) {
                        // 反复修不好说明不是"卡住"而是别的问题（网络/解码/服务端），
                        // 继续 play() 只会反复打断，交给既有错误处理更稳妥
                        Log.w(TAG, "保活看门狗放弃: 已达单次停滞修复上限")
                        continue
                    }
                    repairCount++
                    Log.w(TAG, "保活看门狗: 检测到停滞 (state=${player.playbackState}), 第 $repairCount 次尝试恢复")
                    player.play()
                } catch (e: Throwable) {
                    Log.w(TAG, "保活看门狗循环异常", e)
                }
            }
        }
    }

    private fun stopKeepAliveWatchdog() {
        keepAliveWatchdogJob?.cancel()
        keepAliveWatchdogJob = null
    }

    /**
     * 动态注册熄屏/亮屏广播。
     *
     * `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` / `ACTION_SHUTDOWN` **不能**在 manifest 中静态注册，
     * 必须由活着的组件动态注册才收得到。这里收到熄屏时只做两件事：确保前台状态在位、
     * 确保唤醒锁在位。**绝不主动起播** —— 用户没按播放键时不该因为熄屏而开始出声。
     */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            val player = exoPlayer
                            val playing = player != null && player.playWhenReady &&
                                player.playbackState != Player.STATE_IDLE
                            Log.i(TAG, "屏幕关闭, playWhenReady=${player?.playWhenReady}, isPlaying=${player?.isPlaying}")
                            if (playing) {
                                // 屏保/待机时最容易掉出前台与唤醒锁，这里补一次
                                startImmediateForeground()
                                PlaybackWakeLockManager.ensureHeld(this@PlaybackService)
                            }
                        }

                        Intent.ACTION_SCREEN_ON -> {
                            Log.i(TAG, "屏幕点亮, isPlaying=${exoPlayer?.isPlaying}")
                            updateForegroundNotification(exoPlayer?.isPlaying == true)
                        }

                        Intent.ACTION_SHUTDOWN -> {
                            // 车机熄火 / 中控断电走的是系统有序关机流程，会广播 ACTION_SHUTDOWN，
                            // 但通常只留几秒就直接断电。onPause/onStop 依赖 Activity 走完生命周期，
                            // 熄火瞬间经常来不及执行 → 最后几秒进度丢失。
                            //
                            // 这里必须**同步**落盘：savePlaybackState(commitSync = true) 是普通函数、
                            // 内部直接 prefs.edit().commit()，不经过协程调度，onReceive 返回前就已写完。
                            // 广播线程上做一次小文件 commit 在关机路径上是可以接受的代价。
                            val player = exoPlayer
                            val pos = player?.currentPosition?.takeIf { it > 0L }
                            Log.i(TAG, "系统关机/熄火, 同步落盘播放进度 positionMs=$pos")
                            PlaybackQueueManager.savePlaybackState(
                                applicationContext,
                                positionMs = pos,
                                commitSync = true
                            )
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SHUTDOWN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            screenStateReceiver = receiver
        } catch (e: Throwable) {
            Log.w(TAG, "注册熄屏广播失败", e)
        }
    }

    private fun unregisterScreenStateReceiver() {
        val receiver = screenStateReceiver ?: return
        screenStateReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (e: Throwable) {
            Log.w(TAG, "反注册熄屏广播失败", e)
        }
    }

    /** 撤销前台服务状态 (兼容 API 24 前后的两套 API) */
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "stopForeground failed", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val currentSong = PlaybackQueueManager.currentSongFlow.value
        val title = currentSong?.title?.ifBlank { null } ?: getString(R.string.app_name)
        val artist = currentSong?.artist?.ifBlank { null } ?: if (isPlaying) "正在播放" else "已暂停"
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startImmediateForeground() {
        try {
            val notification = buildNotification(exoPlayer?.isPlaying == true)
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    private fun updateForegroundNotification(isPlaying: Boolean) {
        // 用户已彻底关闭播放时不再刷新通知：播放器是进程级单例，彻底关闭后残留的
        // 状态回调会把媒体通知重新贴回通知栏/电视媒体卡片（TV-C14）。
        if (isExplicitStopping) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(isPlaying))
        } catch (e: Throwable) {
            Log.e(TAG, "Error updating foreground notification", e)
        }
    }

    override fun onDestroy() {
        try {
            stopKeepAliveWatchdog()
            unregisterScreenStateReceiver()
            Media3Factory.removePlayerSwapListener(playerSwapListener)
            // 播放器是进程级共享单例，不会随本服务销毁；
            // 不摘掉通知监听会让它一直持有本 Service 实例 (每停一次泄漏一个)
            try {
                exoPlayer?.removeListener(notificationListener)
            } catch (_: Throwable) {
            }
            // 唤醒锁有意**不无条件释放**：播放器是进程单例，服务销毁后音频很可能仍在播放，
            // 此刻放锁会让系统立刻挂起 CPU，制造"熄屏后断流"。
            // releaseIfNotPlaying 只在播放器确实空闲/结束时才真正释放 —— 请勿"简化"成 release()。
            exoPlayer?.let { player ->
                PlaybackWakeLockManager.releaseIfNotPlaying(player)
            }
            mediaSession?.run {
                release()
                mediaSession = null
            }
            exoPlayer = null
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        try {
            serviceScope.cancel()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
