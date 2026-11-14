package com.lm.player.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong

/**
 * 歌曲下载状态的即时来源（2026-11-13 从手机端移植），让各个列表行在下载完成后立刻刷新。
 *
 * 以前列表行只看传进来的 UnifiedSong：搜索结果、歌手 / 专辑页、发现页、歌单详情拿到的都是打开时的快照，
 * 下载完成只写数据库，快照不会变，所以行尾一直是「下载」按钮；资料库本身也要等数据库防抖 + 全表重读。
 *
 * 两层数据：
 * - library：数据库镜像（MainActivity 每次重读歌曲表后整体替换），只记有本地文件的歌；
 * - overlay：下载引擎的即时事件（完成、删除），数据库镜像追上后自动清掉。
 *
 * 性能：两份都是不可变 Map 整体替换；行里用 derivedStateOf 只读自己那一条，
 * 批量下载时每完成一首只有对应那一行重组，其它行只做一次 Map 查找。
 */
object SongStatusStore {

    @Immutable
    data class Status(
        /** 本地文件路径（null = 不覆盖） */
        val localFilePath: String? = null,
        /** 本地文件已删除（覆盖快照里的路径） */
        val removed: Boolean = false
    )

    private val library = mutableStateOf<Map<String, Status>>(emptyMap())
    private val overlay = mutableStateOf<Map<String, Status>>(emptyMap())
    private val lock = Any()

    /** 数据库重读后调用（任意线程）：替换镜像，并清掉已被数据库覆盖的即时事件 */
    fun updateLibrary(songs: List<UnifiedSong>) {
        val map = HashMap<String, Status>(songs.size / 2 + 16)
        for (s in songs) {
            val path = s.localFilePath?.takeIf { it.isNotBlank() }
            if (path != null) map[s.id] = Status(localFilePath = path)
        }
        synchronized(lock) {
            library.value = map
            val ov = overlay.value
            if (ov.isEmpty()) return
            val kept = ov.filter { (id, o) ->
                val l = map[id]
                val settled = when {
                    o.removed -> l == null || l.localFilePath == null
                    o.localFilePath != null -> l?.localFilePath == o.localFilePath
                    else -> true
                }
                !settled
            }
            if (kept.size != ov.size) overlay.value = kept
        }
    }

    private fun edit(ids: Collection<String>, change: (Status) -> Status) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val next = HashMap(overlay.value)
            for (id in ids) next[id] = change(next[id] ?: Status())
            overlay.value = next
        }
    }

    /** 本地下载完成（下载引擎写库时调用） */
    fun markDownloaded(songId: String, localFilePath: String) =
        edit(listOf(songId)) { it.copy(localFilePath = localFilePath, removed = false) }

    /** 本地文件已删除 */
    fun markRemoved(songIds: Collection<String>) =
        edit(songIds) { it.copy(localFilePath = null, removed = true) }

    private fun statusOf(id: String): Pair<Status?, Status?> = library.value[id] to overlay.value[id]

    /** 把最新状态叠到歌曲上（只改本地路径 / 下载状态，其余字段原样） */
    fun apply(song: UnifiedSong, lib: Status?, ov: Status?): UnifiedSong {
        if (lib == null && ov == null) return song
        var path = song.localFilePath
        var status = song.downloadStatus
        if (lib?.localFilePath != null) {
            path = lib.localFilePath
            status = DownloadStatus.DOWNLOADED
        }
        if (ov != null) {
            if (ov.removed) {
                path = null
                status = DownloadStatus.NOT_DOWNLOADED
            } else if (ov.localFilePath != null) {
                path = ov.localFilePath
                status = DownloadStatus.DOWNLOADED
            }
        }
        if (path == song.localFilePath && status == song.downloadStatus) return song
        return song.copy(localFilePath = path, downloadStatus = status)
    }

    /** 列表行 / 播放页用：拿到带最新下载状态的歌曲；只有这首歌的状态变化才会触发重组 */
    @Composable
    fun rememberLive(song: UnifiedSong): UnifiedSong {
        val id = song.id
        val status by remember(id) { derivedStateOf { statusOf(id) } }
        return remember(song, status) { apply(song, status.first, status.second) }
    }
}
