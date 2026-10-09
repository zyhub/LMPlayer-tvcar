package com.lm.player.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class UnifiedSong(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val album: String = "",
    val albumId: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val streamUrl: String = "",
    val serverId: String = "default",
    val localFilePath: String? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val bitRate: Int = 320,
    val format: String = "flac",
    val isFavorite: Boolean = false,
    val relativeFolderPath: String? = null,
    val addedTimestamp: Long = 0L,
    val rawMetaJson: String? = null
)

@Immutable
data class UnifiedAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val songCount: Int = 0,
    val year: Int? = null
)

@Immutable
data class UnifiedArtist(
    val id: String,
    val name: String,
    val avatarUrl: String = "",
    val albumCount: Int = 0,
    val songCount: Int = 0
)

@Immutable
data class UnifiedPlaylist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val songCount: Int = 0,
    val isOnline: Boolean = false,
    val serverId: String = "local_storage",
    val previewCovers: List<String> = emptyList(),
    val isDiscover: Boolean = false
)

@Immutable
data class UnifiedFolder(
    val id: String,
    val name: String,
    val path: String = "",
    val songCount: Int = 0,
    val songs: List<UnifiedSong> = emptyList()
)

@Immutable
data class ServerFolderItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val parentId: String? = null,
    val childCount: Int = 0,
    val coverUrl: String = "",
    val song: UnifiedSong? = null
)

enum class SongSortOption(val displayName: String) {
    ALBUM("专辑"),
    ALBUM_ARTIST("专辑艺人"),
    ARTIST("作曲家"),
    DATE_ADDED("加入日期"),
    RELEASE_DATE("发行日期"),
    FORMAT("媒体容器"),
    RATING("家长评分"),
    YEAR("年份"),
    DATE_PLAYED("播放日期"),
    DURATION("播放时长"),
    PLAY_COUNT("播放次数"),
    FILENAME("文件名"),
    FILE_SIZE("文件尺寸")
}

enum class SortOrder {
    ASCENDING,
    DESCENDING
}

enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    PAUSED
}

enum class ServerType(val displayName: String) {
    LOCAL_OFFLINE("本地离线模式"),
    LEMON_MUSIC("柠檬音乐服务端")
}

enum class SyncMode(val displayName: String) {
    DIRECT("直连"),
    BACKGROUND("后台"),
    MANUAL("手动")
}

@Immutable
data class ServerConfig(
    val id: String,
    val name: String,
    val type: ServerType,
    val serverUrl: String,
    val username: String = "",
    val tokenOrApiKey: String = "",
    val saltOrSecret: String = "",
    val syncMode: SyncMode = SyncMode.DIRECT,
    val isCurrentActive: Boolean = false
)

@Immutable
data class DownloadTask(
    val song: UnifiedSong,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedKbps: Long = 0L,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING
)

@Immutable
data class DownloadSettings(
    val maxConcurrent: Int = 3,
    val bitrate: String = "原始无损 (FLAC/高码率)",
    val wifiOnly: Boolean = false,
    val autoTagging: Boolean = true,
    val customDownloadPath: String = ""
)

@Immutable
data class HomeScreenDisplayConfig(
    val showRecentlyPlayed: Boolean = true,
    val showRecentlyAdded: Boolean = true,
    val showAlbums: Boolean = false,
    val showArtists: Boolean = false,
    val showFavorites: Boolean = false
)

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String
)

@Immutable
data class LyricResult(
    val lines: List<LyricLine> = emptyList(),
    val isSynced: Boolean = true
)

enum class LibraryCategory {
    PLAYLISTS,
    ARTISTS,
    ALBUMS,
    SONGS,
    DOWNLOADED,
    FAVORITES,
    FOLDERS
}

enum class Screen {
    MINE,
    SEARCH,
    HOME,
    LIBRARY,
    DOWNLOADS,
    SETTINGS
}

@Immutable
data class LemonToplist(
    val id: String,
    val name: String,
    val coverUrl: String,
    val updateFrequency: String = "",
    val source: String = "kw"
)

