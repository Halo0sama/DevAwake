package com.halo.devawake

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPlugged: TextView
    private lateinit var tvSettings: TextView
    private lateinit var tvWriter: TextView
    private lateinit var tvLast: TextView
    private lateinit var btnWriteSettings: Button
    private lateinit var btnShizuku: Button

    /** refresh() 里程序化设置开关状态时抑制监听器 */
    private var suppressSwitch = false

    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvPlugged = findViewById(R.id.tvPlugged)
        tvSettings = findViewById(R.id.tvSettings)
        tvWriter = findViewById(R.id.tvWriter)
        tvLast = findViewById(R.id.tvLast)
        btnWriteSettings = findViewById(R.id.btnWriteSettings)
        btnShizuku = findViewById(R.id.btnShizuku)
        findViewById<TextView>(R.id.tvHint).text = getString(R.string.hint_adb, BuildConfig.APPLICATION_ID)

        findViewById<Button>(R.id.btnApply).setOnClickListener { force(Profile.WIRED, "手动应用") }
        findViewById<Button>(R.id.btnRestore).setOnClickListener { force(Profile.NONE, "手动恢复") }
        findViewById<Button>(R.id.btnBlackScreen).setOnClickListener {
            startActivity(Intent(this, BlackScreenActivity::class.java))
        }
        btnWriteSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        btnShizuku.setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        bindSwitch(R.id.swWiredEnabled, wired = true, field = Field.ENABLED)
        bindSwitch(R.id.swWiredKeepAwake, wired = true, field = Field.KEEP_AWAKE)
        bindSwitch(R.id.swWiredAutoBright, wired = true, field = Field.AUTO_BRIGHTNESS)
        bindSwitch(R.id.swWiredMinBright, wired = true, field = Field.MIN_BRIGHTNESS)
        bindSwitch(R.id.swWifiEnabled, wired = false, field = Field.ENABLED)
        bindSwitch(R.id.swWifiKeepAwake, wired = false, field = Field.KEEP_AWAKE)
        bindSwitch(R.id.swWifiAutoBright, wired = false, field = Field.AUTO_BRIGHTNESS)
        bindSwitch(R.id.swWifiMinBright, wired = false, field = Field.MIN_BRIGHTNESS)
        findViewById<MaterialSwitch>(R.id.swWifiFallback).setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            Tasks.run {
                if (Config.updateWifiFallback(applicationContext, checked)) {
                    DevModeController.onConfigChanged(applicationContext)
                }
                refresh()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
        MonitorService.start(this)
        WirelessMonitor.start()
    }

    private fun bindSwitch(id: Int, wired: Boolean, field: Field) {
        findViewById<MaterialSwitch>(id).setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            Tasks.run {
                if (Config.update(applicationContext, wired, field, checked)) {
                    DevModeController.onConfigChanged(applicationContext)
                }
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 打开界面时按当前连接状态同步一次，覆盖「插着线安装/重启」的情况
        Tasks.run {
            DevModeController.sync(applicationContext, "打开应用")
            refresh()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeRequestPermissionResultListener(permListener)
        }
    }

    private fun force(profile: Profile, reason: String) {
        Tasks.run {
            val msg = DevModeController.forceProfile(applicationContext, profile, reason)
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            refresh()
        }
    }

    private fun requestShizuku() {
        runCatching {
            when {
                !Shizuku.pingBinder() -> toast("Shizuku 未运行，请先在 Shizuku 应用中启动服务")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    toast("Shizuku 已授权")
                    refresh()
                }
                else -> Shizuku.requestPermission(REQ_SHIZUKU)
            }
        }.onFailure { toast("Shizuku 调用失败：${it.message}") }
    }

    private fun refresh() {
        Tasks.run {
            val ctx = applicationContext
            val power = DevModeController.power(ctx)
            val profile = DevModeController.activeProfile(ctx)
            val stay = Settings.Global.getInt(contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, -1)
            val mode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            val bright = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            val missing = DirectWriter.missing(ctx)
            val shizukuRunning = ShizukuWriter.running()
            val shizukuOk = ShizukuWriter.available()
            val writer = Writers.best(ctx)
            val last = DevModeController.lastMessage(ctx)
            val wiredCfg = Config.wired(ctx)
            val wifiCfg = Config.wifi(ctx)
            val fallback = Config.wifiFallback(ctx)

            runOnUiThread {
                suppressSwitch = true
                tvStatus.text = when (profile) {
                    Profile.WIRED -> "● 调试模式生效中（有线）\n${wiredCfg.actions().joinToString(" · ") { it.label }.ifEmpty { "未启用动作" }}"
                    Profile.WIFI -> "● 调试模式生效中（无线调试）\n${wifiCfg.actions().joinToString(" · ") { it.label }.ifEmpty { "未启用动作" }}"
                    Profile.NONE -> "○ 待机中\n等待 USB 连接 / 无线调试接入"
                }
                tvPlugged.text = "当前供电：${power.describe()}\n" +
                        "电池上报：${DevModeController.pluggedText(power.plugged)}；USB 数据连接：${if (power.usbData) "是" else "否"}"
                tvSettings.text = "stay_on_while_plugged_in = $stay\n" +
                        "screen_brightness_mode   = ${when (mode) { 1 -> "1（自动）"; 0 -> "0（手动）"; else -> "$mode" }}\n" +
                        "screen_brightness        = $bright"
                tvWriter.text = "监听服务：${if (MonitorService.running) "运行中（前台服务）" else "未运行"}\n" +
                        "写入通道：${writer?.label ?: "无（缺少授权）"}\n" +
                        "· 系统权限：${if (missing == null) "已具备" else "缺少 $missing"}\n" +
                        "· Shizuku：${when { shizukuOk -> "已授权"; shizukuRunning -> "运行中，未授权"; else -> "未运行" }}\n" +
                        WirelessMonitor.statusText(ctx)
                tvLast.text = "上次动作：$last"

                findViewById<MaterialSwitch>(R.id.swWiredEnabled).isChecked = wiredCfg.enabled
                findViewById<MaterialSwitch>(R.id.swWiredKeepAwake).isChecked = wiredCfg.keepAwake
                findViewById<MaterialSwitch>(R.id.swWiredAutoBright).isChecked = wiredCfg.autoBrightness
                findViewById<MaterialSwitch>(R.id.swWiredMinBright).isChecked = wiredCfg.minBrightness
                findViewById<MaterialSwitch>(R.id.swWifiEnabled).isChecked = wifiCfg.enabled
                findViewById<MaterialSwitch>(R.id.swWifiKeepAwake).isChecked = wifiCfg.keepAwake
                findViewById<MaterialSwitch>(R.id.swWifiAutoBright).isChecked = wifiCfg.autoBrightness
                findViewById<MaterialSwitch>(R.id.swWifiMinBright).isChecked = wifiCfg.minBrightness
                findViewById<MaterialSwitch>(R.id.swWifiFallback).isChecked = fallback
                suppressSwitch = false

                btnWriteSettings.visibility = if (Settings.System.canWrite(this)) View.GONE else View.VISIBLE
                btnShizuku.visibility = if (shizukuRunning && !shizukuOk) View.VISIBLE else View.GONE
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_SHIZUKU = 1
        private const val REQ_NOTIFICATION = 2
    }
}
