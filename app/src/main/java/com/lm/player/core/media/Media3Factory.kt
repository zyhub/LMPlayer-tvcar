package com.lm.player.core.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lm.player.core.network.NetworkClientFactory
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 播放内核工厂 (TV 版)
 *
 * 音频解码策略：**硬件优先，失败自动降级软件**。
 * - 默认优先选用 TV 芯片厂商硬件解码器 (Amlogic / Realtek / MTK / 海信 / TCL 等)，降低 CPU 占用与发热；
 * - 开启 `setEnableDecoderFallback`，硬解初始化失败时由 Media3 自动顺延至下一候选 (Google/Android 软解)；
 * - 若整机硬解被判定异常 (解码报错，或「进度条正常推进但完全无声」)，调用 [rebuildWithSoftwareDecoding]
 *   整体重建为纯软件解码并继续播放。
 */
@OptIn(UnstableApi::class)
object Media3Factory {

    private const val TAG = "Media3Factory"

    /**
     * 无损音频 MIME 类型集合：这些编码一律优先使用软件解码器。
     *
     * 部分智能电视芯片 (小米 / MTK / 晶晨等) 的厂商硬件 FLAC 解码器存在
     * 「初始化成功、播放位正常推进，却完全不输出声音」的缺陷：
     * 常规 16bit/44.1kHz 无损恰好命中该硬解路径而全程无声，
     * 24bit 高解析母带反而因超出硬解能力被 Media3 自动降级到 Google 软解，从而正常出声。
     * 这类静音故障不会抛出任何 PlaybackException，运行时无法可靠探测，
     * 因此无损音频直接优先软解以彻底规避；有损音频 (MP3/AAC/OGG) 仍保持硬件优先，
     * 把额外的 CPU 开销限制在高码率试听场景内。
     */
    private val LosslessAudioMimeTypes = setOf(
        MimeTypes.AUDIO_FLAC,
        MimeTypes.AUDIO_ALAC,
        MimeTypes.AUDIO_RAW,
        "audio/x-flac",
        "audio/wav",
        "audio/x-wav",
        "audio/ape",
        "audio/x-ape",
        "audio/dsd",
        "audio/x-dsd"
    )

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    @Volatile
    private var sharedExoPlayer: ExoPlayer? = null

    /**
     * 运行时解码降级标记：为 true 时后续重建的播放器只使用软件解码器。
     * 一旦置位则在整个进程生命周期内保持，避免反复在硬解/软解之间抖动。
     */
    @Volatile
    private var forceSoftwareDecoding = false

    /** 播放器实例被重建 (解码降级) 时的订阅者，供 MediaSession 等持有方换绑新实例 */
    private val playerSwapListeners = CopyOnWriteArrayList<(ExoPlayer) -> Unit>()

    /** 当前是否已处于纯软件解码模式 */
    val isSoftwareDecodingActive: Boolean get() = forceSoftwareDecoding

    fun addPlayerSwapListener(listener: (ExoPlayer) -> Unit) {
        if (!playerSwapListeners.contains(listener)) {
            playerSwapListeners.add(listener)
        }
    }

    fun removePlayerSwapListener(listener: (ExoPlayer) -> Unit) {
        playerSwapListeners.remove(listener)
    }

    @Synchronized
    fun getSimpleCache(context: Context): SimpleCache {
        if (simpleCacheInstance == null) {
            val cacheDir = File(context.applicationContext.cacheDir, "media3_lru_stream_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            // 2GB 最大磁盘 LRU 缓存，超出时自动淘汰最早未命中的音轨缓存切片
            val evictor = LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024)
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            simpleCacheInstance = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return simpleCacheInstance!!
    }

    /**
     * 在后台线程异步预热 Media3 磁盘与 SQLite 缓存，消除主线程冷启动 IO 阻塞
     */
    fun prewarm(context: Context) {
        try {
            getSimpleCache(context)
        } catch (_: Exception) {}
    }

    @Volatile
    private var isCacheEnabled = false

    fun setCacheEnabled(enabled: Boolean) {
        isCacheEnabled = enabled
    }

    fun isCacheEnabled(): Boolean = isCacheEnabled

    /**
     * 构建双协议自适应数据源工厂：
     * 1. 使用 DefaultDataSource.Factory 智能分发：
     *    - file:// 与 content:// 协议直通本地文件解码，彻底解决本地已下载歌曲播放无反应问题；
     *    - http:// 与 https:// 协议受 isCacheEnabled 控制（TV 版默认关闭边听边存）：
     *      * 开启缓存时由 OkHttpDataSource 与 SimpleCache 接管，支持高速流媒体与磁盘断点续传；
     *      * 关闭缓存时直通 DefaultDataSource 纯内存流式试听，彻底不写磁盘缓存。
     */
    fun buildDataSourceFactory(context: Context, okHttpClient: OkHttpClient): DataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        return DataSource.Factory {
            if (isCacheEnabled) {
                val cache = getSimpleCache(context)
                val cacheDataSinkFactory = CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(20L * 1024 * 1024) // 20MB 单切片，避免默认 5MB 频繁断开重连触发服务端代理并发限制
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
            } else {
                upstreamFactory.createDataSource()
            }
        }
    }

