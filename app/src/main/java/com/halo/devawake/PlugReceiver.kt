package com.halo.devawake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 清单接收器：主要负责开机后拉起监听服务。
 * 插拔广播在部分 OEM 系统上不会投递给后台应用的清单接收器，所以日常监听由 MonitorService 负责；
 * 这里收到时也顺带处理一次，作为兜底。
 */
class PlugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        val app = context.applicationContext
        val tag = action.substringAfterLast('.')
        // 开机广播允许从后台启动前台服务；其他情况可能被系统拒绝，失败就只做本次处理
        runCatching { MonitorService.start(app) }

        val pending = goAsync()
        Tasks.run {
            try {
                if (action == Intent.ACTION_POWER_CONNECTED) {
                    // 开始充电后电脑才枚举 USB，最多等 6 秒，一旦识别为电脑立刻处理（goAsync 预算 10 秒）
                    val deadline = System.currentTimeMillis() + 6000
                    var power = DevModeController.power(app)
                    while (!power.isComputer && System.currentTimeMillis() < deadline) {
                        Thread.sleep(500)
                        power = DevModeController.power(app)
                    }
                    DevModeController.sync(app, tag)
                } else {
                    Thread.sleep(500)
                    DevModeController.sync(app, tag)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val HANDLED = setOf(
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BOOT_COMPLETED,
        )
    }
}
