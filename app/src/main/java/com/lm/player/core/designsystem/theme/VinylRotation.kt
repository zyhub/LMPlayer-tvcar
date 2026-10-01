package com.lm.player.core.designsystem.theme

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive

/**
 * 全局黑胶唱片旋转角度持有者。
 *
 * 主界面常驻播控卡与全屏播放页此前各自维护一份独立的旋转角度，两处黑胶会随进入/退出
 * 播放页的时刻不同而逐渐错位。这里改为全进程共享同一个角度，任意时刻两个界面看到的
 * 黑胶角度完全一致；播放页打开/关闭时也不会突然跳变。
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

    /** 当前正在推进角度的驱动方数量，避免多个界面同时驱动导致转速翻倍 */
    private var activeDrivers = 0

    internal val hasDriver: Boolean get() = activeDrivers > 0

    internal fun acquireDriver() {
        activeDrivers++
    }

    internal fun releaseDriver() {
        activeDrivers = (activeDrivers - 1).coerceAtLeast(0)
    }

    internal fun advance(deltaMs: Long) {
        val safeDelta = deltaMs.coerceIn(0L, 64L)
        mutableAngle.floatValue = (mutableAngle.floatValue + safeDelta * DEGREES_PER_SECOND / 1000f) % 360f
    }
}

/**
 * 订阅共享的黑胶旋转角度并按帧推进动画。
 *
 * - 播放中：由第一个调用方担任驱动方逐帧推进全局角度，其余调用方仅读取，保证多处同步；
 * - 暂停时：角度原地保持，恢复播放后从同一角度继续，不会出现角度跳变。
 */
@Composable
fun rememberVinylRotation(isPlaying: Boolean): State<Float> {
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        // 已有其他界面在推进全局角度时不再重复驱动，否则转速会成倍加快
        if (VinylRotationHolder.hasDriver) return@LaunchedEffect
        VinylRotationHolder.acquireDriver()
        try {
            var lastFrame = withFrameMillis { it }
            while (isActive) {
                val now = withInfiniteAnimationFrameMillis { it }
                VinylRotationHolder.advance(now - lastFrame)
                lastFrame = now
            }
        } finally {
            VinylRotationHolder.releaseDriver()
        }
    }
    return VinylRotationHolder.angle
}
