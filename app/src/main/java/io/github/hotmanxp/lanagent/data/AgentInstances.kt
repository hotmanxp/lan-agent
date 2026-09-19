// data/AgentInstances.kt — 「可切换的 Agent 宿主实例」目录。
//
// 0.15.0 起任务栏默认落在原生 Agent 页,而 Agent 页是**按实例**分家的
// (每个 zai 进程一份 /api/agent/sessions)。所以需要一个「有哪些实例可选、
// 谁活着」的目录 —— 这就是本文件。
//
// 数据源两档(按可靠性排):
//
//   1. **`/api/instances`(supervisor 快照,首选)** —— 一次请求拿到全部子实例
//      的 name / state / port / startPort / isCurrent。判据简单:state == running
//      且 port 非空 = 在线。**离线实例没有 port**,但它有 startPort(它启动时会
//      监听的端口),拿它兜底拼 baseUrl,这样「离线」也能进选择面板(参考图里
//      离线设备是照常列出来的)。
//
//   2. **卡片列表(回落)** —— 管理器不可达时(它自己挂了,或者压根没配
//      `/instances` 卡片),只能用旧路子:卡片 URL 抽 baseUrl,逐个探
//      `/api/agent/sessions` 判断在线。比第 1 档贵(扇出 N 个请求),但能保证
//      「管理器死了,活着的子实例照样能聊」。
//
// 不做排序:顺序沿用服务端 / 卡片的自然顺序。在线优先排序看着更"聪明",但会让
// 行位置随实例起落跳来跳去 —— 选择面板和实例栏的顺序一旦不一致,用户就得重新找。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import io.github.hotmanxp.lanagent.model.Card
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** 一个可以作为 Agent 宿主的 opencc 实例。 */
data class AgentInstance(
    /** 实例 id(`__current__` 或 `inst_xxx`);卡片回落路径下就等于 baseUrl。 */
    val id: String,
    /** 显示名。supervisor 里是实例名(opencc-web / code / …),卡片路径下是卡片标题。 */
    val name: String,
    /** `http://host:port`,拼 API 用。 */
    val baseUrl: String,
    /** 在线 = supervisor 说 running 且端口非空(卡片路径下 = 探测成功)。 */
    val online: Boolean,
    /** 是不是 supervisor 自己(`__current__`)。选择面板里给个「当前」标记。 */
    val isCurrent: Boolean,
    /** 实例启动 profile(标准 / task-factory / weixin),选择面板用来补 tag。 */
    val app: InstanceAppProfile? = null,
)

/** 单实例探活/拉会话的墙钟上限(卡片回落路径用,见 AgentApi 的 callTimeoutMs)。 */
private const val PROBE_TIMEOUT_MS = 1_500L

/**
 * 解析可切换的 Agent 实例目录。**永不抛异常** —— 拿不到就返回空列表,
 * 调用方据此走「还没有可用实例」的空态,而不是让整屏崩掉。
 */
suspend fun Context.resolveAgentInstances(): List<AgentInstance> {
    val cards = runCatching { cardsFlow().first() }.getOrDefault(emptyList())

    val manager = findManagerBaseUrl(cards)
    if (manager != null) {
        val snapshots = runCatching { InstancesApi(manager).listInstances() }.getOrNull()
        if (!snapshots.isNullOrEmpty()) return snapshots.toAgentInstances(manager)
    }

    return cards.discoverInstances()
}

/**
 * 从 supervisor 快照映射。`port` 缺失时退到 `startPort`(离线实例靠它进面板),
 * 两个都没有(理论上不会)就跳过该条。
 */
private fun List<InstanceSnapshot>.toAgentInstances(managerBaseUrl: String): List<AgentInstance> {
    val host = managerBaseUrl
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore(':')
    if (host.isBlank()) return emptyList()

    return mapNotNull { snap ->
        val port = snap.port ?: snap.startPort ?: return@mapNotNull null
        AgentInstance(
            id = snap.id,
            name = snap.name.ifBlank { "$host:$port" },
            baseUrl = "http://$host:$port",
            online = snap.state == InstanceState.running && snap.port != null,
            isCurrent = snap.isCurrent,
            app = snap.app,
        )
    }
}

/** 卡片回落:baseUrl 去重 → 并发探活 → 拼目录。顺序 = 卡片顺序。 */
private suspend fun List<Card>.discoverInstances(): List<AgentInstance> = coroutineScope {
    val cards = this@discoverInstances
    val unique = LinkedHashMap<String, Card>()
    for (card in cards) {
        val base = extractBaseUrl(card.url) ?: continue
        unique.putIfAbsent(base, card)
    }

    unique.map { (base, card) ->
        async {
            val online = runCatching {
                AgentApi(base, callTimeoutMs = PROBE_TIMEOUT_MS).listSessions()
            }.isSuccess
            AgentInstance(
                id = base,
                name = card.title.ifBlank { base.substringAfter("://") },
                baseUrl = base,
                online = online,
                isCurrent = false,
            )
        }
    }.awaitAll()
}

/**
 * 挑一个实例:优先 [preferredBaseUrl] 且在线;它下线了就退到**第一个在线的子实例**
 * (跳过 supervisor 自身 —— 它的 cwd 是家目录,不是一个干活的实例);再退到第一个
 * 在线的;最后退到目录第一条(全离线时也要有个东西可选)。
 */
fun List<AgentInstance>.pickDefault(preferredBaseUrl: String?): AgentInstance? =
    firstOrNull { it.baseUrl == preferredBaseUrl && it.online }
        ?: firstOrNull { it.online && !it.isCurrent }
        ?: firstOrNull { it.online }
        ?: firstOrNull()
