package com.lm.player.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs

import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

// Apple Music 经典质感渐变调色板
val AppleGradients = listOf(
    listOf(Color(0xFFFA233B), Color(0xFFFF5E3A)), // Apple Red -> Sunset Orange
    listOf(Color(0xFF5856D6), Color(0xFFAF52DE)), // Indigo -> Purple
    listOf(Color(0xFFFF2D55), Color(0xFFFF375F)), // Pink Magenta
    listOf(Color(0xFF007AFF), Color(0xFF5AC8FA)), // Apple Blue -> Cyan
    listOf(Color(0xFFFF9500), Color(0xFFFFCC00)), // Amber Orange
    listOf(Color(0xFF34C759), Color(0xFF30D158)), // Emerald Mint
    listOf(Color(0xFF1D1D1F), Color(0xFF3A3A3C))  // Obsidian Dark
)

fun getGradientForId(id: String): List<Color> {
    val index = abs(id.hashCode()) % AppleGradients.size
    return AppleGradients[index]
}

// 界面优化：正则只编译一次（原先每张封面每次组合都重新编译 3 个正则，滚动列表时白白耗 CPU）
private val KWCDN_HOST_REGEX = Regex("""(?i)(?:[a-zA-Z0-9_\-]+\.)*kwcdn\.kuwo\.cn""")
private val KUWO_SIZED_PATH_REGEX = Regex("^(120|150|240|300|500)/.*")
private val KUWO_SMALL_SIZE_PREFIX_REGEX = Regex("^(120|150|240|300)/")

/** 大于等于这个解码尺寸（像素）的封面视为「大封面」：32 位色、可淡入、酷我取 500 像素原图 */
private const val LARGE_COVER_PX = 300

/** 不指定解码尺寸：按控件实际大小解码（小行图更省、大卡片不再糊） */
const val COVER_SIZE_BY_LAYOUT = 0

private fun normalizeMusicCoverUrl(raw: String?, preferLarge: Boolean = true): String? {
    if (raw.isNullOrBlank()) return null
    var url = raw.trim()
    if (url.isEmpty() || url.equals("null", ignoreCase = true) || url.equals("undefined", ignoreCase = true)) return null
    if (com.lm.player.core.network.LemonMusicProtocol.isKnownMissingEmbeddedCoverUrl(url)) return null
    // 歌曲表里可能存着大写 HTTPS:// 的旧封面地址，图片库按小写协议匹配加载器（PR #4）
    url = com.lm.player.core.network.ServerUrlPolicy.normalizeSchemeCase(url)
    if (url.startsWith("//")) {
        url = "https:$url"
    }

    // 酷我 kwcdn 替换为稳定的主 CDN (避免 HTTPS 下 514 限制)
    if (url.contains("kwcdn.kuwo.cn", ignoreCase = true)) {
        url = url.replace(KWCDN_HOST_REGEX, "img1.kuwo.cn")
        url = url.replace("img1.img1.kuwo.cn", "img1.kuwo.cn")
    }

    // 酷我相对路径自动补全
    if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("data:") && !url.startsWith("file:") && !url.startsWith("content:")) {
        if (url.matches(KUWO_SIZED_PATH_REGEX) || url.contains("s3s") || url.contains("s4s") || url.contains("star/albumcover")) {
            // 界面优化：小图（列表行）取 300 像素就够，大封面才取 500 像素
            val upgraded = url.replaceFirst(KUWO_SMALL_SIZE_PREFIX_REGEX, if (preferLarge) "500/" else "300/")
            url = "https://img1.kuwo.cn/star/albumcover/$upgraded"
        }
    }

    // 酷狗与各大音源大尺寸占位符替换
    if (url.contains("{size}")) {
        url = url.replace("{size}", "300")
    }

    // 网易云 music.126.net 自动追加轻量级缩略图参数
    if (url.contains("music.126.net") && !url.contains("param=")) {
        url += if (url.contains("?")) "&param=300y300" else "?param=300y300"
    }

    // 酷我小图自动升阶
    if (url.contains("kuwo.cn/star/albumcover/120/")) {
        url = url.replace("kuwo.cn/star/albumcover/120/", if (preferLarge) "kuwo.cn/star/albumcover/500/" else "kuwo.cn/star/albumcover/300/")
    }

    if (url.startsWith("http://img1.kuwo.cn/")) {
        url = "https://" + url.substring("http://".length)
    } else if (url.startsWith("http://") && (url.contains("music.126.net") || url.contains("migu.cn") || url.contains("gtimg.cn") || url.contains("qq.com"))) {
        url = "https://" + url.substring("http://".length)
    }
    return url
}

