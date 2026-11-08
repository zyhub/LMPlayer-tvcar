package com.lm.player.core.podcast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.lm.player.core.designsystem.component.LocalTvFocusScope
import com.lm.player.core.designsystem.component.TvFocusMemory
import com.lm.player.core.designsystem.component.tvFocusChildScope
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.model.UnifiedSong
import com.lm.player.feature.podcast.TvPodcastEpisodeDialog
import com.lm.player.feature.podcast.TvPodcastHomeScreen
import com.lm.player.feature.podcast.TvPodcastManageScreen
import com.lm.player.feature.podcast.TvPodcastPlayerScreen
import com.lm.player.feature.podcast.TvPodcastSearchScreen
import com.lm.player.feature.podcast.TvPodcastShowScreen
import kotlinx.coroutines.delay

/**
 * TV / 车机端「播客」标签页宿主：把 PodcastController 的状态转成 *Ui 对象交给
 * Antigravity 的大屏无状态页面，并负责页面栈与返回键（与手机端 PodcastHost 同一套逻辑）。
 */
@Composable
fun TvPodcastScreenHost(
    contentPadding: PaddingValues,
    onExit: () -> Unit,
    onGoToServerSettings: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val s by PodcastController.state.collectAsState()
    val progressVersion by PodcastProgressStore.version.collectAsState()
    val currentSong by PlaybackQueueManager.currentSongFlow.collectAsState()
    val isPlaying by PlaybackQueueManager.isPlayingFlow.collectAsState()
    val np = PodcastController.nowPlaying(currentSong, isPlaying)

    val exit by rememberUpdatedState(onExit)
    val goSettings by rememberUpdatedState(onGoToServerSettings)
    val openPlayer by rememberUpdatedState(onOpenPlayer)
    val actions = remember {
        PodcastController.actions(
            onExit = { exit() },
            onGoToServerSettings = { goSettings() },
            onOpenPlayer = { openPlayer() }
        )
    }

    // 每次进入播客页都后台刷新一次（已有数据照常显示）
    LaunchedEffect(Unit) { PodcastController.loadHome(userRefresh = false) }

    // ==================== 遥控器焦点（与「我的」「发现」页同一套 TvFocusMemory 机制） ====================
    // 页面切换时被点击的元素随旧页一起销毁，Compose 找不到落点就把焦点交给左上第一个可聚焦项
    // （左侧黑胶卡），表现为「点完光标跳到最左」。这里补齐三件事：
    // 1. 分层作用域：主页 = podcast，子页 = podcast::xxx。子页里的焦点变化只写子层记忆，主页记忆不被覆盖；
    // 2. 进入子页时显式指定落点；返回时恢复到上一页记住的那一项（列表滚动状态已提升，目标仍在屏幕上）；
    // 3. 删除订阅后焦点移到相邻一行。
    val parentScope = LocalTvFocusScope.current
    val homeScope = remember(parentScope) { tvFocusChildScope(parentScope, "podcast") }
    fun scopeOf(route: PodcastController.Route): String = when (route) {
        PodcastController.Route.Home -> homeScope
        is PodcastController.Route.Show -> "$homeScope::show_${route.feedId}"
        PodcastController.Route.Manage -> "$homeScope::manage"
        PodcastController.Route.Search -> "$homeScope::search"
    }
    fun entryKeyOf(route: PodcastController.Route): String = when (route) {
        PodcastController.Route.Home -> "podcast_header_card_add_rss"
        is PodcastController.Route.Show -> "podcast_show_primary_play"
        PodcastController.Route.Manage -> "podcast_manage_paste_btn"
        PodcastController.Route.Search -> "podcast_search_paste_btn"
    }

    // 各页列表滚动状态提升到宿主：返回时保留滚动位置，刚点的那一项仍在组合中，焦点才恢复得到
    val homeListState = rememberLazyListState()
    val manageListState = rememberLazyListState()
    val searchListState = rememberLazyListState()

    var prevRoutes by remember { mutableStateOf(s.routes) }
    LaunchedEffect(s.routes) {
        val prev = prevRoutes
        val cur = s.routes
        prevRoutes = cur
        if (prev == cur) return@LaunchedEffect
        val top = cur.last()
        val scope = scopeOf(top)
        val isBack = cur.size < prev.size && prev.take(cur.size) == cur
        if (isBack && TvFocusMemory.restoreFocus(scope)) return@LaunchedEffect
        // 进入新页面（或返回后记忆的那一项已不存在，如节目刚被取消订阅）：落到该页的主操作
        TvFocusMemory.rememberFocus(scope, entryKeyOf(top))
        TvFocusMemory.restoreFocus(scope)
    }

    // 离开播客标签：清掉本页的焦点记忆；下次进入从播客主页开始
    DisposableEffect(homeScope) {
        onDispose {
            TvFocusMemory.clearScope(homeScope)
            PodcastController.resetToHome()
        }
    }

    // 子页面（节目 / 管理 / 搜索）按返回键回上一级；主页按返回键交给主界面（回发现页）
    BackHandler(enabled = s.routes.size > 1) { actions.onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        AnimatedContent(
            targetState = s.routes.last(),
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "tv_podcast_route"
        ) { route ->
            CompositionLocalProvider(LocalTvFocusScope provides scopeOf(route)) {
                when (route) {
                    PodcastController.Route.Home -> {
                        val ui = remember(s, progressVersion, np) { PodcastController.homeUi(s, np) }
                        TvPodcastHomeScreen(ui = ui, actions = actions, modifier = Modifier.fillMaxSize(), listState = homeListState)
                    }
                    is PodcastController.Route.Show -> {
                        val ui = remember(s, progressVersion, np, route.feedId) { PodcastController.showDetailUi(s, route.feedId, np) }
                        TvPodcastShowScreen(ui = ui, actions = actions, modifier = Modifier.fillMaxSize())
                    }
                    PodcastController.Route.Manage -> {
                        val ui = remember(s) { PodcastController.manageUi(s) }
                        ManageFocusAfterDelete(ui.shows.map { it.id }, scopeOf(route))
                        TvPodcastManageScreen(ui = ui, actions = actions, modifier = Modifier.fillMaxSize(), listState = manageListState)
                    }
                    PodcastController.Route.Search -> {
                        val ui = remember(s) { PodcastController.searchUi(s) }
                        TvPodcastSearchScreen(ui = ui, actions = actions, modifier = Modifier.fillMaxSize(), listState = searchListState)
                    }
                }
            }
        }

        val sheetEpisode = remember(s, progressVersion, np) { PodcastController.sheetEpisodeUi(s, np) }
        if (sheetEpisode != null) {
            // 弹窗用独立作用域，避免弹窗里的焦点覆盖页面的记忆
            CompositionLocalProvider(LocalTvFocusScope provides "$homeScope::dialog") {
                TvPodcastEpisodeDialog(
                    episode = sheetEpisode,
                    actions = actions,
                    onDismiss = { actions.onCloseEpisode() }
                )
            }
        }
    }
}

