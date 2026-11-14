package com.lm.player.core.designsystem.component

import com.lm.player.core.designsystem.component.TvDialog as Dialog
import com.lm.player.core.designsystem.component.TvDropdownMenu as DropdownMenu
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha

/**
 * 歌单里有哪些歌（内存登记，2026-11-13 从手机端移植）：服务器 / 云端歌单的歌是现拉的、不进本地 playlist_songs 表，
 * 只查表的话「从歌单里播放的歌」在加入歌单时不会显示「已加入」。打开歌单、加入歌单时在这里登记，
 * 按歌曲 id 与「歌名|歌手」两种键匹配（播放时歌曲 id 可能被匹配成本地 / 服务器曲目）。
 */
object PlaylistMembership {
    private val members = androidx.compose.runtime.mutableStateMapOf<String, Set<String>>()

    private fun norm(s: String) = s.lowercase().replace(Regex("""[\s·・()（）\[\]【】'"\-_]"""), "")
    private fun keysOf(song: UnifiedSong): List<String> = buildList {
        add("id:" + song.id)
        val t = norm(song.title)
        if (t.isNotBlank()) add("ta:" + t + "|" + norm(song.artist))
    }

    /** 打开歌单拿到歌曲列表时调用（整份替换） */
    fun record(playlistId: String, songs: List<UnifiedSong>) {
        if (playlistId.isBlank()) return
        members[playlistId] = songs.flatMapTo(HashSet()) { keysOf(it) }
    }

    /** 加入歌单成功后调用 */
    fun add(playlistId: String, song: UnifiedSong) {
        if (playlistId.isBlank()) return
        members[playlistId] = (members[playlistId] ?: emptySet()) + keysOf(song)
    }

    fun playlistsContaining(song: UnifiedSong): Set<String> {
        val keys = keysOf(song)
        return members.entries.filter { (_, v) -> keys.any { it in v } }.mapTo(HashSet()) { it.key }
    }
}

/** 已收录这首歌的歌单 id：本地表（实时）+ 内存登记的服务器歌单；只在菜单展开时订阅 */
@Composable
private fun rememberJoinedPlaylistIds(song: UnifiedSong): Set<String> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val songId = song.id
    val flow = remember(songId) {
        com.lm.player.core.database.ZdsDatabase.getInstance(context.applicationContext)
            .playlistDao()
            .getPlaylistIdsContainingSongFlow(songId)
    }
    val ids by flow.collectAsState(initial = emptyList())
    val registered = PlaylistMembership.playlistsContaining(song)
    return remember(ids, registered) { ids.toSet() + registered }
}


/**
 * 添加歌曲至歌单弹窗
 * 支持选择现有自建/云端歌单，或快速新建歌单并收录
 */
