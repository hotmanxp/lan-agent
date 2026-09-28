// ui/AaTerminalTransport.kt — AA 远程终端的数据源(0.24.2)
//
// ## 定位
//
// 0.24.2 之前 AA 官方给远程终端配的是 termux `RemoteTerminalView`(原生 View +
// 自己的一套快捷键面板)。lan-agent 已有 xterm.js 终端(Services → 局域网 SSH),
// 两者能力重复,所以把 termux 整个摘掉,AA 远程终端改用**同一套 xterm.js 渲染器**
// —— 这样两处终端的字体、复制、快捷命令面板、IME 行为完全一致。
//
// 能这么做是因为协议层同构:AA 服务端推的是**原始 PTY 字节**,只是包在
// WebSocket 的 base64 帧里,没有 termux 特有语义。
//
// ## 协议(来自 AA 官方客户端 `api/TerminalApi.kt` + 其 RemoteTerminalController)
//
// 建终端: POST /connectors/{deviceId}/terminals-v2?root=…  {cols,rows,cwd,…}
// 收/发:  WS   /connectors/{deviceId}/terminals-v2/{terminalId}/stream
//                ?fromSeq=<n>&token=<accessToken>
//
//   上行(客户端 → server):
//     {"type":"input",  "data":"<base64 字节>"}
//     {"type":"resize", "cols":N, "rows":M}
//   下行(server → 客户端):
//     {"type":"output", "data":"<base64>","seq":N}   增量输出,seq 单调
//     {"type":"replay", "data":"<base64>","seq":N}   重连补发,应**重置**缓冲后写入
//     {"type":"exit",   "exitCode":N, "reason":"…"}
//     {"type":"error",  "message":"…"}
//
// seq 去重:server 可能重发,已见过的 seq 丢弃(否则重连后屏幕出现重复文本)。
//
// ## 鉴权走 query 而不是 header
//
// WebSocket 握手在 OkHttp 上是可以带 header 的,但 AA 服务端**只从 query 读
// token**(见上面 streamUrl 的拼法)。这是协议规定,不是偷懒 —— 浏览器 WebSocket
// 根本没法设 header。
//
// ## URL 前缀
//
// 直接用 AA 官方的 `webSocketApiUrl`,它内部按 origin 自动在 `/api/v2` 与根路径
// 两种部署布局间选择(见 `aa/api/ApiUrls.kt` 的 apiRouteStyles)。**不要**自己拼
// 前缀 —— 0.24.1 手写版就是漏了 `/api/v2` 被 SPA fallback 吞掉,排查了半天。
package io.github.hotmanxp.lanagent.ui

import android.util.Base64
import io.github.hotmanxp.lanagent.aa.api.TerminalApi
import io.github.hotmanxp.lanagent.aa.api.webSocketApiUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 终端连接状态。渲染层(屏)据此显示状态条,和 SSH 终端的 `SshShellState` 对齐。
 */
sealed interface AaTerminalState {
    data object Idle : AaTerminalState
    data object Connecting : AaTerminalState
    data object Open : AaTerminalState
    data class Exited(val code: Int, val reason: String?) : AaTerminalState
    data class Failed(val message: String) : AaTerminalState
}

/**
 * AA 远程终端的 [TerminalTransport] 实现。
 *
 * 生命周期:屏调 [start] → 建终端 + 开 WS;WebView 销毁时 [onReleased] 关 WS。
 * [start] 幂等,重复调用会先断开旧连接。
 */
