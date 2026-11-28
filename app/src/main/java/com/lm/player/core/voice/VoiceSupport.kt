package com.lm.player.core.voice

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lm.player.MainActivity
import com.lm.player.core.media.PlaybackService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音相关设置（2026-11-23）。单独一个配置文件：语音指令可能在界面没起来时到达（广播 / 媒体会话 / 中转页），
 * 不能依赖界面初始化过的 AppUiPrefs。
 */
object VoicePrefs {
    private const val PREFS = "lm_voice_prefs"
    private const val KEY_TOAST = "voice_toast"
    private const val KEY_SHOW_UI = "voice_show_ui"

    @Volatile private var loaded = false

    /** 语音点歌时弹出「正在播放…」「没找到…」提示（默认开） */
    var toastEnabled by mutableStateOf(true)
        private set

    /** 语音点歌时把软件切到前台（默认开 = 以前的行为）；关闭后只在后台换歌，不打断导航 */
    var showUi by mutableStateOf(true)
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        toastEnabled = p.getBoolean(KEY_TOAST, true)
        showUi = p.getBoolean(KEY_SHOW_UI, true)
    }

    fun updateToast(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        toastEnabled = enabled
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TOAST, enabled).apply()
    }

    fun updateShowUi(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        showUi = enabled
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_UI, enabled).apply()
    }
}

/**
 * 最近几条语音 / 外部控制记录（2026-11-23）：车上没法抓日志，设置页「最近语音指令」直接显示
 * 指令从哪条路进来（媒体会话 / 意图 / 广播 / 媒体键 / 播放控制）、谁发的、说了什么、怎么处理的。
 * 用来确认「音乐助手」到底用哪种方式控制本应用。只存在本机，最多 50 条（页面显示最近 10 条，导出文件带全部）。
 */
object VoiceDiagnostics {
    private const val TAG = "VoicePlay"
    private const val PREFS = "lm_voice_diag"
    private const val KEY_LINES = "lines"
    // 页面上显示最近 10 条，导出文件里带全部（最多 50 条）
    private const val MAX_LINES = 50
    private const val SEP = "\u001E"

    /** 有新记录时加一，设置页据此刷新 */
    val version = mutableIntStateOf(0)

    // 只在 @Synchronized 的 record 里用，不会并发
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private var nextId = 0L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 记一条。返回这条的编号，处理完后用 [finish] 把结果补上。
     * @param source 进来的路：点歌（媒体会话 / 意图 / 广播）、媒体键、播放控制
     * @param caller 发起方包名（拿不到时为 null）
     */
    @Synchronized
    fun record(context: Context, source: String, caller: String?, content: String): Long {
        val id = System.currentTimeMillis() * 10 + (nextId++ % 10)
        val time = timeFmt.format(Date())
        val who = caller?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        val line = "$id|$time  $source$who：$content"
        Log.i(TAG, "diag: $line")
        runCatching {
            val p = prefs(context)
            val old = p.getString(KEY_LINES, "").orEmpty().split(SEP).filter { it.isNotBlank() }
            val all = (listOf(line) + old).take(MAX_LINES)
            p.edit().putString(KEY_LINES, all.joinToString(SEP)).apply()
        }
        version.intValue++
        return id
    }

    /** 给编号为 [id] 的那条补上处理结果 */
    @Synchronized
    fun finish(context: Context, id: Long, result: String) {
        Log.i(TAG, "diag result: $id → $result")
        runCatching {
            val p = prefs(context)
            val prefix = "$id|"
            val lines = p.getString(KEY_LINES, "").orEmpty().split(SEP).filter { it.isNotBlank() }
                .map { if (it.startsWith(prefix)) "$it → $result" else it }
            p.edit().putString(KEY_LINES, lines.joinToString(SEP)).apply()
        }
        version.intValue++
    }

    /**
     * 别的应用把主界面打开 / 切到前台时记一条（2026-11-24：车测发现分类点歌、播放模式这些指令什么都没收到，
     * 记下音乐助手是不是改用「直接打开应用」的方式，以及带了哪些附加字段）。本应用自己打开的不记。
     */
    /**
     * 资源快照（2026-11-24，查「多次语音点歌后车机变卡」）：本应用内存（Java 堆 / 原生）、线程数、系统剩余内存。
     * 读 /proc/self/status，放 IO 线程调用。
     */
    fun resourceSnapshot(context: Context): String = runCatching {
        val mb = 1024L * 1024L
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / mb
        val javaMax = rt.maxMemory() / mb
        val native = android.os.Debug.getNativeHeapAllocatedSize() / mb
        val threads = runCatching {
            java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')?.trim()
        }.getOrNull() ?: Thread.activeCount().toString()
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(mi)
        "内存 Java ${javaUsed}/${javaMax}MB·原生 ${native}MB；线程 $threads；系统可用 ${mi.availMem / mb}MB" +
            if (mi.lowMemory) "（系统内存吃紧）" else ""
    }.getOrElse { "资源快照失败：${it.javaClass.simpleName}" }

