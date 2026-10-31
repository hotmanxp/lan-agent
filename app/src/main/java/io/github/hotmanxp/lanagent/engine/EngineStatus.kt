// EngineStatus.kt — 引擎进程 ↔ 主进程之间的状态传递。
//
// 引擎跑在独立的 :engine 进程,主进程看不见它的内存,也没法直接问它
// 「起了没」 —— 只能靠一个文件来回话。
//
// 格式刻意用极简的 key=value 而不是 JSON:这个文件两个进程都在写,
// 格式越简单越不容易出「写了一半被读到」的岔子(读的时候按行拆,
// 认不出的行直接丢,不报错)。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 引擎进程的状态快照。
 *
 * @param state starting / running / dead
 * @param port   引擎监听的端口(主进程拿它拼 baseUrl)
 * @param pid    :engine 进程的 pid(主进程靠它判断进程还在不在)
 */
data class EngineStatus(
    val state: String,
    val port: Int,
    val pid: Int,
    val updatedAt: Long,
) {
    /** 引擎是不是活的:状态是 running,而且那个 pid 还存在。 */
    fun isAlive(): Boolean = state == STATE_RUNNING && pidAlive(pid)

    companion object {
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_DEAD = "dead"

        /** 写状态文件。 */
        fun write(context: Context, state: String, port: Int, pid: Int = Process.myPid()) {
            val text = buildString {
                appendLine("state=$state")
                appendLine("port=$port")
                appendLine("pid=$pid")
                appendLine("updatedAt=${System.currentTimeMillis()}")
            }
            runCatching {
                val f = EnginePaths.runtimeFile(context)
                f.parentFile?.mkdirs()
                f.writeText(text)
            }
        }

        /** 读状态文件;读不到(还没写过 / 写坏了)返回 null。 */
        fun read(context: Context): EngineStatus? {
            val f = EnginePaths.runtimeFile(context)
            if (!f.isFile) return null
            return runCatching {
                var state = ""
                var port = EnginePaths.DEFAULT_PORT
                var pid = 0
                var updatedAt = 0L
                f.readLines().forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val k = line.substring(0, idx)
                    val v = line.substring(idx + 1)
                    when (k) {
                        "state" -> state = v
                        "port" -> port = v.toIntOrNull() ?: port
                        "pid" -> pid = v.toIntOrNull() ?: pid
                        "updatedAt" -> updatedAt = v.toLongOrNull() ?: updatedAt
                    }
                }
                if (state.isEmpty()) null else EngineStatus(state, port, pid, updatedAt)
            }.getOrNull()
        }

        /**
         * pid 还活着吗。
         *
         * 引擎进程被系统杀掉时不会有人写「dead」状态(它已经没了),
         * 所以主进程只能靠 /proc/<pid> 判断 —— 这是唯一可靠的死信号。
         */
        fun pidAlive(pid: Int): Boolean = pid > 0 && File("/proc/$pid").exists()
    }
}
