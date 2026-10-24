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
