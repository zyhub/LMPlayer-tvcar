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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.TvAlertDialog
import com.lm.player.core.designsystem.component.TvRestoreFocusOnChange
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.component.tvTextFieldKeys
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.podcast.*

/**
 * TV / 车机端播客订阅管理页 (TvPodcastManageScreen)
 *
 * 核心功能：
 * - 在 App 内直接添加与删除服务器上的 RSS 订阅；
 * - 添加 RSS：配备 `Modifier.tvTextFieldKeys()` 输入框 +「一键粘贴剪贴板」+「添加」按键；
 * - 订阅源列表：展示 RSS 地址、复制 URL、拉取更新、删除（带 [TvAlertDialog] 二次确认）；
 * - 批量清理模式：支持多选后一键批量删除。
 */
@Composable
fun TvPodcastManageScreen(
    ui: PodcastManageUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier,
    /** 列表滚动状态（Claude 修改：由宿主提升保存，返回本页时保留滚动位置，焦点才能恢复到刚点的那一项） */
    listState: LazyListState = rememberLazyListState()
) {
    val dimensions = LocalAppDimensions.current
    val clipboardManager = LocalClipboardManager.current
    var inputUrl by remember { mutableStateOf("") }
    // 添加成功后清空输入框（Claude 接线时补充，与手机端一致）
    LaunchedEffect(ui.lastAddedTitle) {
        if (ui.lastAddedTitle.isNotBlank()) inputUrl = ""
    }
    var isBatchMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    // 删除单项确认弹窗
    var deleteCandidateShow by remember { mutableStateOf<PodcastShowUi?>(null) }
    // 批量删除确认弹窗
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // 单项删除确认
    if (deleteCandidateShow != null) {
        val target = deleteCandidateShow!!
        TvAlertDialog(
            onDismissRequest = { deleteCandidateShow = null },
            title = {
                Text(
                    text = "确认删除订阅？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "确定要从服务器删除《${target.title}》吗？该节目的全部单集与收听记录将一并被移除。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.id
                        deleteCandidateShow = null
                        actions.onDeleteShows(listOf(id))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.05f,
                        onClick = {
                            val id = target.id
                            deleteCandidateShow = null
                            actions.onDeleteShows(listOf(id))
                        }
                    )
                ) {
                    Text(text = "确认删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { deleteCandidateShow = null },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.05f,
                        onClick = { deleteCandidateShow = null }
                    )
                ) {
                    Text(text = "取消", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // 批量删除确认
    if (showBatchDeleteConfirm) {
        TvAlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = {
                Text(
                    text = "确认批量删除？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "确定要从服务器批量删除选中的 ${selectedIds.size} 个播客订阅吗？对应的全部单集将一并移除。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedIds.toList()
                        showBatchDeleteConfirm = false
                        selectedIds.clear()
                        isBatchMode = false
                        actions.onDeleteShows(ids)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.05f,
                        onClick = {
                            val ids = selectedIds.toList()
                            showBatchDeleteConfirm = false
                            selectedIds.clear()
                            isBatchMode = false
                            actions.onDeleteShows(ids)
                        }
                    )
                ) {
                    Text(text = "确认批量删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showBatchDeleteConfirm = false },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.05f,
                        onClick = { showBatchDeleteConfirm = false }
                    )
                ) {
                    Text(text = "取消", color = MaterialTheme.colorScheme.onSurface)
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
        TvRestoreFocusOnChange(trigger = ui.shows.size)

        LazyColumn(
                    state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ================= 顶部导航与批量开关 =================
            item(key = "podcast_manage_top_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
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
                                focusKey = "podcast_manage_back",
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
                            text = "订阅管理",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "在柠檬服务器集中管理 RSS 订阅源",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 批量管理模式切换按钮
                    if (ui.shows.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isBatchMode) AppleRed.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            border = BorderStroke(
                                1.dp,
                                if (isBatchMode) AppleRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            ),
                            modifier = Modifier.tvFocusable(
                                shape = RoundedCornerShape(10.dp),
                                focusedScale = 1.05f,
                                focusKey = "podcast_manage_batch_toggle",
                                onClick = {
                                    isBatchMode = !isBatchMode
                                    if (!isBatchMode) selectedIds.clear()
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isBatchMode) Icons.Default.Close else Icons.Default.Checklist,
                                    contentDescription = null,
                                    tint = if (isBatchMode) AppleRed else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isBatchMode) "退出批量" else "批量管理",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isBatchMode) AppleRed else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // ================= 添加 RSS 输入卡片 =================
            item(key = "podcast_manage_add_card") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RssFeed,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "添加播客 RSS 订阅",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "支持标准 http(s) RSS / Atom 源地址",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 输入框与按键排
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 输入框：必须带 Modifier.tvTextFieldKeys(exitOnRight = true)
                            OutlinedTextField(
                                value = inputUrl,
                                onValueChange = {
                                    inputUrl = it
                                    if (ui.addError.isNotBlank() || ui.lastAddedTitle.isNotBlank()) {
                                        actions.onDismissManageMessage()
                                    }
                                },
                                placeholder = {
                                    Text(
                                        text = "https://example.com/podcast.xml (支持遥控器呼出输入法或一键粘贴)",
                                        fontSize = 12.sp
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvTextFieldKeys(exitOnRight = true)
                            )

                            // 一键粘贴剪贴板按钮 (TV 极其实用)
                            OutlinedButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text.orEmpty().trim()
                                    if (clip.isNotBlank()) {
                                        inputUrl = clip
                                        if (ui.addError.isNotBlank() || ui.lastAddedTitle.isNotBlank()) {
                                            actions.onDismissManageMessage()
                                        }
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
                                        focusKey = "podcast_manage_paste_btn"
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "粘贴",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // 确认添加按钮
                            val canAdd = looksLikeFeedUrl(inputUrl) && !ui.isAdding
                            Button(
                                onClick = {
                                    // 不在这里清空输入：添加失败时保留地址方便修改，成功后由上方 LaunchedEffect 清空
                                    if (canAdd) actions.onAddRss(inputUrl.trim())
                                },
                                // Claude 修改：不再用 enabled 禁用（获焦的按钮一被禁用焦点就丢、光标跳到最左），改为变淡 + 点击时判断
                                colors = ButtonDefaults.buttonColors(containerColor = if (canAdd) AppleRed else AppleRed.copy(alpha = 0.35f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .tvButtonFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedScale = 1.05f,
                                        focusKey = "podcast_manage_submit_add"
                                    )
                            ) {
                                if (ui.isAdding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "解析抓取中...", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "添加订阅", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // 状态反馈提示横条（成功添加 / 失败信息）
                        if (ui.lastAddedTitle.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x2634C759),
                                border = BorderStroke(1.dp, Color(0x6634C759)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF34C759),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "已成功添加订阅：《${ui.lastAddedTitle}》（服务器已收录单集）",
                                            fontSize = 12.sp,
                                            color = Color(0xFF34C759)
                                        )
                                    }
                                    IconButton(
                                        onClick = actions.onDismissManageMessage,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "关闭",
                                            tint = Color(0xFF34C759),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (ui.addError.isNotBlank()) {
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
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = AppleRed,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "添加失败：${ui.addError}",
                                            fontSize = 12.sp,
                                            color = AppleRed,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = actions.onDismissManageMessage,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "关闭",
                                            tint = AppleRed,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ================= 批量操作工具条 =================
            if (isBatchMode) {
                item(key = "podcast_manage_batch_bar") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已选中 ${selectedIds.size} / ${ui.shows.size} 个节目",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        if (selectedIds.size == ui.shows.size) selectedIds.clear()
                                        else {
                                            selectedIds.clear()
                                            selectedIds.addAll(ui.shows.map { it.id })
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (selectedIds.size == ui.shows.size) "取消全选" else "全选",
                                        fontSize = 12.sp
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (selectedIds.isNotEmpty()) {
                                            showBatchDeleteConfirm = true
                                        }
                                    },
                                    // Claude 修改：不再用 enabled 禁用（获焦的按钮一被禁用焦点就丢、光标跳到最左），改为变淡 + 点击时判断
                                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedIds.isNotEmpty()) AppleRed else AppleRed.copy(alpha = 0.35f)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "确认删除选中项 (${selectedIds.size})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ================= 已订阅节目列表 =================
            item(key = "podcast_manage_list_header") {
                Text(
                    text = "当前服务器已订阅 (${ui.shows.size} 个)",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }

            // 加载中 / 未连服务器 / 出错（Claude 接线时补充，与手机端一致）
            val blocking = ui.shows.isEmpty() && ui.state in setOf(
                PodcastLoadState.LOADING, PodcastLoadState.NO_SERVER, PodcastLoadState.UNSUPPORTED, PodcastLoadState.ERROR
            )
            if (blocking) {
                item(key = "podcast_manage_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (ui.state == PodcastLoadState.LOADING) {
                            CircularProgressIndicator(color = AppleRed)
                        } else {
                            Text(
                                text = ui.message.ifBlank { "无法读取服务器上的订阅列表" },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (ui.state == PodcastLoadState.NO_SERVER) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(12.dp),
                                            focusKey = "podcast_manage_go_settings",
                                            onClick = actions.onGoToServerSettings
                                        )
                                    ) {
                                        Text("去设置服务器", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = AppleRed,
                                    modifier = Modifier.tvFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusKey = "podcast_manage_retry",
                                        onClick = actions.onRetry
                                    )
                                ) {
                                    Text("重试", fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                                }
                            }
                        }
                    }
                }
            } else if (ui.shows.isEmpty()) {
                item(key = "podcast_manage_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "服务器上还没有任何订阅源，请在上方输入 RSS 地址添加。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = ui.shows,
                    key = { s -> "manage_show_${s.id}" }
                ) { show ->
                    TvPodcastManageRow(
                        show = show,
                        isBatchMode = isBatchMode,
                        isSelected = selectedIds.contains(show.id),
                        onToggleSelect = {
                            if (selectedIds.contains(show.id)) selectedIds.remove(show.id)
                            else selectedIds.add(show.id)
                        },
                        onCopyUrl = {
                            if (show.feedUrl.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(show.feedUrl))
                            }
                        },
                        onFetch = { actions.onFetchShow(show.id) },
                        onDelete = { deleteCandidateShow = show },
                        onOpenShow = { actions.onOpenShow(show.id) }
                    )
                }
            }
        }
    }
}

/** 单个订阅管理行组件 */
@Composable
private fun TvPodcastManageRow(
    show: PodcastShowUi,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onCopyUrl: () -> Unit,
    onFetch: () -> Unit,
    onDelete: () -> Unit,
    onOpenShow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(
            1.dp,
            if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.02f,
                focusKey = "podcast_manage_row_${show.id}",
                onClick = {
                    if (isBatchMode) onToggleSelect()
                    else onOpenShow()
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
            // 批量勾选复选框
            if (isBatchMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                    modifier = Modifier.size(24.dp)
                )
            }

            // 封面
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                AlbumArtworkImage(
                    model = show.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 12.dp,
                    seedId = show.id,
                    targetSize = 160
                )
            }

            // 标题、URL 与状态
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = show.title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (show.feedUrl.isNotBlank()) {
                    Text(
                        text = show.feedUrl,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "共 ${show.episodeCount} 期 · ${show.updatedText.ifBlank { "近期更新" }}",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (show.errorText.isNotBlank()) {
                        Text(
                            text = "抓取异常",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB300)
                        )
                    }
                }
            }

            // 操作按键组（非批量模式下可用）
            if (!isBatchMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 复制 RSS 按钮
                    if (show.feedUrl.isNotBlank()) {
                        IconButton(
                            onClick = onCopyUrl,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制地址",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // 重新抓取按钮
                    IconButton(
                        onClick = onFetch,
                        modifier = Modifier.size(32.dp)
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
                                contentDescription = "重新拉取",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // 删除按钮
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (show.isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = AppleRed,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "删除订阅",
                                tint = AppleRed,
                                modifier = Modifier.size(16.dp)
                            )
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
fun TvPodcastManageScreenPreviewTvDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF121216)) {
            TvPodcastManageScreen(
                ui = TvPodcastPreviewData.sampleManageUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "TV 1080p 浅色", widthDp = 960, heightDp = 540)
@Composable
fun TvPodcastManageScreenPreviewTvLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(color = Color(0xFFF2F2F7)) {
            TvPodcastManageScreen(
                ui = TvPodcastPreviewData.sampleManageUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "车机超宽 深色", widthDp = 1280, heightDp = 480)
@Composable
fun TvPodcastManageScreenPreviewCarWide() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF101014)) {
            TvPodcastManageScreen(
                ui = TvPodcastPreviewData.sampleManageUi,
                actions = PodcastActions()
            )
        }
    }
}

@Preview(name = "内容区占位尺寸", widthDp = 680, heightDp = 460)
@Composable
fun TvPodcastManageScreenPreviewContentArea() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color(0xFF14141A)) {
            TvPodcastManageScreen(
                ui = TvPodcastPreviewData.sampleManageUi,
                actions = PodcastActions()
            )
        }
    }
}
