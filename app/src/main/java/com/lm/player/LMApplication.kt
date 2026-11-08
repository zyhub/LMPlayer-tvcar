package com.lm.player

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.network.NetworkClientFactory
import com.lm.player.core.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class LMApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // 0. 安装全局崩溃捕获：电视/车机无法取 logcat，闪退后把堆栈留在本机供下次启动展示
        CrashLogger.install(this)

        // 0.05 流畅模式 (性能优化第二批)：必须早于图片加载器创建，图片并发限制按启动时的状态决定
        com.lm.player.core.designsystem.theme.SmoothMode.init(this)

        // 0.1 柠檬服务端会话过期（401）时用本地保存的密码自动重登，避免只能删掉服务器重新添加
        com.lm.player.core.network.LemonMusicProtocol.installSessionReauth(this)
        // 播客收听进度记录（后台服务单独拉起进程时也要能记录 / 续播）
        com.lm.player.core.podcast.PodcastController.init(this)

        // 1. 异步在后台线程加载 Conscrypt 安全提供商与 Media3 缓存（TV 版默认关闭边听边存）
        CoroutineScope(Dispatchers.IO).launch {
            NetworkClientFactory.installSecurityProvider()
            val prefs = getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)

            // 1.1 一次性迁移：界面上「同时保存 .lrc 独立歌词文件」历史上写的是旧键
            //     download_lrc_file，而下载引擎读的是 download_lrc_file_v2（默认 false），
            //     两个键从不同步 → 该开关从未真正生效。现已把界面统一到 _v2，
            //     这里把老用户手动拨动过（旧键存在即代表显式选择，全工程无其它写入点）的
            //     选择搬过去，兑现其既有意图；从未动过的用户保持默认关闭。
            //     副作用需知悉：曾开启过的老用户升级后 .lrc 会开始真的保存 —— 这本来就是该开关的语义。
            if (prefs.contains("download_lrc_file") && !prefs.contains("download_lrc_file_v2")) {
                prefs.edit()
                    .putBoolean("download_lrc_file_v2", prefs.getBoolean("download_lrc_file", false))
                    .apply()
            }

            // 1.2 「在线试听边听边存」无需迁移：写入侧一直写的就是 stream_cache_enabled_v2，
            //     老用户若开过，改读 _v2 后自然读回 true。
            //     这里顺带清掉旧的 tv_stream_cache_default_applied 一次性标记 —— 它当年只会往
            //     无人读取的旧键 stream_cache_enabled 写 false，统一键后已无意义。
            if (prefs.contains("tv_stream_cache_default_applied")) {
                prefs.edit().remove("tv_stream_cache_default_applied").apply()
            }

            val streamCacheEnabled = prefs.getBoolean("stream_cache_enabled_v2", false)
            com.lm.player.core.media.Media3Factory.setCacheEnabled(streamCacheEnabled)
            if (streamCacheEnabled) {
                com.lm.player.core.media.Media3Factory.prewarm(this@LMApplication)
            }
        }

        // 2. 预初始化全局 Room 数据库单例。
        //    Room 首次 open 要建库/校验 schema，是实打实的磁盘操作，
        //    放在 onCreate 主线程会拖慢电视冷启动首帧，因此挪进上面的 IO 协程统一处理。
        CoroutineScope(Dispatchers.IO).launch {
            val db = ZdsDatabase.getInstance(this@LMApplication)
            // 一次性修正：旧版本允许保存 `HTTP://NAS.Local` 这类大写协议/主机名的地址，导致无法登录。
            // 启动时统一规范为小写协议和主机名（路径保持原样），无需删掉服务器重新添加。
            runCatching {
                db.serverDao().getAllServers().forEach { server ->
                    val canonical = com.lm.player.core.network.ServerUrlPolicy.canonicalize(server.serverUrl)
                    if (canonical.isNotBlank() && canonical != server.serverUrl) {
                        db.serverDao().updateServerUrl(server.id, canonical)
                    }
                }
            }
            com.lm.player.core.source.ServerSourceRepository.init(this@LMApplication)
            com.lm.player.core.source.SourceAccelerationSession.init(this@LMApplication)
            com.lm.player.core.source.SourceAccelerationController.init(this@LMApplication)
            com.lm.player.core.source.SourceAccelerationController.restoreFromCache(this@LMApplication)
        }

        // 3. 桌面小部件（车机桌面常用）：TV 版播放队列没有逐处埋点，统一监听「当前歌曲 + 播放状态」，
        //    切歌 / 播放暂停 / 收藏 / 封面元数据变化时刷新一次；没有放置小部件时控制器内部直接返回。
        CoroutineScope(Dispatchers.Main).launch {
            combine(
                com.lm.player.core.media.PlaybackQueueManager.currentSongFlow,
                com.lm.player.core.media.PlaybackQueueManager.isPlayingFlow
            ) { song, playing ->
                listOf(song?.id, song?.title, song?.artist, song?.album, song?.coverUrl, song?.isFavorite, playing)
            }.distinctUntilChanged().drop(1).collect {
                com.lm.player.core.widget.MusicWidgetController.notifyPlaybackStateChanged(this@LMApplication)
            }
        }

        // 4. 歌单小部件：本地歌单 / 收藏变化时刷新「我的歌单」（没放歌单小部件时只是空转监听）
        com.lm.player.core.widget.PlaylistWidgetController.startObserving(this)
    }

    private var lastWidgetOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED

    /** 横竖屏切换（平板 / 车机常见）后桌面格子尺寸改变：桌面小部件按新尺寸重绘 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != lastWidgetOrientation) {
            lastWidgetOrientation = newConfig.orientation
            com.lm.player.core.widget.WidgetDataRepository.refreshAllForNewSize(this)
        }
    }


    /**
     * 针对电视大屏与机顶盒调优的高性能低显存 Coil 图像加载器
     */
    override fun newImageLoader(): ImageLoader {
        val okHttpClient = NetworkClientFactory.createImageOkHttpClient(this)
        val builder = ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(false) // 关闭多余淡入淡出动画，换取极致流畅与零掉帧
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    // 图片磁盘缓存 96MB（原 128MB）：与流媒体缓存、OkHttp 缓存合计
                    // 不应把 cacheDir 撑到 GB 级；Coil 内存缓存已覆盖绝大多数滚动场景
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .allowRgb565(true)
            .respectCacheHeaders(false)
        if (com.lm.player.core.designsystem.theme.SmoothMode.activeAtStartup) {
            // 流畅模式 (第二批)：老设备 CPU 核少，同时解码多张封面会和界面线程抢 CPU，滑动列表时掉帧；
            // 解码并发限制为 2。图片加载器只在启动时创建，切换流畅模式后需重启应用才生效。
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            builder.decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
        }
        return builder.build()
    }
}
