package com.lm.player.feature.settings

import com.lm.player.core.designsystem.component.TvAlertDialog as AlertDialog
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.util.StoragePermissionHelper
import com.lm.player.core.util.StorageVolumeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 诊断结果中的一行：标签、值、是否正常 (null = 仅信息) */
private data class DiagLine(val label: String, val value: String, val ok: Boolean?)

/**
 * 存储权限诊断 (第十八节)：一屏列出与本地下载 / 扫描相关的所有系统状态，便于车机上截图排查。
 * 所有探测 (写探针文件、列目录) 都在 IO 线程执行。
 */
@Composable
fun StorageDiagnosticsDialog(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val lines by produceState(initialValue = null as List<DiagLine>?, key1 = refreshKey) {
        value = null
        value = withContext(Dispatchers.IO) { collectDiagnostics(context) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存储权限诊断", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val list = lines
                if (list == null) {
                    Text("检测中…", fontSize = 13.sp)
                } else {
                    list.forEach { line ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = line.label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(120.dp)
                            )
                            Text(
                                text = line.value,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = if (line.value.startsWith("/")) FontFamily.Monospace else FontFamily.Default,
                                color = when (line.ok) {
                                    true -> Color(0xFF34C759)
                                    false -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(
                        text = "如有问题，请把本页截图发给开发者。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { refreshKey++ },
                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                ) { Text("重新检测") }
                Button(
                    onClick = {
                        onRequestPermission()
                        refreshKey++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                ) { Text("申请权限") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                ) { Text("应用权限设置") }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(12.dp))
                ) { Text("关闭") }
            }
        }
    )
}

private fun yesNo(v: Boolean, yes: String = "已授予", no: String = "未授予") = if (v) yes else no

private fun collectDiagnostics(context: Context): List<DiagLine> {
    val out = ArrayList<DiagLine>()
    val sdk = Build.VERSION.SDK_INT
    out += DiagLine("系统版本", "Android ${Build.VERSION.RELEASE} (API $sdk)", null)
    out += DiagLine("设备", "${Build.MANUFACTURER} ${Build.MODEL}", null)

    StoragePermissionHelper.readPermissions().forEach { p ->
        val g = StoragePermissionHelper.isGranted(context, p)
        out += DiagLine(StoragePermissionHelper.permissionLabel(p) + "权限", yesNo(g), g)
    }
    if (sdk <= Build.VERSION_CODES.Q) {
        val g = StoragePermissionHelper.isGranted(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        out += DiagLine("写入存储权限", yesNo(g), g)
    }
    if (sdk >= Build.VERSION_CODES.R) {
        val g = StoragePermissionHelper.hasAllFilesAccess()
        out += DiagLine("所有文件访问", yesNo(g), g)
    }
    StoragePermissionHelper.isLegacyStorageActive()?.let { legacy ->
        out += DiagLine("旧版存储模式", yesNo(legacy, "已生效", "未生效"), legacy)
    }
    val saf = StoragePermissionHelper.canOpenDocumentTree(context)
    out += DiagLine("系统文件夹选择器", yesNo(saf, "存在", "不存在"), saf)
    val details = StoragePermissionHelper.canOpenAppDetails(context)
    out += DiagLine("应用权限设置页", yesNo(details, "存在", "不存在"), details)

    val volumes = runCatching { StorageVolumeHelper.getMountedVolumes(context) }.getOrDefault(emptyList())
    volumes.forEach { vol ->
        val readable = StoragePermissionHelper.probeReadable(vol.file)
        // 只对卷根目录做写入探测：在根目录留探针文件会立刻删除
        val writable = StoragePermissionHelper.probeWritable(vol.file)
        out += DiagLine(
            vol.name,
            "${vol.path}\n读取：${yesNo(readable, "可以", "不可以")}　写入：${yesNo(writable, "可以", "不可以")}",
            readable
        )
    }
    StoragePermissionHelper.appSpecificMusicDirs(context).forEach { dir ->
        val writable = StoragePermissionHelper.probeWritable(dir)
        val onUsb = StoragePermissionHelper.isOnRemovableVolume(dir.absolutePath)
        out += DiagLine(
            if (onUsb) "U 盘专属目录" else "内部专属目录",
            "${dir.absolutePath}\n写入：${yesNo(writable, "可以", "不可以")}",
            writable
        )
    }
    return out
}
