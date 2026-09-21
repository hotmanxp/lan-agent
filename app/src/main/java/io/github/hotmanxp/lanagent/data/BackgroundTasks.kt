// data/BackgroundTasks.kt — 后台任务的 wire 模型(后台 agent 子代理 + 后台 bash)。
//
// 两条来源(与 opencc-web 端 `useBackgroundTasks` / `useBashBackgroundTasks` 同源):
//   1. `GET /api/agent/sessions/:id/state` 的 `agentTasks` / `bashTasks` ——
//      冷启动快照。服务端已按 sid 过滤好(agent 按 `parentSessionId`,
//      bash 按 `sessionId`),见 `routes/sessionState.ts:90-103`。
//   2. SSE `agent_task.changed` / `bash_task.changed` —— source of truth。
//      **agent 侧服务端在每次新 SSE 连接建立时还会把所有当前任务合成一条
//      重推一遍**(`routes/event.ts:120-135`),所以断线重连不会漏掉 running
//      任务 —— 这条机制正是「刷新后后台任务从状态栏消失」那个 bug 的修复,
//      我们白捡。
//
// bash 侧没有对应的合成推送,所以**冷启动那一次必须靠 state 快照**
// (见 `AgentSessionStore.hydrateState`),否则打开一个已有后台 bash 的会话
// 会看不到它。
//
// 字段形态全部实测倒推(服务端没有 schema),坑点写在各字段上。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.Serializable

/**
 * 「最近结束」窗口。终态任务在这个窗口内还留在底部任务栏上(让用户看得到
 * 「刚刚那条跑完了 / 失败了」),超时就不再占位。
 *
 * 与 web 端 `hooks/useBackgroundTasks.ts` 的 `RECENT_TTL_MS` 同档。
 * running / queued 的任务**不受它影响**,永不清。
 */
const val BG_RECENT_TTL_MS = 60_000L

// ===== 后台 agent 子代理 =====

/**
 * 后台子代理任务(opencc-web `lib/taskApi.ts` 的 `BackgroundTask`)。
 *
 * `status` 取值:`queued` / `running` / `completed` / `failed` / `cancelled`。
 *
 * **只声明要展示的字段** —— `resultText` / `eventCount` 这类可能很大的字段
 * 故意不接(SSE 一帧里带着几十 KB 结果文本是常态,收下来只会白占内存,
 * 手机端不会渲染它)。
 */
@Serializable
data class BgAgentTask(
    /** 空 id 的条目在入口就被丢掉(见 upsertBgAgentTask)—— 没 id 无法去重。 */
    val id: String = "",
    val status: String = "queued",
    /** 派发参数。服务端有可能整体缺省,所以可空。 */
    val input: BgAgentInput? = null,
    @Serializable(with = EpochMsSerializer::class)
    val createdAt: Long = 0L,
    @Serializable(with = EpochMsNullableSerializer::class)
    val startedAt: Long? = null,
    @Serializable(with = EpochMsNullableSerializer::class)
    val finishedAt: Long? = null,
    val error: BgTaskError? = null,
    /**
     * 派发它的主 session。服务端只在 `AgentTool.metadata.parentSessionId`
     * 写入了值时才带 —— CLI 派发 / 调度器自己派的任务是 undefined(这类
     * 任务在 per-sid 的 SSE 里根本收不到,由 state 快照兜底)。
     */
    val parentSessionId: String? = null,
    /**
     * 派发该子代理的 agent 名。原生 Agent 工具路径下是 AgentDefinition 的
     * `agentType`(如 `code-reviewer` / `Explore`);CLI 路径下退化成
     * provider 种类(`opencc`),此时真名在 [description] 里。
     */
    val agentType: String? = null,
    /** `AgentTool.description ?? prompt` 摘要。 */
    val description: String? = null,
)

/** 子代理的派发参数。只取展示用得上的两个,其余(`metadata` 等)忽略。 */
@Serializable
data class BgAgentInput(
    val prompt: String = "",
    val cwd: String? = null,
    val agent: String? = null,
    val model: String? = null,
)

@Serializable
data class BgTaskError(
    val message: String = "",
    val category: String? = null,
)

// ===== 后台 bash 任务 =====

/**
 * 后台 bash 任务(opencc-web `lib/taskApi.ts` 的 `BashTaskInfo`)。
 *
 * `status` 取值:`running` / `completed` / `failed` / `killed`。
 *
 * `stdout` / `stderr` 是**累计**输出(可能到 MB 级),故意不接 —— 列表行
 * 只显示命令与状态,收了也不渲染。
 */
@Serializable
data class BgBashTask(
    val taskId: String = "",
    val sessionId: String = "",
    val command: String = "",
    val description: String = "",
    @Serializable(with = EpochMsSerializer::class)
    val startedAt: Long = 0L,
    @Serializable(with = EpochMsNullableSerializer::class)
    val finishedAt: Long? = null,
    val status: String = "running",
)

// ===== 状态语义 =====

/** `running` / `queued` 视为「还在跑」——这两态永远显示,且不清。 */
fun bgRunning(status: String): Boolean = status == "running" || status == "queued"

/** 终态。与 [bgRunning] 互补(未知字符串按终态处理,至少不会永远挂着)。 */
fun bgTerminal(status: String): Boolean = !bgRunning(status)

/**
 * 状态 → 中文。**两套枚举合一**:agent 是
 * `queued|running|completed|failed|cancelled`,bash 是
 * `running|completed|failed|killed`,只有 `killed` / `cancelled` 的文案不同。
 * 认不出的状态原样返回(新状态不该让整行空白)。
 */
fun bgStatusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "queued" -> "排队中"
    "completed" -> "完成"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    "killed" -> "已终止"
    else -> status
}

/**
 * 终态任务的耗时文案(`12s` / `3m05s`)。
 *
 * **跑中一律返回 null** —— 显示「已跑 12s」需要一个每帧刷新的时钟,而任务栏
 * 里最不缺的就是动效;终态数字反而是静态的、有用的信息,顺手给上。
 */
fun bgDurationLabel(startedAt: Long?, finishedAt: Long?, status: String): String? {
    if (bgRunning(status)) return null
    val from = startedAt ?: return null
    val to = finishedAt ?: return null
    if (from <= 0 || to <= from) return null
    val ms = to - from
    return if (ms < 60_000) "${ms / 1000}s" else "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

private val BG_WHITESPACE = Regex("\\s+")

/** 压成一行 —— 任务栏的行只放得下一行,prompt 里的换行会把卡片撑高。 */
fun String.bgOneLine(max: Int = 60): String {
    val clean = replace(BG_WHITESPACE, " ").trim()
    return if (clean.length <= max) clean else clean.take(max) + "…"
}

/** 列表主文案:优先 agent 名,再退到 description / prompt,最后兜底 "Agent"。 */
val BgAgentTask.displayName: String
    get() = agentType?.takeIf { it.isNotBlank() } ?: "Agent"

/** 列表副文案:描述 / prompt 摘要(压成一行)。 */
val BgAgentTask.displayDetail: String
    get() = (description ?: input?.prompt).orEmpty().bgOneLine()

/** bash 任务的展示文案:描述优先(人写的),没写就用命令。 */
val BgBashTask.displayDetail: String
    get() = (description.takeIf { it.isNotBlank() } ?: command).bgOneLine()