    fun recordAppOpen(context: Context, caller: String?, intent: Intent?, label: String, note: String? = null) {
        if (caller.isNullOrBlank() || caller == context.packageName) return
        val action = intent?.action?.removePrefix("android.intent.action.") ?: "无"
        val extras = runCatching {
            intent?.extras?.let { b ->
                b.keySet().sorted().joinToString(", ") { k ->
                    @Suppress("DEPRECATION")
                    "${k.removePrefix("android.intent.extra.")}=${b.get(k)?.toString()?.replace('\n', ' ')?.take(40)}"
                }
            }
        }.getOrNull()?.take(160)
        record(
            context, label, caller,
            "action=$action" + (extras?.takeIf { it.isNotEmpty() }?.let { "［$it］" } ?: "") +
                (note?.let { " → $it" } ?: "")
        )
    }

    /**
     * 导出语音日志文件（2026-11-24：车机上复制的文字不好传出来，改成存成文件，用文件管理器拷到 U 盘或发给自己）。
     * 存到「下载 / LMPlayer」目录（安卓 10 起走系统下载库，不需要存储权限）；旧系统写不进去时退到应用自己的目录。
     * 必须在 IO 线程调用。成功返回文件位置的说明，失败抛异常。
     */
    fun exportToFile(context: Context): String {
        val appCtx = context.applicationContext
        val now = Date()
        val name = "LMPlayer语音日志_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now) + ".txt"
        val text = buildString {
            appendLine("LMPlayer 语音日志")
            appendLine("导出时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now))
            appendLine("版本：${com.lm.player.BuildConfig.VERSION_NAME} (${com.lm.player.BuildConfig.VERSION_CODE})，构建 ${com.lm.player.BuildConfig.BUILD_TIME}")
            appendLine("包名：${appCtx.packageName}")
            appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}，Android ${android.os.Build.VERSION.RELEASE}")
            VoicePrefs.ensureLoaded(appCtx)
            appendLine("设置：语音点歌时打开界面=${if (VoicePrefs.showUi) "开" else "关"}，语音提示=${if (VoicePrefs.toastEnabled) "开" else "关"}")
            appendLine()
            appendLine("—— 最近记录（新的在前）——")
            val all = lines(appCtx)
            if (all.isEmpty()) appendLine("（没有记录）") else all.forEach { appendLine(it) }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/LMPlayer")
            }
            val resolver = appCtx.contentResolver
            val uri = runCatching { resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            if (uri != null) {
                val ok = runCatching {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                }.getOrDefault(false)
                if (ok) return "下载/LMPlayer/$name"
                runCatching { resolver.delete(uri, null, null) }
            }
        } else {
            @Suppress("DEPRECATION")
            val ok = runCatching {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "LMPlayer")
                dir.mkdirs()
                java.io.File(dir, name).writeBytes(bytes)
            }.isSuccess
            if (ok) return "下载/LMPlayer/$name"
        }
        // 退路：应用自己的外部目录（不需要权限；文件管理器里在 Android/data/<包名>/files/logs）
        val dir = java.io.File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "logs")
        dir.mkdirs()
        val file = java.io.File(dir, name)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** 清空记录（设置「语音控制」页） */
    @Synchronized
    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_LINES).apply() }
        version.intValue++
    }

    /** 最近的记录（新的在前），不带内部编号 */
    fun lines(context: Context): List<String> = runCatching {
        prefs(context).getString(KEY_LINES, "").orEmpty().split(SEP).filter { it.isNotBlank() }
            .map { it.substringAfter('|') }
    }.getOrDefault(emptyList())
}

/**
 * 语音点歌中转页（2026-11-23）：`MEDIA_PLAY_FROM_SEARCH` 与自定义点歌意图由它接（以前由主界面接，主界面一定会被拉到前台）。
 * 不显示任何东西、处理完立即关掉；独立 taskAffinity，不会把主界面任务带到前台。
 * 设置「语音点歌时打开界面」开着时再把主界面切到前台（与以前的效果一样）。
 */
class VoiceRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val i = intent
            if (savedInstanceState == null && VoicePlayRouter.isPlaySearchIntent(i)) {
                VoicePrefs.ensureLoaded(this)
                val caller = runCatching { referrer?.host }.getOrNull()
                // 搜索（在线时可能要几秒）期间进程不被回收：先把播放服务拉成前台
                PlaybackService.startPlaybackService(this)
                VoicePlayRouter.requestFromExtras(
                    applicationContext,
                    i.getStringExtra(SearchManager.QUERY),
                    i.extras,
                    "intent",
                    caller
                )
                if (VoicePrefs.showUi) {
                    // 像桌面图标那样把主界面切到前台：已在运行就保持原来的页面，没运行就冷启动
                    // （带上标记，启动续播不会把刚点的歌顶掉）
                    val launch = Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        putExtra(VoicePlayRouter.EXTRA_FROM_VOICE, true)
                    }
                    runCatching { startActivity(launch) }
                        .onFailure { Log.w("VoicePlay", "open main screen failed", it) }
                }
            }
        } catch (e: Throwable) {
            Log.w("VoicePlay", "voice relay failed", e)
        } finally {
            // Theme.NoDisplay：必须在 onResume 之前关掉
            finish()
        }
    }
}
