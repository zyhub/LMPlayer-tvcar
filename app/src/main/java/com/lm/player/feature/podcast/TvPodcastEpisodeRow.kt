package com.lm.player.feature.podcast

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.podcast.EpisodePlayState
import com.lm.player.core.podcast.PodcastEpisodeUi

/** 绿色已听标志统一色值（与手机端规范逐字一致） */
val PodcastPlayedGreen = Color(0xFF34C759)
val PodcastPlayedGreenBg = Color(0x2634C759)
val PodcastPlayedGreenBorder = Color(0x4D34C759)

/**
 * TV / 车机大屏专属单集卡片组件（双列宽卡片与单列行）
 *
 * 规范：
 * - 遥控器确定键 (D-Pad Center/Enter) 触发播放 [onPlay]
 * - 遥控器菜单键 (Menu) 或长按触发单集详情/Shownotes [onOpenDetails]
 * - 状态徽标：已听（绿色 #34C759）、新集（黄色）、正在播放（跳动音波）
 */
@Composable
fun TvPodcastEpisodeCard(
    episode: PodcastEpisodeUi,
    onClick: () -> Unit,
    onOpenDetails: (() -> Unit)? = null,
    onTogglePlayed: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val isPlayed = episode.playState == EpisodePlayState.PLAYED
    val isPlaying = episode.isPlaying
    val isCurrent = episode.isCurrent

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(
            1.dp,
            if (isCurrent) AppleRed.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.03f,
                focusKey = "podcast_ep_${episode.id}",
                onMenuKey = onOpenDetails,
                onLongClick = onOpenDetails,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 封面与播放状态浮层
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtworkImage(
                    model = episode.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 12.dp,
                    seedId = episode.id,
                    targetSize = 160
                )

                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        TvPodcastSoundWaveIndicator()
                    }
                }
            }

            // 文本信息区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // 上排标签：节目名、已听、新标、发布日期与时长
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (episode.showTitle.isNotBlank()) {
                        Text(
                            text = episode.showTitle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) AppleRed else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    // 绿色已听标志
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
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // 新单集标记
                    if (episode.isNew && !isPlayed) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0x33FFB300),
                            border = BorderStroke(1.dp, Color(0x66FFB300))
                        ) {
                            Text(
                                text = "新",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB300),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // 日期与时长
                    val metaText = listOfNotNull(
                        episode.publishedText.takeIf { it.isNotBlank() },
                        if (episode.playState == EpisodePlayState.IN_PROGRESS && episode.remainingText.isNotBlank()) {
                            episode.remainingText
                        } else {
                            episode.durationText.takeIf { it.isNotBlank() }
                        }
                    ).joinToString(" · ")

                    if (metaText.isNotBlank()) {
                        Text(
                            text = metaText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1
                        )
                    }
                }

                // 单集标题
                Text(
                    text = episode.title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPlayed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 收听进度条（当在收听中时展示）
                if (episode.playState == EpisodePlayState.IN_PROGRESS && episode.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { episode.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .clip(CircleShape),
                        color = AppleRed,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                }
            }

            // 右侧快捷操作按钮（详情 / 标记已听）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onTogglePlayed != null) {
                    IconButton(
                        onClick = { onTogglePlayed(!isPlayed) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = if (isPlayed) "标记未听" else "标记已听完",
                            tint = if (isPlayed) PodcastPlayedGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (onOpenDetails != null) {
                    IconButton(
                        onClick = onOpenDetails,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "单集简介与 Shownotes",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 正在播放时的跳动音波微动动画
 */
@Composable
fun TvPodcastSoundWaveIndicator(
    modifier: Modifier = Modifier,
    color: Color = AppleRed
) {
    val infiniteTransition = rememberInfiniteTransition(label = "podcast_soundwave")
    val wave1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w1"
    )
    val wave2 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing, delayMillis = 150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w2"
    )
    val wave3 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing, delayMillis = 80),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w3"
    )

    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(16.dp * wave1)
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(16.dp * wave2)
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(16.dp * wave3)
                .clip(CircleShape)
                .background(color)
        )
    }
}