enum class OnlineMusicSource(val key: String, val displayName: String, val shortName: String) {
    KUWO("kw", "酷我音乐", "酷我"),
    NETEASE("wy", "网易云音乐", "网易"),
    QQ("tx", "QQ音乐", "QQ"),
    KUGOU("kg", "酷狗音乐", "酷狗"),
    MIGU("mg", "咪咕音乐", "咪咕");

    companion object {
        fun fromKey(key: String): OnlineMusicSource {
            return entries.firstOrNull { it.key == key } ?: KUWO
        }
    }
}

enum class SearchContentType(val key: String, val displayName: String) {
    SONG("song", "歌曲"),
    ALBUM("album", "专辑"),
    PLAYLIST("playlist", "歌单");

    companion object {
        fun fromKey(key: String): SearchContentType {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SONG
        }
    }
}

/**
 * 柠檬音乐服务端导入的落雪/澜音音源脚本数据模型
 */
@Immutable
data class LemonSourceScriptInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val homepage: String = "",
    val supportedPlatforms: List<String> = emptyList(),
    val disabledPlatforms: List<String> = emptyList(),
    val isActive: Boolean = false,
    val healthSummary: String = ""
)

enum class DownloadTarget(val displayName: String, val desc: String) {
    LOCAL("本地下载", "保存到本设备内部存储，离线随时聆听"),
    SERVER("服务器下载", "保存到柠檬曲库，全终端同步共享"),
    BOTH("双端下载", "同时推送到服务器曲库并下载到本地离线存储")
}


enum class AudioQuality(val key: String, val label: String, val format: String, val badge: String, val bitrate: Int) {
    Q_128K("128k", "标准音质", "MP3", "128K", 128),
    Q_320K("320k", "极高音质", "MP3", "320K", 320),
    Q_FLAC("flac", "无损音质", "FLAC", "FLAC", 960),
    Q_HIRES("flac24bit", "Hi-Res 高解析母带", "FLAC", "Hi-Res", 1411);

    fun onlineStreamTag(): String = when (this) {
        Q_128K -> "标准 128K"
        Q_320K -> "极高 320K"
        Q_FLAC -> "无损 FLAC"
        Q_HIRES -> "Hi-Res 无损"
    }

