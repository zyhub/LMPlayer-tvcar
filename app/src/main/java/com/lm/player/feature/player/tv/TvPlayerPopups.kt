package com.lm.player.feature.player.tv

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.TvDropdownMenu
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.UnifiedSong
import java.io.File

/*
 * 播放页的两个弹出窗口（2026-11-13，照手机端的样式移植）：
 * - 下载窗口：标题 + 歌名 → 已有文件信息（本地 / 服务器）→ 存储目标一排小胶囊 → 音质 2×2 方块，点方块即开始下载；
 * - 歌曲信息窗口：音质等级 + 「音频参数」→ 格式 / 码率 / 大小三格 → 来源 / 位置 / 输出。
 * 手机端用液态玻璃面板，车机 Android 10 用不了，这里用播放页的面板配色；遥控器每一项都可获焦。
 */

private val Green = Color(0xFF34C759)

// =====================================================================================
// 下载窗口
// =====================================================================================

/** 播放页的下载窗口：内容与全局下载窗口同一份（[com.lm.player.core.designsystem.component.DownloadChoicePanel]），只换成播放页配色 */
@Composable
fun TvDownloadPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong,
    isServerConnected: Boolean,
    hasLocal: Boolean,
    hasServer: Boolean,
    onConfirm: (DownloadTarget, AudioQuality) -> Unit
) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    TvDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(dims.scale(330.dp)).background(colors.panel)
    ) {
        com.lm.player.core.designsystem.component.DownloadChoicePanel(
            song = song,
            isServerConnected = isServerConnected,
            hasLocal = hasLocal,
            hasServer = hasServer,
            onConfirm = onConfirm,
            onDismiss = onDismissRequest,
            colors = com.lm.player.core.designsystem.component.DownloadPanelColors(
                text = colors.text,
                textSecondary = colors.textSecondary,
                focus = colors.accent,
                fill = colors.subtleFill
            )
        )
    }
}

// =====================================================================================
// 歌曲信息窗口（音频参数）
// =====================================================================================

@Composable
fun TvSongInfoPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong
) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    val context = LocalContext.current
    val quality = rememberQualityInfo(song)
    val tierColor = tvQualityColor(quality.tier, colors.isDark)
    val isLocal = !song.localFilePath.isNullOrBlank()
    // 格式 / 码率 / 大小：本地按文件真实规格，其它按音质判定
    val stats = remember(expanded, song.id, song.localFilePath, quality) {
        if (isLocal) {
            val (ext, kbps, size) = com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
            Triple(ext, "${kbps.coerceAtLeast(128)} kbps", size.ifBlank { "—" })
        } else {
            val fmt = quality.label.substringAfter(" ").substringBefore(" ").ifBlank { song.format.uppercase() }
            val q = if (quality.tier >= 2) AudioQuality.Q_FLAC else AudioQuality.Q_320K
            Triple(song.format.uppercase().ifBlank { fmt }, quality.detail.substringAfterLast("· ").ifBlank { "${q.bitrate} kbps" }, q.estimateSizeText(song))
        }
    }
    val isOnline = song.id.startsWith("lemon_online_") || song.serverId == "lemon_online" || song.streamUrl.startsWith("lemon_online://")
    val location = remember(song.id, song.localFilePath, song.streamUrl) {
        when {
            isLocal -> File(song.localFilePath!!).parent ?: song.localFilePath
            isOnline -> "在线流媒体"
            else -> {
                var nas = ""
                if (song.streamUrl.contains("path=")) runCatching {
                    val p = android.net.Uri.parse(song.streamUrl).getQueryParameter("path")
                    if (!p.isNullOrBlank()) {
                        val d = java.net.URLDecoder.decode(p, "UTF-8")
                        nas = if (d.contains("/")) d.substringBeforeLast("/") + "/" else d
                    }
                }
                if (nas.isBlank()) "服务器曲库" else "服务器：" + (if (nas.startsWith("/")) nas else "/$nas")
            }
        }
    }
    val output = remember(expanded) { currentOutputName(context) }

    TvDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(dims.scale(340.dp)).background(colors.panel)
    ) {
        Column(modifier = Modifier.padding(horizontal = dims.scale(16.dp), vertical = dims.scale(6.dp))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tierColor.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        when (quality.tier) { 3 -> "Hi-Res"; 2 -> "无损"; 1 -> "极高"; else -> "标准" },
                        fontSize = dims.badgeSize, fontWeight = FontWeight.Bold, color = tierColor
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("音频参数", fontSize = dims.bodySize, fontWeight = FontWeight.Bold, color = colors.text)
            }
            Spacer(Modifier.height(dims.scale(10.dp)))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCell("格式", stats.first)
                StatCell("码率", stats.second)
                StatCell("大小", stats.third)
            }
            Spacer(Modifier.height(dims.scale(8.dp)))
            if (isOnline) SpecLine("来源", "在线音源", valueColor = AppleRed)
            SpecLine("位置", location, maxLines = 2)
            SpecLine("输出", output)
            Spacer(Modifier.height(dims.scale(6.dp)))
            // 遥控器需要一个可获焦的项：「关闭」
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dims.scale(40.dp))
                    .tvFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f, focusedBorderColor = colors.accent, onClick = onDismissRequest)
                    .background(colors.subtleFill, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("关闭", fontSize = dims.captionSize, fontWeight = FontWeight.Medium, color = colors.text)
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(caption: String, value: String) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.subtleFill)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = dims.bodySize, fontWeight = FontWeight.Bold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(caption, fontSize = dims.badgeSize, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun SpecLine(label: String, value: String, maxLines: Int = 1, valueColor: Color? = null) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = if (maxLines > 1) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(label, fontSize = dims.captionSize, color = colors.textSecondary, modifier = Modifier.width(dims.scale(44.dp)))
        Text(value, fontSize = dims.captionSize, fontWeight = FontWeight.Medium, color = valueColor ?: colors.text, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** 当前音频输出（蓝牙设备名 / 有线 / 本机扬声器） */
private fun currentOutputName(context: Context): String = runCatching {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val bt = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    val wired = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    when {
        bt != null -> "蓝牙：${bt.productName}"
        wired != null -> "有线耳机"
        else -> "本机扬声器"
    }
}.getOrDefault("本机扬声器")
