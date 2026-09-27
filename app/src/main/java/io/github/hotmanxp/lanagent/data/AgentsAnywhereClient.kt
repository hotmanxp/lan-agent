// data/AgentsAnywhereClient.kt — 顶层 facade,把 API + WS + Prefs 拼成一个
// 调用方友好的对象。`AgentsAnywhereScreen` 只跟这个 facade 打交道。
//
// 状态机:
//   - **未连接**:没调过任何 `subscribe*`,或最后一次 `disconnect()` 之后。
//   - **dashboard WS 已订阅**:`subscribeDashboard()` 拉 ticket + 开 WS,
//     flow 持续吐 frame 直到断线;内置指数退避重连(1xxx-3xxx 关闭码 /
//     network failure),**4xxx 客户端错误直接退出 flow**(ticket 一次性消费,
//     重试只会持续消耗服务端 ticket,见 server `ws_tickets.py:87-89`)。
//   - **session WS 已订阅**:`subscribeSession(id)` 同上,但 path 不同。
//   - **任意时刻只允许一条订阅** —— 切走会 `disconnect()`,下次重新走
//     `subscribe*` 重建。
//
// 事件分类:每条 WS 文本帧被解析成 `JsonObject` 后转发到 `frames`
// (业务层 UI 渲染);连接/断开事件走 `lifecycle`。
package io.github.hotmanxp.lanagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/** 高层事件 —— 业务层 UI 只需订阅 `frames`,`lifecycle` 仅用于状态条。 */
sealed interface AgentsAnywhereEvent {
    /** 任意解析成功的 WS 文本帧。 */
    data class Incoming(
        val parsed: JsonObject,
        val raw: String,
    ) : AgentsAnywhereEvent

    /** 不可解析的文本帧 —— 留个 fallback,别静默吞掉。 */
    data class Unparseable(
        val raw: String,
    ) : AgentsAnywhereEvent

    /** 连接/断开/失败 —— UI 据此切换顶栏状态条。 */
    data class Lifecycle(
        val kind: Kind,
        val message: String,
        val httpCode: Int? = null,
    ) : AgentsAnywhereEvent {
        enum class Kind { Connected, Closed, Failure, Retrying }
    }
}

