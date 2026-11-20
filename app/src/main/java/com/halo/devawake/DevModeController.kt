package com.halo.devawake

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log

/**
 * 当前供电状态。
 * 小米等机型会把连电脑识别成 AC 充电，所以不能靠电池上报的 USB/AC 类型区分；
 * 改用「USB 是否被主机枚举」：电脑会枚举手机，充电头/充电宝不会。
 */
data class Power(val plugged: Int, val usbData: Boolean) {
    val isComputer: Boolean get() = plugged != 0 && usbData

    fun describe(): String = when {
        isComputer -> "USB 连接电脑"
        plugged != 0 -> "${DevModeController.pluggedText(plugged)}（无数据连接）"
        else -> "未接入电源"
    }
}

/**
 * 场景状态机：
 *   NONE --[有线连电脑]--> WIRED --[断开/只充电]--> NONE
 *   NONE --[无线调试有电脑连入]--> WIFI --[断开]--> NONE
 * 有线与无线同时满足时优先有线；切换场景时先恢复上一个场景的动作，再按新场景的开关配置应用。
 * 每个场景可独立开关：启用、保持亮屏、关闭自动亮度、亮度调至最低（见 Config）。
 */
object DevModeController {
    private const val TAG = "DevAwake"
    private const val PREFS = "state"

    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_SAVED_SET = "saved_actions"
    private const val KEY_RESTORE_FAILED = "restore_failed"
    private const val KEY_SAVED_STAY = "saved_stay"
    private const val KEY_SAVED_MODE = "saved_mode"
    private const val KEY_SAVED_BRIGHT = "saved_bright"
    private const val KEY_SAVED_TIMEOUT = "saved_timeout"
    private const val KEY_LAST_MSG = "last_message"

    private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

    /** 亮度 0 在部分机型等于熄屏，1 是安全的最低可见亮度 */
    private const val MIN_BRIGHTNESS = 1

    /**
     * 「永不熄屏」用的熄屏超时（毫秒，约 24.9 天）。
     * stay_on_while_plugged_in 只在插电时生效；无线调试时手机不充电，
     * 必须临时把 system 表的 screen_off_timeout 改成超大值，退出时恢复原值。
     */
    private const val NEVER_SLEEP_TIMEOUT = Int.MAX_VALUE

    /** 与开发者选项「充电时保持亮屏」一致：覆盖全部供电类型 */
    private const val STAY_ON_ANY = BatteryManager.BATTERY_PLUGGED_AC or
            BatteryManager.BATTERY_PLUGGED_USB or
            BatteryManager.BATTERY_PLUGGED_WIRELESS or
            BatteryManager.BATTERY_PLUGGED_DOCK

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 信号 ----------

    fun power(ctx: Context): Power {
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val usb = ctx.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
        return Power(
            plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
            usbData = usb?.getBooleanExtra("connected", false) ?: false,
        )
    }

