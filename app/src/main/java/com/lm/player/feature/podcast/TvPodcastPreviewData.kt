package com.lm.player.feature.podcast

import com.lm.player.core.podcast.*

/**
 * TV / 车机端播客大屏 UI 预览数据与假数据源
 * 提供标准 TV 1080p、车机超宽 (21:9) 等多分辨率与深浅色模式的完整预览数据
 */
object TvPodcastPreviewData {

    val sampleShow1 = PodcastShowUi(
        id = "show_1",
        title = "津津乐道",
        author = "丁丁 / 津津乐道播客网络",
        description = "《津津乐道》是一档专注于科技、生活与商业观察的随聊播客。每期探讨最新技术演进与行业人物故事，带你领略数字生活的温情与理性。",
        imageUrl = "https://images.unsplash.com/photo-1590602847861-f357a9332bbc?w=500&auto=format&fit=crop&q=80",
        episodeCount = 142,
        newEpisodeCount = 3,
        updatedText = "3小时前更新",
        errorText = "",
        isRefreshing = false,
        feedUrl = "https://feed.dao.fm/tech",
        isDeleting = false
    )

    val sampleShow2 = PodcastShowUi(
        id = "show_2",
        title = "忽左忽右",
        author = "程衍樑 / JustPod",
        description = "沙龙式访谈播客，每期邀请不同领域的专家学者与行业观察者，探讨历史、政治、商业与流行文化。",
        imageUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=500&auto=format&fit=crop&q=80",
        episodeCount = 295,
        newEpisodeCount = 1,
        updatedText = "昨天更新",
        errorText = "",
        isRefreshing = false,
        feedUrl = "https://justpodmedia.com/rss/leftright.xml",
        isDeleting = false
    )

    val sampleShow3 = PodcastShowUi(
        id = "show_3",
        title = "声东击西",
        author = "徐涛 / 声动活泼",
        description = "带你看不一样的世界。两位驻美记者用声音记录这个时代的变迁与深度思考。",
        imageUrl = "https://images.unsplash.com/photo-1478737270239-2f02b77fc618?w=500&auto=format&fit=crop&q=80",
        episodeCount = 186,
        newEpisodeCount = 0,
        updatedText = "3天前更新",
        errorText = "",
        isRefreshing = false,
        feedUrl = "https://shengdonghuopo.fm/rss",
        isDeleting = false
    )

    val sampleShow4 = PodcastShowUi(
        id = "show_4",
        title = "硬地骇客",
        author = "独立开发者社区",
        description = "聚焦独立开发者、出海创业与极客产品的真实复盘与心路历程。",
        imageUrl = "https://images.unsplash.com/photo-1528732263440-4dd1a18a4cc2?w=500&auto=format&fit=crop&q=80",
        episodeCount = 88,
        newEpisodeCount = 0,
        updatedText = "1周前更新",
        errorText = "服务器抓取超时，将在下次自动重试",
        isRefreshing = false,
        feedUrl = "https://hardhacker.com/podcast.xml",
        isDeleting = false
    )

    val sampleEpisode1 = PodcastEpisodeUi(
        id = "ep_1",
        showId = "show_1",
        showTitle = "津津乐道",
        title = "Vol.128 具身智能与大模型落地进展：技术如何重塑物理世界",
        description = """【本期概览】
在本期《科技乱炖》中，我们邀请了前沿人形机器人领域的系统工程师，深入探讨大语言模型向物理世界延伸过程中的种种技术突破与工程困境。

【时间线 Timeline】
02:15 为什么具身智能突然在 2024-2026 成为投资与科研热点？
15:40 视觉-语言-动作 (VLA) 端到端模型的真实表现与局限性
24:15 灵巧手触觉反馈、小脑运动控制与毫秒级延迟约束
39:00 工业落地与家庭场景商用时间表的理性预估

【主播与嘉宾】
主播：丁丁（津津乐道网络创始人）
嘉宾：周博士（某前沿具身智能实验室系统架构师）""",
        imageUrl = "https://images.unsplash.com/photo-1590602847861-f357a9332bbc?w=500&auto=format&fit=crop&q=80",
        publishedText = "今天",
        durationText = "53 分钟",
        remainingText = "剩 29 分钟",
        progress = 0.45f,
        playState = EpisodePlayState.IN_PROGRESS,
        isNew = true,
        isCurrent = true,
        isPlaying = true
    )

    val sampleEpisode2 = PodcastEpisodeUi(
        id = "ep_2",
        showId = "show_2",
        showTitle = "忽左忽右",
        title = "No.295 从地中海到大西洋：航海时代的文明交汇与海盗传奇",
        description = "聊聊大航海时代初期，西班牙与葡萄牙的远洋舰队如何在贸易风与洋流中开辟新航线，以及伴随其后的全球贸易体系变迁。",
        imageUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=500&auto=format&fit=crop&q=80",
        publishedText = "昨天",
        durationText = "1 小时 12 分",
        remainingText = "",
        progress = 1.0f,
        playState = EpisodePlayState.PLAYED,
        isNew = false,
        isCurrent = false,
        isPlaying = false
    )

