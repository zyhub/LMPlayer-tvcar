package com.lm.player.core.designsystem.component

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * TV 端「焦点记忆」系统 —— 解决点击/切换后焦点被重置到最左边的问题。
 *
 * ## 问题现象
 * 在「点歌台」页面（`LibrarySearchDialog`，代码内自称 KTV 点歌台）点击热词卡，
 * 或在「我的」页面（`LocalLibraryScreen`）点击歌手胶囊进入下钻，
 * 焦点光标会直接跳到最左边 —— 前者是左栏字母键盘首键，后者是左栏黑胶播客卡。
 *
 * ## 根因
 * 页面切换普遍写成 `if (viewState == null) { LazyColumn(...) }` 与
 * `if (viewState != null) { Column(...) }` 两个**互相独立**的 if，
 * 点击后条件翻转，含当前聚焦节点的整棵子树离开组合。
 * Compose 焦点系统在「当前焦点节点被销毁」时没有可继承的目标，
 * 只能把焦点交给焦点树中的默认节点 —— 布局上就是第一个可聚焦项，视觉上即「跳到最左」。
 *
 * 工程内没有任何代码指定这个落点，这正是缺陷本身。
 *
 * ## 修复设计（四件套，缺一不可）
 * 1. **机制**：本文件 + [tvFocusable] 的 `focusKey`，让每个可聚焦节点「可被点名恢复」；
 * 2. **分层记忆**：按 scope 分层存储（`mine` / `mine::周杰伦`），下钻不会覆盖上级记忆，
 *    返回时能精确回到「我点的那一项」；
 * 3. **进入侧锚点**：进入新视图时必须**显式指定落点**（`tvFocusEntryAnchor`）。
 *    因为被点击的节点此刻已经销毁，光靠「恢复」必然失败，用户看到的仍是跳到最左；
 * 4. **滚动状态提升**：目标项不在视口内时 LazyList 里根本没这个节点，
 *    `requestFocus` 必然失败 —— 调用方必须显式传入 `state = rememberLazyListState()`。
 */
object TvFocusMemory {
    private const val TAG = "TvFocusMemory"

    /** 恢复失败时的最大重试帧数（目标节点可能尚未完成 measure） */
    private const val MAX_RESTORE_FRAMES = 12

    /** 稳定 key → 该 key 对应节点的 FocusRequester */
    private val requesters = java.util.concurrent.ConcurrentHashMap<String, FocusRequester>()

    /**
     * 分层记忆：scope → (key)。
     *
     * scope 用 `::` 分隔层级，例如 `mine` 与 `mine::周杰伦`。
     * 下钻时写入子 scope，**不会**覆盖父 scope 的记忆，因此返回时能精确还原。
     */
    private val memoryByScope = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 恢复过程中置位：避免「恢复动作自身触发的焦点变化」污染记忆 */
    @Volatile
    private var restoring = false

    fun register(key: String, requester: FocusRequester) {
        if (key.isBlank()) return
        requesters[key] = requester
    }

    /**
     * 注销 —— **仅在当前注册者就是自己时才删除**。
     *
     * LazyList 滚动/重排时会出现「新节点先注册、旧节点后 dispose」的顺序，
     * 若无条件按 key 删除，旧节点的 dispose 会把新节点的注册抹掉，
     * 导致该 key 永久无法恢复。
     */
    fun unregister(key: String, requester: FocusRequester) {
        if (key.isBlank()) return
        requesters.remove(key, requester)
    }

    fun rememberFocus(scope: String, key: String) {
        if (scope.isBlank() || key.isBlank()) return
        if (restoring) return   // 恢复动作自身引起的焦点变化不记入记忆
        memoryByScope[scope] = key
    }

    fun rememberedKey(scope: String): String? = memoryByScope[scope]

    /** 清空某层级及其所有子层级的记忆（例如该页面整体退出时） */
    fun clearScope(scope: String) {
        val targets = memoryByScope.keys.filter { it == scope || it.startsWith("$scope::") }
        targets.forEach { memoryByScope.remove(it) }
    }

    /**
     * 把焦点恢复到 [scope]（或它的父层级）记住的那一项。
     *
     * 之所以要「逐级向上回退」：下钻视图刚销毁时，子 scope 的目标往往也已不存在，
     * 此时应回到父 scope 记住的位置（即用户点击的那一项）。
     *
     * 恢复过程按帧重试：Compose 的目标节点在切换当帧可能尚未 measure，
     * 立即 requestFocus 会抛 "FocusRequester is not initialized"。
     */
    suspend fun restoreFocus(scope: String): Boolean {
        var scopeCursor = scope
        while (true) {
            val key = memoryByScope[scopeCursor]
            if (key != null && requesters.containsKey(key)) {
                if (awaitFocus(key)) return true
            }
            val parent = scopeCursor.substringBeforeLast("::", "")
            if (parent.isBlank()) break
            scopeCursor = parent
        }
        Log.i(TAG, "restoreFocus: scope=$scope 无可恢复目标（目标可能已不存在）")
        return false
    }

