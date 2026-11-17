package com.lm.player.core.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「没有崩溃日志的闪退」诊断（2026-11-13）。
 *
 * [CrashLogger] 只能接到 Java 异常。下面几种退出不经过 Java 异常，以前完全没有记录：
 * - 系统图形层 / 原生代码崩溃（native 崩溃）；
 * - 主线程卡死，被系统判定「无响应」后结束（车机上常常不弹无响应对话框，直接没了）；
 * - 内存不足被系统结束。
 *
 * 现在：
 * 1. 主线程看门狗：每秒探一次主线程，卡住超过 5 秒就把主线程正在执行的代码记下来；
 * 2. Android 11 及以上：下次启动时读系统记录的「上次退出原因」（原生崩溃 / 无响应 / 内存不足）；
 * 3. Android 10 及以下（车机）：上次是在前台时没了，就读一下系统崩溃日志缓冲区里本程序的记录（尽力而为）。
 *
 * 找到的都写进崩溃日志文件，下次启动与 Java 崩溃一样弹出「崩溃日志」窗口。
 */
object ExitDiagnostics {
    private const val TAG = "ExitDiagnostics"
    private const val PREFS = "exit_diagnostics"
    private const val KEY_FG = "foreground"
    private const val KEY_PID = "pid"
    private const val KEY_EXIT_SEEN_TS = "exit_info_seen_ts"
    private const val KEY_LOGCAT_SEEN_HASH = "logcat_seen_hash"

    /** 主线程卡住多久算卡死 */
    private const val HANG_MS = 5_000L

    private val startupCheckDone = CompletableDeferred<Unit>()

