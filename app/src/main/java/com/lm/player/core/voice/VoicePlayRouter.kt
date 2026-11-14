package com.lm.player.core.voice

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.PlaybackService
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.widget.WidgetDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音点歌（TV / 车机）：把「播放 周杰伦」「我想听晴天」这类请求变成一次搜索并直接播放。
 *
 * 本应用不做语音识别，只提供**被语音助手调用**的入口，三个入口最后都汇到 [request]：
 * 1. 系统媒体会话的「按搜索词播放」（MediaController.playFromSearch，见 PlaybackService.onSetMediaItems）——
 *    谷歌助手、车机上的语音中转工具（如比亚迪的「音乐助手」）控制通用播放器走的标准方式；
 * 2. 意图 `android.media.action.MEDIA_PLAY_FROM_SEARCH`（MainActivity）——「打开某应用播放某某」；
 * 3. 自定义广播 [ACTION_PLAY_SEARCH]（[VoiceCommandReceiver]）—— 给支持「自定义指令 → 发广播」的工具和 adb 测试用。
 *
 * 播放规则（用户 2026-11-12 确认：本地优先）：
 * - 先在本地曲库（有本地文件的歌）里按歌名 / 歌手 / 专辑找，找到就把这些歌排成队列播放；
 * - 本地没有 → 在线搜索（柠檬服务器 / 本地音源，与桌面搜索小部件同一条链路），播第一首，结果作为队列；
 * - 没说具体内容（只说「播放音乐」）→ 接着播上次的歌；从没播过则播本地曲库。
 */
object VoicePlayRouter {
    private const val TAG = "VoicePlay"

    /** 自定义广播 / 意图动作：extra 见 [EXTRA_KEYWORD] 等 */
    const val ACTION_PLAY_SEARCH = "com.lm.player.tv.action.PLAY_SEARCH"
    const val EXTRA_KEYWORD = "keyword"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ALBUM = "album"

    private const val MAX_QUEUE = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** 口语前缀 / 后缀：语音助手没有先做语义解析、把整句话原样传过来时，去掉这些才搜得到 */
    private val LEADING = Regex("^(请|帮我|给我|我要|我想)?(播放一首|播放|来一首|来首|放一首|放首|听一下|听一首|想听|要听|听|搜索|点播|点一首)")
    private val TRAILING = Regex("(的歌曲|的音乐|的歌|这首歌|歌曲)$")
    private val GENERIC = setOf("音乐", "歌", "歌曲", "music", "一首歌", "点歌", "随便", "随便听听")

