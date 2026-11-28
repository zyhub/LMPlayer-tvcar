package com.lm.player.core.voice

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.PlaybackService
import com.lm.player.core.media.SkipLatencyStats
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.widget.ChartTab
import com.lm.player.core.widget.PlaylistTab
import com.lm.player.core.widget.WidgetDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** 中转页打开主界面时带上：主界面冷启动不再做启动续播（否则会把刚点的歌顶掉） */
    const val EXTRA_FROM_VOICE = "lm_from_voice"

    private const val MAX_QUEUE = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** 口语前缀 / 后缀：语音助手没有先做语义解析、把整句话原样传过来时，去掉这些才搜得到 */
    private val LEADING = Regex("^(请|帮我|给我|我要|我想)?(播放一首|播放|来一首|来首|放一首|放首|听一下|听一首|想听|要听|听|搜索|点播|点一首)")
    private val TRAILING = Regex("(的歌曲|的音乐|的歌|这首歌|歌曲)$")
    private val GENERIC = setOf("音乐", "歌", "歌曲", "music", "一首歌", "点歌", "随便", "随便听听")

    /** 从意图 / 媒体会话附带的参数里取出搜索词，并发起点歌 */
    fun requestFromExtras(context: Context, query: String?, extras: Bundle?, source: String, caller: String? = null) {
        val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE) ?: extras?.getString(EXTRA_TITLE)
        val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST) ?: extras?.getString(EXTRA_ARTIST)
        val album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM) ?: extras?.getString(EXTRA_ALBUM)
        val raw = query?.takeIf { it.isNotBlank() }
            ?: extras?.getString(SearchManager.QUERY)?.takeIf { it.isNotBlank() }
            ?: extras?.getString(EXTRA_KEYWORD)?.takeIf { it.isNotBlank() }
        // 2026-11-23：标准点歌意图里的「歌单」字段（助手说「播放某某歌单」时可能只给这个）
        val playlist = runCatching { extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }
        // 助手给了结构化字段（歌名 / 歌手 / 专辑）时优先用它们，比整句话准确。
        // 2026-11-24：音乐助手常把歌名、歌手、搜索词都填成同一句话（「我问问歌单」×3），
        // 以前拼成「我问问歌单 我问问歌单」去搜，在线几乎搜不到 —— 相同的只取一份，被另一项包含的也去掉
        val fields = listOfNotNull(title, artist, album).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val structured = fields.filter { f -> fields.none { other -> other != f && other.contains(f) } }
        val effective = when {
            structured.isNotEmpty() -> structured.joinToString(" ")
            !raw.isNullOrBlank() -> raw
            else -> playlist.orEmpty()
        }
        // 只给了歌手（focus=artist，或没有歌名）：同一句话另有更具体的一条时让给那条
        val focus = runCatching { extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS) }.getOrNull()
        val artistOnly = focus == MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE ||
            (title.isNullOrBlank() && !artist.isNullOrBlank())
        request(
            context, effective, source, caller,
            detail = summarizeExtras(extras),
            playlistHint = playlist.takeIf { structured.isEmpty() && raw.isNullOrBlank() },
            artistOnly = artistOnly
        )
    }

    /** 意图 / 会话带来的附加字段，写进诊断记录（用来确认语音助手到底传了哪些字段） */
    internal fun summarizeExtras(extras: Bundle?): String? {
        if (extras == null || extras.isEmpty) return null
        val parts = extras.keySet().sorted().mapNotNull { key ->
            val v = runCatching { @Suppress("DEPRECATION") extras.get(key) }.getOrNull() ?: return@mapNotNull null
            val text = v.toString().replace('\n', ' ').take(40)
            "${key.removePrefix("android.intent.extra.")}=$text"
        }
        return parts.joinToString(", ").take(200).takeIf { it.isNotEmpty() }
    }

    /** 意图是否是一次点歌请求（供 MainActivity 判断） */
    fun isPlaySearchIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH || action == ACTION_PLAY_SEARCH
    }

    /** 处理一次点歌意图；处理过返回 true，并把动作清掉避免界面重建时重复执行 */
    fun consumeIntent(context: Context, intent: Intent?, caller: String? = null): Boolean {
        if (!isPlaySearchIntent(intent)) return false
        intent!!
        requestFromExtras(context, intent.getStringExtra(SearchManager.QUERY), intent.extras, "intent_main", caller)
        intent.action = Intent.ACTION_MAIN
        return true
    }

    fun request(
        context: Context,
        rawQuery: String,
        source: String,
        caller: String? = null,
        detail: String? = null,
        playlistHint: String? = null,
        artistOnly: Boolean = false
    ) {
        val appCtx = context.applicationContext
        VoicePrefs.ensureLoaded(appCtx)
        // 只给了歌单名：先看是不是「我喜欢」这类分类，不是就按歌单名找
        val command = if (playlistHint != null) {
            parse(playlistHint).let { if (it is Command.Search || it is Command.Resume) Command.Playlist(playlistHint) else it }
        } else parse(rawQuery)
        val label = when (source) {
            "media_session" -> "点歌·媒体会话"
            "intent" -> "点歌·意图（后台中转）"
            "intent_main" -> "点歌·意图（主界面）"
            "broadcast" -> "点歌·广播"
            else -> source
        }
        dispatch(appCtx, command, label, caller, "「$rawQuery」" + (detail?.let { "［$it］" } ?: ""), artistOnly)
    }

    // ---------------- 同一句话的多条指令（2026-11-24） ----------------
    // 车测日志：音乐助手常把一句话发成两条（整句 +拆出来的词，如「在线孙燕姿歌单」+「孙燕姿」），
    // 以前后到的顶掉先到的，两条都实际执行（搜两次、切两次歌），而且常常是错的那条留下。现在：
    // ① 收到后先等 [SETTLE_MS]，看有没有同一句话的另一条，只执行更准的那条；
    // ② 已经开始执行后 [COMPANION_MS] 内（从第一条到达算）再到的，比正在执行的更准才顶掉，否则忽略；
    // ③ 同一指令 [DUP_MS] 内重复收到只执行一次。
    // 更准 = 歌单 / 分类 / 播放模式 ＞ 点具体歌名 ＞ 只给歌手名 ＞ 什么都没说（续播）。
    private const val SETTLE_MS = 500L
    private const val COMPANION_MS = 2_000L
    private const val DUP_MS = 3_000L
    /** 在线搜歌 / 找歌单 / 取分类的总时限；到时就结束这条指令并提示，不让它一直挂着 */
    private const val ONLINE_TIMEOUT_MS = 12_000L
    /** 等「出声」最多等多久（语音日志里记从收到到出声的耗时） */
    private const val SOUND_WAIT_MS = 15_000L

    private class Entry(
        val command: Command,
        val label: String,
        val caller: String?,
        val diagId: Long,
        val priority: Int,
        val key: String,
        val receivedAt: Long
    )

    private val lock = Any()
    private var pending: Entry? = null
    private var pendingTimer: Job? = null
    private var executing: Entry? = null
    private var groupStartedAt = 0L

    private fun priorityOf(c: Command, artistOnly: Boolean): Int = when (c) {
        is Command.Playlist, is Command.Category, is Command.Mode -> 3
        is Command.Search -> if (artistOnly) 1 else 2
        Command.Resume -> 0
    }

    private fun keyOf(c: Command): String = when (c) {
        is Command.Search -> "search:" + c.query.lowercase().replace(Regex("\\s+"), " ")
        else -> c.toString()
    }

    /**
     * 收到一条已经解析好的指令。
     * @param label 诊断记录里的来源；[content] 诊断记录里的内容
     */
    internal fun dispatch(
        context: Context,
        command: Command,
        label: String,
        caller: String?,
        content: String,
        artistOnly: Boolean = false
    ) {
        val appCtx = context.applicationContext
        VoicePrefs.ensureLoaded(appCtx)
        Log.i(TAG, "voice command: label=$label caller=$caller content=$content command=$command")
        val now = SystemClock.elapsedRealtime()
        noteVoiceArrival(now, caller)
        val diagId = VoiceDiagnostics.record(appCtx, label, caller, content)
        val entry = Entry(command, label, caller, diagId, priorityOf(command, artistOnly), keyOf(command), now)
        synchronized(lock) {
            val p = pending
            val ex = executing
            // ③ 同一指令重复
            if (p?.key == entry.key || (ex != null && ex.key == entry.key && now - ex.receivedAt < DUP_MS)) {
                VoiceDiagnostics.finish(appCtx, diagId, "同一指令 3 秒内重复，已忽略")
                return
            }
            // ① 还在等同一句话的其它条
            if (p != null) {
                if (entry.priority > p.priority) {
                    VoiceDiagnostics.finish(appCtx, p.diagId, "同一句话的另一条更准，已改用那条")
                    pending = entry
                } else {
                    VoiceDiagnostics.finish(appCtx, diagId, "同一句话的另一条，已忽略")
                }
                return
            }
            // ② 同一句话的另一条已经在执行
            if (ex != null && now - groupStartedAt < COMPANION_MS) {
                if (entry.priority > ex.priority) {
                    executeNow(appCtx, entry)
                } else {
                    VoiceDiagnostics.finish(appCtx, diagId, "同一句话的另一条，已忽略")
                }
                return
            }
            // 新的一句话：稍等一下再执行
            groupStartedAt = now
            pending = entry
            pendingTimer?.cancel()
            pendingTimer = scope.launch {
                delay(SETTLE_MS)
                val e = synchronized(lock) { pending.also { pending = null } } ?: return@launch
                executeNow(appCtx, e)
            }
        }
    }

    private fun executeNow(appCtx: Context, e: Entry) {
        synchronized(lock) { executing = e }
        job?.cancel()
        job = scope.launch {
            val beforeId = PlaybackQueueManager.currentSongFlow.value?.id
            val wasPlaying = PlaybackQueueManager.isPlayingFlow.value
            SkipLatencyStats.noteSource("语音点歌")
            val result = try {
                execute(appCtx, e.command)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                VoiceDiagnostics.finish(appCtx, e.diagId, "被下一条指令取代")
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "voice play failed", t)
                toast(appCtx, "语音点歌失败：${t.message ?: "未知错误"}")
                "失败：${t.message ?: t.javaClass.simpleName}"
            }
            VoiceDiagnostics.finish(appCtx, e.diagId, result)
            // 出声耗时 + 资源快照另起协程等：不占着 job，下一条指令来了不必取消它
            val afterId = PlaybackQueueManager.currentSongFlow.value?.id
            val expectSound = afterId != null && !(afterId == beforeId && wasPlaying)
            scope.launch { reportPerf(appCtx, e, if (expectSound) afterId else null) }
        }
    }

    /** 语音日志补上：从收到到出声用了多久、当时的内存 / 线程 / 系统剩余内存 */
    private suspend fun reportPerf(appCtx: Context, e: Entry, targetId: String?) {
        val sound = if (targetId == null) null else {
            val deadline = e.receivedAt + SOUND_WAIT_MS
            var heard = -1L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (SkipLatencyStats.lastSoundSongId == targetId && SkipLatencyStats.lastSoundAt >= e.receivedAt) {
                    heard = SkipLatencyStats.lastSoundAt - e.receivedAt
                    break
                }
                delay(150)
            }
            if (heard >= 0L) String.format(java.util.Locale.US, "出声 %.1f 秒（从收到算）", heard / 1000.0)
            else "${SOUND_WAIT_MS / 1000} 秒内没出声"
        }
        val snapshot = withContext(Dispatchers.IO) { VoiceDiagnostics.resourceSnapshot(appCtx) }
        VoiceDiagnostics.finish(appCtx, e.diagId, listOfNotNull(sound, snapshot).joinToString("；"))
    }

    // ---------------- 语音点歌后音乐助手打开界面（2026-11-24） ----------------
    // 音乐助手转完点歌后还会用 MAIN 把本应用切到前台（一次两次），即使设置「语音点歌时打开界面」关着。
    // 点歌前界面本来就在后台时，主界面收到这种打开就马上退回后台，不打断导航。

    /** 最近一次收到语音指令（elapsedRealtime）、发起方，以及当时界面是否在前台 */
    @Volatile var lastVoiceAt = 0L
        private set
    @Volatile private var lastVoiceCaller: String? = null
    @Volatile private var uiForegroundAtVoice = false

    @Volatile private var uiResumed = false
    @Volatile private var lastUiPausedAt = 0L
    @Volatile private var ignoreNextPause = false

    /** 音乐助手随后的打开在这段时间内到达才算「点歌顺手打开」 */
    private const val FOLLOW_UP_MS = 5_000L
    /**
     * 界面在收到指令前多久之内还在前台，就算「本来就在前台」：
     * 说话时小迪的界面、中转页都可能先把主界面暂停几秒。
     */
    private const val UI_RECENT_MS = 10_000L

    private fun noteVoiceArrival(now: Long, caller: String?) {
        // 同一句话的第二条不改「当时界面在不在前台」（第一条之后界面可能已被音乐助手切出来）
        if (now - lastVoiceAt > COMPANION_MS) {
            uiForegroundAtVoice = uiResumed || (lastUiPausedAt > 0L && now - lastUiPausedAt < UI_RECENT_MS)
        }
        lastVoiceAt = now
        if (!caller.isNullOrBlank()) lastVoiceCaller = caller
    }

    /** 主界面 onResume / onPause 调用 */
    fun onUiResumed() { uiResumed = true }

    fun onUiPaused() {
        uiResumed = false
        if (ignoreNextPause) {
            // 自己退回后台引起的暂停不算「刚才还在前台」
            ignoreNextPause = false
            return
        }
        lastUiPausedAt = SystemClock.elapsedRealtime()
    }

    /** 这次打开主界面是不是发语音指令的应用点完歌顺手打开的（这种打开不做启动续播，免得顶掉刚点的歌） */
    fun isVoiceFollowUpLaunch(caller: String?): Boolean =
        !caller.isNullOrBlank() && caller == lastVoiceCaller &&
            SystemClock.elapsedRealtime() - lastVoiceAt < FOLLOW_UP_MS

    /** 是否应把这次打开退回后台：顺手打开 + 设置为点歌不打开界面 + 点歌前界面本来就在后台 */
    fun shouldReturnToBackground(context: Context, caller: String?): Boolean {
        if (!isVoiceFollowUpLaunch(caller)) return false
        VoicePrefs.ensureLoaded(context)
        if (VoicePrefs.showUi || uiForegroundAtVoice) return false
        ignoreNextPause = true
        return true
    }

    /** 声音被别的应用拿走时记一条（只记语音指令后 30 秒内的，用来看是不是小迪拉起了别的音乐应用） */
    fun noteAudioFocusLoss(context: Context, permanent: Boolean) {
        if (lastVoiceAt == 0L || SystemClock.elapsedRealtime() - lastVoiceAt > 30_000L) return
        VoiceDiagnostics.record(
            context.applicationContext, "声音被抢", null,
            if (permanent) "别的应用开始放音，本应用已暂停（可能是小迪拉起了默认音乐）"
            else "被别的应用临时打断"
        )
    }

    // ---------------- 指令解析（2026-11-23：分类点歌、播放模式） ----------------

    internal enum class ModeKind(val label: String) { SHUFFLE("随机播放"), REPEAT_ONE("单曲循环"), SEQUENTIAL("顺序播放") }

    internal enum class CategoryKind(val label: String) {
        FAVORITES("我喜欢的音乐"), RECENT("最近播放"), DAILY("每日推荐"), LOCAL("本机歌曲"),
        HOT_CHART("热歌榜"), NEW_CHART("新歌榜"), SURGE_CHART("飙升榜")
    }

    internal sealed class Command {
        object Resume : Command() { override fun toString() = "Resume" }
        data class Mode(val kind: ModeKind) : Command()
        data class Category(val kind: CategoryKind) : Command()
        /** @param online 说了「在线 / 网上…歌单」：不找我的歌单，直接在线搜 */
        data class Playlist(val name: String, val online: Boolean = false) : Command()
        data class Search(val query: String) : Command()
    }

    private const val PUNCT = "。.，,！!？?\"“”"
    private val SHUFFLE_RE = Regex("^(随机播放|随机模式|打开随机(播放)?|随机(的)?(音乐|歌曲|歌)?|随便(听听|听|放放|放|来点|放点)?(什么|点)?(音乐|歌曲|歌)?)$")
    private val REPEAT_ONE_RE = Regex("^(单曲循环|单曲重复|单曲播放|(循环|重复)(播放)?(这|本|当前)首(歌)?)$")
    private val SEQUENTIAL_RE = Regex("^(顺序播放|列表循环|列表播放|循环播放|(关闭|取消)(随机(播放)?|单曲循环))$")
    private val FAVORITES_RE = Regex("^(我)?(的)?(最)?(喜欢|收藏|红心|心动)(的)?(音乐|歌曲|歌|歌单|列表)?$")
    // 「最近」「今天」本身也是歌名（李圣杰《最近》、刘德华《今天》），单说这两个词仍按歌名搜
    private val RECENT_RE = Regex("^(最近(常|在)?(播放|听|听过|播过|放过)(的)?(音乐|歌曲|歌|列表)?|最近的(音乐|歌曲|歌)|播放历史|播放记录|历史记录)$")
    private val DAILY_RE = Regex("^((每日|今日)(推荐)?(歌单|音乐|歌曲|歌)?|今天的?推荐(歌单|音乐|歌曲|歌)?|日推|推荐(的)?(音乐|歌曲|歌|歌单)?)$")
    private val LOCAL_RE = Regex("^(本地|本机|下载|已下载|离线)(的)?(音乐|歌曲|歌|歌单)?$")
    private val HOT_RE = Regex("^(热歌榜|热门(歌曲)?榜?|排行榜|热歌)$")
    private val NEW_RE = Regex("^(新歌榜|新歌速递|新歌)$")
    private val SURGE_RE = Regex("^(飙升榜|飙升)$")
    private val BARE_PLAYLIST = setOf("歌单", "我的歌单", "我的歌单列表", "自建歌单", "我创建的歌单")
    private val PLAYLIST_PREFIX_RE = Regex("^(?:我的)?歌单(.{1,20})$")
    private val PLAYLIST_SUFFIX_RE = Regex("^(.{1,20}?)(这个)?歌单$")
    // 2026-11-24：「在线孙燕姿歌单」以前把「在线」也当歌单名去搜，搜不到
    private val ONLINE_LEAD_RE = Regex("^(在线|网上|网络上?|线上)(的)?")
    private val ONLINE_TAIL_RE = Regex("(的)?(在线|网上|网络上?|线上)$")

    /** 把一句话变成要做的事：播放模式 → 分类 → 歌单 → 搜索；什么都没说就续播 */
    internal fun parse(raw: String): Command {
        val q0 = raw.trim().trim(*PUNCT.toCharArray()).trim()
        val q1 = LEADING.replace(q0, "").trim().trim('《', '》', '"', '“', '”')
        for (q in listOf(q0, q1)) {
            when {
                SHUFFLE_RE.matches(q) -> return Command.Mode(ModeKind.SHUFFLE)
                REPEAT_ONE_RE.matches(q) -> return Command.Mode(ModeKind.REPEAT_ONE)
                SEQUENTIAL_RE.matches(q) -> return Command.Mode(ModeKind.SEQUENTIAL)
            }
        }
        for (q in listOf(q0, q1)) {
            val kind = when {
                FAVORITES_RE.matches(q) -> CategoryKind.FAVORITES
                RECENT_RE.matches(q) -> CategoryKind.RECENT
                DAILY_RE.matches(q) -> CategoryKind.DAILY
                LOCAL_RE.matches(q) -> CategoryKind.LOCAL
                HOT_RE.matches(q) -> CategoryKind.HOT_CHART
                NEW_RE.matches(q) -> CategoryKind.NEW_CHART
                SURGE_RE.matches(q) -> CategoryKind.SURGE_CHART
                else -> null
            }
            if (kind != null) return Command.Category(kind)
        }
        // 歌单前面的「在线 / 网上」：只在线搜，并且不算进歌单名
        val onlineLead = ONLINE_LEAD_RE.find(q1)
        val q2 = if (onlineLead != null) q1.substring(onlineLead.range.last + 1).trim() else q1
        // 只说「歌单」「我的歌单」：放我的第一个歌单（「在线歌单」放推荐歌单）
        if (q2 in BARE_PLAYLIST) return Command.Playlist("", online = onlineLead != null)
        val listRaw = (PLAYLIST_PREFIX_RE.matchEntire(q2)?.groupValues?.get(1)
            ?: PLAYLIST_SUFFIX_RE.matchEntire(q2)?.groupValues?.get(1))
            ?.removePrefix("我的")?.removeSuffix("的")?.trim()
        if (listRaw != null) {
            // 「我的在线歌单」「孙燕姿在线歌单」这类在名字里的也认
            val onlineInName = ONLINE_LEAD_RE.containsMatchIn(listRaw) || ONLINE_TAIL_RE.containsMatchIn(listRaw)
            val listName = ONLINE_TAIL_RE.replace(ONLINE_LEAD_RE.replace(listRaw, ""), "")
                .removePrefix("我的").removeSuffix("的").trim()
            val online = onlineLead != null || onlineInName
            if (listName.isNotEmpty() || online) return Command.Playlist(listName, online)
        }
        val query = normalize(raw)
        return if (query.isEmpty()) Command.Resume else Command.Search(query)
    }

    /** 执行指令，返回给诊断记录看的处理结果 */
    private suspend fun execute(context: Context, command: Command): String = when (command) {
        Command.Resume -> resumeOrPlayLibrary(context)
        is Command.Mode -> applyMode(context, command.kind)
        is Command.Category -> playCategory(context, command.kind)
        is Command.Playlist -> playPlaylist(context, command.name, command.online)
        is Command.Search -> searchAndPlay(context, command.query)
    }

    /** 播放模式与界面里的三态一致；没在播时顺便开始播放 */
    private suspend fun applyMode(context: Context, kind: ModeKind): String {
        PlaybackQueueManager.initFromPrefs(context)
        when (kind) {
            ModeKind.SHUFFLE -> { PlaybackQueueManager.setShuffle(true); PlaybackQueueManager.setRepeat(false) }
            ModeKind.REPEAT_ONE -> { PlaybackQueueManager.setShuffle(false); PlaybackQueueManager.setRepeat(true) }
            ModeKind.SEQUENTIAL -> { PlaybackQueueManager.setShuffle(false); PlaybackQueueManager.setRepeat(false) }
        }
        val player = Media3Factory.getSharedExoPlayer(context)
        if (player.playWhenReady) {
            toast(context, "已切换为${kind.label}")
            return "已切换为${kind.label}"
        }
        val started = resumeOrPlayLibrary(context, shuffle = kind == ModeKind.SHUFFLE)
        return "已切换为${kind.label}；$started"
    }

    private suspend fun playCategory(context: Context, kind: CategoryKind): String {
        // 推荐 / 榜单 / 服务器收藏要联网：限时，卡住就结束这条指令
        val loaded = withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
            runCatching { loadCategory(context, kind) }
                .onFailure { Log.w(TAG, "load ${kind.label} failed", it) }
                .getOrDefault(emptyList())
        }
        if (loaded == null) {
            toast(context, "「${kind.label}」加载超时，请检查网络")
            return "「${kind.label}」加载超时（${ONLINE_TIMEOUT_MS / 1000} 秒）"
        }
        val songs = loaded
            .distinctBy { it.id }
            .take(MAX_QUEUE)
        if (songs.isEmpty()) {
            toast(context, "「${kind.label}」里还没有歌")
            return "「${kind.label}」为空或没取到"
        }
        val start = if (PlaybackQueueManager.isShuffleFlow.value) songs.indices.random() else 0
        toast(context, "正在播放：${kind.label}（${songs.size} 首）")
        WidgetDataRepository.playList(context, songs, start)
        return "播放${kind.label}，共 ${songs.size} 首，第一首 ${songs[start].title}"
    }

    private suspend fun loadCategory(context: Context, kind: CategoryKind): List<UnifiedSong> = when (kind) {
        CategoryKind.FAVORITES ->
            WidgetDataRepository.loadPlaylistSongs(context, PlaylistTab.MY, WidgetDataRepository.FAVORITES_ID)
        CategoryKind.RECENT -> withContext(Dispatchers.IO) { PlaybackQueueManager.getRecentPlayedSongs(context) }
        CategoryKind.DAILY -> {
            // 柠檬服务器的「每日推荐」（不刷新、不占次数）；没有服务器或取不到时用推荐歌单的第一张
            val daily = runCatching {
                com.lm.player.core.network.LemonMusicProtocol.fetchDailyMix(context, forceRefresh = false).getOrNull()?.songs
            }.getOrNull().orEmpty()
            daily.ifEmpty {
                val first = WidgetDataRepository.loadPlaylists(context, PlaylistTab.RECOMMEND)?.firstOrNull()
                if (first == null) emptyList()
                else WidgetDataRepository.loadPlaylistSongs(context, PlaylistTab.RECOMMEND, first.id)
            }
        }
        CategoryKind.LOCAL -> withContext(Dispatchers.IO) { localLibrary(context) }
        CategoryKind.HOT_CHART -> WidgetDataRepository.loadChart(context, ChartTab.HOT)?.songs.orEmpty()
        CategoryKind.NEW_CHART -> WidgetDataRepository.loadChart(context, ChartTab.NEW)?.songs.orEmpty()
        CategoryKind.SURGE_CHART -> WidgetDataRepository.loadChart(context, ChartTab.SURGE)?.songs.orEmpty()
    }

    /** 歌单名比较前统一：去空格、「的」「歌单」「我的」，忽略大小写（「开车 歌单」「我的开车歌单」都能对上「开车」） */
    private fun playlistKey(s: String): String =
        s.lowercase().replace(Regex("[\\s的·\\-_]"), "").removePrefix("我").removeSuffix("歌单")

    /**
     * 语音播放歌单（2026-11-24）：先找「我的歌单」（含同步下来的服务器歌单），名字一样优先、其次互相包含；
     * 找不到再在线搜歌单（柠檬服务器 / 本地音源，与发现页同一条链路），放第一个有歌的。
     * [name] 为空（只说了「歌单」「我的歌单」）时放我的第一个歌单。
     * [online]（说了「在线 / 网上…」，2026-11-24）：不找我的歌单，直接在线搜；名字为空时放推荐歌单第一张。
     */
    private suspend fun playPlaylist(context: Context, name: String, online: Boolean): String {
        val key = playlistKey(name)
        if (!online) {
            val mine = withContext(Dispatchers.IO) {
                runCatching { ZdsDatabase.getInstance(context).playlistDao().getAllPlaylists() }.getOrDefault(emptyList())
            }.filter {
                !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") &&
                    it.id != "lemon_favorites" && it.name != "我的收藏" && it.name.isNotBlank()
            }
            val pick = if (key.isEmpty()) mine.firstOrNull() else {
                mine.firstOrNull { playlistKey(it.name) == key }
                    ?: mine.firstOrNull { playlistKey(it.name).contains(key) }
                    ?: mine.firstOrNull { val k = playlistKey(it.name); k.length >= 2 && key.contains(k) }
            }
            if (pick != null) {
                // 服务器歌单本地没有曲目时要联网取：限时
                val songs = withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
                    WidgetDataRepository.loadPlaylistSongs(context, PlaylistTab.MY, pick.id)
                }.orEmpty().take(MAX_QUEUE)
                if (songs.isNotEmpty()) return startList(context, songs, "歌单：${pick.name}")
                if (key.isEmpty()) {
                    toast(context, "歌单「${pick.name}」里还没有歌")
                    return "歌单「${pick.name}」为空或没取到"
                }
                // 我的歌单里同名的是空的：接着在线找
            }
            if (key.isEmpty()) {
                toast(context, "还没有自建歌单")
                return "没有自建歌单"
            }
        }
        if (key.isEmpty()) {
            // 只说了「在线歌单」：放推荐歌单第一张
            toast(context, "正在打开推荐歌单…")
            val rec = withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
                val first = WidgetDataRepository.loadPlaylists(context, PlaylistTab.RECOMMEND)?.firstOrNull()
                first?.let { it to WidgetDataRepository.loadPlaylistSongs(context, PlaylistTab.RECOMMEND, it.id) }
            }
            val songs = rec?.second.orEmpty().take(MAX_QUEUE)
            if (rec != null && songs.isNotEmpty()) return startList(context, songs, "推荐歌单：${rec.first.title}")
            toast(context, "没取到推荐歌单，请检查网络")
            return "没取到推荐歌单"
        }
        // 在线找要请求好几次服务器：先告诉用户在找，限时
        toast(context, "正在在线找歌单「$name」…")
        val found = withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
            Box(
                runCatching { searchOnlinePlaylist(context, name) }
                    .onFailure { Log.w(TAG, "online playlist search failed", it) }
                    .getOrNull()
            )
        }
        if (found == null) {
            toast(context, "在线找歌单「$name」超时，请检查网络")
            return "在线找歌单「$name」超时（${ONLINE_TIMEOUT_MS / 1000} 秒）"
        }
        found.value?.let { (playlist, songs) ->
            return startList(context, songs, "在线歌单：${playlist.name}")
        }
        toast(context, "没有找到歌单「$name」")
        return if (online) "在线没有找到歌单「$name」" else "没有找到歌单「$name」（我的歌单和在线都没有）"
    }

    /** withTimeoutOrNull 里区分「超时」（null）和「结果本身是 null」 */
    private class Box<T>(val value: T)

    /** 在线搜歌单，返回第一个能取到歌的（最多试 3 个） */
    private suspend fun searchOnlinePlaylist(
        context: Context,
        name: String
    ): Pair<com.lm.player.core.model.UnifiedPlaylist, List<UnifiedSong>>? {
        val source = WidgetDataRepository.onlineSource(context).key
        val proto = com.lm.player.core.network.LemonMusicProtocol.getActiveOrNew(context)
        var lists = proto?.searchOnlinePlaylists(name, source, 1, 10)?.getOrNull().orEmpty()
        if (lists.isEmpty() && com.lm.player.core.source.catalog.OfflineCatalogService.isAvailable()) {
            lists = com.lm.player.core.source.catalog.OfflineCatalogService.searchPlaylists(name, source, 1, 10)
        }
        val key = playlistKey(name)
        val ordered = lists.sortedByDescending { if (playlistKey(it.name).contains(key)) 1 else 0 }
        for (p in ordered.take(3)) {
            val songs = WidgetDataRepository.loadPlaylistSongs(context, PlaylistTab.RECOMMEND, p.id).take(MAX_QUEUE)
            if (songs.isNotEmpty()) return p to songs
        }
        return null
    }

    /** 把一组歌排成队列开始放（随机模式下从随机一首开始），返回诊断用的结果 */
    private suspend fun startList(context: Context, songs: List<UnifiedSong>, what: String): String {
        val start = if (PlaybackQueueManager.isShuffleFlow.value) songs.indices.random() else 0
        toast(context, "正在播放$what（${songs.size} 首）")
        WidgetDataRepository.playList(context, songs, start)
        return "播放$what，共 ${songs.size} 首，第一首 ${songs[start].title}"
    }

    internal fun normalize(raw: String): String {
        var q = raw.trim().trim('。', '.', '，', ',', '！', '!', '？', '?', '"', '“', '”', '《', '》')
        q = LEADING.replace(q, "").trim()
        q = TRAILING.replace(q, "").trim()
        q = q.trim('《', '》', '"', '“', '”')
        return if (q.lowercase() in GENERIC) "" else q
    }

    private suspend fun searchAndPlay(context: Context, query: String): String {
        val local = withContext(Dispatchers.IO) { searchLocal(context, query) }
        if (local.isNotEmpty()) {
            Log.i(TAG, "local hit: ${local.size} songs, first=${local.first().title}")
            toast(context, "正在播放：${local.first().title}" + if (local.size > 1) "（本地共 ${local.size} 首）" else "")
            WidgetDataRepository.playList(context, local, 0)
            return "搜「$query」：本地命中 ${local.size} 首，播放 ${local.first().title}"
        }
        toast(context, "本地没有「$query」，正在在线搜索…")
        // 本地刚搜过，这里不再整库扫一遍；限时，网络卡住时不让这条指令一直挂着
        val result = withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
            WidgetDataRepository.searchSongs(context, query, includeLocal = false)
        }
        if (result == null) {
            toast(context, "在线搜索「$query」超时，请检查网络")
            return "搜「$query」：本地没有，在线搜索超时（${ONLINE_TIMEOUT_MS / 1000} 秒）"
        }
        val songs = result.songs.take(MAX_QUEUE)
        return when {
            songs.isNotEmpty() -> {
                Log.i(TAG, "online hit: ${songs.size} songs, first=${songs.first().title}")
                toast(context, "正在播放：${songs.first().title} - ${songs.first().artist}")
                WidgetDataRepository.playList(context, songs, 0)
                "搜「$query」：在线命中 ${songs.size} 首，播放 ${songs.first().title} - ${songs.first().artist}"
            }
            !result.onlineAvailable -> {
                toast(context, "本地曲库没有「$query」，也没有可用的在线来源")
                "搜「$query」：本地没有，也没有在线来源"
            }
            result.onlineFailed -> {
                toast(context, "搜索「$query」失败，请检查网络")
                "搜「$query」：在线搜索失败"
            }
            else -> {
                toast(context, "没有找到「$query」")
                "搜「$query」：没有找到"
            }
        }
    }

    /**
     * 本地曲库搜索：只看有本地文件的歌。
     * 搜索词按空格与「的」拆开（「周杰伦的晴天」→ 周杰伦 + 晴天），每一段都要在「歌名 歌手 专辑」里出现；
     * 排序：歌名完全一致 > 歌名包含 > 歌手匹配 > 专辑匹配。
     *
     * 2026-11-24：以前每条指令把整个曲库读进内存再筛（在线搜时还要再读一遍），车机上连说几句就卡。
     * 现在先在数据库里按最长的那一段关键词取候选（只取有本地文件的），再在候选里做原来的精确筛选和排序。
     */
    private suspend fun searchLocal(context: Context, query: String): List<UnifiedSong> {
        val whole = query.lowercase()
        val tokens = query.split(Regex("[\\s的]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(whole) }
        val dao = ZdsDatabase.getInstance(context).songDao()
        val anchor = tokens.maxByOrNull { it.length } ?: whole
        val locals = runCatching {
            dao.searchLocalFileSongs(escapeLike(anchor), LOCAL_CANDIDATES, DownloadStatus.DOWNLOADED)
        }.getOrDefault(emptyList()).filter { it.hasLocalFile() }
        if (locals.isEmpty()) return emptyList()

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
            runCatching { dao.localFileSongsByArtist(top.artist, 60, DownloadStatus.DOWNLOADED) }.getOrDefault(emptyList())
                .asSequence()
                .filter { it.id !in known && it.hasLocalFile() }
                .take(50)
                .forEach { queue.add(it) }
        }
        return queue.distinctBy { it.localFilePath?.trim()?.lowercase() ?: it.id }
            .take(MAX_QUEUE)
            .map { it.toSong() }
    }

    /** 没说具体内容：接着播上次的歌；从没播过则播本地曲库 */
    private suspend fun resumeOrPlayLibrary(context: Context, shuffle: Boolean = false): String {
        PlaybackQueueManager.initFromPrefs(context)
        PlaybackService.startPlaybackService(context)
        PlaybackQueueManager.currentSongFlow.value?.let { current ->
            val player = Media3Factory.getSharedExoPlayer(context)
            if (!player.playWhenReady) PlaybackQueueManager.togglePlay(context)
            return "续播 ${current.title}"
        }
        val locals = withContext(Dispatchers.IO) { localLibrary(context) }
        if (locals.isEmpty()) {
            toast(context, "本地曲库还没有歌曲")
            return "本地曲库为空"
        }
        val start = if (shuffle) locals.indices.random() else 0
        WidgetDataRepository.playList(context, locals, start)
        return "播放本地曲库（${locals.size} 首）"
    }

    /** 本地曲库里有文件的歌（最多 [MAX_QUEUE] 首）；须在 IO 线程调用 */
    private suspend fun localLibrary(context: Context): List<UnifiedSong> =
        runCatching {
            ZdsDatabase.getInstance(context).songDao().localFileSongs(MAX_QUEUE * 2, DownloadStatus.DOWNLOADED)
        }.getOrDefault(emptyList())
            .filter { it.hasLocalFile() }
            .take(MAX_QUEUE)
            .map { it.toSong() }

    /** 本地搜歌时从数据库最多取多少条候选 */
    private const val LOCAL_CANDIDATES = 1000

    /** LIKE 里的 % _ \ 按字面匹配（配合查询里的 ESCAPE '\'） */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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

    /** 设置「语音提示」关闭时不弹（诊断记录照写） */
    private fun toast(context: Context, text: String) {
        VoicePrefs.ensureLoaded(context)
        if (!VoicePrefs.toastEnabled) return
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
