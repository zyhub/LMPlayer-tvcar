package com.lm.player.core.designsystem.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf

/** 旧版「流畅模式」设置项（只用于从旧设置迁移） */
enum class SmoothModeSetting { AUTO, ON, OFF }

/** 性能模式档位（2026-11-13 性能优化第 8 项）：画质 / 均衡 / 流畅 */
enum class PerformanceLevel(val label: String) {
    QUALITY("画质优先"),
    BALANCED("均衡"),
    SMOOTH("流畅优先")
}

/** 性能模式设置项：自动（按设备判定）或固定某一档 */
enum class PerformanceSetting(val label: String) {
    AUTO("自动"),
    QUALITY("画质优先"),
    BALANCED("均衡"),
    SMOOTH("流畅优先")
}

/**
 * 性能模式（2026-11-13 起由原「流畅模式」开 / 关两档扩成三档，统一管理所有画面效果）。
 *
 * | 效果 | 画质 | 均衡 | 流畅 |
 * |---|---|---|---|
 * | 共享动画时钟 | 30 帧 | 30 帧 | 20 帧 |
 * | 黑胶背景模糊封面旋转 | 跟着转 | 静止 | 静止 |
 * | 歌词景深模糊 | 模糊字图、上下各 3 行 | 模糊字图、上下各 3 行 | 关闭 |
 * | 歌词行弹簧滚动 | 开 | 开 | 直接跳到位 |
 * | 专辑卡片同时渲染 | 11 张 + 倒影 | 7 张 + 倒影 | 5 张、无倒影 |
 * | 全屏封面放大后慢速推近 | 开 | 开 | 关 |
 * | 列表「正在播放」音波 | 动 | 动 | 静止 |
 * | 唱片飞入飞出、封面放大缩小过渡 | 完整 | 完整 | 缩短一半 |
 * | 封面解码 | 32 位色 | 32 位色 | 16 位色、并发 2（重启生效） |
 *
 * 自动：内存 ≤ 2.5GB / 系统标记低内存 / Android 8 以下 → 流畅；其余 → 均衡。
 *
 * [isActive] 仍表示「流畅档」（沿用原流畅模式的含义，已有的调用点不用改）；[level] 是 Compose 可观察状态。
 */
object SmoothMode {

    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_SETTING_OLD = "smooth_mode_setting_v1"
    private const val KEY_SETTING = "performance_mode_setting_v2"

    /** 总内存低于该值视为低性能设备：标称 2GB 的机器实际可用总内存多在 1.7~1.9GB */
    private const val LOW_END_TOTAL_MEM_BYTES = 2_500L * 1024 * 1024

    private val settingState = mutableStateOf(PerformanceSetting.AUTO)
    private val levelState = mutableStateOf(PerformanceLevel.BALANCED)

    @Volatile
    private var initialized = false

    /** 本机是否被判定为低性能设备（自动档据此选「流畅」） */
    var isLowEndDevice: Boolean = false
        private set

    /** 判定原因，供设置页展示 */
    var lowEndReason: String = ""
        private set

    /**
     * 启动时（Application.onCreate）是否为流畅档。
     * 图片加载器只在启动时创建一次，它的色彩与并发按此值决定，因此切换后需重启应用。
     */
    @Volatile
    var activeAtStartup: Boolean = false
        private set

    val setting: PerformanceSetting get() = settingState.value

    /** 当前生效的档位（Compose 可观察） */
    val level: PerformanceLevel get() = levelState.value

    /** 流畅档（沿用原「流畅模式开启」的含义） */
    val isActive: Boolean get() = levelState.value == PerformanceLevel.SMOOTH

    /** 画质档：只有这一档才跑最费 GPU 的效果（黑胶背景旋转、12 遍景深模糊、11 张卡片） */
    val isQuality: Boolean get() = levelState.value == PerformanceLevel.QUALITY

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        detectDevice(app)
        val prefs = runCatching { app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()
        val saved = runCatching { prefs?.getString(KEY_SETTING, null) }.getOrNull()
        settingState.value = PerformanceSetting.entries.firstOrNull { it.name == saved }
            ?: when (runCatching { prefs?.getString(KEY_SETTING_OLD, null) }.getOrNull()) {
                // 旧「流畅模式」迁移：强制开 → 流畅；强制关 → 画质（原来关着就是全部效果）；自动 → 自动
                SmoothModeSetting.ON.name -> PerformanceSetting.SMOOTH
                SmoothModeSetting.OFF.name -> PerformanceSetting.QUALITY
                else -> PerformanceSetting.AUTO
            }
        recompute()
        activeAtStartup = isActive
    }

    fun updateSetting(context: Context, value: PerformanceSetting) {
        settingState.value = value
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SETTING, value.name).apply()
        }
        recompute()
    }

    /** 自动档此刻会选哪一档（设置页说明用） */
    val autoLevel: PerformanceLevel
        get() = if (isLowEndDevice) PerformanceLevel.SMOOTH else PerformanceLevel.BALANCED

    private fun recompute() {
        levelState.value = when (settingState.value) {
            PerformanceSetting.QUALITY -> PerformanceLevel.QUALITY
            PerformanceSetting.BALANCED -> PerformanceLevel.BALANCED
            PerformanceSetting.SMOOTH -> PerformanceLevel.SMOOTH
            PerformanceSetting.AUTO -> autoLevel
        }
    }

    private fun detectDevice(context: Context) {
        val reasons = ArrayList<String>(3)
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                if (am.isLowRamDevice) reasons.add("系统标记为低内存设备")
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                if (info.totalMem in 1 until LOW_END_TOTAL_MEM_BYTES) {
                    val gb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
                    reasons.add("总内存约 ${"%.1f".format(gb)}GB")
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            reasons.add("Android ${Build.VERSION.RELEASE}")
        }
        isLowEndDevice = reasons.isNotEmpty()
        lowEndReason = reasons.joinToString("，")
    }
}
