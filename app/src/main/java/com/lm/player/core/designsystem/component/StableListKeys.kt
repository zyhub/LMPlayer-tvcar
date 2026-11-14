package com.lm.player.core.designsystem.component

import java.lang.ref.WeakReference

/**
 * 界面优化：列表项的稳定 key。
 *
 * 原先不少列表的 key 写成「序号 + id」（为了防止列表里有重复 id 时 LazyColumn 因 key 重复闪退）。
 * 代价是：列表头部增删一项时，后面所有项的 key 全变 → 整个列表重建、遥控器焦点跳走。
 *
 * 这里改为：id 第一次出现时 key 就是 id，同一 id 第 N 次出现时为「id#N」。
 * 既不会重复，又与位置无关 —— 头部增删时其余项 key 不变，只重建真正变化的那一项。
 *
 * 用法：`key = { index, song -> StableListKeys.keyAt(list, index, "libsong_") { it.id } }`
 * 按列表实例缓存，同一份列表只计算一次；只适用于「内容变化就换新列表实例」的不可变列表。
 */
object StableListKeys {
    private const val MAX_ENTRIES = 24

    private class Entry(val list: WeakReference<List<*>>, val prefix: String, val keys: Array<String>)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun <T> keyAt(list: List<T>, index: Int, prefix: String, idOf: (T) -> String): String {
        val entry = entries.firstOrNull { it.list.get() === list && it.prefix == prefix && it.keys.size == list.size }
            ?: build(list, prefix, idOf)
        return entry.keys.getOrElse(index) { "$prefix#idx$index" }
    }

    private fun <T> build(list: List<T>, prefix: String, idOf: (T) -> String): Entry {
        val seen = HashMap<String, Int>(list.size * 2)
        val keys = Array(list.size) { i ->
            val id = idOf(list[i])
            val n = (seen[id] ?: 0) + 1
            seen[id] = n
            if (n == 1) "$prefix$id" else "$prefix$id#$n"
        }
        val entry = Entry(WeakReference(list), prefix, keys)
        entries.removeAll { it.list.get() == null }
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        return entry
    }
}
