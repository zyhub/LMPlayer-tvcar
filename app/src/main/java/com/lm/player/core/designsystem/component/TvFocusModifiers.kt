package com.lm.player.core.designsystem.component

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.UnifiedSong

/**
 * 全局背景层 TV 焦点开关：当全屏播放器 (FullscreenPlayerSheet) 展开时，
 * 将底层 AdaptiveAppScaffold 的此开关设为 false，彻底阻断底层隐藏页面抢夺 2D 方向键焦点导致光标消失。
 */
val LocalTvBackgroundFocusEnabled = compositionLocalOf { true }

/**
 * 安卓 TV 遥控器（D-Pad）专属高对比度双层焦点光环与按键交互修饰符
 * - 彻底移除底层 Modifier.shadow，根治半透明卡片与子封面双层阴影重叠伪影
 * - 采用 drawWithContent 顶层绘制双色高对比度焦点环（纯白衬底外内边 + 主题亮色核心环 + 顶层柔光提亮），
 *   即使在纯红底色按钮（AppleRed）、深色卡片或浅色背景上也能 100% 清晰凸显光标
 * - 完整支持遥控器 OK/确定键 (DPAD_CENTER / ENTER) 触发点击，菜单键或长按确定键触发二级菜单，
 *   并支持在 focusable 节点之前直接拦截左右方向键（如进度条快进/快退）
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.03f,
    focusedBorderColor: Color = AppleRed,
    focusedBackgroundAlpha: Float = 0.12f,
    borderWidth: Dp = 2.5.dp,
    enabled: Boolean = true,
    onFocusChange: (Boolean) -> Unit = {},
    onMenuKey: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLeftKey: ((Int) -> Boolean)? = null,
    onRightKey: ((Int) -> Boolean)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val backgroundFocusEnabled = LocalTvBackgroundFocusEnabled.current
    val effectiveEnabled = enabled && backgroundFocusEnabled

    var isFocused by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnMenuKey by rememberUpdatedState(onMenuKey)
    val scope = rememberCoroutineScope()
    var centerDownReceived by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 用 graphicsLayer 读动画值，而不是 Modifier.scale(animateFloatAsState(...).value)：
    // 后者在**组合期**读取动画状态 → 弹簧动画的每一帧都会让本组件(以及调用它的整块区域)重组。
    // 放进 graphicsLayer 的 lambda 后，读取发生在布局/绘制阶段，失效范围收敛到这一个图层，
    // 动画期间不再触发任何重组 (与工程内 VinylRotation 已采用的写法一致)。
    val scale = animateFloatAsState(
        targetValue = if (isFocused && effectiveEnabled) focusedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tv_focus_scale"
    )

    this
        .focusProperties {
            canFocus = effectiveEnabled
        }
        .zIndex(if (isFocused && effectiveEnabled) 2f else 0f)
        .graphicsLayer {
            val s = scale.value
            scaleX = s
            scaleY = s
        }
        .onFocusChanged { state ->
            val focused = (state.isFocused || state.hasFocus) && effectiveEnabled
            if (isFocused != focused) {
                isFocused = focused
                if (!focused) {
                    longPressJob?.cancel()
                    centerDownReceived = false
                    longPressTriggered = false
                }
                onFocusChange(focused)
            }
        }
        .onKeyEvent { event ->
            val native = event.nativeKeyEvent
            when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (native.action == KeyEvent.ACTION_DOWN && onLeftKey != null) {
                        onLeftKey(native.repeatCount)
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (native.action == KeyEvent.ACTION_DOWN && onRightKey != null) {
                        onRightKey(native.repeatCount)
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val longClickHandler = currentOnLongClick
                    if (longClickHandler == null) {
                        if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            currentOnClick()
                            true
                        } else {
                            native.action == KeyEvent.ACTION_DOWN || native.action == KeyEvent.ACTION_UP
                        }
                    } else {
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            if (native.repeatCount == 0) {
                                centerDownReceived = true
                                longPressTriggered = false
                                longPressJob?.cancel()
                                longPressJob = scope.launch {
                                    kotlinx.coroutines.delay(450L)
                                    if (centerDownReceived && !longPressTriggered) {
                                        longPressTriggered = true
                                        currentOnLongClick?.invoke()
                                    }
                                }
                            } else if (centerDownReceived && !longPressTriggered) {
                                longPressJob?.cancel()
                                longPressTriggered = true
                                longClickHandler.invoke()
                            }
                            true
                        } else if (native.action == KeyEvent.ACTION_UP) {
                            longPressJob?.cancel()
                            val shouldClick = centerDownReceived && !longPressTriggered
                            centerDownReceived = false
                            longPressTriggered = false
                            if (shouldClick) {
                                currentOnClick()
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (native.action == KeyEvent.ACTION_DOWN) {
                        val handler = currentOnMenuKey ?: currentOnLongClick
                        if (handler != null) {
                            handler.invoke()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        .drawWithContent {
            drawContent()
            if (isFocused && effectiveEnabled) {
                val outline = shape.createOutline(size, layoutDirection, this)
                if (focusedBackgroundAlpha > 0f) {
                    drawOutline(
                        outline = outline,
                        color = Color.White.copy(alpha = (focusedBackgroundAlpha * 0.65f).coerceIn(0.04f, 0.16f))
                    )
                }
                val outerStrokePx = (borderWidth + 2.dp).toPx()
                val coreStrokePx = borderWidth.toPx()
                // 外内双层高对比度光环：纯白高光衬边 + 主题色核心环，任何底色（含红底按钮）均清晰可见
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = 0.96f),
                    style = Stroke(width = outerStrokePx)
                )
                drawOutline(
                    outline = outline,
                    color = focusedBorderColor,
                    style = Stroke(width = coreStrokePx)
                )
            }
        }
        .clip(shape)
        .focusable(enabled = effectiveEnabled)
        .pointerInput(effectiveEnabled) {
            detectTapGestures(
                onLongPress = {
                    if (effectiveEnabled) {
                        try {
                            currentOnLongClick?.invoke()
                        } catch (_: Exception) {}
                    }
                },
                onTap = {
                    if (effectiveEnabled) {
                        try {
                            currentOnClick()
                        } catch (_: Exception) {}
                    }
                }
            )
        }
}

/**
 * 针对已自带 onClick / clickable 焦点的原生 Material3 组件（如 Button / OutlinedButton / TextButton / IconButton）
 * 专用 TV 焦点视觉强化修饰符：
 * - 不重复注册多余的 focusable 节点，避免遥控器需要按两次方向键才能移出按钮
 * - 在组件最顶层绘制双层高对比度焦点边框（纯白衬边 + 亮金/苹果红光标环）与微缩放动效，
 *   彻底解决红色底色按钮（如“添加新服务器”、“播放全部”）及普通按钮获焦时光标无法凸现的问题
 */
