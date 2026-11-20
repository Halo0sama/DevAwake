package com.halo.devawake

import android.content.Context

/** 场景（连接类型） */
enum class Profile(val label: String) {
    NONE("无"),
    WIRED("有线"),
    WIFI("无线调试"),
}

/** 单个场景里可开关的动作 */
enum class DevAction(val label: String) {
    KEEP_AWAKE("保持亮屏"),
    AUTO_BRIGHTNESS("关闭自动亮度"),
    MIN_BRIGHTNESS("亮度调至最低"),
    NEVER_SLEEP("永不熄屏"),
}

/** 一个场景的开关组合 */
data class ProfileConfig(
    val enabled: Boolean = true,
    val keepAwake: Boolean = true,
    val autoBrightness: Boolean = true,
    val minBrightness: Boolean = true,
    val neverSleep: Boolean = true,
) {
    fun actions(): List<DevAction> = buildList {
        if (keepAwake) add(DevAction.KEEP_AWAKE)
        if (autoBrightness) add(DevAction.AUTO_BRIGHTNESS)
        if (minBrightness) add(DevAction.MIN_BRIGHTNESS)
        if (neverSleep) add(DevAction.NEVER_SLEEP)
    }
}

/** 界面开关对应的配置项 */
enum class Field { ENABLED, KEEP_AWAKE, AUTO_BRIGHTNESS, MIN_BRIGHTNESS, NEVER_SLEEP }

/** 开关配置的存取（默认全部开启） */
object Config {
    private const val PREFS = "config"

    fun wired(ctx: Context): ProfileConfig = read(ctx, "w_")

    fun wifi(ctx: Context): ProfileConfig = read(ctx, "f_")

    /** 无 Shizuku 时的兜底判定：按「无线调试」开关状态算作已连接（不精确，默认关闭） */
    fun wifiFallback(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("f_toggle_fallback", false)

    /** @return 配置是否真的发生了变化（值未变时返回 false，调用方可跳过重新应用） */
    fun updateWifiFallback(ctx: Context, value: Boolean): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changed = sp.getBoolean("f_toggle_fallback", false) != value
        if (changed) sp.edit().putBoolean("f_toggle_fallback", value).apply()
        return changed
    }

    /** @return 配置是否真的发生了变化（值未变时返回 false，调用方可跳过重新应用） */
    fun update(ctx: Context, wired: Boolean, field: Field, value: Boolean): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = if (wired) "w_" else "f_"
        val key = when (field) {
            Field.ENABLED -> "enabled"
            Field.KEEP_AWAKE -> "keep_awake"
            Field.AUTO_BRIGHTNESS -> "auto_bright"
            Field.MIN_BRIGHTNESS -> "min_bright"
            Field.NEVER_SLEEP -> "never_sleep"
        }
        val changed = sp.getBoolean(prefix + key, true) != value
        if (changed) sp.edit().putBoolean(prefix + key, value).apply()
        return changed
    }

    private fun read(ctx: Context, prefix: String): ProfileConfig {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ProfileConfig(
            enabled = sp.getBoolean(prefix + "enabled", true),
            keepAwake = sp.getBoolean(prefix + "keep_awake", true),
            autoBrightness = sp.getBoolean(prefix + "auto_bright", true),
            minBrightness = sp.getBoolean(prefix + "min_bright", true),
            neverSleep = sp.getBoolean(prefix + "never_sleep", true),
        )
    }
}