@Composable
fun AddToPlaylistDialog(
    song: UnifiedSong,
    playlists: List<UnifiedPlaylist>,
    isServerConnected: Boolean = false,
    onSelectPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit,
    onCreatePlaylistAndAdd: (String, UnifiedSong) -> Unit,
    onDismiss: () -> Unit
) {
    var isCreatingNew by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 1. 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "收藏到歌单",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${song.title} · ${song.artist}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .tvButtonFocusable(shape = RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. 新建歌单入口 / 展开的输入框
                if (!isCreatingNew) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = AppleRed.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(
                                shape = RoundedCornerShape(14.dp),
                                focusedScale = 1.02f,
                                onClick = { isCreatingNew = true }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppleRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "新建歌单",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleRed
                                )
                                Text(
                                    text = if (isServerConnected) "并自动同步至服务器收藏与本地" else "保存在本地歌单中",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "新建歌单名称",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            placeholder = { Text("输入歌单名称...", fontSize = 13.sp) },
                            singleLine = true,
                            modifier = Modifier.tvTextFieldKeys().fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppleRed,
                                unfocusedBorderColor = borderColor
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    isCreatingNew = false
                                    newPlaylistName = ""
                                },
                                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
                            ) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    val name = newPlaylistName.trim()
                                    if (name.isNotEmpty()) {
                                        onCreatePlaylistAndAdd(name, song)
                                        onDismiss()
                                    }
                                },
                                enabled = newPlaylistName.trim().isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
                            ) {
                                Text("创建并添加", fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. 现有歌单列表
                Text(
                    text = "我的歌单 (${playlists.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                )

                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无自建歌单，可点击上方按钮新建",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(playlists, key = { index, _ -> com.lm.player.core.designsystem.component.StableListKeys.keyAt(playlists, index, "add_pl_") { it.id } }) { _, pl ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedScale = 1.02f,
                                        onClick = {
                                            onSelectPlaylist(pl, song)
                                            onDismiss()
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (pl.isOnline) AppleRed.copy(alpha = 0.15f)
                                                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (pl.isOnline) Icons.Default.Cloud else Icons.Default.QueueMusic,
                                            contentDescription = null,
                                            tint = if (pl.isOnline) AppleRed else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pl.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${pl.songCount} 首曲目 · ${if (pl.isOnline) "云端同步" else "本地存储"}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Icon(
                                        imageVector = Icons.Default.AddCircleOutline,
                                        contentDescription = "添加",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
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

/**
 * 「加入歌单」原位弹出菜单（2026-11-13 照手机端逻辑移植，遥控器可操作）：
 * 顶部标题 + 右侧「新建」小胶囊；点新建在原位出现一行输入框（确定 / ✓ 创建并收录）；
 * 下面是**全部**歌单（超过高度内部滚动，以前只列前 8 个），已收录这首歌的歌单置灰、不可再点、标「已加入」；
 * 歌单多（> 8）时多一个搜索框。
 */
@Composable
fun AddToPlaylistDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong,
    playlists: List<UnifiedPlaylist>,
    isServerConnected: Boolean = false,
    onSelectPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit,
    onCreatePlaylistAndAdd: (String, UnifiedSong) -> Unit,
    modifier: Modifier = Modifier
) {
    var isCreatingNew by remember(expanded) { mutableStateOf(false) }
    var newPlaylistName by remember(expanded) { mutableStateOf("") }
    var query by remember(expanded) { mutableStateOf("") }
    val joinedIds = if (expanded) rememberJoinedPlaylistIds(song) else emptySet()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val gold = Color(0xFFFFC947)
    val firstFocus = remember { FocusRequester() }
    val inputFocus = remember { FocusRequester() }
    val shown = remember(playlists, query) {
        val q = query.trim()
        if (q.isEmpty()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }
    // 打开时光标落在第一个还能加入的歌单上（都已加入时落在「新建」）
    val firstJoinable = shown.firstOrNull { it.id !in joinedIds }?.id
    LaunchedEffect(expanded) {
        if (expanded) {
            kotlinx.coroutines.delay(80L)
            runCatching { firstFocus.requestFocus() }
        }
    }
    val createNow = {
        if (newPlaylistName.isNotBlank()) {
            onCreatePlaylistAndAdd(newPlaylistName.trim(), song)
            onDismissRequest()
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(300.dp)
    ) {
        // 1. 标题行：加入歌单 + 歌名；右侧「新建」
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("加入歌单", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = onSurface)
                Text(song.title, fontSize = 11.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!isCreatingNew) {
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .then(if (firstJoinable == null) Modifier.focusRequester(firstFocus) else Modifier)
                        .tvFocusable(shape = RoundedCornerShape(15.dp), focusedScale = 1.05f, focusedBorderColor = gold) {
                            isCreatingNew = true
                        }
                        .background(AppleRed.copy(alpha = 0.14f), RoundedCornerShape(15.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("新建", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppleRed)
                }
            }
        }

        // 2. 新建：原位一行输入框
        if (isCreatingNew) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(onSurface.copy(alpha = 0.07f))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (newPlaylistName.isEmpty()) {
                        Text(
                            text = if (isServerConnected) "新歌单名称（双端同步）" else "新歌单名称",
                            fontSize = 13.sp,
                            color = sub.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it.take(30) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleRed),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { createNow() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvTextFieldKeys()
                            .focusRequester(inputFocus)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .tvFocusable(shape = CircleShape, focusedScale = 1.08f, focusedBorderColor = gold) {
                            isCreatingNew = false
                            newPlaylistName = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, contentDescription = "取消", tint = sub, modifier = Modifier.size(16.dp))
                }
                val ok = newPlaylistName.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .tvFocusable(shape = CircleShape, focusedScale = 1.08f, focusedBorderColor = gold, enabled = ok) { createNow() }
                        .background(if (ok) AppleRed else onSurface.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "创建并收录", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }
            Spacer(Modifier.height(4.dp))
        }

        // 3. 歌单多时：搜索
        if (playlists.size > 8 && !isCreatingNew) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(onSurface.copy(alpha = 0.07f))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = sub, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("搜索歌单", fontSize = 12.sp, color = sub.copy(alpha = 0.7f))
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleRed),
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys()
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(onSurface.copy(alpha = 0.10f))
        )

        // 4. 歌单列表（全部；超过高度内部滚动）
        if (shown.isEmpty()) {
            Text(
                text = if (playlists.isEmpty()) "还没有歌单，点右上角「新建」" else "没有匹配的歌单",
                fontSize = 12.sp,
                color = sub,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                shown.forEach { pl ->
                    val joined = pl.id in joinedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .then(if (pl.id == firstJoinable) Modifier.focusRequester(firstFocus) else Modifier)
                            // 已收录这首歌的歌单：置灰、不可再点，右侧显示「已加入」
                            .tvFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f, focusedBorderColor = gold, enabled = !joined) {
                                onSelectPlaylist(pl, song)
                                onDismissRequest()
                            }
                            .alpha(if (joined) 0.45f else 1f)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (pl.isOnline) Icons.Default.Cloud else Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = if (pl.isOnline) "云歌单" else "本地歌单",
                            tint = if (pl.isOnline) AppleRed else sub,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = pl.name,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (joined) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = sub, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("已加入", fontSize = 11.sp, color = sub)
                        } else {
                            Text("${pl.songCount} 首", fontSize = 11.sp, color = sub)
                        }
                    }
                }
            }
        }
    }
}