/**
 * @param width / height 解码尺寸（像素）；[COVER_SIZE_BY_LAYOUT] 表示按控件实际大小解码（交给 Coil 按约束决定）
 * @param large 大封面：强制 32 位色（低性能设备全局是 16 位色），酷我取 500 像素
 * @param fadeIn 加载完成时 120ms 淡入（只给大封面用，列表小图保持直接显示）
 */
private fun buildMusicImageRequest(
    context: android.content.Context,
    rawUrl: String?,
    width: Int,
    height: Int = width,
    large: Boolean = width >= LARGE_COVER_PX,
    fadeIn: Boolean = false
): ImageRequest? {
    val cleanUrl = normalizeMusicCoverUrl(rawUrl, preferLarge = large) ?: return null
    val builder = ImageRequest.Builder(context)
        .data(cleanUrl)
        .apply { if (width > 0 && height > 0) size(width, height) }
        .apply {
            if (fadeIn) crossfade(120) else crossfade(false)
            if (large) {
                bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                allowRgb565(false)
            }
        }
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
        .networkCachePolicy(coil.request.CachePolicy.ENABLED)

    if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
        val headers = com.lm.player.core.network.MusicCdnHeaders.buildHeadersForUrl(cleanUrl)
        for ((k, v) in headers) {
            builder.addHeader(k, v)
        }
    }
    return builder.build()
}

/**
 * 界面美化：封面取色用的小缩略图请求（与封面组件同一套网址规范化与 CDN 请求头；
 * 软件位图、32 位色，便于读取像素）。
 */
internal fun buildCoverPaletteRequest(context: android.content.Context, rawUrl: String?, sizePx: Int): ImageRequest? =
    buildMusicImageRequest(context, rawUrl, sizePx, sizePx, large = false, fadeIn = false)
        ?.newBuilder()
        ?.allowHardware(false)
        ?.bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
        ?.allowRgb565(false)
        ?.build()

/**
 * 高性能专辑/歌曲封面组件 (限制解码尺寸杜绝车机内存溢出与 GC 卡顿)
 */