    @Volatile private var watchdogStarted = false
    @Volatile private var lastAck = 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 界面进入 / 离开前台时调用：用来判断上次是不是在前台时突然没了 */
    fun markForeground(context: Context, foreground: Boolean) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_FG, foreground).putInt(KEY_PID, Process.myPid()).apply()
        }
    }

    /** 启动时（后台线程）检查上次的退出原因；读崩溃日志的地方先等它做完（最多 1.5 秒） */
    fun onProcessStart(context: Context) {
        val app = context.applicationContext
        try {
            val p = prefs(app)
            val prevPid = p.getInt(KEY_PID, 0)
            val wasForeground = p.getBoolean(KEY_FG, false)
            p.edit().putBoolean(KEY_FG, false).putInt(KEY_PID, Process.myPid()).commit()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                checkExitInfo(app)
            } else if (wasForeground && prevPid != 0 && prevPid != Process.myPid()) {
                checkCrashBuffer(app, prevPid)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "startup exit check failed", e)
        } finally {
            startupCheckDone.complete(Unit)
        }
    }

    suspend fun awaitStartupCheck() {
        withTimeoutOrNull(1_500L) { startupCheckDone.await() }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun checkExitInfo(app: Context) {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val info = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
            .firstOrNull { it.processName == app.packageName } ?: return
        val p = prefs(app)
        if (info.timestamp <= p.getLong(KEY_EXIT_SEEN_TS, 0L)) return
        p.edit().putLong(KEY_EXIT_SEEN_TS, info.timestamp).apply()
        val inForeground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "系统层（原生代码）崩溃"
            ApplicationExitInfo.REASON_ANR -> "主线程无响应，被系统结束"
            ApplicationExitInfo.REASON_LOW_MEMORY -> if (inForeground) "内存不足，被系统结束" else return
            else -> return // Java 崩溃已由 CrashLogger 记录；用户关闭 / 后台回收等不算异常
        }
        val body = buildString {
            appendLine("上次退出原因：$reason")
            appendLine("退出时间：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(info.timestamp)))
            appendLine("系统说明：${info.description.orEmpty()}")
            appendLine("状态码：${info.status}　前后台：${if (inForeground) "前台" else "后台"}")
            appendLine("内存：PSS ${info.pss / 1024} MB　RSS ${info.rss / 1024} MB")
            // 无响应时系统附带各线程堆栈（文本），取主线程那一段
            if (info.reason == ApplicationExitInfo.REASON_ANR) {
                val trace = runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                if (!trace.isNullOrBlank()) {
                    val start = trace.indexOf("\"main\"").takeIf { it >= 0 } ?: 0
                    appendLine()
                    appendLine("主线程堆栈：")
                    append(trace.substring(start).take(8_000))
                }
            }
        }
        CrashLogger.writeExtraReport(app, "异常退出记录", body)
    }

    /** Android 10 及以下：读系统崩溃缓冲区里本程序的记录（系统不一定允许读，读不到就算了） */
    private fun checkCrashBuffer(app: Context, prevPid: Int) {
        // 已经有一份没展示过的 Java 崩溃日志：就是它，不用再从系统缓冲区里重复摘一份
        if (CrashLogger.pendingReport(app) != null) return
        val crash = runLogcat(listOf("logcat", "-d", "-b", "crash", "-v", "threadtime", "-t", "400"))
        if (crash.isEmpty()) return
        val pidTag = " $prevPid "
        val evidence = crash.filter { line ->
            line.contains(pidTag) || line.contains(app.packageName) || line.contains(" DEBUG ")
        }
        if (evidence.none { it.contains("Fatal signal") || it.contains(" DEBUG ") || it.contains("FATAL") || it.contains(app.packageName) }) return
        val p = prefs(app)
        val hash = evidence.joinToString("\n").hashCode()
        if (p.getInt(KEY_LOGCAT_SEEN_HASH, 0) == hash) return
        p.edit().putInt(KEY_LOGCAT_SEEN_HASH, hash).apply()
        // 附上上次进程最后的几十行日志：崩溃前在做什么
        val tail = runLogcat(listOf("logcat", "-d", "-b", "main", "-v", "threadtime", "--pid=$prevPid", "-t", "80"))
        val body = buildString {
            appendLine("上次在前台时程序被结束，系统崩溃记录如下：")
            evidence.takeLast(150).forEach { appendLine(it) }
            if (tail.isNotEmpty()) {
                appendLine()
                appendLine("上次运行最后的日志：")
                tail.forEach { appendLine(it) }
            }
        }
        CrashLogger.writeExtraReport(app, "异常退出记录", body)
    }

    private fun runLogcat(cmd: List<String>): List<String> = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val lines = proc.inputStream.bufferedReader().useLines { seq -> seq.take(2_000).toList() }
        proc.destroy()
        lines
    }.getOrDefault(emptyList())

    /**
     * 主线程看门狗：后台线程每秒往主线程投一个空任务，超过 5 秒没被执行就记下主线程当前堆栈。
     * 每次卡住只记一次，恢复后再卡会再记（每个进程最多 3 次）。
     */
    fun startWatchdog(context: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val ack = Runnable { lastAck = SystemClock.uptimeMillis() }
        lastAck = SystemClock.uptimeMillis()
        Thread({
            var reportedThisHang = false
            var reports = 0
            // 只累计「看门狗自己按时醒来、主线程却没回应」的时间（2026-11-15 修正误报）：
            // 以前直接拿「现在 − 上次回应」算，进程在后台被系统冻结（华为等省电策略）一冻十几分钟，
            // 解冻后看门狗先醒、主线程还没来得及处理探针，就报成「主线程卡住 1034 秒」——其实主线程一直是空闲的
            var stallMs = 0L
            var lastTick = SystemClock.uptimeMillis()
            while (true) {
                val postedAt = SystemClock.uptimeMillis()
                // 探针用「异步消息」（2026-11-16）：界面在后台时系统可能停发画面刷新信号，消息队列里留着一道
                // 「等下一帧刷新」的屏障，普通消息会被挡在后面，但主线程其实是空闲的（堆栈停在 nativePollOnce）。
                // 异步消息不受这道屏障影响，测的才是主线程真的有没有在忙
                main.sendMessage(android.os.Message.obtain(main, ack).apply { isAsynchronous = true })
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = SystemClock.uptimeMillis()
                val tick = now - lastTick
                lastTick = now
                if (tick > 3_000L) {
                    // 看门狗自己也被耽误了很久：整个进程被冻结 / 挂起过，这段时间不算主线程卡住
                    stallMs = 0L
                    reportedThisHang = false
                    continue
                }
                if (lastAck >= postedAt) {
                    // 主线程按时处理了探针
                    stallMs = 0L
                    reportedThisHang = false
                    continue
                }
                stallMs += tick
                if (stallMs >= HANG_MS && !reportedThisHang && reports < 3) {
                    val frames = Looper.getMainLooper().thread.stackTrace
                    // 主线程停在消息循环本身（没有任何业务代码在执行）：是空闲 / 刚被唤醒，不是卡住
                    if (isIdleLoopStack(frames)) continue
                    reportedThisHang = true
                    reports++
                    val stack = frames.joinToString("\n") { "    at $it" }
                    Log.w(TAG, "main thread stalled ${stallMs}ms")
                    CrashLogger.writeExtraReport(
                        app,
                        "主线程卡住记录",
                        "主线程已经 ${stallMs / 1000} 秒没有响应（超过 5 秒系统会判定无响应并可能直接结束程序）。\n" +
                            "卡住时主线程正在执行：\n$stack"
                    )
                }
            }
        }, "main-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** 主线程堆栈全是消息循环 / 进程入口本身（没有业务代码）：空闲或刚被唤醒 */
    private fun isIdleLoopStack(frames: Array<StackTraceElement>): Boolean {
        if (frames.isEmpty()) return true
        val idlePrefixes = listOf(
            "android.os.MessageQueue",
            "android.os.Looper",
            "android.os.ThreadLocalWorkSource",
            "android.os.Binder.",
            "java.lang.ThreadLocal",
            "android.app.ActivityThread",
            "java.lang.reflect.Method",
            "com.android.internal.os."
        )
        return frames.all { f -> idlePrefixes.any { f.className.startsWith(it) || "${f.className}.".startsWith(it) } }
    }
}
