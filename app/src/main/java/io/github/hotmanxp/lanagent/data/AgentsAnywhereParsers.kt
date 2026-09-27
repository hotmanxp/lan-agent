// data/AgentsAnywhereParsers.kt — server wire JSON → 强类型模型的转换层。
//
// 为什么不全靠 kotlinx.serialization 直接 decode:我们要在 typed model 上额外
// 挂一个 `raw: JsonObject`,这是纯 JSON 透传(`ignoreUnknownKeys` 之外,渲染
// 层遇到没建模字段直接走 raw 取)。直接 `decodeFromJsonElement` 没法挂 raw,
// 所以拆两层:先 kotlinx 拿结构 + `coerceInputValues` 兜底类型漂移,再把原始
// JsonObject 也挂上去。
//
// 这个文件**严格只做 wire→typed 的桥接**,不做字段语义推断 —— 比如 `content`
// 的「是字符串还是对象」交给渲染层;`source` 是不是 Notice.source 这种,不要在
// parser 里假设。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private val parseJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

// ── SessionSummary ─────────────────────────────────────────────────────
// sessions/list 直接给 SessionView,字段多;客户端只取常用字段,剩余放 raw。

internal fun JsonObject.toSessionSummary(): SessionSummary? {
    val id = (this["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    return SessionSummary(
        id = id,
        connectorId = stringOrNull("connectorId"),
        runtime = stringOrNull("runtime"),
        runtimeId = stringOrNull("runtimeId"),
        externalSessionId = stringOrNull("externalSessionId"),
        title = stringOrNull("title"),
        cwd = stringOrNull("cwd"),
        status = stringOrNull("status"),
        takeover = boolOrFalse("takeover"),
        pinned = boolOrFalse("pinned"),
        archived = boolOrFalse("archived"),
        unread = boolOrFalse("unread"),
        lastActivityAt = stringOrNull("lastActivityAt"),
        updatedSeq = (this["updatedSeq"] as? JsonPrimitive)?.longOrNull ?: 0L,
        raw = this,
    )
}

// ── TimelineItem(server payload.item/... 形态) ─────────────────────────

internal fun JsonObject.toTimelineItem(): TimelineItem? {
    val id = (this["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    val sessionId = (this["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val type = (this["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    val status = (this["status"] as? JsonPrimitive)?.contentOrNull ?: "pending"
    val role = stringOrNull("role")
    val content = this["content"]
    val source = (this["source"] as? JsonObject)?.toTimelineSource()
    val orderSeq = (this["orderSeq"] as? JsonPrimitive)?.longOrNull ?: 0L
    val revision = (this["revision"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
    val contentHash = stringOrNull("contentHash")
    val createdAt = stringOrNull("createdAt")
    val updatedAt = stringOrNull("updatedAt")
    val completedAt = stringOrNull("completedAt")
    val updatedSeq = (this["updatedSeq"] as? JsonPrimitive)?.longOrNull ?: 0L
    return TimelineItem(
        id = id,
        sessionId = sessionId,
        type = type,
        status = status,
        role = role,
        content = content,
        source = source,
        orderSeq = orderSeq,
        revision = revision,
        contentHash = contentHash,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        updatedSeq = updatedSeq,
        raw = this,
    )
}

internal fun JsonObject.toTimelineSource(): TimelineSource? {
    val runtime = stringOrNull("runtime")
    val sessionId = stringOrNull("sessionId")
    val itemId = stringOrNull("itemId")
    val itemType = stringOrNull("itemType")
    val event = stringOrNull("event")
    val derivedKey = stringOrNull("derivedKey")
    val clientMessageId = stringOrNull("clientMessageId")
    // 全空也允许存在 —— server 端 source 是 `Platform`/`codex` 时通常只有 runtime。
    return TimelineSource(
        runtime = runtime,
        sessionId = sessionId,
        itemId = itemId,
        itemType = itemType,
        event = event,
        derivedKey = derivedKey,
        clientMessageId = clientMessageId,
    )
}

// ── TimelineSnapshot payload:{ items:[...], hasMore?, nextSeq? } ───────

internal fun JsonObject.toTimelineSnapshot(): ProtocolTimelineSnapshot {
    val items = (this["items"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.toTimelineItem() }
        ?: emptyList()
    val nextSeq = (this["nextSeq"] as? JsonPrimitive)?.longOrNull ?: 0L
    val hasMore = boolOrFalse("hasMore")
    return ProtocolTimelineSnapshot(items = items, nextSeq = nextSeq, hasMore = hasMore)
}

// ── NoticeIn ──────────────────────────────────────────────────────────

internal fun JsonObject.toNotice(): NoticeIn? {
    val noticeId = (this["noticeId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val type = (this["type"] as? JsonPrimitive)?.contentOrNull ?: "notification"
    val sessionId = (this["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val source = (this["source"] as? JsonObject)?.toNoticeSource() ?: NoticeSource()
    val title = (this["title"] as? JsonPrimitive)?.contentOrNull ?: ""
    val message = stringOrNull("message")
    val severity = (this["severity"] as? JsonPrimitive)?.contentOrNull ?: "info"
    val status = (this["status"] as? JsonPrimitive)?.contentOrNull ?: "open"
    val interactionType = stringOrNull("interactionType")
    val blocking = (this["blocking"] as? JsonObject)?.let {
        val targetId = (it["targetId"] as? JsonPrimitive)?.contentOrNull ?: ""
        NoticeBlocking(
            scope = (it["scope"] as? JsonPrimitive)?.contentOrNull ?: "session",
            targetId = targetId,
        )
    }
    val responseRequired = boolOrFalse("responseRequired")
    val actions = (this["actions"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.toNoticeAction() }
        ?: emptyList()
    val context = (this["context"] as? JsonObject) ?: JsonObject(emptyMap())
    val metadata = (this["metadata"] as? JsonObject) ?: JsonObject(emptyMap())
    val expiresAt = stringOrNull("expiresAt")
    val revision = (this["revision"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
    val createdAt = stringOrNull("createdAt")
    val resolvedAt = stringOrNull("resolvedAt")
    val updatedSeq = (this["updatedSeq"] as? JsonPrimitive)?.longOrNull ?: 0L
    return NoticeIn(
        noticeId = noticeId,
        type = type,
        sessionId = sessionId,
        source = source,
        title = title,
        message = message,
        severity = severity,
        status = status,
        interactionType = interactionType,
        blocking = blocking,
        responseRequired = responseRequired,
        actions = actions,
        context = context,
        metadata = metadata,
        expiresAt = expiresAt,
        revision = revision,
        createdAt = createdAt,
        resolvedAt = resolvedAt,
        updatedSeq = updatedSeq,
        raw = this,
    )
}

internal fun JsonObject.toNoticeSource(): NoticeSource {
    return NoticeSource(
        runtime = stringOrNull("runtime"),
        component = stringOrNull("component"),
        approvalId = stringOrNull("approvalId"),
        timelineItemId = stringOrNull("timelineItemId"),
        operationId = stringOrNull("operationId"),
    )
}

internal fun JsonObject.toNoticeAction(): NoticeAction? {
    val actionId = (this["actionId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val label = (this["label"] as? JsonPrimitive)?.contentOrNull ?: actionId
    val style = (this["style"] as? JsonPrimitive)?.contentOrNull ?: "secondary"
    val inputObj = this["input"] as? JsonObject
    val required = (inputObj?.get("required") as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false
    val schemaObj = inputObj?.get("schema") as? JsonObject
    val uiSchemaObj = inputObj?.get("uiSchema") as? JsonObject
    return NoticeAction(
        actionId = actionId,
        label = label,
        style = style,
        input = NoticeActionInput(
            required = required,
            schema = schemaObj,
            uiSchema = uiSchemaObj,
        ),
    )
}

// ── SessionRuntimeState ───────────────────────────────────────────────

internal fun JsonObject.toRuntimeState(): SessionRuntimeState? {
    val sessionId = (this["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val runtime = (this["runtime"] as? JsonPrimitive)?.contentOrNull ?: return null
    return SessionRuntimeState(
        sessionId = sessionId,
        runtime = runtime,
        runtimeId = stringOrNull("runtimeId"),
        externalSessionId = stringOrNull("externalSessionId"),
        status = stringOrNull("status") ?: "idle",
        selections = (this["selections"] as? JsonObject) ?: JsonObject(emptyMap()),
        statusReason = stringOrNull("statusReason"),
        error = this["error"] as? JsonObject,
        metadata = (this["metadata"] as? JsonObject) ?: JsonObject(emptyMap()),
        updatedSeq = (this["updatedSeq"] as? JsonPrimitive)?.longOrNull ?: 0L,
        createdAt = stringOrNull("createdAt"),
        updatedAt = stringOrNull("updatedAt"),
    )
}

// ── DashboardSnapshot(payload 是 dashboard.snapshot 顶层) ──────────────

internal fun JsonObject.toDashboardSnapshot(): DashboardSnapshot {
    val connectors = (this["connectors"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val projects = (this["projects"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val sessions = (this["sessions"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val runtimes = (this["runtimes"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val sessionPagesObj = this["sessionPages"] as? JsonObject
    val active = (sessionPagesObj?.get("active") as? JsonObject) ?: JsonObject(emptyMap())
    val archived = (sessionPagesObj?.get("archived") as? JsonObject) ?: JsonObject(emptyMap())
    return DashboardSnapshot(
        type = (this["type"] as? JsonPrimitive)?.contentOrNull ?: "dashboard.snapshot",
        connectors = connectors,
        projects = projects,
        sessions = sessions,
        runtimes = runtimes,
        sessionPages = DashboardSessionPages(active = active, archived = archived),
        serverTime = stringOrNull("serverTime").orEmpty(),
    )
}

// ── helpers ──────────────────────────────────────────────────────────

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolOrFalse(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false
