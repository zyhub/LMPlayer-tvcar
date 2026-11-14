package com.lm.player.core.designsystem.component

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 全软件统一的系统提示（2026-11-13）：屏幕顶部居中的圆角气泡，带图标区分 成功 / 失败 / 普通，
 * 自动消失，跟随深浅色主题。替换以前混用的安卓原生 Toast 与播放页提示气泡。
 *
 * 用法与原生 Toast 一样：`AppToast.makeText(context, "文字", Toast.LENGTH_SHORT).show()`，任意线程可调。
 * 主界面不在前台（例如小组件、语音点歌在后台触发）时没有地方画气泡，自动退回原生 Toast，提示不会丢。
 */
object AppToast {
    enum class Kind { INFO, SUCCESS, ERROR }

    data class Message(val id: Long, val text: String, val kind: Kind, val durationMs: Long)

    private val _current = MutableStateFlow<Message?>(null)
    val current: StateFlow<Message?> = _current
    private val counter = AtomicLong()
    private val main = Handler(Looper.getMainLooper())

    /** 主界面的气泡宿主是否在前台（宿主根据生命周期维护） */
    @Volatile
    internal var hostActive = false

    class Pending internal constructor(private val context: Context, private val text: CharSequence, private val duration: Int) {
        fun show() = AppToast.show(context, text, duration)
    }

    /** 与 Toast.makeText 同签名，方便整体替换 */
    fun makeText(context: Context, text: CharSequence, duration: Int): Pending = Pending(context, text, duration)

    fun show(context: Context, text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val s = text.toString().trim()
        if (s.isEmpty()) return
        if (!hostActive) {
            val app = context.applicationContext ?: context
            main.post { runCatching { Toast.makeText(app, s, duration).show() } }
            return
        }
        val base = if (duration == Toast.LENGTH_LONG) 3400L else 2000L
        // 长句多给一点时间读完
        val ms = base + (s.length - 18).coerceIn(0, 40) * 40L
        _current.value = Message(counter.incrementAndGet(), s, kindOf(s), ms)
    }

    internal fun dismiss(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }

    private val errorWords = listOf("失败", "错误", "无法", "异常", "不可用", "未配置", "超时", "不支持", "未找到", "没有可用")
    private val successWords = listOf("成功", "完成", "已加入", "已添加", "已收藏", "已保存", "已复制", "已提交", "已切换", "已设置", "已删除", "已下载", "已开启", "已关闭", "已同步")

    private fun kindOf(s: String): Kind = when {
        errorWords.any { s.contains(it) } -> Kind.ERROR
        successWords.any { s.contains(it) } -> Kind.SUCCESS
        else -> Kind.INFO
    }
}

/**
 * 气泡宿主：挂在主界面最上层（全屏播放页之上）。只有它在前台时 [AppToast] 才用气泡，否则走原生 Toast。
 */
@Composable
fun AppToastHost(modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> AppToast.hostActive = true
                Lifecycle.Event.ON_PAUSE -> AppToast.hostActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        AppToast.hostActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            AppToast.hostActive = false
        }
    }
    val msg by AppToast.current.collectAsState()
    // 退场动画期间继续显示上一条的内容
    var shown by remember { mutableStateOf<AppToast.Message?>(null) }
    if (msg != null) shown = msg
    LaunchedEffect(msg?.id) {
        val m = msg ?: return@LaunchedEffect
        delay(m.durationMs)
        AppToast.dismiss(m.id)
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier = modifier.fillMaxWidth().padding(top = top + 14.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { -it / 3 }
        ) {
            shown?.let { ToastBubble(it) }
        }
    }
}

@Composable
private fun ToastBubble(m: AppToast.Message) {
    val dims = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val bg = if (isDark) Color(0xF01E1F25) else Color(0xF7FFFFFF)
    val border = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val textColor = if (isDark) Color.White else Color(0xFF1C1C1E)
    val (icon, tint) = when (m.kind) {
        AppToast.Kind.SUCCESS -> Icons.Filled.CheckCircle to Color(0xFF34C759)
        AppToast.Kind.ERROR -> Icons.Filled.ErrorOutline to Color(0xFFFF453A)
        AppToast.Kind.INFO -> Icons.Filled.Info to Color(0xFFFFC947)
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .shadow(14.dp, shape, clip = false)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BubbleIcon(icon, tint)
        Spacer(Modifier.width(10.dp))
        Text(
            text = m.text,
            fontSize = (dims.bodySize.value * 0.95f).coerceAtLeast(14f).sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BubbleIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
}
