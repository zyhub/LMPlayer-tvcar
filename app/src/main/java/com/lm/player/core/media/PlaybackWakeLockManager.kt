package com.lm.player.core.media

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * 播放期间的应用级唤醒锁（电视/车机版后台保活，纵深防御层）。
 *
 * **为什么挂在播放器而不是 Service 上**：[Media3Factory] 里的 ExoPlayer 是**进程级单例**，
 * 可以活过 [PlaybackService]（服务 stopSelf 之后音乐仍在放）。若把锁绑在 Service 生命周期上，
 * `onDestroy`/`onTaskRemoved` 一执行就会把 CPU 锁放掉，而此刻音频仍在播放 —— 反而亲手制造
 * "熄屏后断流"。所以锁的持有条件只看**播放器状态**，不看服务是否存活。
 *
 * **这不是屏保停播的主修复**：ExoPlayer 自身在播放时已经通过
 * `setWakeMode(C.WAKE_MODE_NETWORK)` 持有 PARTIAL_WAKE_LOCK + WifiLock
 * （media3-exoplayer 内的 `WakeLockManager`/`WifiLockManager`）。真正的确定性修复是
 * manifest 的 `stopWithTask="false"` 加上重写 [PlaybackService.onTaskRemoved]。
 * 本类用于覆盖"服务被顶掉 / 播放器被软解重建"的窗口期，属纵深防御。
 *
 * 所有 acquire/release 都 **先判 `isHeld` 再动作**，且 `setReferenceCounted(false)`，
 * 因此不可能抛出 WakeLock 的 under-lock / over-lock 运行时异常。
 */
object PlaybackWakeLockManager {

    private const val TAG = "PlaybackWakeLockMgr"
    private const val WAKE_LOCK_TAG = "LMPlayerTV:PlaybackWakeLock"
    private const val WIFI_LOCK_TAG = "LMPlayerTV:PlaybackWifiLock"

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var listenedPlayer: Player? = null
    private var playerListener: Player.Listener? = null

    /** 是否应当持有唤醒锁：用户希望播放 且 播放器既没空闲也没放完 */
    private fun shouldHold(player: Player): Boolean {
        return player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
    }

    /**
     * 绑定到播放器：首次创建与软解重建（[Media3Factory.rebuildWithSoftwareDecoding]）都会走到这里。
     * 幂等，重复绑定同一实例只做一次状态重算。
     */
    @Synchronized
    fun bind(context: Context, player: Player) {
        val appContext = context.applicationContext
        if (listenedPlayer === player) {
            recompute(appContext, player)
            return
        }
        // 换绑新播放器前先摘掉旧实例上的监听，避免已释放的旧播放器继续驱动锁
        playerListener?.let { old ->
            try {
                listenedPlayer?.removeListener(old)
            } catch (_: Throwable) {
            }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                recompute(appContext, player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // reason 是区分"用户主动暂停"与"系统抑制"的关键证据，用户反馈异常时可直接看日志
                Log.i(TAG, "playWhenReady=$playWhenReady (reason=$reason)")
                recompute(appContext, player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                recompute(appContext, player)
            }

            override fun onPlayerError(error: PlaybackException) {
                // 出错后播放器会转到 IDLE，这里显式释放，避免锁悬挂
                Log.w(TAG, "player error, releasing wake locks", error)
                release()
            }

            override fun onPlaybackSuppressionReasonChanged(suppressionReason: Int) {
                // 仅记录不处理：用于排查"熄屏时 HDMI 音频路由变化触发抑制"这一类成因
                Log.i(TAG, "playbackSuppressionReason=$suppressionReason")
            }
        }
        listenedPlayer = player
        playerListener = listener
        try {
            player.addListener(listener)
        } catch (e: Throwable) {
            Log.w(TAG, "addListener failed", e)
        }
        recompute(appContext, player)
    }

    private fun recompute(context: Context, player: Player) {
        if (shouldHold(player)) ensureHeld(context) else release()
    }

    /** 获取唤醒锁（幂等：已持有则什么也不做） */
    @Synchronized
    fun ensureHeld(context: Context) {
        val appContext = context.applicationContext
        try {
            val lock = wakeLock ?: run {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val created = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
                wakeLock = created
                created
            }
            // 有意不设超时：播放期间必须持续持有，超时会在播放中途被系统自动释放。
            // 释放时机完全由播放器状态驱动（暂停/停止/播放结束/出错即释放）。
            if (lock != null && !lock.isHeld) lock.acquire()
        } catch (e: Throwable) {
            Log.w(TAG, "acquire wakeLock failed", e)
        }

        // WiFi 锁单独 try：盒子多数走以太网，且本工程未声明 ACCESS_WIFI_STATE，
        // 拿不到属于正常情况，绝不能让它的失败影响上面已经拿到的 CPU 锁
        try {
            val lock = wifiLock ?: run {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                val created = wm?.createWifiLock(mode, WIFI_LOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
                wifiLock = created
                created
            }
            if (lock != null && !lock.isHeld) lock.acquire()
        } catch (e: Throwable) {
            Log.w(TAG, "acquire wifiLock failed", e)
        }
    }

    /** 释放唤醒锁（幂等：未持有时什么也不做） */
    @Synchronized
    fun release() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "release wakeLock failed", e)
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "release wifiLock failed", e)
        }
    }

    /**
     * 供 [PlaybackService.onDestroy] 使用。
     *
     * **必须**只在播放器确实不在播放时释放，不能无条件 release：
     * 播放器是进程单例，服务销毁后音频很可能仍在播放，此时放锁等于让系统把 CPU 挂起 →
     * 熄屏断流。这个判断是有意为之，请勿"简化"掉。
     */
    @Synchronized
    fun releaseIfNotPlaying(player: Player) {
        if (!shouldHold(player)) release()
    }
}
