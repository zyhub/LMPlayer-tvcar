package com.lm.player.core.startup

import android.graphics.drawable.BitmapDrawable
import coil.EventListener
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * 封面加载统计（本次进程内）：车机上取不到 logcat，封面不显示时靠这一行判断是哪一类问题 ——
 * 加载失败（看失败数与最近一次失败原因）、解码出来的图异常小、还是请求一直没有结束（进行中很多）。
 * 设置 → 存储权限诊断 →「封面加载统计」显示并可复制。只在内存里计数，不写盘。
 */
object ImageLoadStats : EventListener {
    private val started = AtomicInteger()
    private val succeeded = AtomicInteger()
    private val failed = AtomicInteger()
    private val cancelled = AtomicInteger()
    private val tiny = AtomicInteger()

    @Volatile
    private var lastError: String? = null

    override fun onStart(request: ImageRequest) {
        started.incrementAndGet()
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        succeeded.incrementAndGet()
        // 解码结果只有几个像素：通常是首次测量时拿到了异常小的尺寸，画面上看起来就像没有封面
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null && bitmap.width <= 8 && bitmap.height <= 8) tiny.incrementAndGet()
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        failed.incrementAndGet()
        val kind = when (val data = request.data) {
            is String -> when {
                data.startsWith("http", ignoreCase = true) -> "网络"
                data.startsWith("content:", ignoreCase = true) -> "系统媒体库"
                else -> "本机文件"
            }
            else -> data.javaClass.simpleName
        }
        val t = result.throwable
        lastError = "$kind：${t.javaClass.simpleName}${t.message?.let { " $it" }.orEmpty()}".take(160)
    }

    override fun onCancel(request: ImageRequest) {
        cancelled.incrementAndGet()
    }

    fun summary(): String {
        val s = started.get()
        val ok = succeeded.get()
        val err = failed.get()
        val cancel = cancelled.get()
        val pending = (s - ok - err - cancel).coerceAtLeast(0)
        return buildString {
            append("开始 $s · 成功 $ok · 失败 $err · 取消 $cancel · 进行中 $pending")
            if (tiny.get() > 0) append(" · 异常小图 ${tiny.get()}")
            lastError?.let { append("；最近失败：$it") }
        }
    }
}
