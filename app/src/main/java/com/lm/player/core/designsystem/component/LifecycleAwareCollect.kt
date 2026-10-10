package com.lm.player.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.StateFlow

/**
 * 生命周期感知的 StateFlow 收集（等价于 lifecycle-runtime-compose 的 collectAsStateWithLifecycle）。
 *
 * **为什么自实现**：本项目未引入 `androidx.lifecycle:lifecycle-runtime-compose`，
 * 而离线构建环境无法从远端拉取新依赖。这里只用 Compose 与 lifecycle 已有 API 实现同样语义：
 * 当 Lifecycle 低于 STARTED 时，把对外暴露的值**冻结**为最后一次可见值，
 * 从而阻断「界面已不可见却仍在持续收集并写 State」带来的无效重组与耗电。
 *
 * 注意：与 collectAsStateWithLifecycle 的行为差异 —— 后者会随生命周期真正暂停上游收集；
 * 本实现是「冻结下游读取」，上游仍在收集（Room/StateFlow 的收集本身开销很小）。
 * 若后续引入官方依赖，应替换为本函数的等价调用以便统一。
 */
@Composable
fun <T> StateFlow<T>.collectAsStateLifecycleAware(): State<T> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val raw by this.collectAsState()
    var visible by remember { mutableStateOf(true) }
    val observer = remember(lifecycleOwner) {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_STOP -> visible = false
                else -> Unit
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 不可见期间冻结最后一次可见值，避免后台持续触发重组
    val frozen = remember { mutableStateOf(raw) }
    if (visible) frozen.value = raw
    return frozen
}