    @Synchronized
    fun getSharedExoPlayer(context: Context): ExoPlayer {
        sharedExoPlayer?.let { return it }
        val appContext = context.applicationContext
        val player = buildPlayer(appContext)
        sharedExoPlayer = player
        return player
    }

    /**
     * 硬件解码异常降级：释放当前播放器并以「纯软件解码」重建，随后通知所有换绑订阅者。
     * 若已处于纯软解状态则直接返回现有实例，重复调用安全。
     */
    @Synchronized
    fun rebuildWithSoftwareDecoding(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        val existing = sharedExoPlayer
        if (forceSoftwareDecoding && existing != null) return existing

        forceSoftwareDecoding = true
        Log.w(TAG, "音频硬件解码不可用，正在重建播放器为纯软件解码模式")
        try {
            existing?.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放旧播放器实例失败", e)
        }
        sharedExoPlayer = null

        val fresh = buildPlayer(appContext)
        sharedExoPlayer = fresh
        for (listener in playerSwapListeners) {
            try {
                listener(fresh)
            } catch (e: Exception) {
                Log.w(TAG, "播放器换绑回调异常", e)
            }
        }
        return fresh
    }

    @Synchronized
    private fun buildPlayer(appContext: Context): ExoPlayer {
        isCacheEnabled = appContext.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
            .getBoolean("stream_cache_enabled_v2", false)
        val okHttpClient = NetworkClientFactory.createOkHttpClient(appContext)
        val dataSourceFactory = buildDataSourceFactory(appContext, okHttpClient)
        val softwareOnly = forceSoftwareDecoding

        // 音频解码器排序：有损音频硬件优先、软件兜底；无损音频一律软件优先 (见 LosslessAudioMimeTypes)。
        // MediaCodecSelector.DEFAULT 已按系统优先级返回候选列表，此处显式把厂商硬解排到最前、
        // Google/Android 软解排到最后；配合 setEnableDecoderFallback(true)，
        // 硬解不可用时 Media3 会自动顺延到软解，无需手工重试。
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
                if (mimeType == null || !mimeType.startsWith("audio/") || defaultList.size <= 1) {
                    defaultList
                } else {
                    // partition 返回 (匹配项, 非匹配项)，即 (软件解码器, 硬件解码器)
                    val (softwareDecoders, hardwareDecoders) = defaultList.partition { isSoftwareDecoder(it.name) }
                    val preferSoftware = softwareOnly || LosslessAudioMimeTypes.contains(mimeType.lowercase())
                    if (preferSoftware) softwareDecoders + hardwareDecoders
                    else hardwareDecoders + softwareDecoders
                }
            }

        // 针对柠檬音乐服务端 /api/play/proxy (15秒 Socket 空闲超时) 调优缓冲窗口：
        // minBufferMs=50s 与 maxBufferMs=55s 仅差 5 秒 (< 15秒)，保证流连接每 5 秒持续读取保活，彻底根治播半首断开暂停问题
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 50_000,
                /* maxBufferMs = */ 55_000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory))
            // handleAudioFocus = true：**车机上必须参与音频焦点仲裁**。
            // 此前为 false，导致导航播报 / 倒车雷达 / 来电时音乐既不暂停也不压低音量，
            // 与车机自带的其它音源直接叠着放（车机场景下这是最容易被投诉的一条）。
            // 打开后由 ExoPlayer 内部 AudioFocusManager 处理 LOSS / LOSS_TRANSIENT / DUCK。
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK) // 同时持有 CPU WakeLock 与 WifiLock，防止休眠断流
            // 拔耳机 / 断开车机蓝牙时自动暂停（否则声音切到外放，车内体验很差）
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                volume = 1.0f
                // 播放期间自持应用级唤醒锁（电视/车机版后台保活，见 PlaybackWakeLockManager 注释）。
                // 挂在这里而不是 Service 上：首次创建与软解重建(rebuildWithSoftwareDecoding)都经过本函数，
                // 一处即覆盖两条构建路径，且锁的生命周期跟随进程级单例播放器而非短命的 Service。
                PlaybackWakeLockManager.bind(appContext, this)
            }
    }

    /** 判定解码器实现是否属于纯软件解码 (Google / Android 平台软解) */
    private fun isSoftwareDecoder(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("c2.android.") ||
            lower.startsWith("c2.google.") ||
            lower.startsWith("omx.google.") ||
            lower.contains(".sw.") ||
            lower.endsWith(".sw")
    }

    /**
     * 获取当前流媒体缓存大小 (字节)
     */
    fun getCacheSizeBytes(context: Context): Long {
        return try {
            val cacheDir = File(context.applicationContext.cacheDir, "media3_lru_stream_cache")
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 清理流媒体缓存
     */
    fun clearStreamCache(context: Context) {
        try {
            simpleCacheInstance?.let { cache ->
                for (key in cache.keys.toSet()) {
                    try { cache.removeResource(key) } catch (_: Exception) {}
                }
            }
            val cacheDir = File(context.applicationContext.cacheDir, "media3_lru_stream_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
            }
        } catch (_: Exception) {}
    }

    fun clearCache(context: Context) = clearStreamCache(context)
}
