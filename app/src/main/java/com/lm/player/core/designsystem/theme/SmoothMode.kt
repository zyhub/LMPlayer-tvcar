package com.lm.player.core.designsystem.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf

/** 流畅模式设置项：自动 (按设备性能判定) / 强制开启 / 强制关闭 */
enum class SmoothModeSetting { AUTO, ON, OFF }

/**
 * 「流畅模式」(性能优化第二批)。
 *
 * 面向老车机 / 老电视：开启后用少量视觉细节换取更低的 CPU / GPU 负载 ——
 * - 黑胶旋转与逐字歌词的动画时钟从约 30 帧/秒降到约 20 帧/秒；
 * - 全屏封面主题切歌时封面直接切换，不做两张大图的交叉淡化；
 * - 全屏封面主题去掉被 88% 遮罩盖住的整屏背景封面层，改用纯色衬底；
 * - 滚动歌词行的缩放不再做过渡动画 (透明度渐变保留)；
 * - 图片请求并发 128→16、解码并发限制为 2 (图片加载器在启动时创建，此项**重启应用后生效**)。
 *
 * [isActive] 是 Compose 可观察状态：界面在组合期读取即可随设置实时切换。
 */
object SmoothMode {

    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_SETTING = "smooth_mode_setting_v1"

    /** 总内存低于该值视为低性能设备：标称 2GB 的机器实际可用总内存多在 1.7~1.9GB */
    private const val LOW_END_TOTAL_MEM_BYTES = 2_500L * 1024 * 1024

    private val settingState = mutableStateOf(SmoothModeSetting.AUTO)
    private val activeState = mutableStateOf(false)

    @Volatile
    private var initialized = false

    /** 本机是否被判定为低性能设备 (自动模式据此决定是否开启) */
    var isLowEndDevice: Boolean = false
        private set

    /** 判定原因，供设置页展示 */
    var lowEndReason: String = ""
        private set

    /**
     * 启动时 (Application.onCreate) 的生效状态快照。
     * 图片加载器只在启动时创建一次，它的并发限制按此值决定，因此切换设置后需重启应用。
     */
    @Volatile
    var activeAtStartup: Boolean = false
        private set

    val setting: SmoothModeSetting get() = settingState.value

    /** 当前是否启用流畅模式 (Compose 可观察) */
    val isActive: Boolean get() = activeState.value

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        detectDevice(app)
        val saved = runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SETTING, null)
        }.getOrNull()
        settingState.value = SmoothModeSetting.entries.firstOrNull { it.name == saved } ?: SmoothModeSetting.AUTO
        recompute()
        activeAtStartup = activeState.value
    }

    fun updateSetting(context: Context, value: SmoothModeSetting) {
        settingState.value = value
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SETTING, value.name).apply()
        }
        recompute()
    }

    private fun recompute() {
        activeState.value = when (settingState.value) {
            SmoothModeSetting.ON -> true
            SmoothModeSetting.OFF -> false
            SmoothModeSetting.AUTO -> isLowEndDevice
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
