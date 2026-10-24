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

    private fun versionName(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
