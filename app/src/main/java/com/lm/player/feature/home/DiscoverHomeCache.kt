package com.lm.player.feature.home

import com.lm.player.core.model.LemonToplist
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong

/**
 * 切页提速：发现页数据的进程级缓存。
 *
 * 顶部切页会把发现页整个销毁重建，原先页面里的数据随之丢失，每次切回发现页都要转圈、
 * 重新请求推荐歌单 / 榜单 / 新歌 / 新碟四组数据，再在主线程拿整个曲库做一次匹配。
 * 现在数据按「服务器名 + 音源」缓存 [TTL_MS]，切回来直接显示；手动刷新或过期后才重新请求。
 */
internal object DiscoverHomeCache {
    private const val TTL_MS = 30 * 60 * 1000L

    data class Snapshot(
        val playlists: List<UnifiedPlaylist> = emptyList(),
        val toplists: List<LemonToplist> = emptyList(),
        val newSongs: List<UnifiedSong> = emptyList(),
        val newAlbums: List<UnifiedAlbum> = emptyList(),
        val savedAt: Long = 0L
    ) {
        val isEmpty: Boolean
            get() = playlists.isEmpty() && toplists.isEmpty() && newSongs.isEmpty() && newAlbums.isEmpty()
    }

    private var key: String? = null
    private var data: Snapshot = Snapshot()

    private var resolvedInput: List<UnifiedSong>? = null
    private var resolvedLibrary: List<UnifiedSong>? = null
    private var resolvedTaskKey: Any? = null
    private var resolvedOutput: List<UnifiedSong>? = null

    fun keyOf(serverName: String, source: OnlineMusicSource): String = "$serverName|${source.name}"

    /** 新鲜且非空的缓存；没有时返回 null */
    @Synchronized
    fun snapshot(cacheKey: String): Snapshot? {
        if (cacheKey != key || data.isEmpty) return null
        if (System.currentTimeMillis() - data.savedAt > TTL_MS) return null
        return data
    }

    @Synchronized
    fun put(cacheKey: String, update: (Snapshot) -> Snapshot) {
        val base = if (cacheKey == key) data else Snapshot()
        key = cacheKey
        data = update(base).copy(savedAt = System.currentTimeMillis())
    }

    /** 新歌与本地曲库的匹配结果：输入（新歌列表 / 曲库列表实例 / 下载任务键）不变时直接复用 */
    @Synchronized
    fun peekResolved(newSongs: List<UnifiedSong>, library: List<UnifiedSong>, taskKey: Any?): List<UnifiedSong>? =
        if (resolvedInput === newSongs && resolvedLibrary === library && resolvedTaskKey == taskKey) resolvedOutput else null

    @Synchronized
    fun storeResolved(newSongs: List<UnifiedSong>, library: List<UnifiedSong>, taskKey: Any?, output: List<UnifiedSong>) {
        resolvedInput = newSongs
        resolvedLibrary = library
        resolvedTaskKey = taskKey
        resolvedOutput = output
    }
}
