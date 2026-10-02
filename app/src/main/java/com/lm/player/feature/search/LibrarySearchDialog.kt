package com.lm.player.feature.search

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.LocalPlatformMode
import com.lm.player.core.designsystem.component.tvButtonFocusable
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.PlatformMode
import com.lm.player.core.model.UnifiedSong
import com.lm.player.feature.home.OnlineSourceDropdownMenu
import com.lm.player.feature.home.SongListItemRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * KTV 点歌台式拼音首字母提取与高频词典引擎
 */
private object TvPinyinSearchHelper {
    private val initialsCache = ConcurrentHashMap<String, String>()
    private val gbkCharset: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    // GB2312 一级汉字拼音首字母区间表
    private val secPosValueList = intArrayOf(
        1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787,
        3106, 3212, 3472, 3635, 3722, 3730, 3858, 4027, 4086,
        4390, 4558, 4684, 4925, 5249, 5600
    )
    private val firstLetterArray = charArrayOf(
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j',
        'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
        't', 'w', 'x', 'y', 'z'
    )

    // KTV 点歌台高频歌手与金曲首字母速查表（支持遥控器输入首字母一键联想全网搜索）
    val ktvPopularPresets: List<Pair<String, String>> = listOf(
        "ZJL" to "周杰伦",
        "JJ" to "林俊杰",
        "LJJ" to "林俊杰",
        "CYX" to "陈奕迅",
        "DZQ" to "邓紫棋",
        "XZQ" to "薛之谦",
        "WLH" to "王力宏",
        "WF" to "王菲",
        "ZXY" to "张学友",
        "LDH" to "刘德华",
        "BYD" to "Beyond",
        "WYT" to "五月天",
        "LRH" to "李荣浩",
        "zjl" to "周杰伦",
        "QT" to "晴天",
        "DX" to "稻香",
        "QLX" to "七里香",
        "QHC" to "青花瓷",
        "YQD" to "夜曲",
        "告白气球" to "GBQQ",
        "GBQQ" to "告白气球",
        "FSH" to "富士山下",
        "GZY" to "孤勇者",
        "GYZ" to "孤勇者",
        "PM" to "泡沫",
        "GHZ" to "光辉岁月",
        "GHSY" to "光辉岁月",
        "HKTK" to "海阔天空",
        "YY" to "演员",
        "PFZL" to "平凡之路",
        "CD" to "成都",
        "QFL" to "起风了",
        "RMW" to "让我欢喜让我忧",
        "SN" to "少年",
        "KHS" to "可可托海的牧羊人"
    )

    val defaultHotKeywords = listOf(
        "周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "薛之谦",
        "王菲", "张学友", "Beyond", "五月天", "李荣浩",
        "晴天", "稻香", "青花瓷", "光辉岁月", "起风了", "孤勇者"
    )

