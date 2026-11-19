package com.halo.devawake

import android.annotation.SuppressLint
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
 * 屏幕保持点亮（配合 stay_on 防休眠，OLED 全黑几乎不耗电），用于连接电脑开发时防误触/防烧屏。
 * 触摸任意位置或按返回键退出；调试状态结束（拔出/只充电）后自动退出。
 */
class BlackScreenActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val devEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 应用自身的同步可能有几百毫秒延迟，稍等再判定
            handler.postDelayed({
                if (DevModeController.activeProfile(applicationContext) == Profile.NONE) finish()
            }, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = View(this)
        view.setBackgroundColor(Color.BLACK)
        setContentView(view)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }
        // 触摸任意位置退出
        @SuppressLint("ClickableViewAccessibility")
        view.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) finish()
            true
        }

        ContextCompat.registerReceiver(
            this, devEndReceiver,
            IntentFilter(Intent.ACTION_POWER_DISCONNECTED),
            ContextCompat.RECEIVER_EXPORTED,
        )
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
        super.onDestroy()
    }
}
