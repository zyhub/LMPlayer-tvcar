package com.lm.player.core.designsystem.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 启动时进入的页面 */
enum class StartPage(val label: String) {
    /** 「我的」：只加载本地资料库，启动最快（默认） */
    MINE("我的"),

    /** 「发现」：启动后会拉取推荐歌单 / 榜单 / 新歌 / 新碟 */
    HOME("发现"),

    /** 上次退出时停留的顶部页面 */
    LAST("上次离开的页面")
}

/**
 * TV 端界面偏好（2026-11-08 界面优化）：启动页面、主界面黑胶旋转、大封面淡入。
 * 值是 Compose 可观察状态，设置页修改后界面立即生效。
 */
object AppUiPrefs {
    private const val PREFS = "lemon_settings_prefs"
    private const val KEY_START_PAGE = "ui_start_page_v1"
    private const val KEY_LAST_PAGE = "ui_last_top_page_v1"
    private const val KEY_MAIN_VINYL_ROTATION = "ui_main_vinyl_rotation_v1"
    private const val KEY_COVER_FADE_IN = "ui_cover_fade_in_v1"
    private const val KEY_KEEP_PAGES_ALIVE = "ui_keep_pages_alive_v1"
    private const val KEY_HIDE_PLAYER_STATUS_BAR = "ui_player_hide_status_bar_v1"

    @Volatile
    private var initialized = false

    var startPage by mutableStateOf(StartPage.MINE)
        private set

    /** 主界面左侧黑胶卡片播放时是否旋转（全屏播放页不受影响） */
    var mainVinylRotation by mutableStateOf(true)
        private set

    /** 大封面（黑胶卡 / 播放页 / 大卡片）加载完成时是否淡入 */
    var coverFadeIn by mutableStateOf(true)
        private set

    /** 切页时把「我的」「发现」保留在后台（不销毁重建），见 ui/KeepAlivePageHost */
    var keepPagesAlive by mutableStateOf(true)
        private set

    /** 全屏播放页隐藏系统顶部状态栏（2026-11-13，默认开启；从屏幕顶部下滑可临时呼出） */
    var hidePlayerStatusBar by mutableStateOf(true)
        private set

    /** 须在 [SmoothMode.init] 之后调用：大封面淡入的默认值随流畅模式（流畅模式下默认关闭） */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        startPage = runCatching { StartPage.valueOf(p.getString(KEY_START_PAGE, StartPage.MINE.name)!!) }
            .getOrDefault(StartPage.MINE)
        mainVinylRotation = p.getBoolean(KEY_MAIN_VINYL_ROTATION, true)
        coverFadeIn = if (p.contains(KEY_COVER_FADE_IN)) p.getBoolean(KEY_COVER_FADE_IN, true)
        else !SmoothMode.activeAtStartup
        keepPagesAlive = p.getBoolean(KEY_KEEP_PAGES_ALIVE, true)
        hidePlayerStatusBar = p.getBoolean(KEY_HIDE_PLAYER_STATUS_BAR, true)
    }

    fun updateHidePlayerStatusBar(context: Context, enabled: Boolean) {
        hidePlayerStatusBar = enabled
        prefs(context).edit().putBoolean(KEY_HIDE_PLAYER_STATUS_BAR, enabled).apply()
    }

    fun updateKeepPagesAlive(context: Context, enabled: Boolean) {
        keepPagesAlive = enabled
        prefs(context).edit().putBoolean(KEY_KEEP_PAGES_ALIVE, enabled).apply()
    }

    fun updateStartPage(context: Context, page: StartPage) {
        startPage = page
        prefs(context).edit().putString(KEY_START_PAGE, page.name).apply()
    }

    fun updateMainVinylRotation(context: Context, enabled: Boolean) {
        mainVinylRotation = enabled
        prefs(context).edit().putBoolean(KEY_MAIN_VINYL_ROTATION, enabled).apply()
    }

    fun updateCoverFadeIn(context: Context, enabled: Boolean) {
        coverFadeIn = enabled
        prefs(context).edit().putBoolean(KEY_COVER_FADE_IN, enabled).apply()
    }

    /** 记录最后停留的顶部页面（仅「上次离开的页面」模式会读取） */
    fun recordLastPage(context: Context, screenName: String) {
        val p = prefs(context)
        if (p.getString(KEY_LAST_PAGE, null) != screenName) p.edit().putString(KEY_LAST_PAGE, screenName).apply()
    }

    fun lastPage(context: Context): String? = prefs(context).getString(KEY_LAST_PAGE, null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
