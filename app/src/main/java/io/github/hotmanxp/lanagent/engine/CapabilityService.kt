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
        client.use { sock ->
            sock.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // POST /cap/<name> HTTP/1.1
            val path = requestLine.split(" ").getOrNull(1) ?: return

            // 读完 header(到空行为止)
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':', ignoreCase = true)
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val payload = if (read > 0) JSONObject(String(body, 0, read)) else JSONObject()

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

    private fun responseBody(json: JSONObject): ByteArray {
        val body = json.toString().toByteArray(Charsets.UTF_8)
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
