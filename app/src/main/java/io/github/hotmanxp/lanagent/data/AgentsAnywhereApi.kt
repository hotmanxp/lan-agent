// data/AgentsAnywhereApi.kt — REST 客户端 for Agents-Anywhere server.
//
// baseUrl 形如 `http://192.168.1.10:8000`(server 默认端口 8000,
// 见 server/agent_server/main.py 的 uvicorn 绑定)。accessToken 走
// `Authorization: Bearer <token>`(`deps.py:current_user_id`)。
//
// 覆盖端点(全部来自 server/agent_server/api/*):
//   POST /ws-ticket                                            client_ws.py:21
//   GET  /sessions/list                                        sessions.py:458
//   GET  /sessions/{id}/timeline?afterSeq=<int>                sessions.py:827
//   GET  /sessions/{id}/snapshot                               sessions.py:884
//   POST /sessions/{id}/attachments   multipart                 sessions_fs.py:106
//   POST /sessions/{id}/runtime/messages  body=MessageCreate    sessions.py:1351
//   POST /sessions/{id}/runtime/interrupt                      sessions.py:1386
//   POST /sessions/{id}/runtime/steer     body=SessionSteer     sessions.py:1420
//   POST /sessions/{id}/runtime/notices/{nid}/respond          sessions.py:1477
//   POST /sessions/{id}/takeover                               sessions.py:1146
//   DELETE /sessions/{id}/takeover                             sessions.py:1178
//   POST /auth/mobile-login/qr                                 auth.py:433
//   POST /auth/mobile-login/status  body={loginToken}           auth.py:513
//   POST /auth/mobile-login/exchange body={userId, loginToken}  auth.py:462
//
// 序列化策略跟 AgentApi.kt 对齐:ignoreUnknownKeys + coerceInputValues +
// explicitNulls=false,这样服务端偶发漏字段 / 字段类型分歧不会让整页崩。
package io.github.hotmanxp.lanagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AgentsAnywhereApi(
    private val baseUrl: String,
    /** Bearer access token,空串 = 不带 Authorization。 */
    private val accessToken: String,
    /** 单一稳定 clientId,WS ticket 也用它。 */
    private val clientId: String,
) {
    private val client = SharedHttpClient

    companion object {
        /**
         * **单例** OkHttpClient —— 跨所有 `AgentsAnywhereApi` 实例共享。
         *
         * `AgentsAnywhereScreen` 里 `remember(baseUrl, accessToken, clientId)` 会在
         * 用户每次保存配置时重建 Api 实例;若每个实例都 new 一个 OkHttpClient,
         * dispatcher / connection pool / 内部线程池会跟着泄漏(旧 client
         * 要等 GC 才释放,但线程池不是 GC 的)。
         *
         * OkHttp 文档明确推荐**进程级共享一个 client** —— 它是线程安全的,
         * connection pool 复用也是它最大的性能卖点。
         */
        private val SharedHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun urlFor(path: String): String {
        val base = baseUrl.trimEnd('/')
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "$base$normalized"
    }

    private fun authHeader(builder: Request.Builder): Request.Builder {
        if (accessToken.isNotBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        return builder
    }

    private fun parseErrorBody(body: String): String = runCatching {
        val obj = json.parseToJsonElement(body) as? JsonObject
        (obj?.get("detail") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (obj?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull() ?: body.take(200)

    private suspend inline fun <reified T> execute(req: Request): T = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, parseErrorBody(resp.peekBody(4096).string()))
            }
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) {
                @Suppress("UNCHECKED_CAST")
                return@use Unit as T
            }
            json.decodeFromString<T>(raw)
        }
    }

    private suspend fun executeRaw(req: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, parseErrorBody(resp.peekBody(4096).string()))
            }
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) return@use JsonObject(emptyMap())
            json.parseToJsonElement(raw) as? JsonObject
                ?: throw IOException("expected JSON object, got: ${raw.take(80)}")
        }
    }

    private fun postJson(path: String, body: JsonObject): Request {
        val builder = Request.Builder().url(urlFor(path))
        authHeader(builder)
        return builder.post(
            body.toString().toRequestBody("application/json".toMediaType())
        ).build()
    }

    private fun postBody(path: String, body: String): Request {
        val builder = Request.Builder().url(urlFor(path))
        authHeader(builder)
        return builder.post(body.toRequestBody("application/json".toMediaType())).build()
    }

    private fun getJson(path: String): Request {
        val builder = Request.Builder().url(urlFor(path))
        authHeader(builder)
        return builder.get().build()
    }

    private fun deleteJson(path: String): Request {
        val builder = Request.Builder().url(urlFor(path))
        authHeader(builder)
        return builder.delete().build()
    }

    // ── Ticket 鉴权(client_ws.py:21) ─────────────────────────────────

    suspend fun fetchWsTicket(scope: WsTicketScope): WsTicketResponse {
        val req = postJson(
            "/ws-ticket",
            json.encodeToJsonElement(WsTicketRequest.serializer(), WsTicketRequest(clientId, scope))
                .let { it as JsonObject },
        )
        return execute<WsTicketResponse>(req)
    }

    // ── Session 列表(sessions.py:458) ────────────────────────────────

    suspend fun listSessions(): List<SessionSummary> {
        val req = getJson("/sessions/list")
        val resp = execute<SessionListResponse>(req)
        return resp.sessions.map { it.toSessionSummary() }.filterNotNull()
    }

    // ── Connectors / Projects(sessions.py / connectors.py / projects.py) ─
    //
    // 这两条是 dashboard WS `dashboard.snapshot` 同样字段的 REST 兜底:WS 还没
    // 连上时(用户点"手动刷新"),或 server 临时推送链路断,客户端可以拿它们
    // 重建本地视图。
    // **没有 `/runtimes/list` 这种聚合端点** —— server `dashboard_stream.py:55`
    // 直接 `db.list_user_device_runtimes(user_id)`,REST 这层没有公开入口;
    // 想看 runtimes 只能等 dashboard WS 或按 connector 调
    // `GET /connectors/{id}/runtimes`(那是单 connector 的,不是 user 级)。

    @Serializable
    data class ConnectorListBody(val connectors: List<JsonObject> = emptyList(), val serverTime: String? = null)

    @Serializable
    data class ProjectListBody(val projects: List<JsonObject> = emptyList(), val serverTime: String? = null)

    suspend fun listConnectors(): ConnectorListBody {
        return execute<ConnectorListBody>(getJson("/connectors"))
    }

    suspend fun listProjects(): ProjectListBody {
        return execute<ProjectListBody>(getJson("/projects"))
    }

    // ── Dashboard full snapshot(服务端没有直接给一个非 WS 聚合端点,
    //    这里折中拉三个 REST 端点 + 复用 sessions/list,合并成 DashboardSnapshot) ─

    suspend fun fetchDashboardSnapshot(): DashboardSnapshot {
        val connectors = listConnectors().connectors
        val projects = listProjects().projects
        val sessions = listSessions().map { it.raw }
        // runtimes 没 user 级 REST 端点,留空 —— UI 顶部提示「dashboard WS 才能看 runtimes」。
        return DashboardSnapshot(
            connectors = connectors,
            projects = projects,
            sessions = sessions,
            runtimes = emptyList(),
            serverTime = null,
        )
    }

    // ── Snapshot / timeline(sessions.py:827 / :884) ──────────────────

    suspend fun fetchSnapshot(sessionId: String): ProtocolSessionSnapshotResponse {
        val req = getJson("/sessions/$sessionId/snapshot")
        return execute<ProtocolSessionSnapshotResponse>(req)
    }

    suspend fun fetchTimeline(sessionId: String, afterSeq: Long = 0L): ProtocolTimelineResponse {
        // server alias 是 `afterSeq`(FastAPI `Query(0, alias="afterSeq")`,
        // 见 server `sessions.py:830`),不是 `after`。
        val req = getJson("/sessions/$sessionId/timeline?afterSeq=$afterSeq")
        return execute<ProtocolTimelineResponse>(req)
    }

    // ── 发送 / 中断 / steer / 通知响应 / takeover ────────────────────

    suspend fun sendMessage(
        sessionId: String,
        content: String,
        attachments: List<AttachmentRef> = emptyList(),
        clientMessageId: String? = null,
    ): RpcResponsePayload {
        val req = postJson(
            "/sessions/$sessionId/runtime/messages",
            json.encodeToJsonElement(
                MessageCreateRequest.serializer(),
                MessageCreateRequest(content, attachments, clientMessageId)
            ).let { it as JsonObject }
        )
        return execute<RpcResponsePayload>(req)
    }

    suspend fun interrupt(sessionId: String): RpcResponsePayload {
        val req = postJson("/sessions/$sessionId/runtime/interrupt", JsonObject(emptyMap()))
        return execute<RpcResponsePayload>(req)
    }

    suspend fun steer(
        sessionId: String,
        content: String,
        attachments: List<AttachmentRef> = emptyList(),
        clientMessageId: String? = null,
    ): RpcResponsePayload {
        val req = postJson(
            "/sessions/$sessionId/runtime/steer",
            json.encodeToJsonElement(
                SessionSteerRequest.serializer(),
                SessionSteerRequest(content, attachments, clientMessageId)
            ).let { it as JsonObject }
        )
        return execute<RpcResponsePayload>(req)
    }

    suspend fun respondNotice(
        sessionId: String,
        noticeId: String,
        actionId: String,
        input: JsonObject? = null,
    ): RpcResponsePayload {
        val body = buildJsonObject {
            put("actionId", actionId)
            if (input != null) put("input", input)
        }
        val req = postJson(
            "/sessions/$sessionId/runtime/notices/$noticeId/respond",
            body
        )
        return execute<RpcResponsePayload>(req)
    }

    suspend fun enableTakeover(sessionId: String): JsonObject {
        val req = postJson("/sessions/$sessionId/takeover", JsonObject(emptyMap()))
        return executeRaw(req)
    }

    suspend fun disableTakeover(sessionId: String): JsonObject {
        val req = deleteJson("/sessions/$sessionId/takeover")
        return executeRaw(req)
    }

    // ── 附件上传(sessions_fs.py:106 `POST /sessions/{id}/attachments`) ───
    //
    // server 端走 `files: list[UploadFile] = File(...)` —— multipart/form-data,
    // 字段名固定 `files`,**一次请求最多 5 个文件 / 单个 25 MiB**,超出后端会
    // 返 422 / 413。客户端只能传「已读取到内存的字节」;Uri → byte[] 由
    // UI 层用 ContentResolver 做完再调进来。
    //
    // 返回的 `fileId` 是真附件 id,后续 [sendMessage] / [steer] 把它包装成
    // [AttachmentRef] 投递 —— server 端靠它把附件喂给 connector。

    suspend fun uploadAttachment(
        sessionId: String,
        bytes: ByteArray,
        filename: String,
        mediaType: String,
    ): UploadedAttachment {
        val builder = Request.Builder().url(urlFor("/sessions/$sessionId/attachments"))
        authHeader(builder)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files",
                filename,
                bytes.toRequestBody(mediaType.toMediaType()),
            )
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(builder.post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw HttpException(resp.code, parseErrorBody(resp.peekBody(4096).string()))
                }
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) {
                    throw IOException("upload returned empty body")
                }
                val parsed = json.decodeFromString(UserUploadResponse.serializer(), raw)
                parsed.attachments.firstOrNull()
                    ?: throw IOException("upload returned no attachments")
            }
        }
    }

    // ── Mobile-login(server `auth.py:433 / :513 / :462`) ───────────────
    //
    // 流程(对齐 server `routes/api/auth.py`):
    //   ① qr() → `{userId, loginToken, expiresAt}`,client 把 loginToken 编到
    //      一个 web URL 里让已登录设备打开 `/auth/mobile-login/confirm`
    //   ② status(token) → `{status: pending_scan|pending_web_confirm|approved|
    //      rejected|expired|consumed, …}`,approved 就 exchange
    //   ③ exchange(userId, token) → `{auth: {accessToken, …}, refreshToken,
    //      expiresAt}`,client 把 accessToken 落进 EncryptedPrefs(§5),refresh
    //      先暂存普通 DataStore(无加密 — token 已经过期就用新的 exchange 重
    //      走一遍 QR,这字段只是触发态载荷,不是凭据)。

    suspend fun mobileLoginQr(): MobileLoginQrResponse {
        val req = postBody("/auth/mobile-login/qr", "{}")
        return execute<MobileLoginQrResponse>(req)
    }

    suspend fun mobileLoginStatus(loginToken: String): MobileLoginStatusResponse {
        val body = json.encodeToString(
            MobileLoginStatusBody.serializer(),
            MobileLoginStatusBody(loginToken),
        )
        val req = postBody("/auth/mobile-login/status", body)
        return execute<MobileLoginStatusResponse>(req)
    }

    suspend fun mobileLoginExchange(
        userId: String,
        loginToken: String,
    ): MobileLoginExchangeResponse {
        val body = json.encodeToString(
            MobileLoginExchangeBody.serializer(),
            MobileLoginExchangeBody(userId, loginToken),
        )
        val req = postBody("/auth/mobile-login/exchange", body)
        return execute<MobileLoginExchangeResponse>(req)
    }
}