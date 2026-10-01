package com.lm.player.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale

/**
 * 崩溃自诊断报告弹窗
 *
 * TV / 车机端无法取 logcat，闪退后把上一次的完整堆栈原样呈现给用户，
 * 用户截图回传即可定位问题。展示完由调用方标记已读，不会每次启动重复打扰。
 */
@Composable
fun CrashReportDialog(
    report: String,
    onDismiss: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 遥控器必须有确定性焦点，否则用户无法用 OK 键关闭
        try {
            kotlinx.coroutines.delay(80L)
            closeFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                Text(
                    text = "上次运行发生闪退",
                    fontSize = dimensions.sectionTitleSize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "以下堆栈已自动保存在本机，请截图发给开发者用于定位；关闭后不再自动弹出。",
                    fontSize = dimensions.captionSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Box(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(
                            text = report,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = AppleRed.copy(alpha = 0.16f),
                        modifier = Modifier
                            .focusRequester(closeFocusRequester)
                            .tvFocusable(
                                shape = RoundedCornerShape(18.dp),
                                focusedScale = 1.08f,
                                onClick = onDismiss
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = dimensions.scale(22.dp), vertical = dimensions.scale(9.dp)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "我知道了",
                                fontSize = dimensions.itemTitleSize,
                                fontWeight = FontWeight.Bold,
                                color = AppleRed
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                }
            }
        }
    }
}
