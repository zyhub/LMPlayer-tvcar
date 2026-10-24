package com.lm.player.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.database.entity.ServerSourceEntity
import com.lm.player.core.database.entity.ServerSourceMetaEntity
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.network.ServerLatencyMonitor
import com.lm.player.core.source.SourceAccelerationSession

private val RingGreen = Color(0xFF34C759)
private val RingRed = Color(0xFFFF3B30)
private val RingOrange = Color(0xFFFF9F0A)
private val RingGray = Color(0xFF8E8E93)

/** 4 环极简看板（大屏/TV 遥控版）：在线 / 延迟 / 激活源 / 音源加速。无多余层级、支持 D-Pad 焦点。 */
@Composable
fun ServerSourceStatusCard(
    serverName: String,
    isConnected: Boolean,
    serverHost: String,
    onlineLatencyMs: Long? = null,
    sources: List<ServerSourceEntity>,
    meta: ServerSourceMetaEntity?,
    isRefreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    val session by SourceAccelerationSession.status.collectAsState()
    val online = isConnected
    val rawLatency = onlineLatencyMs?.takeIf { it > 0L }
        ?: meta?.latencyMs?.takeIf { it > 0L }
        ?: ServerLatencyMonitor.latencyMs(serverHost)
    val latency = if (online) rawLatency else 0L
    val active = if (online) (meta?.activeSourceCount ?: sources.count { it.isActive }).coerceAtLeast(0) else 0
    val total = if (online) (meta?.totalSourceCount ?: sources.size).coerceAtLeast(0) else 0
    val accelReady = session.isReady
    val accelLoading = session.state == SourceAccelerationSession.State.LOADING
    val accelColor = when {
        accelReady -> RingGreen
        accelLoading -> RingGray
        else -> RingRed
    }
    val onlineColor = if (online) RingGreen else RingRed
    val latencyColor = when {
        !online -> RingRed
        latency <= 0L -> RingGray
        latency <= 60L -> RingGreen
        latency <= 150L -> RingOrange
        else -> RingRed
    }
    val activeColor = if (!online) RingRed else if (active > 0 && total > 0) RingGreen else RingGray

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "在线:${if (online) "正常" else "未连接"}; 延迟:${if (latency > 0L) "${latency}毫秒" else "未知"}; 激活源:${active}/${total}; 音源加速:${if (accelReady) "正常" else "未加速"}" + (error?.let { "; ${it}" } ?: "")
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音源加速与状态看板", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (serverName.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "($serverName)",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier
                        .size(36.dp)
                        .tvButtonFocusable(shape = CircleShape, focusedScale = 1.15f)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新音源状态", modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (!error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                StatusRingColumn(
                    label = "服务连通",
                    progress = if (online) 1f else 0f,
                    color = onlineColor,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (online) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = onlineColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
                StatusRingColumn(
                    label = "控制延迟",
                    progress = if (latency > 0L) (latency / 200f).coerceIn(0.05f, 1f) else 0f,
                    color = latencyColor,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (latency > 0L) "${latency}" else "—", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("ms", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StatusRingColumn(
                    label = "可用音源",
                    progress = if (total > 0) active.toFloat() / total else 0f,
                    color = activeColor,
                    segments = total.coerceIn(1, 8),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (total > 0) "${active}/${total}" else "—", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                StatusRingColumn(
                    label = "本地加速",
                    progress = if (accelLoading) 0f else 1f,
                    color = accelColor,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = accelColor, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusRingColumn(
    label: String,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    segments: Int = 0,
    content: @Composable () -> Unit
) {
    val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "ring")
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val bg = color.copy(alpha = 0.12f)
                drawArc(
                    color = bg,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (segments > 1) {
                    val gap = 7f
                    val sweep = 360f / segments
                    val filled = (animated * segments).toInt()
                    for (i in 0 until segments) {
                        drawArc(
                            color = if (i < filled) color else color.copy(alpha = 0.18f),
                            startAngle = -90f + i * sweep + gap / 2f,
                            sweepAngle = sweep - gap,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                } else {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            content()
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}
