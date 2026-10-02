package com.lm.player.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.ServerConfig

/**
 * 首页顶部服务器切换下拉选择菜单 (毛玻璃效果 + 仅显示已真实配置的服务器与本地曲库)
 */
@Composable
fun ServerSwitchDropdownButton(
    currentServer: String,
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    onSelectLocal: () -> Unit,
    onSelectServer: (ServerConfig) -> Unit,
    onSyncNow: () -> Unit,
    onGoToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    var expanded by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = if (isDark) {
        Color(0xFF222228).copy(alpha = blurAlpha)
    } else {
        Color(0xFFFFFFFF).copy(alpha = blurAlpha)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

    val pillShape = RoundedCornerShape(12.dp)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .tvFocusable(
                    shape = pillShape,
                    focusedScale = 1.04f,
                    onClick = { expanded = true }
                )
                .clip(pillShape)
                .background(surfaceColor)
                .border(BorderStroke(1.dp, borderColor), pillShape)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = AppleRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currentServer,
                    style = TextStyle(
                        fontSize = dimensions.captionSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // 1. 本地媒体库选项
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentServer.contains("本地")) {
                            Text("✓ ", color = AppleRed, fontWeight = FontWeight.Bold)
                        }
                        Text("本地 · 已下载", fontSize = dimensions.bodySize)
                    }
                },
                onClick = {
                    onSelectLocal()
                    expanded = false
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(10.dp))
            )

            // 2. 真实已添加配置的服务器选项 (不显示未添加的占位项)
            if (configuredServers.isNotEmpty()) {
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f), thickness = 0.5.dp)
                val isLocalMode = currentServer.contains("本地")
                configuredServers.forEach { server ->
                    val isCurrent = !isLocalMode && (currentServer.contains(server.name, ignoreCase = true) || (currentServer != "未连接服务器" && server.isCurrentActive))
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrent) {
                                    Text("✓ ", color = AppleRed, fontWeight = FontWeight.Bold)
                                }
                                Text("${server.type.name.lowercase()} · ${server.name}", fontSize = dimensions.bodySize)
                            }
                        },
                        onClick = {
                            onSelectServer(server)
                            expanded = false
                        },
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            } else {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed) },
                    text = { Text("添加柠檬音乐服务器...", fontSize = dimensions.bodySize, color = AppleRed) },
                    onClick = {
                        onGoToSettings()
                        expanded = false
                    },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                        .clip(RoundedCornerShape(10.dp))
                )
            }

            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 0.5.dp)

            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                },
                text = { Text("立即同步", color = AppleRed, fontSize = dimensions.bodySize, fontWeight = FontWeight.SemiBold) },
                onClick = {
                    onSyncNow()
                    expanded = false
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .tvButtonFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.02f)
                    .clip(RoundedCornerShape(10.dp))
            )
        }
    }
}
