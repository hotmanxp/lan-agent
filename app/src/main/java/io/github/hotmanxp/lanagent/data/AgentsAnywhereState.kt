// data/AgentsAnywhereState.kt — 可观察的运行时状态容器,供 Compose 直接 collect。
//
// 分为两块独立容器,屏幕按需持有:
//   - **Dashboard**:连接 dashboard WS 期间的 connectors/projects/sessions/runtimes
//     + 服务器时间。每次 `dashboard.snapshot` 到达整段替换(服务端会自己重
//     build/invalidate,客户端拿到啥渲染啥,见 `dashboard_stream.py:127-155`)。
//   - **Session**:选进某个会话后的 timeline + runtime state + notices +
//     connection status + 用户输入队列。`timeline.snapshot` 整段重置,
//     `timeline.item_created/updated` 按 id upsert。
//
// 设计原则:状态对外只暴露 **不可变的快照**(List / Map 都包装成 immutable 拷
// 贝),Compose 用 key 命中重组,业务代码修改路径只能走容器内部方法。容器方法
// 内部用 `mutableStateOf` + `Snapshot.takeMutableSnapshot` 不需要——直接赋值
// 即可,Compose 自动观察。
package io.github.hotmanxp.lanagent.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.serialization.json.JsonObject

/** 连接状态 —— 给 UI 状态条和按钮 disable 逻辑统一用。 */
enum class AgentsAnywhereConnState {
    /** 没填 baseUrl 或 token。 */
    NotConfigured,
    /** 已配置但当前没订阅。 */
    Disconnected,
    /** 正在拉 ticket + 建 WS。 */
    Connecting,
    /** WS 已连,正常收事件。 */
    Connected,
    /** 异常/断线 —— 等退避重连。 */
    Error,
    /** 用户手动按了「停止」(重连退避也停)。 */
    Cancelled,
}

// ── Dashboard ──────────────────────────────────────────────────────────

class AgentsAnywhereDashboardState {
    var conn: AgentsAnywhereConnState by mutableStateOf(AgentsAnywhereConnState.NotConfigured)
        private set
    var statusText: String by mutableStateOf("未连接")
        private set
    var connectors: List<JsonObject> by mutableStateOf(emptyList())
        private set
    var projects: List<JsonObject> by mutableStateOf(emptyList())
        private set
    var sessions: List<JsonObject> by mutableStateOf(emptyList())
        private set
    var runtimes: List<JsonObject> by mutableStateOf(emptyList())
        private set
    var serverTime: String? by mutableStateOf(null)
        private set

    /**
     * REST 兜底 + WS 帧分派共用入口 —— 都收 `DashboardSnapshot`(强类型)。
     * WS 那一路在 `AgentsAnywhereDispatch.dispatchDashboardFrame` 里先把
     * `JsonObject` 转成 `DashboardSnapshot` 再调过来;这里不重复解析。
     */
    fun applyDashboardSnapshot(snapshot: DashboardSnapshot) {
        // 5 个 mutableState* 同时赋值 —— 包到同一个 snapshot 里,Compose
        // 重组时只看到一致终态,不会读到一个「project 更新了但 connector
        // 还没」的中间态。
        Snapshot.withMutableSnapshot {
            connectors = snapshot.connectors
            projects = snapshot.projects
            sessions = snapshot.sessions
            runtimes = snapshot.runtimes
            serverTime = snapshot.serverTime
        }
    }

    fun setConn(state: AgentsAnywhereConnState, status: String) {
        conn = state
        statusText = status
    }
}

// ── Session ────────────────────────────────────────────────────────────

/** 乐观 UI 的发送项 —— server 回真实 item 时按 clientMessageId 替换。 */
data class AaOutgoing(
    val clientMessageId: String,
    val content: String,
    /** `Sending` / `Failed` / `Sent`(等待 server 回 item) —— 没真发出去之前不进 timeline。 */
    val status: OutgoingStatus,
    val sentAt: Long,
)

enum class OutgoingStatus { Sending, Failed, Sent }

/**
 * 选中会话后的可观察状态。
 *
 * 字段全部 `by mutableStateOf` + `mutableStateMapOf` —— Compose 直接观察,
 * 任何方法调用都会触发重组。
 */
class AgentsAnywhereSessionState(val sessionId: String) {
    var conn: AgentsAnywhereConnState by mutableStateOf(AgentsAnywhereConnState.NotConfigured)
        private set
    var statusText: String by mutableStateOf("未连接")
        private set

    /** 当前 runtime state(`SessionRuntimeState`)—— server `runtime.state.updated` 推过来。 */
    var runtimeState: SessionRuntimeState? by mutableStateOf(null)
        private set

    /** SessionView(`session` 字段) —— `session.meta.updated` 推过来。 */
    var sessionMeta: JsonObject? by mutableStateOf(null)
        private set

    /** timeline items —— key 是 item.id。顺序按 orderSeq/updatedSeq。 */
    val itemsById = mutableStateMapOf<String, TimelineItem>()

    /** 已经展示过 user bubble 的 clientMessageId(乐观层) —— server item 落地后从这删。 */
    val outgoingByCmid = mutableStateMapOf<String, AaOutgoing>()

    /** 顺序(append-only) —— 按 `updatedSeq` 排序的 item id 列表。 */
    val orderedIds = mutableStateListOf<String>()

    /** 还没解决的 notice(按 noticeId)。 */
    val noticesById = mutableStateMapOf<String, NoticeIn>()