@Composable
fun AlbumArtworkImage(
    model: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    shadowElevation: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
    seedId: String = "",
    // 界面优化：默认按控件实际大小解码（原先固定 160px：列表小图解码偏大，铺满卡片的大图在大屏上发糊）
    targetSize: Int = COVER_SIZE_BY_LAYOUT,
    /** 大封面（铺满卡片 / 黑胶卡 / 播放页）：32 位色、可淡入、取高分辨率原图 */
    highQuality: Boolean = false,
    /** 加载成功后回报原图像素尺寸（播放页「全屏封面」据此判断封面是否够清晰） */
    onImageSize: ((Int, Int) -> Unit)? = null
) {
    val large = highQuality || targetSize >= LARGE_COVER_PX
    val fadeIn = large && com.lm.player.core.designsystem.theme.AppUiPrefs.coverFadeIn
    val cleanModel = remember(model, large) { normalizeMusicCoverUrl(model, preferLarge = large) }
    var loadFailed by remember(cleanModel) { mutableStateOf(false) }
    val gradientColors = remember(seedId, cleanModel) {
        getGradientForId(if (seedId.isNotBlank()) seedId else (cleanModel ?: "seed"))
    }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .then(if (shadowElevation > 0.dp) Modifier.shadow(shadowElevation, RoundedCornerShape(cornerRadius)) else Modifier)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        if (!cleanModel.isNullOrBlank()) {
            // 2026-11-13：网络封面加载失败（启动时还没登录完、服务器偶尔超时）以前就一直空着，
            // 现在 3 秒后自动重试，最多 2 次；本机文件封面失败不重试
            var retry by remember(cleanModel) { mutableIntStateOf(0) }
            val isNetwork = cleanModel.startsWith("http://", ignoreCase = true) || cleanModel.startsWith("https://", ignoreCase = true)
            val retryScope = rememberCoroutineScope()
            AsyncImage(
                model = remember(cleanModel, targetSize, large, fadeIn, retry) {
                    val req = buildMusicImageRequest(context, cleanModel, targetSize, targetSize, large, fadeIn)
                    if (retry == 0 || req == null) req else req.newBuilder().setParameter("lm_retry", retry, memoryCacheKey = null).build()
                },
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = if (onImageSize == null) null else { state ->
                    val d = state.result.drawable
                    onImageSize(d.intrinsicWidth, d.intrinsicHeight)
                },
                onError = { _ ->
                    if (isNetwork && retry < 2) {
                        retryScope.launch {
                            kotlinx.coroutines.delay(3000L)
                            retry++
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            DefaultMusicPlaceholder(colors = gradientColors)
        }
    }
}

/**
 * 高性能歌单封面组件
 */
@Composable
fun PlaylistArtworkImage(
    title: String = "",
    model: String? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    seedId: String = "",
    targetSize: Int = COVER_SIZE_BY_LAYOUT,
    highQuality: Boolean = false
) {
    val large = highQuality || targetSize >= LARGE_COVER_PX
    val fadeIn = large && com.lm.player.core.designsystem.theme.AppUiPrefs.coverFadeIn
    val cleanModel = remember(model, large) { normalizeMusicCoverUrl(model, preferLarge = large) }
    var loadFailed by remember(cleanModel) { mutableStateOf(false) }
    val gradientColors = remember(title, seedId, cleanModel) {
        getGradientForId(if (title.isNotBlank()) title else if (seedId.isNotBlank()) seedId else (cleanModel ?: "playlist"))
    }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        if (!cleanModel.isNullOrBlank()) {
            AsyncImage(
                model = remember(cleanModel, targetSize, large, fadeIn) {
                    buildMusicImageRequest(context, cleanModel, targetSize, targetSize, large, fadeIn)
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 高性能艺术家圆形头像组件
 */
@Composable
fun ArtistAvatarImage(
    model: String?,
    modifier: Modifier = Modifier,
    seedId: String = "",
    targetSize: Int = COVER_SIZE_BY_LAYOUT
) {
    val cleanModel = remember(model) { normalizeMusicCoverUrl(model, preferLarge = targetSize >= LARGE_COVER_PX) }
    var loadFailed by remember(cleanModel) { mutableStateOf(false) }
    val gradientColors = remember(seedId, cleanModel) {
        getGradientForId(if (seedId.isNotBlank()) seedId else (cleanModel ?: "artist"))
    }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        if (!cleanModel.isNullOrBlank()) {
            AsyncImage(
                model = remember(cleanModel, targetSize) {
                    buildMusicImageRequest(context, cleanModel, targetSize, targetSize)
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * 高性能 2x2 四宫格封面缩略图组件 (针对车机进行精简与小图优化)
 */
@Composable
fun MosaicArtworkCollage(
    coverUrls: List<String>,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    seedId: String = ""
) {
    var failedUrls by remember(coverUrls) { mutableStateOf(emptySet<String>()) }
    val validCovers = remember(coverUrls, failedUrls) {
        coverUrls.mapNotNull { normalizeMusicCoverUrl(it, preferLarge = false) }
            .filter { it !in failedUrls }
            .distinct()
            .take(4)
    }
    val gradientColors = remember(seedId) { getGradientForId(seedId) }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        when (validCovers.size) {
            0 -> {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(28.dp)
                )
            }
            1 -> {
                val c0 = validCovers[0]
                AsyncImage(
                    model = remember(c0) {
                        buildMusicImageRequest(context, c0, 200, 200)
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failedUrls = failedUrls + c0 },
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                val c0 = validCovers[0]
                val c1 = validCovers.getOrElse(1) { c0 }
                val c2 = validCovers.getOrElse(2) { c0 }
                val c3 = validCovers.getOrElse(3) { c1 }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            AsyncImage(
                                model = remember(c0) {
                                    buildMusicImageRequest(context, c0, 120, 120)
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onError = { failedUrls = failedUrls + c0 },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            AsyncImage(
                                model = remember(c1) {
                                    buildMusicImageRequest(context, c1, 120, 120)
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onError = { failedUrls = failedUrls + c1 },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            AsyncImage(
                                model = remember(c2) {
                                    buildMusicImageRequest(context, c2, 120, 120)
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onError = { failedUrls = failedUrls + c2 },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            AsyncImage(
                                model = remember(c3) {
                                    buildMusicImageRequest(context, c3, 120, 120)
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onError = { failedUrls = failedUrls + c3 },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DefaultMusicPlaceholder(
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(24.dp)
        )
    }
}
