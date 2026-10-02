package com.lm.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import android.view.KeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.scale
import com.lm.player.core.model.PlatformMode

/**
 * 首次启动的**平台模式向导**（不可跳过）。
 *
 * 必须二选一：电视 / 机顶盒（遥控器 D-Pad 焦点交互）或车机 / 中控（触控为主，停用焦点体系）。
 * 选择结果写入 `lemon_settings_prefs`，之后可在「设置 → 播放与启动行为 → 使用平台模式」中切换。
 *
 * 不可跳过的三重保证：
 * 1. 拦截并吞掉返回键（[BackHandler]）；
 * 2. 全屏**不透明**背景，底层界面完全不可见；
 * 3. 空白区域消费所有点击，避免穿透到底层界面误触。
 *
 * 向导呈现期间平台模式按 [PlatformMode.TV] 生效（见 MainActivity 的 `effectivePlatformMode`），
 * 因此即使设备**没有触屏**，也能用遥控器方向键 + OK 键完成选择。
 */
@Composable
fun PlatformModeWizard(
    onSelect: (PlatformMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val backgroundColor = if (isDark) Color(0xFF0E0E12) else Color(0xFFF4F4F8)
    val tvAccent = AppleRed
    val carAccent = Color(0xFF34C759)

    // 不可跳过：吞掉返回键
    BackHandler(enabled = true) { }

    // 默认把焦点落在「电视」卡片上：电视端与车机端都能直接按 OK 确认，方向键也能立刻移动光标
    val tvFocusRequester = remember { FocusRequester() }
    val carFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            kotlinx.coroutines.delay(80L)
            tvFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            // 消费空白区域的点击，防止穿透到底层已渲染的界面
            .pointerInput(Unit) { detectTapGestures { } }
            // 上下方向键在本层整体吞掉：向导只有左右两张卡片，按上下键不应把焦点交给
            // 身后被遮住的主界面（那会让光标「消失」在看不见的地方）。左右键照常放行。
            .onKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = dimensions.scale(48.dp), vertical = dimensions.scale(28.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "请选择使用平台",
                fontSize = dimensions.cardHeaderSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(dimensions.scale(10.dp)))
            Text(
                text = "该选择决定界面交互方式，可在「设置 → 播放与启动行为」中随时切换",
                fontSize = dimensions.captionSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = (dimensions.captionSize.value * 1.4f).sp
            )
            Spacer(modifier = Modifier.height(dimensions.scale(30.dp)))

            Row(horizontalArrangement = Arrangement.spacedBy(dimensions.scale(26.dp))) {
                PlatformModeCard(
                    icon = Icons.Default.Tv,
                    title = PlatformMode.TV.displayName,
                    subtitle = "使用遥控器方向键操作，完整启用焦点光环与按键快捷键",
                    accent = tvAccent,
                    onClick = { onSelect(PlatformMode.TV) },
                    modifier = Modifier
                        .focusRequester(tvFocusRequester)
                        // 焦点围栏：上下穿越一律 Cancel（光标不动），左右与另一张卡片互相跳转，
                        // 使光标永远被限制在「电视」「车机」两个选项之间。
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            right = carFocusRequester
                        }
                )
                PlatformModeCard(
                    icon = Icons.Default.DirectionsCar,
                    title = PlatformMode.CAR.displayName,
                    subtitle = "以触控为主，停用遥控器焦点体系以降低车机资源占用",
                    accent = carAccent,
                    onClick = { onSelect(PlatformMode.CAR) },
                    modifier = Modifier
                        .focusRequester(carFocusRequester)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            left = tvFocusRequester
                            right = FocusRequester.Cancel
                        }
                )
            }

            Spacer(modifier = Modifier.height(dimensions.scale(24.dp)))
            Text(
                text = "提示：误选后可在设置中改回；若设备无触屏又误选了「车机 / 中控」，将无法再操作界面。",
                fontSize = dimensions.captionSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                lineHeight = (dimensions.captionSize.value * 1.35f).sp
            )
        }
    }
}

@Composable
private fun PlatformModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val cardBackground = if (isDark) Color(0xFF26262E) else Color.White
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val shape = RoundedCornerShape(dimensions.scale(18.dp))

    Column(
        modifier = modifier
            .width(dimensions.scale(300.dp))
            // tvFocusable 放在 clip/background 之前：焦点光环绘制在本卡片内容之上
            .tvFocusable(
                shape = shape,
                focusedScale = 1.04f,
                focusedBorderColor = accent,
                onClick = onClick
            )
            .clip(shape)
            .background(cardBackground)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = dimensions.scale(24.dp), vertical = dimensions.scale(28.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(dimensions.scale(64.dp))
        )
        Spacer(modifier = Modifier.height(dimensions.scale(16.dp)))
        Text(
            text = title,
            fontSize = dimensions.itemTitleSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(dimensions.scale(8.dp)))
        Text(
            text = subtitle,
            fontSize = dimensions.captionSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = (dimensions.captionSize.value * 1.4f).sp
        )
    }
}
