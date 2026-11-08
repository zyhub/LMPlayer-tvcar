package com.lm.player.core.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 第二十四节：给第三方桌面 (如迪粉桌面) 用的公开封面。
 *
 * 系统媒体会话里的封面地址 (artworkUri) 会被第三方桌面直接拿去加载，而本 App 的封面地址
 * 要么是私有目录文件 (files/local_art/…)，要么是需要登录且为自签证书的服务器地址
 * (…/api/tag/cover…)，其他 App 都打不开 → 桌面卡片没有封面。
 *
 * 这里把这类封面先用 App 自己的图片通道加载、缩小到 512 像素以内，存成缓存目录里的 JPEG，
 * 再通过只读的 [CoverArtProvider] 以 `content://<包名>.coverart/art/<md5>.jpg` 对外提供。
 * 公网 http(s) 封面 (在线音源，地址不含 /api/) 本来别人就能打开，保持原地址不变。
 */
object SessionArtworkStore {
    private const val TAG = "SessionArtworkStore"
    private const val DIR_NAME = "session_art"
    private const val MAX_FILES = 100
    private const val WAIT_MS = 5_000L
    const val PATH_PREFIX = "art"

    private val NAME_RE = Regex("^[0-9a-f]{32}\\.jpg$")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 文件名 → 原始封面地址 (仅本进程内有效，进程重启后靠磁盘上已生成的文件兜底) */
    private val sources = ConcurrentHashMap<String, String>()
    /** 文件名 → 正在生成的任务 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun authority(context: Context): String = context.packageName + ".coverart"

    fun isValidName(name: String?): Boolean = name != null && NAME_RE.matches(name)

    private fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    /** 需要转成公开地址的封面：本地文件 / 本 App 外不可读的 content / 服务器 /api/ 地址。 */
    private fun needsProxy(context: Context, raw: String): Boolean {
        val lower = raw.lowercase()
        return when {
            raw.startsWith("/") || lower.startsWith("file://") -> true
            lower.startsWith("content://") -> !raw.startsWith("content://" + authority(context) + "/")
            lower.startsWith("http://") || lower.startsWith("https://") -> lower.contains("/api/")
            else -> false
        }
    }

    private fun nameFor(raw: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /** 若是本 App 公开封面地址，返回其文件名，否则 null。 */
    fun nameFromUri(context: Context, raw: String): String? {
        val prefix = "content://" + authority(context) + "/" + PATH_PREFIX + "/"
        if (!raw.startsWith(prefix)) return null
        val name = raw.removePrefix(prefix)
        return name.takeIf { isValidName(it) }
    }

    /** 公开封面对应的原始地址 (进程内已登记时)。 */
    fun sourceFor(name: String): String? = sources[name]

    /**
     * 交给系统媒体会话的封面地址。需要转换的封面会在后台开始生成文件，并立即返回公开地址；
     * 桌面来读时若文件还没生成好，[CoverArtProvider] 会等待最多 5 秒。
     */
    fun artworkUriFor(context: Context, coverUrl: String): Uri? {
        if (coverUrl.isBlank()) return null
        if (!needsProxy(context, coverUrl)) return Uri.parse(coverUrl)
        val appContext = context.applicationContext
        val name = nameFor(coverUrl)
        sources[name] = coverUrl
        ensurePrepared(appContext, name)
        return Uri.parse("content://" + authority(appContext) + "/" + PATH_PREFIX + "/" + name)
    }

    private fun ensurePrepared(context: Context, name: String): CompletableDeferred<Boolean>? {
        val file = fileFor(context, name)
        if (file.exists() && file.length() > 0) return null
        val source = sources[name] ?: return null
        val fresh = CompletableDeferred<Boolean>()
        val existing = pending.putIfAbsent(name, fresh)
        if (existing != null) return existing
        scope.launch {
            var ok = false
            try {
                val bmp = SessionCoverBitmapLoader.load(context, source)
                if (bmp != null) ok = writeAtomically(context, file, bmp)
            } catch (e: Throwable) {
                Log.w(TAG, "prepare cover failed: ${e.message}")
            } finally {
                fresh.complete(ok)
                pending.remove(name, fresh)
            }
            if (ok) trim(context)
        }
        return fresh
    }

    private fun writeAtomically(context: Context, target: File, bmp: Bitmap): Boolean {
        val tmp = File(dir(context), target.name + ".tmp" + System.nanoTime())
        return try {
            FileOutputStream(tmp).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            if (tmp.length() <= 0) {
                tmp.delete(); false
            } else if (tmp.renameTo(target)) {
                true
            } else {
                tmp.delete(); target.exists()
            }
        } catch (e: Throwable) {
            tmp.delete()
            Log.w(TAG, "write cover failed: ${e.message}")
            false
        }
    }

    private fun trim(context: Context) {
        try {
            val files = dir(context).listFiles { f -> isValidName(f.name) } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_FILES)
                .forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    /** 供 [CoverArtProvider] 调用 (Binder 线程)：拿到已生成的封面文件，必要时等待生成完成。 */
    fun awaitFile(context: Context, name: String): File? {
        if (!isValidName(name)) return null
        val file = fileFor(context, name)
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val job = pending[name] ?: ensurePrepared(context.applicationContext, name) ?: return null
        val ok = runBlocking { withTimeoutOrNull(WAIT_MS) { job.await() } } == true
        return if (ok && file.exists() && file.length() > 0) file else null
    }
}
