package com.lm.player.feature.player.tv

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * 歌词景深模糊（2026-11-13 重做）：真正的模糊，取代「同一行字偏移叠画 6～12 遍」。
 *
 * 叠画法在偏移量大时能看出一个个分开的字影（用户反馈的「重影」），而且每帧要画 6～12 遍文字。
 * 现在每行在后台线程把文字画进一张**半分辨率**的小图、只对透明度做 3 遍盒式模糊（近似高斯），
 * 显示时把这张图放大画回原位、按当前文字颜色着色：边缘是连续的柔和过渡，没有字影；每帧只画一张图。
 *
 * - 图里的字一律画成白色，显示时再着色 —— 同一张图深色 / 浅色主题都能用；
 * - 按「歌词文字 + 译文 + 样式 + 排版宽度 + 模糊级别」缓存，切歌回来、上下来回滚都直接复用；
 * - 图还没算好的那一两帧先显示清晰的字（不会空白）。
 */

/** 生成好的一行模糊字图 */
internal class BlurredLyricLine(
    val image: ImageBitmap,
    /** 图的缩小比例（画回去时要放大 1/scale） */
    val scale: Float,
    /** 四周为模糊留的边（原分辨率像素） */
    val padPx: Float,
    /** 这一行（含译文）原本的排版高度（原分辨率像素），用来给占位高度 */
    val contentHeightPx: Int
)

private object LyricBlurCache {
    // 2026-11-13：所有看得见的行都模糊后同时在用的图更多，48 → 80（每张约几十 KB）
    private val cache = LruCache<String, BlurredLyricLine>(80)
    fun get(key: String): BlurredLyricLine? = cache.get(key)
    fun put(key: String, line: BlurredLyricLine) { cache.put(key, line) }
}

/**
 * 取一行歌词的模糊字图：缓存里有就立即返回；没有就在后台生成，生成好之前返回 null（调用方先显示清晰的字）。
 * @param level 模糊级别（离当前行的距离 1～3），决定模糊半径
 */
