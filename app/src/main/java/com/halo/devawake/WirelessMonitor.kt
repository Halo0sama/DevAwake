package com.halo.devawake

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 无线调试（adb over Wi-Fi）连接检测。
 *
 * 精确判定（需 Shizuku，已授权时使用）：netstat 里存在「本端端口是通配地址上的监听端口、
 * 远端非回环地址」的 ESTABLISHED 连接，即有电脑主动连入手机的调试端口。手机自身的出站
 * 连接本端端口是临时端口，不会命中监听端口，因此不会误判。
 *
 * 轮询 2.5 秒；连续 2 次判定为连接、连续 3 次判定为断开才切换，避免瞬时抖动；
 * 无线调试开关被关闭时立即判定断开。
 *
 * 无 Shizuku 时的兜底（Config.wifiFallback，默认关闭）：按「无线调试」开关状态判定。
 */
object WirelessMonitor {
    private const val TAG = "DevAwake"
    private const val INTERVAL_MS = 2500L

    @Volatile
    var connected = false
        private set

    private var started = false
    private var pending = 0

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread {
            while (true) {
                runCatching {
                    val flipped = tick()
                    if (flipped != null) {
                        DevModeController.sync(
                            App.instance,
                            if (flipped) "无线调试已连接" else "无线调试已断开",
                        )
                    }
                }.onFailure { Log.w(TAG, "wireless tick failed", it) }
                Thread.sleep(INTERVAL_MS)
            }
        }.apply {
            name = "wireless-monitor"
            isDaemon = true
        }.start()
    }

    /** 返回去抖后的状态变化；null 表示无变化或本次无法判定 */
    @Synchronized
    private fun tick(): Boolean? {
        val ctx = App.instance
        val raw = rawState(ctx) ?: return null
        if (raw == connected) {
            pending = 0
            return null
        }
        pending++
        val need = when {
            !toggleOn(ctx) -> 1 // 开关被关掉：明确的用户动作，立即生效
            raw -> 2            // 连接：连续两次确认
            else -> 3           // 断开：多等一拍，容忍瞬时抖动
        }
        if (pending < need) return null
        connected = raw
        pending = 0
        Log.i(TAG, "wireless ${if (raw) "connected" else "disconnected"}")
        return raw
    }

    /** null = 无法判定（如 Shizuku 执行失败），保持原状态 */
    private fun rawState(ctx: Context): Boolean? {
        if (!toggleOn(ctx)) return false
        return when {
            ShizukuWriter.available() -> hasInboundAdbSession()
            Config.wifiFallback(ctx) -> true
            else -> false
        }
    }

    private fun toggleOn(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 }
            .getOrDefault(false)

    private class Row(val local: String, val remote: String, val state: String)

    private fun portOf(addr: String): Int? = addr.substringAfterLast(':').toIntOrNull()

    private fun ipOf(addr: String): String = addr.substringBeforeLast(':').trim('[', ']')

    private fun isWildcard(addr: String): Boolean =
        ipOf(addr) == "*" || ipOf(addr) == "0.0.0.0" || ipOf(addr) == "::"

    private fun String.isLoopback(): Boolean =
        this == "127.0.0.1" || this.startsWith("127.") || this == "::1" || this.startsWith("::ffff:127.")

    /**
     * netstat 输出解析：先收集通配监听端口，再看是否有 ESTABLISHED 连接的
     * 本端端口命中监听端口且远端非回环。返回 null 表示执行失败。
     */
    private fun hasInboundAdbSession(): Boolean? {
        val out = ShizukuWriter.rawExec("netstat -tan 2>/dev/null") ?: return null
        val rows = mutableListOf<Row>()
        for (line in out.lineSequence()) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 6 || (cols[0] != "tcp" && cols[0] != "tcp6")) continue
            if (portOf(cols[3]) == null) continue
            rows.add(Row(cols[3], cols[4], cols[5]))
        }
        val listen = rows.asSequence()
            .filter { it.state == "LISTEN" && isWildcard(it.local) }
            .mapNotNull { portOf(it.local) }
            .toSet()
        return rows.any {
            it.state == "ESTABLISHED" &&
                    !ipOf(it.remote).isLoopback() &&
                    listen.contains(portOf(it.local))
        }
    }

    fun statusText(ctx: Context): String {
        val toggle = toggleOn(ctx)
        val mode = when {
            ShizukuWriter.available() -> "Shizuku 精确检测"
            Config.wifiFallback(ctx) -> "按开关判定（兜底）"
            else -> "需要 Shizuku"
        }
        return "无线调试：开关${if (toggle) "开" else "关"} · $mode · ${if (connected) "已连接电脑" else "未连接"}"
    }
}