/**
 * 订阅管理页删除订阅后，被删的那一行连同焦点一起消失；把焦点移到原位置的下一行（没有则上一行 / 粘贴按钮）。
 */
@Composable
private fun ManageFocusAfterDelete(ids: List<String>, scope: String) {
    var prevIds by remember { mutableStateOf(ids) }
    LaunchedEffect(ids) {
        val prev = prevIds
        prevIds = ids
        val removed = prev.filterNot { it in ids }
        if (removed.isEmpty()) return@LaunchedEffect
        val idx = prev.indexOf(removed.first()).coerceAtLeast(0)
        val neighbor = ids.getOrNull(idx) ?: ids.lastOrNull()
        TvFocusMemory.rememberFocus(scope, neighbor?.let { "podcast_manage_row_$it" } ?: "podcast_manage_paste_btn")
        TvFocusMemory.restoreFocus(scope)
    }
}

/** TV / 车机端播客全屏播放页宿主：当前播放的是播客单集时，代替音乐播放页显示 */
@Composable
fun TvPodcastPlayerHost(
    song: UnifiedSong,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onOpenShow: (feedId: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val s by PodcastController.state.collectAsState()
    val progressVersion by PodcastProgressStore.version.collectAsState()
    val queue by PlaybackQueueManager.playlistFlow.collectAsState()
    val sleepMinutes by PlaybackQueueManager.sleepTimerMinutesFlow.collectAsState()
    val sleepRemaining by PlaybackQueueManager.sleepTimerRemainingSecFlow.collectAsState()

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }

    LaunchedEffect(song.id) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val player = Media3Factory.peekSharedExoPlayer()
                if (player != null) {
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                    durationMs = player.duration.takeIf { it > 0L } ?: 0L
                    isBuffering = player.playbackState == Player.STATE_BUFFERING
                }
                delay(500L)
            }
        }
    }

    LaunchedEffect(song.id) { PodcastController.ensureDescription(song) }

    BackHandler(enabled = true) { onDismiss() }

    // 播客播放页背景为主题底色：系统栏同色
    com.lm.player.core.designsystem.theme.SystemBarsColor(
        statusBar = androidx.compose.material3.MaterialTheme.colorScheme.background
    )

    val ui = remember(song, isPlaying, isBuffering, durationMs, sleepMinutes, sleepRemaining, queue, s.cacheVersion, progressVersion) {
        PodcastController.playerUi(
            context = context,
            song = song,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            durationMs = durationMs,
            sleepTimerMinutes = sleepMinutes,
            sleepTimerRemainingSec = sleepRemaining,
            queue = queue,
            isCasting = false
        )
    }

    val actions = remember(song.id) {
        PodcastPlayerActions(
            onTogglePlayPause = { PlaybackQueueManager.togglePlay(context) },
            onSeekTo = { ms ->
                Media3Factory.peekSharedExoPlayer()?.seekTo(ms)
                positionMs = ms
            },
            onSkipBack = { PodcastController.skipBy(-PodcastPlayerDefaults.SKIP_BACK_MS) },
            onSkipForward = { PodcastController.skipBy(PodcastPlayerDefaults.SKIP_FORWARD_MS) },
            onSetSpeed = { PodcastController.setSpeed(it) },
            onSetSleepTimer = { PlaybackQueueManager.setSleepTimer(it, context) },
            onPlayFromUpNext = { ep ->
                PlaybackQueueManager.playlistFlow.value.firstOrNull {
                    PodcastSongs.isPodcast(it) && PodcastSongs.episodeId(it) == ep.id
                }?.let { target ->
                    PodcastController.prepareReplay(ep.id)
                    PlaybackQueueManager.playSong(target, context, startPositionMs = PodcastController.resumePositionFor(target))
                }
            },
            onMarkPlayed = { played ->
                val cur = PlaybackQueueManager.currentSongFlow.value
                if (cur != null && PodcastSongs.isPodcast(cur)) {
                    PodcastController.markEpisodePlayed(PodcastSongs.episodeId(cur), played)
                }
            },
            onOpenShow = { feedId -> onOpenShow(feedId) },
            onDismiss = onDismiss
        )
    }

    TvPodcastPlayerScreen(ui = ui, positionMs = positionMs, actions = actions, modifier = Modifier.fillMaxSize())
}
