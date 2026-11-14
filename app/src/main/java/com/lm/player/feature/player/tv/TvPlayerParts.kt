package com.lm.player.feature.player.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.UnifiedSong
import java.io.File
import java.util.Locale

/** 金色强调色（与 TV 主界面焦点色一致） */
val TvPlayerGold = Color(0xFFFFC947)

/** 播放页统一的深色胶囊 / 面板底色（手机端液态玻璃在 Android 13 以下的备用样式） */
val TvPlayerPanelColor = Color(0xE6141418)
val TvPlayerPanelBorder = Color(0x26FFFFFF)

/** 文字压在封面上时的柔和投影，保证任何封面颜色上都清晰 */
val TvTextShadow = Shadow(color = Color(0x99000000), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 8f)

@Immutable
data class TvQualityInfo(val label: String, val detail: String, val tier: Int)

/** 音质分级标签的颜色（手机端同款：Hi-Res 金 / 无损 红 / 极高 蓝 / 标准 灰） */
fun tvQualityColor(tier: Int, isDark: Boolean = true): Color = when (tier) {
    3 -> Color(0xFFD4AF37)
    2 -> AppleRed
    1 -> Color(0xFF38BDF8)
    else -> if (isDark) Color.White.copy(alpha = 0.70f) else Color(0xFF636366)
}

/** 音质判定（与旧播放页同一口径：本地文件按真实格式 / 码率，在线试听按当前取流音质） */
@Composable
fun rememberQualityInfo(song: UnifiedSong): TvQualityInfo {
    val context = LocalContext.current
    val streamQualityVer by com.lm.player.core.network.LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    // 2026-11-13 性能优化第 6 项：读文件属性 / 文件头放到 IO 线程。切歌时先按「不读盘」算一次
    // （本地路径不为空就当本地文件、规格取内存里的上次结果或估算），IO 线程核实后再刷新
    val state = androidx.compose.runtime.produceState(
        initialValue = computeQualityInfo(song, context, readDisk = false),
        song.id, song.localFilePath, song.format, song.bitRate, song.streamUrl, streamQualityVer
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            computeQualityInfo(song, context, readDisk = true)
        }
    }
    return state.value
}

private fun computeQualityInfo(song: UnifiedSong, context: android.content.Context, readDisk: Boolean): TvQualityInfo {
    return run {
        val path = song.localFilePath
        val isLocal = !path.isNullOrBlank() && (
            path.startsWith("content://") || !readDisk ||
                runCatching { File(path).let { it.exists() && it.length() > 0L } }.getOrDefault(false)
            )
        val spec = if (song.bitDepth != null && song.sampleRate != null) "${song.bitDepth}bit / ${song.sampleRate!! / 1000}kHz · " else ""
        if (isLocal) {
            val (ext, kbps, _) = if (readDisk) com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
                else com.lm.player.feature.home.peekRealLocalFormatAndSize(song)
                    ?: com.lm.player.feature.home.estimateLocalSpecsWithoutDisk(song)
            val lossless = ext in LOSSLESS || kbps >= 800 || song.isHiRes
            val hires = song.isHiRes || (lossless && kbps >= 1200) || (song.sampleRate ?: 0) >= 48000 || (song.bitDepth ?: 0) >= 24
            val tier = when { hires -> 3; lossless -> 2; kbps >= 320 -> 1; else -> 0 }
            TvQualityInfo(tierLabel(tier, ext), "$spec${kbps.coerceAtLeast(128)} kbps", tier)
        } else {
            val trial = song.id.startsWith("lemon_online_") || song.serverId == "lemon_online" ||
                song.streamUrl.startsWith("lemon_online://") || song.streamUrl.contains("/api/play/proxy")
            if (trial) {
                val q = AudioQuality.fromKey(com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context))
                val tier = when (q) {
                    AudioQuality.Q_HIRES -> 3
                    else -> if (q.format.equals("flac", true)) 2 else if (q.bitrate >= 320) 1 else 0
                }
                TvQualityInfo(tierLabel(tier, q.format.uppercase()), "$spec${q.bitrate} kbps", tier)
            } else {
                val fmt = song.format.uppercase().ifBlank { "FLAC" }
                val lossless = fmt in LOSSLESS || song.bitRate >= 800 || song.isHiRes
                val hires = song.isHiRes || (lossless && song.bitRate >= 1200) || (song.sampleRate ?: 0) >= 48000 || (song.bitDepth ?: 0) >= 24
                val br = if (song.bitRate > 0) song.bitRate else if (hires) 1640 else if (lossless) 960 else 320
                val tier = when { hires -> 3; lossless -> 2; br >= 320 -> 1; else -> 0 }
                TvQualityInfo(tierLabel(tier, fmt), "$spec$br kbps", tier)
            }
        }
    }
}

private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF")

private fun tierLabel(tier: Int, ext: String): String = when (tier) {
    3 -> "Hi-Res $ext"
    2 -> "$ext 无损"
    1 -> "$ext 极高"
    else -> "$ext 标准"
}

/** 音质分级标签（只读展示；详细参数在「更多 → 音质信息」） */
@Composable
fun TvQualityTag(info: TvQualityInfo, fontSize: TextUnit = 13.sp, modifier: Modifier = Modifier) {
    val color = tvQualityColor(info.tier, LocalTvPlayerColors.current.isDark)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = color, modifier = Modifier.size((fontSize.value + 1f).dp))
        Spacer(Modifier.width(4.dp))
        Text(info.label, fontSize = fontSize, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** 歌名 / 歌手 · 专辑 / 音质标签 */
@Composable
fun TvSongHeader(
    song: UnifiedSong,
    titleSize: TextUnit,
    subtitleSize: TextUnit,
    modifier: Modifier = Modifier,
    shadow: Boolean = false
) {
    val quality = rememberQualityInfo(song)
    val colors = LocalTvPlayerColors.current
    val shadowStyle = if (shadow && colors.isDark) TextStyle(shadow = TvTextShadow) else TextStyle.Default
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = song.title,
            style = shadowStyle,
            fontSize = titleSize,
            fontWeight = FontWeight.ExtraBold,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = titleSize * 1.15f
        )
        val sub = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                style = shadowStyle,
                fontSize = subtitleSize,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        TvQualityTag(quality, fontSize = (subtitleSize.value * 0.72f).coerceAtLeast(11f).sp)
    }
}

fun formatPlayerTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
