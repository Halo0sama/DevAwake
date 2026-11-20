package com.halo.devawake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 常驻前台服务。小米/HyperOS 会把电源广播标记为「不允许唤醒后台应用」，清单里注册的接收器会被
 * 系统直接跳过；只有活着的进程里动态注册的接收器才能稳定收到插拔事件，所以用一条静音通知把进程保住。
 * 同时监听 USB_STATE：电脑枚举完成的瞬间就能处理，不必轮询等待。
 * 无线调试连接由 WirelessMonitor 轮询检测。
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastAction = "服务启动"

    private val syncRunnable = Runnable {
        Tasks.run {
            DevModeController.sync(applicationContext, lastAction)
            updateNotification()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lastAction = intent.action?.substringAfterLast('.') ?: "?"
            // 插入时 POWER_CONNECTED 与 USB_STATE(connected/configured) 会接连到达，合并成一次处理
            handler.removeCallbacks(syncRunnable)
            handler.postDelayed(syncRunnable, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        createChannel()
        // 清理旧版本可能遗留的第二条通知
        getSystemService(NotificationManager::class.java).cancel(NOTIF_LEGACY_DEV)
        ServiceCompat.startForeground(this, NOTIF_ID, initialNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        WirelessMonitor.start()
        // Shizuku 就绪后修复历史遗留（黑屏期间进程被杀导致侧边栏原值未恢复）
        Tasks.run { SidebarController.migrateLegacy(applicationContext) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ACTION_USB_STATE)
        }
        // USB_STATE 是 sticky 广播，注册时会立刻收到一次当前状态，顺带完成首次同步
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        running = false
        handler.removeCallbacks(syncRunnable)
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 常驻通知只有一条，随场景状态变化：
     * 调试模式生效中（有线 / 无线调试）→ 点击进入全黑界面；
     * 待机 → 点击进应用主界面。
     */
    private fun updateNotification() {
        val ctx = applicationContext
        val profile = DevModeController.activeProfile(ctx)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (profile == Profile.NONE) {
            builder.setContentTitle(getString(R.string.notif_idle_title))
                .setContentText("待机：${DevModeController.power(ctx).describe()}")
                .setContentIntent(PendingIntent.getActivity(
                    this, REQ_OPEN_APP, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ))
        } else {
            val label = if (profile == Profile.WIRED) "有线" else "无线调试"
            val actions = actionsText(if (profile == Profile.WIRED) Config.wired(ctx) else Config.wifi(ctx))
            val text = getString(R.string.notif_dev_text, actions)
            builder.setContentTitle(getString(R.string.notif_dev_title, label))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(
                    this, REQ_BLACK, Intent(this, BlackScreenActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, builder.build())
    }

    private fun initialNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(getString(R.string.notif_idle_title))
            .setContentText("正在监听 USB / 无线调试连接…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun actionsText(cfg: ProfileConfig): String =
        cfg.actions().joinToString(" · ") { it.label }.ifEmpty { "未启用动作" }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "USB 监听服务", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = "保持后台监听 USB / 无线调试连接；可在系统设置里把这条通知设为最小化"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val NOTIF_LEGACY_DEV = 2
        private const val REQ_OPEN_APP = 0
        private const val REQ_BLACK = 1
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

        @Volatile
        private var instance: MonitorService? = null

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        /** 场景状态可能由无线检测线程或清单接收器直接同步，通知需要随之刷新 */
        fun refreshNotifications() {
            instance?.let { svc -> svc.handler.post { svc.updateNotification() } }
        }
    }
}
