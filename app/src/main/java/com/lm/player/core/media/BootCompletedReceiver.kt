package com.lm.player.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lm.player.MainActivity

/**
 * 安卓系统开机自启动广播接收器（电视/车机版）。
 *
 * 与手机端 `app/` 模块下的同名实现保持一致的行为契约：
 * 开关默认**关闭**；开启后开机完成时自动拉起 [MainActivity] 与 [PlaybackService]。
 *
 * 实现要点（每一条都有具体原因，改动前请先读）：
 * 1. `directBootAware` + 双存储写入：开机广播可能早于用户解锁，此时凭据保护的 SharedPreferences
 *    不可读。若不把开关同时写进**设备保护存储**，开机早期读回的是默认 false，开关形同虚设。
 * 2. 未解锁时**直接返回**：等解锁后的 `ACTION_BOOT_COMPLETED` 再来，避免在数据库/媒体库尚不可用时抢跑。
 * 3. **先起服务、再拉界面，且各自独立 try/catch**：起前台服务是保活的主路径（必须成功尝试）；
 *    `startActivity` 受 Android 10+ 后台启动 Activity 限制，在部分盒子上会被静默丢弃——
 *    属预期现象，绝不能让它抛异常把服务启动也一起带崩。
 * 4. 延迟 1500 ms：系统刚开机时 PMS/AMS 仍在忙，立即启动容易被拒。
 *
 * ⚠️ 将来的地雷：`targetSdk` 升到 35 后，Android 15 会禁止由 `BOOT_COMPLETED` 启动
 * `mediaPlayback` 类型的前台服务。本工程当前 targetSdk 34，暂不受影响。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        const val PREFS_NAME = "zds_auto_play_prefs"
        const val KEY_AUTO_LAUNCH_ON_BOOT = "auto_launch_on_boot"

        fun isAutoLaunchOnBootEnabled(context: Context): Boolean {
            return try {
                val appCtx = context.applicationContext
                val userUnlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val um = appCtx.getSystemService(Context.USER_SERVICE) as? UserManager
                    um?.isUserUnlocked != false
                } else {
                    true
                }
                if (userUnlocked) {
                    val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.getBoolean(KEY_AUTO_LAUNCH_ON_BOOT, false)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val dpCtx = appCtx.createDeviceProtectedStorageContext()
                    dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(KEY_AUTO_LAUNCH_ON_BOOT, false)
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read auto_launch_on_boot preference", e)
                false
            }
        }

        fun setAutoLaunchOnBootEnabled(context: Context, enabled: Boolean) {
            try {
                val appCtx = context.applicationContext
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_LAUNCH_ON_BOOT, enabled)
                    .apply()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val dpCtx = appCtx.createDeviceProtectedStorageContext()
                    dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_AUTO_LAUNCH_ON_BOOT, enabled)
                        .apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist auto_launch_on_boot preference", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // 若处于 Direct Boot 锁屏未解锁阶段，等待解锁后的 ACTION_BOOT_COMPLETED 再拉起主界面与数据库
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um?.isUserUnlocked == false) {
                Log.i(TAG, "Device still locked on $action, waiting for unlocked BOOT_COMPLETED")
                return
            }
        }

        val autoLaunch = isAutoLaunchOnBootEnabled(context)
        Log.i(TAG, "Received boot broadcast ($action), autoLaunchOnBoot=$autoLaunch")
        if (!autoLaunch) return

        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            // 主路径：把前台播放服务拉起来（BOOT_COMPLETED 场景允许启动前台服务）
            try {
                val serviceIntent = Intent(appContext, PlaybackService::class.java)
                ContextCompat.startForegroundService(appContext, serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Start PlaybackService on boot failed", e)
            }

            // 尽力而为：部分盒子会因后台启动 Activity 限制静默丢弃，属预期
            try {
                val activityIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    putExtra("from_boot_completed", true)
                }
                appContext.startActivity(activityIntent)
                Log.i(TAG, "Launched MainActivity on system boot completed")
            } catch (e: Exception) {
                Log.e(TAG, "Launch MainActivity on boot failed", e)
            }
        }, 1500L)
    }
}