    fun getPinyinInitials(text: String): String {
        if (text.isBlank()) return ""
        initialsCache[text]?.let { return it }
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' -> sb.append(ch.lowercaseChar())
                ch.code in 0x4E00..0x9FA5 -> {
                    val initial = getChineseCharInitial(ch)
                    if (initial != null) sb.append(initial)
                }
            }
        }
        val result = sb.toString()
        if (initialsCache.size < 10000) {
            initialsCache[text] = result
        }
        return result
    }

    private fun getChineseCharInitial(ch: Char): Char? {
        // 常见多音字/歌手姓氏优先修正
        when (ch) {
            '曾' -> return 'z'
            '单' -> return 's'
            '朴' -> return 'p'
            '区' -> return 'o'
            '仇' -> return 'q'
            '解' -> return 'x'
            '查' -> return 'z'
            '乐' -> return 'y'
            '行' -> return 'x'
            '重' -> return 'c'
            '长' -> return 'c'
            '调' -> return 'd'
            '传' -> return 'c'
            '藏' -> return 'z'
            '邓' -> return 'd'
            '薛' -> return 'x'
            '喆' -> return 'z'
        }
        val charset = gbkCharset ?: return null
        val bytes = ch.toString().toByteArray(charset)
        if (bytes.size == 2) {
            val secPosValue = ((bytes[0].toInt() and 0xFF) - 160) * 100 + ((bytes[1].toInt() and 0xFF) - 160)
            if (secPosValue in 1601..5589) {
                for (i in 0 until 23) {
                    if (secPosValue >= secPosValueList[i] && secPosValue < secPosValueList[i + 1]) {
                        return firstLetterArray[i]
                    }
                }
            }
        }
        // Android 10+ ICU 兜底转换二级字库汉字
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val transliterator = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
                val latin = transliterator.transliterate(ch.toString()).trim()
                val first = latin.firstOrNull { it.isLetter() }
                if (first != null) return first.lowercaseChar()
            }
        }
        return null
    }

    fun matchesSong(song: UnifiedSong, rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return false
        if (song.title.lowercase().contains(q) ||
            song.artist.lowercase().contains(q) ||
            song.album.lowercase().contains(q)
        ) {
            return true
        }
        // 首字母匹配（例如输入 ZJL 匹配 周杰伦，输入 QT 匹配 晴天）
        val titleInitials = getPinyinInitials(song.title)
        val artistInitials = getPinyinInitials(song.artist)
        val albumInitials = getPinyinInitials(song.album)
        return titleInitials.contains(q) || artistInitials.contains(q) || albumInitials.contains(q)
    }

    fun buildSmartSuggestions(rawQuery: String, allSongs: List<UnifiedSong>): List<String> {
        val q = rawQuery.trim().uppercase()
        if (q.isEmpty()) return defaultHotKeywords
        val qLower = q.lowercase()
        val suggestions = LinkedHashSet<String>()

        // 1. KTV 热门预设首字母精确/前缀匹配
        ktvPopularPresets.forEach { (initials, word) ->
            if (initials.uppercase().startsWith(q) || word.uppercase().contains(q)) {
                suggestions.add(word)
            }
        }

        // 2. 从本地/NAS 曲库提取匹配该首字母的歌手与歌名
        for (song in allSongs) {
            if (suggestions.size >= 14) break
            val artist = song.artist.trim()
            if (artist.isNotBlank() && artist != "未知歌手") {
                val artistInit = getPinyinInitials(artist)
                if (artistInit.startsWith(qLower) || artist.lowercase().contains(qLower)) {
                    suggestions.add(artist)
                }
            }
            val title = song.title.trim()
            if (title.isNotBlank()) {
                val titleInit = getPinyinInitials(title)
                if (titleInit.startsWith(qLower) || title.lowercase().contains(qLower)) {
                    suggestions.add(title)
                }
            }
        }
        return suggestions.toList()
    }
}

/**
 * TV 端专属 KTV 点歌台式全网搜索界面
 * - 左侧：应用内首字母/数字快速搜索软键盘（A-Z、0-9、退格、清空、立即搜索、系统输入法）+ 首字母智能联想词条
 * - 右侧：在线音源切换胶囊 + 媒体库/全网搜索结果双路展示
 */