fun Modifier.tvButtonFocusable(
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.04f,
    focusedBorderColor: Color = Color(0xFFFFD60A),
    borderWidth: Dp = 2.5.dp,
    onFocusChange: (Boolean) -> Unit = {}
): Modifier = composed {
    val backgroundFocusEnabled = LocalTvBackgroundFocusEnabled.current
    var isFocused by remember { mutableStateOf(false) }
    // 同 tvFocusable：动画值放进 graphicsLayer 读取，避免聚焦弹簧动画期间每帧重组调用方
    val scale = animateFloatAsState(
        targetValue = if (isFocused && backgroundFocusEnabled) focusedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tv_btn_focus_scale"
    )

    this
        .focusProperties { canFocus = backgroundFocusEnabled }
        .zIndex(if (isFocused && backgroundFocusEnabled) 2f else 0f)
        .graphicsLayer {
            val s = scale.value
            scaleX = s
            scaleY = s
        }
        .onFocusChanged { state ->
            val focused = (state.isFocused || state.hasFocus) && backgroundFocusEnabled
            if (isFocused != focused) {
                isFocused = focused
                onFocusChange(focused)
            }
        }
        .drawWithContent {
            drawContent()
            if (isFocused && backgroundFocusEnabled) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = 0.12f)
                )
                val outerStrokePx = (borderWidth + 2.2.dp).toPx()
                val coreStrokePx = borderWidth.toPx()
                drawOutline(
                    outline = outline,
                    color = Color.White,
                    style = Stroke(width = outerStrokePx)
                )
                drawOutline(
                    outline = outline,
                    color = focusedBorderColor,
                    style = Stroke(width = coreStrokePx)
                )
            }
        }
}

/**
 * TV 遥控器专属大尺寸歌曲快捷操作面板
 * 解决电视端无法用方向键点按列表行内多个微小图标的痛点
 */
@Composable
fun TvSongActionDialog(
    song: UnifiedSong,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null
) {
    val dimensions = LocalAppDimensions.current
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            firstFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1A1C24),
            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.16f)),
            modifier = Modifier
                .widthIn(min = 380.dp, max = 480.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶部歌曲信息卡片
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AlbumArtworkImage(
                        model = song.coverUrl,
                        seedId = song.id,
                        modifier = Modifier.size(58.dp),
                        cornerRadius = 12.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            fontSize = dimensions.sectionTitleSize,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${song.artist} · ${song.album}",
                            fontSize = dimensions.bodySize,
                            color = Color.White.copy(alpha = 0.68f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                TvActionButton(
                    icon = Icons.Default.PlayArrow,
                    title = "立即播放此歌曲",
                    subtitle = "按遥控器 OK 键直接开始播放",
                    accentColor = AppleRed,
                    modifier = Modifier.focusRequester(firstFocusRequester),
                    onClick = {
                        onDismiss()
                        onPlayNow()
                    }
                )

                if (onToggleFavorite != null) {
                    TvActionButton(
                        icon = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        title = if (song.isFavorite) "取消红心收藏" else "加入我喜欢收藏",
                        subtitle = "同步至「我喜欢」收藏列表",
                        accentColor = if (song.isFavorite) AppleRed else Color.White,
                        onClick = {
                            onToggleFavorite()
                            onDismiss()
                        }
                    )
                }

                if (onAddToPlaylist != null) {
                    TvActionButton(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        title = "添加到自建歌单...",
                        subtitle = "将歌曲归档至本地或柠檬服务端歌单",
                        onClick = {
                            onDismiss()
                            onAddToPlaylist()
                        }
                    )
                }

                if (onDownload != null) {
                    TvActionButton(
                        icon = Icons.Default.Download,
                        title = "下载无损音频...",
                        subtitle = "选择音质下载至电视本机或远端柠檬服务器",
                        onClick = {
                            onDismiss()
                            onDownload()
                        }
                    )
                }

                TvActionButton(
                    icon = Icons.Default.Close,
                    title = "关闭操作菜单 (返回键)",
                    subtitle = "返回曲目列表继续浏览",
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun TvActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(
                shape = RoundedCornerShape(14.dp),
                focusedScale = 1.02f,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = dimensions.itemTitleSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = dimensions.captionSize,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}
