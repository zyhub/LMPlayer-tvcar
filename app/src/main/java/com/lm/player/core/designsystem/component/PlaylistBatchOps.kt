package com.lm.player.core.designsystem.component

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.PlaylistAddOutcome
import com.lm.player.core.model.PlaylistAddStatus
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 多选「加入歌单」（2026-11-14）：所有带多选的歌曲列表共用。
 *
 * 流程：选歌单（或新建）→ 进度条 → 结果。全部加入成功只提示一句；有「已存在（忽略）」或失败时弹出结果窗口，
 * 点各项数字可以看到对应的歌曲，失败的写明原因。
 *
 * 状态放在这里（进程级）、弹窗由 [PlaylistBatchOpsHost]（MainActivity 根部一次）显示：
 * 加入过程中切页 / 退出多选都不会打断正在进行的写入。真正的加入由 MainActivity 注册 [addHandler]。
 */
object PlaylistBatchOps {

    sealed class Ui {
        data class Picking(val songs: List<UnifiedSong>) : Ui()
        data class Running(val playlistName: String, val progress: Float, val step: String) : Ui()
        data class Result(val playlistName: String, val outcomes: List<PlaylistAddOutcome>) : Ui()
    }

    val ui = mutableStateOf<Ui?>(null)

    /** 可加入的歌单（MainActivity 维护：不含发现页 / 推荐歌单） */
    val playlists = mutableStateOf<List<UnifiedPlaylist>>(emptyList())

    /** 配置了柠檬服务器（云端歌单是否可选还要看服务器是否在线） */
    val serverConfigured = mutableStateOf(false)

    /** 批量加入：(目标歌单, 歌曲, 进度回调(0~1, 步骤)) → 每首的结果。由 MainActivity 注册 */
    @Volatile
    var addHandler: (suspend (UnifiedPlaylist, List<UnifiedSong>, (Float, String) -> Unit) -> List<PlaylistAddOutcome>)? = null

