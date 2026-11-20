package com.halo.devawake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全黑界面：调试模式生效时可从通知栏或应用内进入。
 * 屏幕保持点亮（配合「永不熄屏」动作），用于连接电脑开发时防误触/防烧屏。
 *
 * 交互：点击屏幕不会退出（防误触核心诉求），退出走系统手势（侧滑返回/Home）或返回键；
 * 系统手势由系统处理，不受本界面影响。进入时隐藏 MIUI 侧边应用栏，退出时恢复。
 * 调试状态结束（拔出 / 无线断开 / 场景开关关闭）后自动退出。
 */
class BlackScreenActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val devEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 应用自身的同步可能有几百毫秒延迟，稍等再判定
            handler.postDelayed({ finishIfDevEnded() }, 1500)
        }
    }

    /** 无线调试断开没有电源广播可听，用轮询兜底 */
    private val devEndPoll = object : Runnable {
        override fun run() {
            if (finishIfDevEnded()) return
            handler.postDelayed(this, 2000)
        }
    }

    private var enteredFromDev = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enteredFromDev = DevModeController.isActive(applicationContext)
        val view = View(this)
        view.setBackgroundColor(Color.BLACK)
        setContentView(view)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }

        ContextCompat.registerReceiver(
            this, devEndReceiver,
            IntentFilter(Intent.ACTION_POWER_DISCONNECTED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        handler.postDelayed(devEndPoll, 2000)
        // 阻断 MIUI 侧边应用栏；退出时在 onDestroy 恢复。顺带迁移修复历史遗留
        Tasks.run {
            SidebarController.migrateLegacy(applicationContext)
            SidebarController.hide(applicationContext)
        }
    }

    private fun finishIfDevEnded(): Boolean {
        if (enteredFromDev && DevModeController.activeProfile(applicationContext) == Profile.NONE) {
            finish()
            return true
        }
        return false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(devEndReceiver) }
        Tasks.run {
            SidebarController.restore(applicationContext)
            SidebarController.migrateLegacy(applicationContext)
        }
        super.onDestroy()
    }
}
