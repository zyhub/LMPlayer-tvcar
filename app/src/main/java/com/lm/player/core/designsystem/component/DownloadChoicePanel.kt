package com.lm.player.core.designsystem.component

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.media.LocalUpgradeRequests
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.model.serverFileSpecsOf

/*
 * TV / 车机端唯一的下载窗口内容（2026-11-13，照手机端「下载」弹出菜单移植）：
 * 歌曲行尾的下载菜单、各页面的下载对话框、批量下载、播放页的下载按钮全部用这一份，外面只套不同的壳
 * （原位下拉菜单 / 居中对话框）和配色。
 *
 * 1. 标题 + 歌名；
 * 2. 已有文件信息：本地「音质 · 格式 · 大小」（已是 Hi-Res 时标明）、服务器原文件音质；
 * 3. 存储目标一排小胶囊（本地 / 服务器 / 双端）；本地已有时「本地」变成「本地升级」；
 * 4. 音质 2×2 方块，点了就开始下载；本地升级时不高于现有音质的方块置灰，更高的标「升级」，
 *    下载完成后由下载引擎删掉旧文件完成替换（见 [LocalUpgradeRequests]）。
 */

@Immutable
data class DownloadPanelColors(
    val text: Color,
    val textSecondary: Color,
    /** 遥控器焦点描边 */
    val focus: Color,
    /** 目标胶囊底槽 */
    val fill: Color
)

@Composable
fun defaultDownloadPanelColors(): DownloadPanelColors {
    val cs = MaterialTheme.colorScheme
    return DownloadPanelColors(
        text = cs.onSurface,
        textSecondary = cs.onSurfaceVariant,
        focus = Color(0xFFFFC947),
        fill = cs.onSurface.copy(alpha = 0.06f)
    )
}

private val Green = Color(0xFF34C759)
private val LocalOnlyServerIds = listOf("local_storage", "local_folder", "local_saf")

/** 本地文件的真实音质（按文件头与码率判断）：Triple(音质, 格式, 大小) */
private fun localFileQuality(song: UnifiedSong): Triple<AudioQuality, String, String> =
    qualityOfSpecs(com.lm.player.feature.home.resolveRealLocalFormatAndSize(song))

private fun qualityOfSpecs(specs: Triple<String, Int, String>): Triple<AudioQuality, String, String> {
    val (ext, kbps, size) = specs
    val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || kbps >= 800
    val q = when {
        lossless && kbps >= 1200 -> AudioQuality.Q_HIRES
        lossless -> AudioQuality.Q_FLAC
        kbps >= 300 -> AudioQuality.Q_320K
        else -> AudioQuality.Q_128K
    }
    return Triple(q, ext, size)
}

/** 服务器原文件音质（同步曲库时存下的服务器元数据）；拿不到就返回 null，只显示「服务器曲库已有」 */
private fun serverFileQuality(song: UnifiedSong): Triple<AudioQuality, String, String>? {
    val specs = serverFileSpecsOf(song) ?: return null
    val q = when {
        specs.isHiRes -> AudioQuality.Q_HIRES
        specs.isLossless -> AudioQuality.Q_FLAC
        specs.bitRate >= 300 -> AudioQuality.Q_320K
        else -> AudioQuality.Q_128K
    }
    val size = if (specs.sizeBytes > 0L) "%.1f MB".format(java.util.Locale.US, specs.sizeBytes / (1024.0 * 1024.0)) else q.estimateSizeText(song)
    return Triple(q, specs.format.uppercase(), size)
}

