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

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
private const val TAG = "LanAgentTerm"
/** sink 未就绪时的帧缓冲上限(见 [AaTerminalTransport.deliver])。 */
private const val PENDING_MAX = 512

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

    /**
     * sink 落到 `webView.evaluateJavascript`,那是**只能在主线程调**的 WebView
     * 方法。而 AA 的 WS 帧跑在 OkHttp 的后台线程上(`WebSocketListener.onMessage`),
     * 直接调会拿到 `java.lang.Throwable: A WebView method was called on thread ...`
     * —— 0.24.8 之前手机上报「连接失败:…A WebView method…」就是这个。
     *
     * 用主线程 Handler 而不是把 WebView 塞进 transport:后者会让「字节管道」
     * 这层抽象反向依赖渲染器。SSH 那条路没这个问题,是因为 [SshTerminalStore]
     * 自己已经切过线程。
     */
    private val main = Handler(Looper.getMainLooper())

    private var socket: WebSocket? = null
    private var job: Job? = null
    private var sink: ((String) -> Unit)? = null
    private var lastSeq: Long = -1L
    private var terminalId: String? = null
    private var closedByUs = false

    // sink 就绪(xterm 初始化完)之前收到的帧,按序攒着,等 sink 到位一次性补灌。
    private val pending = ArrayDeque<String>()
    private var framesIn = 0
    private var framesDropped = 0

    override fun onSinkReady(sink: ((String) -> Unit)?) {
        if (sink == null) {
            this.sink = null
            pending.clear()
            return
        }
        // 包一层 post:不管调用方在哪个线程,输出统一下回主线程再进 WebView。
        //
        // **必须捕获入参 `sink`(它才是真正的 evaluateJavascript),不能在 lambda
        // 里读 `this.sink`** —— `this.sink` 就是这个包装器自己,读它等于自己调
        // 自己:post → invoke 包装器 → 再 post …… 无限循环,JS 一帧也送不到
        // WebView,表现是「已连接 · online」但屏幕全黑、连诊断横幅都不出现。
        //
        // WebView 已销毁的保护改用 `this.sink != null` 做活性判断:onReleased
        // 会把它置空,已经排队但还没执行的 post 就此变成 no-op。
        val real: (String) -> Unit = sink
        this.sink = { js: String -> main.post { if (this.sink != null) real(js) } }
        // **补灌**:`AaTerminalScreen` 的 DisposableEffect 先调 start() 开 WS,
        // WebView 之后才 loadUrl + 解析 xterm.js。xterm 就绪之前到的帧先攒着,
        // 否则 shell 那一次性的提示符会被丢掉。
        val flushed = pending.toList()
        pending.clear()
        flushed.forEach { js -> this.sink?.invoke(js) }
        Log.i(TAG, "sink ready; flushed=${flushed.size} framesIn=$framesIn dropped=$framesDropped")
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
        pending.clear()
        stop()
    }

    /**
     * 状态回调统一切回主线程。
     *
     * `onState` 的实现方写的是 Compose `MutableState`(`state.value = …`),而
     * 绝大多数调用点在 OkHttp 的后台线程上。跨线程写快照状态不是崩溃,但会绕过
     * recomposition 的线程假设,表现为状态条不刷新或偶发崩溃 —— 与 sink 那个
     * WebView 线程错误是同一类问题,一起收在这里。
     */
    private fun emitState(state: AaTerminalState) {
        main.post { onState(state) }
    }

    /** 建终端并开 WS。幂等。 */
    fun start() {
        stop()
        closedByUs = false
        emitState(AaTerminalState.Connecting)
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
                emitState(AaTerminalState.Failed(err.message ?: err.javaClass.simpleName))
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
                    emitState(AaTerminalState.Open)
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
                        emitState(AaTerminalState.Exited(code, reason.takeIf { it.isNotBlank() }))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    if (!closedByUs) {
                        emitState(AaTerminalState.Failed(t.message ?: t.javaClass.simpleName))
                    }
                }
            },
        )
    }

    private fun handleFrame(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = json.optString("type")
        when (type) {
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
                deliver("wbTerm && wbTerm.reset()")
                emit(json.optString("data"))
            }
            "exit" -> emitState(
                AaTerminalState.Exited(json.optInt("exitCode", 0), json.optString("reason").takeIf { it.isNotBlank() })
            )
            "error" -> emitState(AaTerminalState.Failed(json.optString("message", "未知错误")))
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
        framesIn += 1
        deliver("wbTerm && wbTerm.write('$base64')")
    }

    /**
     * sink 到位就直接送,没到位就攒着等 [onSinkReady] 补灌。
     *
     * 上限 512 条:正常情况下缓冲只攒几十毫秒,这个上限只为「用户一直没打开
     * 终端页」这种极端情况兜底,免得无限增长。
     */
    private fun deliver(js: String) {
        val target = sink
        if (target == null) {
            pending.addLast(js)
            framesDropped += 1
            if (pending.size > PENDING_MAX) pending.removeFirst()
            return
        }
        target(js)
    }

    /** 往终端流里写一行青色状态横幅(连接中 / 错误原因),不额外占行。 */
    fun banner(text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        deliver("wbTerm && wbTerm.banner('$b64')")
    }
}
