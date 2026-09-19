// data/ActiveTasks.kt — 跨实例聚合「进行中的 Agent 任务」
//
// 任务栏顶部那一段「进行中」的数据源。lan-agent 里的 Agent 会话天然按实例
// 分家(每个 zai 进程一份 /api/agent/sessions),而用户关心的是「我这会儿
// 到底有几个活儿在跑」—— 所以这里做一次扇出聚合:
//
//   for each 卡片 → baseUrl 去重 → listSessions()
//     → 留最近 [windowMs] 内有更新的会话,按 updatedAt 倒序取前 [perInstance] 条
//     → 对最活跃的 [probeTop] 条再拉一次 /state,数出「未完成任务数」
//
// **只探测前几条**,不是偷懒:N 个实例 × M 个会话全量拉 state 是 O(N·M) 请求,
// 10 秒一轮会把手机和 Mac 都打满;而「跑着的任务」按定义必然在最近更新过,
// 取头部几条已经够覆盖。
//
// 失败静默降级:某个实例不可达 → 跳过它,不让一条超时把整段列表清空。
package io.github.hotmanxp.lanagent.data

import io.github.hotmanxp.lanagent.model.Card
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 一条「进行中任务」的展示单元。 */
data class ActiveTask(
    /** 会话所在实例的 baseUrl(`http://host:port`),点进详情要用。 */
    val baseUrl: String,
    /** 实例显示名 —— 取指向该 baseUrl 的第一张卡片标题,没卡片就回落 host:port。 */
    val instanceName: String,
    /** 会话元数据(标题 / 模型 / 更新时间 / sessionId)。 */
    val meta: AgentSessionMeta,
    /** 未完成任务数(v2Tasks 里 pending/in_progress 的条数)。探测失败为 0。 */
    val activeTaskCount: Int,
    /** 后台 agent 任务数(agentTasks)。只用来加徽标,不进 activeTaskCount。 */
    val backgroundTaskCount: Int,
    /** 最近 [RUNNING_WINDOW_MS] 内有过更新 —— 认为「正在跑」。 */
    val running: Boolean,
)

/**
 * 最近一次聚合结果的进程内缓存。
 *
 * 为什么需要:导航到会话详情/WebView 时 NavHost 会**销毁**任务栏的 composition,
 * 回来时 `remember` 状态清零、重新拉一轮 —— 而一轮要打 N 个实例(一个不可达
 * 的就是 2.5 秒),用户会看到「进行中」区空白几秒。
 *
 * 这不是持久化,只是把上一轮结果留着先渲染,新结果到了覆盖它。进程重启即失效,
 * 符合「状态类数据不该落盘」的取舍。
 */
object ActiveTasksCache {
    @Volatile
    var tasks: List<ActiveTask> = emptyList()
}

/** 「正在跑」的时间窗:最后一条消息在 90 秒内 = 大概率还在输出。 */
private const val RUNNING_WINDOW_MS = 90_000L


/** 单个实例一次聚合的墙钟上限(见 AgentApi 的 callTimeoutMs 注释)。 */
private const val INSTANCE_CALL_TIMEOUT_MS = 2_500L

/** 「进行中」区的宽窗口:半小时内更新过的会话都值得露出(可回看刚跑完的)。 */
private const val DEFAULT_WINDOW_MS = 30 * 60_000L

/**
 * 统计会话未完成任务数。`/state` 拉不到时返回 (0, 0) —— 宁可少显示一个徽标,
 * 也不要因为一次 404 把这条会话从列表里踢掉。
 */
private suspend fun countTasks(api: AgentApi, sessionId: String): Pair<Int, Int> {
    val state = runCatching { api.readState(sessionId) }.getOrNull() ?: return 0 to 0
    val active = state.v2Tasks.count { it.status == "pending" || it.status == "in_progress" }
    // agentTasks 是 JsonElement(服务端还没定 schema),只数个数。
    return active to state.agentTasks.size
}

/**
 * 聚合所有实例的活跃会话。返回已排序:在跑的在前,其余按更新时间倒序。
 *
 * @param cards 入口卡片列表(任务栏直接把它当实例发现源)。
 */
suspend fun collectActiveTasks(
    cards: List<Card>,
    windowMs: Long = DEFAULT_WINDOW_MS,
    perInstance: Int = 3,
    probeTop: Int = 2,
): List<ActiveTask> = coroutineScope {
    val now = System.currentTimeMillis()

    // baseUrl → 显示名(第一张指向它的卡片标题)。同一实例配了多张卡时只出现一次。
    val targets = LinkedHashMap<String, String>()
    for (card in cards) {
        val base = extractBaseUrl(card.url) ?: continue
        targets.putIfAbsent(base, card.title.ifBlank { base.substringAfter("://") })
    }

    val results = targets.map { (base, name) ->
        async {
            // 每个实例的墙钟上限。zai 在本机局域网内应答 < 1 秒,2.5 秒已经
            // 是「明显不对劲」的量级;超了就当作这个实例不可达,静默跳过 ——
            // 否则一张写错 IP 的卡片会把整段「进行中」拖到十几秒后才出现。
            val api = AgentApi(base, callTimeoutMs = INSTANCE_CALL_TIMEOUT_MS)
            val sessions = runCatching { api.listSessions() }.getOrNull() ?: return@async emptyList()
            val recent = sessions
                .filter { it.updatedAt > 0 && now - it.updatedAt <= windowMs }
                .sortedByDescending { it.updatedAt }
                .take(perInstance)

            recent.mapIndexed { index, meta ->
                val (active, background) = if (index < probeTop) {
                    countTasks(api, meta.sessionId)
                } else {
                    0 to 0
                }
                ActiveTask(
                    baseUrl = base,
                    instanceName = name,
                    meta = meta,
                    activeTaskCount = active,
                    backgroundTaskCount = background,
                    running = active > 0 || now - meta.updatedAt <= RUNNING_WINDOW_MS,
                )
            }
        }
    }.awaitAll().flatten()

    val sorted = results.sortedWith(
        compareByDescending<ActiveTask> { it.running }.thenByDescending { it.meta.updatedAt }
    )
    ActiveTasksCache.tasks = sorted
    return@coroutineScope sorted
}