    /** 从意图 / 媒体会话附带的参数里取出搜索词，并发起点歌 */
    fun requestFromExtras(context: Context, query: String?, extras: Bundle?, source: String) {
        val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE) ?: extras?.getString(EXTRA_TITLE)
        val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST) ?: extras?.getString(EXTRA_ARTIST)
        val album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM) ?: extras?.getString(EXTRA_ALBUM)
        val raw = query?.takeIf { it.isNotBlank() }
            ?: extras?.getString(SearchManager.QUERY)?.takeIf { it.isNotBlank() }
            ?: extras?.getString(EXTRA_KEYWORD)?.takeIf { it.isNotBlank() }
        // 助手给了结构化字段（歌名 / 歌手 / 专辑）时优先用它们，比整句话准确
        val structured = listOfNotNull(title, artist, album).map { it.trim() }.filter { it.isNotEmpty() }
        val effective = if (structured.isNotEmpty()) structured.joinToString(" ") else raw.orEmpty()
        request(context, effective, source)
    }

    /** 意图是否是一次点歌请求（供 MainActivity 判断） */
    fun isPlaySearchIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH || action == ACTION_PLAY_SEARCH
    }

    /** 处理一次点歌意图；处理过返回 true，并把动作清掉避免界面重建时重复执行 */
    fun consumeIntent(context: Context, intent: Intent?): Boolean {
        if (!isPlaySearchIntent(intent)) return false
        intent!!
        requestFromExtras(context, intent.getStringExtra(SearchManager.QUERY), intent.extras, "intent")
        intent.action = Intent.ACTION_MAIN
        return true
    }

    fun request(context: Context, rawQuery: String, source: String) {
        val appCtx = context.applicationContext
        val query = normalize(rawQuery)
        Log.i(TAG, "voice play request: source=$source raw=\"$rawQuery\" query=\"$query\"")
        job?.cancel()
        job = scope.launch {
            try {
                if (query.isEmpty()) resumeOrPlayLibrary(appCtx) else searchAndPlay(appCtx, query)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "voice play failed", e)
                toast(appCtx, "语音点歌失败：${e.message ?: "未知错误"}")
            }
        }
    }

    internal fun normalize(raw: String): String {
        var q = raw.trim().trim('。', '.', '，', ',', '！', '!', '？', '?', '"', '“', '”', '《', '》')
        q = LEADING.replace(q, "").trim()
        q = TRAILING.replace(q, "").trim()
        q = q.trim('《', '》', '"', '“', '”')
        return if (q.lowercase() in GENERIC) "" else q
    }

    private suspend fun searchAndPlay(context: Context, query: String) {
        val local = withContext(Dispatchers.IO) { searchLocal(context, query) }
        if (local.isNotEmpty()) {
            Log.i(TAG, "local hit: ${local.size} songs, first=${local.first().title}")
            toast(context, "正在播放：${local.first().title}" + if (local.size > 1) "（本地共 ${local.size} 首）" else "")
            WidgetDataRepository.playList(context, local, 0)
            return
        }
        toast(context, "本地没有「$query」，正在在线搜索…")
        val result = WidgetDataRepository.searchSongs(context, query)
        val songs = result.songs.take(MAX_QUEUE)
        when {
            songs.isNotEmpty() -> {
                Log.i(TAG, "online hit: ${songs.size} songs, first=${songs.first().title}")
                toast(context, "正在播放：${songs.first().title} - ${songs.first().artist}")
                WidgetDataRepository.playList(context, songs, 0)
            }
            !result.onlineAvailable -> toast(context, "本地曲库没有「$query」，也没有可用的在线来源")
            result.onlineFailed -> toast(context, "搜索「$query」失败，请检查网络")
            else -> toast(context, "没有找到「$query」")
        }
    }

    /**
     * 本地曲库搜索：只看有本地文件的歌。
     * 搜索词按空格与「的」拆开（「周杰伦的晴天」→ 周杰伦 + 晴天），每一段都要在「歌名 歌手 专辑」里出现；
     * 排序：歌名完全一致 > 歌名包含 > 歌手匹配 > 专辑匹配。
     */
    private suspend fun searchLocal(context: Context, query: String): List<UnifiedSong> {
        val all = runCatching { ZdsDatabase.getInstance(context).songDao().getAllSongsList() }.getOrDefault(emptyList())
        val locals = all.filter { it.hasLocalFile() }
        if (locals.isEmpty()) return emptyList()

        val whole = query.lowercase()
        val tokens = query.split(Regex("[\\s的]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(whole) }

        fun score(s: SongEntity): Int {
            val title = s.title.lowercase()
            val artist = s.artist.lowercase()
            val album = s.album.lowercase()
            var sc = 0
            if (title == whole) sc += 16
            if (title.contains(whole)) sc += 4
            if (tokens.any { title.contains(it) }) sc += 4
            if (tokens.any { artist.contains(it) }) sc += 2
            if (tokens.any { album.contains(it) }) sc += 1
            return sc
        }

        // 先按整句匹配（歌名里本来就带「的」的歌，如「后来的我们」）；没有再按拆开的每一段都要出现来匹配
        var matched = locals.filter { s -> "${s.title} ${s.artist} ${s.album}".lowercase().contains(whole) }
        if (matched.isEmpty()) {
            matched = locals.filter { s ->
                val hay = "${s.title} ${s.artist} ${s.album}".lowercase()
                tokens.all { hay.contains(it) }
            }
        }
        if (matched.isEmpty()) return emptyList()

        val ranked = matched.sortedByDescending { score(it) }
            .filter { it.localFileStillThere() }
        if (ranked.isEmpty()) return emptyList()

        val queue = ArrayList<SongEntity>(ranked)
        // 点的是具体某一首歌（命中很少）：把同一歌手的其它本地歌接在后面，播完不至于马上停
        val top = ranked.first()
        if (ranked.size < 5 && top.title.lowercase().let { t -> tokens.any { t.contains(it) } } && top.artist.isNotBlank()) {
            val known = ranked.mapTo(HashSet()) { it.id }
            locals.asSequence()
                .filter { it.id !in known && it.artist == top.artist }
                .take(50)
                .forEach { queue.add(it) }
        }
        return queue.distinctBy { it.localFilePath?.trim()?.lowercase() ?: it.id }
            .take(MAX_QUEUE)
            .map { it.toSong() }
    }

    /** 没说具体内容：接着播上次的歌；从没播过则播本地曲库 */
    private suspend fun resumeOrPlayLibrary(context: Context) {
        PlaybackQueueManager.initFromPrefs(context)
        PlaybackService.startPlaybackService(context)
        if (PlaybackQueueManager.currentSongFlow.value != null) {
            val player = Media3Factory.getSharedExoPlayer(context)
            if (!player.playWhenReady) PlaybackQueueManager.togglePlay(context)
            return
        }
        val locals = withContext(Dispatchers.IO) {
            runCatching { ZdsDatabase.getInstance(context).songDao().getAllSongsList() }.getOrDefault(emptyList())
                .filter { it.hasLocalFile() }
                .take(MAX_QUEUE)
                .map { it.toSong() }
        }
        if (locals.isEmpty()) {
            toast(context, "本地曲库还没有歌曲")
            return
        }
        WidgetDataRepository.playList(context, locals, 0)
    }

    private fun SongEntity.hasLocalFile(): Boolean =
        !localFilePath.isNullOrBlank() || downloadStatus == DownloadStatus.DOWNLOADED

    private fun SongEntity.localFileStillThere(): Boolean {
        val path = localFilePath?.trim().orEmpty()
        if (path.isEmpty()) return false
        if (path.startsWith("content://")) return true
        return runCatching { File(path).let { it.isFile && it.length() > 0L } }.getOrDefault(false)
    }

    private fun SongEntity.toSong(): UnifiedSong = UnifiedSong(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        streamUrl = streamUrl,
        serverId = serverId,
        localFilePath = localFilePath,
        downloadStatus = downloadStatus,
        bitRate = bitRate,
        format = format,
        isFavorite = isFavorite,
        relativeFolderPath = relativeFolderPath
    )

    private fun toast(context: Context, text: String) {
        runCatching { com.lm.player.core.designsystem.component.AppToast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * 自定义点歌广播入口：
 * `adb shell am broadcast -n com.lm.player.tv/com.lm.player.core.voice.VoiceCommandReceiver
 *      -a com.lm.player.tv.action.PLAY_SEARCH --es keyword "周杰伦"`
 * 也接受 `query` / `artist` / `title` / `album` 这些 extra。
 */
class VoiceCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoicePlayRouter.ACTION_PLAY_SEARCH) return
        // 界面可能不在：先把播放服务拉成前台，保证搜索（在线搜索可能要几秒）期间进程不被回收
        PlaybackService.startPlaybackService(context)
        VoicePlayRouter.requestFromExtras(
            context,
            intent.getStringExtra(VoicePlayRouter.EXTRA_KEYWORD) ?: intent.getStringExtra(SearchManager.QUERY),
            intent.extras,
            "broadcast"
        )
    }
}
