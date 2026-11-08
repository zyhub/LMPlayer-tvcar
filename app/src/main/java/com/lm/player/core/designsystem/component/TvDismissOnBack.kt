package com.lm.player.core.designsystem.component

import android.os.Build
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

/**
 * TV 遥控器返回键关闭弹窗（Android 12 及以下的按键派发路径；13+ 见下方 [WindowBackCallback]）。
 *
 * 背景：Compose 1.6 的根节点把 BACK / ESC 映射为焦点 `FocusDirection.Exit`。弹窗里光标停在
 * 某个按钮上时（按钮外层几乎都有 LazyColumn / focusGroup），按返回只会把焦点「退」到外层分组并**吞掉**
 * 这次按键，Dialog / Popup 自己的返回处理永远收不到 —— 表现为「弹窗打开后按返回没有任何反应」。
 *
 * 这里在弹窗内容根部用 onPreviewKeyEvent（先于焦点退出处理）接管：按下记一次，抬起时关闭弹窗。
 * 只认「在本窗口内按下」的返回，避免打开弹窗那一下的残留抬起事件把弹窗立刻关掉。
 */
fun Modifier.tvDismissOnBack(enabled: Boolean = true, onDismiss: () -> Unit): Modifier = composed {
    val dismiss by rememberUpdatedState(onDismiss)
    val downSeen = remember { booleanArrayOf(false) }
    onPreviewKeyEvent { event ->
        if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> downSeen[0] = true
            KeyEventType.KeyUp -> {
                if (downSeen[0] && enabled) dismiss()
                downSeen[0] = false
            }
        }
        true
    }
}

/** 替代 `androidx.compose.ui.window.Dialog`：各文件通过 `import ...TvDialog as Dialog` 无改动接入 */
@Composable
fun TvDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Box(Modifier.tvDismissOnBack(enabled = properties.dismissOnBackPress, onDismiss = onDismissRequest)) {
            content()
        }
    }
}

/** 替代 Material3 `AlertDialog`（confirmButton 版本） */
@Composable
fun TvAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.tvDismissOnBack(enabled = properties.dismissOnBackPress, onDismiss = onDismissRequest),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}

/** 替代 Material3 `DropdownMenu`（下载 / 加歌单 / 主题 / 睡眠定时等浮层菜单） */
@Composable
fun TvDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.tvDismissOnBack(enabled = properties.dismissOnBackPress, onDismiss = onDismissRequest),
        offset = offset,
        scrollState = scrollState,
        properties = properties
    ) {
        // 必须在浮层窗口内部注册：LocalView 此时是浮层自己的窗口
        WindowBackCallback(enabled = properties.dismissOnBackPress, onBack = onDismissRequest)
        content()
    }
}

/**
 * 浮层（Popup）窗口的系统返回回调。
 *
 * **真正根因**：清单开启了 `android:enableOnBackInvokedCallback="true"`。Android 13+ 上系统不再把返回键
 * 作为按键派发给窗口，而是交给「该窗口注册的 OnBackInvokedCallback」；没注册回调的窗口，返回键会被系统直接吞掉。
 * Activity 与 Dialog（ComponentDialog）都会自动注册，但 Compose 1.6 的 Popup（DropdownMenu 的载体）
 * 不会注册 —— 于是下载 / 加歌单 / 主题 / 睡眠定时 / 音频参数这些浮层菜单按返回毫无反应。
 * Android 12 及以下仍走按键派发，由 Popup 自身的 dispatchKeyEvent 处理，这里不做任何事。
 */
@Composable
fun WindowBackCallback(enabled: Boolean = true, onBack: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val view = LocalView.current
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(view, enabled) {
        val handle = if (enabled) Api33BackCallback.register(view) { currentOnBack() } else null
        onDispose { handle?.invoke() }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object Api33BackCallback {
    /** 注册到 [view] 所在窗口的返回分发器，返回反注册函数；拿不到分发器时返回 null */
    fun register(view: View, onBack: () -> Unit): (() -> Unit)? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback { onBack() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        return { runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) } }
    }
}