internal class AaTerminalTransport(
    private val serverUrl: String,
    private val accessToken: String,
    private val deviceId: String,
    private val root: String,
    private val cwd: String,
    private val scope: CoroutineScope,
    private val onState: (AaTerminalState) -> Unit,
) : TerminalTransport {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // **必须 0**:WS 是长连接,任何有界 readTimeout 都会在服务端两次心跳之间
        // 把连接判死。AA 服务端 15s 一条 keepalive。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val api = TerminalApi()

    private var socket: WebSocket? = null
    private var job: Job? = null
    private var sink: ((String) -> Unit)? = null
    private var lastSeq: Long = -1L
    private var terminalId: String? = null
    private var closedByUs = false

    override fun onSinkReady(sink: ((String) -> Unit)?) {
        this.sink = sink
    }

    override fun onInput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val frame = JSONObject()
            .put("type", "input")
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        socket?.send(frame.toString())
    }

    override fun onResize(cols: Int, rows: Int) {
        val frame = JSONObject().put("type", "resize").put("cols", cols).put("rows", rows)
        socket?.send(frame.toString())
    }

    override fun onReleased() {
        // WebView 没了,立刻停手 —— 之后任何往 sink 的写入都会打到已 destroy 的
        // WebView 上。
        sink = null
        stop()
    }

    /** 建终端并开 WS。幂等。 */
    fun start() {
        stop()
        closedByUs = false
        onState(AaTerminalState.Connecting)
        job = scope.launch(Dispatchers.IO) {
            try {
                val terminal = api.createTerminal(
                    serverUrl = serverUrl,
                    authorizationToken = accessToken,
                    deviceId = deviceId,
                    root = root,
                    // 初值无关紧要:xterm ready 后会用真实行列发一次 resize 覆盖。
                    cols = 80,
                    rows = 24,
                    cwd = cwd,
                    // AA 用它把同一次会话开的多个 terminal 归组;这里每次新开一个
                    // 随机组,不与别的终端共享。
                    ephemeralGroupId = java.util.UUID.randomUUID().toString(),
                )
                terminalId = terminal.terminalId
                openSocket()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                onState(AaTerminalState.Failed(err.message ?: err.javaClass.simpleName))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, "closed by client")
        socket = null
        lastSeq = -1L
    }

    private fun openSocket() {
        val id = terminalId ?: return
        val url = api.streamUrl(
            serverUrl = serverUrl,
            authorizationToken = accessToken,
            deviceId = deviceId,
            terminalId = id,
            fromSeq = 0L,
        )
        socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onState(AaTerminalState.Open)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    if (!closedByUs) {
                        onState(AaTerminalState.Exited(code, reason.takeIf { it.isNotBlank() }))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    if (!closedByUs) {
                        onState(AaTerminalState.Failed(t.message ?: t.javaClass.simpleName))
                    }
                }
            },
        )
    }

    private fun handleFrame(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "output" -> {
                val seq = json.optLong("seq", -1L)
                // seq 单调,重复的丢掉 —— 重连补发会让已渲染的内容再发一遍。
                if (seq > 0) {
                    if (seq <= lastSeq) return
                    lastSeq = seq
                }
                emit(json.optString("data"))
            }
            "replay" -> {
                // 重连补发:server 从 fromSeq 重发,客户端要**先清屏**再整体重写,
                // 否则屏幕上会变成「旧内容 + 补发内容」的拼接。
                lastSeq = -1L
                sink?.invoke("wbTerm && wbTerm.reset()")
                emit(json.optString("data"))
            }
            "exit" -> onState(
                AaTerminalState.Exited(json.optInt("exitCode", 0), json.optString("reason").takeIf { it.isNotBlank() })
            )
            "error" -> onState(AaTerminalState.Failed(json.optString("message", "未知错误")))
        }
    }

    /**
     * 往 xterm 写输出。
     *
     * `wbTerm.write(b64)` 收的**就是** UTF-8 字节的 base64(见
     * assets/terminal/index.html),与 AA 下发的 `data` 字段格式完全一致,所以
     * **原样透传**,不做二次编码。
     *
     * 走 base64 而不是直接拼字符串:evaluateJavascript 的实参是一段 JS 源码,
     * 终端输出里的引号/换行/反斜杠/控制字符会把整段炸掉。
     */
    private fun emit(base64: String) {
        if (base64.isBlank()) return
        val target = sink ?: return
        target("wbTerm && wbTerm.write('$base64')")
    }

    /** 往终端流里写一行青色状态横幅(连接中 / 错误原因),不额外占行。 */
    fun banner(text: String) {
        val target = sink ?: return
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        target("wbTerm && wbTerm.banner('$b64')")
    }
}
