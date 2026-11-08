package com.lm.player.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 全进程共享的「限帧动画时钟」(约 30 帧/秒)。
 *
 * 性能优化第一批 A2：黑胶旋转与逐字歌词此前各自按屏幕刷新率 (60 帧/秒) 逐帧失效，
 * 只要在播放，整个窗口就每秒重绘 60 次 —— 老车机/老电视 GPU 长期满载，表现为卡顿与发热。
 *
 * 现在所有持续型动画都挂在这一个时钟上：
 * - 同一时刻只有一个订阅方担任驱动方，驱动方离开时由其余订阅方接手；
 * - 每约 33ms 才写一次状态，所有订阅方在**同一帧**一起失效，整窗重绘频率被锁在 ~30 次/秒
 *   (两个动画若各自限帧但相位错开，合起来仍可能是 60 次/秒，所以必须共用时钟)；
 * - 没有订阅方时不再请求任何帧，暂停零开销；退到后台时界面帧时钟暂停，驱动也随之暂停。
 *
 * 所有方法只能在主线程调用 (Composable 的 LaunchedEffect 内即为主线程)，因此无需加锁。
 */
object ThrottledFrameClock {

    /** 目标帧间隔：30 帧/秒 */
    const val FRAME_INTERVAL_MS = 33L

    /** 流畅模式下的目标帧间隔：20 帧/秒 (60Hz 屏每 3 帧推进一次) */
    const val SMOOTH_FRAME_INTERVAL_MS = 50L

    /**
     * 帧间隔判定容差：60Hz 屏两帧间隔为 33.3ms，取整后可能是 33 或 34；
     * 50Hz 电视两帧为 40ms (即 25 帧/秒)。留 4ms 容差保证 60Hz 屏稳定地「隔一帧推进一次」。
     */
    private const val FRAME_TOLERANCE_MS = 4L

    private val frameTime = mutableLongStateOf(0L)

    /** 最近一次推进的帧时间 (毫秒)。必须在 draw / graphicsLayer 等延迟阶段读取。 */
    val frameTimeMs: State<Long> get() = frameTime

    /** 当前是否已有订阅方在担任驱动方 */
    private val driverActive = MutableStateFlow(false)
    private val tickListeners = ArrayList<(Long) -> Unit>(2)

    /**
     * 在调用方的协程里保持订阅，直到被取消。
     *
     * 驱动协程必须跑在**界面自己的**帧时钟上 (LaunchedEffect 提供的、随 Activity 进入后台而暂停的时钟)：
     * 若改用进程级的 Choreographer 时钟，后台播放时 (车机导航盖在上面) 主线程仍会每帧被唤醒。
     * 因此由第一个订阅方担任驱动方；它离开时，其余订阅方中的一个立即接手。
     * 所有调用都在主线程，`driverActive` 的检查与设置之间不会被其它订阅方插入。
     */
    suspend fun runSubscription(): Nothing {
        while (true) {
            if (!driverActive.value) {
                driverActive.value = true
                try {
                    driveFrames()
                } finally {
                    driverActive.value = false
                }
            } else {
                driverActive.first { !it }
            }
        }
    }

    /** 注册每次推进时的回调 (参数为距上次推进的毫秒数)，用于黑胶角度这类需要累加的动画 */
    internal fun addTickListener(listener: (Long) -> Unit) {
        if (!tickListeners.contains(listener)) tickListeners.add(listener)
    }

    internal fun removeTickListener(listener: (Long) -> Unit) {
        tickListeners.remove(listener)
    }

    private suspend fun driveFrames(): Nothing {
        var last = withFrameMillis { it }
        frameTime.longValue = last
        while (true) {
            val now = withFrameMillis { it }
            val delta = now - last
            // 流畅模式 (第二批)：降到约 20 帧/秒；在帧循环内读取，切换设置后下一帧即生效
            val interval = if (SmoothMode.isActive) SMOOTH_FRAME_INTERVAL_MS else FRAME_INTERVAL_MS
            if (delta >= interval - FRAME_TOLERANCE_MS) {
                last = now
                // 先推进依赖增量的动画，再写时间戳：二者在同一帧生效
                for (i in tickListeners.indices) {
                    tickListeners.getOrNull(i)?.invoke(delta)
                }
                frameTime.longValue = now
            }
        }
    }
}

/**
 * 订阅限帧动画时钟：[active] 为 true 期间保持订阅，返回的帧时间需在绘制阶段读取。
 */
@Composable
fun rememberThrottledFrameClock(active: Boolean): State<Long> {
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        ThrottledFrameClock.runSubscription()
    }
    return ThrottledFrameClock.frameTimeMs
}

/**
 * 全局黑胶唱片旋转角度持有者。
 *
 * 主界面常驻播控卡与全屏播放页此前各自维护一份独立的旋转角度，两处黑胶会随进入/退出
 * 播放页的时刻不同而逐渐错位。这里改为全进程共享同一个角度，任意时刻两个界面看到的
 * 黑胶角度完全一致；播放页打开/关闭时也不会突然跳变。
 *
 * 角度由 [ThrottledFrameClock] 统一推进 (约 30 帧/秒)，不再逐帧驱动。
 */
object VinylRotationHolder {

    /** 每秒旋转角度 (度/秒)：约 18°/s，即 20 秒转满一圈 */
    private const val DEGREES_PER_SECOND = 18f

    private val mutableAngle = mutableFloatStateOf(0f)

    /**
     * 只读角度状态。
     *
     * 调用方**必须**在 `graphicsLayer { }` / `offset { }` 等「延迟读取」位置读取 `value`：
     * 若直接在组合作用域里读，播放期间每帧都会让整个作用域重组
     * (全屏播放页有 3200 行、主界面播控卡同理)，这是电视端最大的单项帧开销来源。
     */
    val angle: State<Float> get() = mutableAngle

    /** 当前角度快照 (非组合场景使用) */
    val angleValue: Float get() = mutableAngle.floatValue

    /** 当前需要黑胶旋转的界面数量：多个界面同时显示时角度也只推进一次，转速不会翻倍 */
    private var activeUsers = 0

    private val tickListener: (Long) -> Unit = { deltaMs -> advance(deltaMs) }

    internal fun acquire() {
        activeUsers++
        if (activeUsers == 1) ThrottledFrameClock.addTickListener(tickListener)
    }

    internal fun release() {
        activeUsers = (activeUsers - 1).coerceAtLeast(0)
        if (activeUsers == 0) ThrottledFrameClock.removeTickListener(tickListener)
    }

    internal fun advance(deltaMs: Long) {
        // 上限 100ms：限帧后正常增量约 33~40ms；主线程偶发卡顿后不让黑胶一次跳一大截
        val safeDelta = deltaMs.coerceIn(0L, 100L)
        mutableAngle.floatValue = (mutableAngle.floatValue + safeDelta * DEGREES_PER_SECOND / 1000f) % 360f
    }
}

/**
 * 订阅共享的黑胶旋转角度。
 *
 * - 播放中：登记为使用方，由全局限帧时钟推进角度，多处界面读到的是同一个角度；
 * - 暂停时：角度原地保持，恢复播放后从同一角度继续，不会出现角度跳变。
 */
@Composable
fun rememberVinylRotation(isPlaying: Boolean): State<Float> {
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        VinylRotationHolder.acquire()
        try {
            ThrottledFrameClock.runSubscription()
        } finally {
            VinylRotationHolder.release()
        }
    }
    return VinylRotationHolder.angle
}
