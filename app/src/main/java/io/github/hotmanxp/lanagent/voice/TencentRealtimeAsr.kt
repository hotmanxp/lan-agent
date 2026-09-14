// voice/TencentRealtimeAsr.kt — 实时语音识别（WebSocket）客户端，两家共用。
//
// 鉴权与收尾形态由 [AsrDialect] 分派（见 TencentAsrSignature.kt 的对照表）：
//   · 腾讯云  —— URL 签名 + 文本结束帧 `{"type":"end"}` + result.slice_type 切片
//   · WorkBuddy —— Bearer header + 空二进制结束帧 + **全量 text 覆盖**
// 其余（裸 PCM 二进制帧上行、握手期缓冲、收尾兜底）两家完全一致。
//
// 四个必须处理的边界：
//   1. **握手期缓冲**。start() 到 onOpen 之间通常有 100–500ms，用户这时已经在说话了。
//      直接 ws.send 会失败，丢掉就是「按住后第一个字听不见」。这里用 pending 队列补发。
//   2. **收尾不能被抢跑**。若松手时握手还没完成，立刻发 `{"type":"end"}` 会排到
//      缓冲音频**前面**，服务端收到 end 直接收尾，一个字都识别不出来。
//      所以握手未完成时只置 endRequested，由 onOpen 补发完音频再发 end。
//   3. **稳态 vs 非稳态文本**。slice_type=2 才 append 到 committed，slice_type=1 只覆盖
//      partial。若把两者混在一起，整句会被后续的非稳态结果覆盖回去，输入框里字来回跳。
//   4. **签名不能在主线程做**。AsrUrlProvider.Local 是一次 HMAC（快），
//      但 .Remote 是一次 HTTP 请求 —— 在主线程直接 NetworkOnMainThreadException。
//      所以建连整体扔到单线程 io 上。
package io.github.hotmanxp.lanagent.voice

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TencentRealtimeAsr(
    private val urlProvider: AsrUrlProvider,
    private val engine: String = "16k_zh",
    private val listener: Listener,
    private val client: OkHttpClient = defaultClient,
) {
    companion object {
        /** 腾讯云口径：音频传完发这条**文本**消息通知后台结束识别。 */
        private const val END_FRAME = """{"type": "end"}"""

        /** WorkBuddy 口径：结束信号是一个**空二进制帧**（`Buffer.alloc(0)`）。 */
        private val WORKBUDDY_END_FRAME = ByteArray(0)

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 不要设读超时
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /** 收尾兜底：发完 end 之后最多等这么久。 */
        private const val FINAL_TIMEOUT_MS = 8_000L
    }

    interface Listener {
        /** 握手成功，可以开始灌音频了。 */
        fun onConnected(voiceId: String)

        /**
         * 文本有更新。[committed] 是已定稿的句子拼接，[partial] 是当前句的非稳态猜测。
         * UI 直接显示 `committed + partial`。
         */
        fun onText(committed: String, partial: String)

        /** 识别真正结束，[text] 是完整结果。 */
        fun onFinal(text: String)

        /** 失败。[code] 为 0 表示本地/网络异常（非服务端错误码）。 */
        fun onError(code: Int, message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pending = ArrayDeque<ByteArray>()
    private val io: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "asr-connect") }

    private var socket: WebSocket? = null
    private var closed = false
    private var connected = false

    /** 本次连接的服务端方言，由 [AsrUrlProvider] 决定收尾帧形态与下行解析方式。 */
    private var dialect: AsrDialect = AsrDialect.TencentCloud

    /** 松手时握手尚未完成 —— 等 onOpen 补发完缓冲音频再发 end。 */
    private var endRequested = false

    private val committed = StringBuilder()
    private var partial: String = ""

    private var finalWaiter: ((String) -> Unit)? = null
    private val finalTimer = Runnable {
        val waiter = finalWaiter ?: return@Runnable
        finalWaiter = null
        closeQuietly()
        val content = committed.toString().trim()
        post { waiter(content) }
    }

    /** 建连。返回后还要等 [Listener.onConnected] 才能确认通道可用。 */
    fun start() {
        io.execute {
            val signed = runCatching { urlProvider.provide(engine) }.getOrElse { e ->
                finishWithError(0, "获取识别地址失败：${e.message ?: e}")
                return@execute
            }
            if (closed) return@execute

            dialect = signed.dialect

            // WorkBuddy 走 Bearer，鉴权在 header 上；腾讯云走 URL 签名，这里为空。
            val request = Request.Builder()
                .url(signed.url)
                .apply { signed.headers.forEach { (name, value) -> addHeader(name, value) } }
                .build()
            socket = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // 服务端还会回一条 {code:0,...} 的握手确认，但通道此时已可用，
                    // 直接把缓冲音频补发出去能省掉一轮 RTT。
                    connected = true
                    drainPending(webSocket)
                    if (endRequested) {
                        endRequested = false
                        sendEndFrame(webSocket)
                    }
                    post { listener.onConnected(signed.voiceId) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(webSocket, text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finishWithError(0, "连接失败：${t.message ?: t}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closed) return
                    // 服务端在 final 之后会主动断开，属于正常收尾。
                    if (finalWaiter == null) finishWithError(code, "连接被关闭：$code $reason")
                }
            })
        }
    }

    /**
     * 灌一片音频。200ms 一片（16k → 6400 字节），保持 1:1 实时率。
     * 握手未完成时会自动缓冲，不会丢。
     */
    fun feed(chunk: ByteArray) {
        if (closed || chunk.isEmpty()) return
        val ws = socket
        if (!connected || ws == null) {
            synchronized(lock) { pending.addLast(chunk) }
            return
        }
        ws.send(chunk.toByteString())
    }

    /**
     * 松手：通知服务端音频结束，并等待 final。
     * [onDone] 一定会在主线程被调到（正常收尾、超时兜底或出错兜底），调用方据此复位 UI。
     *
     * **必须在 `recorder.stop()` 之后调用** —— 否则 feed() 还会继续往里塞音频。
     */
    fun finish(onDone: (String) -> Unit) {
        if (closed) {
            post { onDone(committed.toString().trim()) }
            return
        }
        if (finalWaiter != null) return // 幂等：重复松手只认第一次
        finalWaiter = onDone
        main.postDelayed(finalTimer, FINAL_TIMEOUT_MS)

        // 见文件头坑位 2：握手没完成就发 end，end 会插到音频前面。
        if (socket == null || !connected) {
            endRequested = true
            return
        }
        synchronized(lock) { pending.clear() } // 已经结束了，缓冲里的残片没意义
        socket?.let { sendEndFrame(it) }
    }

    /** 两家收尾信号不同：腾讯云发文本帧，WorkBuddy 发空二进制帧。 */
    private fun sendEndFrame(webSocket: WebSocket) {
        when (dialect) {
            AsrDialect.TencentCloud -> webSocket.send(END_FRAME)
            AsrDialect.WorkBuddy -> webSocket.send(WORKBUDDY_END_FRAME.toByteString())
        }
    }

    /** 上滑取消 / 页面销毁。 */
    fun cancel() {
        if (closed) return
        closed = true
        main.removeCallbacks(finalTimer)
        finalWaiter = null
        synchronized(lock) { pending.clear() }
        closeQuietly()
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        val code = json.optInt("code", 0)
        if (code != 0) {
            finishWithError(code, json.optString("message").ifBlank { json.optString("msg") })
            return
        }

        when (dialect) {
            AsrDialect.TencentCloud -> consumeTencentSlice(json)
            AsrDialect.WorkBuddy -> consumeWorkBuddySlice(json)
        }

        // final 表示音频流全部识别结束，之后服务端会主动断开。
        if (isFinalFlag(json)) completeSuccessfully()
    }

    /**
     * 腾讯云：`result.slice_type` 0 一段开始 / 1 识别中(非稳态) / 2 一段结束(稳态)。
     * 非稳态只覆盖 partial，混进 committed 会让输入框里的字来回跳。
     */
    private fun consumeTencentSlice(json: JSONObject) {
        val result = json.optJSONObject("result") ?: return
        val piece = result.optString("voice_text_str")
        when (result.optInt("slice_type", -1)) {
            1 -> {
                partial = piece
                if (piece.isNotBlank()) post { listener.onText(committed.toString(), partial) }
            }

            2 -> {
                if (piece.isNotBlank()) committed.append(piece)
                partial = ""
                post { listener.onText(committed.toString(), "") }
            }

            else -> Unit // slice_type=0 只是「有人声」，文本通常为空
        }
    }

    /**
     * WorkBuddy：下行是 `{code,message,voice_id,text,stable,final,noise_reject}`。
     *
     * ⚠️ **`text` 是整段全量，不是增量** —— 实测一条长语音只会回两条消息
     * （`final:false` 与 `final:true`），两条的 `text` 都是同一整句。所以这里必须
     * **覆盖**而不是追加，否则输入框里的字会翻倍。
     *
     * WorkBuddy 桌面端自己也是这么处理的，它的 `appendStreamingText` 做的事就是
     * 「拿新全文和上一次的存量比，只把多出来的那段喂给输入框」：
     *
     *     const delta = text.startsWith(previous) ? text.slice(previous.length) : text;
     *
     * 我们不需要算 delta —— 上层（HoldToTalk）消费的是「整体替换」语义
     * （`liveText = committed + partial`），给全量反而更直接。
     *
     * 首条 `{code:0,message:"success",voice_id:"…"}` 只是握手确认，text 为空，会被跳过。
     */
    private fun consumeWorkBuddySlice(json: JSONObject) {
        val piece = json.optString("text")
        if (piece.isBlank()) return
        committed.setLength(0)
        committed.append(piece)
        partial = ""
        post { listener.onText(committed.toString(), "") }
    }

    /** `final` 可能是 JSON 数字 1，也可能是布尔 true —— `optBoolean` 不认数字，两种都判。 */
    private fun isFinalFlag(json: JSONObject): Boolean {
        if (!json.has("final")) return false
        if (json.optBoolean("final", false)) return true
        return json.optInt("final", 0) == 1
    }

    private fun completeSuccessfully() {
        main.removeCallbacks(finalTimer)
        val waiter = finalWaiter
        finalWaiter = null
        closed = true
        val full = committed.toString().trim()
        post {
            listener.onText(full, "")
            listener.onFinal(full)
            waiter?.invoke(full)
        }
        closeQuietly()
    }

    private fun drainPending(webSocket: WebSocket) {
        while (!closed) {
            val chunk = synchronized(lock) { pending.pollFirst() } ?: return
            webSocket.send(chunk.toByteString())
        }
    }

    private fun finishWithError(code: Int, message: String) {
        if (closed) return
        closed = true
        connected = false
        main.removeCallbacks(finalTimer)
        val waiter = finalWaiter
        finalWaiter = null
        synchronized(lock) { pending.clear() }
        closeQuietly()
        val content = committed.toString().trim()
        post {
            listener.onError(code, message)
            // 出错也必须把控制权交回去，否则调用方会一直卡在「识别中」。
            if (waiter != null) {
                listener.onText(content, "")
                waiter.invoke(content)
            } else {
                listener.onFinal(content)
            }
        }
    }

    private fun closeQuietly() {
        runCatching { socket?.close(1000, null) }
        runCatching { socket?.cancel() }
        socket = null
        connected = false
        // shutdown 而不是 shutdownNow：finishWithError 可能就在 io 线程上跑，
        // 中断当前线程会把后面的 post{} 也一起打断。
        io.shutdown()
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
