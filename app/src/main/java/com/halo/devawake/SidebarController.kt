package com.halo.devawake

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * MIUI / HyperOS 侧边应用栏（全局侧边栏 / 游戏工具箱）的隐藏与恢复。
 *
 * 该状态存储在 settings 表的 MIUI 私有键里（随系统版本不同），键名通过 [discover]
 * 在本机上枚举确认后填进 [CANDIDATES]。黑屏进入时保存原值并写入禁用值，退出时恢复。
 *
 * 原值以「table|key|value」映射持久化：黑屏期间进程被杀也能在下次 restore 时恢复，
 * 且重复 hide 不会用禁用值覆盖真正的原值。旧格式（每键一个 pref）由 [migrateLegacy] 迁移。
 */
object SidebarController {
    private const val TAG = "DevAwake"
    private const val PREFS = "sidebar"
    private const val MAP_KEY = "saved_map"
    private const val LEGACY_PREFIX = "saved_"

    /**
     * 本机（REDMI K90 Pro Max / HyperOS）侧边栏相关键。
     * 实测记录：system/game_toolbox_wild_mode=0 不能隐藏把手；
     * 尝试 global/launcher_sidebar_feature_enabled（MIUI 自由窗口侧边栏由桌面/系统 UI 绘制）。
     */
    private val CANDIDATES: List<Triple<String, String, String>> = listOf(
        Triple("global", "launcher_sidebar_feature_enabled", "0"),
    )

    /** 仅用于调试显示的键（不做隐藏/恢复） */
    private val DISPLAY_ONLY: List<Pair<String, String>> = listOf(
        "system" to "game_toolbox_wild_mode",
    )

    fun hide(ctx: Context) {
        if (CANDIDATES.isEmpty()) return
        val sp = prefs(ctx)
        val saved = loadMap(sp)
        for ((table, key, off) in CANDIDATES) {
            val mapKey = "$table|$key"
            if (!saved.containsKey(mapKey)) {
                val current = rawGet(table, key) ?: continue
                saved[mapKey] = current
            }
            if (rawPut(table, key, off)) {
                Log.i(TAG, "sidebar hidden: $table/$key -> $off")
            } else {
                Log.w(TAG, "sidebar hide failed: $table/$key")
            }
        }
        saveMap(sp, saved)
    }

    fun restore(ctx: Context) {
        val sp = prefs(ctx)
        val saved = loadMap(sp)
        if (saved.isEmpty()) return
        val pending = mutableMapOf<String, String>()
        for ((mapKey, value) in saved) {
            val table = mapKey.substringBefore('|')
            val key = mapKey.substringAfter('|')
            if (rawPut(table, key, value)) {
                Log.i(TAG, "sidebar restored: $table/$key -> $value")
            } else {
                Log.w(TAG, "sidebar restore failed (保留现场待重试): $table/$key")
                pending[mapKey] = value
            }
        }
        saveMap(sp, pending)
    }

    /** 旧格式遗留（saved_<table>_<key>）的原值迁移恢复，App 启动时调用一次 */
    fun migrateLegacy(ctx: Context) {
        val sp = prefs(ctx)
        val legacy = sp.all.filterKeys { it.startsWith(LEGACY_PREFIX) && it != MAP_KEY }
        if (legacy.isEmpty()) return
        for ((prefKey, value) in legacy) {
            val rest = prefKey.removePrefix(LEGACY_PREFIX)
            val table = listOf("system", "secure", "global").firstOrNull { rest.startsWith("$it" + "_") }
                ?: continue
            val key = rest.removePrefix("$table" + "_")
            if (rawPut(table, key, value.toString())) {
                Log.i(TAG, "legacy sidebar restored: $table/$key=$value")
                sp.edit().remove(prefKey).apply()
            } else {
                Log.w(TAG, "legacy sidebar restore failed: $table/$key")
            }
        }
    }

    /** 调试用：候选键实时值 + 遗留未恢复标记 */
    fun liveStatus(ctx: Context): String {
        val parts = (CANDIDATES.map { (t, k, _) -> Triple(t, k, false) } +
                DISPLAY_ONLY.map { Triple(it.first, it.second, true) }).map { (table, key, displayOnly) ->
            val v = ShizukuWriter.rawExec("settings get $table $key")?.take(12) ?: "?"
            val tag = if (displayOnly) "(仅显示)" else ""
            "$table/$key=$v$tag"
        }
        val sp = prefs(ctx)
        val pending = loadMap(sp)
        val savedMark = if (pending.isEmpty()) "" else "；待恢复:${pending.keys.joinToString(",")}"
        return parts.joinToString("；") + savedMark
    }

    /** 发现结果缓存（历史调试），主界面读取显示 */
    fun candidatesText(ctx: Context): String =
        prefs(ctx).getString("candidates", null).orEmpty()

    /**
     * 键名发现：按表枚举 settings，把与侧边栏/自由窗口相关的键存入 SharedPreferences
     * （主界面显示；logcat 在该设备上被系统噪音淹没无法读取）。已确认键名后不再调用。
     */
    fun discover() {
        Tasks.run {
            val ctx = App.instance
            val re = "sidebar|side_slide|sideslide|toolbox|freeform"
            val cmd = listOf("sys" to "system", "sec" to "secure", "glb" to "global")
                .joinToString("; ") { (tag, table) ->
                    "settings list $table | grep -iE '$re' | sed 's/^/[$tag] /'"
                }
            val out = ShizukuWriter.rawExec(cmd)
            val text = if (out == null) {
                "（Shizuku 执行失败）"
            } else {
                val hits = out.lineSequence()
                    .filter { it.startsWith("[") }
                    .take(30)
                    .map { line ->
                        val kv = line.split('=', limit = 2)
                        (kv.getOrNull(0)?.trim().orEmpty()) + "=" + (kv.getOrNull(1)?.trim()?.take(20).orEmpty())
                    }
                    .toList()
                if (hits.isEmpty()) "（无匹配键）" else hits.joinToString("；")
            }
            prefs(ctx).edit().putString("candidates", text).apply()
            Log.i(TAG, "sidebar candidates: $text")
        }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadMap(sp: SharedPreferences): MutableMap<String, String> {
        val raw = sp.getString(MAP_KEY, null) ?: return mutableMapOf()
        val map = mutableMapOf<String, String>()
        for (entry in raw.split(';')) {
            if (entry.isBlank()) continue
            val parts = entry.split('|')
            if (parts.size >= 3) map["${parts[0]}|${parts[1]}"] = parts.drop(2).joinToString("|")
        }
        return map
    }

    private fun saveMap(sp: SharedPreferences, map: Map<String, String>) {
        sp.edit().putString(MAP_KEY, map.entries.joinToString(";") { "${it.key}|${it.value}" }).apply()
    }

    private fun rawGet(table: String, key: String): String? =
        ShizukuWriter.rawExec("settings get $table $key")
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun rawPut(table: String, key: String, value: String): Boolean {
        val out = ShizukuWriter.rawExec("settings put $table $key $value") ?: return false
        return !out.contains("Exception", true) && !out.contains("Error", true)
    }
}
