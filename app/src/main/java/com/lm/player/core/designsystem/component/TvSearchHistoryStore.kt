package com.lm.player.core.designsystem.component

import android.content.Context
import android.content.SharedPreferences

/**
 * 「点歌台」搜索历史持久化。
 *
 * 需求：切换界面（退出点歌台再进来、切到别的 Tab 再回来、甚至重启应用）后，
 * 之前搜过的关键词仍然可用，不用重新一个字一个字敲。
 *
 * 设计要点：
 *  - **环形去重**：同一个关键词再次搜索时提到最前，而不是重复出现；
 *  - **有序**：最新的在最前，方便一键复用；
 *  - **有上限**：默认最多 20 条，避免无限增长（SharedPreferences 单值也有大小限制）；
 *  - **分隔符安全**：关键词可能含换行/竖线吗？搜索词基本不会，但仍做清洗，避免把存储格式破坏。
 */
object TvSearchHistoryStore {
    private const val PREFS = "zds_ktv_search_history"
    private const val KEY_HISTORY = "history"
    private const val SEPARATOR = "\u0001"   // 用不可见控制符作分隔，杜绝与用户输入冲突
    private const val MAX_ITEMS = 20

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取历史，最新在最前 */
    fun load(context: Context): List<String> =
        prefs(context).getString(KEY_HISTORY, "")
            ?.split(SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * 记录一次搜索。
     *
     * @return 写入后的最新列表，便于调用方直接更新 UI 状态
     */
    fun remember(context: Context, keyword: String): List<String> {
        val word = sanitize(keyword)
        if (word.isEmpty()) return load(context)
        val current = load(context)
        // 环形去重：已有则先移除，再插到队首 -> 天然「最近使用优先」
        val updated = (listOf(word) + current.filter { !it.equals(word, ignoreCase = true) })
            .take(MAX_ITEMS)
        prefs(context).edit().putString(KEY_HISTORY, updated.joinToString(SEPARATOR)).apply()
        return updated
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    /** 去掉会破坏存储格式的控制符与首尾空白 */
    private fun sanitize(keyword: String): String =
        keyword.replace(SEPARATOR, " ").replace("\n", " ").replace("\r", " ").trim()
}
