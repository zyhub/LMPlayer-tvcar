package com.lm.player.feature.podcast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.TvRestoreFocusOnChange
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.podcast.*

/**
 * TV / 车机端播客主页 (TvPodcastHomeScreen)
 *
 * 布局规范：
 * 1. 顶行两端对齐：左上方放置「订阅状态胶囊卡片」，右上方放置「全部拉取最新合并按钮（内嵌拉取进度条与动效）」；
 * 2. 第二行：三等分卡片排（已撤销副标题字幕，纯净单行居中）：
 *    - 卡片 1：`+ 添加 RSS 源`
 *    - 卡片 2：`⚙️ 管理订阅库 (N)`
 *    - 卡片 3：`🔍 搜索与发现`
 * 3. 内容区三大板块：
 *    - 继续收听：未听完单集断点续播卡片
 *    - 我的订阅：节目网格（带红色未读角标与抓取告警）
 *    - 最新单集：汇总双列宽卡片（带绿色已听标示与音波）
 * 4. 异常状态页覆盖：LOADING / EMPTY / NO_SERVER / UNSUPPORTED / ERROR
 */
@Composable
fun TvPodcastHomeScreen(
    ui: PodcastHomeUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier,
    /** 列表滚动状态（Claude 修改：由宿主提升保存，返回本页时保留滚动位置，焦点才能恢复到刚点的那一项） */
    listState: LazyListState = rememberLazyListState()
) {
    val dimensions = LocalAppDimensions.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // 记忆焦点恢复：当从节目详情页或管理页返回主页时，焦点恢复到刚才操作的卡片
        TvRestoreFocusOnChange(trigger = ui.state == PodcastLoadState.READY)

        when (ui.state) {
            PodcastLoadState.LOADING -> {
                TvPodcastLoadingState(modifier = Modifier.fillMaxSize())
            }
            PodcastLoadState.EMPTY -> {
                TvPodcastEmptyState(actions = actions, modifier = Modifier.fillMaxSize())
            }
            PodcastLoadState.NO_SERVER -> {
                TvPodcastNoServerState(
                    message = ui.message,
                    onGoToSettings = actions.onGoToServerSettings,
                    onRetry = actions.onRetry,
                    modifier = Modifier.fillMaxSize()
                )
            }
            PodcastLoadState.UNSUPPORTED -> {
                TvPodcastUnsupportedState(
                    message = ui.message,
                    onRetry = actions.onRetry,
                    modifier = Modifier.fillMaxSize()
                )
            }
            PodcastLoadState.ERROR -> {
                TvPodcastErrorState(
                    message = ui.message,
                    onRetry = actions.onRetry,
                    modifier = Modifier.fillMaxSize()
                )
            }
            PodcastLoadState.READY -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // ================= 顶部控制区 =================
                    item(key = "podcast_header_controls") {
                        TvPodcastHeaderControls(
                            ui = ui,
                            actions = actions
                        )
                    }

                    // ================= 1. 继续收听 =================
                    if (ui.continueListening.isNotEmpty()) {
                        item(key = "podcast_section_continue") {
                            TvPodcastContinueListeningSection(
                                episodes = ui.continueListening,
                                onPlayEpisode = actions.onPlayEpisode,
                                onOpenEpisode = actions.onOpenEpisode
                            )
                        }
                    }

                    // ================= 2. 我的订阅 =================
                    if (ui.shows.isNotEmpty()) {
                        item(key = "podcast_section_shows") {
                            TvPodcastMyShowsSection(
                                shows = ui.shows,
                                onOpenShow = actions.onOpenShow
                            )
                        }
                    }

                    // ================= 3. 最新单集 =================
                    if (ui.latestEpisodes.isNotEmpty()) {
                        item(key = "podcast_section_latest_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "最新单集",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "所有订阅汇总 (${ui.latestEpisodes.size})",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 双列网格排布最新单集卡片
                        val chunkedEpisodes = ui.latestEpisodes.chunked(2)
                        items(
                            items = chunkedEpisodes,
                            key = { pair -> "latest_row_${pair.first().id}" }
                        ) { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                for (ep in pair) {
                                    TvPodcastEpisodeCard(
                                        episode = ep,
                                        onClick = { actions.onPlayEpisode(ep) },
                                        onOpenDetails = { actions.onOpenEpisode(ep) },
                                        onTogglePlayed = { played -> actions.onMarkPlayed(ep, played) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部工具控制区：
 * - 第一行：左上角订阅状态胶囊 + 右上角拉取与进度条合并按钮
 * - 第二行：三等分卡片排（已撤销副标题字幕，纯净单行居中）
 */
@Composable
private fun TvPodcastHeaderControls(
    ui: PodcastHomeUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier
) {
    val totalUnplayed = remember(ui.continueListening) {
        ui.continueListening.size
    }
    val isFetching = ui.isRefreshing || ui.refreshProgressText.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 第一行：左上状态胶囊 + 右上拉取进度合并按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 订阅状态胶囊卡片（播客页面左上方）
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                    )
                    Text(
                        text = "${ui.shows.size} 个订阅源",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$totalUnplayed 个未完成单集",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. 全部拉取最新合并按钮（内嵌拉取中动画及进度提示）
            Surface(
                shape = CircleShape,
                color = if (isFetching) AppleRed.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                border = BorderStroke(
                    1.dp,
                    if (isFetching) AppleRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                ),
                modifier = Modifier.tvFocusable(
                    shape = CircleShape,
                    focusedScale = 1.05f,
                    focusKey = "podcast_header_refresh_merged",
                    onClick = {
                        if (!isFetching) {
                            actions.onFetchAllShows()
                        }
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            color = AppleRed,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = ui.refreshProgressText.ifBlank { "正在拉取最新..." },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleRed
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "全部拉取最新",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "全部拉取最新",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 第二行：三等分卡片排（已撤销副标题字幕，纯净单行居中）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 卡片 1：添加 RSS 源（主操作卡片，醒目质感）
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppleRed.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.35f)),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .tvFocusable(
                        shape = RoundedCornerShape(16.dp),
                        focusedScale = 1.04f,
                        focusKey = "podcast_header_card_add_rss",
                        onClick = actions.onOpenManage
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFFF334B), AppleRed)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "添加 RSS 源",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // 卡片 2：管理订阅库（副操作卡片，带订阅数统计）
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .tvFocusable(
                        shape = RoundedCornerShape(16.dp),
                        focusedScale = 1.04f,
                        focusKey = "podcast_header_card_manage",
                        onClick = actions.onOpenManage
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "管理订阅库",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "${ui.shows.size}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // 卡片 3：搜索与发现（快捷入口卡片）
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .tvFocusable(
                        shape = RoundedCornerShape(16.dp),
                        focusedScale = 1.04f,
                        focusKey = "podcast_header_card_search",
                        onClick = actions.onOpenSearch
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = AppleRed,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "搜索与发现",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 区块 1：继续收听横排大卡片
 */
@Composable
private fun TvPodcastContinueListeningSection(
    episodes: List<PodcastEpisodeUi>,
    onPlayEpisode: (PodcastEpisodeUi) -> Unit,
    onOpenEpisode: (PodcastEpisodeUi) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "继续收听",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${episodes.size} 个未完成",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 双列网格并排展示宽大卡片
        val chunked = episodes.chunked(2)
        for (pair in chunked) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (ep in pair) {
                    TvPodcastResumeCard(
                        episode = ep,
                        onPlay = { onPlayEpisode(ep) },
                        onOpen = { onOpenEpisode(ep) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 单个继续收听大卡片 */
@Composable
private fun TvPodcastResumeCard(
    episode: PodcastEpisodeUi,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.03f,
                focusKey = "podcast_resume_${episode.id}",
                onMenuKey = onOpen,
                onLongClick = onOpen,
                onClick = onPlay
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 封面与播放微图标
            Box(
                modifier = Modifier
                    .size(56.dp)
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
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "继续播放",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // 标题与进度
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = episode.showTitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = episode.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { episode.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape),
                        color = AppleRed,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                    Text(
                        text = episode.remainingText.ifBlank { "剩若干分钟" },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 区块 2：我的订阅网格
 */
@Composable
private fun TvPodcastMyShowsSection(
    shows: List<PodcastShowUi>,
    onOpenShow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "我的订阅",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "共 ${shows.size} 个节目源",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 4 列自适应网格
        val chunkedShows = shows.chunked(4)
        for (row in chunkedShows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (show in row) {
                    TvPodcastShowCard(
                        show = show,
                        onClick = { onOpenShow(show.id) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 单个已订阅节目大屏卡片（带红色新单集角标与抓取告警） */
@Composable
private fun TvPodcastShowCard(
    show: PodcastShowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        modifier = modifier
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.05f,
                focusKey = "podcast_show_${show.id}",
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 方形封面与红色新集数角标
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                AlbumArtworkImage(
                    model = show.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 12.dp,
                    seedId = show.id,
                    targetSize = 240
                )

                // 红色新单集角标（用户上次点开之后新出的单集数，点开即清零）
                if (show.newEpisodeCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = AppleRed,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = if (show.newEpisodeCount > 99) "99+" else show.newEpisodeCount.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                        )
                    }
                }

                // 抓取失败轻量警示浮层
                if (show.errorText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xD9E65100),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = "同步失败",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = show.title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Text(
                text = "${show.updatedText.ifBlank { "近期" }} · ${show.episodeCount}期",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// ==================== 状态页 Composable ====================

@Composable
private fun TvPodcastLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                color = AppleRed,
                strokeWidth = 3.dp,
                modifier = Modifier.size(42.dp)
            )
            Text(
                text = "正在载入播客订阅与最新单集...",
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TvPodcastEmptyState(
    actions: PodcastActions,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Podcasts,
                    contentDescription = null,
                    tint = AppleRed,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = "还没有订阅任何播客",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "您可以直接输入或粘贴 RSS 订阅地址，或者在 Apple 播客公开目录中搜索喜欢的节目。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = actions.onOpenManage,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.05f,
                        onClick = actions.onOpenManage
                    )
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "添加 RSS 地址", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = actions.onOpenSearch,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.05f,
                        onClick = actions.onOpenSearch
                    )
                ) {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "搜索播客", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun TvPodcastNoServerState(
    message: String,
    onGoToSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 440.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0x26FFA000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = Color(0xFFFFA000),
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = "未连接柠檬音乐服务端",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = message.ifBlank { "播客功能依赖柠檬服务器进行 RSS 抓取与单集音频解析，请先在设置中配置并连接服务器。" },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onGoToSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.05f,
                        onClick = onGoToSettings
                    )
                ) {
                    Text(text = "去设置服务器", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.05f,
                        onClick = onRetry
                    )
                ) {
                    Text(text = "重试连接", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun TvPodcastUnsupportedState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFFFA000),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "当前服务器版本不支持播客",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message.ifBlank { "检测到当前连接的柠檬音乐服务器版本过旧，未提供播客 API (/api/podcasts)，请升级服务器端。" },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.tvFocusable(
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.05f,
                    onClick = onRetry
                )
            ) {
                Text(text = "刷新重试", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TvPodcastErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = AppleRed,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "获取播客数据失败",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message.ifBlank { "网络连接超时或服务器解析异常，请稍后重试。" },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.tvFocusable(
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.05f,
                    onClick = onRetry
                )
            ) {
                Text(text = "重新加载", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==================== 4 档 Preview 预览 ====================

@Preview(name = "TV 1080p 深色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastHomeScreenPreviewTvDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF121216)) {
            TvPodcastHomeScreen(
                ui = TvPodcastPreviewData.sampleHomeUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "TV 1080p 浅色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastHomeScreenPreviewTvLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(color = Color(0xFFF2F2F7)) {
            TvPodcastHomeScreen(
                ui = TvPodcastPreviewData.sampleHomeUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "车机超宽 深色", widthDp = 1280, heightDp = 480)
@Composable
fun TvPodcastHomeScreenPreviewCarWide() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF101014)) {
            TvPodcastHomeScreen(
                ui = TvPodcastPreviewData.sampleHomeUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "右侧内容区占位尺寸", widthDp = 680, heightDp = 460)
@Composable
fun TvPodcastHomeScreenPreviewContentArea() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF14141A)) {
            TvPodcastHomeScreen(
                ui = TvPodcastPreviewData.sampleHomeUi,
                actions = PodcastActions()
            )
        }
    }
}
