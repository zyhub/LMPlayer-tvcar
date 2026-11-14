package com.lm.player.core.startup

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale

/**
 * 界面流畅度统计（2026-11-13 性能优化第 0 项）：车机连不上 adb，用系统的逐帧耗时回调在应用内统计，
 * 设置页「运行诊断 → 界面流畅度」直接看 / 复制，用来对比每批优化前后的效果。
 *
 * - 按「当前在看的界面」分开统计（我的 / 发现 / 播放页·黑胶 …），同一个界面才有可比性；
 * - 卡顿帧：一帧耗时超过 1 个刷新周期（60Hz 屏约 16.7ms），即这一帧没赶上屏幕刷新；
 * - 严重卡顿：超过 50ms（肉眼可见的顿挫）；
 * - 只统计真正画出来的帧：界面静止时系统不出帧，不会拉低平均值；每个窗口的第一帧（排版初始化）不计入。
 *
 * 回调跑在独立的后台线程上，每帧只做几次加法，对被测界面几乎没有影响。
 */
object FrameStats {

    class Bucket {
        var frames = 0L
        var janky = 0L
        var severe = 0L
        var totalNanos = 0L
        var maxNanos = 0L
    }

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()

    @Volatile
    var scope: String = "启动"

    @Volatile
    private var vsyncNanos = 16_666_667L
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    fun attach(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (listener != null) return
        runCatching {
            @Suppress("DEPRECATION")
            val hz = activity.windowManager.defaultDisplay.refreshRate
            if (hz in 20f..240f) vsyncNanos = (1_000_000_000.0 / hz).toLong()
        }
        val t = HandlerThread("frame-stats").also { it.start() }
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total <= 0L) return@OnFrameMetricsAvailableListener
            synchronized(lock) {
                val b = buckets.getOrPut(scope) { Bucket() }
                b.frames++
                b.totalNanos += total
                if (total > b.maxNanos) b.maxNanos = total
                // 留 1ms 容差：刚好落在刷新边界上的帧不算卡顿
                if (total > vsyncNanos + 1_000_000L) b.janky++
                if (total > 50_000_000L) b.severe++
            }
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(l, Handler(t.looper)) }
            .onSuccess { thread = t; listener = l }
            .onFailure { t.quitSafely() }
    }

    fun detach(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val l = listener ?: return
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(l) }
        listener = null
        thread?.quitSafely()
        thread = null
    }

    fun reset() {
        synchronized(lock) { buckets.clear() }
    }

    /** 「性能模式 均衡（自动，动画 30 帧） · 主界面黑胶旋转 开」 */
    private fun modeLine(): String = runCatching {
        val mode = com.lm.player.core.designsystem.theme.SmoothMode
        val fps = if (mode.isActive) 20 else 30
        val auto = if (mode.setting == com.lm.player.core.designsystem.theme.PerformanceSetting.AUTO) "自动，" else ""
        val vinyl = if (com.lm.player.core.designsystem.theme.AppUiPrefs.mainVinylRotation) "开" else "关"
        "性能模式 ${mode.level.label}（${auto}动画 $fps 帧） · 主界面黑胶旋转 $vinyl"
    }.getOrDefault("")

    /** 一行一个界面：「播放页·黑胶：2310 帧 · 卡顿 4.2% · 严重 3 · 平均 9.8ms · 最长 120ms」 */
    fun summary(): String {
        val snapshot = synchronized(lock) {
            buckets.map { (k, b) -> k to Bucket().also { c -> c.frames = b.frames; c.janky = b.janky; c.severe = b.severe; c.totalNanos = b.totalNanos; c.maxNanos = b.maxNanos } }
        }
        val hz = 1_000_000_000.0 / vsyncNanos
        if (snapshot.isEmpty()) return "暂无数据（在要测的界面停留一会儿再回来看）"
        return buildString {
            append(String.format(Locale.US, "屏幕 %.0fHz", hz))
            // 2026-11-13：带上当前性能模式与主界面动画开关，测试报告里能直接看出模式有没有切成功
            append(" · ").append(modeLine())
            snapshot.filter { it.second.frames >= 30 }.sortedByDescending { it.second.frames }.forEach { (name, b) ->
                append('\n')
                append(
                    String.format(
                        Locale.US,
                        "%s：%d 帧 · 卡顿 %.1f%% · 严重 %d · 平均 %.1fms · 最长 %dms",
                        name,
                        b.frames,
                        b.janky * 100.0 / b.frames,
                        b.severe,
                        b.totalNanos / 1_000_000.0 / b.frames,
                        b.maxNanos / 1_000_000L
                    )
                )
            }
        }
    }
}
