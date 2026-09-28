// ui/AaSessionHolder.kt — AA 会话的**进程内**状态与 WS 生命周期持有者(0.24.0)
//
// ## 为什么需要它
//
// 0.24.0 之前 AA 的 dashboard 和会话详情在**同一个 composable** 里,顶层屏自己
// `remember` 一张 `sessionStates` map 管所有会话的 WS job。底栏加了「远程」栏
// 之后,会话详情要变成独立路由 `aa-session/{sid}` —— 于是顶层屏和详情屏是
// **两个互不相见的 composition**:
//
//   - 导航到 `aa-session/{sid}` 时,`tab/remote` 的 composable 被移出 composition,
//     它的 `remember` 全没了,连 WS job 一起被丢(协程随之取消)
//   - 用 `rememberSaveable` 救不了 —— `AgentsAnywhereSessionState` 内部是
//     `mutableStateMapOf` / `Snapshot.withMutableSnapshot`,不可序列化
//
// 提到进程级单例是本仓已有的做法(`PresentFileCache` 的「离开再回来」兜底、
// `ActiveTasksCache`)。这里同理:持有者只管「谁活着、谁的数据在」,绘制仍在
// `AaSessionViews.kt`,两者用 `AgentsAnywhereSessionState` 通信。
//
// ## 生命周期
//
// - `open()` 幂等:同一 session 重复 open 会先 cancel 旧 job,再按新参数重开
// - `stop(sessionId)` 只断 WS,**不清数据** —— 从详情返回列表时气泡还在
// - `stopAll()` 清 job 与数据,给「退出登录」用
//
// 不做的事:不做跨进程持久化。AA 会话是实时流,冷启动重新拉 snapshot 即可,
// 持久化一份陈旧 timeline 反而会闪旧内容。
package io.github.hotmanxp.lanagent.ui

import io.github.hotmanxp.lanagent.data.AgentsAnywhereApi
import io.github.hotmanxp.lanagent.data.AgentsAnywhereClient
import io.github.hotmanxp.lanagent.data.AgentsAnywhereConnState
import io.github.hotmanxp.lanagent.data.AgentsAnywhereEvent
import io.github.hotmanxp.lanagent.data.AgentsAnywhereSessionState
import io.github.hotmanxp.lanagent.data.dispatchSessionFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object AaSessionHolder {

    private val states = mutableMapOf<String, AgentsAnywhereSessionState>()
    private val jobs = mutableMapOf<String, Job>()

    /**
     * 取(或建)某会话的状态对象。**不要**在渲染路径里用 `remember` 包它 ——
     * 对象本身是稳定的,真正驱动重组的是它内部的 mutableState 字段。
     */
    fun stateOf(sessionId: String): AgentsAnywhereSessionState =
        states.getOrPut(sessionId) { AgentsAnywhereSessionState(sessionId) }

    /**
     * 打开会话:先拉 snapshot(冷启),再开 WS。
     *
     * 顺序不能反 —— 先开 WS 再拉 snapshot 会让 snapshot 覆盖掉 WS 已经推来的
     * 新事件(冷启动瞬间的 timeline 增量就丢了)。
     *
     * @param preserveOutgoing true = **手动重连**,`applyTimelineSnapshot` 不清
     *        乐观气泡(`outgoingByCmid`)。用户刚发出去但 server 还没回流的
     *        事件,重连不能吞掉。冷启动传 false(本来就没有 outgoing)。
     */
    fun open(
        sessionId: String,
        scope: CoroutineScope,
        client: AgentsAnywhereClient,
        api: AgentsAnywhereApi,
        baseUrl: String,
        accessToken: String,
        preserveOutgoing: Boolean = false,
    ) {
        jobs.remove(sessionId)?.cancel()
        val st = stateOf(sessionId)
        if (!preserveOutgoing) st.clearOutgoing()
        st.setConn(AgentsAnywhereConnState.Connecting, "拉 snapshot + 开 WS")

        jobs[sessionId] = scope.launch {
            // 1) snapshot 冷启
            val snapResult = runCatching { api.fetchSnapshot(sessionId) }
            snapResult.onFailure { err ->
                val msg = (err as? io.github.hotmanxp.lanagent.data.HttpException)
                    ?.let { "${it.code} ${it.message ?: ""}".trim() }
                    ?: err.message ?: err.javaClass.simpleName
                st.setConn(AgentsAnywhereConnState.Error, "snapshot 失败: $msg")
            }
            snapResult.getOrNull()?.let { snap ->
                val title = (snap.session["title"] as? JsonPrimitive)?.contentOrNull
                st.applyTimelineSnapshot(snap.timeline)
                snap.state?.let { st.applyRuntimeState(it) }
                st.applySessionMeta(snap.session)
                snap.notices.forEach { st.upsertNotice(it) }
                st.setConn(
                    AgentsAnywhereConnState.Connecting,
                    "snapshot 拉完" + if (!title.isNullOrBlank()) " · $title" else "",
                )
            }

            // 2) 再开 WS,cursor 用 snapshot 末尾的 nextSeq
            val cursor = snapResult.getOrNull()?.timeline?.nextSeq ?: 0L
            try {
                client.subscribeSession(
                    sessionId = sessionId,
                    baseUrl = baseUrl.trim(),
                    accessToken = accessToken.trim(),
                ).collect { ev ->
                    when (ev) {
                        is AgentsAnywhereEvent.Incoming -> {
                            if (dispatchSessionFrame(ev.parsed, st)) {
                                st.setConn(
                                    AgentsAnywhereConnState.Connected,
                                    "ws · 已连接 (cursor seq:$cursor)",
                                )
                            }
                        }
                        is AgentsAnywhereEvent.Lifecycle -> st.setConn(ev.kind.toConnState(), ev.message)
                        is AgentsAnywhereEvent.Unparseable -> Unit
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    /** 断开 WS 但**保留数据** —— 从详情返回列表时气泡要还在。 */
    fun stop(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
    }

    /** 断全部连接并丢数据 —— 「退出登录」走这里,别让旧 token 的会话留在内存里。 */
    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        states.clear()
    }

    fun isOpen(sessionId: String): Boolean = jobs[sessionId]?.isActive == true
}

internal fun AgentsAnywhereEvent.Lifecycle.Kind.toConnState(): AgentsAnywhereConnState = when (this) {
    AgentsAnywhereEvent.Lifecycle.Kind.Connected -> AgentsAnywhereConnState.Connected
    AgentsAnywhereEvent.Lifecycle.Kind.Closed -> AgentsAnywhereConnState.Disconnected
    AgentsAnywhereEvent.Lifecycle.Kind.Failure -> AgentsAnywhereConnState.Error
    AgentsAnywhereEvent.Lifecycle.Kind.Retrying -> AgentsAnywhereConnState.Error
}
