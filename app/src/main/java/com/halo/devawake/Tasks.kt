package com.halo.devawake

import android.util.Log
import java.util.concurrent.Executors

/** 所有设置读写都放到这个单线程后台执行，避免阻塞主线程（Shizuku 首次绑定较慢） */
object Tasks {
    private const val TAG = "DevAwake"
    private val exec = Executors.newSingleThreadExecutor()

    fun run(block: () -> Unit) {
        exec.execute {
            runCatching(block).onFailure { Log.e(TAG, "task failed", it) }
        }
    }
}
