package com.lm.player.feature.podcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.lm.player.core.designsystem.component.TvAlertDialog
import com.lm.player.core.designsystem.component.TvRestoreFocusOnChange
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.podcast.*

/**
 * TV / 车机端节目详情大屏页 (TvPodcastShowScreen)
 *
 * 布局规范：
 * - 左右双栏架构：左侧 35% 节目封面与简介名片；右侧 65% 单集列表舞台；
 * - 左栏：返回按键、大封面、标题、主播、期数、滚动简介、主操作（继续收听/播放最新）、拉取与取消订阅；
 * - 右栏：排序（最新/最早）与过滤（全部/未听）分段胶囊、分页滚动单集列表；
 * - 二次确认：取消订阅必须弹起 [TvAlertDialog] 避免误触。
 */
@Composable
fun TvPodcastShowScreen(
    ui: PodcastShowDetailUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier
) {
    val show = ui.show
    val dimensions = LocalAppDimensions.current
    var showUnsubscribeConfirm by remember { mutableStateOf(false) }

    // 取消订阅二次确认弹窗（TvAlertDialog 自动处理 Android 13+ TV 返回键拦截）
    if (showUnsubscribeConfirm) {
        TvAlertDialog(
            onDismissRequest = { showUnsubscribeConfirm = false },
            title = {
                Text(
                    text = "确定取消订阅？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "取消订阅《${show.title}》后，该节目的单集列表与所有收听进度记录将一并从服务器移除。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsubscribeConfirm = false
                        actions.onUnsubscribe(show.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.05f,
                        onClick = {
                            showUnsubscribeConfirm = false
                            actions.onUnsubscribe(show.id)
                        }
                    )
                ) {
                    Text(text = "确认取消订阅", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showUnsubscribeConfirm = false },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.05f,
                        onClick = { showUnsubscribeConfirm = false }
                    )
                ) {
                    Text(text = "再想想", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // 焦点恢复
        TvRestoreFocusOnChange(trigger = ui.state == PodcastLoadState.READY)

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // ================= 左侧 35% 节目信息与主操作 =================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 上半部：返回按钮 + 大封面 + 标题 + 简介
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 返回主页按钮
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            modifier = Modifier.tvFocusable(
                                shape = CircleShape,
                                focusedScale = 1.08f,
                                focusKey = "podcast_show_back_btn",
                                onClick = actions.onBack
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "返回主页",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 大封面
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.3f))
                        ) {
                            AlbumArtworkImage(
                                model = show.imageUrl,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 16.dp,
                                seedId = show.id,
                                targetSize = 360
                            )
                        }

                        // 标题与主播
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = show.title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (show.author.isNotBlank()) {
                                Text(
                                    text = show.author,
                                    fontSize = 12.sp,
                                    color = AppleRed,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "共 ${ui.totalEpisodes} 期 · ${show.updatedText.ifBlank { "近期更新" }}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 简介（背景框包裹，遥控器可上下滑动浏览）
                        if (show.description.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = show.description,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    // 下半部：主操作大按钮 + 次操作行
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 主按钮：有断点续播则“继续收听”，否则“播放最新一期”
                        val resume = ui.resumeEpisode
                        Button(
                            onClick = {
                                if (resume != null) actions.onPlayEpisode(resume)
                                else actions.onPlayShowLatest(show.id)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .tvFocusable(
                                    shape = RoundedCornerShape(12.dp),
                                    focusedScale = 1.04f,
                                    focusKey = "podcast_show_primary_play",
                                    onClick = {
                                        if (resume != null) actions.onPlayEpisode(resume)
                                        else actions.onPlayShowLatest(show.id)
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
                                text = if (resume != null) {
                                    "继续收听 (${resume.remainingText.ifBlank { "记忆位置" }})"
                                } else {
                                    "播放最新一期"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 次操作行：拉取最新 + 取消订阅
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 拉取最新按钮
                            OutlinedButton(
                                onClick = { actions.onFetchShow(show.id) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                                // Claude 修改：默认 24dp 内边距在窄栏里会把「拉取最新」挤到看不见
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .tvFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedScale = 1.04f,
                                        focusKey = "podcast_show_fetch_btn",
                                        onClick = { actions.onFetchShow(show.id) }
                                    )
                            ) {
                                if (show.isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = AppleRed,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (show.isRefreshing) "拉取中" else "拉取最新",
                                    maxLines = 1,
                                    softWrap = false,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // 取消订阅按钮
                            OutlinedButton(
                                onClick = { showUnsubscribeConfirm = true },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.3f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = AppleRed.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier
                                    .height(38.dp)
                                    .tvFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedScale = 1.04f,
                                        focusKey = "podcast_show_unsubscribe_btn",
                                        onClick = { showUnsubscribeConfirm = true }
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "取消订阅",
                                    maxLines = 1,
                                    softWrap = false,
                                    fontSize = 11.5.sp,
                                    color = AppleRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // ================= 右侧 65% 单集列表舞台 =================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 顶部排序与筛选工具排
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "单集列表 (${ui.totalEpisodes} 期)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 排序切换 (最新在前 / 最早在前)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                            ) {
                                Row(modifier = Modifier.padding(2.dp)) {
                                    EpisodeSort.entries.forEach { s ->
                                        val selected = s == ui.sort
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f) else Color.Transparent,
                                            modifier = Modifier.tvFocusable(
                                                shape = RoundedCornerShape(8.dp),
                                                focusedScale = 1.05f,
                                                focusKey = "podcast_sort_${s.name}",
                                                onClick = { actions.onSortChange(show.id, s) }
                                            )
                                        ) {
                                            Text(
                                                text = s.label,
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 过滤切换 (全部 / 未听完)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                            ) {
                                Row(modifier = Modifier.padding(2.dp)) {
                                    EpisodeFilter.entries.forEach { f ->
                                        val selected = f == ui.filter
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (selected) AppleRed.copy(alpha = 0.25f) else Color.Transparent,
                                            modifier = Modifier.tvFocusable(
                                                shape = RoundedCornerShape(8.dp),
                                                focusedScale = 1.05f,
                                                focusKey = "podcast_filter_${f.name}",
                                                onClick = { actions.onFilterChange(show.id, f) }
                                            )
                                        ) {
                                            Text(
                                                text = f.label,
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 单集列表
                    val listState = rememberLazyListState()

                    // 滑动到底部自动加载更多
                    LaunchedEffect(listState.canScrollForward, ui.canLoadMore, ui.isLoadingMore) {
                        if (!listState.canScrollForward && ui.canLoadMore && !ui.isLoadingMore && ui.episodes.isNotEmpty()) {
                            actions.onLoadMore(show.id)
                        }
                    }

                    if (ui.episodes.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (ui.filter == EpisodeFilter.UNPLAYED) "本节目中没有未听完的单集" else "暂无单集内容",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = ui.episodes,
                                key = { ep -> "show_ep_${ep.id}" }
                            ) { ep ->
                                TvPodcastEpisodeCard(
                                    episode = ep,
                                    onClick = { actions.onPlayEpisode(ep) },
                                    onOpenDetails = { actions.onOpenEpisode(ep) },
                                    onTogglePlayed = { played -> actions.onMarkPlayed(ep, played) }
                                )
                            }

                            if (ui.isLoadingMore) {
                                item(key = "podcast_show_loading_more") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = AppleRed,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "正在分页加载更多单集...",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

// ==================== 4 档 Preview 预览 ====================

@Preview(name = "TV 1080p 深色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastShowScreenPreviewTvDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF121216)) {
            TvPodcastShowScreen(
                ui = TvPodcastPreviewData.sampleShowDetailUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "TV 1080p 浅色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastShowScreenPreviewTvLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(color = Color(0xFFF2F2F7)) {
            TvPodcastShowScreen(
                ui = TvPodcastPreviewData.sampleShowDetailUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "车机超宽 深色", widthDp = 1280, heightDp = 480)
@Composable
fun TvPodcastShowScreenPreviewCarWide() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF101014)) {
            TvPodcastShowScreen(
                ui = TvPodcastPreviewData.sampleShowDetailUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "内容区占位尺寸", widthDp = 680, heightDp = 460)
@Composable
fun TvPodcastShowScreenPreviewContentArea() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF14141A)) {
            TvPodcastShowScreen(
                ui = TvPodcastPreviewData.sampleShowDetailUi,
                actions = PodcastActions()
            )
        }
    }
}
