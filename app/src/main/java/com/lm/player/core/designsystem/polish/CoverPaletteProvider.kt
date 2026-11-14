package com.lm.player.core.designsystem.polish

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 封面取色（Claude 维护的逻辑层，供 Antigravity 设计的 CoverTintBackground 使用）。
 * 只解码 64px 的小缩略图、在后台线程用 androidx.palette 取色，结果按封面地址缓存，
 * 同一首歌反复进出播放页不会重复计算。
 */

private const val PALETTE_THUMB_PX = 64
private val paletteCache = LruCache<String, CoverPalette>(64)

/** 后台计算封面取色；没有封面或加载失败返回 null（界面用主题默认色） */
suspend fun computeCoverPalette(context: Context, coverUrl: String?): CoverPalette? {
    if (coverUrl.isNullOrBlank()) return null
    paletteCache.get(coverUrl)?.let { return it }
    val app = context.applicationContext
    val request = com.lm.player.core.designsystem.component.buildCoverPaletteRequest(app, coverUrl, PALETTE_THUMB_PX)
        ?: return null
    val bitmap = runCatching {
        (app.imageLoader.execute(request) as? SuccessResult)?.drawable
            ?.toBitmap(PALETTE_THUMB_PX, PALETTE_THUMB_PX, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return null
    val palette = withContext(Dispatchers.Default) {
        runCatching {
            val p = Palette.from(bitmap).maximumColorCount(12).generate()
            val dominant = p.getDominantColor(0xFF2A2D36.toInt())
            val vibrant = p.getVibrantColor(p.getLightVibrantColor(dominant))
            val muted = p.getDarkMutedColor(p.getMutedColor(dominant))
            CoverPalette(
                dominant = Color(dominant),
                vibrant = Color(vibrant),
                muted = Color(muted),
                isDark = ColorUtils.calculateLuminance(dominant) < 0.45
            )
        }.getOrNull()
    } ?: return null
    paletteCache.put(coverUrl, palette)
    return palette
}

/** 当前封面的取色；切歌时先保留上一首的颜色，算好后由 CoverTintBackground 平滑过渡 */
@Composable
fun rememberCoverPalette(coverUrl: String?): CoverPalette? {
    val context = LocalContext.current
    val state = produceState(
        initialValue = coverUrl?.takeIf { it.isNotBlank() }?.let { paletteCache.get(it) },
        coverUrl
    ) {
        if (coverUrl.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = computeCoverPalette(context, coverUrl)
    }
    return state.value
}

/** 跟随封面取色的背景层（铺满父容器，放在内容之下） */
@Composable
fun CoverTintLayer(coverUrl: String?, modifier: Modifier = Modifier.fillMaxSize()) {
    CoverTintBackground(palette = rememberCoverPalette(coverUrl), modifier = modifier) {}
}

/**
 * 首页左侧播放卡片的底色（2026-11-13）：用当前歌曲封面里的颜色，从卡片顶部中间向下辐射填满整张卡片。
 * 顶部最浓、往下渐渐融进卡片底色；切歌时颜色 0.5 秒平滑过渡。只在绘制阶段读取颜色，不引起卡片重组。
 */
@Composable
fun CoverRadialTintLayer(coverUrl: String?, modifier: Modifier = Modifier.fillMaxSize()) {
    val palette = rememberCoverPalette(coverUrl)
    val isDark = androidx.compose.material3.MaterialTheme.colorScheme.background.red < 0.5f
    val base = if (isDark) androidx.compose.ui.graphics.Color(0xFF0E1117) else androidx.compose.ui.graphics.Color(0xFFEEF1F6)
    // 主色偏灰时用鲜艳色，保证「有颜色」；没取到颜色时用中性色
    val accent = palette?.let { p ->
        val d = p.dominant
        val gray = kotlin.math.abs(d.red - d.green) + kotlin.math.abs(d.green - d.blue) < 0.12f
        if (gray) p.vibrant else d
    } ?: if (isDark) androidx.compose.ui.graphics.Color(0xFF3A3F52) else androidx.compose.ui.graphics.Color(0xFFD5DAE6)
    val color by androidx.compose.animation.animateColorAsState(accent, androidx.compose.animation.core.tween(500), label = "card_radial_tint")
    val strength = if (isDark) 0.92f else 0.62f
    androidx.compose.foundation.layout.Spacer(
        modifier = modifier.drawBehind {
            drawRect(base)
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to color.copy(alpha = strength),
                    0.45f to color.copy(alpha = strength * 0.55f),
                    1f to color.copy(alpha = 0f),
                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                    radius = size.height * 1.05f
                )
            )
        }
    )
}
