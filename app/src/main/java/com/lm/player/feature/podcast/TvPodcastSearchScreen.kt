package com.lm.player.feature.podcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.TvRestoreFocusOnChange
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.component.tvTextFieldKeys
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.podcast.*

/**
 * TV / 车机端播客搜索与发现页 (TvPodcastSearchScreen)
 *
 * 核心交互：
 * - 输入框支持 `Modifier.tvTextFieldKeys()`，方便电视遥控器方向键上下移出焦点与呼出输入法；
 * - 智能识别：若用户输入或粘贴的内容为 http(s) 开头的 RSS 地址，主操作按钮自动切换为「订阅此 RSS」；
 * - Apple 播客目录搜索结果：采用 3 列大卡片网格呈现，支持一键订阅、已订阅状态禁用与抓取中旋转指示。
 */
@Composable
fun TvPodcastSearchScreen(
    ui: PodcastSearchUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier,
    /** 列表滚动状态（Claude 修改：由宿主提升保存，返回本页时保留滚动位置，焦点才能恢复到刚点的那一项） */
    listState: LazyListState = rememberLazyListState()
) {
    val dimensions = LocalAppDimensions.current
    val clipboardManager = LocalClipboardManager.current
    var queryText by remember { mutableStateOf("") }
    val isFeedUrl = remember(queryText) { looksLikeFeedUrl(queryText) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // 焦点恢复
        TvRestoreFocusOnChange(trigger = ui.results.size)

        LazyColumn(
                    state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ================= 顶部导航与搜索框 =================
            item(key = "podcast_search_top_bar") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 顶部返回行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            modifier = Modifier.tvFocusable(
                                shape = CircleShape,
                                focusedScale = 1.08f,
                                focusKey = "podcast_search_back",
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
                                    text = "返回",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Text(
                            text = "搜索与发现播客",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "基于 Apple 播客公开目录与直接 RSS 解析",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 搜索输入框与操作按键
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = queryText,
                            onValueChange = {
                                queryText = it
                                if (it.isBlank()) actions.onClearSearch()
                            },
                            placeholder = {
                                Text(
                                    text = "输入节目名、主播，或直接粘贴 RSS 地址...",
                                    fontSize = 12.5.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (queryText.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            queryText = ""
                                            actions.onClearSearch()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清空",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .tvTextFieldKeys(exitOnRight = true)
                        )

                        // 一键粘贴剪贴板
                        OutlinedButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text.orEmpty().trim()
                                if (clip.isNotBlank()) {
                                    queryText = clip
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                            modifier = Modifier
                                .height(48.dp)
                                // Claude 修改：Button 自带可点击焦点，改用 tvButtonFocusable 只做焦点外观，避免一个按钮两个焦点位
                                .tvButtonFocusable(
                                    shape = RoundedCornerShape(12.dp),
                                    focusedScale = 1.05f,
                                    focusKey = "podcast_search_paste_btn"
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "粘贴", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }

                        // 主操作按键（RSS 地址自动识别为“订阅此 RSS”，否则为“搜索”）
                        val canSubmit = queryText.isNotBlank() && !ui.isSubscribingUrl
                        Button(
                            onClick = {
                                if (canSubmit) {
                                    val q = queryText.trim()
                                    if (isFeedUrl) actions.onSubscribeUrl(q)
                                    else actions.onSearch(q)
                                }
                            },
                            // Claude 修改：不再用 enabled 禁用（获焦的按钮一被禁用焦点就丢、光标跳到最左），改为变淡 + 点击时判断
                            colors = ButtonDefaults.buttonColors(containerColor = if (canSubmit) AppleRed else AppleRed.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .tvButtonFocusable(
                                    shape = RoundedCornerShape(12.dp),
                                    focusedScale = 1.05f,
                                    focusKey = "podcast_search_submit_btn"
                                )
                        ) {
                            if (ui.isSubscribingUrl) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "订阅解析中...", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = if (isFeedUrl) Icons.Default.Add else Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isFeedUrl) "订阅此 RSS" else "搜索节目",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 订阅错误提示条
                    if (ui.subscribeError.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AppleRed.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = ui.subscribeError,
                                    fontSize = 12.sp,
                                    color = AppleRed
                                )
                            }
                        }
                    }
                }
            }

            // ================= 搜索状态与结果呈现 =================
            when (ui.state) {
                PodcastSearchState.IDLE -> {
                    item(key = "podcast_search_idle") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(42.dp)
                                )
                                Text(
                                    text = "搜索节目名称、主播，或直接输入播客 RSS 地址",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                PodcastSearchState.SEARCHING -> {
                    item(key = "podcast_search_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = AppleRed,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "正在从 Apple 播客公开目录检索...",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                PodcastSearchState.NO_RESULT -> {
                    item(key = "podcast_search_no_result") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 50.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ui.message.ifBlank { "未找到相关播客，请尝试更换关键词或直接粘贴该节目的 RSS 源地址。" },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                PodcastSearchState.ERROR -> {
                    item(key = "podcast_search_error") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 50.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ui.message.ifBlank { "网络检索超时，请稍后重试。" },
                                fontSize = 13.sp,
                                color = AppleRed,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                PodcastSearchState.RESULTS -> {
                    item(key = "podcast_search_results_header") {
                        Text(
                            text = "搜索结果 (${ui.results.size} 个相关节目)",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }

                    // 3 列网格排布搜索结果卡片
                    val chunked = ui.results.chunked(3)
                    items(
                        items = chunked,
                        key = { row -> "search_row_${row.first().feedUrl}" }
                    ) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (result in row) {
                                TvPodcastSearchResultCard(
                                    result = result,
                                    onSubscribe = { actions.onSubscribeResult(result) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果大屏卡片组件 */
@Composable
private fun TvPodcastSearchResultCard(
    result: PodcastSearchResultUi,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        modifier = modifier
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.04f,
                focusKey = "podcast_search_item_${result.feedUrl.hashCode()}",
                onClick = {
                    if (!result.isSubscribed && !result.isSubscribing) {
                        onSubscribe()
                    }
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                AlbumArtworkImage(
                    model = result.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 12.dp,
                    seedId = result.feedUrl,
                    targetSize = 160
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = result.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        result.author.takeIf { it.isNotBlank() },
                        result.genre.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.episodeCountText.isNotBlank()) {
                    Text(
                        text = result.episodeCountText,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // 订阅按钮
            if (result.isSubscribed) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "已订阅",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else if (result.isSubscribing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = AppleRed,
                    strokeWidth = 2.dp
                )
            } else {
                Button(
                    onClick = onSubscribe,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = "订阅", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==================== 4 档 Preview 预览 ====================

@Preview(name = "TV 1080p 深色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastSearchScreenPreviewTvDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF121216)) {
            TvPodcastSearchScreen(
                ui = TvPodcastPreviewData.sampleSearchUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "TV 1080p 浅色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastSearchScreenPreviewTvLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(color = Color(0xFFF2F2F7)) {
            TvPodcastSearchScreen(
                ui = TvPodcastPreviewData.sampleSearchUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "车机超宽 深色", widthDp = 1280, heightDp = 480)
@Composable
fun TvPodcastSearchScreenPreviewCarWide() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF101014)) {
            TvPodcastSearchScreen(
                ui = TvPodcastPreviewData.sampleSearchUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "内容区占位尺寸", widthDp = 680, heightDp = 460)
@Composable
fun TvPodcastSearchScreenPreviewContentArea() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF14141A)) {
            TvPodcastSearchScreen(
                ui = TvPodcastPreviewData.sampleSearchUi,
                actions = PodcastActions()
            )
        }
    }
}
