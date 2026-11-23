package com.lm.player.core.util

import android.content.Context
import android.os.Build
import com.lm.player.core.security.LogRedactor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃日志自诊断 (TV / 车机端无 adb 场景下的兜底排查手段，对齐手机端异常捕获与脱敏机制)
 */
object CrashLogger {

    private const val FILE_NAME = "crash_last.txt"
    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_SHOWN_STAMP = "crash_log_shown_stamp"
    private const val MAX_LOG_LENGTH = 180_000

    /** 崩溃日志文件 (应用私有目录，随卸载一并清除) */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * 安装全局崩溃捕获。重复调用安全：多次安装时保留原有处理器链尾，
     * 避免相互覆盖导致系统崩溃上报失效。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private const val KEY_LAST_BUILD = "crash_log_last_build"
    private const val PREV_BUILD_FILE_NAME = "crash_prev_build.txt"

    /**
     * 换了安装包（构建时间变了）后的第一次启动：把旧包留下、还没弹出过的记录挪到 crash_prev_build.txt，
     * 不再当成当前版本的问题弹出来（2026-11-17：用户装新包后，旧包写下的「主线程卡住」记录在新包第一次
     * 启动时弹出，被误以为新包还有问题）。必须在 Application.onCreate 里、任何新记录写入之前调用。
     */
    fun discardIfBuildChanged(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = com.lm.player.BuildConfig.BUILD_TIME
            val last = prefs.getString(KEY_LAST_BUILD, null)
            if (last == current) return
            val f = file(context)
            if (f.exists() && f.length() > 0L) {
                val prev = File(context.filesDir, PREV_BUILD_FILE_NAME)
                prev.delete()
                if (!f.renameTo(prev)) f.delete()
            }
            prefs.edit().putString(KEY_LAST_BUILD, current).commit()
        }
    }

    /** 取出尚未展示过的崩溃堆栈；没有新崩溃时返回 null */
    fun pendingReport(context: Context): String? {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return null
        val shownStamp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SHOWN_STAMP, 0L)
        if (f.lastModified() <= shownStamp) return null
        return runCatching { f.readText() }.getOrNull()?.let { LogRedactor.text(it) }?.takeIf { it.isNotBlank() }
    }

    /** 标记当前崩溃日志已展示，避免每次启动反复弹出同一份报告 */
    fun markReportShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SHOWN_STAMP, file(context).lastModified())
            .apply()
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val writer = StringWriter()
        PrintWriter(writer).use { pw ->
            pw.println("LMPlayer TV车机版 崩溃日志")
            pw.println("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            pw.println("版本: " + versionName(context))
            pw.println(
                "设备: " + Build.MANUFACTURER + " " + Build.MODEL +
                    " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
            )
            pw.println("线程: " + thread.name + " (id=" + thread.id + ")")
            pw.println("----------------------------------------")
            throwable.printStackTrace(pw)
        }
        val rawLog = writer.toString()
        val sanitized = LogRedactor.text(rawLog).take(MAX_LOG_LENGTH)
        runCatching { file(context).writeText(sanitized) }
    }

    /**
     * 记一份「非 Java 异常」的报告（主线程卡死、系统层崩溃、被系统结束等，见 [ExitDiagnostics]），
     * 下次启动与崩溃日志一样弹出。上一份还没展示过时追加在后面，不覆盖。
     */
    @Synchronized
    fun writeExtraReport(context: Context, title: String, body: String) {
        runCatching {
            val text = buildString {
                appendLine("LMPlayer TV车机版 $title")
                appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                appendLine("版本: " + versionName(context))
                appendLine(
                    "设备: " + Build.MANUFACTURER + " " + Build.MODEL +
                        " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                )
                appendLine("----------------------------------------")
                append(body)
            }
            val sanitized = LogRedactor.text(text)
            val f = file(context)
            val shownStamp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_SHOWN_STAMP, 0L)
            val unshown = f.exists() && f.length() > 0L && f.lastModified() > shownStamp
            val merged = if (unshown) f.readText() + "\n\n========================================\n\n" + sanitized else sanitized
            f.writeText(merged.takeLast(MAX_LOG_LENGTH))
        }
    }

    /** 版本号 + 构建时间（版本号锁定后每次出包都相同，构建时间才能分辨是哪一次的包） */
    private fun versionName(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        "$name（构建 ${com.lm.player.BuildConfig.BUILD_TIME}）"
    }.getOrDefault("?")
}
