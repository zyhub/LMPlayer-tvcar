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
import kotlinx.coroutines.launch

class LMApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // 0. 安装全局崩溃捕获：电视/车机无法取 logcat，闪退后把堆栈留在本机供下次启动展示
        CrashLogger.install(this)

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
            ZdsDatabase.getInstance(this@LMApplication)
        }
    }

    /**
     * 针对电视大屏与机顶盒调优的高性能低显存 Coil 图像加载器
     */
    override fun newImageLoader(): ImageLoader {
        val okHttpClient = NetworkClientFactory.createOkHttpClient(this)
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(false) // 关闭多余淡入淡出动画，换取极致流畅与零掉帧
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.14)
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
            .build()
    }
}