    fun pluggedText(p: Int): String = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC 充电"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB 充电"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "底座供电"
        else -> "未接入电源"
    }

    // ---------- 状态查询 ----------

    fun activeProfile(ctx: Context): Profile =
        runCatching {
            Profile.valueOf(prefs(ctx).getString(KEY_ACTIVE, Profile.NONE.name) ?: Profile.NONE.name)
        }.getOrDefault(Profile.NONE)

    fun isActive(ctx: Context): Boolean = activeProfile(ctx) != Profile.NONE

    fun lastMessage(ctx: Context): String = prefs(ctx).getString(KEY_LAST_MSG, "—") ?: "—"

    fun remember(ctx: Context, msg: String) {
        prefs(ctx).edit().putString(KEY_LAST_MSG, msg).apply()
        Log.i(TAG, msg)
    }

    // ---------- 同步入口 ----------

    /** 按当前信号（有线供电+枚举 / 无线调试连接）计算目标场景并迁移 */
    @Synchronized
    fun sync(ctx: Context, reason: String): String {
        val power = power(ctx)
        Log.d(TAG, "signals: plugged=${power.plugged} usbData=${power.usbData} wifi=${WirelessMonitor.connected} reason=$reason")
        val msg = transition(ctx, desiredProfile(ctx), reason, force = false)
        remember(ctx, msg)
        return msg
    }

    /** 「立即应用 / 立即恢复」按钮：进入或退出指定场景（已在目标场景时只提示，不重复写入） */
    @Synchronized
    fun forceProfile(ctx: Context, profile: Profile, reason: String): String {
        val msg = transition(ctx, profile, reason, force = false)
        remember(ctx, msg)
        return msg
    }

    /** 开关变化后：恢复已应用的动作，再按新配置重新应用 */
    @Synchronized
    fun onConfigChanged(ctx: Context): String {
        val msg = transition(ctx, desiredProfile(ctx), "设置变更", force = true)
        remember(ctx, msg)
        return msg
    }

    private fun desiredProfile(ctx: Context): Profile = when {
        power(ctx).isComputer && Config.wired(ctx).enabled -> Profile.WIRED
        WirelessMonitor.connected && Config.wifi(ctx).enabled -> Profile.WIFI
        else -> Profile.NONE
    }

    /**
     * 场景迁移：先恢复已应用动作（失败则整体不动作、保留现场等待下次重试），
     * 成功后按目标场景的开关配置应用。
     */
    private fun transition(ctx: Context, desired: Profile, reason: String, force: Boolean): String {
        val p = prefs(ctx)
        val active = activeProfile(ctx)
        val saved = p.getStringSet(KEY_SAVED_SET, emptySet()) ?: emptySet()
        // 已在目标场景且没有待重试的恢复失败时不做任何写入：
        // 否则每次打开应用、每条粘性广播都会把设置恢复再重放一遍，造成闪动
        val pendingRestore = saved.isNotEmpty() && p.getBoolean(KEY_RESTORE_FAILED, false)
        if (desired == active && !force && !pendingRestore) {
            return when (desired) {
                Profile.NONE -> "当前${power(ctx).describe()}，无需处理（$reason）"
                Profile.WIRED -> "已连接电脑（有线），调试模式已生效（$reason）"
                Profile.WIFI -> "已连接电脑（无线调试），调试模式已生效（$reason）"
            }
        }

        val parts = mutableListOf<String>()
        val r = restore(ctx)
        if (r.failed) return "${r.msg}（$reason）"
        r.msg?.let { parts.add(it) }
        if (desired != Profile.NONE) parts.add(applyProfile(ctx, desired, reason))
        return parts.joinToString("；").ifEmpty { "无需处理（$reason）" }
    }

    private class RestoreResult(val msg: String?, val failed: Boolean = false)

    /** 恢复所有已应用动作到连接前保存的值；没有已应用动作时返回 msg=null。
     *  恢复失败（如写入通道缺失）时保留现场，并标记 restore_failed 等待下次重试。 */
    private fun restore(ctx: Context): RestoreResult {
        val p = prefs(ctx)
        val names = p.getStringSet(KEY_SAVED_SET, emptySet())?.sorted() ?: emptyList()
        if (names.isEmpty()) return RestoreResult(null)
        val actions = names.mapNotNull { runCatching { DevAction.valueOf(it) }.getOrNull() }
        val writer = Writers.best(ctx)
        if (writer == null) {
            p.edit().putBoolean(KEY_RESTORE_FAILED, true).apply()
            return RestoreResult("无可用写入通道，无法恢复，保持现场待重试", failed = true)
        }
        var ok = true
        for (a in actions) ok = writeSaved(a, writer, p) && ok
        if (!ok) {
            p.edit().putBoolean(KEY_RESTORE_FAILED, true).apply()
            return RestoreResult("恢复部分设置失败，保持现场待重试", failed = true)
        }
        p.edit().remove(KEY_SAVED_SET)
            .putString(KEY_ACTIVE, Profile.NONE.name)
            .putBoolean(KEY_RESTORE_FAILED, false)
            .apply()
        return RestoreResult("已恢复：${actions.joinToString("、") { it.label }}")
    }

    private fun applyProfile(ctx: Context, profile: Profile, reason: String): String {
        val cfg = if (profile == Profile.WIRED) Config.wired(ctx) else Config.wifi(ctx)
        val actions = cfg.actions()
        val p = prefs(ctx)
        if (actions.isEmpty()) {
            p.edit().putString(KEY_ACTIVE, profile.name).apply()
            return "已连接电脑（${profile.label}）→ 未启用任何动作（$reason）"
        }
        val writer = Writers.best(ctx) ?: return "无可用写入通道，请打开应用完成授权（$reason）"
        val edit = p.edit()
        for (a in actions) when (a) {
            DevAction.KEEP_AWAKE -> edit.putInt(KEY_SAVED_STAY, writer.getGlobal(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0))
            DevAction.AUTO_BRIGHTNESS -> edit.putInt(KEY_SAVED_MODE, writer.getSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC))
            DevAction.MIN_BRIGHTNESS -> edit.putInt(KEY_SAVED_BRIGHT, writer.getSystem(Settings.System.SCREEN_BRIGHTNESS, 128))
            DevAction.NEVER_SLEEP -> edit.putInt(KEY_SAVED_TIMEOUT, writer.getSystem(Settings.System.SCREEN_OFF_TIMEOUT, -1))
        }
        edit.putStringSet(KEY_SAVED_SET, actions.map { it.name }.toSet())
            .putString(KEY_ACTIVE, profile.name)
            .apply()
        var ok = true
        for (a in actions) ok = applyAction(a, writer) && ok
        return "已连接电脑（${profile.label}）→ 应用：${actions.joinToString("、") { it.label }}" +
                (if (ok) "" else "（部分写入失败，请检查授权）") + "（$reason）"
    }

    private fun applyAction(a: DevAction, writer: SettingsWriter): Boolean = when (a) {
        DevAction.KEEP_AWAKE -> writer.putGlobal(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_ANY)
        DevAction.AUTO_BRIGHTNESS -> writer.putSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        DevAction.MIN_BRIGHTNESS -> writer.putSystem(Settings.System.SCREEN_BRIGHTNESS, MIN_BRIGHTNESS)
        DevAction.NEVER_SLEEP -> writer.putSystem(Settings.System.SCREEN_OFF_TIMEOUT, NEVER_SLEEP_TIMEOUT)
    }

    private fun writeSaved(a: DevAction, writer: SettingsWriter, p: SharedPreferences): Boolean = when (a) {
        DevAction.KEEP_AWAKE -> writer.putGlobal(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, p.getInt(KEY_SAVED_STAY, 0))
        DevAction.AUTO_BRIGHTNESS -> writer.putSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, p.getInt(KEY_SAVED_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC))
        DevAction.MIN_BRIGHTNESS -> writer.putSystem(Settings.System.SCREEN_BRIGHTNESS, p.getInt(KEY_SAVED_BRIGHT, 128))
        // 原值 -1 表示原本没有显式设置，恢复「未设置」状态（等效 settings delete）
        DevAction.NEVER_SLEEP -> {
            val saved = p.getInt(KEY_SAVED_TIMEOUT, -1)
            if (saved < 0) writer.deleteSystem(Settings.System.SCREEN_OFF_TIMEOUT)
            else writer.putSystem(Settings.System.SCREEN_OFF_TIMEOUT, saved)
        }
    }
}
