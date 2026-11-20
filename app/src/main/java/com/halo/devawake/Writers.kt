package com.halo.devawake

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 对 global/system 两张 settings 表的读写抽象，屏蔽「权限直写」和「Shizuku shell」两种通道的差异 */
interface SettingsWriter {
    val label: String
    fun getGlobal(key: String, def: Int): Int
    fun putGlobal(key: String, value: Int): Boolean
    fun getSystem(key: String, def: Int): Int
    fun putSystem(key: String, value: Int): Boolean

    /** 删除 system 表的键（恢复「未设置」状态）；不支持时返回 false */
    fun deleteSystem(key: String): Boolean
}

object Writers {
    /**
     * global 表（保持亮屏）与 system 表（亮度）各自挑可用的通道：
     * 优先权限直写，缺哪张表的权限就用 Shizuku 补上；两条路都不通才返回 null。
     */
    fun best(ctx: Context): SettingsWriter? {
        val secure = DirectWriter.hasSecure(ctx)
        val system = DirectWriter.hasSystem(ctx)
        if (secure && system) return DirectWriter
        val shizuku = ShizukuWriter.available()
        val globalWriter = when {
            secure -> DirectWriter
            shizuku -> ShizukuWriter
            else -> return null
        }
        val systemWriter = when {
            system -> DirectWriter
            shizuku -> ShizukuWriter
            else -> return null
        }
        return if (globalWriter === systemWriter) globalWriter else CompositeWriter(globalWriter, systemWriter)
    }
}

private class CompositeWriter(
    private val global: SettingsWriter,
    private val system: SettingsWriter,
) : SettingsWriter {
    override val label = "${global.label}(global) + ${system.label}(system)"
    override fun getGlobal(key: String, def: Int) = global.getGlobal(key, def)
    override fun putGlobal(key: String, value: Int) = global.putGlobal(key, value)
    override fun getSystem(key: String, def: Int) = system.getSystem(key, def)
    override fun putSystem(key: String, value: Int) = system.putSystem(key, value)
    override fun deleteSystem(key: String) = system.deleteSystem(key)
}

/** 持有 WRITE_SECURE_SETTINGS + 「修改系统设置」时的直写通道，无需任何外部进程 */
object DirectWriter : SettingsWriter {
    override val label = "系统权限直写"

    private val resolver get() = App.instance.contentResolver

    fun hasSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasSystem(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    /** 返回缺失的权限描述；null 表示全部就绪 */
    fun missing(ctx: Context): String? {
        val secure = hasSecure(ctx)
        val system = hasSystem(ctx)
        return when {
            secure && system -> null
            !secure && !system -> "WRITE_SECURE_SETTINGS 与「修改系统设置」"
            !secure -> "WRITE_SECURE_SETTINGS"
            else -> "「修改系统设置」"
        }
    }

    override fun getGlobal(key: String, def: Int) = Settings.Global.getInt(resolver, key, def)

    override fun putGlobal(key: String, value: Int) =
        runCatching { Settings.Global.putInt(resolver, key, value) }.getOrDefault(false)

    override fun getSystem(key: String, def: Int) = Settings.System.getInt(resolver, key, def)

    override fun putSystem(key: String, value: Int) =
        runCatching { Settings.System.putInt(resolver, key, value) }.getOrDefault(false)

    /** 传 null 值即删除该行（SettingsProvider 的语义），等效 `settings delete` */
    override fun deleteSystem(key: String) =
        runCatching { Settings.System.putString(resolver, key, null); true }.getOrDefault(false)
}

/** Shizuku 兜底通道：以 shell 身份执行 `settings put/get`，适合没有做 adb 授权的场景 */
object ShizukuWriter : SettingsWriter {
    override val label = "Shizuku"
    private const val TAG = "DevAwake"

    @Volatile
    private var service: IShellService? = null

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun available(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 服务端在应用进程启动后才推送 binder，冷启动时稍等片刻 */
    fun waitForBinder(timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (running()) return true
            Thread.sleep(200)
        }
        return running()
    }

    @Synchronized
    private fun service(): IShellService? {
        service?.let { if (it.asBinder().pingBinder()) return it }
        service = null
        if (!available()) return null

        val latch = CountDownLatch(1)
        var connected: IShellService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    connected = IShellService.Stub.asInterface(binder)
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(App.instance, ShellService::class.java))
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        runCatching { Shizuku.bindUserService(args, conn) }
            .onFailure { Log.w(TAG, "bindUserService failed", it) }
        latch.await(6, TimeUnit.SECONDS)
        service = connected
        return connected
    }

    private fun exec(command: String): String? = runCatching {
        service()?.exec(command)?.trim()
    }.onFailure { Log.w(TAG, "shizuku exec failed: $command", it) }.getOrNull()

    /** 供检测逻辑执行任意 shell 命令；失败返回 null */
    fun rawExec(command: String): String? = exec(command)

    private fun get(table: String, key: String, def: Int): Int =
        exec("settings get $table $key")?.toIntOrNull() ?: def

    private fun put(table: String, key: String, value: Int): Boolean {
        val out = exec("settings put $table $key $value") ?: return false
        return !out.contains("Exception", true) && !out.contains("Error", true)
    }

    override fun getGlobal(key: String, def: Int) = get("global", key, def)
    override fun putGlobal(key: String, value: Int) = put("global", key, value)
    override fun getSystem(key: String, def: Int) = get("system", key, def)
    override fun putSystem(key: String, value: Int) = put("system", key, value)

    override fun deleteSystem(key: String): Boolean {
        val out = exec("settings delete system $key") ?: return false
        return !out.contains("Exception", true) && !out.contains("Error", true)
    }
}
