package com.halo.devawake

import kotlin.system.exitProcess

/** 由 Shizuku 以 shell 身份拉起的进程，用来执行 settings 命令（shell 对全部 settings 表有写权限） */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }
}