    val sampleEpisode3 = PodcastEpisodeUi(
        id = "ep_3",
        showId = "show_3",
        showTitle = "声东击西",
        title = "#280 在不确定的世界里寻找锚点：都市年轻人的心态转向",
        description = "当我们面对快节奏的生活变化时，如何建立属于自己的内在稳定感？本期与心理学研究者一起展开探讨。",
        imageUrl = "https://images.unsplash.com/photo-1478737270239-2f02b77fc618?w=500&auto=format&fit=crop&q=80",
        publishedText = "3天前",
        durationText = "48 分钟",
        remainingText = "剩 12 分钟",
        progress = 0.75f,
        playState = EpisodePlayState.IN_PROGRESS,
        isNew = false,
        isCurrent = false,
        isPlaying = false
    )

    val sampleEpisode4 = PodcastEpisodeUi(
        id = "ep_4",
        showId = "show_4",
        showTitle = "硬地骇客",
        title = "Ep.88 独立开发者的第 100 天产品复盘与海外收入拆解",
        description = "从零构建一个微型 SaaS 工具，并成功获取第一批海外付费用户的实操经验与踩坑复盘。",
        imageUrl = "https://images.unsplash.com/photo-1528732263440-4dd1a18a4cc2?w=500&auto=format&fit=crop&q=80",
        publishedText = "4天前",
        durationText = "45 分钟",
        remainingText = "",
        progress = 0.0f,
        playState = EpisodePlayState.UNPLAYED,
        isNew = true,
        isCurrent = false,
        isPlaying = false
    )

    val sampleHomeUi = PodcastHomeUi(
        state = PodcastLoadState.READY,
        message = "",
        isRefreshing = false,
        refreshProgressText = "",
        continueListening = listOf(sampleEpisode1, sampleEpisode3),
        shows = listOf(sampleShow1, sampleShow2, sampleShow3, sampleShow4),
        latestEpisodes = listOf(sampleEpisode1, sampleEpisode2, sampleEpisode3, sampleEpisode4)
    )

    val sampleShowDetailUi = PodcastShowDetailUi(
        show = sampleShow1,
        state = PodcastLoadState.READY,
        message = "",
        episodes = listOf(sampleEpisode1, sampleEpisode2, sampleEpisode3, sampleEpisode4),
        totalEpisodes = 142,
        sort = EpisodeSort.NEWEST_FIRST,
        filter = EpisodeFilter.ALL,
        canLoadMore = true,
        isLoadingMore = false,
        resumeEpisode = sampleEpisode1
    )

    val sampleManageUi = PodcastManageUi(
        state = PodcastLoadState.READY,
        message = "",
        shows = listOf(sampleShow1, sampleShow2, sampleShow3, sampleShow4),
        isAdding = false,
        addError = "",
        lastAddedTitle = "",
        deleteError = ""
    )

    val sampleSearchResult1 = PodcastSearchResultUi(
        feedUrl = "https://feed.dao.fm/tech",
        title = "津津乐道",
        author = "津津乐道网络",
        imageUrl = "https://images.unsplash.com/photo-1590602847861-f357a9332bbc?w=300&auto=format&fit=crop&q=80",
        genre = "科技 / 商业",
        episodeCountText = "共 142 期",
        isSubscribed = true,
        isSubscribing = false
    )

    val sampleSearchResult2 = PodcastSearchResultUi(
        feedUrl = "https://siliconvalley.fm/feed",
        title = "硅谷高能量",
        author = "Han & Terry",
        imageUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=300&auto=format&fit=crop&q=80",
        genre = "商业 / 投资",
        episodeCountText = "共 64 期",
        isSubscribed = false,
        isSubscribing = false
    )

    val sampleSearchResult3 = PodcastSearchResultUi(
        feedUrl = "https://crazycapital.fm/feed",
        title = "疯投圈",
        author = "Rio & 黄海",
        imageUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=300&auto=format&fit=crop&q=80",
        genre = "商业消费",
        episodeCountText = "共 98 期",
        isSubscribed = false,
        isSubscribing = false
    )

    val sampleSearchUi = PodcastSearchUi(
        state = PodcastSearchState.RESULTS,
        message = "",
        results = listOf(sampleSearchResult1, sampleSearchResult2, sampleSearchResult3),
        isSubscribingUrl = false,
        subscribeError = ""
    )

    val samplePlayerUi = PodcastPlayerUi(
        episode = sampleEpisode1,
        showTitle = "津津乐道 · 科技乱炖",
        coverUrl = sampleEpisode1.imageUrl,
        isPlaying = true,
        isBuffering = false,
        durationMs = 53 * 60 * 1000L,
        speed = 1.25f,
        sleepTimerMinutes = 0,
        sleepTimerRemainingSec = 0L,
        upNext = listOf(sampleEpisode2, sampleEpisode3, sampleEpisode4),
        isCasting = false
    )
}