    /** 按帧重试 requestFocus，直到成功或超过帧数上限 */
    private suspend fun awaitFocus(key: String): Boolean {
        restoring = true
        try {
            repeat(MAX_RESTORE_FRAMES) { attempt ->
                val requester = requesters[key] ?: return false
                val ok = try {
                    requester.requestFocus()
                    true
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    Log.i(TAG, "restoreFocus: key=$key 已恢复（第 ${attempt + 1} 帧）")
                    return true
                }
                withFrameNanos { }
            }
            return false
        } finally {
            // 再等一帧才允许记录记忆，否则恢复动作本身触发的那次 onFocusChanged
            // 会把「错误落点」写成新记忆
            withFrameNanos { }
            restoring = false
        }
    }
}

/**
 * 焦点作用域：由组合树自然嵌套，避免全局单值在 AnimatedContent 过渡期互相覆盖。
 *
 * 约定：scope 用 `::` 表示层级，例如根页面 `mine`，其下钻视图为 `mine::周杰伦`。
 */
val LocalTvFocusScope = compositionLocalOf { "root" }

/** 在某个层级上追加作用域名，返回新的作用域名 */
fun tvFocusChildScope(parent: String, child: String): String =
    if (parent.isBlank() || parent == "root") child else "$parent::$child"

/**
 * 声明一个焦点作用域，并在其内部提供 [LocalTvFocusScope]。
 *
 * 用法：
 * ```
 * TvFocusScope("mine") {
 *     // 这里的可聚焦节点默认属于 mine 作用域
 *     TvFocusScope("周杰伦") { ... }   // 子作用域 -> mine::周杰伦
 * }
 * ```
 */
@Composable
fun TvFocusScope(name: String, content: @Composable () -> Unit) {
    val parent = LocalTvFocusScope.current
    val scope = remember(parent, name) { tvFocusChildScope(parent, name) }
    CompositionLocalProvider(LocalTvFocusScope provides scope) { content() }
}

/**
 * 焦点恢复触发器：当 [trigger] 变化（视图/子视图切换）时，恢复 [scope] 记住的焦点。
 *
 * @param trigger 决定「当前显示哪棵子树」的那个状态，例如 `activeSubViewTitle`
 * @param scope   要恢复的作用域；通常直接传 `LocalTvFocusScope.current`
 */
@Composable
fun TvRestoreFocusOnChange(trigger: Any?, scope: String = LocalTvFocusScope.current) {
    // **首次组合不动作**：LaunchedEffect 会在进入组合时立即执行一次，
    // 若此时就去恢复焦点，会与「页面自己安排的初始落点」「顶部 Tab 的默认焦点」抢焦点；
    // 更糟的是恢复动作本身会引发一次 focus 变化，可能再次触发本 effect，
    // 形成「恢复 → 焦点变 → 再恢复」的自激循环，表现为页面完全没有光标。
    // 因此只在 trigger **真正发生变化**（视图切换）时才恢复。
    var lastTrigger by remember { mutableStateOf<Any?>(UninitializedTrigger) }
    LaunchedEffect(trigger, scope) {
        if (lastTrigger === UninitializedTrigger) {
            lastTrigger = trigger
            return@LaunchedEffect   // 首次组合：只登记，不抢焦点
        }
        if (lastTrigger == trigger) return@LaunchedEffect
        lastTrigger = trigger
        // 不要在这里 clearFocus(force = true)：当触发原因是「原焦点节点被移出组合」时，
        // Compose 已把焦点交给默认节点；再强制清空会在恢复失败时留下「完全没有焦点」，
        // 遥控器需要多按一次方向键才能重新获得焦点 —— 比跳到左上角更糟。
        TvFocusMemory.restoreFocus(scope)
    }
}

/** 用于区分「从未登记」与「登记为 null」的哨兵值 */
private val UninitializedTrigger = Any()

/**
 * 进入侧锚点：当 [trigger] 变为非 null（进入新视图）时，把焦点交给本锚点。
 *
 * **这是修复的关键一环**：被点击的那个节点此刻已经销毁，仅仅「恢复记忆」必然失败，
 * 用户看到的仍是跳到最左边。因此进入新视图时必须显式指定落点。
 *
 * 返回的 Modifier 需挂到该视图内**最具语义的第一个可聚焦元素**上
 * （例如下钻视图的返回键、结果列表的首行）。
 */
@Composable
fun Modifier.tvFocusEntryAnchor(trigger: Any?): Modifier {
    val requester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        isFocused = false
        // 关键：在刚进入新视图/数据刚就绪时，目标节点尚未完成 Measure/Layout 与焦点树关联，
        // 此时盲目 requestFocus 会静默失效（不抛异常）。
        // 必须在与帧同步（withFrameNanos / delay）后持续重试，直到本节点真正获得焦点！
        for (attempt in 0..15) {
            withFrameNanos { }
            if (isFocused) return@LaunchedEffect
            try {
                requester.requestFocus()
            } catch (_: Exception) {
            }
            if (isFocused) return@LaunchedEffect
            if (attempt >= 3) {
                delay(30)
                if (isFocused) return@LaunchedEffect
                runCatching { requester.requestFocus() }
            }
        }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { state ->
            if (state.isFocused || state.hasFocus) {
                isFocused = true
            }
        }
}