class AgentsAnywhereClient(
    val prefs: AgentsAnywherePrefs,
    val api: AgentsAnywhereApi,
    val ws: AgentsAnywhereWsClient,
) {
    /**
     * 订阅 dashboard WS。返回的 flow 完成时表示 WS 已断。
     *
     * **Built-in exponential backoff**;UI **不应该**在外面再套一层
     * `retryWhen` + `subscribe*` —— 重试节奏会跟内部退避打架,而且会让
     * `attempt` 状态机失效。
     *
     * 唯一例外:**RFC 6455 4xxx 关闭码**(e.g. 4401 ticket 失效、4403 forbidden)
     * 视为**客户端错误**,整个 flow 直接退出、不再 retry —— ticket 是
     * `getdel` 一次性消费(server `ws_tickets.py:87-89`),retry 只会
     * 持续消耗服务端 ticket,不会自愈。
     *
     * @param backoffMillis 退避基线,失败时 `2^n * backoffMillis`,封顶 30s;
     *        默认 1500ms,符合项目其他 SSE 客户端的惯例(`AgentApi` 的 SSE
     *        也有类似退避,见 pitfalls.md「SSE 三条血泪坑」)。
     */
    fun subscribeDashboard(
        baseUrl: String,
        accessToken: String,
        backoffMillis: Long = 1500L,
    ): Flow<AgentsAnywhereEvent> = subscribeWithTicket(
        path = "/dashboard/ws",
        baseUrl = baseUrl,
        accessToken = accessToken,
        ticketFetcher = { api.fetchWsTicket(WsTicketScope(dashboard = true)) },
        backoffMillis = backoffMillis,
    )

    fun subscribeSession(
        sessionId: String,
        baseUrl: String,
        accessToken: String,
        backoffMillis: Long = 1500L,
    ): Flow<AgentsAnywhereEvent> = subscribeWithTicket(
        path = "/sessions/$sessionId/ws",
        baseUrl = baseUrl,
        accessToken = accessToken,
        ticketFetcher = { api.fetchWsTicket(WsTicketScope(sessionId = sessionId)) },
        backoffMillis = backoffMillis,
    )

    private fun subscribeWithTicket(
        path: String,
        baseUrl: String,
        accessToken: String,
        ticketFetcher: suspend () -> WsTicketResponse,
        backoffMillis: Long,
    ): Flow<AgentsAnywhereEvent> = flow {
        var attempt = 0
        // `flow { }` 是 inlined lambda,但 `.collect { }` 不是 —— `return@flow`
        // 没法从内层 lambda 跳出来。改用 flag + `while (active)`:Closing/
        // Closed 收到 4xxx 时把 `active` 置 false,跳出循环,整个 flow 自然
        // 完成(`emit(Lifecycle.Failure)` 已经在 flag 翻转前完成)。
        var active = true
        while (active) {
            // 每次重连都拉新 ticket —— ticket 是 `getdel` 一次性消费
            // (server `ws_tickets.py:87-89`),重用会被拒。
            val ticket = ticketFetcher()
            emit(
                AgentsAnywhereEvent.Lifecycle(
                    kind = AgentsAnywhereEvent.Lifecycle.Kind.Connected,
                    message = "ws connected: $path",
                )
            )
            try {
                ws.subscribe(
                    baseUrl = baseUrl,
                    path = path,
                    ticket = ticket.ticket,
                    accessToken = accessToken,
                ).collect { frame ->
                    when (frame) {
                        is WsFrame.Text -> {
                            val parsed = ws.parseFrame(frame.text)
                            if (parsed != null) {
                                emit(AgentsAnywhereEvent.Incoming(parsed, frame.text))
                            } else {
                                emit(AgentsAnywhereEvent.Unparseable(frame.text))
                            }
                        }
                        is WsFrame.Binary -> {
                            // 协议层全文本,二进制忽略。
                        }
                        is WsFrame.Closing -> {
                            if (frame.code in 4000..4999) {
                                emit(
                                    AgentsAnywhereEvent.Lifecycle(
                                        kind = AgentsAnywhereEvent.Lifecycle.Kind.Failure,
                                        message = "client error ${frame.code} ${frame.reason} — 不再重连",
                                        httpCode = frame.code,
                                    )
                                )
                                active = false
                                return@collect
                            }
                            emit(
                                AgentsAnywhereEvent.Lifecycle(
                                    kind = AgentsAnywhereEvent.Lifecycle.Kind.Closed,
                                    message = "closing ${frame.code} ${frame.reason}",
                                    httpCode = frame.code,
                                )
                            )
                            return@collect
                        }
                        is WsFrame.Closed -> {
                            if (frame.code in 4000..4999) {
                                emit(
                                    AgentsAnywhereEvent.Lifecycle(
                                        kind = AgentsAnywhereEvent.Lifecycle.Kind.Failure,
                                        message = "client error ${frame.code} ${frame.reason} — 不再重连",
                                        httpCode = frame.code,
                                    )
                                )
                                active = false
                                return@collect
                            }
                            emit(
                                AgentsAnywhereEvent.Lifecycle(
                                    kind = AgentsAnywhereEvent.Lifecycle.Kind.Closed,
                                    message = "closed ${frame.code} ${frame.reason}",
                                    httpCode = frame.code,
                                )
                            )
                            return@collect
                        }
                        is WsFrame.Failure -> {
                            emit(
                                AgentsAnywhereEvent.Lifecycle(
                                    kind = AgentsAnywhereEvent.Lifecycle.Kind.Failure,
                                    message = frame.cause.message
                                        ?: frame.cause.javaClass.simpleName,
                                    httpCode = frame.httpCode,
                                )
                            )
                            throw frame.cause
                        }
                    }
                }
                // 正常结束(服务端 orderly close)→ 也尝试重连,免得用户必须手动。
                emit(
                    AgentsAnywhereEvent.Lifecycle(
                        kind = AgentsAnywhereEvent.Lifecycle.Kind.Closed,
                        message = "stream ended",
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emit(
                    AgentsAnywhereEvent.Lifecycle(
                        kind = AgentsAnywhereEvent.Lifecycle.Kind.Failure,
                        message = t.message ?: t.javaClass.simpleName,
                    )
                )
            }
            if (!active) break
            attempt += 1
            val sleep = (backoffMillis shl (attempt.coerceAtMost(5) - 1))
                .coerceAtMost(30_000L)
            emit(
                AgentsAnywhereEvent.Lifecycle(
                    kind = AgentsAnywhereEvent.Lifecycle.Kind.Retrying,
                    message = "retry in ${sleep}ms",
                )
            )
            delay(sleep)
        }
    }.flowOn(Dispatchers.IO)
}