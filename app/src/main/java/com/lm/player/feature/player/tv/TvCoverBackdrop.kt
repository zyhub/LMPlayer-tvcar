package com.lm.player.feature.player.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.SuccessResult
import com.lm.player.core.designsystem.component.buildCoverPaletteRequest
import com.lm.player.core.designsystem.component.getGradientForId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页「封面模糊底图」（照手机端 CoverBackdrop 移植）：
 * 封面缩到 72px 见方、预先盒式模糊一次，放大铺满全屏时由双线性过滤自然柔化。
 * 不依赖 RenderEffect，Android 10 车机同样可用；每首歌只算一次（带小缓存），之后绘制只是一张小图拉伸。
 *
 * @param scrimAlpha 上面压一层黑色的不透明度（大字歌词主题压得更暗，保证对比度）
 */
@Composable
fun TvCoverBackdrop(
    coverUrl: String?,
    seedId: String,
    scrimAlpha: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalTvPlayerColors.current
    val key = coverUrl?.takeIf { it.isNotBlank() }
    val fallback = remember(seedId) {
        val g = getGradientForId(seedId.ifBlank { "seed" })
        g.first() to g.last()
    }
    var image by remember(key) { mutableStateOf(key?.let { BackdropCache.get(it) }) }
    // 换歌时新底图淡入，不是硬切
    val fade = remember(key) { Animatable(if (image != null) 1f else 0f) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(key) {
        if (key == null) {
            image = null
            return@LaunchedEffect
        }
        if (image == null) {
            image = loadBlurred(context, key)?.also { BackdropCache.put(key, it) }
        }
        fade.animateTo(1f, tween(450))
        previous = image
    }

    Spacer(
        modifier = modifier.drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            drawRect(Brush.linearGradient(listOf(fallback.first, fallback.second), start = Offset.Zero, end = Offset(w, h)))
            val old = previous
            val cur = image
            if (old != null && old !== cur) drawCover(old, 1f)
            if (cur != null) drawCover(cur, fade.value)
            drawRect(colors.scrim.copy(alpha = (scrimAlpha * colors.scrimBoost).coerceAtMost(0.95f)))
        }
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCover(img: ImageBitmap, alpha: Float) {
    val w = size.width
    val h = size.height
    val scale = maxOf(w / img.width, h / img.height)
    val srcW = (w / scale).toInt().coerceIn(1, img.width)
    val srcH = (h / scale).toInt().coerceIn(1, img.height)
    drawImage(
        image = img,
        srcOffset = IntOffset((img.width - srcW) / 2, (img.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(w.toInt(), h.toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low
    )
}

private object BackdropCache {
    private val bitmaps = LruCache<String, ImageBitmap>(8)
    fun get(url: String): ImageBitmap? = bitmaps.get(url)
    fun put(url: String, img: ImageBitmap) { bitmaps.put(url, img) }
}

private const val BACKDROP_SIZE = 72

private suspend fun loadBlurred(context: Context, url: String): ImageBitmap? {
    val request = buildCoverPaletteRequest(context, url, BACKDROP_SIZE * 2) ?: return null
    val result = runCatching { context.imageLoader.execute(request) }.getOrNull() as? SuccessResult ?: return null
    val source = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val small = Bitmap.createScaledBitmap(source, BACKDROP_SIZE, BACKDROP_SIZE, true)
                .copy(Bitmap.Config.ARGB_8888, true)
            boxBlur(small, radius = 4, passes = 3)
            small.asImageBitmap()
        }.getOrNull()
    }
}

/** 三次盒式模糊 ≈ 高斯模糊（只处理 72×72 的小图，几毫秒） */
private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
    val w = bitmap.width
    val h = bitmap.height
    val a = IntArray(w * h)
    val b = IntArray(w * h)
    bitmap.getPixels(a, 0, w, 0, 0, w, h)
    repeat(passes) {
        blurLine(a, b, w, h, radius, horizontal = true)
        blurLine(b, a, w, h, radius, horizontal = false)
    }
    bitmap.setPixels(a, 0, w, 0, 0, w, h)
}

private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val outer = if (horizontal) h else w
    val inner = if (horizontal) w else h
    val div = r * 2 + 1
    for (o in 0 until outer) {
        var sr = 0; var sg = 0; var sb = 0
        fun px(i: Int): Int {
            val c = i.coerceIn(0, inner - 1)
            return if (horizontal) src[o * w + c] else src[c * w + o]
        }
        for (i in -r..r) {
            val p = px(i)
            sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
        }
        for (i in 0 until inner) {
            val idx = if (horizontal) o * w + i else i * w + o
            dst[idx] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = px(i + r + 1)
            val rem = px(i - r)
            sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
            sb += (add and 0xFF) - (rem and 0xFF)
        }
    }
}
