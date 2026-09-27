// data/AgentsAnywhereDispatch.kt — 把 WS 帧分派到对应状态容器。
//
// 纯函数,只读 frame、调用 state 的 apply* 方法、不抛异常(未知 type 静默
// 跳过 —— 服务器将来加新 type 时,老客户端直接忽略就行)。
//
// dashboard WS 帧:只有 `dashboard.snapshot` 一种 —— server 端
// `dashboard_events.py:30` 确实 `publish("dashboard.changed")` 到 broker bus,
// 但 `dashboard_stream.py:145` 在流出口把它 filter 掉,自动重推
// `dashboard.snapshot` 覆盖(client 永远不会直接看到 `dashboard.changed`)。
//
// session WS 帧(server `core/events.py:104-241`):
//   - timeline.snapshot            → 整段重置 + 清空乐观层
//   - timeline.item_created        → 按 id upsert(新加)
//   - timeline.item_updated        → 按 id upsert(原地更新)
//   - runtime.state.updated        → 替换当前 SessionRuntimeState
//   - session.meta.updated         → 替换 SessionView
//   - runtime.notice.snapshot      → 整段重置
//   - runtime.notice.updated       → 按 id upsert(终态则移除)
//   - runtime.capability.updated   → 暂不消费(后续可加 cap 提示)
//   - runtime.catalog.updated      → 暂不消费
//   - session.refetch_required     → 客户端重新拉 snapshot(下轮 todo)
//   - session.subscribed           → 仅首次,记 cursor
//
// `keepalive` 是裸帧(server `sessions.py:1091` 直接 send_json,没经 envelope),
// 这里不分配 state,只返回 false 让外层知道"这是心跳"。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 派发一条 dashboard WS 帧到 [state]。
 *
 * @return true 表示有可消费的 frame,屏幕据此决定要不要更新"上次心跳"等元数据。
 */
fun dispatchDashboardFrame(
    frame: JsonObject,
    state: AgentsAnywhereDashboardState,
): Boolean {
    val type = (frame["type"] as? JsonPrimitive)?.contentOrNull ?: return false
    return when (type) {
        "dashboard.snapshot" -> {
            state.applyDashboardSnapshot(frame.toDashboardSnapshot())
            true
        }
        else -> false
    }
}

/**
 * 派发一条 session WS 帧到 [state]。
 *
 * @return true 表示有可消费的 frame。
 */
fun dispatchSessionFrame(
    frame: JsonObject,
    state: AgentsAnywhereSessionState,
): Boolean {
    val type = (frame["type"] as? JsonPrimitive)?.contentOrNull ?: return false
    val payload = frame["payload"] as? JsonObject ?: JsonObject(emptyMap())
    return when (type) {
        "session.subscribed" -> {
            // payload:{clientId, eventCursor}
            // 仅首次订阅成功,记录 cursor 不必持久化(每次重连都重拉)。
            true
        }
        "timeline.snapshot" -> {
            state.applyTimelineSnapshot(payload.toTimelineSnapshot())
            true
        }
        "timeline.item_created", "timeline.item_updated" -> {
            val itemObj = payload["item"] as? JsonObject
            val item = itemObj?.toTimelineItem()
            if (item != null) state.upsertTimelineItem(item)
            item != null
        }
        "runtime.state.updated" -> {
            val st = (payload["state"] as? JsonObject)?.toRuntimeState()
            if (st != null) state.applyRuntimeState(st)
            st != null
        }
        "session.meta.updated" -> {
            (payload["session"] as? JsonObject)?.let { state.applySessionMeta(it) } != null
        }
        "runtime.notice.snapshot" -> {
            val arr = payload["notices"] as? kotlinx.serialization.json.JsonArray
            val notices = arr?.mapNotNull { (it as? JsonObject)?.toNotice() } ?: emptyList()
            state.applyNoticeSnapshot(notices)
            true
        }
        "runtime.notice.updated" -> {
            val noticeObj = payload["notice"] as? JsonObject
            val notice = noticeObj?.toNotice()
            if (notice != null) state.upsertNotice(notice)
            notice != null
        }
        "session.refetch_required" -> {
            // payload:{eventCursor} —— 客户端收到后应重新拉一次 snapshot。
            // 当前屏内由 toggleSubscription 重新触发,这里不直接拉。
            true
        }
        "runtime.capability.updated",
        "runtime.catalog.updated" -> true
        else -> false
    }
}

/** `keepalive` / 其他未知 —— 不消耗状态,返回 false。 */
fun isKnownFrame(type: String): Boolean = when (type) {
    "dashboard.snapshot" -> true
    "session.subscribed",
    "timeline.snapshot",
    "timeline.item_created",
    "timeline.item_updated",
    "runtime.state.updated",
    "session.meta.updated",
    "runtime.notice.snapshot",
    "runtime.notice.updated",
    "runtime.capability.updated",
    "runtime.catalog.updated",
    "session.refetch_required" -> true
    else -> false
}