@Composable
internal fun rememberBlurredLyricLine(
    text: String,
    translation: String?,
    style: TextStyle,
    transStyle: TextStyle,
    transGapPx: Float,
    widthPx: Int,
    level: Int,
    radiusPx: Float
): BlurredLyricLine? {
    val fontResolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // 内容键（不含模糊级别）：同一句歌词只是级别变了时，先用旧级别的图垫着，不闪成清晰字
    val contentKey = "$text\u0001${translation.orEmpty()}\u0001${style.hashCode()}\u0001${transStyle.hashCode()}\u0001$widthPx\u0001${density.density}"
    val key = "$contentKey\u0001$level"
    // 2026-11-13 修复：以前用 produceState —— 它记住的值不随 key 重置，歌词内容变了以后，
    // 「已经有图」就跳过生成，一直显示最早那句歌词的模糊图。现在显示的图必须与当前内容对得上
    var shown by remember { mutableStateOf<ShownBlur?>(null) }
    val cached = remember(key) { LyricBlurCache.get(key) }
    LaunchedEffect(key) {
        if (cached != null) {
            shown = ShownBlur(key, contentKey, cached)
            return@LaunchedEffect
        }
        val made = try {
            renderBlurred(fontResolver, density, direction, text, translation, style, transStyle, transGapPx, widthPx, radiusPx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return@LaunchedEffect
        LyricBlurCache.put(key, made)
        shown = ShownBlur(key, contentKey, made)
    }
    val s = shown
    return when {
        cached != null -> cached
        s == null -> null
        s.key == key -> s.line
        s.contentKey == contentKey -> s.line // 同一句歌词、级别变了：新级别生成好之前先用旧的
        else -> null // 内容不同：先显示清晰的字
    }
}

private class ShownBlur(val key: String, val contentKey: String, val line: BlurredLyricLine)

/** 把模糊字图按 [color] 着色画出来；占位高度与清晰文字一致 */
@Composable
internal fun BlurredLyricLineView(line: BlurredLyricLine, color: Color) {
    val density = LocalDensity.current
    val heightDp = with(density) { line.contentHeightPx.toDp() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .drawBehind {
                val pad = line.padPx.roundToInt()
                drawImage(
                    image = line.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(line.image.width, line.image.height),
                    dstOffset = IntOffset(-pad, -pad),
                    dstSize = IntSize(
                        (line.image.width / line.scale).roundToInt(),
                        (line.image.height / line.scale).roundToInt()
                    ),
                    colorFilter = ColorFilter.tint(color),
                    filterQuality = FilterQuality.Low
                )
            }
    )
}

private const val BLUR_SCALE = 0.5f

/**
 * 生成一行模糊字图：排版（后台）→ 画字（**主线程**）→ 模糊（后台）。
 *
 * 2026-11-13 修复闪退（专辑卡片快速滑动、重进播放页时偶发闪退，且没有崩溃日志）：
 * Compose 画字（TextPainter.paint → TextLayout.paint）用的是**全进程共用的一块画布**
 * （SharedTextAndroidCanvas，不是线程安全的）。以前整个生成过程都在后台线程，后台画字时
 * 主线程也在画界面上的文字，两边互相把对方的画布换掉 → 系统图形层崩溃（native 崩溃，不经过 Java 异常，
 * 所以崩溃日志里什么都没有）。切歌 / 刚进播放页时一下子要生成很多行，最容易撞上。
 * 现在只把「画字」这一小步放回主线程（一行几百微秒），排版和模糊仍在后台。
 */
private suspend fun renderBlurred(
    fontResolver: FontFamily.Resolver,
    density: Density,
    direction: LayoutDirection,
    text: String,
    translation: String?,
    style: TextStyle,
    transStyle: TextStyle,
    transGapPx: Float,
    widthPx: Int,
    radiusPx: Float
): BlurredLyricLine {
    // 1. 排版：后台线程（只量不画，量的过程不碰共用画布）
    val (main, trans) = withContext(Dispatchers.Default) {
        val measurer = TextMeasurer(fontResolver, density, direction, cacheSize = 0)
        val m = measurer.measure(text, style.copy(color = Color.White), constraints = Constraints(maxWidth = widthPx))
        val t = translation?.let {
            measurer.measure(it, transStyle.copy(color = Color.White.copy(alpha = 0.85f)), constraints = Constraints(maxWidth = widthPx))
        }
        m to t
    }
    val contentH = main.size.height + (trans?.let { (transGapPx + it.size.height).roundToInt() } ?: 0)
    // 半分辨率下的模糊半径与四周留边
    val r = (radiusPx * BLUR_SCALE).coerceAtLeast(1f)
    val padScaled = ceil(r * 2.5f)
    val bw = ceil(widthPx * BLUR_SCALE + padScaled * 2f).toInt().coerceAtLeast(1)
    val bh = ceil(contentH * BLUR_SCALE + padScaled * 2f).toInt().coerceAtLeast(1)
    val image = ImageBitmap(bw, bh, ImageBitmapConfig.Argb8888)
    // 2. 画字：必须在主线程（见上方说明）
    withContext(Dispatchers.Main.immediate) {
        val canvas = Canvas(image)
        canvas.translate(padScaled, padScaled)
        canvas.scale(BLUR_SCALE, BLUR_SCALE)
        TextPainter.paint(canvas, main)
        if (trans != null) {
            canvas.translate(0f, main.size.height + transGapPx)
            TextPainter.paint(canvas, trans)
        }
    }
    // 3. 模糊：后台线程（只处理像素数组，不涉及文字）
    withContext(Dispatchers.Default) {
        alphaBoxBlur(image.asAndroidBitmap(), radius = ceil(r / 1.7f).toInt().coerceAtLeast(1), passes = 3)
    }
    return BlurredLyricLine(image, BLUR_SCALE, padScaled / BLUR_SCALE, contentH)
}

/** 只模糊透明度通道（字是白色，颜色在显示时着色）；3 遍盒式模糊 ≈ 高斯模糊 */
private fun alphaBoxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val a = IntArray(w * h) { (px[it] ushr 24) and 0xFF }
    val b = IntArray(w * h)
    repeat(passes) {
        blurAlpha(a, b, w, h, radius, horizontal = true)
        blurAlpha(b, a, w, h, radius, horizontal = false)
    }
    for (i in px.indices) px[i] = (a[i] shl 24) or 0x00FFFFFF
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
}

private fun blurAlpha(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val outer = if (horizontal) h else w
    val inner = if (horizontal) w else h
    val div = r * 2 + 1
    for (o in 0 until outer) {
        fun at(i: Int): Int {
            if (i < 0 || i >= inner) return 0 // 边界外视为透明（字的四周本来就是空白）
            return if (horizontal) src[o * w + i] else src[i * w + o]
        }
        var sum = 0
        for (i in -r..r) sum += at(i)
        for (i in 0 until inner) {
            val idx = if (horizontal) o * w + i else i * w + o
            dst[idx] = sum / div
            sum += at(i + r + 1) - at(i - r)
        }
    }
}
