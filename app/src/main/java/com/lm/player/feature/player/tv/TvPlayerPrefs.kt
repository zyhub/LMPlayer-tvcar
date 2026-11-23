package com.lm.player.feature.player.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lm.player.core.designsystem.theme.PlayerThemeStyle

/**
 * 播放页偏好（2026-11-12 重做）：当前主题、每个主题各自的歌词样式与字号、歌词快慢。
 * 存在原来的 `zds_lyrics_prefs` 里（主题键与快慢键沿用旧键，升级后选择不丢）。
 *
 * 样式和字号按主题分开记：大字歌词主题默认就很大，在黑胶主题里调小字号不会影响它。
 */
object TvPlayerPrefs {
    private const val PREFS = "zds_lyrics_prefs"
    private const val KEY_THEME = "player_theme_style"
    private const val KEY_OFFSET = "lyrics_offset_ms"

    const val FONT_MIN = 16f
    const val FONT_MAX = 96f
    const val FONT_STEP = 2f

    private var prefs: SharedPreferences? = null

    var theme by mutableStateOf(PlayerThemeStyle.MODERN)
        private set
    var offsetMs by mutableLongStateOf(0L)
        private set

    private val styles = mutableStateMapOf<PlayerThemeStyle, LyricDisplayStyle>()
    private val fontSizes = mutableStateMapOf<PlayerThemeStyle, Float>()

    /** 三个可选主题（顺序即主题面板里的顺序） */
    val selectableThemes = listOf(
        PlayerThemeStyle.MODERN, PlayerThemeStyle.IPOD_RETRO, PlayerThemeStyle.NETEASE_TV_COVER,
        // 2026-11-23 新增
        PlayerThemeStyle.RECORD_SLEEVE
    )

    fun ensureLoaded(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = PlayerThemeStyle.fromId(p.getString(KEY_THEME, PlayerThemeStyle.MODERN.id) ?: PlayerThemeStyle.MODERN.id)
        // 「大字歌词」主题已删除（2026-11-13），原来选它的改用接替它的「专辑卡片」
        val migrated = if (saved == PlayerThemeStyle.KARAOKE_COVER) PlayerThemeStyle.IPOD_RETRO else saved
        theme = if (migrated in selectableThemes) migrated else PlayerThemeStyle.MODERN
        if (migrated != saved) p.edit().putString(KEY_THEME, migrated.id).apply()
        offsetMs = p.getLong(KEY_OFFSET, 0L)
        cardLyricsOpen = p.getBoolean("tv_card_lyrics_open", true)
        capsuleCollapsed = p.getBoolean("tv_capsule_collapsed", false)
        selectableThemes.forEach { t ->
            styles[t] = LyricDisplayStyle.fromId(p.getString("tv_lyric_style_${t.id}", null), defaultStyle(t))
            fontSizes[t] = p.getFloat("tv_lyric_font_${t.id}", defaultFont(t)).coerceIn(FONT_MIN, FONT_MAX)
        }
        prefs = p
    }

    private fun defaultStyle(@Suppress("UNUSED_PARAMETER") t: PlayerThemeStyle): LyricDisplayStyle = LyricDisplayStyle.FLOW

    private fun defaultFont(t: PlayerThemeStyle): Float = when (t) {
        PlayerThemeStyle.IPOD_RETRO -> 26f
        // 全屏封面改为五行歌词后默认字号从 40 调到 34，五行才放得下（已手动调过字号的不受影响）
        PlayerThemeStyle.NETEASE_TV_COVER -> 34f
        else -> 30f
    }

    fun styleFor(t: PlayerThemeStyle): LyricDisplayStyle = styles[t] ?: defaultStyle(t)
    fun fontFor(t: PlayerThemeStyle): Float = fontSizes[t] ?: defaultFont(t)

    fun updateTheme(t: PlayerThemeStyle) {
        theme = t
        prefs?.edit()?.putString(KEY_THEME, t.id)?.apply()
    }

    fun updateStyle(t: PlayerThemeStyle, style: LyricDisplayStyle) {
        styles[t] = style
        prefs?.edit()?.putString("tv_lyric_style_${t.id}", style.id)?.apply()
    }

    fun updateFont(t: PlayerThemeStyle, size: Float) {
        val v = size.coerceIn(FONT_MIN, FONT_MAX)
        fontSizes[t] = v
        prefs?.edit()?.putFloat("tv_lyric_font_${t.id}", v)?.apply()
    }

    /** 专辑卡片主题：右侧歌词栏是否展开（手机端可收起，记住上次的选择） */
    /**
     * 退出播放页时胶囊是否处于收起（画面放大）状态：下次进来保持（2026-11-13 用户要求，三个主题通用）。
     * 只在进 / 出播放页时读写，不需要是可观察状态。
     */
    var capsuleCollapsed: Boolean = false
        private set

    fun updateCapsuleCollapsed(collapsed: Boolean) {
        if (capsuleCollapsed == collapsed) return
        capsuleCollapsed = collapsed
        prefs?.edit()?.putBoolean("tv_capsule_collapsed", collapsed)?.apply()
    }

    var cardLyricsOpen by mutableStateOf(true)
        private set

    fun updateCardLyricsOpen(open: Boolean) {
        cardLyricsOpen = open
        prefs?.edit()?.putBoolean("tv_card_lyrics_open", open)?.apply()
    }

    fun updateOffset(ms: Long) {
        offsetMs = ms.coerceIn(-5000L, 5000L)
        prefs?.edit()?.putLong(KEY_OFFSET, offsetMs)?.apply()
    }
}
