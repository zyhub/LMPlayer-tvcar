package com.lm.player.core.designsystem.component

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * TV / 车机遥控器下的输入框按键处理。
 *
 * Compose 的输入框获得焦点后会吞掉方向键（用来移动光标），返回键也只作用于输入法，
 * 表现为「上下键移不出输入框、返回键失灵」。这里统一接管：
 * - 上 / 下：把焦点移到上一个 / 下一个可聚焦控件（找不到时按 Previous / Next 再试一次）；
 * - 返回：软键盘显示时先收起键盘；键盘已收起时释放输入框焦点并**不消费**事件，交给弹窗 / 页面处理返回。
 * 左 / 右仍交给输入框移动光标。
 *
 * @param exitOnRight 为 true 时，右键直接把焦点移到右侧控件（如输入框右边的「粘贴」「搜索」按钮）。
 *   单行输入框右侧紧挨着按钮的场景必须打开，否则遥控器永远走不到这些按钮。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
fun Modifier.tvTextFieldKeys(exitOnRight: Boolean = false): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    onPreviewKeyEvent { event ->
        when (event.key) {
            Key.DirectionRight -> {
                if (!exitOnRight) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    keyboard?.hide()
                    if (!focusManager.moveFocus(FocusDirection.Right)) focusManager.moveFocus(FocusDirection.Next)
                }
                true
            }
            Key.DirectionDown -> {
                if (event.type == KeyEventType.KeyDown) {
                    keyboard?.hide()
                    if (!focusManager.moveFocus(FocusDirection.Down)) focusManager.moveFocus(FocusDirection.Next)
                }
                true
            }
            Key.DirectionUp -> {
                if (event.type == KeyEventType.KeyDown) {
                    keyboard?.hide()
                    if (!focusManager.moveFocus(FocusDirection.Up)) focusManager.moveFocus(FocusDirection.Previous)
                }
                true
            }
            Key.Back, Key.Escape -> {
                if (imeVisible) {
                    if (event.type == KeyEventType.KeyUp) keyboard?.hide()
                    true
                } else {
                    if (event.type == KeyEventType.KeyDown) focusManager.clearFocus()
                    false
                }
            }
            else -> false
        }
    }
}
