// data/SessionToolsApi.kt — 会话「工作区」面板的 HTTP + SSE 客户端(0.24.11)
//
// baseUrl 约定与 [AgentApi] 完全一致("http://host:port"),但**刻意不并进
// AgentApi**:那已经 500+ 行且是"会话流"专用,这里三条数据源的生命周期与
// 打开/关闭方式都不同(面板一开一关之间独立)。合成一个大类只会让两边互相
// 牵连。
//
// 覆盖的端点(全部来自 opencc-web packages/zai/src/server/routes/):
//   GET  /api/fs/list?dir=                        fs.ts:345      列目录
//   POST /api/git {action:status}                 git.ts:163/180 git 状态
//   POST /api/git {action:diff}                   git.ts:163/196 单文件 diff
//   POST /api/bash/repl/:sid/exec                 bashRepl.ts:29 执行命令
//   GET  /api/bash/repl/:sid/events               bashRepl.ts:73 SSE 输出
//   POST /api/bash/repl/:sid/abort                bashRepl.ts:96 中断
//
// 同样**不需要 token**:/api/* 无鉴权中间件(与 AgentApi 顶部注释同因)。
//
// ── 安全边界(重要,别当成"顺手加的功能")──────────────────────────────
// `POST /api/bash/repl/:sid/exec` 是**任意 shell 命令执行**,而且是**从手机
// 触发的**。zai 默认绑 127.0.0.1(`server/index.ts:77`),但 `--lan` 模式绑
// 0.0.0.0 且 /api 下没有鉴权中间件 —— 而 lan-agent 按惯例正是连 --lan 实例。
// 也就是说这条路径今天就已经是"局域网内的远程命令执行",本次只是给它加了个
// UI,没有扩大也没有缩小暴露面。缓解(默认 bind 收紧 / 加 token / 只在
// localhost 起实例)属于 opencc-web 侧改动,不在本客户端范围内。
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

class SessionToolsApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** SSE 专用。**readTimeout 必须为 0** —— bashRepl.ts:84 有 15s 心跳,
     * 任何有界 readTimeout 都会在空闲时把长连接掐掉。 */
    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 执行命令专用(wait 模式)。同样 **readTimeout = 0**:`sleep 600` 这类命令
     * 会让这条连接挂 10 分钟,15s 的默认 readTimeout 必然把它掐掉。
     * 「停不下来」不靠超时兜底 —— 靠 [abortCommand],服务端 abort 后 child
     * 退出,completion resolve,这条请求自己就返回了。 */
    private val commandClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // 与 AgentApi 的 Json 逐项一致,否则测试会"因为配置不同而假绿"。
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

    private fun Request.Builder.postJson(body: JsonObject): Request =
        post(body.toString().toRequestBody(JSON)).build()

    private suspend inline fun <reified T> execute(req: Request): T = executeWith(client, req)

    private suspend inline fun <reified T> executeWith(
        http: OkHttpClient,
        req: Request,
    ): T = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, text.take(200))
            json.decodeFromString(text)
        }
    }

    // ===== 文件 =====

    /**
     * 问出 instance cwd。文件栏把它当列表根,并用它把相对路径拼成绝对路径
     * 给预览层(见 [absUnderCwd] 的来龙去脉)。
     */
    suspend fun systemCwd(): SystemCwd =
        execute(Request.Builder().url(urlFor("/api/system")).build())

    /**
     * 列目录。`dir` 是**相对 instance cwd** 的路径,`""` 即根。
     *
     * 越界由服务端挡(403,fs.ts:350),客户端不用也不该自己判。
     */
    suspend fun fsList(dir: String): FsList {
        val encoded = URLEncoder.encode(dir, "UTF-8")
        return execute(Request.Builder().url(urlFor("/api/fs/list?dir=$encoded")).build())
    }

    // ===== git =====

    suspend fun gitStatus(): GitStatus = execute(
        Request.Builder().url(urlFor("/api/git"))
            .postJson(buildJsonObject { put("action", "status") })
    )

    /**
     * 单文件 diff。
     *
     * ⚠️ 未跟踪文件(`xy == "??"`)**会拿到空 diff 且 `ok` 为真** ——
     * `git.ts:209` 把 `isUntracked` 硬编码成 false,而 `svcDiff` 跑的是
     * `git diff`(按定义不含未跟踪文件)。这不是错误,调用方要靠
     * [GitStatusEntry.isUntracked] 自己判并说明,别把空 diff 当成点错了。
     */
    suspend fun gitDiff(path: String, staged: Boolean): GitDiff = execute(
        Request.Builder().url(urlFor("/api/git"))
            .postJson(
                buildJsonObject {
                    put("action", "diff")
                    put("path", path)
                    put("staged", staged)
                }
            )
    )

    // ===== Bash REPL =====

    /**
     * 执行一条命令并**等它跑完**(`wait: true`)。
     *
     * 用 wait 而不是 fire-and-forget:bashRepl.ts:49-58 在 wait 模式下会补上
     * `code` / `signal` / `durationMs`,于是"退出码徽标"和"停止键何时可点"
     * 都不用自己追 `exit` 事件,一次 REST 往返就定了。代价是这条 HTTP 连接
     * 会一直挂着直到命令结束,所以走 [commandClient](readTimeout = 0)。
     */
    suspend fun runCommand(sessionId: String, command: String, cwd: String?): ReplExecResult =
        executeWith(
            commandClient,
            Request.Builder().url(urlFor("/api/bash/repl/$sessionId/exec?wait=1"))
                .postJson(
                    buildJsonObject {
                        put("command", command)
                        cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
                    }
                )
        )

    /** 中断当前命令。服务端在没有命令在跑时回 409(`bashRepl.ts:100`)。 */
    suspend fun abortCommand(sessionId: String) {
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(urlFor("/api/bash/repl/$sessionId/abort"))
                    .postJson(JsonObject(emptyMap()))
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, resp.body?.string().orEmpty().take(200))
            }
        }
    }

    /**
     * 订阅命令输出(SSE)。
     *
     * 生命周期契约(UI 那边必须照做):**面板打开时建流、关闭时断流**;切到别的
     * 栏**不要**断 —— 断了会重连抖动,而且 sessionId 会被反复重键。
     *
     * 取消语义的坑与 `AgentApi.eventStream` 完全相同(见该函数 415-426 行的
     * 注释):只 `call.cancel()` 不够,阻塞读抛出的异常会被下面的 catch 吞掉
     * 然后循环继续往下走**重新建连**,于是 collect 早已结束、后台线程却永远
     * 重连下去。所以循环条件、catch、退避睡眠三处都看 `cancelled`。
     *
     * 心跳是 `: heartbeat` 注释行(`bashRepl.ts:84`),和 eventStream 一样丢弃。
     */
    fun bashEvents(sessionId: String): Flow<ReplEvent> = callbackFlow {
        val currentCall = AtomicReference<Call?>(null)
        val cancelled = AtomicBoolean(false)
        var attempt = 0

        val worker = Thread({
            while (!cancelled.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val rq = Request.Builder()
                        .url(urlFor("/api/bash/repl/$sessionId/events"))
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .build()
                    val call = sseClient.newCall(rq)
                    currentCall.set(call)
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw HttpException(resp.code, "bash events")
                        attempt = 0
                        val src = resp.body?.source() ?: throw IOException("bash events: no body")
                        val data = StringBuilder()
                        while (!cancelled.get()) {
                            val line = src.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                val raw = data.toString()
                                if (raw.isNotEmpty()) {
                                    runCatching {
                                        json.decodeFromString<ReplEvent>(raw)
                                    }.getOrNull()?.let { trySend(it) }
                                }
                                data.setLength(0)
                            } else if (line.startsWith(":")) {
                                // 心跳注释行,丢弃
                            } else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).trim())
                            }
                            // id: / event: 这条流用不上(没有补发语义),忽略
                        }
                    }
                    currentCall.set(null)
                } catch (_: Throwable) {
                    currentCall.set(null)
                    if (cancelled.get() || Thread.currentThread().isInterrupted) break
                }
                if (cancelled.get() || Thread.currentThread().isInterrupted) break
                attempt++
                sleepCancellable(minOf(1_000L shl minOf(attempt, 4), 15_000L), cancelled)
            }
            close()
        }, "lan-agent-bash-sse-$sessionId").apply { isDaemon = true }

        worker.start()
        awaitClose {
            cancelled.set(true)
            currentCall.getAndSet(null)?.cancel()
        }
    }

    /** 可被打断的退避睡眠,同 `AgentApi.sleepCancellable`。 */
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