    /** 当前是否有"运行中"行为(用于控制 interrupt 按钮可见性)。 */
    val isStreaming: Boolean get() = runtimeState?.status in setOf("running", "waiting_approval", "pending")

    fun setConn(state: AgentsAnywhereConnState, status: String) {
        conn = state
        statusText = status
    }

    /** `timeline.snapshot` 到达 → 整段重置。 */
    fun applyTimelineSnapshot(snapshot: ProtocolTimelineSnapshot) {
        // itemsById / orderedIds / outgoingByCmid 同时重置 —— 包到
        // 同一个 snapshot 事务里,避免 Compose 重组时观察到「orderedIds
        // 已被清空但 itemsById 还在旧值」的中间态导致空指针 / 错位。
        Snapshot.withMutableSnapshot {
            itemsById.clear()
            orderedIds.clear()
            snapshot.items.sortedBy { it.orderSeq }.forEach { item ->
                itemsById[item.id] = item
                orderedIds.add(item.id)
            }
            // snapshot 重置 = 用户消息已经全在 server 那边了,清掉乐观占位。
            // 配对规则:只有当新 snapshot 的某条 item 已经覆盖了对应 cmid,
            // 才把它从 outgoing map 移除 —— 否则保留(用户能看到自己的
            // 乐观气泡,等 server `timeline.item_created` 推回来时再替换)。
            val coveredCmids = snapshot.items
                .mapNotNull { it.source?.clientMessageId }
                .filter { it.isNotBlank() }
                .toSet()
            val iter = outgoingByCmid.keys.iterator()
            while (iter.hasNext()) {
                if (iter.next() in coveredCmids) iter.remove()
            }
        }
    }

    /** `timeline.item_created` / `timeline.item_updated` → 按 id upsert。 */
    fun upsertTimelineItem(item: TimelineItem) {
        // itemsById / orderedIds / outgoingByCmid 三者一起动 —— 同事务。
        Snapshot.withMutableSnapshot {
            val wasNew = itemsById.put(item.id, item) == null
            if (wasNew) {
                insertOrdered(item.id, item.orderSeq, item.updatedSeq)
            }
            // 如果这条 item 带 clientMessageId,把乐观占位移除。
            val cmid = item.source?.clientMessageId
            if (!cmid.isNullOrBlank()) {
                outgoingByCmid.remove(cmid)
            }
        }
    }

    /**
     * 把新 item id 按 orderSeq(updatedSeq tiebreak)插到有序列表里 —— O(n)
     * 线性查找足够(item 数一般 < 1000);append-only 列表不应该做就地排序。
     */
    private fun insertOrdered(id: String, orderSeq: Long, updatedSeq: Long) {
        val idx = orderedIds.indexOfFirst { existingId ->
            val existing = itemsById[existingId] ?: return@indexOfFirst false
            if (existing.orderSeq != orderSeq) existing.orderSeq > orderSeq
            else existing.updatedSeq > updatedSeq
        }
        if (idx < 0) orderedIds.add(id) else orderedIds.add(idx, id)
    }

    /** `runtime.state.updated` —— `state` 字段。 */
    fun applyRuntimeState(state: SessionRuntimeState) {
        runtimeState = state
    }

    /** `session.meta.updated` —— `session` 字段。 */
    fun applySessionMeta(meta: JsonObject) {
        sessionMeta = meta
    }

    /** `runtime.notice.snapshot` —— 整段重置。 */
    fun applyNoticeSnapshot(notices: List<NoticeIn>) {
        // clear + 多次 put 必须同事务,否则 notice 行会闪一下空状态。
        Snapshot.withMutableSnapshot {
            noticesById.clear()
            notices.forEach { noticesById[it.noticeId] = it }
        }
    }

    /** `runtime.notice.updated` —— 按 id upsert,终态就移除。 */
    fun upsertNotice(notice: NoticeIn) {
        Snapshot.withMutableSnapshot {
            if (notice.status in setOf("resolved", "closed", "expired", "cancelled", "failed")) {
                noticesById.remove(notice.noticeId)
            } else {
                noticesById[notice.noticeId] = notice
            }
        }
    }

    /** 发送中(乐观):用户输入即塞一条占位。 */
    fun trackOutgoing(clientMessageId: String, content: String, status: OutgoingStatus) {
        outgoingByCmid[clientMessageId] = AaOutgoing(
            clientMessageId = clientMessageId,
            content = content,
            status = status,
            sentAt = System.currentTimeMillis(),
        )
    }

    fun updateOutgoingStatus(clientMessageId: String, status: OutgoingStatus) {
        val cur = outgoingByCmid[clientMessageId] ?: return
        outgoingByCmid[clientMessageId] = cur.copy(status = status)
    }

    /**
     * 清空乐观气泡队列 —— 用于冷启动路径(`openSession` 默认行为)。
     * 手动重连场景**不要**调本方法,否则会丢用户刚发但 server 还没回流的事件。
     */
    fun clearOutgoing() {
        outgoingByCmid.clear()
    }

    /** 公开一条只读的有序 timeline 视图 —— 给渲染层直接用,key 命中重组。 */
    fun orderedItems(): List<TimelineItem> = orderedIds.mapNotNull { itemsById[it] }

    /** 当前需要展示的"待解决" notice 列表(按 createdAt 升序)。 */
    fun openNotices(): List<NoticeIn> = noticesById.values
        .filter { it.status in setOf("open", "responding") }
        .sortedBy { it.createdAt ?: "" }
}
