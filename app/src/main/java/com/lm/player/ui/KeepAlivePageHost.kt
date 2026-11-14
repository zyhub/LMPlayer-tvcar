package com.lm.player.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.lm.player.core.designsystem.component.LocalTvFocusScope
import com.lm.player.core.designsystem.component.tvFocusChildScope
import com.lm.player.core.designsystem.theme.SmoothMode
import kotlinx.coroutines.delay

/**
 * 当前页面是否正在显示。
 *
 * 被 [KeepAlivePageHost] 保留在后台的页面读到 false；普通页面（以及不在容器里的界面）读到 true。
 * 页面可据此在「重新显示」时做一次数据新鲜度检查（见发现页）。
 */
val LocalPageActive = compositionLocalOf { true }

/**
 * 本页面从后台**重新显示**的次数（首次进入为 0）。
 *
 * 页面被保留后不再随切页重建，原先靠「重建时 LaunchedEffect 重新执行」完成的进入页面刷新
 * （服务器歌单、扫描状态、发现页过期数据……）就不会再触发。把本值加进那些 LaunchedEffect 的 key，
 * 每次切回来它们会和原来一样再执行一次。
 */
@Composable
fun rememberPageReentryCount(): Int {
    val active = LocalPageActive.current
    val tracker = remember { PageReentryTracker(active) }
    if (active && !tracker.wasActive) tracker.count++
    tracker.wasActive = active
    return tracker.count
}

private class PageReentryTracker(var wasActive: Boolean) {
    var count: Int = 0
}

/** 被保留的页面在后台最多留多久；超时后释放，下次进入按原来的方式重建 */
private const val KEEP_ALIVE_RETAIN_MS = 10 * 60 * 1000L

/**
 * 切页提速：顶部页面容器。
 *
 * 原先整个内容区是一个 AnimatedContent：每次切页都销毁旧页面、从头组合新页面。
 * 「我的」「发现」这两个最重的页面即使数据都有缓存，光是重新组合 + 重新测量在车机上也要几百毫秒。
 *
 * 现在：[keepAliveKeyOf] 返回非 null 的页面进入过一次后就留在组合里，离开时只是
 * **不测量、不摆放**（因而不绘制、不接收触摸，方向键的焦点搜索也会跳过它 ——
 * Compose 的二维焦点搜索只考虑已摆放的节点），再切回来时直接摆放，不用重建。
 * 其余页面（搜索 / 播客 / 下载 / 设置）沿用原来的淡入淡出，离开即销毁。
 *
 * 隐藏期间额外处理三件事，保证行为与「页面已销毁」一致：
 * - 返回键：隐藏页面里的 BackHandler 改挂到一个永不触发的分发器上，不会抢走当前页面的返回键；
 * - 焦点：页面隐藏的那一刻若焦点还在它里面，立即清除（原先是节点销毁时由 Compose 清除）；
 * - 焦点记忆：每个保留页面用自己的作用域，两个页面同时存在时记忆与登记互不覆盖。
 *
 * @param enabled false 时全部页面都按原来的方式销毁重建（设置里可关）
 */
@Composable
fun <T : Any> KeepAlivePageHost(
    current: T,
    keepAliveKeyOf: (T) -> String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val currentKey = if (enabled) keepAliveKeyOf(current) else null

    // 已进入过、仍保留着的页面：key → 最近一次显示它时的页面值
    val aliveKeys = remember { mutableStateListOf<String>() }
    val aliveValues = remember { HashMap<String, T>() }
    if (currentKey != null) aliveValues[currentKey] = current

    DisposableEffect(currentKey) {
        if (currentKey != null && currentKey !in aliveKeys) aliveKeys.add(currentKey)
        onDispose { }
    }
    // 关闭开关后立即释放全部保留页面
    LaunchedEffect(enabled) {
        if (!enabled) {
            aliveKeys.clear()
            aliveValues.clear()
        }
    }
    // 长时间没有再切页：释放后台页面（内存归还；下次进入重建，数据也随之按原逻辑刷新）
    LaunchedEffect(currentKey, enabled) {
        if (!enabled) return@LaunchedEffect
        delay(KEEP_ALIVE_RETAIN_MS)
        val stale = aliveKeys.filter { it != currentKey }
        aliveKeys.removeAll(stale)
        stale.forEach { aliveValues.remove(it) }
    }

    val parentScope = LocalTvFocusScope.current

    Box(modifier = modifier.fillMaxSize()) {
        // 本次要显示的保留页面可能还没登记进 aliveKeys（登记发生在本次组合之后），这里补上，保证首帧就组合它
        val keysToCompose = if (currentKey != null && currentKey !in aliveKeys) aliveKeys + currentKey else aliveKeys
        keysToCompose.forEach { pageKey ->
            key(pageKey) {
                val value = aliveValues[pageKey]
                if (value != null) {
                    KeepAlivePage(
                        active = pageKey == currentKey,
                        focusScope = remember(parentScope, pageKey) { tvFocusChildScope(parentScope, "page_$pageKey") }
                    ) {
                        content(value)
                    }
                }
            }
        }

        // 非保留页面：与原先完全一致的淡入淡出。当前是保留页面时这里的目标为 null（空白），
        // 上一个非保留页面在它的淡出动画结束后销毁
        AnimatedContent(
            targetState = if (currentKey == null) current else null,
            // 始终占满：否则「空白 ↔ 页面」之间尺寸从 0 变到全屏，AnimatedContent 会做一段带裁剪的尺寸动画
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(120, easing = FastOutSlowInEasing))
            },
            label = "screen_transition"
        ) { transient ->
            if (transient != null) {
                content(transient)
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun KeepAlivePage(
    active: Boolean,
    focusScope: String,
    content: @Composable () -> Unit
) {
    val realBackOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inertBackOwner = remember(lifecycleOwner) { InertBackDispatcherOwner(lifecycleOwner) }

    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    // 页面被隐藏的那一刻焦点还在它里面（例如点了页面里的「下载管理」跳走）：清掉，
    // 否则按键会继续发给一个看不见的节点。与原先「节点销毁时 Compose 自动清焦点」等价
    DisposableEffect(active) {
        if (!active && hasFocus) focusManager.clearFocus(force = true)
        onDispose { }
    }

    // 切回来时很短的淡入；隐藏时立即归零，保证下次显示的第一帧就是从 0 开始
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (active && !SmoothMode.isActive) tween(110) else snap(),
        label = "keep_alive_page_alpha"
    )

    CompositionLocalProvider(
        LocalPageActive provides active,
        LocalTvFocusScope provides focusScope,
        LocalOnBackPressedDispatcherOwner provides
            (if (active || realBackOwner == null) (realBackOwner ?: inertBackOwner) else inertBackOwner)
    ) {
        Layout(
            content = content,
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { hasFocus = it.hasFocus }
                .graphicsLayer { this.alpha = alpha }
        ) { measurables, constraints ->
            if (!active) {
                // 隐藏：既不测量也不摆放。子节点保持组合（状态、滚动位置都在），但不参与布局、绘制、触摸与焦点搜索
                layout(constraints.maxWidth, constraints.maxHeight) { }
            } else {
                val placeables = measurables.map { it.measure(constraints) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEach { it.place(0, 0) }
                }
            }
        }
    }
}

/** 隐藏页面用的返回键分发器：没有任何东西会驱动它，挂在上面的 BackHandler 因此不会生效 */
private class InertBackDispatcherOwner(private val host: LifecycleOwner) : OnBackPressedDispatcherOwner {
    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()
    override val lifecycle: Lifecycle get() = host.lifecycle
}
