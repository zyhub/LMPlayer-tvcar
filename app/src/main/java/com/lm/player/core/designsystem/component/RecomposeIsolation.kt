package com.lm.player.core.designsystem.component

import androidx.compose.runtime.Composable

/**
 * 重组隔离区 (性能优化 A3)。
 *
 * Row / Column / Box 的内容 lambda 是 inline 的，在里面读取高频变化的状态 (例如每 0.4 秒刷新的
 * 播放进度) 会让**外层整个函数**一起重组 —— 全屏播放页有 3500 行，每次刷新都要整页重算。
 *
 * 把读取高频状态的那一小块 UI 包进本函数：可组合 lambda 自带独立的重组作用域，
 * 状态变化时只重算这一小块，外层函数保持跳过。本函数自身不产生任何布局节点，
 * 被包裹的内容仍直接排列在父布局里，外观与布局完全不变。
 */
@Composable
fun RecomposeIsolation(content: @Composable () -> Unit) {
    content()
}
