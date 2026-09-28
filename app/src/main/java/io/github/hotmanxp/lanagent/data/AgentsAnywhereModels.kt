// data/AgentsAnywhereModels.kt — wire models for the Agents-Anywhere server
// (server/agent_server/core/protocol.py + agents_anywhere/api/*).
//
// 设计原则:
//   - 对齐 server 端的 Pydantic 模型,字段名(camelCase)直接抄 —— 服务端
//     `ProtocolWireModel` 用 `extra="allow"` + `by_alias=True` 序列化,
//     客户端必须容忍未知字段(forward-compat)。
//   - 不引入严格校验。wire 上偶发漏字段 / 字段值类型分化(JSON 数字 vs 字符串
//     时间戳、enum 字面量大小写漂移)都要兜住,否则一条事件就能把整页渲染崩
//     掉 —— 这跟 AgentApi.kt 的 `coerceInputValues = true` 是一回事。
//   - 推送契约(`ProtocolEventEnvelope`)是所有 WS 帧的统一壳,事件类型靠
//     `type` 字段分派;`payload` 字段故意不强类型,渲染层按 `type` 自取。
//   - **`keepalive` 不在 envelope 里**(server `sessions.py:1091` 直接
//     `send_json({"type":"keepalive",...})`),客户端要按 "type 唯一" 判别,
//     没有 envelope 也能认。
//
// Schema 来源(避免猜):
//   - server/agent_server/core/protocol.py            (ProtocolEventEnvelope 等)
//   - server/agent_server/core/models.py:945-1080     (TimelineItem / NoticeIn)
//   - server/agent_server/api/dashboard_stream.py     (dashboard.snapshot shape)
//   - server/agent_server/api/sessions.py:1036-1143   (session WS 事件形状)
//   - server/agent_server/api/auth.py                 (mobile login 系列)
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `ProtocolVersion = Literal["1.0"]`。 */
const val AGENTS_ANYWHERE_PROTOCOL_VERSION: String = "1.0"

// ── Ticket 鉴权 ─────────────────────────────────────────────────────────

@Serializable
data class WsTicketScope(
    /** 二选一:会话级订阅传 sessionId,dashboard 订阅传 `dashboard = true`。 */
    val sessionId: String? = null,
    val dashboard: Boolean = false,
)

@Serializable
data class WsTicketRequest(
    val clientId: String,
    val scope: WsTicketScope,
)

@Serializable
data class WsTicketResponse(
    val ticket: String,
    val expiresAt: String,
    val serverTime: String,
)

// ── Handshake(参考用,本任务不需要) ─────────────────────────────────────

@Serializable
data class ProtocolHandshakeRequest(
    val protocolVersions: List<String>,
    val connectorVersion: String,
    val runtimes: List<ProtocolRuntimeIdentity> = emptyList(),
)

@Serializable
data class ProtocolRuntimeIdentity(
    val runtime: String,
    val runtimeVersion: String,
)

@Serializable
data class ProtocolHandshakeResponse(
    val selectedProtocolVersion: String,
    val serverVersion: String,
)

// ── 推送契约:`ProtocolEventEnvelope` 是所有 session WS 帧的统一壳 ─────

@Serializable
data class ProtocolEventEnvelope(
    val protocolVersion: String = AGENTS_ANYWHERE_PROTOCOL_VERSION,
    val eventId: String,
    val sequence: Long,
    val cursor: String,
    val type: String,
    val sessionId: String,
    val emittedAt: String,
    /** 事件 payload —— 按 `type` 分派。故意不强类型,渲染层按需取字段。 */
    val payload: JsonObject = JsonObject(emptyMap()),
)

// ── REST snapshot 拉取的对齐子集(server core/models.py) ─────────────────

/**
 * `TimelineItem` —— 对齐 server `core/models.py:961`。
 *
 * 字段远超 opencc-web 那边的 `TranscriptEntry`,把每个常用字段都显式建模,
 * 渲染层才能直接取字段(不必每次解 metadata JSON),`raw` 兜底留给前向兼容。
 */
@Serializable
data class TimelineItem(
    val id: String,
    val sessionId: String,
    /** `message|tool|artifact|marker|system`。 */
    val type: String,
    /** `pending|running|waiting_approval|done|failed|cancelled|interrupted`。 */
    val status: String,
    /** `user|assistant|system|tool` —— 气泡分色靠它。 */
    val role: String? = null,
    /** server 端 schema 是 `Any`,实际多见 string / object,这里用 JsonElement。 */
    val content: JsonElement? = null,
    /** server `TimelineSource`;非消息类(系统/工具)也会有。 */
    val source: TimelineSource? = null,
    val orderSeq: Long = 0,
    val revision: Int = 1,
    val contentHash: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val completedAt: String? = null,
    val updatedSeq: Long = 0,
    /** 原始 JSON,渲染未覆盖字段直接取出(`ignoreUnknownKeys` 仍兜底 forward-compat)。 */
    val raw: JsonObject,
)

