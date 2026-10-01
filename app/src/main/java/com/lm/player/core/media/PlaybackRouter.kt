package com.lm.player.core.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.dao.DownloadDao
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackRouter(
    private val downloadDao: DownloadDao,
    private val context: Context
) {
    private val TAG = "PlaybackRouter"

    /**
     * 核心路由决策机制：
     * 1. 优先检测本地已下载文件 (Room 下载库、曲目本地路径、默认存储目录)。
     * 2. 若未直接记录本地路径，通过歌曲标题与艺术家进行全局本地离线库严格匹配（区分 Live/专辑/时长）。
     * 3. 存在真实本地文件时，构建标准 file:// Uri，实现 0 等待、0 流量极速秒开。
     * 4. 若为在线歌曲且流媒体链接未就绪，自动通过柠檬音乐协议后台快速解析真实流媒体链接。
     * 5. 否则路由至远程流媒体 URL，自动接管 Media3 播放与缓存。
     */
    /**
     * @param streamPreResolved 调用方已在本次切歌流程中重新解析过 streamUrl (如在线搜索后立即播放)，
     *   此时跳过重复的在线解析，直接复用直链，可显著缩短切歌等待时间。
     */
    /**
     * @return 解析成功返回可直接交给 ExoPlayer 的 MediaItem；
     *   本地文件与真实直链**都没能拿到**时返回 null（调用方必须给出提示或跳过），
     *   不再用一个空 URI 的 MediaItem 假装成功。
     */
    suspend fun resolveMediaItem(
        song: UnifiedSong,
        forceRefresh: Boolean = false,
        streamPreResolved: Boolean = false
    ): MediaItem? {
        // 单次解析内缓存本地曲库全量列表，避免本地匹配与服务器路径回退重复做全表扫描
        var cachedLibrarySongs: List<SongEntity>? = null
        suspend fun loadLibrarySongs(): List<SongEntity> {
            cachedLibrarySongs?.let { return it }
            val loaded = try {
                ZdsDatabase.getInstance(context).songDao().getAllSongsList()
            } catch (_: Exception) {
                emptyList()
            }
            cachedLibrarySongs = loaded
            return loaded
        }

        val resolvedLocalPath: String? = withContext(Dispatchers.IO) {
            val downloadRecord = try { downloadDao.getDownloadRecord(song.id) } catch (_: Exception) { null }
            val recordPath = downloadRecord?.localFilePath
            val hasValidRecordFile = downloadRecord?.status == DownloadStatus.DOWNLOADED &&
                    !recordPath.isNullOrEmpty() &&
                    File(recordPath).let { it.exists() && it.length() > 0 }

            val hasValidSongLocalFile = !song.localFilePath.isNullOrEmpty() &&
                    File(song.localFilePath).let { it.exists() && it.length() > 0 }

            val isStreamUrlLocalFile = song.streamUrl.startsWith("/") &&
                    File(song.streamUrl).let { it.exists() && it.length() > 0 }

            val defaultDownloadFile = File(File(context.getExternalFilesDir(null), "music"), "${song.id}.${song.format}")
            val hasDefaultFile = defaultDownloadFile.exists() && defaultDownloadFile.length() > 0

            val directLocalPath: String? = when {
                hasValidRecordFile -> recordPath
                hasValidSongLocalFile -> song.localFilePath
                isStreamUrlLocalFile -> song.streamUrl
                hasDefaultFile -> defaultDownloadFile.absolutePath
                else -> null
            }

            // 本地库智能匹配：当直接路径为空时，尝试从本地曲库匹配已下载的物理音频（严格校验版本、专辑与时长）
            directLocalPath ?: try {
                val allSongs = loadLibrarySongs()
                val matched = allSongs.firstOrNull { s ->
                    val hasFile = !s.localFilePath.isNullOrBlank() && File(s.localFilePath).let { f -> f.exists() && f.length() > 0 }
                    hasFile && (s.id == song.id || SongMatchingResolver.isSongMatch(
                        s.title, s.artist, s.durationMs,
                        song.title, song.artist, song.durationMs,
                        s.album, song.album
                    ))
                }
                matched?.localFilePath
            } catch (_: Exception) {
                null
            }
        }

        val currentPreferredQuality = LemonMusicProtocol.getPreferredStreamQuality(context)
        val targetQuality = com.lm.player.core.model.AudioQuality.fromKey(currentPreferredQuality)
        val targetBitRate = targetQuality.bitrate
        val isStaleQuality = song.bitRate != targetBitRate && song.localFilePath.isNullOrBlank() && song.downloadStatus != DownloadStatus.DOWNLOADED

        var finalStreamUrl = if (forceRefresh || isStaleQuality) {
            ""
        } else {
            song.streamUrl
        }

        if (resolvedLocalPath != null) {
            // 本地文件命中，同步更新队列与当前曲目状态
            val updated = song.copy(
                localFilePath = resolvedLocalPath,
                streamUrl = resolvedLocalPath,
                downloadStatus = DownloadStatus.DOWNLOADED
            )
            PlaybackQueueManager.updateCurrentSong(updated)
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val db = ZdsDatabase.getInstance(context)
                    val active = db.serverDao().getActiveServer()
                        ?: db.serverDao().getAllServers().firstOrNull { it.id == song.serverId || it.type == ServerType.LEMON_MUSIC }

                    if (active != null && active.type == ServerType.LEMON_MUSIC) {
                        val client = NetworkClientFactory.createOkHttpClient(context)
                        val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                        protocol.ensureAuthenticated()

                        var serverPath = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl)
                        var trackId = LemonMusicProtocol.getServerTrackId(song.id)
                        if (serverPath.isNullOrBlank()) {
                            val rel = song.relativeFolderPath?.trim().orEmpty()
                            val hasAudioExt = rel.substringAfterLast('.', "").lowercase() in setOf(
                                "mp3", "flac", "wav", "ape", "m4a", "aac", "ogg", "opus", "wma", "dsf", "dff"
                            )
                            if (hasAudioExt && (rel.startsWith("/") || rel.contains(":/") || rel.contains(":\\"))) {
                                serverPath = rel
                            }
                        }
                        if (serverPath.isNullOrBlank()) {
                            // 从本地数据库中查找同 ID 或完全同版本服务端曲目的有效路径/流地址
                            val allSongs = loadLibrarySongs()
                            val matchedServerSong = allSongs.firstOrNull { s ->
                                (s.id == song.id || SongMatchingResolver.isSongMatch(
                                    s.title, s.artist, s.durationMs,
                                    song.title, song.artist, song.durationMs,
                                    s.album, song.album
                                )) && (s.streamUrl.contains("/api/play/local") || s.coverUrl.contains("path="))
                            }
                            if (matchedServerSong != null) {
                                serverPath = LemonMusicProtocol.getServerFilePath(
                                    matchedServerSong.id,
                                    matchedServerSong.streamUrl,
                                    matchedServerSong.coverUrl
                                )
                                if (trackId.isNullOrBlank()) {
                                    trackId = LemonMusicProtocol.getServerTrackId(matchedServerSong.id)
                                }
                            }
                        }

                        val serverExt = serverPath?.substringAfterLast('.', "")?.lowercase().orEmpty().ifBlank { song.format.lowercase() }
                        val serverMatchesTargetQuality = !serverPath.isNullOrBlank() && when (targetQuality) {
                            com.lm.player.core.model.AudioQuality.Q_128K -> serverExt in listOf("mp3", "m4a", "aac", "ogg") && song.bitRate in 64..192
                            com.lm.player.core.model.AudioQuality.Q_320K -> serverExt in listOf("mp3", "m4a", "aac", "ogg") && song.bitRate in 193..512
                            com.lm.player.core.model.AudioQuality.Q_FLAC -> serverExt in listOf("flac", "wav", "ape", "alac") && song.bitRate < 1200
                            com.lm.player.core.model.AudioQuality.Q_HIRES -> serverExt in listOf("flac", "wav", "ape", "alac", "dsf", "dff") && song.bitRate >= 1200
                        }

                        var resolvedFormat = targetQuality.format.lowercase()
                        var resolvedBitRate = targetQuality.bitrate

                        // 调用方已在本次切歌流程内重新解析过直链且音质未过期时，直接复用，避免重复的网络往返拖慢切歌
                        val reusablePreResolvedUrl = streamPreResolved &&
                            !forceRefresh &&
                            !isStaleQuality &&
                            finalStreamUrl.isNotBlank() &&
                            !finalStreamUrl.startsWith("lemon_online://")

                        // 若为在线曲目，或用户在设置中指定的试听音质与服务器原文件音质不一致，优先按试听音质设置请求对应音质流
                        if (!serverMatchesTargetQuality && !reusablePreResolvedUrl) {
                            val cleanId = song.id.removePrefix("lemon_online_")
                            val source = if (song.id.startsWith("lemon_online_") && cleanId.contains("_")) {
                                cleanId.substringBefore("_")
                            } else {
                                context.getSharedPreferences("zds_online_prefs", Context.MODE_PRIVATE)
                                    .getString("selected_source", "KUWO")
                                    ?.let { com.lm.player.core.model.OnlineMusicSource.entries.firstOrNull { s -> s.name == it }?.key }
                                    ?: "kw"
                            }
                            val meta = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }

                            val resolvedStream = protocol.resolveOnlineStreamWithQuality(
                                songId = song.id,
                                source = source,
                                preferredQuality = currentPreferredQuality,
                                metaJson = meta,
                                fallbackTitle = song.title,
                                fallbackArtist = song.artist,
                                refresh = forceRefresh
                            ).getOrNull()

                            if (resolvedStream != null && resolvedStream.url.isNotBlank()) {
                                finalStreamUrl = resolvedStream.url
                                resolvedFormat = resolvedStream.format
                                resolvedBitRate = resolvedStream.bitRate
                            }
                        }

                        // 若服务器原文件音质直接匹配试听音质，或在线音源解析未命中，使用服务端本地曲目流解析（带试听音质参数）
                        if (serverMatchesTargetQuality || finalStreamUrl.isBlank() || finalStreamUrl.startsWith("lemon_online://")) {
                            if (!serverPath.isNullOrBlank() || !trackId.isNullOrBlank()) {
                                val srvUrl = protocol.resolveServerLocalPlayUrl(serverPath, trackId, quality = currentPreferredQuality).getOrNull()
                                if (!srvUrl.isNullOrBlank()) {
                                    finalStreamUrl = srvUrl
                                } else if (!serverPath.isNullOrBlank()) {
                                    finalStreamUrl = protocol.getStreamUrlForPath(serverPath, quality = currentPreferredQuality)
                                }
                            }
                        }

                        // 兜底流地址
                        if (finalStreamUrl.isBlank() && song.streamUrl.isNotBlank() && !song.streamUrl.startsWith("lemon_online://")) {
                            finalStreamUrl = song.streamUrl
                        }

                        if (finalStreamUrl.isNotBlank() && (finalStreamUrl != song.streamUrl || resolvedFormat != song.format || resolvedBitRate != song.bitRate)) {
                            val updated = song.copy(
                                streamUrl = finalStreamUrl,
                                format = resolvedFormat,
                                bitRate = resolvedBitRate
                            )
                            PlaybackQueueManager.updateCurrentSong(updated)
                        }
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve stream URL for ${song.title}", e)
                }
                Unit
            }
        }

        // 没有本地文件、也没有真实可用的网络直链时，直接判定解析失败。
        // `lemon_online://` 是内部占位 scheme，ExoPlayer 同样打不开：
        // 以前这两种情况都会被 Uri.parse("") / Uri.parse("lemon_online://…") 包成一个 MediaItem，
        // 播放器报的错被降级成一行日志，界面却照常显示"正在播放"，用户只看到"无声"这一个现象
        val hasPlayableUrl = resolvedLocalPath != null ||
                (finalStreamUrl.isNotBlank() && !finalStreamUrl.startsWith("lemon_online://"))
        if (!hasPlayableUrl) {
            Log.w(TAG, "无法为「${song.title}」解析出可播放地址 (本地文件/真实直链均未命中)，本次播放放弃")
            return null
        }

        val uri: Uri = if (resolvedLocalPath != null) {
            Log.d(TAG, "Routing to local file: $resolvedLocalPath")
            Uri.fromFile(File(resolvedLocalPath))
        } else if (finalStreamUrl.startsWith("content://")) {
            Log.d(TAG, "Routing to SAF content uri: $finalStreamUrl")
            Uri.parse(finalStreamUrl)
        } else {
            Log.d(TAG, "Routing to remote stream: $finalStreamUrl")
            Uri.parse(finalStreamUrl)
        }

        val isOffline = uri.scheme == "file" || uri.scheme == "content"

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(if (song.coverUrl.isNotEmpty()) Uri.parse(song.coverUrl) else null)
            .setExtras(Bundle().apply {
                putBoolean("KEY_IS_OFFLINE", isOffline)
                putString("KEY_SONG_ID", song.id)
                putString("KEY_SERVER_ID", song.serverId)
                putInt("KEY_BITRATE", song.bitRate)
            })

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}
