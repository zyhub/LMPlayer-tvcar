package com.lm.player.core.designsystem.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.security.LogRedactor

/**
 * 崩溃自诊断报告弹窗（对齐手机端程序崩溃日志风格，带一键复制与大屏焦点适配）
 */
@Composable
fun CrashReportDialog(
    report: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val dimensions = LocalAppDimensions.current
    val copyFocusRequester = remember { FocusRequester() }
    val sanitizedReport = remember(report) { LogRedactor.text(report) }

    LaunchedEffect(Unit) {
        // TV 遥控器确定性焦点，优先聚焦在「一键复制」上，按 OK 即可复制
        try {
            kotlinx.coroutines.delay(80L)
            copyFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.86f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                Text(
                    text = "程序崩溃日志",
                    fontSize = dimensions.sectionTitleSize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "上次运行异常退出，完整日志如下。可一键复制后反馈。",
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
                            text = sanitizedReport,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .tvButtonFocusable(shape = RoundedCornerShape(14.dp))
                    ) {
                        Text("关闭", fontSize = dimensions.bodySize)
                    }

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("LMPlayer Crash Log", sanitizedReport))
                            Toast.makeText(context, "崩溃日志已复制", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                        modifier = Modifier
                            .weight(1.3f)
                            .focusRequester(copyFocusRequester)
                            .tvButtonFocusable(
                                shape = RoundedCornerShape(14.dp),
                                focusedBorderColor = Color(0xFFFFD60A)
                            )
                    ) {
                        Text(
                            text = "一键复制全部日志",
                            fontSize = dimensions.bodySize,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