/** `TimelineSource` —— 对应 server `core/models.py:935`。 */
@Serializable
data class TimelineSource(
    /** `codex|claude|opencode|acp|dsh` 或 `platform`。 */
    val runtime: String? = null,
    val sessionId: String? = null,
    val itemId: String? = null,
    val itemType: String? = null,
    val event: String? = null,
    val derivedKey: String? = null,
    /** 客户端发消息时塞的 id —— 用它把乐观 UI 跟真实 item 去重。 */
    val clientMessageId: String? = null,
)

/** `SessionRuntimeState` —— 对应 server `core/models.py:812`。 */
@Serializable
data class SessionRuntimeState(
    val sessionId: String,
    val runtime: String,
    val runtimeId: String? = null,
    val externalSessionId: String? = null,
    val status: String = "idle",
    val selections: JsonObject = JsonObject(emptyMap()),
    val statusReason: String? = null,
    val error: JsonObject? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val updatedSeq: Long = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ProtocolTimelineSnapshot(
    val items: List<TimelineItem> = emptyList(),
    val nextSeq: Long = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class ProtocolTimelineResponse(
    val sessionId: String,
    val items: List<TimelineItem> = emptyList(),
    val nextSeq: Long = 0,
    val hasMore: Boolean = false,
    val serverTime: String,
)

@Serializable
data class ProtocolSessionSnapshotResponse(
    /** `SessionView` 显式建模的子集,其余 `raw` 兜底。 */
    val session: JsonObject,
    val state: SessionRuntimeState? = null,
    val timeline: ProtocolTimelineSnapshot,
    val approvals: List<JsonObject> = emptyList(),
    val notices: List<NoticeIn> = emptyList(),
    val effectiveCapabilities: JsonObject,
    val runtimeCapabilities: JsonObject,
    val catalogs: JsonObject = JsonObject(emptyMap()),
    val eventCursor: String,
    val serverTime: String,
)

// ── Notice —— 对应 server `core/models.py:1049`(`NoticeIn`) ─────────────

@Serializable
data class NoticeBlocking(
    val scope: String = "session",
    val targetId: String,
)

@Serializable
data class NoticeActionInput(
    val required: Boolean = false,
    @SerialName("schema")
    val schema: JsonObject? = null,
    val uiSchema: JsonObject? = null,
)

@Serializable
data class NoticeAction(
    val actionId: String,
    val label: String,
    /** `primary|secondary|danger`。 */
    val style: String = "secondary",
    val input: NoticeActionInput = NoticeActionInput(),
)

@Serializable
data class NoticeSource(
    val runtime: String? = null,
    val component: String? = null,
    val approvalId: String? = null,
    val timelineItemId: String? = null,
    val operationId: String? = null,
)

/**
 * `NoticeIn` —— 整段照搬 server 字段,常用项用强类型,兜底 `raw`。
 * 客户端视图模型(`Notice` 内部)不另立,直接复用。
 */
@Serializable
data class NoticeIn(
    val noticeId: String,
    /** `notification|interaction`。 */
    val type: String,
    val sessionId: String,
    val source: NoticeSource = NoticeSource(),
    val title: String,
    val message: String? = null,
    /** `info|success|warning|error` —— 卡片配色用。 */
    val severity: String = "info",
    /** `open|responding|response_accepted|resolving|resolved|closed|expired|cancelled|failed`。 */
    val status: String = "open",
    /** `approval|execution_error|confirmation|input_request|unknown` —— 卡片样式分派。 */
    val interactionType: String? = null,
    val blocking: NoticeBlocking? = null,
    val responseRequired: Boolean = false,
    val actions: List<NoticeAction> = emptyList(),
    val context: JsonObject = JsonObject(emptyMap()),
    val metadata: JsonObject = JsonObject(emptyMap()),
    val expiresAt: String? = null,
    val revision: Int = 1,
    val createdAt: String? = null,
    val resolvedAt: String? = null,
    val updatedSeq: Long = 0,
    val raw: JsonObject,
)

// ── Dashboard snapshot(`type="dashboard.snapshot"` —— 自定义形状,不在
//    protocol.py 里;形状见 api/dashboard_stream.py:56-89) ─────────────────

@Serializable
data class DashboardSessionPages(
    val active: JsonObject = JsonObject(emptyMap()),
    val archived: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DashboardSnapshot(
    val type: String = "dashboard.snapshot",
    val connectors: List<JsonObject> = emptyList(),
    val projects: List<JsonObject> = emptyList(),
    val sessions: List<JsonObject> = emptyList(),
    val runtimes: List<JsonObject> = emptyList(),
    val sessionPages: DashboardSessionPages = DashboardSessionPages(),
    /** WS 帧里一定有,REST 兜底为 null —— UI 自行显示「—」。 */
    val serverTime: String? = null,
)

// ── Keepalive ──────────────────────────────────────────────────────────

@Serializable
data class KeepaliveFrame(
    val type: String = "keepalive",
    val serverTime: String,
)

// ── 发送消息 / 操作 ────────────────────────────────────────────────────

/**
 * `AttachmentRef` —— 对应 server `core/models.py:703`,**只有 fileId**。
 * 客户端发附件必须先 POST `/sessions/{sid}/attachments` 拿到 fileId,
 * 把它塞这里。裸路径/裸文件名不是合法 attachment。
 *
 * 老协议里那些 `path/mime/displayName/size/metadata` 字段是上轮的误建,server
 * 会拒(`extra="forbid"` in MessageCreateRequest),本模型去掉以免误导。
 */
@Serializable
data class AttachmentRef(
    val fileId: String,
)

@Serializable
data class MessageCreateRequest(
    val content: String,
    val attachments: List<AttachmentRef> = emptyList(),
    val clientMessageId: String? = null,
)

@Serializable
data class SessionSteerRequest(
    val content: String,
    val attachments: List<AttachmentRef> = emptyList(),
    val clientMessageId: String? = null,
)

@Serializable
data class InteractionRespondRequest(
    val actionId: String,
    val input: JsonObject? = null,
)

/**
 * `RpcResponsePayload` —— 对齐 server `core/models.py:1355-1362`。
 *
 * 错误字段是嵌套的 `error: { code, message }`,**不是顶层 code/message**。
 * 上轮模型把这两个拍平到顶层是个 wire bug(解析永远不会成功,只能走兜底)。
 */
@Serializable
data class RpcResponsePayload(
    val ok: Boolean = true,
    val result: JsonElement? = null,
    val error: RpcError? = null,
    val serverTime: String? = null,
)

@Serializable
data class RpcError(
    val code: String,
    val message: String,
)

// ── Connectors / 设备(GET /connectors) ───────────────────────────────

/**
 * 远端 connector(= 一台桌面设备上跑的 Connector 进程)—— 对齐 Agents-Anywhere
 * `DevicesDtos.RemoteDevice`。
 *
 * 0.24.0 之前 `DashboardSnapshot.connectors` 是裸 `List<JsonObject>`,UI 只能
 * `toString` 整个对象,没法画「在线绿点 / 离线灰」和设备名。现在强类型化,
 * `raw` 保留原 JSON 备用(对齐 [SessionSummary.raw] 的做法)。
 */
@Serializable
data class AaConnector(
    val id: String,
    val name: String? = null,
    /** `online` / `offline` 等字面量 —— 服务端枚举大小写会漂,渲染层只判 != "offline"。 */
    val status: String? = null,
    val deviceOs: String? = null,
    val lastSeenAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** 判在线刻意宽松 —— 只认 `offline` 为离线,其余(空 / 未来新状态)当在线。 */
    val online: Boolean get() = !status.equals("offline", ignoreCase = true)
}

/**
 * `POST /connectors` 的响应 —— 一次性拿到 connector 与它的 token。
 *
 * `deviceToken` 只在这一刻出现,**之后再也取不到**(server 只存哈希),所以
 * 配对流程必须当场落盘到 [SecureTokenStore],不能只存 connectorId。
 */
@Serializable
data class AaConnectorCredential(
    val connector: AaConnector? = null,
    val deviceToken: String? = null,
    val tokenPrefix: String? = null,
    val serverTime: String? = null,
)

/** `POST /pairing/claim` 的请求体。 */
@Serializable
data class AaPairingClaimRequest(
    val code: String,
    val name: String,
    val serverUrl: String,
    val connectorId: String,
    val connectorToken: String,
)

/** `POST /pairing/claim` 的响应 —— 认领后的 connector。 */
@Serializable
data class AaPairingClaimResponse(
    val connector: AaConnector? = null,
    val serverTime: String? = null,
)

// ── 登录态(GET /auth/me) ────────────────────────────────────────────

/**
 * `GET /auth/me` 的响应 —— 拿 token 之后向 server 换权威用户信息。
 *
 * 设置栏展示的 userId / email / displayName / role 一律以这里为准,不信任
 * 本地存的那份(可能已被服务端改过)。
 */
@Serializable
data class AaMeResponse(
    val userId: String,
    val email: String? = null,
    val displayName: String? = null,
    val emailVerified: Boolean = false,
    val role: String? = null,
    val serverTime: String? = null,
)

/**
 * `POST /oauth/token`(form-encoded)的响应。
 *
 * ⚠️ wire 上是 **snake_case**(与 Agents-Anywhere `api/Auth.kt:203` 的
 * `toOAuthTokenResponse` 一致),和 `MobileLoginExchangeResponse` 的 camelCase
 * 嵌套 `auth` 对象是两套完全不同的形状 —— 别拿同一个 DTO 套两条登录流。
 * 服务端只给 `expires_in`(秒),不给绝对时间;要算到期时刻得自己加当前时间。
 */
@Serializable
data class AaOAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
)

// ── Session 列表(GET /sessions/list) ─────────────────────────────────

/** `SessionView` 关键字段抽出来,其余原样存在 `raw` 备用。 */
@Serializable
data class SessionSummary(
    val id: String,
    val connectorId: String? = null,
    val runtime: String? = null,
    val runtimeId: String? = null,
    val externalSessionId: String? = null,
    val title: String? = null,
    val cwd: String? = null,
    val status: String? = null,
    val takeover: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val unread: Boolean = false,
    val lastActivityAt: String? = null,
    val updatedSeq: Long = 0,
    /** 原始 JSON,渲染未覆盖字段直接取出。 */
    val raw: JsonObject,
)

@Serializable
data class SessionListResponse(
    val sessions: List<JsonObject> = emptyList(),
    val serverTime: String? = null,
)

// ── 通用 HTTP 错误壳(由 Api.parseErrorBody 解析) ───────────────────────

@Serializable
data class ApiErrorBody(
    val detail: String? = null,
    val error: String? = null,
)

// ── 附件上传(server `core/models.py:1163`) ─────────────────────────────

/**
 * 单条附件元数据 —— 对齐 server `UploadedAttachment`(见 server
 * `core/models.py:1163`)。客户端拿到这个之后只把 `fileId` 写进消息请求;
 * 名字 / 大小 / MIME 是显示用信息。
 */
@Serializable
data class UploadedAttachment(
    val fileId: String,
    val sessionId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val mediaType: String,
    val createdAt: String,
    val downloadUrl: String? = null,
    val openUrl: String? = null,
)

/**
 * `POST /sessions/{id}/attachments` 的响应壳 —— `attachments` 数组里每一项
 * 对应一个上传的文件(`files: list[UploadFile]`)。
 */
@Serializable
data class UserUploadResponse(
    val attachments: List<UploadedAttachment> = emptyList(),
    val serverTime: String? = null,
)

// ── Mobile-login QR(`POST /auth/mobile-login/qr`,server
//    `api/auth.py:433`)。返回值没有 `url` 字段 —— client 自行拼 query 串
//    给 web 端(`{baseUrl}/auth/mobile-login/confirm?loginToken=…&userId=…`)。 ───

@Serializable
data class MobileLoginQrResponse(
    val userId: String,
    val loginToken: String,
    val expiresAt: String,
    val serverTime: String? = null,
)

@Serializable
data class MobileLoginStatusBody(
    val loginToken: String,
)

@Serializable
data class MobileLoginStatusResponse(
    /** `pending_scan | pending_web_confirm | approved | rejected | expired | consumed`。 */
    val status: String,
    val userId: String? = null,
    val deviceName: String? = null,
    val expiresAt: String? = null,
    val requestedAt: String? = null,
    val approvedAt: String? = null,
    val serverTime: String? = null,
)

@Serializable
data class MobileLoginExchangeBody(
    val userId: String,
    val loginToken: String,
)

/**
 * `POST /auth/mobile-login/exchange` 响应 —— 把 `auth.accessToken` 写进
 * Prefs 作新 accessToken,同时保留 `refreshToken` 以便后续刷新。
 */
@Serializable
data class MobileLoginExchangeResponse(
    val auth: AuthEnvelope,
    val refreshToken: String,
    val expiresAt: String,
    val serverTime: String? = null,
)

@Serializable
data class AuthEnvelope(
    val userId: String,
    val accessToken: String,
    val tokenType: String = "bearer",
    val serverTime: String? = null,
)
