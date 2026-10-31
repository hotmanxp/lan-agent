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
import android.util.Log
import io.github.hotmanxp.lanagent.engine.EngineController
import io.github.hotmanxp.lanagent.engine.EngineInstaller
import io.github.hotmanxp.lanagent.engine.EnginePaths
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
    /**
     * supervisor 自己的 baseUrl —— 生命周期动作(启动 / 停止 / 重启)只认它,
     * 不是子实例的 baseUrl。卡片回落路径下没有 supervisor,这里是 null,
     * 于是 [canRestart] 恒 false(那条路上根本没有能重启的接口)。
     */
    val managerBaseUrl: String? = null,
)

/**
 * 能不能对这一个实例发 `POST /api/instances/{id}/restart`。
 *
 * 两条约束,都来自服务端而不是本地猜测:
 *   - **必须是 supervisor 管出来的实例** —— 生命周期路由挂在 manager 上,
 *     子实例自己(`/api/instances` 在它那儿不是 supervisor 角色)会被
 *     `ensureNotInstanceChild` 挡掉。卡片回落路径压根没有 manager。
 *   - **不能是 supervisor 自己** —— route 对 `__current__` 直接 400
 *     `cannot restart current instance`,supervisor 内部 `ensureNotCurrent`
 *     也再拦一道(见 opencc-web `instanceSupervisor.ts:511`)。
 *
 * 离线实例**也允许**重启:服务端的 restart = `doStop` + `doStart`,而 `doStop`
 * 对没有活子进程的条目是 no-op(直接置 stopped),所以「重启一个停掉的实例」
 * 实际等价于把它拉起来 —— 这正是从选择面板救一个崩掉的实例最想做的事。
 *
 * 判 `isNullOrBlank` 而不是 `!= null`:`AgentInstance` 有好几处是手工 new 出来的
 * (路由参数摊的临时实例等),空串当「没有 manager」比 null 更符合本意 ——
 * 对着空 baseUrl 发请求,UI 上就是个点了没反应的重启按钮。
 */
fun AgentInstance.canRestart(): Boolean = !managerBaseUrl.isNullOrBlank() && !isCurrent

/** 单实例探活/拉会话的墙钟上限(卡片回落路径用,见 AgentApi 的 callTimeoutMs)。 */
private const val PROBE_TIMEOUT_MS = 1_500L

/**
 * 本地引擎的探活超时。它比卡片路径宽裕得多:引擎要解包 + 起 Node,
 * 冷启动好几秒,给 1.5s 会被误判成离线。
 */
private const val LOCAL_ENGINE_PROBE_TIMEOUT_MS = 8_000L

/** 本地引擎在实例目录里的 id。 */
const val LOCAL_ENGINE_ID = "local-engine"

/**
 * 探一下本机引擎活着没有。
 *
 * 只打 127.0.0.1,一次 HTTP 调用,很便宜 —— 可以放进轻量轮询里。
 * 冷启动那几秒会返回 false,通了就 true。
 */
suspend fun Context.probeLocalEngine(logFailure: Boolean = false): Boolean =
    runCatching {
        AgentApi(EnginePaths.baseUrl(), callTimeoutMs = LOCAL_ENGINE_PROBE_TIMEOUT_MS).listSessions()
    }.onFailure { e ->
        if (logFailure) Log.w("AgentInstances", "本机引擎探活失败(${EnginePaths.baseUrl()})", e)
    }.isSuccess

/**
 * 解析可切换的 Agent 实例目录。**永不抛异常** —— 拿不到就返回空列表,
 * 调用方据此走「还没有可用实例」的空态,而不是让整屏崩掉。
 */
suspend fun Context.resolveAgentInstances(): List<AgentInstance> {
    val cards = runCatching { cardsFlow().first() }.getOrDefault(emptyList())

    // 本地引擎排第一:它是「不依赖电脑」的兜底 —— 局域网实例全挂了的时候,
    // 手机上这一个还能聊。引擎没起来就整条不出现,UI 那边的空态文案不用改。
    val local = localEngineInstance()

    val manager = findManagerBaseUrl(cards)
    if (manager != null) {
        val snapshots = runCatching { InstancesApi(manager).listInstances() }.getOrNull()
        if (!snapshots.isNullOrEmpty()) {
            return listOfNotNull(local) + snapshots.toAgentInstances(manager)
        }
    }

    return listOfNotNull(local) + cards.discoverInstances()
}

/**
 * 本地引擎实例(跑在 APK 里的 :engine 进程)。
 *
 * baseUrl 是 127.0.0.1 —— 同设备的所有进程共享同一个 loopback,所以主进程
 * 直连就行,跟连局域网实例在协议层完全一样(现有的 AgentApi / SSE 客户端
 * 一行都不用改,这也是选 opencc core 而不是 dsh 的原因)。
 */
private suspend fun Context.localEngineInstance(): AgentInstance? {
    // 引擎没装(没跑过 fetch-native.sh / 引擎包没进 APK)就不给这个选项 ——
    // 与其给一个永远连不上的入口,不如不出现。
    if (!EngineInstaller.isInstalled(this)) return null

    // 顺手把引擎起起来。之前 EngineController 没有任何调用点,引擎永远
    // 不会自己跑 —— 这里是唯一的触发点:每次解析实例列表时看一眼,
    // 引擎没活就让它起。EngineController.start() 幂等,重复调不会起两个。
    //
    // 起引擎是异步的(解包 + 起 Node 要好几秒),所以这一次解析拿到的
    // online 必然是 false —— UI 下一轮刷新(2.5s 轮询)就会变成 true。
    EngineController.start(this)

    val baseUrl = EnginePaths.baseUrl()
    val online = probeLocalEngine(logFailure = true)
    return AgentInstance(
        id = LOCAL_ENGINE_ID,
        name = "本机引擎",
        baseUrl = baseUrl,
        online = online,
        isCurrent = false,
    )
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
            managerBaseUrl = managerBaseUrl,
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
