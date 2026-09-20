// data/AgentApi.kt — zai Agent 会话的 HTTP + SSE 客户端。
//
// baseUrl 形如 "http://192.168.101.69:9201"(实例的 host:port),与
// InstancesApi 同一个约定。由 InstancesScreen 从实例快照的 host+port 派生。
//
// 覆盖的端点(全部来自 opencc-web packages/zai/src/server/routes/):
//   GET  /api/agent/sessions                     agent.ts:1991  会话列表
//   POST /api/agent/sessions                     agent.ts:2005  新建空会话
//   GET  /api/agent/sessions/:id                 agent.ts:2090  transcript 全文
//   GET  /api/agent/sessions/:id/state           sessionState.ts:59  cwd + v2Tasks
//   POST /api/agent/prompt                       agent.ts:1880  发消息(sessionId 走 body)
//   POST /api/agent/abort                        agent.ts:2198  中断(X-Session-Id 可选但推荐)
//   POST /api/agent/queue/cancel|edit|steer      agent.ts:2216/2244/2286
//   POST /api/agent/answer            + /reject  answer.ts:27/63
//   POST /api/agent/permission-response          permission.ts:42
//   POST /api/agent/approve           + /reject  approve.ts:73/102
//   GET  /api/agent/approve/file?toolUseId=      approve.ts:147
//   GET  /api/fs/preview?path=                   fs.ts:1020  文件预览(DisplayFiles)
//   POST /api/fs/reveal                          fs.ts:1125  在 Mac 上打开所在目录
//   GET  /api/event?sid=<sid>                    routes/event.ts:44  SSE
//   GET  /api/slash                              slash.ts:7   命令 + skill 清单
//   POST /api/agent/command                      command.ts:36 执行/展开一条命令
//
// **不需要 token**:服务端 /api/* 没有鉴权中间件(web 端带的 X-Zai-Token
// 服务端根本不读)。LAN 直连直接打即可。
//
// SSE 的两个反直觉点(踩过一次就别再踩):
//   1. 服务端 `writeSse` 的 `id:` 行写的是 **seq 数字**,而它自己的
//      `Last-Event-ID` 补发是按 **eventId 字符串** 查找 eventBus history 的
//      (`eventBus.ts:208 findIndex(e => e.eventId === lastEventId)`)。两者永远
//      对不上 → 每次重连都会退化成「全量 replay 最近 256 条」。所以**去重必须
//      在客户端自己做**,按 seq 单调丢弃(见 [eventStream])。
//   2. 首次连接(不带 Last-Event-ID)服务端会过滤掉 runtime.delta /
//      thinking / tool_call / tool_result(`STREAMING_REPLAY_EXCLUDE`),
//      因为这些内容已经落盘进 transcript,重放会和 hydrate 出来的历史重复。
//      重连时不过滤。这正是「先 hydrate transcript,再连 SSE」的顺序依据。
package io.github.hotmanxp.lanagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AgentApi(
    private val baseUrl: String,
    /**
     * 单个 call 的墙钟上限(`callTimeout`)。0 = 不设(默认)。
     *
     * 任务栏的跨实例聚合必须传一个短值:`/api/agent/sessions` 走的是阻塞的
     * `execute()`,而**协程的 withTimeout 取消不了阻塞在 socket 上的调用**
     * (阻塞块不响应协程取消)。OkHttp 的 callTimeout 由看门狗线程在到点时
     * 直接 `cancel()` 连接,阻塞的 execute() 会立刻抛 InterruptedIOException
     * ——这是唯一能让「一个不可达实例不拖住整轮」的机制。
     */
    callTimeoutMs: Long = 0L,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .apply { if (callTimeoutMs > 0) callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS) }
        .build()

    /**
     * SSE 专用 client。**readTimeout 必须为 0**(无限等待)—— 服务端心跳
     * 15s 一次,任何有界 readTimeout 都会在空闲时把长连接掐掉。
     */
    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun urlFor(path: String): String {
        val base = baseUrl.trimEnd('/')
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "$base$normalized"
    }

    private fun parseErrorBody(body: String): String =
        runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            obj?.str("error")
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

    private fun request(path: String, sessionId: String? = null): Request.Builder =
        Request.Builder()
            .url(urlFor(path))
            .apply { if (sessionId != null) header("X-Session-Id", sessionId) }

    private fun Request.Builder.getJson(): Request = get().build()

    private fun Request.Builder.postJson(body: JsonObject): Request =
        post(body.toString().toRequestBody(JSON)).build()

    private fun Request.Builder.postEmpty(): Request = post("{}".toRequestBody(JSON)).build()

    /**
     * PATCH + JSON body。用于 session 元信息变更(model / providerId / title
     * 等)。Android OkHttp 的 `patch()` 内部走 PATCH method,但 5.x 之前的
     * 版本会因「部分服务器拒绝 PATCH」抛 `MethodNotAllowedException` —
     * 我们这边是 zai 自家服务,直接走 PATCH 没问题。
     */
    private fun Request.Builder.patchJson(body: JsonObject): Request =
        patch(body.toString().toRequestBody(JSON)).build()

    // ===== 会话 =====

    suspend fun listSessions(): List<AgentSessionMeta> =
        execute<AgentSessionsResponse>(request("/api/agent/sessions").getJson()).sessions

    /** 新建一条空会话,立即返回 sessionId(对齐 web 端 sidebar 的 + 按钮)。 */
    suspend fun createSession(): String =
        execute<CreateSessionResponse>(request("/api/agent/sessions").postEmpty()).sessionId

    suspend fun readTranscript(sessionId: String): Transcript =
        execute<TranscriptResponse>(
            request("/api/agent/sessions/$sessionId").getJson()
        ).transcript

    suspend fun readState(sessionId: String): SessionStateResponse =
        execute(request("/api/agent/sessions/$sessionId/state").getJson())

    /**
     * PATCH /api/agent/sessions/:id — 用于切换 session 模型。
     * 服务端 schema 见 opencc-web `routes/agent.ts:2141` 的
     * `PatchSessionRequest`(目前支持 model / providerId / title / cwd /
     * permissionMode),本端只暴露 model 相关字段。
     *
     * `providerId == null` 时服务端保留原值(不会把它改成 null)——
     * 端点行为见 web 端 `useAgentStore.patchSessionModel` 注释。
     */
    suspend fun patchSession(
        sessionId: String,
        body: PatchSessionRequest,
    ): Unit = execute(
        request("/api/agent/sessions/$sessionId")
            .patchJson(buildJsonObject {
                put("model", body.model)
                body.providerId?.let { put("providerId", it) }
            })
    )

    /**
     * 拉服务端注册的模型清单(给 model picker 用)。
     * 端点是 `GET /api/agent/settings`,响应里 `models: ModelEntry[]`
     * 字段(对齐 web 端 `useAgentStore.loadSessions` 的处理路径)。
     *
     * 失败时降级返回空列表 —— picker 渲不渲染只是「无法切换」,不该把
     * 整个会话详情页拉崩。调用方拿到 `emptyList()` 时可以让 chip 显示
     * 「当前模型」+ 锁头图标,等下一次拉取成功再开放下拉。
     */
    suspend fun listAvailableModels(): List<ModelEntry> = runCatching {
        execute<AgentSettingsResponse>(request("/api/agent/settings").getJson()).models
    }.getOrDefault(emptyList())

    // ===== 命令面板(/命令 + Skill) =====

    /**
     * `GET /api/slash` —— 命令 + skill 合并清单(`routes/slash.ts`)。无参数、
     * 无鉴权。
     *
     * 失败降级成**空列表**而不是抛:拿不到清单只意味着「敲 `/` 不弹面板」,
     * 不该把整个会话页拉崩(跟 [listAvailableModels] 一个态度)。
     */
    suspend fun listSlashCommands(): List<SlashItem> = runCatching {
        execute<SlashListResponse>(request("/api/slash").getJson()).items
    }.getOrDefault(emptyList())

    /**
     * `POST /api/agent/command` —— 执行 / 展开一条命令(`routes/command.ts:36`)。
     *
     * 两个约定:
     *   - `sessionId` 走 **body**(不读 `X-Session-Id`)。服务端缺省会回落到
     *     `getCurrentSessionId()`,多实例场景别赌这个回落,一律显式带;
     *   - `args` 不在这里预截断 —— 服务端上限 1024 字符、超出自己截断并在
     *     `command.run` 事件里标 `argsTruncated`,截断责任只留一处。
     */
    suspend fun runCommand(
        sessionId: String,
        name: String,
        args: String = "",
    ): CommandRunResponse =
        execute(
            request("/api/agent/command").postJson(
                buildJsonObject {
                    put("name", name)
                    if (args.isNotEmpty()) put("args", args)
                    put("sessionId", sessionId)
                }
            )
        )

    // ===== 对话 =====
    /**
     * 发消息。`sessionId` 走 **body**(不是 header —— prompt 路由全程不读
     * X-Session-Id)。返回体里 `queued=true` 表示当前轮在跑、这条已入队。
     *
     * body 的构造逻辑抽到 [promptRequestBody] 里(可单测)。
     */
    suspend fun sendPrompt(
        sessionId: String,
        prompt: String,
        images: List<AttachedImage> = emptyList(),
    ): PromptResponse =
        execute(
            request("/api/agent/prompt")
                .postJson(promptRequestBody(sessionId, prompt, images.map { it.toPromptImage() }))
        )

    suspend fun abort(sessionId: String): AbortResponse =
        execute(request("/api/agent/abort", sessionId).postEmpty())

    // ===== 队列 =====

    suspend fun cancelQueued(sessionId: String, promptId: String): ActionResult =
        execute(
            request("/api/agent/queue/cancel").postJson(
                buildJsonObject { put("sessionId", sessionId); put("promptId", promptId) }
            )
        )

    suspend fun editQueued(sessionId: String, promptId: String, text: String): ActionResult =
        execute(
            request("/api/agent/queue/edit").postJson(
                buildJsonObject {
                    put("sessionId", sessionId); put("promptId", promptId); put("text", text)
                }
            )
        )

    suspend fun steerQueued(sessionId: String, promptId: String): ActionResult =
        execute(
            request("/api/agent/queue/steer").postJson(
                buildJsonObject { put("sessionId", sessionId); put("promptId", promptId) }
            )
        )

    // ===== ask / permission / approve =====

    /** 提交问询答案。`answers` 的 key 是**问题原文**,value 是选项 label。 */
    suspend fun submitAnswer(
        sessionId: String,
        toolUseId: String,
        answers: Map<String, String>,
    ): ActionResult =
        execute(
            request("/api/agent/answer", sessionId).postJson(
                buildJsonObject {
                    put("toolUseId", toolUseId)
                    putJsonObject("answers") { answers.forEach { (k, v) -> put(k, v) } }
                }
            )
        )

    suspend fun rejectAsk(sessionId: String, toolUseId: String, reason: String? = null): ActionResult =
        execute(
            request("/api/agent/answer/reject", sessionId).postJson(
                buildJsonObject {
                    put("toolUseId", toolUseId)
                    if (!reason.isNullOrBlank()) put("reason", reason)
                }
            )
        )

    suspend fun respondPermission(
        sessionId: String,
        toolUseId: String,
        allow: Boolean,
        message: String? = null,
    ): ActionResult =
        execute(
            request("/api/agent/permission-response", sessionId).postJson(
                buildJsonObject {
                    put("toolUseId", toolUseId)
                    put("decision", if (allow) "allow" else "deny")
                    if (!message.isNullOrBlank()) put("message", message)
                }
            )
        )

    /**
     * 文档审核决定。**拒绝必须带非空 comment**(服务端 schema 强制 1..2000),
     * 所以 UI 侧给「驳回」补一句默认理由,不要发空串。
     */
    suspend fun respondApprove(
        sessionId: String,
        toolUseId: String,
        approved: Boolean,
        comment: String? = null,
    ): ActionResult =
        execute(
            request("/api/agent/approve", sessionId).postJson(
                buildJsonObject {
                    put("toolUseId", toolUseId)
                    put("decision", if (approved) "approved" else "rejected")
                    if (!comment.isNullOrBlank()) put("comment", comment)
                }
            )
        )

    suspend fun readApproveFile(sessionId: String, toolUseId: String): ApproveFileResponse {
        val encoded = URLEncoder.encode(toolUseId, "UTF-8")
        return execute(request("/api/agent/approve/file?toolUseId=$encoded", sessionId).getJson())
    }

    // ===== 文件预览(DisplayFiles 工具,见 data/DisplayFiles.kt) =====

    /**
     * `GET /api/fs/preview?path=` —— 读一个本地文件的预览内容(`routes/fs.ts:1020`)。
     *
     * 响应按 kind 分岔:image → base64,text/html → 原文,binary → 只有元数据
     * (见 [FilePreview])。服务端把 `maxBytes` clamp 在 `[1024, 1 MiB]`,超出直接回
     * **413 ETOOBIG** —— 调用方要把 413 翻译成「文件过大」而不是「加载失败」,
     * 两者对用户的含义完全不同。
     *
     * 无鉴权(zai 只监听局域网 + `/api/` 下没有鉴权中间件,与其余端点一致)。
     */
    suspend fun previewFile(path: String): FilePreview =
        execute(request("/api/fs/preview?path=${URLEncoder.encode(path, "UTF-8")}").getJson())

    /** `POST /api/fs/reveal` —— 在 Mac 上打开该文件所在目录(macOS 走 `open -R`)。 */
    suspend fun revealFile(path: String): Boolean =
        execute<RevealResponse>(
            request("/api/fs/reveal").postJson(buildJsonObject { put("path", path) })
        ).ok

    // ===== SSE =====

    /**
     * 订阅某条会话的事件流,自带重连 + 指数退避 + seq 去重。
     *
     * 不传 `topics` —— 白名单会漏掉 `prompt.approve` / `prompt.permission` /
     * `queue.changed`(`eventBus.ts:223-245` 没有这几个 topic),只传 `sid`
     * 才拿得到完整事件面。
     *
     * **取消语义**:`awaitClose` 里既 `call.cancel()`(打醒阻塞中的
     * `readUtf8Line()`)又置 `cancelled` 标志。只 `cancel()` 是不够的 ——
     * `Call.cancel()` 不是线程中断,阻塞读抛出的异常会被 catch 吞掉,循环会
     * 继续往下走并**重新建连**,于是 collect 早已结束、后台线程却永远重连下去
     * (既漏 socket 又白耗电)。所以循环条件、catch 分支、退避睡眠三处都要看
     * 这个标志。
     */
    fun eventStream(sessionId: String): Flow<AgentEvent> = callbackFlow {
        val currentCall = AtomicReference<Call?>(null)
        val cancelled = AtomicBoolean(false)
        var lastSeq: Long? = null
        var attempt = 0

        val worker = Thread({
            while (!cancelled.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val rq = Request.Builder()
                        .url(urlFor("/api/event?sid=${URLEncoder.encode(sessionId, "UTF-8")}"))
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .apply { lastSeq?.let { header("Last-Event-ID", it.toString()) } }
                        .build()
                    val call = sseClient.newCall(rq)
                    currentCall.set(call)
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw HttpException(resp.code, "event stream")
                        attempt = 0
                        val src = resp.body?.source() ?: throw IOException("event stream: no body")
                        var frameId: String? = null
                        var frameType: String? = null
                        val data = StringBuilder()

                        while (!cancelled.get()) {
                            val line = src.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                val raw = data.toString()
                                if (raw.isNotEmpty()) {
                                    val payload = runCatching {
                                        json.parseToJsonElement(raw) as? JsonObject
                                    }.getOrNull()
                                    val type = frameType ?: payload?.str("type")
                                    if (payload != null && !type.isNullOrEmpty()) {
                                        val ev = AgentEvent(frameId, type, payload)
                                        // 服务端补发按 eventId 匹配、我们回的是 seq →
                                        // 永远 miss → 每次重连都是全量 replay。按 seq
                                        // 单调丢弃即可:重连窗口内漏掉的事件 seq 更大,
                                        // 会被保留;已应用的 seq 更小,被丢掉。
                                        val prev = lastSeq
                                        if (prev == null || ev.seq > prev) {
                                            if (ev.seq > (prev ?: 0L)) lastSeq = ev.seq
                                            trySend(ev)
                                        }
                                    }
                                }
                                frameId = null; frameType = null; data.setLength(0)
                            } else if (line.startsWith(":")) {
                                // 心跳注释行(`: heartbeat`),丢弃
                            } else if (line.startsWith("id:")) {
                                frameId = line.substring(3).trim().takeIf { it.isNotEmpty() }
                            } else if (line.startsWith("event:")) {
                                frameType = line.substring(6).trim().takeIf { it.isNotEmpty() }
                            } else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).trim())
                            }
                        }
                    }
                    currentCall.set(null)
                } catch (_: Throwable) {
                    currentCall.set(null)
                    if (cancelled.get() || Thread.currentThread().isInterrupted) break
                    // 连接失败 / 被服务端掐断 → 落到下面的退避重连
                }
                if (cancelled.get() || Thread.currentThread().isInterrupted) break
                attempt++
                val backoff = minOf(1_000L shl minOf(attempt, 4), 15_000L)
                sleepCancellable(backoff, cancelled)
            }
            close()
        }, "lan-agent-sse-$sessionId").apply { isDaemon = true }

        worker.start()
        awaitClose {
            cancelled.set(true)
            currentCall.getAndSet(null)?.cancel()
        }
    }

    /** 可被打断的退避睡眠 —— 分片 250ms 轮询取消标志,避免退出时白等 15s。 */
    private fun sleepCancellable(totalMs: Long, cancelled: AtomicBoolean) {
        var left = totalMs
        while (left > 0 && !cancelled.get()) {
            val step = minOf(250L, left)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            left -= step
        }
    }

    companion object {
        internal val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * `POST /api/agent/prompt` 的请求体。
 *
 * **文本不要自己塞进 `contentBlocks`**：服务端把 `prompt` 和 `contentBlocks`
 * 两个字段自己拼成 user content ——
 *   `blocks.length ? [...blocks, ...(text ? [{type:'text',text}] : [])] : text`
 * (agent.ts:1201-1204)。所以这里只放图片块 + 顶层 `prompt`，否则文本会被
 * 追加第二次，用户看到自己发的话重复一遍。
 *
 * 纯函数（无网络、无 Context、无 Uri），方便单测把上面这条契约钉死。
 */
internal fun promptRequestBody(
    sessionId: String,
    prompt: String,
    images: List<PromptImage>,
): JsonObject = buildJsonObject {
    // 只发图的场景是合法的（zod refine: prompt 或 contentBlocks 至少有一个），
    // 所以空 prompt 时干脆不传字段，别发一个空串过去。
    if (prompt.isNotBlank()) put("prompt", prompt)
    put("sessionId", sessionId)
    if (images.isNotEmpty()) {
        putJsonArray("contentBlocks") {
            images.forEach { img ->
                addJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        // 恒为 base64 + image/jpeg —— 见 ImageAttachments 头部
                        // 注释（media_type 是枚举 + magic bytes 预检 + 20mb 上限）。
                        put("type", "base64")
                        put("media_type", img.mediaType)
                        put("data", img.base64)
                    }
                }
            }
        }
    }
}
