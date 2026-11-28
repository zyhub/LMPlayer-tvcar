package com.lm.player.feature.settings

import com.lm.player.core.designsystem.component.TvDialog as Dialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.component.AppToast
import com.lm.player.core.designsystem.component.tvFocusable
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.voice.VoiceDiagnostics
import com.lm.player.core.voice.VoicePrefs

/**
 * 设置 →「语音控制」分类的内容（2026-11-24；同日由单独的二级页改成设置左侧导航里的一个分类）。
 * 用设置页现成的卡片 / 行组件拼出来，样式与设置页一致。
 * 内容：配合「音乐助手」的说明、通用开关、可以这样说、语音日志（导出文件）。
 * （DiLink 3.0 的小迪只能靠音乐助手中转；2026-11-24 试过的「小迪车机版」直连已删除。）
 */
@Composable
internal fun VoiceControlSettingsContent() {
    val context = LocalContext.current
    val dimensions = LocalAppDimensions.current
    remember { VoicePrefs.ensureLoaded(context) }
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    fun GuideText(text: String) {
        Text(
            text,
            fontSize = dimensions.captionSize,
            color = guideColor,
            lineHeight = (dimensions.captionSize.value * 1.5f).sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }

    // 导出日志文件（2026-11-24：车机上复制的文字传不出来，改成导出文件）
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var lastExport by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
                    SettingsCard(title = "配合「音乐助手」", icon = Icons.Filled.RecordVoiceOver) {
                        GuideText("1. 在音乐助手里把包名选为「自定义」，填写：${context.packageName}")
                        GuideText("2. 打开音乐助手「增强语音」下的所有开关，先确认它能回显你说的话。")
                        GuideText("3. 音乐助手目前只转发「播放某某」点歌和播放 / 暂停 / 切歌；「播放我喜欢的歌」「随机播放」这类命令它不转发。")
                    }

                    SettingsCard(title = "通用", icon = Icons.Filled.Tune) {
                        SettingSwitchRow(
                            title = "语音点歌时打开界面",
                            subtitle = "关闭后语音点歌只在后台换歌，不把软件切到前台、不打断导航（音乐助手点完歌顺手打开本软件时也会马上退回后台）；方向盘 / 语音「播放」唤醒续播始终不打开界面",
                            checked = VoicePrefs.showUi,
                            onCheckedChange = { VoicePrefs.updateShowUi(context, it) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingSwitchRow(
                            title = "语音提示",
                            subtitle = "点歌时弹出「正在播放…」「没找到…」等提示；用常规版音乐助手时小迪不会语音回复，建议打开",
                            checked = VoicePrefs.toastEnabled,
                            onCheckedChange = { VoicePrefs.updateToast(context, it) }
                        )
                    }

                    SettingsCard(title = "可以这样说", icon = Icons.Filled.Lightbulb) {
                        GuideText("点歌：「播放周杰伦的晴天」「我想听陈奕迅」（本地有就放本地，没有再在线搜）")
                        GuideText("歌单：「播放开车歌单」「播放我的歌单」（先找我的歌单，没有再在线搜）；「播放在线国风歌单」（带「在线」只在线搜）")
                        GuideText("分类：「播放我喜欢的歌」「播放最近播放」「播放每日推荐」「播放本地音乐」「播放热歌榜」")
                        GuideText("模式：「随机播放」「单曲循环」「顺序播放」；控制：「下一首」「暂停」「继续播放」")
                        GuideText("用音乐助手时，以上只有点歌和控制一定能用，其余取决于音乐助手是否转发。")
                    }

                    val diagVersion = VoiceDiagnostics.version.intValue
                    val lines = remember(diagVersion) { VoiceDiagnostics.lines(context) }
                    SettingsCard(title = "语音日志", icon = Icons.Filled.History) {
                        if (lines.isEmpty()) {
                            GuideText("还没有记录。说一次「播放某某」或用方向盘播放 / 切歌后再来看，这里会显示指令从哪来、怎么处理的。")
                        } else {
                            // 页面上只列最近 10 条，导出的文件里带全部
                            lines.take(10).forEach { GuideText(it) }
                            if (lines.size > 10) GuideText("…… 共 ${lines.size} 条，导出文件里有全部")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingActionRow(
                            title = "导出日志文件",
                            subtitle = lastExport?.let { "已导出到：$it" }
                                ?: "存到「下载 / LMPlayer」文件夹，用文件管理器拷到 U 盘或发给开发者",
                            actionText = if (exporting) "导出中" else "导出",
                            onAction = {
                                if (!exporting) {
                                    exporting = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { VoiceDiagnostics.exportToFile(context) }
                                        }
                                        exporting = false
                                        result.onSuccess { where ->
                                            lastExport = where
                                            AppToast.makeText(context, "已导出：$where", Toast.LENGTH_LONG).show()
                                        }.onFailure { e ->
                                            AppToast.makeText(context, "导出失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingActionRow(
                            title = "清空记录",
                            subtitle = "重新测试前先清空，看得更清楚",
                            actionText = "清空",
                            onAction = { VoiceDiagnostics.clear(context) }
                        )
                    }
    }
}