    /**
     * 取音源下发的**该音质真实大小字符串**（如 "8.5MB"）。没有该音质的大小信息时返回 null。
     */
    private fun rawSizeText(song: UnifiedSong): String? {
        val rawJson = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
            ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
        if (rawJson.isNullOrBlank()) return null
        try {
            val obj = org.json.JSONObject(rawJson)
            val typesObj = obj.optJSONObject("_types")
            if (typesObj != null) {
                val s = typesObj.optJSONObject(key)?.optString("size")?.trim().orEmpty()
                if (s.isNotBlank() && !s.equals("null", true) && !s.startsWith("0")) return s
            }
            val typesArr = obj.optJSONArray("types")
            if (typesArr != null) {
                for (i in 0 until typesArr.length()) {
                    val item = typesArr.optJSONObject(i) ?: continue
                    if (item.optString("type").equals(key, ignoreCase = true)) {
                        val s = item.optString("size").trim()
                        if (s.isNotBlank() && !s.equals("null", true) && !s.startsWith("0")) return s
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 无真实大小信息时按 码率 × 时长 估算的单首大小 (MB) */
    private fun estimatedSizeMb(song: UnifiedSong): Double {
        val durationSec = if (song.durationMs > 1000L) (song.durationMs / 1000.0).coerceIn(30.0, 1800.0) else 215.0
        return (durationSec * bitrate * 1000.0 / 8.0) / (1024.0 * 1024.0)
    }

    private fun formatSizeMb(totalMb: Double): String =
        if (totalMb >= 1024.0) {
            "%.2f GB".format(java.util.Locale.US, totalMb / 1024.0)
        } else {
            "%.1f MB".format(java.util.Locale.US, totalMb)
        }

    fun estimateSizeText(song: UnifiedSong): String {
        rawSizeText(song)?.let {
            return it.replace(Regex("(?i)(\\d)(mb|kb|gb)"), "$1 $2").uppercase(java.util.Locale.US)
        }
        // 旧版批量弹窗造的假歌 (batch_download_N) 仍按数量放大，避免外部调用方拿到突兀的数值
        val batchMultiplier = if (song.id.startsWith("batch_download_")) {
            song.id.removePrefix("batch_download_").toIntOrNull()?.coerceAtLeast(1) ?: 1
        } else 1
        return "约 " + formatSizeMb(estimatedSizeMb(song) * batchMultiplier)
    }

    /**
     * 批量预估**所选全部歌曲**的总大小：逐首优先用音源下发的真实大小，
     * 取不到的按 码率 × 时长 估算，最后求和。
     * 取代此前"假歌 × 数量"的粗算（那个连真实时长都不看，误差极大）。
     */
    fun estimateSizeText(songs: List<UnifiedSong>): String {
        if (songs.isEmpty()) return "—"
        var totalMb = 0.0
        var allExact = true
        for (song in songs) {
            val real = rawSizeText(song)?.let { parseSizeMb(it) }
            if (real != null) {
                totalMb += real
            } else {
                allExact = false
                totalMb += estimatedSizeMb(song)
            }
        }
        return (if (allExact) "" else "约 ") + formatSizeMb(totalMb)
    }

    /** 把 "12.5MB" / "800 KB" / "1.2GB" 解析成 MB；无法解析时返回 null（回落到按码率估算） */
    private fun parseSizeMb(raw: String): Double? {
        val m = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(KB|MB|GB)?\\s*$", RegexOption.IGNORE_CASE)
            .find(raw) ?: return null
        val value = m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        return when (m.groupValues[2].uppercase(java.util.Locale.US)) {
            "KB" -> value / 1024.0
            "GB" -> value * 1024.0
            // 无单位视为 MB（音源偶尔只回一个数字，按字节解读会得出 ~0 的荒谬结果）
            else -> value
        }
    }

    fun labelWithSize(song: UnifiedSong): String = "$label (${estimateSizeText(song)})"

    fun labelWithSize(songs: List<UnifiedSong>): String = "$label (${estimateSizeText(songs)})"

    companion object {
        fun fromKey(key: String): AudioQuality {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Q_320K
        }
    }
}

@Immutable
data class LemonServerDownloadTask(
    val id: String = "",
    val name: String,
    val singer: String,
    val source: String = "kw",
    val album: String = "",
    val interval: String = "",
    val quality: String = "320k",
    val songId: String = "",
    val songmid: String = "",
    val hash: String = "",
    val rid: String = "",
    val copyrightId: String = "",
    val img: String = "",
    val platform: String = source,
    val pic: String = img,
    val raw: String = ""
)

@Immutable
data class LemonServerDownloadTaskRecord(
    val id: String,
    val name: String,
    val singer: String,
    val album: String = "",
    val quality: String = "320k",
    val status: String = "waiting", // waiting, downloading, completed, error, paused, await_confirm
    val progress: Int = 0,
    val error: String = "",
    val createdAt: Long = 0L,
    val filePath: String = "",
    val songId: String = "",
    val coverUrl: String = ""
)

@Immutable
data class LemonDownloadPreferences(
    val defaultTarget: DownloadTarget = DownloadTarget.LOCAL,
    val defaultQuality: AudioQuality = AudioQuality.Q_320K,
    val maxConcurrent: Int = 3,
    val embedCover: Boolean = true,
    val embedLyric: Boolean = true,
    val downloadLrcFile: Boolean = false,
    val existFileMode: String = "skip", // skip | overwrite
    val groupByFolder: Boolean = false,
    val autoCacheOnFavorite: Boolean = false
)

@Immutable
data class ResolvedOnlineStream(
    val url: String,
    val qualityKey: String,
    val format: String,
    val bitRate: Int,
    val isDowngraded: Boolean = false
)

@Immutable
data class UnifiedGenre(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
    val coverUrl: String = ""
)

@Immutable
data class LemonScanStatus(
    val isScanning: Boolean = false,
    val cachedCount: Int = 0,
    val pendingCount: Int = 0,
    val total: Int = 0
)
