// CapabilityService.kt — 手机能力桥的服务端(跑在 App 主进程)。
//
// ## 为什么要有这一层
//
// 引擎(Node)在 :engine 进程里,它碰不到 Android 的 API —— 截图要
// MediaProjection、通知要 NotificationManagerService、启动 App 要
// PackageManager。这些全在主进程(有权限、有 Context)。
//
// 所以链路是:
//
//   模型 → phone-bridge mod 的 execute() → HTTP POST 127.0.0.1:9421
//        → CapabilityService → Android API
//
// ## 为什么用 HTTP 而不是 socketpair
//
// DSH-Android 用 socketpair 传 JSON-RPC,是因为它的引擎是 C++ 的 dsh,
// 没有现成的 HTTP 客户端。我们引擎就是 Node —— fetch 是现成的。
// 多引入一套协议没有收益。
//
// ## 端口与鉴权
//
// 只监听 127.0.0.1。**没有鉴权**:同设备上的其他 App 理论上能连,
// 但 Android 上每个 App 有自己的 uid,另一个 App 连 127.0.0.1 的
// 9421 端口虽然技术上可行,但要拿到本 App 的 uid 权限 —— 实际上
// Android 的 network namespace 是共享的,这点确实是个弱面。
//
// 所以真正的防线是:**能力实现自己判断要不要敏感操作**。
// 截图要用户授权 MediaProjection(系统弹窗),通知要 POST_NOTIFICATIONS
// 权限,这些都不是这里能绕过的。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 能力桥的 HTTP 服务端。
 *
 * 生命周期跟着主进程走:App 在 → 服务在;App 死 → 服务死。
 */
