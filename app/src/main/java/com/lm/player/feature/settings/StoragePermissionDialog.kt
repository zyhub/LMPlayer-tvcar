package com.lm.player.feature.settings

import com.lm.player.core.designsystem.component.TvAlertDialog as AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.theme.AppleRed

/**
 * 存储权限被拒绝时的说明弹窗 (第十八节)：说明缺了什么、会影响什么，
 * 并提供「打开应用权限设置」与「仍然继续」(例如改走系统文件夹选择器或应用专属目录)。
 */
@Composable
fun StoragePermissionDialog(
    message: String,
    showContinue: Boolean,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null, tint = AppleRed) },
        title = { Text("需要存储权限", fontWeight = FontWeight.Bold) },
        text = { Text(message, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
            ) {
                Text("打开应用权限设置")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showContinue) {
                    TextButton(
                        onClick = onContinue,
                        modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                    ) {
                        Text("仍然继续")
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                ) {
                    Text("取消")
                }
            }
        }
    )
}

/**
 * 更换下载目录时选择用哪种选择器：系统文件夹选择器 (可授权任意目录，含 U 盘) 或内置选择器
 * (可直接选「U 盘专属目录」等一定可写的位置，不依赖车机系统组件)。
 */
@Composable
fun StorageDirSourceChooserDialog(
    onSystemPicker: () -> Unit,
    onBuiltInPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下载目录的方式", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onBuiltInPicker,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("内置选择器", fontWeight = FontWeight.Bold)
                        Text("浏览内部存储与 U 盘，含「专属目录」快捷项", fontSize = 11.sp)
                    }
                }
                OutlinedButton(
                    onClick = onSystemPicker,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("系统文件夹选择器", fontWeight = FontWeight.Bold)
                        Text("由系统授权，可选 U 盘普通文件夹", fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
            ) {
                Text("取消")
            }
        }
    )
}
