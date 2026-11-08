package com.lm.player.core.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
     * 下一首预取（切歌提速）：预取时不能改动「当前歌曲」状态、也不弹 Toast，
     * 先把这些副作用记在这里，真正切到这首歌时再按原顺序回放。
     */
    class PrefetchCapture {
        val songUpdates = mutableListOf<UnifiedSong>()
        @Volatile var pendingToast: String? = null
    }

    private fun publishSongUpdate(updated: UnifiedSong, prefetch: PrefetchCapture?) {
        if (prefetch != null) {
            synchronized(prefetch.songUpdates) { prefetch.songUpdates.add(updated) }
        } else {
            PlaybackQueueManager.updateCurrentSong(updated)
        }
    }    private val PLAYBACK_ACCEL_BUDGET_MS = 2_000L

    private fun buildAccelerationInfo(song: UnifiedSong): String {
        val obj = runCatching { org.json.JSONObject(song.rawMetaJson ?: "{}") }.getOrDefault(org.json.JSONObject())
        val clean = song.id.removePrefix("lemon_online_")
        val rawId = if (clean.contains("_")) clean.substringAfter("_") else clean
        if (rawId.isNotBlank()) {
            if (obj.optString("id").isBlank()) obj.put("id", rawId)
            if (obj.optString("songId").isBlank()) obj.put("songId", rawId)
        }
        if (obj.optString("name").isBlank()) obj.put("name", song.title)
        if (obj.optString("singer").isBlank()) obj.put("singer", song.artist)
        if (obj.optString("albumName").isBlank()) obj.put("albumName", song.album)
        listOf("hash", "songmid").forEach { key ->
            if (obj.has(key) && obj.optString(key).isBlank()) obj.remove(key)
        }
        return obj.toString()
    }


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
        streamPreResolved: Boolean = false,
        overrideQuality: String? = null,
        prefetch: PrefetchCapture? = null
    ): MediaItem? {
        // 播客单集：直接播 RSS 里的音频地址（失败时由 PlaybackQueueManager 改走服务器代理），
        // 不参与本地曲库匹配、在线音源取链与音质降级
        if (com.lm.player.core.podcast.PodcastSongs.isPodcast(song)) {
            return buildPodcastMediaItem(song)
        }
        // 单次解析内缓存同 ID / 同标题候选歌曲，定向索引查询，严禁全库扫描
        var cachedCandidates: List<SongEntity>? = null
        suspend fun loadCandidates(): List<SongEntity> {
            cachedCandidates?.let { return it }
            val loaded = try {
                ZdsDatabase.getInstance(context).songDao().findCandidatesForPlayback(song.id, song.title)
            } catch (_: Exception) {
                emptyList()
            }
            cachedCandidates = loaded
            return loaded
        }

        val resolvedLocalPath: String? = withContext(Dispatchers.IO) {
            val downloadRecord = try { downloadDao.getDownloadRecord(song.id) } catch (_: Exception) { null }
            val recordPath = downloadRecord?.localFilePath
            val hasValidRecordFile = downloadRecord?.status == DownloadStatus.DOWNLOADED &&
                    !recordPath.isNullOrEmpty() &&
                    if (recordPath.startsWith("content://")) true
                    else File(recordPath).let { it.exists() && it.length() > 0 }

            val hasValidSongLocalFile = !song.localFilePath.isNullOrEmpty() &&
                    if (song.localFilePath.startsWith("content://")) true
                    else File(song.localFilePath).let { it.exists() && it.length() > 0 }

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

            // 本地库智能匹配：当直接路径为空时，尝试从本地曲库匹配已下载的物理音频（定向索引查询，严禁全库扫描）
            directLocalPath ?: try {
                val candidates = loadCandidates()
                val matched = candidates.firstOrNull { s ->
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

        val currentPreferredQuality = overrideQuality ?: LemonMusicProtocol.getPreferredStreamQuality(context)
        val targetQuality = com.lm.player.core.model.AudioQuality.fromKey(currentPreferredQuality)
        val targetBitRate = targetQuality.bitrate
        val isStaleQuality = if (streamPreResolved && song.streamUrl.startsWith("http")) {
            false
        } else {
            (song.bitRate != targetBitRate && song.localFilePath.isNullOrBlank() && song.downloadStatus != DownloadStatus.DOWNLOADED) || overrideQuality != null
        }

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
            publishSongUpdate(updated, prefetch)
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val db = ZdsDatabase.getInstance(context)
                    val active = db.serverDao().getActiveServer()
                        ?: db.serverDao().getAllServers().firstOrNull { it.id == song.serverId || it.type == ServerType.LEMON_MUSIC }
                    val accelPriority = if (prefetch != null) com.lm.player.core.source.ResolvePriority.PREFETCH
                        else com.lm.player.core.source.ResolvePriority.PLAY
                    val accelQuality = overrideQuality ?: currentPreferredQuality

                    if ((active == null || active.type != ServerType.LEMON_MUSIC) &&
                        (song.id.startsWith("lemon_online_") || song.serverId == "lemon_online")
                    ) {
                        // 没有柠檬服务器（已删除/解绑）：在线曲目直接由本地音源取链（需求②）
                        val hit = com.lm.player.core.source.PlaybackAccelerator.resolveOnline(
                            song = song,
                            preferredQuality = accelQuality,
                            priority = accelPriority,
                            refresh = forceRefresh,
                            server = null
                        ) as? com.lm.player.core.source.PlaybackAccelerator.Outcome.LocalHit
                        if (hit != null) {
                            finalStreamUrl = hit.url
                            publishSongUpdate(song.copy(streamUrl = hit.url, format = hit.quality.format.lowercase(), bitRate = hit.quality.bitrate), prefetch)
                            Log.i(TAG, "Detached local source resolved: ${song.title}")
                        }
                    }

                    if (active != null && active.type == ServerType.LEMON_MUSIC) {
                        val client = NetworkClientFactory.createOkHttpClient(context)
                        val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)

                        val isOnlineId = song.id.startsWith("lemon_online_") || song.serverId == "lemon_online"
                        val isServerSong = !isOnlineId && song.serverId.isNotBlank() && song.serverId != "local_storage"

                        var accelerated = false
                        var accelerationAttempted = false
                        var serverAlreadyTried = false
                        var hedgedServerStream: com.lm.player.core.model.ResolvedOnlineStream? = null
                        var localQuality: com.lm.player.core.model.AudioQuality? = null
                        val accelMode = com.lm.player.core.source.SourceAccelerationController.playbackMode()

                        // 取链调度（PlaybackAccelerator）：外网/内网高延迟 → 本地优先，1.2 秒没结果时与服务器并行竞速；
                        // 服务器离线/删除 → 只走本地（10 秒预算，含跨平台同曲）；内网低延迟 → 服务器优先。
                        if (isOnlineId && (!forceRefresh || accelMode == com.lm.player.core.source.SourceAccelerationController.Mode.LOCAL_ONLY)) {
                            val clean = song.id.removePrefix("lemon_online_")
                            val serverSource = if (clean.contains("_")) clean.substringBefore("_") else "kw"
                            val serverMeta = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
                            val outcome = com.lm.player.core.source.PlaybackAccelerator.resolveOnline(
                                song = song,
                                preferredQuality = accelQuality,
                                priority = accelPriority,
                                refresh = forceRefresh,
                                server = {
                                    protocol.ensureAuthenticated()
                                    protocol.resolveOnlineStreamWithQuality(
                                        songId = song.id,
                                        source = serverSource,
                                        preferredQuality = accelQuality,
                                        metaJson = serverMeta,
                                        fallbackTitle = song.title,
                                        fallbackArtist = song.artist,
                                        refresh = forceRefresh,
                                        skipLocalAcceleration = true
                                    ).getOrNull()
                                }
                            )
                            when (outcome) {
                                is com.lm.player.core.source.PlaybackAccelerator.Outcome.LocalHit -> {
                                    accelerationAttempted = true
                                    finalStreamUrl = outcome.url
                                    accelerated = true
                                    localQuality = outcome.quality
                                    if (outcome.downgraded) {
                                        val msg = "已自动为您换源降至【${outcome.quality.label}】播放"
                                        if (prefetch != null) {
                                            prefetch.pendingToast = msg
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    Log.i(TAG, "Home/playback local acceleration resolved: ${song.title} (${outcome.platform}/${outcome.quality.key})")
                                }
                                is com.lm.player.core.source.PlaybackAccelerator.Outcome.ServerHit -> {
                                    accelerationAttempted = true
                                    hedgedServerStream = outcome.stream
                                    Log.i(TAG, "Home/playback hedged server resolved first: ${song.title}")
                                }
                                is com.lm.player.core.source.PlaybackAccelerator.Outcome.Miss -> {
                                    accelerationAttempted = outcome.localAttempted
                                    serverAlreadyTried = outcome.serverAttempted
                                }
                            }
                        } else if (isServerSong && com.lm.player.core.source.SourceAccelerationController.shouldAccelerate()) {
                            // 服务器曲库：用本地目录服务搜索同曲目再本地取链（不经服务器，结果已经过 CDN 探活）
                            accelerationAttempted = true
                            val resolved = com.lm.player.core.source.ServerLibraryAccelerationResolver.resolve(
                                protocol = protocol,
                                serverId = active.id,
                                song = song,
                                overrideQuality = overrideQuality,
                                refresh = forceRefresh,
                                priority = accelPriority
                            ).getOrNull()
                            resolved?.let {
                                finalStreamUrl = it.url
                                accelerated = true
                                localQuality = it.quality
                                Log.i(TAG, "Server library local acceleration resolved: ${song.title} via ${it.source}")
                            }
                        }

                        if (!accelerated) {
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
                            // 从本地数据库中查找同 ID 或完全同版本服务端曲目的有效路径/流地址（定向候选匹配）
                            val candidates = loadCandidates()
                            val matchedServerSong = candidates.firstOrNull { s ->
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

                            val preferredQuality = overrideQuality ?: currentPreferredQuality
                            val resolvedStream = hedgedServerStream ?: if (serverAlreadyTried) null else protocol.resolveOnlineStreamWithQuality(
                                songId = song.id,
                                source = source,
                                preferredQuality = preferredQuality,
                                metaJson = meta,
                                fallbackTitle = song.title,
                                fallbackArtist = song.artist,
                                refresh = forceRefresh,
                                // 上面已经试过一次本地加速；失败后不要在协议层再试一次。
                                skipLocalAcceleration = accelerationAttempted
                            ).getOrNull()

                            if (resolvedStream != null && resolvedStream.url.isNotBlank()) {
                                finalStreamUrl = resolvedStream.url
                                resolvedFormat = resolvedStream.format
                                resolvedBitRate = resolvedStream.bitRate
                                val globalPrefQ = LemonMusicProtocol.getPreferredStreamQuality(context)
                                if (resolvedStream.isDowngraded || (overrideQuality != null && overrideQuality != globalPrefQ)) {
                                    val qLabel = com.lm.player.core.model.AudioQuality.fromKey(resolvedStream.qualityKey).label
                                    val msg = "已自动为您换源降至【$qLabel】播放"
                                    if (prefetch != null) {
                                        // 预取阶段不打扰用户：真正切到这首歌时再提示
                                        prefetch.pendingToast = msg
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }

                        // 若服务器原文件音质直接匹配试听音质，或在线音源解析未命中，使用服务端本地曲目流解析（带试听音质参数）
                        if (serverMatchesTargetQuality || finalStreamUrl.isBlank() || finalStreamUrl.startsWith("lemon_online://")) {
                            if (!serverPath.isNullOrBlank() || !trackId.isNullOrBlank()) {
                                // C-08：偏好「标准 128K」时服务器无损原文件改走 local-smooth（AAC 128K），
                                // 服务端 /api/play/local 与 /api/play/url 都会忽略 quality；无 ffmpeg 时回落原有路径。
                                val smoothUrl = serverPath?.let { p ->
                                    protocol.getPlaybackStreamUrlForPath(p, currentPreferredQuality)
                                        .takeIf { it.contains("/api/play/local-smooth") }
                                }
                                val srvUrl = smoothUrl
                                    ?: protocol.resolveServerLocalPlayUrl(serverPath, trackId, quality = currentPreferredQuality).getOrNull()
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
                        }

                        var resolvedFormat = song.format
                        var resolvedBitRate = song.bitRate
                        localQuality?.let { q ->
                            resolvedFormat = q.format.lowercase()
                            resolvedBitRate = q.bitrate
                        }

                        if (finalStreamUrl.isNotBlank() && (finalStreamUrl != song.streamUrl || resolvedFormat != song.format || resolvedBitRate != song.bitRate)) {
                            val updated = song.copy(
                                streamUrl = finalStreamUrl,
                                format = resolvedFormat,
                                bitRate = resolvedBitRate
                            )
                            publishSongUpdate(updated, prefetch)
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
        // 服务器路径（内网服务器优先模式）也没拿到可播地址：最后用本地音源兜底一次
        val onlineForFallback = song.id.startsWith("lemon_online_") || song.serverId == "lemon_online"
        if (resolvedLocalPath == null && onlineForFallback &&
            !finalStreamUrl.startsWith("http://", ignoreCase = true) && !finalStreamUrl.startsWith("https://", ignoreCase = true) &&
            !finalStreamUrl.startsWith("content://") && !(finalStreamUrl.startsWith("/") && File(finalStreamUrl).exists())
        ) {
            val fallback = com.lm.player.core.source.PlaybackAccelerator.fallbackLocal(
                song,
                currentPreferredQuality,
                if (prefetch != null) com.lm.player.core.source.ResolvePriority.PREFETCH else com.lm.player.core.source.ResolvePriority.PLAY
            )
            if (fallback != null) {
                finalStreamUrl = fallback.url
                publishSongUpdate(song.copy(streamUrl = fallback.url, format = fallback.quality.format.lowercase(), bitRate = fallback.quality.bitrate), prefetch)
                Log.i(TAG, "Server path failed, local source fallback resolved: ${song.title}")
            }
        }

        val hasPlayableUrl = resolvedLocalPath != null ||
                (finalStreamUrl.isNotBlank() && !finalStreamUrl.startsWith("lemon_online://"))
        if (!hasPlayableUrl) {
            Log.w(TAG, "无法为「${song.title}」解析出可播放地址 (本地文件/真实直链均未命中)，本次播放放弃")
            return null
        }

        val uri: Uri = if (resolvedLocalPath != null) {
            if (resolvedLocalPath.startsWith("content://")) {
                Log.d(TAG, "Routing to local content uri: $resolvedLocalPath")
                Uri.parse(resolvedLocalPath)
            } else {
                Log.d(TAG, "Routing to local file: $resolvedLocalPath")
                Uri.fromFile(File(resolvedLocalPath))
            }
        } else if (finalStreamUrl.startsWith("content://")) {
            Log.d(TAG, "Routing to SAF content uri: $finalStreamUrl")
            Uri.parse(finalStreamUrl)
        } else {
            Log.d(TAG, "Routing to remote stream: $finalStreamUrl")
            Uri.parse(finalStreamUrl)
        }

        val isOffline = uri.scheme == "file" || uri.scheme == "content"

        // 封面兜底回填：若歌曲本身 coverUrl 为空或指向已知的 404 嵌入封面，从内存协议缓存与数据库同曲目自动补全
        val effectiveCover = if (song.coverUrl.isNotBlank() && !com.lm.player.core.network.LemonMusicProtocol.isKnownMissingEmbeddedCoverUrl(song.coverUrl)) {
            song.coverUrl
        } else {
            val fromProto = com.lm.player.core.network.LemonMusicProtocol.getResolvedCover(song.id)
            if (!fromProto.isNullOrBlank()) {
                fromProto
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        val db = com.lm.player.core.database.ZdsDatabase.getInstance(context)
                        val candidates = db.songDao().findCandidatesForPlayback(song.id, song.title)
                        candidates.firstOrNull {
                            it.coverUrl.isNotBlank() &&
                            !com.lm.player.core.network.LemonMusicProtocol.isKnownMissingEmbeddedCoverUrl(it.coverUrl)
                        }?.coverUrl.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                }
            }
        }

        val effectiveSong = if (effectiveCover.isNotBlank() && effectiveCover != song.coverUrl) {
            val songWithCover = song.copy(coverUrl = effectiveCover)
            publishSongUpdate(songWithCover, prefetch)
            songWithCover
        } else {
            song
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(effectiveSong.title)
            .setDisplayTitle(effectiveSong.title)
            .setArtist(effectiveSong.artist)
            .setAlbumTitle(effectiveSong.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            // 第二十四节：私有文件 / 需登录的服务器封面换成本 App 公开的 content 地址，第三方桌面才读得到
            .setArtworkUri(SessionArtworkStore.artworkUriFor(context, effectiveSong.coverUrl))
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

    /** 播客单集的 MediaItem：流地址为 RSS 音频直链或服务器代理地址 */
    private fun buildPodcastMediaItem(song: UnifiedSong): MediaItem? {
        val url = song.streamUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "播客单集没有可用的音频地址: ${song.title}")
            return null
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setArtworkUri(SessionArtworkStore.artworkUriFor(context, song.coverUrl))
            .setExtras(Bundle().apply {
                putBoolean("KEY_IS_OFFLINE", false)
                putString("KEY_SONG_ID", song.id)
                putString("KEY_SERVER_ID", song.serverId)
                putInt("KEY_BITRATE", song.bitRate)
            })
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(com.lm.player.core.network.ServerUrlPolicy.normalizeSchemeCase(url)))
            .setMediaMetadata(metadata.build())
            .build()
    }
}