class CapabilityService(private val context: Context) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)

    /** 能力实现表:能力名 → 处理函数。 */
    private val handlers: Map<String, (JSONObject) -> JSONObject> = mapOf(
        "phone_screenshot" to { PhoneCapabilities.screenshot(it) },
        "phone_notifications" to { PhoneCapabilities.notifications(it) },
        "phone_clipboard" to { PhoneCapabilities.clipboard(it) },
        "phone_open_app" to { PhoneCapabilities.openApp(it) },
        "phone_list_apps" to { PhoneCapabilities.listApps(it) },
        "phone_settings" to { PhoneCapabilities.settings(it) },
        "notification_open" to { PhoneCapabilities.notificationOpen(it) },
        "notification_dismiss" to { PhoneCapabilities.notificationDismiss(it) },
        "notification_reply" to { PhoneCapabilities.notificationReply(it) },
        // 屏幕操作(无障碍)
        "screen_read" to { PhoneCapabilities.screenRead(it) },
        "screen_tap" to { PhoneCapabilities.screenTap(it) },
        "screen_type" to { PhoneCapabilities.screenType(it) },
        "screen_swipe" to { PhoneCapabilities.screenSwipe(it) },
        "screen_scroll" to { PhoneCapabilities.screenScroll(it) },
        "screen_key" to { PhoneCapabilities.screenKey(it) },
        "screen_wait" to { PhoneCapabilities.screenWait(it) },
        "screen_capture" to { PhoneCapabilities.screenCapture(it) },
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // 能力实现是 object 单例,自己拿不到 Context —— 在这儿喂给它。
        PhoneCapabilities.init(context)
        // stackSize 给 512K:这个线程只处理 HTTP,不吃栈。
        Thread(null, Runnable { serve() }, "cap-service", 512L * 1024).start()
        Log.i(TAG, "能力桥起来了 → http://127.0.0.1:${EnginePaths.CAPABILITY_PORT}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        workers.shutdownNow()
    }

    private fun serve() {
        try {
            val s = ServerSocket(EnginePaths.CAPABILITY_PORT)
            serverSocket = s
            while (running.get()) {
                val client = try {
                    s.accept()
                } catch (e: Throwable) {
                    if (running.get()) Log.w(TAG, "accept 失败", e)
                    break
                }
                workers.execute { handle(client) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "能力桥起不来", e)
            running.set(false)
        }
    }

    private fun handle(client: Socket) {
        // ⚠️ **整段必须包 try/catch** ——
        //
        // 之前这里一个 SocketTimeoutException 就把整个 App 干掉了(FATAL EXCEPTION):
        // 客户端连上但没发数据(或者发了半截就断开)→ 读请求行超时 →
        // 异常从 worker 线程逃出去 → Android 视为未捕获异常 → 进程崩。
        //
        // 这不只是「自己调用会崩」:同设备上**任何** App 都能连 127.0.0.1:9421,
        // 一个畸形请求就能把 lan-agent 杀掉(DoS)。所以这里吞掉所有异常,
        // 最多回一个错误响应。
        try {
            handleInner(client)
        } catch (e: Throwable) {
            Log.w(TAG, "处理请求出错(已忽略)", e)
            runCatching {
                client.getOutputStream().write(responseBody(JSONObject().put("ok", false).put("error", "请求处理失败")))
                client.getOutputStream().flush()
            }
        }
    }

    private fun handleInner(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 10_000
            // ⚠️ **必须按字节读**,不能按字符读。
            //
            // `Content-Length` 是**字节数**,而用 BufferedReader/InputStreamReader
            // 读出来的是**字符数** —— body 里只要有一个非 ASCII 字符(中文!
            // agent 传中文参数是常态),字符数就比字节数少,于是「按 Content-Length
            // 读字符」永远读不够 → 卡在 read 上 → 10 秒 SocketTimeoutException →
            // 客户端收到空响应(curl 报 "Empty reply from server")。
            //
            // 症状极具迷惑性:纯 ASCII 参数的调用全部正常,只有带中文的挂 ——
            // 看起来像「某个能力不支持中文参数」。
            //
            // 所以这里全程走原始字节,自己按 \r\n 切行,body 按字节数读完后
            // 再用 UTF-8 解码。
            val input = sock.getInputStream()
            val requestLine = readLineBytes(input) ?: return
            // POST /cap/<name> HTTP/1.1
            val path = requestLine.split(" ").getOrNull(1) ?: return

            // 读完 header(到空行为止)
            var contentLength = 0
            while (true) {
                val line = readLineBytes(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':', ignoreCase = true)
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val payload = if (read > 0) JSONObject(String(body, 0, read, Charsets.UTF_8)) else JSONObject()

            val name = path.removePrefix("/cap/").substringBefore('?')
            val handler = handlers[name]
            val response = if (handler == null) {
                JSONObject().put("ok", false).put("error", "没有这个能力:$name")
            } else {
                try {
                    handler(payload)
                } catch (e: Throwable) {
                    Log.e(TAG, "能力 $name 出错", e)
                    JSONObject().put("ok", false).put("error", e.message ?: e.toString())
                }
            }

            val out = sock.getOutputStream()
            out.write(responseBody(response))
            out.flush()
        }
    }

    /**
     * 从原始流里读一行(到 \r\n 或 \n 为止),返回**字节**。
     *
     * 不用 BufferedReader:它会预读,body 的字节会被它吞进缓冲,
     * 后面按 Content-Length 读 body 就少字节。行尾的 \r\n / \n 都算行尾。
     */
    private fun readLineBytes(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (b == '\r'.code) continue
            buf.write(b)
        }
    }

    private fun responseBody(json: JSONObject): ByteArray {        val body = json.toString().toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        return head + body
    }

    companion object {
        private const val TAG = "CapabilityService"

        @Volatile
        private var instance: CapabilityService? = null

        /** 幂等启动。 */
        fun ensureStarted(context: Context) {
            val app = context.applicationContext
            synchronized(this) {
                if (instance?.running?.get() == true) return
                instance = CapabilityService(app).also { it.start() }
            }
        }
    }
}
