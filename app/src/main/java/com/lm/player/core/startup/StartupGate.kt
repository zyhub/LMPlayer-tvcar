package com.lm.player.core.startup

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 启动闸门（TV 车机端启动提速）。
 *
 * 冷启动时只做三件事：画出基础界面（顶部导航 / 左侧播控卡 / 当前页骨架）、恢复上次的歌曲并继续播放、
 * 读出当前页需要的数据。其余工作（音源脚本引擎、服务器登录与曲库同步、遗留数据清理、
 * 发现页数据、最近播放同步、更新检查……）全部 [await] 本闸门，等「首帧已出 + 续播已开始」后再做，
 * 不再和首帧、续播抢 CPU 与磁盘。
 *
 * 放行时机（任一满足即放行，只放行一次）：
 * - MainActivity：首帧已绘制，且续播已出声（或判定无需续播）后再静置 1.2 秒；
 * - 用户主动切到其它页面（搜索 / 我的 / 播客 / 设置）—— 用户已开始操作，不再等；
 * - 兜底：进程启动 [FALLBACK_MS] 后（小组件 / 开机自启只拉起服务、没有界面时也能执行延后任务）。
 */
object StartupGate {
    private const val TAG = "StartupGate"
    const val FALLBACK_MS = 8_000L

    private val _released = MutableStateFlow(false)
    val released: StateFlow<Boolean> = _released.asStateFlow()
    val isReleased: Boolean get() = _released.value

    @Volatile
    private var armed = false

    /** 进程启动时调用一次：挂上兜底计时 */
    fun armFallback() {
        if (armed) return
        armed = true
        Handler(Looper.getMainLooper()).postDelayed({ release("fallback") }, FALLBACK_MS)
    }

    fun release(reason: String) {
        if (_released.compareAndSet(expect = false, update = true)) {
            StartupTrace.mark(StartupTrace.GATE_RELEASED)
            Log.i(TAG, "startup gate released: $reason (${StartupTrace.sinceProcessStartMs()}ms)")
        }
    }

    /** 挂起直到闸门放行；已放行时立即返回 */
    suspend fun await() {
        if (_released.value) return
        _released.first { it }
    }
}

/** 进程内固定的时间戳（用于缺失时间戳的兜底值，保证同一进程内多次计算结果一致） */
object StableClock {
    val processStartMs: Long = System.currentTimeMillis()
}

/**
 * 启动耗时打点：记录本次进程各关键节点距进程启动的毫秒数，启动完成后写入本机，
 * 设置页「启动耗时」一行显示最近一次的结果，用于在车机上直接对比优化前后。
 */
object StartupTrace {
    private const val PREFS = "lemon_startup_trace"
    private const val KEY_LAST = "last_summary"
    private const val PERSIST_DELAY_MS = 20_000L

    const val APP_CREATE = "app_create"
    const val ACTIVITY_CREATE = "activity_create"
    const val FIRST_FRAME = "first_frame"
    const val PLAYBACK_STARTED = "playback_started"
    const val LIBRARY_READY = "library_ready"
    const val GATE_RELEASED = "gate_released"

    private val labels = linkedMapOf(
        FIRST_FRAME to "首帧",
        PLAYBACK_STARTED to "开始出声",
        LIBRARY_READY to "曲库就绪",
        GATE_RELEASED to "后台任务开始"
    )

    /** 进程启动时刻（elapsedRealtime）；API 24 以下取本对象首次加载的时刻 */
    private val processStartElapsed: Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartElapsedRealtime()
        else SystemClock.elapsedRealtime()

    private val marks = LinkedHashMap<String, Long>()

    @Volatile
    private var persistScheduled = false

    fun sinceProcessStartMs(): Long = SystemClock.elapsedRealtime() - processStartElapsed

    /** 记录一个节点；同名节点只记第一次 */
    fun mark(name: String) {
        synchronized(marks) {
            if (!marks.containsKey(name)) marks[name] = sinceProcessStartMs()
        }
    }

    /**
     * 启动 [PERSIST_DELAY_MS] 后把本次结果写入本机。
     * 只有界面冷启动（记到了首帧）才写，避免被小组件 / 开机自启拉起的无界面进程覆盖。
     */
    fun schedulePersist(context: Context) {
        if (persistScheduled) return
        persistScheduled = true
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            val summary = summary() ?: return@postDelayed
            runCatching {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, summary).apply()
            }
            Log.i("StartupTrace", summary)
        }, PERSIST_DELAY_MS)
    }

    private fun summary(): String? {
        val snapshot = synchronized(marks) { LinkedHashMap(marks) }
        if (!snapshot.containsKey(FIRST_FRAME)) return null
        return labels.mapNotNull { (key, label) ->
            snapshot[key]?.let { "$label ${formatSeconds(it)}" }
        }.joinToString(" · ")
    }

    /** 最近一次界面冷启动的耗时摘要；还没有记录时返回 null */
    fun lastSummary(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun formatSeconds(ms: Long): String = String.format(java.util.Locale.US, "%.1f 秒", ms / 1000.0)
}
