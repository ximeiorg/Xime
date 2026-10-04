package com.kingzcheung.xime.data

import android.content.Context
import org.json.JSONArray

/**
 * 最近使用记录（LRU）：置顶去重，最久未用的排末尾，超出上限截断。
 * emoji 与符号面板各自独立记录（key 区分），持久化到 SharedPreferences（JSON 数组，
 * 兼容 emoji ZWJ 组合序列与任意符号字符）。
 *
 * UI 侧采用惰性刷新：面板打开期间点按只调用 [record] 持久化、不重排当前列表
 * （位置稳定，便于连续输入）；面板关闭后组合状态丢弃，重新打开时再读取最新顺序。
 */
object RecentUsageStore {
    const val MAX_COUNT = 32
    private const val PREFS_NAME = "recent_usage"
    const val KEY_RECENT_EMOJIS = "recent_emojis"
    const val KEY_RECENT_SYMBOLS = "recent_symbols"

    /**
     * LRU 核心逻辑（纯函数，便于单测）：置顶去重 + 上限截断。
     * 不按点击次数排序——"最近使用"语义是时间序，早期高频项按次数排序会固化霸榜。
     */
    fun record(list: List<String>, value: String, maxCount: Int = MAX_COUNT): List<String> =
        (listOf(value) + list.filter { it != value }).take(maxCount)

    fun get(context: Context, key: String): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** 记录一次使用并持久化，返回更新后的列表（供 UI 状态直接刷新） */
    fun record(context: Context, key: String, value: String): List<String> {
        val updated = record(get(context, key), value)
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, arr.toString()).apply()
        return updated
    }
}
