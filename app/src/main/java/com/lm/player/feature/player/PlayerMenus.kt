package com.lm.player.feature.player

import com.lm.player.core.designsystem.component.TvDropdownMenu as DropdownMenu
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.lm.player.core.designsystem.component.RecomposeIsolation
import com.lm.player.core.designsystem.component.AddToPlaylistDialog
import com.lm.player.core.designsystem.component.AddToPlaylistDropdownMenu
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.component.tvDismissOnBack
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SpecBadgeBitrateColor
import com.lm.player.core.designsystem.theme.SpecBadgeQualityColor
import com.lm.player.core.designsystem.theme.SpecBadgeSizeColor
import com.lm.player.core.designsystem.theme.specBadgeBorder
import com.lm.player.core.designsystem.theme.specBadgeContainer
import com.lm.player.core.model.*
import com.lm.player.core.media.PlaybackQueueManager
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// 2026-11-12 播放页重做：旧 FullscreenPlayerSheet 已由 feature/player/tv/TvPlayerScreen 取代，
// 这里只保留新播放页「更多」面板继续使用的弹出菜单（睡眠定时 / 音质信息 / 音频输出 / 主题选择）。


private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
}

@Composable
private fun SpecRowItem(
    label: String,
    value: String,
    isBold: Boolean = false,
    isPath: Boolean = false,
    valueColor: Color? = null
) {
    val primaryTextColor = if (MaterialTheme.colorScheme.background.red < 0.5f) Color.White else Color.Black
    val secondaryTextColor = if (MaterialTheme.colorScheme.background.red < 0.5f) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (isPath) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = secondaryTextColor,
            modifier = Modifier.width(68.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor ?: primaryTextColor,
            textAlign = TextAlign.End,
            maxLines = if (isPath) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SleepTimerDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    activeTimerMinutes: Int,
    /** 剩余秒数：由宿主每秒更新，用于展示真实倒计时（0 表示未设置或已到期） */
    remainingSeconds: Long = 0L,
    onSelectTimer: (Int) -> Unit
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                kotlinx.coroutines.delay(60L)
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 220.dp, max = 280.dp)
    ) {
        Text(
            text = if (activeTimerMinutes > 0 && remainingSeconds > 0L) {
                val m = remainingSeconds / 60
                val s = remainingSeconds % 60
                "将在 %d:%02d 后停止播放".format(java.util.Locale.US, m, s)
            } else if (activeTimerMinutes > 0) {
                "已设置：${activeTimerMinutes}分钟后停止"
            } else if (activeTimerMinutes == -1) {
                "已设置：播完当前曲后停止"
            } else {
                "睡眠定时关闭"
            },
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

        val presets = listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟", 90 to "90 分钟", -1 to "播完当前曲")
        presets.forEachIndexed { index, (min, label) ->
            val isSelected = activeTimerMinutes == min
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(24.dp)) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = label,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                },
                onClick = {
                    onSelectTimer(min)
                    onDismissRequest()
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                    .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(10.dp))
            )
        }

        if (activeTimerMinutes != 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
            DropdownMenuItem(
                text = {
                    Text(
                        text = "关闭定时器",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                },
                onClick = {
                    onSelectTimer(0)
                    onDismissRequest()
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(10.dp))
            )
        }
    }
}

