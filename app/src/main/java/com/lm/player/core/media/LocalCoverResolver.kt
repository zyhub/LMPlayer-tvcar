package com.lm.player.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 本地已缓存歌曲的封面（2026-11-13）：播放本地文件时优先用它自己的封面，不依赖服务器。
 *
 * 在线模式下，从服务器同步来的歌曲封面是服务器地址（/api/tag/cover?path=…），要服务器可达且已登录；
 * 启动时登录还没完成、服务器偶尔超时，封面就加载失败。歌曲既然已经下载 / 缓存在本机，文件里就内嵌着封面。
 *
 * 查找顺序（调用方须在 IO 线程）：
 * 1. 应用缓存里的封面 `cacheDir/album_art/md5(歌曲ID).jpg`（下载完成、本地扫描时写的，与它们同目录同命名）；
 * 2. 缓存被系统清掉了 → 从音频文件里重新取内嵌封面，写回缓存；
 * 3. 音频文件同目录的 cover.jpg / folder.jpg / front.jpg（老版本下载或用户自己放的）。
 */
object LocalCoverResolver {

    private const val TAG = "LocalCoverResolver"

    /** 已确认没有本地封面的音频文件（本进程内不再重复解析） */
    private val noCover = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun cacheFile(context: Context, songId: String): File =
        File(File(context.cacheDir, "album_art"), "${com.lm.player.core.network.LemonMusicProtocol.md5(songId)}.jpg")

    /** 是否是本机文件形式的封面地址（且文件还在） */
    fun isUsableLocalCover(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("content://")) return true
        val path = url.removePrefix("file://")
        if (!path.startsWith("/")) return false
        return runCatching { File(path).let { it.isFile && it.length() > 0L } }.getOrDefault(false)
    }

    /** @return 本地封面文件的绝对路径；找不到返回 null。必须在 IO 线程调用 */
    fun resolve(context: Context, songId: String, audioPath: String?): String? {
        if (songId.isBlank() || audioPath.isNullOrBlank()) return null
        val cached = cacheFile(context, songId)
        if (cached.isFile && cached.length() > 0L) return cached.absolutePath
        if (audioPath in noCover) return null

        // 从音频文件取内嵌封面
        val embedded = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (audioPath.startsWith("content://")) retriever.setDataSource(context, Uri.parse(audioPath))
                else retriever.setDataSource(audioPath)
                retriever.embeddedPicture
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
        if (embedded != null && embedded.isNotEmpty()) {
            val ok = runCatching {
                cached.parentFile?.mkdirs()
                cached.writeBytes(embedded)
                true
            }.getOrDefault(false)
            if (ok) return cached.absolutePath
        }

        // 同目录的封面图片
        if (!audioPath.startsWith("content://")) {
            val dir = File(audioPath).parentFile
            if (dir != null) {
                for (name in listOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png", "AlbumArt.jpg")) {
                    val f = File(dir, name)
                    if (f.isFile && f.length() > 0L) return f.absolutePath
                }
            }
        }
        noCover.add(audioPath)
        Log.d(TAG, "no local cover for $audioPath")
        return null
    }
}
