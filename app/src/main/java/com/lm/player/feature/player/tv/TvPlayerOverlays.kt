package com.lm.player.feature.player.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AddToPlaylistDropdownMenu
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import com.lm.player.feature.player.SleepTimerDropdownMenu
import kotlinx.coroutines.delay

/** 「词」展开状态：-1 收起 / 0 竖条 / 1 字号 / 2 快慢 / 3 主题与选项 */
object LyricsPanel {
    const val CLOSED = -1
    const val STRIP = 0
    const val FONT = 1
    const val OFFSET = 2
    const val OPTIONS = 3
}

private suspend fun FocusRequester.requestSoon() {
    delay(80L)
    try { requestFocus() } catch (_: Exception) {}
}

// =====================================================================================
// 歌词调节「词」：照手机端的形态 —— 竖条（字号 / 快慢 / 主题与选项）→
// 字号、快慢在竖条里换成「＋ / 数值 / －」（只变长不变宽）；主题与选项向左扩成方块。
// 手机端从按钮向下长，TV 胶囊在底部，所以向上长。
// =====================================================================================

@Composable
fun TvLyricsAdjustStrip(
    panel: Int,
    theme: PlayerThemeStyle,
    onPanelChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = LocalAppDimensions.current
    val stripW = dims.scale(60.dp)
    val item = dims.scale(48.dp)
    val optionsW = dims.scale(300.dp)
    val row = dims.scale(46.dp)
    val targetW = if (panel == LyricsPanel.OPTIONS) optionsW else stripW
    val targetH = when (panel) {
        LyricsPanel.FONT -> item * 3 + dims.scale(64.dp) + dims.scale(24.dp)
        LyricsPanel.OFFSET -> item * 4 + dims.scale(64.dp) + dims.scale(30.dp)
        LyricsPanel.OPTIONS -> row * 8 + dims.scale(28.dp)
        else -> item * 3 + dims.scale(30.dp)
    }
    val w by animateDpAsState(targetW, spring(dampingRatio = 0.66f, stiffness = 300f), label = "lyrics_strip_w")
    val h by animateDpAsState(targetH, spring(dampingRatio = 0.62f, stiffness = 300f), label = "lyrics_strip_h")

    Box(
        modifier = modifier
            .width(w)
            .height(h)
            .clip(RoundedCornerShape(stripW / 2))
            .background(LocalTvPlayerColors.current.panel)
            .border(1.dp, LocalTvPlayerColors.current.panelBorder, RoundedCornerShape(stripW / 2))
    ) {
        AnimatedContent(
            targetState = panel,
            transitionSpec = {
                val forward = targetState > initialState
                val t = if (forward) {
                    (slideInVertically(tween(260)) { -it / 2 } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                        (slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(140)))
                } else {
                    (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                        (slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(140)))
                }
                t using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.BottomEnd,
            modifier = Modifier.fillMaxSize(),
            label = "lyrics_strip_content"
        ) { p ->
            when (p) {
                LyricsPanel.FONT -> VerticalStepper(
                    valueText = "${TvPlayerPrefs.fontFor(theme).toInt()}",
                    caption = "字号",
                    highlight = false,
                    item = item,
                    onPlus = { TvPlayerPrefs.updateFont(theme, TvPlayerPrefs.fontFor(theme) + TvPlayerPrefs.FONT_STEP) },
                    onMinus = { TvPlayerPrefs.updateFont(theme, TvPlayerPrefs.fontFor(theme) - TvPlayerPrefs.FONT_STEP) },
                    onBack = { onPanelChange(LyricsPanel.STRIP) }
                )
                LyricsPanel.OFFSET -> {
                    val off = TvPlayerPrefs.offsetMs
                    VerticalStepper(
                        valueText = when {
                            off > 0 -> "+%.1f".format(off / 1000.0)
                            off < 0 -> "%.1f".format(off / 1000.0)
                            else -> "0"
                        },
                        caption = when { off > 0 -> "提前"; off < 0 -> "延后"; else -> "同步" },
                        highlight = off != 0L,
                        item = item,
                        onPlus = { TvPlayerPrefs.updateOffset(off + 500L) },
                        onMinus = { TvPlayerPrefs.updateOffset(off - 500L) },
                        onBack = { onPanelChange(LyricsPanel.STRIP) },
                        extra = {
                            StripIcon(Icons.Filled.Replay, "恢复同步", if (off != 0L) AppleRed else LocalTvPlayerColors.current.text.copy(alpha = 0.35f), item) {
                                TvPlayerPrefs.updateOffset(0L)
                            }
                        }
                    )
                }
                LyricsPanel.OPTIONS -> OptionsPanel(theme, row, onBack = { onPanelChange(LyricsPanel.STRIP) })
                else -> {
                    val first = remember { FocusRequester() }
                    LaunchedEffect(Unit) { first.requestSoon() }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(vertical = dims.scale(10.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(dims.scale(5.dp), Alignment.Bottom)
                    ) {
                        StripIcon(Icons.Filled.MoreHoriz, "主题与选项", LocalTvPlayerColors.current.text, item) { onPanelChange(LyricsPanel.OPTIONS) }
                        StripIcon(Icons.Filled.Timer, "快慢", if (TvPlayerPrefs.offsetMs != 0L) AppleRed else LocalTvPlayerColors.current.text, item) {
                            onPanelChange(LyricsPanel.OFFSET)
                        }
                        StripIcon(Icons.Filled.TextFields, "字号", LocalTvPlayerColors.current.text, item, first) { onPanelChange(LyricsPanel.FONT) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StripIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    size: Dp,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusable(shape = CircleShape, focusedScale = 1.1f, focusedBorderColor = LocalTvPlayerColors.current.accent, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
private fun VerticalStepper(
    valueText: String,
    caption: String,
    highlight: Boolean,
    item: Dp,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onBack: () -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    val dims = LocalAppDimensions.current
    val plus = remember { FocusRequester() }
    LaunchedEffect(Unit) { plus.requestSoon() }
    val color = if (highlight) AppleRed else LocalTvPlayerColors.current.text
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = dims.scale(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        StripIcon(Icons.Filled.Add, "加", LocalTvPlayerColors.current.text, item, plus, onPlus)
        Column(
            modifier = Modifier.height(dims.scale(64.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(valueText, fontSize = dims.bodySize, fontWeight = FontWeight.Bold, color = color, maxLines = 1, softWrap = false)
            Text(caption, fontSize = dims.badgeSize, color = color.copy(alpha = 0.6f), maxLines = 1, softWrap = false)
        }
        StripIcon(Icons.Filled.Remove, "减", LocalTvPlayerColors.current.text, item, onClick = onMinus)
        extra?.invoke()
        Spacer(Modifier.height(dims.scale(4.dp)))
        StripIcon(Icons.Filled.KeyboardArrowDown, "收回到竖条", LocalTvPlayerColors.current.text.copy(alpha = 0.6f), item * 0.8f, onClick = onBack)
    }
}

@Composable
private fun OptionsPanel(theme: PlayerThemeStyle, row: Dp, onBack: () -> Unit) {
    val dims = LocalAppDimensions.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestSoon() }
    val style = TvPlayerPrefs.styleFor(theme)
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = dims.scale(10.dp), vertical = dims.scale(10.dp))) {
        Row(
            modifier = Modifier
                .height(row)
                .tvFocusable(shape = RoundedCornerShape(14.dp), focusedScale = 1f, focusedBorderColor = LocalTvPlayerColors.current.accent, onClick = onBack)
                .padding(horizontal = dims.scale(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "返回", tint = LocalTvPlayerColors.current.text.copy(alpha = 0.8f), modifier = Modifier.size(dims.scale(22.dp)))
            Text("主题与选项 · ${theme.displayName}", fontSize = dims.bodySize, fontWeight = FontWeight.SemiBold, color = LocalTvPlayerColors.current.text, maxLines = 1)
        }
        LyricDisplayStyle.entries.forEachIndexed { i, s ->
            OptionRow(
                title = s.title,
                subtitle = s.subtitle,
                checked = style == s,
                radio = true,
                height = row,
                focusRequester = if (i == 0) first else null
            ) { TvPlayerPrefs.updateStyle(theme, s) }
        }
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(LocalTvPlayerColors.current.text.copy(alpha = 0.1f)))
        OptionRow("逐字流光", null, TvLyricSettings.karaoke, false, row) { TvLyricSettings.updateKaraoke(!TvLyricSettings.karaoke) }
        OptionRow("翻译", null, TvLyricSettings.translation, false, row) { TvLyricSettings.updateTranslation(!TvLyricSettings.translation) }
        // 「卡拉 OK」样式下景深模糊自动关闭：显示为未勾选、灰色不可点（不改用户的设置，换回「流光」即恢复）
        val blurLocked = style == LyricDisplayStyle.KARAOKE
        OptionRow(
            title = "景深模糊",
            subtitle = if (blurLocked) "卡拉 OK 样式下自动关闭" else null,
            checked = TvLyricSettings.depthBlur && !blurLocked,
            radio = false,
            height = row,
            enabled = !blurLocked
        ) { TvLyricSettings.updateDepthBlur(!TvLyricSettings.depthBlur) }
        OptionRow("系统字体", null, TvLyricSettings.systemFont, false, row) { TvLyricSettings.updateSystemFont(!TvLyricSettings.systemFont) }
    }
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    radio: Boolean,
    height: Dp,
    focusRequester: FocusRequester? = null,
    /** false：灰色、不可点（遥控器也选不中） */
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val dims = LocalAppDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusable(
                shape = RoundedCornerShape(14.dp),
                focusedScale = 1f,
                focusedBorderColor = LocalTvPlayerColors.current.accent,
                enabled = enabled,
                onClick = onClick
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .padding(horizontal = dims.scale(10.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = dims.bodySize, fontWeight = FontWeight.Medium, color = LocalTvPlayerColors.current.text, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, fontSize = dims.badgeSize, color = LocalTvPlayerColors.current.text.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val icon = when {
            radio && checked -> Icons.Filled.RadioButtonChecked
            radio -> Icons.Filled.RadioButtonUnchecked
            checked -> Icons.Filled.CheckBox
            else -> Icons.Filled.CheckBoxOutlineBlank
        }
        Icon(icon, contentDescription = null, tint = if (checked) AppleRed else LocalTvPlayerColors.current.text.copy(alpha = 0.45f), modifier = Modifier.size(dims.scale(22.dp)))
    }
}


// =====================================================================================
// 「更多」面板：加入歌单 / 睡眠定时 / 音质信息 + 主题（车机不需要音频输出 / 投屏，已删除）。
// 专辑卡片主题的小胶囊没有收藏 / 下载，这两项放进本面板（[playbackExtras]）。
// 遥控器焦点全部显式指定：原先第一格向右时，系统按几何位置把焦点送到了右侧歌词行上（看不见的焦点），
// 表现为「加入歌单 / 黑胶唱片向右光标消失」。
// =====================================================================================

/** 专辑卡片主题放进「更多」的收藏 / 下载 */
class MorePlaybackExtras(
    val isFavorite: Boolean,
    val isDownloaded: Boolean,
    val isServerConnected: Boolean,
    val onToggleFavorite: () -> Unit,
    val onDownload: (com.lm.player.core.model.DownloadTarget, com.lm.player.core.model.AudioQuality) -> Unit
)

@Composable
fun TvMorePanel(
    song: UnifiedSong,
    theme: PlayerThemeStyle,
    quality: TvQualityInfo,
    allPlaylists: List<UnifiedPlaylist>,
    isServerConnected: Boolean,
    activeTimerMinutes: Int,
    sleepRemainingSec: Long,
    playbackExtras: MorePlaybackExtras?,
    onSelectTheme: (PlayerThemeStyle) -> Unit,
    onSelectTimer: (Int) -> Unit,
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit,
    onCreatePlaylistAndAdd: (String, UnifiedSong) -> Unit,
    onSubMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    // 0 无 / 1 歌单 / 2 定时 / 3 音质 / 4 下载
    var openMenu by remember { mutableStateOf(0) }
    LaunchedEffect(openMenu) { onSubMenuOpenChange(openMenu != 0) }
    val favFocus = remember { FocusRequester() }
    val dlFocus = remember { FocusRequester() }
    val playlistFocus = remember { FocusRequester() }
    val timerFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val themeFocus = remember { List(TvPlayerPrefs.selectableThemes.size) { FocusRequester() } }
    val tiles = if (playbackExtras != null) listOf(favFocus, dlFocus, playlistFocus, timerFocus, qualityFocus)
    else listOf(playlistFocus, timerFocus, qualityFocus)
    LaunchedEffect(Unit) { tiles.first().requestSoon() }
    fun refocus(r: FocusRequester) { try { r.requestFocus() } catch (_: Exception) {} }
    fun tileNav(i: Int): Modifier = Modifier.focusProperties {
        left = tiles.getOrNull(i - 1) ?: FocusRequester.Cancel
        right = tiles.getOrNull(i + 1) ?: FocusRequester.Cancel
        up = FocusRequester.Cancel
        down = themeFocus[(i * themeFocus.size / tiles.size).coerceIn(0, themeFocus.lastIndex)]
    }
    fun themeNav(i: Int): Modifier = Modifier.focusProperties {
        left = themeFocus.getOrNull(i - 1) ?: FocusRequester.Cancel
        right = themeFocus.getOrNull(i + 1) ?: FocusRequester.Cancel
        up = tiles[(i * tiles.size / themeFocus.size).coerceIn(0, tiles.lastIndex)]
    }

    Column(
        modifier = modifier
            .width(dims.scale(if (playbackExtras != null) 680.dp else 560.dp))
            .background(colors.panel, RoundedCornerShape(dims.scale(22.dp)))
            .border(1.dp, colors.panelBorder, RoundedCornerShape(dims.scale(22.dp)))
            .padding(dims.scale(14.dp)),
        verticalArrangement = Arrangement.spacedBy(dims.scale(10.dp))
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(dims.scale(10.dp))) {
            var index = 0
            if (playbackExtras != null) {
                val favIndex = index++
                Box(Modifier.weight(1f)) {
                    MoreTile(
                        if (playbackExtras.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (playbackExtras.isFavorite) "已收藏" else "收藏",
                        playbackExtras.isFavorite, favFocus, tileNav(favIndex),
                        tint = if (playbackExtras.isFavorite) Color(0xFFFF2D55) else colors.text
                    ) { playbackExtras.onToggleFavorite() }
                }
                val dlIndex = index++
                Box(Modifier.weight(1f)) {
                    MoreTile(
                        if (playbackExtras.isDownloaded) Icons.Filled.CheckCircle else Icons.Filled.FileDownload,
                        if (playbackExtras.isDownloaded) "已下载" else "下载",
                        playbackExtras.isDownloaded, dlFocus, tileNav(dlIndex),
                        tint = if (playbackExtras.isDownloaded) Color(0xFF34C759) else colors.text
                    ) { openMenu = 4 }
                    TvDownloadPopup(
                        expanded = openMenu == 4,
                        onDismissRequest = { openMenu = 0; refocus(dlFocus) },
                        song = song,
                        isServerConnected = playbackExtras.isServerConnected,
                        hasLocal = playbackExtras.isDownloaded,
                        hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                        onConfirm = { target, q ->
                            openMenu = 0
                            playbackExtras.onDownload(target, q)
                            refocus(dlFocus)
                        }
                    )
                }
            }
            val timerLabel = when {
                activeTimerMinutes == -1 -> "播完本首"
                activeTimerMinutes > 0 && sleepRemainingSec > 0 -> formatPlayerTime(sleepRemainingSec * 1000L)
                else -> "睡眠定时"
            }
            val plIndex = index++
            Box(Modifier.weight(1f)) {
                MoreTile(Icons.AutoMirrored.Filled.PlaylistAdd, "加入歌单", false, playlistFocus, tileNav(plIndex)) { openMenu = 1 }
                AddToPlaylistDropdownMenu(
                    expanded = openMenu == 1,
                    onDismissRequest = { openMenu = 0; refocus(playlistFocus) },
                    song = song,
                    playlists = allPlaylists,
                    isServerConnected = isServerConnected,
                    onSelectPlaylist = { pl, s -> onAddToPlaylist(pl, s); openMenu = 0; refocus(playlistFocus) },
                    onCreatePlaylistAndAdd = { name, s -> onCreatePlaylistAndAdd(name, s); openMenu = 0; refocus(playlistFocus) }
                )
            }
            val timerIndex = index++
            Box(Modifier.weight(1f)) {
                MoreTile(Icons.Outlined.Bedtime, timerLabel, activeTimerMinutes != 0, timerFocus, tileNav(timerIndex)) { openMenu = 2 }
                SleepTimerDropdownMenu(
                    expanded = openMenu == 2,
                    onDismissRequest = { openMenu = 0; refocus(timerFocus) },
                    activeTimerMinutes = activeTimerMinutes,
                    remainingSeconds = sleepRemainingSec,
                    onSelectTimer = { onSelectTimer(it); openMenu = 0; refocus(timerFocus) }
                )
            }
            val qIndex = index
            Box(Modifier.weight(1f)) {
                MoreTile(
                    Icons.Outlined.Info, quality.label, false, qualityFocus, tileNav(qIndex),
                    tint = tvQualityColor(quality.tier, colors.isDark)
                ) { openMenu = 3 }
                TvSongInfoPopup(expanded = openMenu == 3, onDismissRequest = { openMenu = 0; refocus(qualityFocus) }, song = song)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.scale(10.dp))
        ) {
            Text("主题", fontSize = dims.captionSize, color = colors.textSecondary, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
            TvPlayerPrefs.selectableThemes.forEachIndexed { i, t ->
                val selected = t == theme
                val icon = when (t) {
                    PlayerThemeStyle.IPOD_RETRO -> Icons.Filled.ViewCarousel
                    PlayerThemeStyle.NETEASE_TV_COVER -> Icons.Filled.Wallpaper
                    PlayerThemeStyle.RECORD_SLEEVE -> Icons.Filled.LibraryMusic
                    else -> Icons.Filled.Album
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(dims.scale(48.dp))
                        .focusRequester(themeFocus[i])
                        .then(themeNav(i))
                        .tvFocusable(shape = RoundedCornerShape(14.dp), focusedScale = 1.05f, focusedBorderColor = colors.accent) { onSelectTheme(t) }
                        .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.subtleFill, RoundedCornerShape(14.dp))
                        .border(1.dp, if (selected) colors.accent.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(icon, contentDescription = null, tint = if (selected) colors.accent else colors.text, modifier = Modifier.size(dims.scale(20.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(t.displayName, fontSize = dims.captionSize, fontWeight = FontWeight.Medium, color = if (selected) colors.accent else colors.text, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MoreTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    focusRequester: FocusRequester,
    navigation: Modifier,
    tint: Color = if (active) LocalTvPlayerColors.current.accent else LocalTvPlayerColors.current.text,
    onClick: () -> Unit
) {
    val dims = LocalAppDimensions.current
    val colors = LocalTvPlayerColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(dims.scale(78.dp))
            .focusRequester(focusRequester)
            .then(navigation)
            .tvFocusable(shape = RoundedCornerShape(14.dp), focusedScale = 1.05f, focusedBorderColor = colors.accent, onClick = onClick)
            .background(colors.subtleFill, RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(dims.scale(24.dp)))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = dims.captionSize, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// =====================================================================================
// 待播列表：从右侧滑出，盖在任何主题上
// =====================================================================================

@Composable
fun TvQueueDrawer(
    playlist: List<UnifiedSong>,
    currentSongId: String,
    onSelect: (UnifiedSong) -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = LocalAppDimensions.current
    val currentIndex = playlist.indexOfFirst { it.id == currentSongId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { currentFocus.requestSoon() }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(LocalTvPlayerColors.current.panel)
            .border(1.dp, LocalTvPlayerColors.current.panelBorder)
            .padding(top = statusBarTop())
            .padding(horizontal = dims.scale(16.dp), vertical = dims.scale(20.dp))
    ) {
        Text(
            text = "待播列表 · ${playlist.size} 首",
            fontSize = dims.sectionTitleSize,
            fontWeight = FontWeight.Bold,
            color = LocalTvPlayerColors.current.text,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
        )
        if (playlist.isEmpty()) {
            Text("队列为空", fontSize = dims.bodySize, color = LocalTvPlayerColors.current.text.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(playlist, key = { i, s -> com.lm.player.core.designsystem.component.StableListKeys.keyAt(playlist, i, "queue_") { it.id } }) { index, s ->
                val isCurrent = s.id == currentSongId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == currentIndex) Modifier.focusRequester(currentFocus) else Modifier)
                        .tvFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.02f, focusedBorderColor = LocalTvPlayerColors.current.accent) { onSelect(s) }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isCurrent) LocalTvPlayerColors.current.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        fontSize = dims.captionSize,
                        color = if (isCurrent) LocalTvPlayerColors.current.accent else LocalTvPlayerColors.current.text.copy(alpha = 0.5f),
                        modifier = Modifier.width(dims.scale(34.dp))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            s.title,
                            fontSize = dims.itemTitleSize,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrent) LocalTvPlayerColors.current.accent else LocalTvPlayerColors.current.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(s.artist, fontSize = dims.captionSize, color = LocalTvPlayerColors.current.text.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isCurrent) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = LocalTvPlayerColors.current.accent, modifier = Modifier.size(dims.scale(20.dp)))
                    }
                }
            }
        }
    }
}

/** 遥控器操作的提示气泡（屏幕上方居中） */
@Composable
fun TvHudBubble(text: String, modifier: Modifier = Modifier) {
    val dims = LocalAppDimensions.current
    Text(
        text = text,
        fontSize = dims.bodySize,
        fontWeight = FontWeight.Bold,
        color = LocalTvPlayerColors.current.text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(LocalTvPlayerColors.current.panel)
            .border(1.dp, LocalTvPlayerColors.current.accent.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 9.dp)
    )
}
