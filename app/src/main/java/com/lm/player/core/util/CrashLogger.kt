package com.lm.player.core.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃日志自诊断 (TV / 车机无 adb 场景下的兜底排查手段)
 *
 * 电视与车机设备通常无法直接抓取 logcat，界面一旦闪退只能靠用户口述现象，定位成本极高。
 * 这里在主进程安装全局未捕获异常处理器：进程结束前把完整堆栈写入
 * filesDir/crash_last.txt (应用私有目录，不需要任何存储权限)，下次启动时由
 * CrashReportDialog 原样展示，用户截图即可回传完整堆栈。
 *
 * 注意：处理器只负责「记录」，记录完成后仍然交回系统默认处理器，
 * 因此「应用已停止运行」等原生行为与崩溃上报完全不受影响。
 */
object CrashLogger {

    private const val FILE_NAME = "crash_last.txt"
    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_SHOWN_STAMP = "crash_log_shown_stamp"

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
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
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
            pw.println("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            pw.println("版本: " + versionName(context))
            pw.println(
                "设备: " + Build.MANUFACTURER + " " + Build.MODEL +
                    " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
            )
            pw.println("线程: " + thread.name)
            pw.println("----------------------------------------")
            throwable.printStackTrace(pw)
        }
        runCatching { file(context).writeText(writer.toString()) }
    }

    private fun versionName(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
