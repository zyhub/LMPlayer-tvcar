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
     *
     * **不是永久标记**：置位后若连续 [HARDWARE_RETRY_SUCCESS_THRESHOLD] 首曲目在软解下
     * 稳定播放，会调用 [noteSuccessfulPlayback] 自动尝试回到硬解 —— 原先「一旦置位进程内
     * 不再复位」会导致一次偶发错误（音频设备热插拔、单个损坏文件）就让此后**所有**歌曲
     * （包括 MP3/AAC）永久走软解，在盒子上表现为高解析度卡顿、发热与耗电。
     */
    @Volatile
    private var forceSoftwareDecoding = false

    /** 软解模式下稳定播放的曲目计数，达到阈值后尝试回升硬解 */
    @Volatile
    private var softwareModeSuccessStreak = 0

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

    /** 软解模式下需要连续稳定播放多少首才尝试回升硬解 */
    private const val HARDWARE_RETRY_SUCCESS_THRESHOLD = 3

    /**
     * 硬解回升的失败记忆。
     *
     * 原实现只看「buildPlayer 是否抛异常」来判定回升成功，而硬解真正损坏时
     * 只有后续 onPlayerError 才能反馈 —— 于是「软解稳 3 首 → 回升 → 又报错 → 再软解」
     * 会无限循环，在硬解确实坏掉的盒子上表现为**每 3 首就静音重建一次**。
     * 这里记录连续失败次数，做指数退避：失败越多，下次尝试越晚。
     */
    @Volatile
    private var hardwareRetryFailures = 0
    @Volatile
    private var nextHardwareRetryAtMs = 0L

    /**
     * 由播放层在「一首曲目稳定播放了一段时间」时调用。
     *
     * 处于软解模式时累计成功次数，达到阈值就自动尝试重建回硬解 —— 这是
     * 「一次偶发解码错误导致整机永久软解」的自愈路径。回升失败会再次降级，
     * 不会造成反复抖动（每次回升都要再攒够 3 首成功）。
     *
     * 返回 true 表示本次调用触发了回升重建（调用方需要处理换绑后的续播）。
     */
    @Synchronized
    fun noteSuccessfulPlayback(context: Context): Boolean {
        if (!forceSoftwareDecoding) return false
        softwareModeSuccessStreak++
        if (softwareModeSuccessStreak < HARDWARE_RETRY_SUCCESS_THRESHOLD) return false
        // 指数退避：连续失败 1/2/3/4+ 次分别等 5/10/20/40 分钟（上限 40 分钟），
        // 避免在硬解确实不可用的设备上反复 release/rebuild（每次都会静音数秒）
        val now = System.currentTimeMillis()
        if (now < nextHardwareRetryAtMs) return false
        Log.i(TAG, "软解下已连续稳定播放 $softwareModeSuccessStreak 首，尝试回升硬件解码（累计失败 $hardwareRetryFailures 次）")
        softwareModeSuccessStreak = 0
        val swapped = rebuildWithSoftwareDecoding(context, forceSoftware = false) != null
        if (swapped) {
            // 回升动作本身成功：真正的成败要等后续是否再次报解码错误来判定，
            // 但先把退避时间推后，避免紧接着又重建一次
            hardwareRetryFailures = 0
            nextHardwareRetryAtMs = 0L
        }
        return swapped
    }

    @Synchronized
    fun getSimpleCache(context: Context): SimpleCache {
        if (simpleCacheInstance == null) {
            val cacheDir = File(context.applicationContext.cacheDir, "media3_lru_stream_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            // 流媒体缓存上限：**按可用空间动态取值**，上限 300MB。
            // 此前硬编码 2GB —— 而 cacheDir 下还有图片缓存与 OkHttp 缓存，三者合计可达 2.3GB，
            // 电视盒子/车机内置存储常为 8~16GB，会显著挤占空间；Android 只在存储告急时才回收
            // 缓存目录且不保证。行业常规（ExoPlayer 官方示例）为 50~200MB。
            val cacheBudgetBytes = run {
                val capBytes = 300L * 1024 * 1024
                try {
                    val stat = android.os.StatFs(cacheDir.absolutePath)
                    // 取「上限」与「可用空间的 1/10」中的较小值，最低保留 64MB 保证基本缓冲能力
                    val dynamic = (stat.availableBytes / 10).coerceAtLeast(64L * 1024 * 1024)
                    minOf(capBytes, dynamic)
                } catch (_: Exception) {
                    capBytes
                }
            }
            val evictor = LeastRecentlyUsedCacheEvictor(cacheBudgetBytes)
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

    /**
     * 释放进程级共享播放器（彻底退出播放时调用）。
     *
     * 此前 TV 端也没有任何一处 release()：播放器一旦 build 过就会一直持有
     * setWakeMode(C.WAKE_MODE_NETWORK) 带来的 PARTIAL_WAKE_LOCK、解码器与 AudioTrack。
     * 电视长期插电虽不敏感，但「用户明确关闭播放」后仍攥着音频资源并不合理，
     * 也会让厂商省电策略把本应用判为常驻耗电应用。
     *
     * 注意：释放后 sharedExoPlayer 为 null，下次 getSharedExoPlayer 会重建；
     * PlaybackQueueManager 通过 playerSwapListeners 换绑监听，MediaSession 由
     * PlaybackService 的 playerSwapListener 换绑，两处都能自动跟上新实例。
     */
    @Synchronized
    fun releaseSharedPlayer() {
        val player = sharedExoPlayer ?: return
        sharedExoPlayer = null
        try {
            player.release()
            Log.i(TAG, "Shared ExoPlayer released")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to release shared ExoPlayer", e)
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
        return rebuildWithSoftwareDecoding(context, forceSoftware = true)!!
    }

    /**
     * 重建播放器内核并换绑所有订阅者。
     *
     * @param forceSoftware true = 切到纯软件解码（降级）；false = 尝试回升硬件解码。
     * @return 新播放器实例；无需重建时返回现有实例；重建失败返回 null。
     */
    @Synchronized
    private fun rebuildWithSoftwareDecoding(context: Context, forceSoftware: Boolean): ExoPlayer? {
        val appContext = context.applicationContext
        val existing = sharedExoPlayer
        // 已是目标状态则无需重建（避免反复 release/rebuild 造成播放中断）
        if (forceSoftwareDecoding == forceSoftware && existing != null) return existing

        // 换绑前保存播放器级状态：rebuild 会换掉整个播放器实例，
        // 若不回写，用户设置的**倍速会静默回落到 1.0**（TV-C17），音量同理。
        val savedPlaybackParameters = runCatching { existing?.playbackParameters }.getOrNull()
        val savedVolume = runCatching { existing?.volume }.getOrNull()

        // 从硬解降级到软解时累加失败次数并设置退避窗口
        if (forceSoftware) {
            hardwareRetryFailures++
            val backoffMinutes = minOf(40L, 5L shl minOf(3, hardwareRetryFailures - 1))
            nextHardwareRetryAtMs = System.currentTimeMillis() + backoffMinutes * 60_000L
            Log.w(TAG, "硬解降级第 $hardwareRetryFailures 次，下次回升尝试推迟 $backoffMinutes 分钟")
        }
        forceSoftwareDecoding = forceSoftware
        softwareModeSuccessStreak = 0
        Log.w(
            TAG,
            if (forceSoftware) "音频硬件解码不可用，正在重建播放器为纯软件解码模式"
            else "正在尝试回升硬件解码（软解下已稳定播放若干首）"
        )
        try {
            existing?.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放旧播放器实例失败", e)
        }
        sharedExoPlayer = null

        val fresh = try {
            buildPlayer(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "重建播放器失败，回退到上一个解码策略", e)
            // 重建失败时回退：回升硬解失败就退回软解，保证「至少能播」
            forceSoftwareDecoding = !forceSoftware
            return try {
                val fallback = buildPlayer(appContext)
                sharedExoPlayer = fallback
                notifySwap(fallback)
                fallback
            } catch (e2: Throwable) {
                Log.e(TAG, "回退重建同样失败", e2)
                null
            }
        }
        sharedExoPlayer = fresh
        // 回写播放器级状态，保证换绑对用户完全无感（倍速、音量不丢）
        runCatching {
            if (savedPlaybackParameters != null) fresh.playbackParameters = savedPlaybackParameters
            if (savedVolume != null) fresh.volume = savedVolume
        }
        notifySwap(fresh)
        return fresh
    }

    private fun notifySwap(player: ExoPlayer) {
        for (listener in playerSwapListeners) {
            try {
                listener(player)
            } catch (e: Exception) {
                Log.w(TAG, "播放器换绑回调异常", e)
            }
        }
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
        // 注意：本函数**不会**释放播放器或删除缓存目录。
        try {

            // 关键点：DataSource 工厂在**构建播放器时**捕获了 SimpleCache 实例。
            // 因此不能「一边使用、一边 release 并删除目录」——SimpleCache 内部的 released
            // 断言会抛 IllegalStateException，内存索引与磁盘分片也会不一致。
            //
            // 但同样不能靠「先 releaseSharedPlayer()」来解决：那会让**正在播放的曲目直接中断**，
            // 用户点一下「清理试听缓存」音乐就哑了，必须手动恢复 —— 为一个清理动作付这个代价是不可接受的。
            //
            // 正确做法：**不释放、不删目录**，只逐 key 清掉缓存内容。这样
            //   ① 播放器的 DataSource 引用依旧有效，播放完全不受影响；
            //   ② 磁盘上的分片文件被逐个删除，空间照常释放；
            //   ③ SimpleCache 的索引与磁盘始终一致，不会抛异常。
            // SimpleCache 自身的 LRU 上限（按可用空间动态取值，上限 300MB）负责后续回收。
            val cache = simpleCacheInstance
            if (cache != null) {
                for (key in cache.keys.toSet()) {
                    try { cache.removeResource(key) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun clearCache(context: Context) = clearStreamCache(context)
}
