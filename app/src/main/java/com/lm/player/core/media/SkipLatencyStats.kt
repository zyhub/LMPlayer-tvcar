package com.lm.player.core.media

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 切歌耗时统计（2026-11-13，用户反馈「软件在后台时切歌慢 1～2 秒」）。
 *
 * 每次切歌记一条：从收到切歌指令到真正出声用了多久，拆成「取播放地址」和「缓冲到出声」两段，
 * 并标明当时软件在前台还是后台、指令来自哪里（桌面小组件 / 方向盘按键 / 系统媒体控制 / 界面）。
 * 设置 →「运行诊断 → 切歌耗时」可以看 / 复制，车机上直接对比。
 *
 * 只在主线程调用（PlaybackQueueManager 的播放协程与播放器回调都在主线程）。
 */
object SkipLatencyStats {
    private const val MAX_RECORDS = 10

    /** 界面是否在前台（MainActivity onResume / onStop 维护） */
    @Volatile var appInForeground: Boolean = false

    @Volatile private var pendingSource: String? = null
    @Volatile private var pendingSourceAt = 0L

    private class Pending(
        val songId: String,
        val title: String,
        val source: String,
        val background: Boolean,
        val startedAt: Long,
        val wallTime: Long
    ) {
        var resolvedAt = 0L
        var prefetched = false
    }

    private var current: Pending? = null
    private val records = ArrayDeque<String>()

    /** 切歌入口调用：记下这次切歌是谁发起的（3 秒内的下一次播放请求才算它的） */
    fun noteSource(source: String) {
        pendingSource = source
        pendingSourceAt = SystemClock.elapsedRealtime()
    }

    fun onPlayRequested(songId: String, title: String) {
        val now = SystemClock.elapsedRealtime()
        val src = pendingSource?.takeIf { now - pendingSourceAt < 3_000L } ?: "界面 / 自动下一首"
        pendingSource = null
        current = Pending(songId, title, src, !appInForeground, now, System.currentTimeMillis())
    }

    fun onResolved(songId: String, prefetched: Boolean) {
        val c = current ?: return
        if (c.songId != songId) return
        c.resolvedAt = SystemClock.elapsedRealtime()
        c.prefetched = prefetched
    }

    /** 最近一次出声的歌与时间（elapsedRealtime）；语音日志据此算「从收到指令到出声」用了多久 */
    @Volatile var lastSoundSongId: String? = null
        private set
    @Volatile var lastSoundAt = 0L
        private set

    fun onPlaying(songId: String?) {
        lastSoundSongId = songId
        lastSoundAt = SystemClock.elapsedRealtime()
        val c = current ?: return
        if (c.songId != songId) return
        current = null
        val now = SystemClock.elapsedRealtime()
        val total = now - c.startedAt
        val resolve = if (c.resolvedAt > 0L) c.resolvedAt - c.startedAt else -1L
        val buffer = if (c.resolvedAt > 0L) now - c.resolvedAt else -1L
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(c.wallTime))
        val line = buildString {
            append(time).append(' ')
            append(if (c.background) "后台" else "前台").append('·').append(c.source)
            append(" → 出声 ").append(sec(total))
            if (resolve >= 0L) {
                append("（取地址 ").append(sec(resolve))
                if (c.prefetched) append("·用了预取")
                append(" / 缓冲 ").append(sec(buffer)).append('）')
            }
            append(" 《").append(c.title.take(16)).append('》')
        }
        synchronized(records) {
            records.addFirst(line)
            while (records.size > MAX_RECORDS) records.removeLast()
        }
    }

    fun summary(): String = synchronized(records) {
        if (records.isEmpty()) "暂无记录：切几首歌后再来看（前台、后台各切几次对比）" else records.joinToString("\n")
    }

    private fun sec(ms: Long): String = String.format(Locale.US, "%.2f 秒", ms / 1000.0)
}