@Composable
fun LibrarySearchDialog(
    allSongs: List<UnifiedSong>,
    // 有意用 provider 而不是 List：下载进度流每 ~300ms 换一个新 List 实例，本工程强跳过未生效，
    // 传值会让本对话框每次进度推进都整体重组（并连带整库匹配）。
    // 需要进度的地方调用它，需要稳定 key 的地方用 DownloadEngine.structuralMatchKey。
    activeDownloadTasks: () -> List<com.lm.player.core.model.DownloadTask> = { emptyList() },
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = false,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    initialOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    onOnlineSourceChanged: ((OnlineMusicSource) -> Unit)? = null,
    onOnlineSearch: (suspend (keyword: String, source: OnlineMusicSource) -> List<UnifiedSong>)? = null,
    onParseExternalPlaylist: (suspend (url: String, source: OnlineMusicSource) -> List<UnifiedSong>)? = null,
    isServerConnected: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onDismiss: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    var query by remember { mutableStateOf("") }
    var selectedSource by remember(initialOnlineSource) { mutableStateOf(initialOnlineSource) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var onlineResults by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }
    var selectedResultTab by remember { mutableIntStateOf(0) } // 0: 全部结果, 1: 全网在线, 2: 本地/NAS曲库
    var showSystemKeyboardDialog by remember { mutableStateOf(false) }
    var manualSearchTrigger by remember { mutableIntStateOf(0) }

    val firstKeyFocusRequester = remember { FocusRequester() }

    // 首字母智能联想词（当用户输入如 ZJL 时自动映射“周杰伦”用于联想芯片及在线搜索优化）
    val smartSuggestions = remember(query, allSongs) {
        TvPinyinSearchHelper.buildSmartSuggestions(query, allSongs)
    }

    // 在线全网搜索协程（运行在 Dispatchers.IO，当输入纯首字母缩写且命中 KTV 预设或曲库歌手时自动使用展开词或原词检索）
    LaunchedEffect(query, selectedSource, manualSearchTrigger, onOnlineSearch) {
        if (query.isBlank()) {
            onlineResults = emptyList()
            isSearchingOnline = false
            return@LaunchedEffect
        }
        val trimmed = query.trim()
        if (onParseExternalPlaylist != null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            isSearchingOnline = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    onParseExternalPlaylist(trimmed, selectedSource)
                }
                onlineResults = parsed
                isSearchingOnline = false
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                onlineResults = emptyList()
                isSearchingOnline = false
            }
            return@LaunchedEffect
        }
        if (onOnlineSearch == null) {
            onlineResults = emptyList()
            isSearchingOnline = false
            return@LaunchedEffect
        }
        if (manualSearchTrigger == 0) {
            delay(350)
        }
        // 如果输入的是全英文缩写（如 ZJL、QT），优先看是否有精确匹配的预设中文词或曲库智能联想词，提升全网音源搜索命中率
        val exactPreset = TvPinyinSearchHelper.ktvPopularPresets.firstOrNull {
            it.first.equals(trimmed, ignoreCase = true)
        }?.second
        val effectiveOnlineKeyword = exactPreset
            ?: if (trimmed.all { it.isLetter() && it.code < 128 } && smartSuggestions.isNotEmpty()) {
                smartSuggestions.first()
            } else {
                trimmed
            }

        isSearchingOnline = true
        try {
            val fetched = withContext(Dispatchers.IO) {
                onOnlineSearch(effectiveOnlineKeyword, selectedSource)
            }
            onlineResults = fetched
            isSearchingOnline = false
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            onlineResults = emptyList()
            isSearchingOnline = false
        }
    }

    // 将在线全网搜索结果与本地曲库及已下载歌曲进行智能比对（纯内存快速比对）
    // 记忆键用结构键(歌曲集合+状态)：Compose 的 remember 按 equals 比较 key，
    // 因此下载进度每 300ms 推进时键值不变 → 这里的整库匹配不会被反复触发
    val activeTaskMatchKey = com.lm.player.core.media.DownloadEngine.structuralMatchKey(activeDownloadTasks())
    val resolvedOnlineResults = remember(onlineResults, allSongs, activeTaskMatchKey) {
        val tasks = activeDownloadTasks()
        if (onlineResults.isEmpty()) onlineResults
        else com.lm.player.core.media.SongMatchingResolver.resolveSongList(
            incomingSongs = onlineResults,
            allCachedSongs = allSongs,
            activeTasks = tasks
        )
    }

    // 本地/NAS 曲库搜索结果（支持歌名/歌手/专辑关键字 + 拼音首字母快速检索，限制最多展示150首防止大列表卡顿）
    val searchResults = remember(query, allSongs) {
        if (query.isBlank()) {
            emptyList()
        } else {
            allSongs.asSequence()
                .filter { TvPinyinSearchHelper.matchesSong(it, query) }
                .take(150)
                .toList()
        }
    }

    val keyboardKeys = remember {
        ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() }
    }

    val panelBg = if (isDark) Color(0xFF212532) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 4.dp,
                    end = 8.dp,
                    top = 2.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ================= 左侧：KTV 点歌台首字母软键盘面板 =================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = panelBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .width(dimensions.scale(292.dp))
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. 顶部标题栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFFFFD60A),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "点歌台 · 首字母快搜",
                                    fontSize = dimensions.itemTitleSize,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "输入拼音首字母 (如 ZJL · QT) 检索",
                                    fontSize = dimensions.badgeSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (query.isNotEmpty()) {
                            TextButton(
                                onClick = { query = "" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(8.dp))
                            ) {
                                Text("重置", color = Color(0xFFFFD60A), fontSize = dimensions.badgeSize, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // 2. 搜索关键词显示框 (点击也可唤起系统全拼/歌单链接输入框)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) Color(0xFF171A24) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.5.dp, if (query.isNotEmpty()) Color(0xFFFFD60A) else borderColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .tvFocusable(
                                shape = RoundedCornerShape(14.dp),
                                focusedScale = 1.02f,
                                onClick = { showSystemKeyboardDialog = true }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = if (query.isNotEmpty()) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (query.isEmpty()) "请用遥控器点选下方字母..." else query,
                                    fontSize = if (query.isEmpty()) dimensions.bodySize else dimensions.cardHeaderSize,
                                    fontWeight = if (query.isEmpty()) FontWeight.Normal else FontWeight.ExtraBold,
                                    color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (query.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = AppleRed.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "${query.length} 字",
                                        fontSize = dimensions.badgeSize,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleRed,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3. 快捷功能按键行：[退格] [清空] [全拼/链接] [立即搜索]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 退格
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .tvFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    focusedScale = 1.05f,
                                    onClick = {
                                        if (query.isNotEmpty()) {
                                            query = query.dropLast(1)
                                        }
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("退格", fontSize = dimensions.badgeSize, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // 清空
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .tvFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    focusedScale = 1.05f,
                                    onClick = { query = "" }
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("清空", fontSize = dimensions.badgeSize, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // 全拼/链接输入
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .weight(1.1f)
                                .height(36.dp)
                                .tvFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    focusedScale = 1.05f,
                                    onClick = { showSystemKeyboardDialog = true }
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("全拼/链接", fontSize = dimensions.badgeSize, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // 立即搜索 (AppleRed 高亮按钮 + 金色获焦外框)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AppleRed,
                            modifier = Modifier
                                .weight(1.1f)
                                .height(36.dp)
                                .tvFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    focusedScale = 1.05f,
                                    focusedBorderColor = Color(0xFFFFD60A),
                                    onClick = { manualSearchTrigger++ }
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("全网搜", fontSize = dimensions.badgeSize, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    // 4. 6x6 字母与数字点歌台矩阵键盘 (A-Z, 0-9)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (rowIndex in 0 until 6) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (colIndex in 0 until 6) {
                                    val keyIndex = rowIndex * 6 + colIndex
                                    val keyChar = keyboardKeys[keyIndex]
                                    val isDigit = keyChar[0].isDigit()
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isDigit) {
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        border = BorderStroke(1.dp, borderColor),
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .then(if (keyIndex == 0) Modifier.focusRequester(firstKeyFocusRequester) else Modifier)
                                            .tvFocusable(
                                                shape = RoundedCornerShape(10.dp),
                                                focusedScale = 1.08f,
                                                focusedBorderColor = Color(0xFFFFD60A),
                                                onClick = {
                                                    query += keyChar
                                                }
                                            )
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = keyChar,
                                                fontSize = dimensions.cardHeaderSize,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isDigit) AppleRed else MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. 底部：首字母智能联想词条 / 热门搜索词
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (query.isBlank()) "🔥 热门点歌：" else "💡 首字母智能联想 (按OK直达)：",
                            fontSize = dimensions.badgeSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            itemsIndexed(
                                items = smartSuggestions,
                                key = { idx, word -> "sug_${idx}_$word" }
                            ) { _, word ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = AppleRed.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.35f)),
                                    modifier = Modifier.tvFocusable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedScale = 1.06f,
                                        focusedBorderColor = Color(0xFFFFD60A),
                                        onClick = {
                                            query = word
                                            manualSearchTrigger++
                                        }
                                    )
                                ) {
                                    Text(
                                        text = word,
                                        fontSize = dimensions.badgeSize,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppleRed,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ================= 右侧：全网音源切换与搜索结果流 =================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // 1. 顶部：全网音源切换条
                if (onOnlineSearch != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = panelBg,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "全网音源:",
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // 音源从横排标签改为下拉框，避免音源增多后横向溢出
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isDark) Color(0xFF171A24) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, borderColor),
                                        modifier = Modifier.tvFocusable(
                                            shape = RoundedCornerShape(10.dp),
                                            focusedScale = 1.06f,
                                            focusedBorderColor = Color(0xFFFFD60A),
                                            onClick = { sourceMenuExpanded = true }
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = selectedSource.displayName,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = dimensions.captionSize,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Icon(
                                                Icons.Default.ArrowDropDown,
                                                contentDescription = "切换音源",
                                                tint = Color(0xFFFFD60A),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    OnlineSourceDropdownMenu(
                                        expanded = sourceMenuExpanded,
                                        onDismissRequest = { sourceMenuExpanded = false },
                                        currentSource = selectedSource,
                                        onSourceSelect = { src ->
                                            sourceMenuExpanded = false
                                            val changed = selectedSource != src
                                            selectedSource = src
                                            if (changed) {
                                                onlineResults = emptyList()
                                            }
                                            if (selectedResultTab == 2) selectedResultTab = 0
                                            manualSearchTrigger++
                                            onOnlineSourceChanged?.invoke(src)
                                        }
                                    )
                                }
                            }

                            if (isSearchingOnline) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFFFFD60A)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "检索中...",
                                        fontSize = dimensions.badgeSize,
                                        color = Color(0xFFFFD60A),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 2. 结果分类切换标签栏 (全部 / 全网在线 / 本地与NAS曲库)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = listOf(
                        "全部结果 (${searchResults.size + resolvedOnlineResults.size})",
                        "全网在线 (${resolvedOnlineResults.size})",
                        "本地曲库 (${searchResults.size})"
                    )
                    tabs.forEachIndexed { idx, title ->
                        val isTabSelected = selectedResultTab == idx
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isTabSelected) AppleRed.copy(alpha = 0.16f) else panelBg,
                            border = BorderStroke(
                                1.dp,
                                if (isTabSelected) AppleRed else borderColor
                            ),
                            modifier = Modifier.tvFocusable(
                                shape = RoundedCornerShape(10.dp),
                                focusedScale = 1.04f,
                                onClick = { selectedResultTab = idx }
                            )
                        ) {
                            Text(
                                text = title,
                                fontSize = dimensions.captionSize,
                                fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isTabSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // 3. 搜索结果内容区
                if (query.isBlank()) {
                    // 空状态：展示点歌台热门歌手/热歌快捷卡片网格
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = panelBg,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "TV车机版点歌台操作指南 & 热门快搜",
                                    fontSize = dimensions.cardHeaderSize,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                // 车机模式只陈述触控路径；电视模式文案保持原样
                                text = if (LocalPlatformMode.current == PlatformMode.CAR) {
                                    "• 直接点按左侧字母键盘输入歌名/歌手首字母（如输入「ZJL」搜周杰伦，「QT」搜晴天）\n" +
                                        "• 本地与柠檬曲库 (${allSongs.size} 首) 支持实时首字母过滤，全网音源支持自动联想搜索\n" +
                                        "• 点按搜索结果立即播放"
                                } else {
                                    "• 支持车机触控直点或使用遥控器方向键在左侧字母键盘点选歌名/歌手首字母（如输入「ZJL」搜周杰伦，「QT」搜晴天）\n" +
                                        "• 本地与柠檬曲库 (${allSongs.size} 首) 支持实时首字母过滤，全网音源支持自动联想搜索\n" +
                                        "• 在搜索结果上按「确定键」立即播放，按遥控器「菜单键」呼出收藏与下载菜单"
                                },
                                fontSize = dimensions.captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 19.sp
                            )

                            HorizontalDivider(color = borderColor)

                            Text(
                                text = "热门华语歌手与金曲一键点播",
                                fontSize = dimensions.bodySize,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            val rows = TvPinyinSearchHelper.defaultHotKeywords.chunked(4)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                rows.forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        rowItems.forEach { keyword ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, borderColor),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(44.dp)
                                                    .tvFocusable(
                                                        shape = RoundedCornerShape(12.dp),
                                                        focusedScale = 1.04f,
                                                        onClick = {
                                                            query = keyword
                                                            manualSearchTrigger++
                                                        }
                                                    )
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(horizontal = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = keyword,
                                                        fontSize = dimensions.bodySize,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Default.Search,
                                                        contentDescription = null,
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
                    }
                } else if (searchResults.isEmpty() && resolvedOnlineResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (isSearchingOnline) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 2.5.dp,
                                    color = AppleRed
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "正在全网检索「$query」(${selectedSource.displayName})...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = dimensions.bodySize
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Outlined.SearchOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "未找到与「$query」匹配的歌曲",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = dimensions.itemTitleSize,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "可尝试点选左下方「首字母智能联想词」或切换顶部其它全网音源",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = dimensions.captionSize
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 全网在线搜索结果
                        if ((selectedResultTab == 0 || selectedResultTab == 1) &&
                            (resolvedOnlineResults.isNotEmpty() || isSearchingOnline)
                        ) {
                            item(key = "header_online_results") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "全网在线发现 · ${selectedSource.displayName} (${resolvedOnlineResults.size})",
                                        fontSize = dimensions.bodySize,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleRed
                                    )
                                }
                            }
                            itemsIndexed(
                                items = resolvedOnlineResults,
                                key = { idx, song -> "search_online_${idx}_${song.id}" },
                                contentType = { _, _ -> "search_online_song_item" }
                            ) { _, song ->
                                SongListItemRow(
                                    song = song,
                                    activeDownloadTasks = activeDownloadTasks,
                                    currentPlayingSong = currentPlayingSong,
                                    isPlaying = isPlaying,
                                    isServerConnected = isServerConnected,
                                    onClick = {
                                        onSongClick(song, resolvedOnlineResults)
                                    },
                                    onDownloadClick = { songForDownloadChoice = song },
                                    onDownloadWithOptions = { s, target, quality ->
                                        onDownloadSongWithOptions(s, target, quality)
                                    }
                                )
                            }
                        }

                        // 媒体库（本地与 NAS 曲库）结果
                        if ((selectedResultTab == 0 || selectedResultTab == 2) && searchResults.isNotEmpty()) {
                            item(key = "header_local_results") {
                                Text(
                                    text = "本地与柠檬媒体库匹配 (${searchResults.size})",
                                    fontSize = dimensions.bodySize,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            itemsIndexed(
                                items = searchResults,
                                key = { idx, song -> "search_local_${idx}_${song.id}" },
                                contentType = { _, _ -> "search_song_item" }
                            ) { _, song ->
                                SongListItemRow(
                                    song = song,
                                    activeDownloadTasks = activeDownloadTasks,
                                    currentPlayingSong = currentPlayingSong,
                                    isPlaying = isPlaying,
                                    isServerConnected = isServerConnected,
                                    onClick = {
                                        onSongClick(song, searchResults)
                                    },
                                    onDownloadClick = { songForDownloadChoice = song },
                                    onDownloadWithOptions = { s, target, quality ->
                                        onDownloadSongWithOptions(s, target, quality)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 系统全拼键盘 / 歌单链接输入弹窗
    if (showSystemKeyboardDialog) {
        var tempInput by remember(query) { mutableStateOf(query) }
        Dialog(onDismissRequest = { showSystemKeyboardDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "全拼输入 / 外部歌单链接解析",
                        fontSize = dimensions.sectionTitleSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = tempInput,
                        onValueChange = { tempInput = it },
                        placeholder = { Text("输入中文歌名、歌手，或粘贴外部歌单 URL...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showSystemKeyboardDialog = false },
                            modifier = Modifier.tvButtonFocusable(shape = RoundedCornerShape(10.dp))
                        ) {
                            Text("取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                query = tempInput.trim()
                                showSystemKeyboardDialog = false
                                manualSearchTrigger++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.tvButtonFocusable(
                                shape = RoundedCornerShape(10.dp),
                                focusedBorderColor = Color(0xFFFFD60A)
                            )
                        ) {
                            Text("确定搜索")
                        }
                    }
                }
            }
        }
    }

    if (songForDownloadChoice != null) {
        DownloadQualityChoiceDialog(
            song = songForDownloadChoice!!,
            isServerConnected = isServerConnected,
            onDismiss = { songForDownloadChoice = null },
            onConfirm = { target, quality ->
                onDownloadSongWithOptions(songForDownloadChoice!!, target, quality)
                songForDownloadChoice = null
            }
        )
    }
}

