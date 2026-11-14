package com.lm.player.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.SyncMode

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["downloadStatus"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastPlayedTimestamp"]),
        Index(value = ["addedTimestamp"]),
        Index(value = ["localFilePath"]),
        Index(value = ["albumId"]),
        Index(value = ["artistId"])
    ]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val album: String,
    val albumId: String,
    val durationMs: Long,
    val coverUrl: String,
    val streamUrl: String,
    val serverId: String,
    val localFilePath: String?,
    val downloadStatus: DownloadStatus,
    val bitRate: Int,
    val format: String,
    val isFavorite: Boolean,
    val relativeFolderPath: String? = null,
    val lastPlayedTimestamp: Long = 0L,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val rawMetaJson: String? = null
)

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["completedTimestamp"]),
        Index(value = ["localFilePath"])
    ]
)
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val localFilePath: String?,
    val remoteUrl: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val errorMessage: String? = null,
    val completedTimestamp: Long = 0L
)

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: ServerType,
    val serverUrl: String,
    val username: String,
    val tokenOrApiKey: String,
    val saltOrSecret: String,
    val syncMode: SyncMode = SyncMode.DIRECT,
    val isCurrentActive: Boolean
)

@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["isOnline"])
    ]
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverUrl: String = "",
    val serverId: String = "local_storage",
    val isOnline: Boolean = false,
    val songCount: Int = 0,
    val updatedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["songId"])
    ]
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val orderIndex: Int = 0
)

/**
 * 记录柠檬音乐服务端当前配置的自定义音源元信息。
 */
@Entity(
    tableName = "server_sources",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["isActive"]),
        Index(value = ["fetchedAt"])
    ]
)
data class ServerSourceEntity(
    @PrimaryKey val key: String, // serverId + "::" + sourceId
    val serverId: String,
    val sourceId: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val homepage: String = "",
    val isActive: Boolean = false,
    val healthLevel: String = "unknown",
    val healthBadge: String = "",
    val healthTip: String = "",
    val healthUnhealthy: Boolean = false,
    val healthSampleCount: Int = 0,
    val healthPreviewCount: Int = 0,
    val healthRatio: Double = 0.0,
    val healthPlatformsJson: String = "[]",
    val platformsJson: String = "[]",
    val disabledPlatformsJson: String = "[]",
    val rawScriptCached: Boolean = false,
    val fetchedAt: Long = System.currentTimeMillis()
)

/** 服务器连接/音源同步的元状态：用于判断「服务器是否最近在线」和延迟高低 */
@Entity(tableName = "server_source_meta")
data class ServerSourceMetaEntity(
    @PrimaryKey val serverId: String,
    val serverName: String = "",
    val fallbackMode: String = "auto",
    val activeSourceCount: Int = 0,
    val totalSourceCount: Int = 0,
    val latencyMs: Long = 0L,
    val lastOnlineAt: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastError: String? = null
)

/**
 * 最近成功解析出的播放/下载直链（短时本地兜底）。
 * 仅在服务器最近在线且当前请求延迟偏高时使用；超过 TTL 自动失效。
 */
@Entity(
    tableName = "resolved_stream_cache",
    indices = [
        Index(value = ["serverHost"]),
        Index(value = ["expiresAt"])
    ]
)
data class ResolvedStreamCacheEntity(
    @PrimaryKey val cacheKey: String, // serverHost + "|" + songId + "|" + quality
    val serverHost: String,
    val songId: String,
    val quality: String,
    val url: String,
    val format: String = "",
    val bitRate: Int = 0,
    val sourceId: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 10 * 60 * 1000L
)

/**
 * 服务器歌曲加速表（2026-11-13，数据库第 7 版）：每首服务器曲库歌曲一行，按服务器歌曲 ID 对应。
 *
 * 只存「本地音源加速要用的数据」，不改资料库（songs 表）里的歌曲列表、封面和元数据（11-11 规则）：
 * - 平台对应：这首服务器歌在酷我 / 酷狗 / QQ 等平台上对应哪一首（播放时跳过现搜，直接本地取链）；
 * - 平台封面：服务器封面慢或失败时与之竞速；
 * - 歌词缓存：第二次播放起直接从本地读。
 *
 * [fileKey] 是服务器文件指纹（路径 + 大小 + 时长）：服务器上换了文件就让这一行的匹配、歌词作废重来。
 */
@Entity(
    tableName = "server_track_accel",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["matchStatus"]),
        Index(value = ["lyricAt"])
    ]
)
data class ServerTrackAccelEntity(
    @PrimaryKey val songId: String,
    val serverId: String,
    val fileKey: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** 0 = 待匹配，1 = 已匹配，2 = 未找到 */
    val matchStatus: Int = 0,
    val platform: String = "",
    val platformSongId: String = "",
    /** 匹配到的平台曲目（id / title / artist / album / durationMs / coverUrl / raw） */
    val matchJson: String = "",
    val matchScore: Int = 0,
    val platformCoverUrl: String = "",
    val attempts: Int = 0,
    val lastTriedAt: Long = 0L,
    val matchedAt: Long = 0L,
    /** 歌词原文（LRC，含翻译时为合并后的文本） */
    val lyricText: String = "",
    /** server / platform / kugou */
    val lyricSource: String = "",
    val lyricAt: Long = 0L
)
