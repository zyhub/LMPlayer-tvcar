package com.lm.player.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 第二十三节：系统媒体会话 (车机桌面播放卡片、锁屏、媒体中心) 用的封面加载器。
 *
 * Media3 默认的 DataSourceBitmapLoader 走它自己的 HttpURLConnection：
 * - 不认柠檬服务器的自签证书，也不带登录凭证 → 服务器封面一律加载失败；
 * - 按原图解码 → 扫描保存的内嵌原图可能几千像素，占用大量内存。
 * 这里改走 App 自己的 Coil 图片加载器 (与界面显示封面同一条通道：同一个 OkHttp、
 * 自签证书处理与鉴权都已就绪)，并统一缩到 [MAX_SIZE] 像素以内再交给系统。
 */
@UnstableApi
class SessionCoverBitmapLoader(context: Context) : BitmapLoader {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/", ignoreCase = true)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                if (bmp != null) future.set(bmp) else future.setException(IllegalArgumentException("无法解码封面数据"))
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val bmp = load(appContext, uri.toString())
                if (bmp != null) future.set(bmp) else future.setException(IllegalStateException("封面加载失败: $uri"))
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        return future
    }

    companion object {
        private const val TAG = "SessionCoverLoader"
        const val MAX_SIZE = 512

        private fun sampleSizeFor(w: Int, h: Int): Int {
            var sample = 1
            val longest = maxOf(w, h)
            while (longest / (sample * 2) >= MAX_SIZE) sample *= 2
            return sample
        }

        /**
         * 按封面地址加载缩小后的软件位图 (可跨进程传给系统)，失败返回 null。
         * 支持 http(s) 地址、`file://` 与不带协议的本地绝对路径、`content://`。
         */
        suspend fun load(context: Context, raw: String): Bitmap? {
            if (raw.isBlank()) return null
            // 第二十四节：本 App 的公开封面地址 → 直接读已生成的文件，或回到原始地址加载 (不绕 Provider)
            SessionArtworkStore.nameFromUri(context, raw)?.let { name ->
                val file = SessionArtworkStore.fileFor(context, name)
                val source = when {
                    file.exists() && file.length() > 0 -> file.absolutePath
                    else -> SessionArtworkStore.sourceFor(name)
                } ?: return null
                return load(context, source)
            }
            val data: Any = when {
                raw.startsWith("/") -> File(raw)
                raw.startsWith("file://") -> File(Uri.parse(raw).path ?: return null)
                else -> raw
            }
            if (data is File && !data.exists()) return null
            return try {
                val request = ImageRequest.Builder(context)
                    .data(data)
                    .size(MAX_SIZE)
                    .allowHardware(false) // 硬件位图不能跨进程传给系统媒体会话
                    .build()
                val result = context.imageLoader.execute(request)
                (result as? SuccessResult)?.drawable?.toBitmap(config = Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                Log.w(TAG, "load cover failed: ${e.message}")
                null
            }
        }
    }
}
