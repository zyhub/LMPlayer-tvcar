package com.lm.player.core.media

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
object PlaybackQueueManager {

    private const val TAG = "PlaybackQueueManager"
    private const val AUTO_PLAY_PREFS = "zds_auto_play_prefs"
    private const val MAX_PERSISTED_QUEUE_SIZE = 120

    /** 本地播放足迹持久化 (离线/未连接服务器时「最近播放」卡片的数据来源) */
    private const val RECENT_PLAY_PREFS = "zds_recent_play_prefs"
    private const val KEY_RECENT_PLAYED_SONGS = "recent_played_songs_json"

    /**
     * 每首歌的真实播放时间 (songId → epochMs)，与歌曲列表并存。
     * 旧版本没有这份数据，读取时按"越靠前越新"回填，升级不丢序。
     */
    private const val KEY_RECENT_PLAYED_AT = "recent_played_at_json"
    private const val MAX_RECENT_PLAY_SIZE = 60

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playJob: Job? = null
    private var positionSaveJob: Job? = null

    /**
     * 串行 IO 执行器（单线程 = 严格按提交顺序执行）：
     * 1) 「最近播放」足迹的读-改-写在这里排队：每次播放都要落盘一份最多 60 首的 JSON
     *    (解析 + 序列化 + 写盘)，原先整个过程发生在主线程，且 playSong 与 MainActivity 的
     *    isPlaying 效应两处调用会并发读写同一份 prefs —— 串行化后主线程零成本，写竞争也天然消除。
     * 2) 整条播放队列的磁盘校验 (mergeSongMetadata 内的 File.exists) 同样移到这里，
     *    避免曲库数据变化时在主线程对最多 120 首队列逐个 stat。
     */
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlaybackQueue-IO").apply { isDaemon = true }
    }

    private val _playlistFlow = MutableStateFlow<List<UnifiedSong>>(emptyList())
    val playlistFlow: StateFlow<List<UnifiedSong>> = _playlistFlow.asStateFlow()

    private val _currentSongFlow = MutableStateFlow<UnifiedSong?>(null)
    val currentSongFlow: StateFlow<UnifiedSong?> = _currentSongFlow.asStateFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _isShuffleFlow = MutableStateFlow(false)
    val isShuffleFlow: StateFlow<Boolean> = _isShuffleFlow.asStateFlow()

    private val _isRepeatFlow = MutableStateFlow(false)
    val isRepeatFlow: StateFlow<Boolean> = _isRepeatFlow.asStateFlow()

    /**
     * 「最近播放」的**唯一真源**。
     * 全系统没有任何"播放时间"字段，顺序 100% 依赖列表排列，因此任何一处手工拼接
     * (`serverList + local.filter{...}`) 都会永久打乱顺序且无法自愈。
     * 这里把"前移/合并"收成两个方法，界面只 collect 本 flow，不再各自维护副本。
     */
    private val _recentPlayedSongsFlow = MutableStateFlow<List<UnifiedSong>>(emptyList())
    val recentPlayedSongsFlow: StateFlow<List<UnifiedSong>> = _recentPlayedSongsFlow.asStateFlow()

    private val recentPlayLock = Any()
    private var recentPlayedAt: MutableMap<String, Long> = HashMap()
    @Volatile private var recentPlayedPrimed = false

    private var isListenerAttached = false
    private var appContext: Context? = null

    private fun songToJson(song: UnifiedSong): JSONObject {
        return JSONObject().apply {
            put("id", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("artistId", song.artistId)
            put("album", song.album)
            put("albumId", song.albumId)
            put("durationMs", song.durationMs)
            put("coverUrl", song.coverUrl)
            put("streamUrl", song.streamUrl)
            put("serverId", song.serverId)
            put("localFilePath", song.localFilePath ?: "")
            put("downloadStatus", song.downloadStatus.name)
            put("bitRate", song.bitRate)
            put("format", song.format)
            put("isFavorite", song.isFavorite)
            put("relativeFolderPath", song.relativeFolderPath ?: "")
            put("addedTimestamp", song.addedTimestamp)
            put("rawMetaJson", song.rawMetaJson ?: "")
        }
    }

    private fun jsonToSong(obj: JSONObject): UnifiedSong? {
        val id = obj.optString("id", "").trim()
        val title = obj.optString("title", "").trim()
        if (id.isEmpty() && title.isEmpty()) return null
        val dlStatusStr = obj.optString("downloadStatus", DownloadStatus.NOT_DOWNLOADED.name)
        val dlStatus = try {
            DownloadStatus.valueOf(dlStatusStr)
        } catch (_: Exception) {
            DownloadStatus.NOT_DOWNLOADED
        }
        return UnifiedSong(
            id = id.ifEmpty { "restored_${title.hashCode()}" },
            title = title.ifEmpty { "未知曲目" },
            artist = obj.optString("artist", "未知歌手"),
            artistId = obj.optString("artistId", ""),
            album = obj.optString("album", ""),
            albumId = obj.optString("albumId", ""),
            durationMs = obj.optLong("durationMs", 0L),
            coverUrl = obj.optString("coverUrl", ""),
            streamUrl = obj.optString("streamUrl", ""),
            serverId = obj.optString("serverId", "default"),
            localFilePath = obj.optString("localFilePath", "").ifBlank { null },
            downloadStatus = dlStatus,
            bitRate = obj.optInt("bitRate", 320),
            format = obj.optString("format", "flac"),
            isFavorite = obj.optBoolean("isFavorite", false),
            relativeFolderPath = obj.optString("relativeFolderPath", "").ifBlank { null },
            addedTimestamp = obj.optLong("addedTimestamp", 0L),
            rawMetaJson = obj.optString("rawMetaJson", "").ifBlank { null }
        )
    }

    // 队列 JSON 序列化缓存。
    // 播放期间每 5 秒就要落盘一次进度，其中队列本身几乎不变，而 queueToJson 最多要序列化 120 首
    // (每首一个 JSONObject)，重复做纯属浪费，因此按「队列内容 + 当前曲目」的轻量哈希做缓存。
    private var cachedQueueJson: String? = null
    private var cachedQueueKey: Int = 0

    private fun queueToJsonCached(queue: List<UnifiedSong>, currentSongId: String?): String {
        var key = currentSongId?.hashCode() ?: 0
        for (s in queue) {
            key = key * 31 + s.id.hashCode()
        }
        val cached = cachedQueueJson
        if (cached != null && key == cachedQueueKey) return cached
        val json = queueToJson(queue, currentSongId)
        cachedQueueJson = json
        cachedQueueKey = key
        return json
    }

    private fun queueToJson(queue: List<UnifiedSong>, currentSongId: String?): String {
        if (queue.isEmpty()) return "[]"
        val windowed = if (queue.size <= MAX_PERSISTED_QUEUE_SIZE) {
            queue
        } else {
            val idx = queue.indexOfFirst { it.id == currentSongId }.coerceAtLeast(0)
            val half = MAX_PERSISTED_QUEUE_SIZE / 2
            val start = (idx - half).coerceAtLeast(0)
            val end = (start + MAX_PERSISTED_QUEUE_SIZE).coerceAtMost(queue.size)
            val adjustedStart = (end - MAX_PERSISTED_QUEUE_SIZE).coerceAtLeast(0)
            queue.subList(adjustedStart, end)
        }
        val arr = JSONArray()
        for (s in windowed) {
            arr.put(songToJson(s))
        }
        return arr.toString()
    }

    fun getSavedLastSong(context: Context): UnifiedSong? {
        return try {
            val prefs = context.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            val rawJson = prefs.getString("last_played_song_json", null)
            if (!rawJson.isNullOrBlank()) {
                jsonToSong(JSONObject(rawJson))
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved last song", e)
            null
        }
    }

    fun getSavedQueue(context: Context): List<UnifiedSong> {
        return try {
            val prefs = context.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            val rawJson = prefs.getString("last_played_queue_json", null)
            if (!rawJson.isNullOrBlank()) {
                val arr = JSONArray(rawJson)
                val list = ArrayList<UnifiedSong>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    jsonToSong(obj)?.let { list.add(it) }
                }
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved queue", e)
            emptyList()
        }
    }

    /** 「最近播放」的一次完整快照：歌曲列表 + 每首的真实播放时间。 */
    private data class RecentPlaySnapshot(
        val songs: List<UnifiedSong>,
        val playedAt: Map<String, Long>
    )

    private fun sortedRecent(snapshot: RecentPlaySnapshot): List<UnifiedSong> =
        snapshot.songs.sortedByDescending { snapshot.playedAt[it.id] ?: 0L }

    private fun readRecentPlaySnapshot(context: Context): RecentPlaySnapshot {
        return try {
            val prefs = context.getSharedPreferences(RECENT_PLAY_PREFS, Context.MODE_PRIVATE)
            val rawJson = prefs.getString(KEY_RECENT_PLAYED_SONGS, null)
            if (rawJson.isNullOrBlank()) return RecentPlaySnapshot(emptyList(), emptyMap())
            val arr = JSONArray(rawJson)
            val songs = ArrayList<UnifiedSong>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                jsonToSong(obj)?.let { songs.add(it) }
            }
            if (songs.isEmpty()) return RecentPlaySnapshot(emptyList(), emptyMap())

            val stored = HashMap<String, Long>()
            val rawAt = prefs.getString(KEY_RECENT_PLAYED_AT, null)
            if (!rawAt.isNullOrBlank()) {
                val atObj = JSONObject(rawAt)
                val keys = atObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = atObj.optLong(key, 0L)
                    if (value > 0L) stored[key] = value
                }
            }
            // 老数据（升级前的版本）没有播放时间。落盘时列表就是"新→旧"排好的，
            // 所以以已知最大时间为基准按索引递减回填：升级后顺序原样保留，不会被全部打平。
            val base = stored.values.maxOrNull() ?: System.currentTimeMillis()
            val playedAt = HashMap<String, Long>(songs.size)
            songs.forEachIndexed { index, song ->
                playedAt[song.id] = stored[song.id] ?: (base - index).coerceAtLeast(1L)
            }
            RecentPlaySnapshot(songs, playedAt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse recent played songs", e)
            RecentPlaySnapshot(emptyList(), emptyMap())
        }
    }

    private fun persistRecentPlaySnapshot(appCtx: Context, snapshot: RecentPlaySnapshot) {
        try {
            val prefs = appCtx.getSharedPreferences(RECENT_PLAY_PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray()
            for (s in snapshot.songs) arr.put(songToJson(s))
            val atObj = JSONObject()
            for ((id, at) in snapshot.playedAt) atObj.put(id, at)
            prefs.edit()
                .putString(KEY_RECENT_PLAYED_SONGS, arr.toString())
                .putString(KEY_RECENT_PLAYED_AT, atObj.toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist recent play snapshot", e)
        }
    }

    /**
     * 首次进入界面时同步装载一次「最近播放」到内存 flow（单次读盘成本与原实现一致），
     * 之后所有增改都只写内存 flow，落盘在串行 IO 上排队。
     */
    fun primeRecentPlayedSongs(context: Context) {
        if (recentPlayedPrimed) return
        val appCtx = context.applicationContext
        synchronized(recentPlayLock) {
            if (recentPlayedPrimed) return
            val snapshot = readRecentPlaySnapshot(appCtx)
            recentPlayedAt = HashMap(snapshot.playedAt)
            _recentPlayedSongsFlow.value = sortedRecent(snapshot)
            recentPlayedPrimed = true
        }
    }

    /**
     * 本地「最近播放」足迹（按真实播放时间倒序）。
     * 未连接柠檬服务器时，「我的 → 最近播放」卡片即以此为准。
     */
    fun getRecentPlayedSongs(context: Context): List<UnifiedSong> =
        sortedRecent(readRecentPlaySnapshot(context.applicationContext))

    /**
     * 把一首歌前移到「最近播放」首位（同一首重复播放只前移、不堆积）。
     * **所有播放入口的唯一写入口** —— 各处不要再自己写 `listOf(song) + filter`。
     * 内存 flow 立即更新，落盘在串行 IO 上排队，调用方零成本。
     */
    fun promoteToRecentPlay(context: Context, song: UnifiedSong) {
        if (song.id.isBlank() && song.title.isBlank()) return
        val appCtx = context.applicationContext
        primeRecentPlayedSongs(appCtx)
        val snapshot: RecentPlaySnapshot
        synchronized(recentPlayLock) {
            val current = _recentPlayedSongsFlow.value
            val now = System.currentTimeMillis()
            val updated = ArrayList<UnifiedSong>(MAX_RECENT_PLAY_SIZE)
            updated.add(song)
            for (s in current) {
                if (s.id == song.id) continue
                if (updated.size >= MAX_RECENT_PLAY_SIZE) break
                updated.add(s)
            }
            val playedAt = HashMap<String, Long>(updated.size)
            updated.forEachIndexed { index, s ->
                playedAt[s.id] = if (s.id == song.id) {
                    now
                } else {
                    // 未知播放时间的条目按"列表位置"回填，保证与既有顺序一致
                    recentPlayedAt[s.id] ?: (now - index).coerceAtLeast(1L)
                }
            }
            recentPlayedAt = playedAt
            _recentPlayedSongsFlow.value = updated
            snapshot = RecentPlaySnapshot(updated, playedAt)
        }
        ioExecutor.execute { persistRecentPlaySnapshot(appCtx, snapshot) }
    }

    /**
     * 合并服务器下发的播放足迹（约定按"新→旧"给出）。
     *
     * 旧实现是 `serverRecent + 本地.filter{不在服务器}`：服务器整段压在最前面，
     * 本地独有的播放记录全部沉底，而服务器数组本身没有任何顺序保证 —— 这正是
     * 「最近播放」看不出最近顺序的直接原因。这里改为**按时序插值**：
     * 本地已经记录了真实播放时间的条目一律保留自己的时间（不被服务器覆盖）；
     * 服务器独有的条目按游标递减插入，扫描到已知播放时间的条目时把游标对齐到它的时间，
     * 使服务器独有的历史落在与本地真实时序相称的位置（两条已知时间之间的条目即插在中间）。
     */
    fun mergeServerRecentPlays(context: Context, serverSongs: List<UnifiedSong>) {
        if (serverSongs.isEmpty()) return
        val appCtx = context.applicationContext
        primeRecentPlayedSongs(appCtx)
        val snapshot: RecentPlaySnapshot
        synchronized(recentPlayLock) {
            val byId = LinkedHashMap<String, UnifiedSong>()
            // 先放本地：本地那份可能带本地文件路径，且并列时优先保持本地顺序
            for (s in _recentPlayedSongsFlow.value) byId[s.id] = s
            val mergedAt = HashMap<String, Long>(recentPlayedAt)

            // 起点取"已知最早时间 - 1"而不是"已知最新时间"：服务器足迹里**没有播放时间**，
            // 它无权声称比本机刚播过的歌更新。从最早一条往下排，服务器独有的历史无论如何
            // 都不会把本机真实的最近播放挤下去（有本地时间的条目仍保留自己的时间）。
            // 本地没有任何足迹时（新装/全新设备）则以当前时间为起点，纯按服务器顺序排列。
            var cursor = mergedAt.values.minOrNull()?.minus(1L)?.coerceAtLeast(1L)
                ?: System.currentTimeMillis()
            for (s in serverSongs) {
                if (s.id.isBlank()) continue
                val known = mergedAt[s.id]
                if (known != null && known > 0L) {
                    cursor = known
                } else {
                    mergedAt[s.id] = cursor
                    cursor = (cursor - 1L).coerceAtLeast(1L)
                }
                if (!byId.containsKey(s.id)) byId[s.id] = s
            }

            val mergedSongs = byId.values
                .sortedByDescending { mergedAt[it.id] ?: 0L }
                .take(MAX_RECENT_PLAY_SIZE)
            val finalAt = HashMap<String, Long>(mergedSongs.size)
            for (s in mergedSongs) finalAt[s.id] = mergedAt[s.id] ?: 0L
            recentPlayedAt = finalAt
            _recentPlayedSongsFlow.value = mergedSongs
            snapshot = RecentPlaySnapshot(mergedSongs, finalAt)
        }
        ioExecutor.execute { persistRecentPlaySnapshot(appCtx, snapshot) }
    }

    fun getSavedPositionMs(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            prefs.getLong("last_played_position_ms", 0L).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    fun savePlaybackState(
        context: Context? = appContext,
        song: UnifiedSong? = _currentSongFlow.value,
        positionMs: Long? = null,
        commitSync: Boolean = false
    ) {
        val ctx = context ?: appContext ?: return
        val target = song ?: _currentSongFlow.value ?: return
        try {
            val prefs = ctx.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putString("last_played_song_id", target.id)
                .putString("last_played_song_title", target.title)
                .putString("last_played_song_artist", target.artist)
                .putString("last_played_song_json", songToJson(target).toString())
            if (_playlistFlow.value.isNotEmpty()) {
                editor.putString("last_played_queue_json", queueToJsonCached(_playlistFlow.value, target.id))
            }
            if (positionMs != null && positionMs >= 0L) {
                editor.putLong("last_played_position_ms", positionMs)
            }
            if (commitSync) {
                editor.commit()
            } else {
                editor.apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback state", e)
        }
    }

    fun initFromPrefs(context: Context) {
        appContext = context.applicationContext
        try {
            val prefs = context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
            _isShuffleFlow.value = prefs.getBoolean("playback_is_shuffle", false)
            _isRepeatFlow.value = prefs.getBoolean("playback_is_repeat", false)
        } catch (_: Exception) {}

        try {
            val savedQueue = getSavedQueue(context)
            if (_playlistFlow.value.isEmpty() && savedQueue.isNotEmpty()) {
                _playlistFlow.value = savedQueue
            }
            val savedSong = getSavedLastSong(context)
            if (_currentSongFlow.value == null && savedSong != null) {
                _currentSongFlow.value = savedSong
            }
        } catch (_: Exception) {}
    }

    fun setQueue(songs: List<UnifiedSong>) {
        _playlistFlow.value = songs
        savePlaybackState(commitSync = false)
    }

    fun setInitialSongIfAbsent(song: UnifiedSong, playlist: List<UnifiedSong>) {
        if (_currentSongFlow.value == null) {
            _currentSongFlow.value = song
            if (_playlistFlow.value.isEmpty() && playlist.isNotEmpty()) {
                _playlistFlow.value = playlist
            }
        }
    }

    private fun mergeSongMetadata(existing: UnifiedSong, incoming: UnifiedSong): UnifiedSong {
        val validExistingLocal = existing.localFilePath?.takeIf {
            it.isNotBlank() && (it.startsWith("content://") || java.io.File(it).exists())
        }
        val validIncomingLocal = incoming.localFilePath?.takeIf {
            it.isNotBlank() && (it.startsWith("content://") || java.io.File(it).exists())
        }
        val effectiveLocal = validIncomingLocal ?: validExistingLocal
        val effectiveStream = when {
            !effectiveLocal.isNullOrBlank() -> effectiveLocal
            incoming.streamUrl.isNotBlank() && !incoming.streamUrl.startsWith("lemon_online://") -> incoming.streamUrl
            existing.streamUrl.isNotBlank() -> existing.streamUrl
            else -> incoming.streamUrl
        }
        return incoming.copy(
            localFilePath = effectiveLocal,
            streamUrl = effectiveStream,
            downloadStatus = if (!effectiveLocal.isNullOrBlank()) DownloadStatus.DOWNLOADED else incoming.downloadStatus,
            rawMetaJson = incoming.rawMetaJson ?: existing.rawMetaJson
        )
    }

    /**
     * 用最新的曲库数据刷新队列条目的元信息。
     *
     * S9③：整条队列的磁盘校验（mergeSongMetadata 内的 File.exists，最多 120 首 × 2 次）
     * 与合并计算整体移出主线程；调用点 MainActivity 的曲库 Flow 收集器原先在主线程执行本函数。
     * 基准队列在任务真正执行时才读取（StateFlow 读写线程安全），因此不会用过期快照
     * 覆盖此间由用户操作 (setQueue / updatePlaylist) 刚写入的队列。
     */
    fun updateMetadata(songs: List<UnifiedSong>) {
        ioExecutor.execute {
            try {
                val base = _playlistFlow.value
                if (base.isEmpty()) {
                    _playlistFlow.value = songs
                } else {
                    val songMap = songs.associateBy { it.id }
                    _playlistFlow.value = base.map { existing ->
                        val matched = songMap[existing.id]
                        if (matched != null) mergeSongMetadata(existing, matched) else existing
                    }
                }
                val current = _currentSongFlow.value
                if (current != null) {
                    val updated = songs.firstOrNull { it.id == current.id }
                    if (updated != null) {
                        val merged = mergeSongMetadata(current, updated)
                        if (merged != current) {
                            _currentSongFlow.value = merged
                            savePlaybackState(song = merged, commitSync = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update queue metadata", e)
            }
        }
    }

    fun updatePlaylist(songs: List<UnifiedSong>) {
        _playlistFlow.value = songs
        val current = _currentSongFlow.value
        if (current != null) {
            val updated = songs.firstOrNull { it.id == current.id }
            if (updated != null && updated != current) {
                _currentSongFlow.value = mergeSongMetadata(current, updated)
            }
        }
        savePlaybackState(commitSync = false)
    }

    fun updateCurrentSong(song: UnifiedSong) {
        _currentSongFlow.value = song
        _playlistFlow.value = _playlistFlow.value.map { if (it.id == song.id) song else it }
        savePlaybackState(song = song, commitSync = false)
    }

    fun setShuffle(shuffle: Boolean) {
        _isShuffleFlow.value = shuffle
        try {
            appContext?.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("playback_is_shuffle", shuffle)?.apply()
        } catch (_: Exception) {}
    }

    fun setRepeat(repeat: Boolean) {
        _isRepeatFlow.value = repeat
        try {
            appContext?.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("playback_is_repeat", repeat)?.apply()
        } catch (_: Exception) {}
    }

    private fun startPeriodicPositionSave(context: Context, player: Player) {
        positionSaveJob?.cancel()
        positionSaveJob = coroutineScope.launch {
            while (isActive && _isPlayingFlow.value) {
                delay(5000L)
                if (player.isPlaying) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    if (pos > 0L) {
                        savePlaybackState(context, _currentSongFlow.value, positionMs = pos, commitSync = false)
                    }
                }
            }
        }
    }

    private var listenedPlayer: Player? = null
    private var playerListener: Player.Listener? = null
    private var decoderWatchdogJob: Job? = null

    fun ensurePlayerListener(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        val ctx = context.applicationContext
        if (!isListenerAttached) {
            // 播放内核可能因硬件解码异常被整体重建，重建后需要把监听重新挂到新实例上
            Media3Factory.addPlayerSwapListener { newPlayer -> attachToPlayer(newPlayer, ctx) }
            isListenerAttached = true
        }
        attachToPlayer(Media3Factory.getSharedExoPlayer(ctx), ctx)
    }

    private fun attachToPlayer(player: Player, context: Context) {
        val previousListener = playerListener
        val previousPlayer = listenedPlayer
        if (previousListener != null && previousPlayer === player) return
        if (previousListener != null && previousPlayer != null) {
            try {
                previousPlayer.removeListener(previousListener)
            } catch (_: Exception) {}
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlayingFlow.value = playing
                if (playing) {
                    startPeriodicPositionSave(context, player)
                    startDecoderWatchdog(player, context)
                } else {
                    positionSaveJob?.cancel()
                    decoderWatchdogJob?.cancel()
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    if (pos > 0L) {
                        savePlaybackState(context, _currentSongFlow.value, positionMs = pos, commitSync = false)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (_isRepeatFlow.value && _currentSongFlow.value != null) {
                        playSong(_currentSongFlow.value!!, context)
                    } else {
                        playNext(context)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error, context)
            }
        }
        player.addListener(listener)
        playerListener = listener
        listenedPlayer = player
        // 换绑时若正处于播放中需立刻重启看门狗 (onIsPlayingChanged 不会因挂监听而再次回调)；
        // 未播放时不启动，交由 onIsPlayingChanged(true) 启动，避免待机空转
        if (player.isPlaying) {
            startDecoderWatchdog(player, context)
        }
    }

    /**
     * 音频解码异常判定：
     * 命中解码/音轨相关错误码，或底层抛出 MediaCodec.CodecException 时，说明硬件解码器不可用。
     */
    private fun handlePlayerError(error: PlaybackException, context: Context) {
        val code = error.errorCode
        val isAudioDecodeFailure = code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.cause is MediaCodec.CodecException
        if (isAudioDecodeFailure && !Media3Factory.isSoftwareDecodingActive) {
            triggerSoftwareDecodingFallback(context, "音频解码错误 code=$code")
        } else {
            Log.e(TAG, "播放错误 code=$code", error)
        }
    }

    /**
     * 硬件解码卡死看门狗：
     * 部分 TV 芯片的硬解器既不报错也不出数据，表现为「缓冲已充足 (bufferedPosition 远超播放位)
     * 但播放位置长时间不前进、完全无声」。此时判定硬解异常并整体降级为软件解码。
     * 由于额外要求「本地已缓冲充足」，可避免把普通网络卡顿误判为解码故障。
     */
    private fun startDecoderWatchdog(player: Player, context: Context) {
        decoderWatchdogJob?.cancel()
        decoderWatchdogJob = coroutineScope.launch {
            var lastPosition = -1L
            var stalledTicks = 0
            var lastSongId: String? = null
            while (isActive) {
                delay(1000L)
                if (player !== Media3Factory.getSharedExoPlayer(context)) return@launch
                val songId = _currentSongFlow.value?.id
                if (songId != lastSongId) {
                    lastSongId = songId
                    lastPosition = -1L
                    stalledTicks = 0
                }
                // 未在播放时直接结束本次看门狗，恢复播放时由 onIsPlayingChanged 重新启动。
                // 原先这里是 continue，会让 24 小时开机的盒子在待机状态下每秒空转唤醒一次。
                // 说明：卡顿判定本来就只在 STATE_READY 下进行，因此退出 BUFFERING 分支不损失任何检测能力
                if (!player.playWhenReady || !player.isPlaying || player.playbackState != Player.STATE_READY) {
                    stalledTicks = 0
                    lastPosition = -1L
                    return@launch
                }
                val position = player.currentPosition
                val buffered = player.bufferedPosition
                val dataAvailable = buffered - position > 3_000L
                if (dataAvailable && position <= lastPosition) {
                    stalledTicks++
                } else {
                    stalledTicks = 0
                }
                lastPosition = position
                if (stalledTicks >= 6 && !Media3Factory.isSoftwareDecodingActive) {
                    triggerSoftwareDecodingFallback(context, "缓冲充足但播放位停滞 ${stalledTicks}s，疑似硬件解码卡死")
                    return@launch
                }
            }
        }
    }

    /**
     * 降级为纯软件解码并原地续播当前曲目。
     * 内部切到主线程下一个调度周期执行，避免在播放器回调栈内释放播放器实例。
     */
    private fun triggerSoftwareDecodingFallback(context: Context, reason: String) {
        if (Media3Factory.isSoftwareDecodingActive) return
        val song = _currentSongFlow.value ?: return
        val ctx = context.applicationContext
        val resumePosition = try {
            listenedPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
        Log.w(TAG, "触发软件解码降级：$reason (曲目=${song.title}, 续播位置=${resumePosition}ms)")

        coroutineScope.launch {
            try {
                val db = ZdsDatabase.getInstance(ctx)
                val router = PlaybackRouter(db.downloadDao(), ctx)
                val mediaItem: MediaItem? = router.resolveMediaItem(song, forceRefresh = false)
                if (mediaItem == null) {
                    // 解析不出可播放地址时不要重建播放器：否则旧播放器已被释放、新的又播不了，
                    // 等于把"解码降级"变成了"彻底静音"
                    Log.e(TAG, "软件解码降级放弃：仍未解析出可播放地址 (${song.title})")
                    return@launch
                }
                val player = Media3Factory.rebuildWithSoftwareDecoding(ctx)
                player.volume = 1.0f
                if (resumePosition > 0L) {
                    player.setMediaItem(mediaItem, resumePosition)
                } else {
                    player.setMediaItem(mediaItem)
                }
                player.prepare()
                player.play()
                Log.w(TAG, "已切换至软件解码并续播：${song.title}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "软件解码降级后续播失败", e)
            }
        }
    }

    fun playSong(
        targetSong: UnifiedSong,
        context: Context,
        newPlaylist: List<UnifiedSong>? = null,
        startPositionMs: Long = 0L,
        forceRefresh: Boolean = false,
        streamPreResolved: Boolean = false
    ) {
        ensurePlayerListener(context)
        _currentSongFlow.value = targetSong
        // 写入本地播放足迹：离线时「最近播放」卡片即展示这份记录
        promoteToRecentPlay(context, targetSong)
        if (newPlaylist != null && newPlaylist.isNotEmpty()) {
            _playlistFlow.value = newPlaylist
        } else if (_playlistFlow.value.isEmpty()) {
            _playlistFlow.value = listOf(targetSong)
        } else if (_playlistFlow.value.none { it.id == targetSong.id }) {
            _playlistFlow.value = listOf(targetSong) + _playlistFlow.value
        }

        // 立即同步持久化当前播放歌曲完整元数据、播放队列与起始进度，确保任意时刻关闭应用均可精准恢复
        savePlaybackState(
            context = context,
            song = targetSong,
            positionMs = startPositionMs.coerceAtLeast(0L),
            commitSync = true
        )

        playJob?.cancel()
        playJob = coroutineScope.launch {
            try {
                val db = ZdsDatabase.getInstance(context)
                val router = PlaybackRouter(db.downloadDao(), context)
                val mediaItem: MediaItem? = router.resolveMediaItem(
                    targetSong,
                    forceRefresh = forceRefresh,
                    streamPreResolved = streamPreResolved
                )
                if (mediaItem == null) {
                    // 明确告知用户"这首放不了"，而不是让它显示成正在播放却毫无声音
                    Log.e(TAG, "playSong 中断：无法解析播放地址 (${targetSong.title})")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context.applicationContext,
                            "「${targetSong.title}」暂时无法获取播放地址，请检查网络或服务器设置",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                val player = Media3Factory.getSharedExoPlayer(context)
                player.volume = 1.0f
                if (startPositionMs > 0L) {
                    player.setMediaItem(mediaItem, startPositionMs)
                } else {
                    player.setMediaItem(mediaItem)
                }
                player.prepare()
                player.play()
                Log.i(TAG, "playSong started: ${targetSong.title} (startPos=${startPositionMs}ms, forceRefresh=$forceRefresh)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error playing song: ", e)
            }
        }
    }

    fun playNext(context: Context) {
        ensurePlayerListener(context)
        val list = _playlistFlow.value
        if (list.isEmpty()) {
            Log.w(TAG, "playNext called but playlist is empty")
            return
        }
        val current = _currentSongFlow.value
        val isShuffle = _isShuffleFlow.value

        val nextSong: UnifiedSong = if (isShuffle) {
            val candidates = if (list.size > 1) list.filter { it.id != current?.id } else list
            candidates.random()
        } else {
            val currentIndex = list.indexOfFirst { it.id == current?.id }
            if (currentIndex >= 0 && currentIndex < list.size - 1) {
                list[currentIndex + 1]
            } else {
                list.first()
            }
        }
        Log.i(TAG, "playNext: switching to ${nextSong.title}")
        playSong(nextSong, context)
    }

    fun playPrevious(context: Context) {
        ensurePlayerListener(context)
        val list = _playlistFlow.value
        if (list.isEmpty()) {
            Log.w(TAG, "playPrevious called but playlist is empty")
            return
        }
        val current = _currentSongFlow.value
        val isShuffle = _isShuffleFlow.value

        val prevSong: UnifiedSong = if (isShuffle) {
            val candidates = if (list.size > 1) list.filter { it.id != current?.id } else list
            candidates.random()
        } else {
            val currentIndex = list.indexOfFirst { it.id == current?.id }
            if (currentIndex > 0) {
                list[currentIndex - 1]
            } else {
                list.last()
            }
        }
        Log.i(TAG, "playPrevious: switching to ${prevSong.title}")
        playSong(prevSong, context)
    }

    fun togglePlay(context: Context) {
        ensurePlayerListener(context)
        val player = Media3Factory.getSharedExoPlayer(context)
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.currentMediaItem == null && _currentSongFlow.value != null) {
                val savedPos = getSavedPositionMs(context)
                playSong(_currentSongFlow.value!!, context, startPositionMs = savedPos)
            } else {
                player.play()
            }
        }
    }
}
