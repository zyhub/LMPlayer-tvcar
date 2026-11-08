package com.lm.player.feature.podcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
import com.lm.player.core.designsystem.component.TvDialog
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.podcast.EpisodePlayState
import com.lm.player.core.podcast.PodcastActions
import com.lm.player.core.podcast.PodcastEpisodeUi

/**
 * TV / 车机端单集详情与 Shownotes 弹窗 (TvPodcastEpisodeDialog)
 *
 * 核心特性：
 * - 采用 [TvDialog] 自动支持 Android 13+ TV 返回键关闭；
 * - 顶部显示节目名（点击可快速跳转到对应节目详情页）；
 * - 封面、标题、发布日期与时长；
 * - 简介与 Shownotes 全文区域：支持遥控器 D-Pad 上下键平滑滚动浏览；
 * - 底部按键：播放 / 暂停、标记已听完（已听完时呈现淡绿色高亮状态）。
 */
@Composable
fun TvPodcastEpisodeDialog(
    episode: PodcastEpisodeUi,
    actions: PodcastActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val isPlayed = episode.playState == EpisodePlayState.PLAYED

    TvDialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            modifier = modifier
                .widthIn(min = 520.dp, max = 640.dp)
                .heightIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 顶部：节目名链接 + 关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = episode.showTitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onDismiss()
                                actions.onOpenShow(episode.showId)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 中间：封面 + 标题 + 元数据
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                    ) {
                        AlbumArtworkImage(
                            model = episode.imageUrl,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 14.dp,
                            seedId = episode.id,
                            targetSize = 180
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = episode.title,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

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
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            val meta = listOfNotNull(
                                episode.publishedText.takeIf { it.isNotBlank() },
                                episode.durationText.takeIf { it.isNotBlank() },
                                if (episode.playState == EpisodePlayState.IN_PROGRESS && episode.remainingText.isNotBlank()) {
                                    episode.remainingText
                                } else null
                            ).joinToString(" · ")
                            Text(
                                text = meta,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Shownotes 全文内容（遥控器可上下滑动滚动浏览）
                val scrollState = rememberScrollState()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = episode.description.ifBlank { "本单集暂无详细简介说明。" },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            lineHeight = 18.sp
                        )
                    }
                }

                // 底部按键组：播放 / 暂停 + 标记已听完
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            onDismiss()
                            actions.onPlayEpisode(episode)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .tvFocusable(
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.05f,
                                focusKey = "podcast_ep_dialog_play",
                                onClick = {
                                    onDismiss()
                                    actions.onPlayEpisode(episode)
                                }
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (episode.isPlaying) "暂停播放" else "播放此单集",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 标记已听完按钮（已听完显示淡绿色高亮状态）
                    OutlinedButton(
                        onClick = { actions.onMarkPlayed(episode, !isPlayed) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isPlayed) PodcastPlayedGreenBg else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isPlayed) PodcastPlayedGreenBorder else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier
                            .height(42.dp)
                            .tvFocusable(
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.05f,
                                focusKey = "podcast_ep_dialog_mark_played",
                                onClick = { actions.onMarkPlayed(episode, !isPlayed) }
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isPlayed) PodcastPlayedGreen else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPlayed) "已听完 (点击取消)" else "标记已听完",
                            fontSize = 12.5.sp,
                            fontWeight = if (isPlayed) FontWeight.Bold else FontWeight.Normal,
                            color = if (isPlayed) PodcastPlayedGreen else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ==================== 4 档 Preview 预览 ====================

@Preview(name = "TV 1080p 深色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastEpisodeDialogPreviewTvDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101014)),
            contentAlignment = Alignment.Center
        ) {
            TvPodcastEpisodeDialog(
                episode = TvPodcastPreviewData.sampleEpisode1,
                actions = PodcastActions(),
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "TV 1080p 浅色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastEpisodeDialogPreviewTvLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF2F2F7)),
            contentAlignment = Alignment.Center
        ) {
            TvPodcastEpisodeDialog(
                episode = TvPodcastPreviewData.sampleEpisode1,
                actions = PodcastActions(),
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "车机超宽 深色", widthDp = 1280, heightDp = 480)
@Composable
fun TvPodcastEpisodeDialogPreviewCarWide() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101014)),
            contentAlignment = Alignment.Center
        ) {
            TvPodcastEpisodeDialog(
                episode = TvPodcastPreviewData.sampleEpisode1,
                actions = PodcastActions(),
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "内容区占位尺寸", widthDp = 680, heightDp = 460)
@Composable
fun TvPodcastEpisodeDialogPreviewContentArea() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF14141A)),
            contentAlignment = Alignment.Center
        ) {
            TvPodcastEpisodeDialog(
                episode = TvPodcastPreviewData.sampleEpisode1,
                actions = PodcastActions(),
                onDismiss = {}
            )
        }
    }
}
