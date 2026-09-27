// data/AgentsAnywhereWsClient.kt — OkHttp WebSocket 客户端 for Agents-Anywhere.
//
// 协议要点(踩过再说):
//   1. **Server-push 方向**:server 通过 `run_server_push_until_disconnect`
//      (server_push_websocket.py)驱动,**客户端主动消息没有 schema**
//      (`wait_for_websocket_disconnect` 直接忽略任何非 disconnect 帧)。
//      所以发送走 REST,WS 只负责收。
//   2. **Ticket in query string**:服务端从 `websocket.query_params.get("ticket")`
//      消费 ticket(`dashboard_stream.py:105`、`sessions.py:1047`),一次消费
//      (Redis 用 `getdel`,本地用 `pop`)—— 同一个 ticket 不能重用。
//   3. **Keepalive**:15s 一条 `{"type":"keepalive","serverTime":"..."}`(`sessions.py:1088-1092`),
//      用来挡住网关空闲掐连接。任何有界 readTimeout 都会把这条心跳当超时——
//      readTimeout 必须 0。
//   4. **恢复信号**:`broker.recovery_signal`(`sessions.py:1063`)触发时
//      server 直接 `close(code=1012, reason="realtime interrupted; reconnect to recover")`
//      推客户端重连恢复。重连必须自己重取 ticket(已消费)。
//
// 实现选择:OkHttp 的 WebSocket API 自带 ping/pong + 自动重试,但**不重连**——
// 业务层 `callbackFlow` + `retryWhen` 控制重连节奏更直接。
package io.github.hotmanxp.lanagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 单帧 WS 消息。`Close` 表示服务端主动关闭(走 onClosing/onClosed);
 * `Failure` 表示网络异常 —— 区分两者有利于决定是否重连:
 * 4xxx 关闭码通常说明客户端错误,不该硬重试。
 */
sealed interface WsFrame {
    data class Text(val text: String) : WsFrame
    data class Binary(val bytes: ByteString) : WsFrame
    data class Closing(val code: Int, val reason: String) : WsFrame
    data class Closed(val code: Int, val reason: String) : WsFrame
    data class Failure(val cause: Throwable, val httpCode: Int?) : WsFrame
}

class AgentsAnywhereWsClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // WS 长连接必须有界 readTimeout 才能让心跳前的不确定期不卡住 —
        // 15s 心跳 + 5s 缓冲 = 20s 上限。
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * 订阅一个 WS 端点,返回 `Flow<WsFrame>`。**外层可包 `retryWhen` 实现重连**,
     * 单次订阅断线即结束。
     *
     * @param baseUrl 形如 `http://host:port`,内部按 ws/wss 升级。
     * @param path    形如 `/dashboard/ws` 或 `/sessions/{id}/ws`,query 由
     *                本函数挂 `?ticket=`。
     * @param ticket  ticket 字符串,由 `AgentsAnywhereApi.fetchWsTicket()` 取得。
     * @param accessToken Bearer access token;空串 = 不带 Authorization。
     */
    fun subscribe(
        baseUrl: String,
        path: String,
        ticket: String,
        accessToken: String,
    ): Flow<WsFrame> = channelFlow {
        val httpUrlString = baseUrl.trimEnd('/').let {
            val scheme = if (it.startsWith("https://")) "wss://" else "ws://"
            scheme + it.removePrefix("http://").removePrefix("https://")
        }
        val fullUrl = "$httpUrlString$path?ticket=$ticket"
        val reqBuilder = Request.Builder().url(fullUrl)
        if (accessToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $accessToken")
        }
        val req = reqBuilder.build()

        // 用独立变量捕获 ws,让 listener 在 awaitClose 时能拿到。
        var socket: WebSocket? = null
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(WsFrame.Text(text))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                trySend(WsFrame.Binary(bytes))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WsFrame.Closing(code, reason))
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WsFrame.Closed(code, reason))
                close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WsFrame.Failure(t, response?.code))
                close(t)
            }
        }
        socket = http.newWebSocket(req, listener)
        // 阻塞直至 channel 关闭;关闭时关闭底层 socket。
        awaitClose {
            runCatching { socket?.close(1000, "client-cancel") }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 解析服务端 WS 文本帧为 JSON object —— 不强类型,统一返回 `JsonObject`。
     * 调用方按 `type` 字段分发(`session.subscribed` / `dashboard.snapshot` /
     * `keepalive` / `runtime.delta` / 等等,均见 protocol.py 与 events.py)。
     */
    fun parseFrame(text: String): JsonObject? = runCatching {
        json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()
}