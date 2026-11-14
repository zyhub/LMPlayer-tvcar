package com.lm.player.feature.library

import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedArtist
import com.lm.player.core.model.UnifiedFolder
import com.lm.player.core.model.UnifiedSong

/** 「我的」页由曲库聚合出的几组数据 */
internal data class LibraryAggregates(
    val folders: List<UnifiedFolder>,
    val artists: List<UnifiedArtist>,
    val albums: List<UnifiedAlbum>,
    val recentAdded: List<UnifiedSong>
) {
    companion object {
        val EMPTY = LibraryAggregates(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * 切页提速：「我的」页的曲库聚合结果按曲库列表实例缓存。
 *
 * 顶部切页会把「我的」页整个销毁重建，原先文件夹 / 歌手 / 专辑 / 最近添加 / 排序列表
 * 每次进页都在主线程整库重算（几千首时是切页卡 1–2 秒的主因）。
 * 现在曲库没变化时切回来直接复用；曲库变化后由页面在后台线程重算。
 * 计算逻辑与原页面内的实现逐字一致，只是挪到了这里。
 */
internal object LibraryAggregateCache {
    /** 曲库不超过这个数量时，首次进页直接同步计算（耗时很短），避免出现一瞬间的空列表 */
    const val SYNC_COMPUTE_LIMIT = 800

    private var input: List<UnifiedSong>? = null
    private var output: LibraryAggregates? = null

    private var sortedInput: List<UnifiedSong>? = null
    private var sortedMode: String? = null
    private var sortedOutput: List<UnifiedSong>? = null

    @Synchronized
    fun peek(allSongs: List<UnifiedSong>): LibraryAggregates? = if (allSongs === input) output else null

    /** 计算并缓存（可在后台线程调用） */
    fun compute(allSongs: List<UnifiedSong>): LibraryAggregates {
        peek(allSongs)?.let { return it }
        val result = LibraryAggregates(
            folders = buildFolders(allSongs),
            artists = buildArtists(allSongs),
            albums = buildAlbums(allSongs),
            recentAdded = buildRecentAdded(allSongs)
        )
        synchronized(this) {
            input = allSongs
            output = result
        }
        return result
    }

    /** 排序后的歌曲列表，按（曲库实例 + 排序方式）缓存 */
    fun sorted(allSongs: List<UnifiedSong>, mode: String, sourceRank: (UnifiedSong) -> Int): List<UnifiedSong> {
        synchronized(this) {
            if (allSongs === sortedInput && mode == sortedMode) sortedOutput?.let { return it }
        }
        val result = when (mode) {
            "name" -> allSongs.sortedBy { it.title }
            "artist" -> allSongs.sortedBy { it.artist }
            "duration" -> allSongs.sortedByDescending { it.durationMs }
            "source" -> allSongs.sortedWith(
                compareBy<UnifiedSong> { sourceRank(it) }
                    .thenByDescending { it.addedTimestamp }
                    .thenBy { it.title }
            )
            // 按加入时间倒序（新加入的在前）。时间戳缺失(0)的极少数条目自然排在末尾，属预期。
            // addedTimestamp 的口径由调用方按模式注入，这里不区分：
            //   在线模式 = 服务器文件的 mtime（即服务器「最近添加」的先后）
            //   本地模式 = 本地下载完成时间（纯扫描入库的文件回落到文件修改时间）
            "added" -> allSongs.sortedWith(
                compareByDescending<UnifiedSong> { it.addedTimestamp }.thenBy { it.title }
            )
            else -> allSongs
        }
        synchronized(this) {
            sortedInput = allSongs
            sortedMode = mode
            sortedOutput = result
        }
        return result
    }

    // 本地文件夹目录结构动态聚合 (根据相对路径或本地物理路径聚合并提取目录名，过滤服务端 JSON 元数据)
    private fun buildFolders(allSongs: List<UnifiedSong>): List<UnifiedFolder> =
        allSongs.mapNotNull { song ->
            val rawRelPath = song.relativeFolderPath?.trim()
            val validRelPath = if (
                !rawRelPath.isNullOrBlank() &&
                !rawRelPath.startsWith("{") &&
                !rawRelPath.contains("\"") &&
                !rawRelPath.contains("_id__") &&
                !rawRelPath.startsWith("http")
            ) rawRelPath else null

            val folderName = when {
                validRelPath != null -> {
                    val p = validRelPath.replace('\\', '/')
                    p.trimEnd('/').substringAfterLast('/')
                }
                !song.localFilePath.isNullOrBlank() -> {
                    val p = song.localFilePath.replace('\\', '/').trimEnd('/')
                    p.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { null }
                }
                else -> null
            }?.trim()?.ifBlank { null }
            if (folderName != null && folderName !in listOf("0", "emulated", "sdcard", "storage")) {
                folderName to song
            } else null
        }.groupBy({ it.first }, { it.second })
            .map { (folderName, songs) ->
                UnifiedFolder(
                    id = "folder_${folderName.hashCode()}",
                    name = folderName,
                    path = songs.firstOrNull()?.let { s ->
                        val rp = s.relativeFolderPath?.trim()
                        if (!rp.isNullOrBlank() && !rp.startsWith("{") && !rp.contains("\"")) rp else s.localFilePath
                    } ?: "",
                    songCount = songs.size,
                    songs = songs
                )
            }.sortedByDescending { it.songCount }

    private fun buildArtists(allSongs: List<UnifiedSong>): List<UnifiedArtist> {
        val countMap = allSongs.groupingBy { it.artist.ifBlank { "未知歌手" } }.eachCount()
        return allSongs.groupBy { it.artist.ifBlank { "未知歌手" } }
            .map { (artistName, songs) ->
                val sCount = countMap[artistName] ?: songs.size
                UnifiedArtist(
                    id = "artist_${artistName.hashCode()}",
                    name = artistName,
                    avatarUrl = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: "",
                    albumCount = songs.map { it.album }.distinct().size,
                    songCount = sCount
                )
            }.sortedByDescending { it.songCount }
    }

    private fun buildAlbums(allSongs: List<UnifiedSong>): List<UnifiedAlbum> =
        allSongs.groupBy { it.album.ifBlank { "单曲精选" } }
            .map { (albumName, songs) ->
                UnifiedAlbum(
                    id = "album_${albumName.hashCode()}",
                    title = albumName,
                    artist = songs.firstOrNull()?.artist ?: "各种艺术家",
                    coverUrl = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: "",
                    songCount = songs.size
                )
            }.sortedByDescending { it.songCount }

    // 最近添加歌曲切片 (优先聚合已下载或有明确添加时间戳的曲目，按 addedTimestamp 倒序排序取前20首)
    private fun buildRecentAdded(allSongs: List<UnifiedSong>): List<UnifiedSong> {
        val downloadedOrTimestamped = allSongs.filter { it.downloadStatus == DownloadStatus.DOWNLOADED || it.addedTimestamp > 0 }
        return if (downloadedOrTimestamped.isNotEmpty()) {
            downloadedOrTimestamped.sortedWith(
                compareByDescending<UnifiedSong> { it.addedTimestamp }
                    .thenByDescending { it.downloadStatus == DownloadStatus.DOWNLOADED }
            ).take(20)
        } else {
            allSongs.sortedByDescending { it.addedTimestamp }.take(20)
        }
    }
}