@Composable
fun DownloadChoicePanel(
    song: UnifiedSong,
    isServerConnected: Boolean,
    hasLocal: Boolean,
    hasServer: Boolean,
    onConfirm: (DownloadTarget, AudioQuality) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    colors: DownloadPanelColors = defaultDownloadPanelColors(),
    title: String = "下载",
    subtitle: String = song.title,
    /** 批量下载：按整批歌曲汇总预估大小 */
    estimateSongs: List<UnifiedSong>? = null,
    /** 批量下载没有「单个旧文件」可替换，不提供本地升级 */
    allowUpgrade: Boolean = true,
    /** 居中对话框形态显示右上角关闭按钮（原位菜单点外面就关） */
    showClose: Boolean = false
) {
    val dims = LocalAppDimensions.current
    val context = LocalContext.current
    // 服务器确认离线 / 已删除时不再提供「服务器下载 / 双端下载」
    val linkState by com.lm.player.core.source.ServerLinkState.state.collectAsState()
    val serverOk = isServerConnected && !linkState.isLost
    val prefs = remember(context) { context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE) }
    val savedTarget = remember(prefs) {
        runCatching { DownloadTarget.valueOf(prefs.getString("default_download_target", DownloadTarget.LOCAL.name) ?: "") }
            .getOrDefault(DownloadTarget.LOCAL)
    }
    val savedQuality = remember(prefs) {
        AudioQuality.fromKey(prefs.getString("default_download_quality", AudioQuality.Q_320K.key) ?: AudioQuality.Q_320K.key)
    }

    // 性能优化第 6 项：读文件头放到 IO 线程；先用内存里的上次结果，核实后刷新
    val wantLocalInfo = estimateSongs == null && hasLocal && !song.localFilePath.isNullOrBlank()
    val localInfo = androidx.compose.runtime.produceState(
        initialValue = if (wantLocalInfo) com.lm.player.feature.home.peekRealLocalFormatAndSize(song)?.let { qualityOfSpecs(it) } else null,
        song.id, song.localFilePath, wantLocalInfo
    ) {
        value = if (!wantLocalInfo) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { localFileQuality(song) }
    }.value
    val serverInfo = remember(song.id, song.rawMetaJson, hasServer, estimateSongs) {
        if (estimateSongs != null || !hasServer) null else serverFileQuality(song)
    }
    // 能否「本地升级」：有可下载的来源（不是纯本地扫描导入的歌）、旧文件是普通文件路径、还没到 Hi-Res
    val upgradable = allowUpgrade && localInfo != null &&
        localInfo.first != AudioQuality.Q_HIRES &&
        song.serverId !in LocalOnlyServerIds &&
        song.localFilePath?.startsWith("content://") == false
    val canLocal = !hasLocal || upgradable

    val targets = remember(hasLocal, hasServer, serverOk, canLocal) {
        buildList {
            add(Triple(DownloadTarget.LOCAL, if (hasLocal) (if (canLocal) "本地升级" else "本地已有") else "本地", canLocal))
            if (serverOk) {
                add(Triple(DownloadTarget.SERVER, "服务器", !hasServer))
                add(Triple(DownloadTarget.BOTH, "双端", !hasLocal && !hasServer))
            }
        }
    }
    var selected by remember(hasLocal, hasServer, serverOk, savedTarget) {
        mutableStateOf(
            when {
                hasServer && !hasLocal -> DownloadTarget.LOCAL
                hasLocal && !hasServer && serverOk -> DownloadTarget.SERVER
                hasLocal -> DownloadTarget.LOCAL
                !serverOk -> DownloadTarget.LOCAL
                else -> savedTarget
            }
        )
    }
    val effective: DownloadTarget? = targets.firstOrNull { it.first == selected && it.third }?.first
        ?: targets.firstOrNull { it.third }?.first
    val upgrading = effective == DownloadTarget.LOCAL && localInfo != null

    // 打开时光标落在默认音质（本地升级时落在第一个可升级的音质）
    val qualityFocus = remember { FocusRequester() }
    val focusQuality = if (upgrading) {
        AudioQuality.entries.reversed().firstOrNull { it.bitrate > localInfo!!.first.bitrate }
    } else savedQuality
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60L)
        runCatching { qualityFocus.requestFocus() }
    }

    Column(modifier = modifier.padding(horizontal = dims.scale(14.dp), vertical = dims.scale(6.dp))) {
        // 1. 标题 + 歌名
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = dims.bodySize, fontWeight = FontWeight.Bold, color = colors.text)
            Spacer(Modifier.width(8.dp))
            Text(
                subtitle,
                fontSize = dims.captionSize,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showClose) {
                Box(
                    modifier = Modifier
                        .size(dims.scale(30.dp))
                        .tvFocusable(shape = CircleShape, focusedBorderColor = colors.focus, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = colors.textSecondary, modifier = Modifier.size(dims.scale(18.dp)))
                }
            }
        }

        // 2. 已有文件信息
        if (localInfo != null || hasServer) {
            Spacer(Modifier.height(dims.scale(8.dp)))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Green.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (localInfo != null) {
                    val (q, ext, size) = localInfo
                    InfoLine(Icons.Default.PhoneAndroid, "本地：${q.badge} · $ext · $size", if (q == AudioQuality.Q_HIRES) "已是最高音质" else null, colors)
                }
                if (hasServer) {
                    InfoLine(
                        Icons.Default.Cloud,
                        serverInfo?.let { (q, ext, size) -> "服务器：${q.badge} · $ext · $size" } ?: "服务器曲库已有",
                        null,
                        colors
                    )
                }
            }
        }

        if (effective == null) {
            Spacer(Modifier.height(dims.scale(8.dp)))
            Text(
                if (hasLocal && hasServer) "本地与服务器都已有这首歌" else "本地已有这首歌",
                fontSize = dims.captionSize,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(dims.scale(8.dp)))
        } else {

        // 3. 存储目标：一排小胶囊
        Spacer(Modifier.height(dims.scale(10.dp)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dims.scale(40.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(colors.fill)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            targets.forEach { (target, label, enabled) ->
                val isSel = effective == target && enabled
                val tint = when {
                    !enabled -> colors.textSecondary.copy(alpha = 0.35f)
                    isSel -> Color.White
                    else -> colors.text.copy(alpha = 0.8f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .tvFocusable(
                            shape = RoundedCornerShape(17.dp),
                            focusedScale = 1.04f,
                            focusedBorderColor = colors.focus,
                            enabled = enabled
                        ) { selected = target }
                        .background(if (isSel) AppleRed else Color.Transparent, RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (target) {
                                DownloadTarget.LOCAL -> if (hasLocal && enabled) Icons.Default.Upgrade else Icons.Default.PhoneAndroid
                                DownloadTarget.SERVER -> Icons.Default.Cloud
                                DownloadTarget.BOTH -> Icons.Default.Sync
                            },
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(dims.scale(15.dp))
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            label,
                            fontSize = dims.captionSize,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 4. 音质：2×2 方块，点了就开始下载
        Spacer(Modifier.height(dims.scale(10.dp)))
        AudioQuality.entries.reversed().chunked(2).forEach { rowQ ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowQ.forEach { q ->
                    val tileEnabled = !upgrading || q.bitrate > localInfo!!.first.bitrate
                    val badgeColor = when {
                        !tileEnabled -> colors.textSecondary.copy(alpha = 0.45f)
                        q == AudioQuality.Q_HIRES -> Color(0xFFD4AF37)
                        q == AudioQuality.Q_FLAC -> AppleRed
                        else -> Color(0xFF0A84FF)
                    }
                    val sizeText = if (estimateSongs != null) q.estimateSizeText(estimateSongs) else q.estimateSizeText(song)
                    val caption = when {
                        !upgrading -> sizeText
                        q == localInfo!!.first -> "本地已有"
                        !tileEnabled -> "低于本地音质"
                        else -> "升级 · $sizeText"
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(dims.scale(60.dp))
                            .then(if (q == focusQuality && tileEnabled) Modifier.focusRequester(qualityFocus) else Modifier)
                            .tvFocusable(
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.04f,
                                focusedBorderColor = colors.focus,
                                enabled = tileEnabled
                            ) {
                                // 升级音质：登记旧文件，下载引擎落盘新文件后删掉它
                                if (upgrading) LocalUpgradeRequests.mark(song.id, song.localFilePath)
                                onConfirm(effective, q)
                                onDismiss()
                            }
                            .background(
                                if (tileEnabled) badgeColor.copy(alpha = 0.11f) else colors.text.copy(alpha = 0.04f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.badge, fontSize = dims.bodySize, fontWeight = FontWeight.Bold, color = badgeColor, maxLines = 1, modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = if (upgrading && tileEnabled) Icons.Default.Upgrade else Icons.Default.Download,
                                contentDescription = if (upgrading && tileEnabled) "升级音质" else "下载",
                                tint = badgeColor,
                                modifier = Modifier.size(dims.scale(16.dp))
                            )
                        }
                        Text(
                            caption,
                            fontSize = dims.badgeSize,
                            color = if (tileEnabled) colors.textSecondary else colors.textSecondary.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, trailing: String?, colors: DownloadPanelColors) {
    val dims = LocalAppDimensions.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, contentDescription = null, tint = Green, modifier = Modifier.size(dims.scale(15.dp)))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = dims.captionSize,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) Text(trailing, fontSize = dims.badgeSize, fontWeight = FontWeight.Bold, color = Green)
    }
}