@Composable
fun AudioSpecsDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong
) {
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                kotlinx.coroutines.delay(60L)
                closeFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val context = LocalContext.current
    val isLocal = !song.localFilePath.isNullOrBlank()
    val streamQualityVer by com.lm.player.core.network.LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    val (formatStr, effectiveBitRate, sizeText) = remember(song.id, song.localFilePath, song.format, song.bitRate, song.serverId, song.streamUrl, isLocal, streamQualityVer) {
        val isOnlineTrial = !isLocal && (
            song.serverId == "lemon_online" ||
            song.id.startsWith("lemon_online_") ||
            song.streamUrl.contains("/api/play/proxy")
        )
        when {
            isLocal -> com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
            isOnlineTrial -> {
                val q = AudioQuality.fromKey(com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context))
                Triple(q.format.uppercase(), q.bitrate, q.estimateSizeText(song))
            }
            else -> com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
        }
    }
    val isLossless = formatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || effectiveBitRate >= 800 || song.isHiRes
    val isHires = song.isHiRes || (isLossless && effectiveBitRate >= 1200) || (song.sampleRate ?: 0) >= 48000 || (song.bitDepth ?: 0) >= 24
    val qualityTag = if (isHires) "Hi-Res 无损母带" else if (isLossless) "无损高品质" else if (effectiveBitRate >= 320) "极高品质音频" else "标准音频"

    val locationText: String = if (isLocal && !song.localFilePath.isNullOrBlank()) {
        val f = java.io.File(song.localFilePath)
        f.parent ?: song.localFilePath
    } else {
        val stream = song.streamUrl
        var nasPath = ""
        if (stream.contains("path=")) {
            try {
                val uri = android.net.Uri.parse(stream)
                val pathParam = uri.getQueryParameter("path")
                if (!pathParam.isNullOrBlank()) {
                    val decoded = java.net.URLDecoder.decode(pathParam, "UTF-8")
                    val parentDir = if (decoded.contains("/")) decoded.substringBeforeLast("/") + "/" else decoded
                    nasPath = parentDir
                }
            } catch (_: Exception) {}
        }
        if (nasPath.isBlank() && !song.relativeFolderPath.isNullOrBlank()) {
            val folder = song.relativeFolderPath.trim().trimEnd('/')
            nasPath = "$folder/"
        }
        if (nasPath.isBlank()) {
            val artist = song.artist.ifBlank { "未知歌手" }
            val album = song.album.ifBlank { "单曲" }
            nasPath = "/音乐/$artist/$album/"
        }
        "NAS: $nasPath"
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .widthIn(min = 270.dp, max = 330.dp)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFD4AF37).copy(alpha = 0.2f)) {
                    Text(if (isLossless) "Hi-Res" else "Audio", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4AF37), modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("音频参数详情", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .size(26.dp)
                    .focusRequester(closeFocusRequester)
                    .tvButtonFocusable(shape = RoundedCornerShape(13.dp))
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))

        val isOnlineSong = song.id.startsWith("lemon_online_") ||
            song.serverId == "lemon_online" ||
            song.streamUrl.startsWith("lemon_online://") ||
            (com.lm.player.core.media.DownloadRequestPlanner.hasRemoteSource(song) && !isLocal)

        val platformKey = when {
            song.id.startsWith("lemon_online_") -> song.id.removePrefix("lemon_online_").substringBefore("_")
            else -> {
                val rawJson = song.rawMetaJson ?: song.relativeFolderPath
                if (!rawJson.isNullOrBlank()) {
                    runCatching {
                        val obj = org.json.JSONObject(rawJson)
                        obj.optString("source").ifBlank { obj.optString("platform") }
                    }.getOrNull().orEmpty()
                } else ""
            }
        }.ifBlank { "kw" }

        val platformName = when (platformKey.lowercase()) {
            "tx" -> "QQ音乐"
            "kw" -> "酷我音乐"
            "wy" -> "网易云音乐"
            "kg" -> "酷狗音乐"
            "mg" -> "咪咕音乐"
            "bd" -> "百度音乐"
            else -> platformKey.uppercase()
        }

        val sourceName = runCatching {
            val rawJson = song.rawMetaJson ?: song.relativeFolderPath
            if (!rawJson.isNullOrBlank()) {
                val obj = org.json.JSONObject(rawJson)
                obj.optString("sourceName").ifBlank { obj.optString("scriptName") }
            } else ""
        }.getOrNull()?.ifBlank { null } ?: "官方音源"

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isOnlineSong) {
                SpecRowItem("解析来源", "$sourceName / ${platformName}解析", isBold = true, valueColor = AppleRed)
            }
            SpecRowItem("音质等级", qualityTag, valueColor = if (isHires || isLossless) Color(0xFFD4AF37) else AppleRed)
            if (song.bitDepth != null || song.sampleRate != null) {
                val depth = song.bitDepth ?: 24
                val rateKhz = (song.sampleRate ?: 96000) / 1000
                SpecRowItem("声学精度", "${depth}bit / ${rateKhz}kHz", isBold = true, valueColor = Color(0xFFFFD700))
            }
            SpecRowItem("编码格式", formatStr, isBold = true)
            SpecRowItem("音频码率", "$effectiveBitRate kbps")
            SpecRowItem("文件大小", sizeText, isBold = true, valueColor = AppleRed)
            SpecRowItem("存储位置", locationText, isPath = true)
            SpecRowItem("播放通道", if (isLocal) "本地硬件直解" else "柠檬无损推流", valueColor = AppleRed)
        }
    }
}

/**
 * 播放界面主题选择下拉列表菜单（如同“收藏到歌单”的列表选择方式）
 */
@Composable
fun PlayerThemeDropdownMenu(
    expanded: Boolean,
    currentTheme: com.lm.player.core.designsystem.theme.PlayerThemeStyle,
    onDismissRequest: () -> Unit,
    onSelectTheme: (com.lm.player.core.designsystem.theme.PlayerThemeStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocusRequester = remember { FocusRequester() }
    val selectableThemes = remember {
        listOf(
            com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN,
            com.lm.player.core.designsystem.theme.PlayerThemeStyle.KARAOKE_COVER,
            com.lm.player.core.designsystem.theme.PlayerThemeStyle.NETEASE_TV_COVER
        )
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            try {
                kotlinx.coroutines.delay(60L)
                firstFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 270.dp, max = 330.dp)
    ) {
        Text(
            text = "播放界面主题",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
        Text(
            text = "选择您偏好的全屏播放视觉样式",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        selectableThemes.forEachIndexed { index, themeStyle ->
            val isSelected = themeStyle == currentTheme
            val iconVec = when (themeStyle) {
                com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN -> Icons.Default.Album
                com.lm.player.core.designsystem.theme.PlayerThemeStyle.KARAOKE_COVER -> Icons.Default.Mic
                com.lm.player.core.designsystem.theme.PlayerThemeStyle.NETEASE_TV_COVER -> Icons.Default.Wallpaper
                else -> Icons.Default.Palette
            }
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) AppleRed.copy(alpha = 0.16f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = iconVec,
                                    contentDescription = null,
                                    tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = themeStyle.displayName,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = themeStyle.description,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = {
                    onSelectTheme(themeStyle)
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                    .tvButtonFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}


