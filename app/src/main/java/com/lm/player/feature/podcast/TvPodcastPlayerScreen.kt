package com.lm.player.feature.podcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.TvDropdownMenu
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.podcast.*
import java.util.Locale

/**
 * TV / 车机端全屏播客播放页 (TvPodcastPlayerScreen)
 *
 * 当正在播放播客单集时，点击左侧常驻黑胶卡打开的专属全屏大屏播放界面（替代音乐播放页）。
 *
 * 核心架构：
 * - 顶部工具排：收起按钮、所属节目链接、标记已听完开关；
 * - 左侧 50%：超清大封面、标题、进度条与快进快退滑块、核心 5 控按钮组：
 *   `[倍速选择]` `[⟲后退15秒]` `[▶/⏸ 核心大键]` `[前进30秒⟳]` `[⏱定时关闭]`；
 * - 右侧 50%：单集简介（Shownotes 全文，支持遥控器滚动）与待播清单（Up Next）分栏切换。
 */
@Composable
fun TvPodcastPlayerScreen(
    ui: PodcastPlayerUi,
    positionMs: Long,
    actions: PodcastPlayerActions,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val episode = ui.episode
    val isPlayed = episode.playState == EpisodePlayState.PLAYED

    // 倍速下拉菜单
    var showSpeedMenu by remember { mutableStateOf(false) }
    // 睡眠定时下拉菜单
    var showSleepTimerMenu by remember { mutableStateOf(false) }
    // 右侧标签切换：0 = Shownotes 简介, 1 = 待播清单
    var selectedRightTab by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ================= 1. 播放器顶部工具行 =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 收起按钮
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                    modifier = Modifier.tvFocusable(
                        shape = CircleShape,
                        focusedScale = 1.08f,
                        focusKey = "podcast_player_dismiss",
                        onClick = actions.onDismiss
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "收起播放页",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // 所属节目名链接
                Text(
                    text = ui.showTitle,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            actions.onOpenShow(episode.showId)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                // 标记已听完按钮
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isPlayed) PodcastPlayedGreenBg else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    border = BorderStroke(
                        1.dp,
                        if (isPlayed) PodcastPlayedGreenBorder else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.05f,
                        focusKey = "podcast_player_mark_played_top",
                        onClick = { actions.onMarkPlayed(!isPlayed) }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isPlayed) PodcastPlayedGreen else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (isPlayed) "已听完 (点击撤销)" else "标记已听完",
                            fontSize = 12.sp,
                            fontWeight = if (isPlayed) FontWeight.Bold else FontWeight.Normal,
                            color = if (isPlayed) PodcastPlayedGreen else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // ================= 2. 播放器主体左右分栏 =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ---------- 左侧 50%：大封面、单集名、进度滑块、核心 5 控 ----------
                Column(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 大封面
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.4f))
                    ) {
                        AlbumArtworkImage(
                            model = ui.coverUrl,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 20.dp,
                            seedId = episode.id,
                            targetSize = 420
                        )
                    }

                    // 单集标题与元数据
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isPlayed) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = PodcastPlayedGreenBg,
                                    border = BorderStroke(1.dp, PodcastPlayedGreenBorder)
                                ) {
                                    Text(
                                        text = "已听",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PodcastPlayedGreen,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = listOfNotNull(
                                    episode.publishedText.takeIf { it.isNotBlank() },
                                    episode.durationText.takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = episode.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 进度条与时间 (带左右方向键快退 / 快进支持)
                    val duration = ui.durationMs.coerceAtLeast(1L)
                    val progressRatio = (positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    val canSeek = ui.durationMs > 0L

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Slider(
                            value = progressRatio,
                            onValueChange = { ratio ->
                                if (canSeek) {
                                    actions.onSeekTo((ratio * duration).toLong())
                                }
                            },
                            enabled = canSeek,
                            colors = SliderDefaults.colors(
                                thumbColor = AppleRed,
                                activeTrackColor = AppleRed,
                                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.02f,
                                    focusKey = "podcast_player_slider",
                                    onLeftKey = {
                                        actions.onSkipBack()
                                        true
                                    },
                                    onRightKey = {
                                        actions.onSkipForward()
                                        true
                                    },
                                    onClick = actions.onTogglePlayPause
                                )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTimeMs(positionMs),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (ui.durationMs > 0) {
                                    "剩 " + formatTimeMs(ui.durationMs - positionMs)
                                } else {
                                    "直播/未知时长"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 核心 5 控按钮排：[倍速] [⟲15s] [ ▶/⏸ ] [30s⟳] [定时关闭]
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 倍速控制胶囊
                        Box {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                modifier = Modifier
                                    .size(46.dp)
                                    .tvFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedScale = 1.10f,
                                        focusKey = "podcast_player_speed_btn",
                                        onClick = { showSpeedMenu = true }
                                    )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = PodcastPlayerDefaults.speedText(ui.speed),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleRed
                                    )
                                    Text(
                                        text = "倍速",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            TvDropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                PodcastPlayerDefaults.SPEED_OPTIONS.forEach { spd ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = PodcastPlayerDefaults.speedText(spd),
                                                fontWeight = if (spd == ui.speed) FontWeight.Bold else FontWeight.Normal,
                                                color = if (spd == ui.speed) AppleRed else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            showSpeedMenu = false
                                            actions.onSetSpeed(spd)
                                        }
                                    )
                                }
                            }
                        }

                        // 2. 快退 15 秒
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            modifier = Modifier
                                .size(46.dp)
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.10f,
                                    focusKey = "podcast_player_skip_back",
                                    onClick = actions.onSkipBack
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.RotateLeft,
                                    contentDescription = "快退15秒",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 3. 核心播放 / 暂停大按键 (默认高光焦点)
                        Surface(
                            shape = CircleShape,
                            color = AppleRed,
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .size(58.dp)
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.12f,
                                    focusKey = "podcast_player_play_pause",
                                    onClick = actions.onTogglePlayPause
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (ui.isBuffering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = Color.White,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (ui.isPlaying) "暂停" else "播放",
                                        tint = Color.White,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }

                        // 4. 快进 30 秒
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            modifier = Modifier
                                .size(46.dp)
                                .tvFocusable(
                                    shape = CircleShape,
                                    focusedScale = 1.10f,
                                    focusKey = "podcast_player_skip_forward",
                                    onClick = actions.onSkipForward
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.RotateRight,
                                    contentDescription = "快进30秒",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 5. 定时关闭胶囊
                        Box {
                            val timerActive = ui.sleepTimerMinutes != 0
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (timerActive) AppleRed.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (timerActive) AppleRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                ),
                                modifier = Modifier
                                    .size(46.dp)
                                    .tvFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedScale = 1.10f,
                                        focusKey = "podcast_player_sleep_timer",
                                        onClick = { showSleepTimerMenu = true }
                                    )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccessTime,
                                        contentDescription = "定时关闭",
                                        tint = if (timerActive) AppleRed else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (timerActive) {
                                            if (ui.sleepTimerMinutes == -1) "播完" else "${ui.sleepTimerMinutes}m"
                                        } else "定时",
                                        fontSize = 9.sp,
                                        color = if (timerActive) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            TvDropdownMenu(
                                expanded = showSleepTimerMenu,
                                onDismissRequest = { showSleepTimerMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = "关闭定时", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        showSleepTimerMenu = false
                                        actions.onSetSleepTimer(0)
                                    }
                                )
                                PodcastPlayerDefaults.SLEEP_TIMER_OPTIONS.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (m == -1) "本集播完停止" else "$m 分钟后停止",
                                                fontWeight = if (m == ui.sleepTimerMinutes) FontWeight.Bold else FontWeight.Normal,
                                                color = if (m == ui.sleepTimerMinutes) AppleRed else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            showSleepTimerMenu = false
                                            actions.onSetSleepTimer(m)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- 右侧 50%：Shownotes (单集简介) 与 待播清单 (Up Next) ----------
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 顶部标签切换
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedRightTab == 0) AppleRed.copy(alpha = 0.2f) else Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedRightTab == 0) AppleRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier.tvFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    focusedScale = 1.05f,
                                    focusKey = "podcast_player_tab_notes",
                                    onClick = { selectedRightTab = 0 }
                                )
                            ) {
                                Text(
                                    text = "单集简介 (Shownotes)",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedRightTab == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedRightTab == 0) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedRightTab == 1) AppleRed.copy(alpha = 0.2f) else Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedRightTab == 1) AppleRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier.tvFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    focusedScale = 1.05f,
                                    focusKey = "podcast_player_tab_upnext",
                                    onClick = { selectedRightTab = 1 }
                                )
                            ) {
                                Text(
                                    text = "待播清单 (${ui.upNext.size})",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedRightTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedRightTab == 1) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // 内容展示区
                        if (selectedRightTab == 0) {
                            // Shownotes 全文（支持上下遥控滑动）
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = episode.description.ifBlank { "本单集暂无详细 Shownotes 简介说明。" },
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                        lineHeight = 19.sp
                                    )
                                }
                            }
                        } else {
                            // 待播清单列表
                            if (ui.upNext.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "本节目中没有更多待播单集了",
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        items = ui.upNext,
                                        key = { next -> "upnext_${next.id}" }
                                    ) { next ->
                                        TvPodcastEpisodeCard(
                                            episode = next,
                                            onClick = { actions.onPlayFromUpNext(next) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 毫秒转换为分:秒格式 */
private fun formatTimeMs(timeMs: Long): String {
    val totalSec = (timeMs.coerceAtLeast(0L) / 1000L).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

// ==================== 4 档 Preview 预览 ====================

@Preview(name = "TV 1080p 深色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastPlayerScreenPreviewTvDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF101014)) {
            TvPodcastPlayerScreen(
                ui = TvPodcastPreviewData.samplePlayerUi,
                positionMs = 24 * 60 * 1000L,
                actions = PodcastPlayerActions()
            )
        }
    }
}

@Preview(name = "TV 1080p 浅色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastPlayerScreenPreviewTvLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(color = Color(0xFFF2F2F7)) {
            TvPodcastPlayerScreen(
                ui = TvPodcastPreviewData.samplePlayerUi,
                positionMs = 24 * 60 * 1000L,
                actions = PodcastPlayerActions()
            )
        }
    }
}

@Preview(name = "车机超宽 深色", widthDp = 1280, heightDp = 480)
@Composable
fun TvPodcastPlayerScreenPreviewCarWide() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF101014)) {
            TvPodcastPlayerScreen(
                ui = TvPodcastPreviewData.samplePlayerUi,
                positionMs = 24 * 60 * 1000L,
                actions = PodcastPlayerActions()
            )
        }
    }
}

@Preview(name = "内容区占位尺寸", widthDp = 680, heightDp = 460)
@Composable
fun TvPodcastPlayerScreenPreviewContentArea() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF14141A)) {
            TvPodcastPlayerScreen(
                ui = TvPodcastPreviewData.samplePlayerUi,
                positionMs = 24 * 60 * 1000L,
                actions = PodcastPlayerActions()
            )
        }
    }
}
