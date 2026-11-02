// ToolCallWatchdog.kt — 工具调用「客户端兜底超时」。
//
// ## 它解决什么
//
// 引擎侧(core)的 ripgrep 超时**依赖 core 自己的看门狗**:
// `utils/ripgrep.ts` 里 execFile 分支在 `timeout + 5s` 时直接 settle(ETIMEDOUT),
// 不等子进程的 'close' 事件。没这个看门狗的话,Node 的 execFile 超时只是
// `setTimeout(kill)`:kill 之后要等子进程退出才回调 —— 而 rg 卡在不可中断
// I/O(D 状态)时连 SIGKILL 都杀不掉,'close' 永远不来 → callback 永不触发 →
// promise 永不 settle → **Grep/Glob 工具调用在 UI 上永远转圈,既没有结果
// 也没有报错**。
//
// 这个看门狗住在 **core bundle** 里 —— 而 core bundle 在**用户下载的引擎包**
// (`files/engine/<指纹>/node_modules/@zn-ai/zn-agent-core/opencc-core.mjs`)里。
// 用户手机上的引擎包可能还是看门狗修好之前(2026-11-01 20:28 的 3e534f03)
// 下的旧版 —— 于是手机上「超过 30 秒也没有超时错误」,而在装了新引擎包的
// 机器上复现不了。
//
// **本模块是 APK 侧的兜底,不依赖引擎包版本**:只要工具调用在 App 侧观察到
// 「开了很久还没结果」,就把那张卡标成超时错误,让 UI 停下来并给出可读的
// 说明,而不是永远转圈。
//
// ## 为什么阈值比 core 的 30s 更长
//
// core 的 rg 超时是 30s(`CLAUDE_CODE_GLOB_TIMEOUT_SECONDS`),加上看门狗的
// 5s 余量,引擎侧最多 35s 就会 settle。App 侧阈值取 **60s** ——
// 必须大于引擎侧最坏情况,否则会在 core 正常报错之前抢先把它标成超时,
// 把真正的错误信息(RipgrepTimeoutError / 输出过大等)盖掉。
//
// Bash 这类工具**不设阈值**:core 允许 Bash 跑 10 分钟
// (`BASH_MAX_TIMEOUT_MS`,见 core 的 `utils/timeouts.ts`),Agent 子代理、
// 长跑脚本都合法地超过 60s。给它们套 60s 阈值会把正常的活判成超时。
// 只有 Grep/Glob 这类「引擎侧本来就有明确超时」的工具才纳入。
//
// ## 计时从哪来
//
// 用**收到 tool_call 事件的那一刻**当起点,不是 SSE 的 ts —— 服务端 ts 与
// 设备时钟可能有偏差,而「App 看到它多久了」才是用户体感的等待时长。
// 冷启动 hydrate 的历史工具卡不算(它们没有「正在跑」这回事)。

package io.github.hotmanxp.lanagent.ui

/**
 * 工具调用的超时策略(纯 Kotlin,可单测)。
 *
 * 只对**引擎侧本来就有明确超时**的工具生效 —— 见文件头的说明。
 */
object ToolCallWatchdog {

    /**
     * Grep / Glob 的客户端兜底超时:60s。
     *
     * 引擎侧最坏 35s(30s rg 超时 + 5s 看门狗),这里留一倍余量,
     * 保证正常情况下是引擎先报错,App 只在引擎彻底挂死时才接手。
     */
    const val SEARCH_TIMEOUT_MS: Long = 60_000

    /** 纳入看门狗的工具名。 */
    private val WATCHED_TOOLS = setOf("Grep", "Glob")

    /**
     * 这个工具要不要纳入看门狗。
     *
     * @param toolName 工具名(core 的 `GREP_TOOL_NAME` / `GLOB_TOOL_NAME`)。
     */
    fun isWatched(toolName: String): Boolean = toolName in WATCHED_TOOLS

    /**
     * 这个工具的兜底超时时长(毫秒);null = 不纳入看门狗。
     */
    fun timeoutFor(toolName: String): Long? =
        if (isWatched(toolName)) SEARCH_TIMEOUT_MS else null

    /**
     * 判断一次工具调用是否已经超时。
     *
     * @param toolName 工具名。
     * @param startedAt App 侧收到 tool_call 的时刻(epoch ms)。
     * @param now 当前时刻(epoch ms)。
     * @return true = 已超时,应把那张卡标成超时错误。
     */
    fun isTimedOut(toolName: String, startedAt: Long, now: Long): Boolean {
        val limit = timeoutFor(toolName) ?: return false
        // now < startedAt:设备时钟被往回拨了 —— 不判超时(否则立刻误杀)。
        if (now < startedAt) return false
        return now - startedAt >= limit
    }
}