    /** 新建歌单（有服务器建在服务器上，否则本地），返回新歌单；失败返回 null。由 MainActivity 注册 */
    @Volatile
    var createHandler: (suspend (String) -> UnifiedPlaylist?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var onFinished: (() -> Unit)? = null

    /** 多选栏「加入歌单」：打开歌单选择；[onFinished] 在加入完成后调用（一般用来退出多选） */
    fun startAdd(context: Context, songs: List<UnifiedSong>, onFinished: () -> Unit = {}) {
        if (songs.isEmpty()) return
        if (ui.value is Ui.Running) return
        appContext = context.applicationContext
        this.onFinished = onFinished
        ui.value = Ui.Picking(songs)
    }

    fun dismiss() {
        if (ui.value is Ui.Running) return
        ui.value = null
    }

    fun pick(target: UnifiedPlaylist, songs: List<UnifiedSong>) {
        scope.launch { run(target, songs) }
    }

    fun createAndPick(name: String, songs: List<UnifiedSong>) {
        val clean = name.trim()
        if (clean.isBlank()) return
        scope.launch {
            ui.value = Ui.Running(clean, 0f, "新建歌单…")
            val created = try {
                createHandler?.invoke(clean)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (created == null) {
                ui.value = null
                toast("新建歌单「$clean」失败")
                return@launch
            }
            run(created, songs)
        }
    }

    private suspend fun run(target: UnifiedPlaylist, songs: List<UnifiedSong>) {
        ui.value = Ui.Running(target.name, 0f, "准备中…")
        val handler = addHandler
        val outcomes = if (handler == null) {
            songs.map { PlaylistAddOutcome(it, PlaylistAddStatus.FAILED, "加入功能还没准备好，请稍后再试") }
        } else try {
            handler(target, songs) { f, step ->
                ui.value = Ui.Running(target.name, f.coerceIn(0f, 1f), step)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            songs.map { PlaylistAddOutcome(it, PlaylistAddStatus.FAILED, e.message ?: "未知错误") }
        }
        onFinished?.invoke()
        onFinished = null
        val existed = outcomes.count { it.status == PlaylistAddStatus.EXISTED }
        val failed = outcomes.count { it.status == PlaylistAddStatus.FAILED }
        if (existed == 0 && failed == 0) {
            ui.value = null
            toast("已将 ${outcomes.size} 首加入「${target.name}」")
        } else {
            ui.value = Ui.Result(target.name, outcomes)
        }
    }

    private fun toast(text: String) {
        val ctx = appContext ?: return
        AppToast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }
}

/** 放在 MainActivity 根部一次：显示多选「加入歌单」的选择、进度与结果窗口 */
@Composable
fun PlaylistBatchOpsHost() {
    when (val state = PlaylistBatchOps.ui.value) {
        is PlaylistBatchOps.Ui.Picking -> BatchPlaylistPicker(state.songs)
        is PlaylistBatchOps.Ui.Running -> BatchProgressDialog(
            title = "正在加入「${state.playlistName}」",
            progress = state.progress,
            step = state.step
        )
        is PlaylistBatchOps.Ui.Result -> BatchAddResultDialog(state.playlistName, state.outcomes)
        null -> Unit
    }
}

/** 批量操作进度窗口（加入 / 移出歌单共用）：不可关闭，完成后由调用方关掉 */
@Composable
fun BatchProgressDialog(title: String, progress: Float, step: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = AppleRed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(step, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun BatchPlaylistPicker(songs: List<UnifiedSong>) {
    val all = PlaylistBatchOps.playlists.value
    val serverConfigured = PlaylistBatchOps.serverConfigured.value
    // 云端歌单要连着服务器才能改
    val serverUsable = serverConfigured && !com.lm.player.core.source.ServerLinkState.isLost
    val targets = remember(all, serverUsable) { all.filter { !it.isOnline || serverUsable } }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { PlaylistBatchOps.dismiss() },
        title = {
            Column {
                Text("加入歌单", fontWeight = FontWeight.Bold)
                Text("已选 ${songs.size} 首", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(40) },
                        singleLine = true,
                        label = { Text("新歌单名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { creating = false },
                            modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
                        ) { Text("返回") }
                        Button(
                            onClick = { PlaylistBatchOps.createAndPick(newName, songs) },
                            enabled = newName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
                        ) { Text("新建并加入") }
                    }
                } else {
                    PickerRow(
                        icon = { Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed) },
                        title = "新建歌单",
                        subtitle = if (serverUsable) "建在服务器上" else "建在本机",
                        onClick = { creating = true }
                    )
                    if (targets.isEmpty()) {
                        Text("还没有可加入的歌单，先新建一个", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(targets, key = { it.id }) { pl ->
                                PickerRow(
                                    icon = {
                                        Icon(
                                            if (pl.isOnline) Icons.Default.Cloud else Icons.Default.PhoneAndroid,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    title = pl.name,
                                    subtitle = "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${pl.songCount} 首",
                                    onClick = { PlaylistBatchOps.pick(pl, songs) }
                                )
                            }
                        }
                    }
                    if (serverConfigured && !serverUsable) {
                        Text("服务器离线，云端歌单暂时不能加入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { PlaylistBatchOps.dismiss() },
                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
            ) { Text("取消") }
        }
    )
}

@Composable
private fun PickerRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.01f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun BatchAddResultDialog(playlistName: String, outcomes: List<PlaylistAddOutcome>) {
    val added = remember(outcomes) { outcomes.filter { it.status == PlaylistAddStatus.ADDED } }
    val existed = remember(outcomes) { outcomes.filter { it.status == PlaylistAddStatus.EXISTED } }
    val failed = remember(outcomes) { outcomes.filter { it.status == PlaylistAddStatus.FAILED } }
    // 默认展开失败的明细（有的话）
    var shown by remember(outcomes) { mutableStateOf(if (failed.isNotEmpty()) PlaylistAddStatus.FAILED else null) }

    AlertDialog(
        onDismissRequest = { PlaylistBatchOps.dismiss() },
        title = { Text("加入「$playlistName」", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatChip("已添加", added.size, Color(0xFF34C759), shown == PlaylistAddStatus.ADDED, Modifier.weight(1f)) {
                        shown = if (shown == PlaylistAddStatus.ADDED) null else PlaylistAddStatus.ADDED
                    }
                    StatChip("已存在·已忽略", existed.size, Color(0xFFFF9F0A), shown == PlaylistAddStatus.EXISTED, Modifier.weight(1f)) {
                        shown = if (shown == PlaylistAddStatus.EXISTED) null else PlaylistAddStatus.EXISTED
                    }
                    StatChip("失败", failed.size, Color(0xFFFF3B30), shown == PlaylistAddStatus.FAILED, Modifier.weight(1f)) {
                        shown = if (shown == PlaylistAddStatus.FAILED) null else PlaylistAddStatus.FAILED
                    }
                }
                val list = when (shown) {
                    PlaylistAddStatus.ADDED -> added
                    PlaylistAddStatus.EXISTED -> existed
                    PlaylistAddStatus.FAILED -> failed
                    null -> emptyList()
                }
                if (shown == null) {
                    Text("点上面的数字查看对应的歌曲（失败的写明原因）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (list.isEmpty()) {
                    Text("没有", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(list) { o ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusable(shape = RoundedCornerShape(8.dp), focusedScale = 1f, onClick = {})
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "${o.song.title} · ${o.song.artist}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (o.reason.isNotBlank() && o.status != PlaylistAddStatus.ADDED) {
                                    Text(
                                        o.reason,
                                        fontSize = 11.sp,
                                        color = if (o.status == PlaylistAddStatus.FAILED) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { PlaylistBatchOps.dismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
            ) { Text("完成") }
        }
    )
}

@Composable
private fun StatChip(label: String, count: Int, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color.copy(alpha = 0.18f) else color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, if (selected) color else color.copy(alpha = 0.3f)),
        modifier = modifier.tvFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.03f, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
