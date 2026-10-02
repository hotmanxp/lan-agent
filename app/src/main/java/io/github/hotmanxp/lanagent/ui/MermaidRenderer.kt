// ui/MermaidRenderer.kt — Mermaid 流程图渲染(离线 WebView + JS 桥)
//
// ## 为什么是「一个 WebView 出 PNG」而不是「每块图一个 WebView」
//
// 会话消息流是 `reverseLayout` 的 LazyColumn(WebViewScreen 同款,见 ui/AgentSessionScreen.kt)。
// 一个 WebView 实例光初始化就 20–50 MB 内存,在列表里按块 new 会直接拖垮滚动,
// 而且 LazyColumn 会回收重组 —— WebView 重新 attach 时状态全丢(白屏 / 串图)。
//
// 所以走**进程级单例**:detached、0 尺寸、从不进 View 层级,只用来把 mermaid
// 源码光栅化成一张 PNG。列表里拿到的只是一张 Bitmap,滚动是纯 Compose 的事。
//
// 代价是失去矢量缩放(大图放大会糊)。手机宽度上的流程图够用,真要放大可以后续
// 换 androidsvg 之类重新贴一层。
//
// ## 数据流
//
//   Kotlin ──evaluateJavascript(window.__mermaidRender)──▶ JS
//      ▲                                                    │
//      └──────── @JavascriptInterface onResult(id, dataUrl) ┘
//
// 失败(语法错 / 流式半截代码块 / 超时 / 图片编码失败)一律回 null,
// 由调用方回退到普通代码块 —— 也就是「关掉开关」时的那副样子。
package io.github.hotmanxp.lanagent.ui

import android.content.Context
import android.util.Base64
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.hotmanxp.lanagent.service.WebViewFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object MermaidRenderer {

    private const val TAG = "LanAgentMermaid"

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    private const val PAGE_URL = "file:///android_asset/mermaid/renderer.html"

    /** mermaid.min.js 有 2.5 MB,首次解析在低端机上不便宜,给宽一点。 */
    private const val READY_TIMEOUT_MS = 8_000L
    private const val RENDER_TIMEOUT_MS = 5_000L

    /**
     * 缓存的是**已经光栅化好的 Bitmap**。key 用源码原文而不是 hashCode ——
     * 源码本来就被消息流持有着,再存一份字符串不心疼,但 hashCode 撞车会渲染出
     * 别人的图,那种 bug 排查起来很折磨。
     */
    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHED) {}
    private const val MAX_CACHED = 24

    private var webView: WebView? = null
    private var ready: CompletableDeferred<Unit>? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger(0)

    /**
     * 渲染一段 mermaid 源码。成功返回 Bitmap,任何环节失败返回 null。
     *
     * @param bgHex 画布底色(不带 `#`)。PNG 是带底色烤进去的 —— 透明底在深色
     *   主题下会让 mermaid 的浅色节点文字糊在深色卡片上。
     */
    suspend fun render(
        context: Context,
        source: String,
        dark: Boolean,
        bgHex: String,
    ): ImageBitmap? {
        val src = source.trim()
        if (src.isEmpty()) return null

        val key = "$dark|$bgHex|$src"
        cache.get(key)?.let { return it }

        val dataUrl = requestRender(context, src, dark, bgHex) ?: return null
        val bitmap = decodeDataUrl(dataUrl) ?: return null

        cache.put(key, bitmap)
        return bitmap
    }

    /** 在调用方离开页面时清掉。渲染中的请求不打断,反正 WebView 是进程级的。 */
    fun clearCache() = cache.evictAll()

    // ── WebView 生命周期 ───────────────────────────────────────────────

    /**
     * 建一个 detached WebView 并指向 assets 里的宿主页。
     *
     * **不 attach 到 View 层级**:Chromium 会跳过 rasterization,但 JS engine
     * 照常跑 —— 我们要的正是这个(见 WebViewKeepAliveService 的同款理由)。
     * 从此之后这个实例活到进程结束,不 destroy。
     */
    private fun ensureWebView(context: Context): WebView =
        webView ?: synchronized(this) {
            webView ?: WebViewFactory
                .createForContent(context.applicationContext)
                .apply {
                    // 宿主页是 file:///android_asset/ 下的 HTML,要能读到同目录的
                    // mermaid.min.js。默认就是 true,写出来是为了让意图显式。
                    settings.allowFileAccess = true
                    // ⚠️ 没有这层就只能靠猜。renderer.html 里的 console.warn /
                    // 自诊断信息是排障的唯一线索 —— 渲染失败在 UI 上表现为
                    // 「静默回退成代码块」,不接出来根本看不到原因。
                    // 排障:adb logcat -s LanAgentMermaid
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(
                            msg: android.webkit.ConsoleMessage,
                        ): Boolean {
                            log("js: ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                            return true
                        }
                    }
                    addJavascriptInterface(Bridge(), "AndroidMermaid")
                    loadUrl(PAGE_URL)
                }
                .also { webView = it }
        }

    // ── 一次渲染的往返 ─────────────────────────────────────────────────

    private suspend fun requestRender(
        context: Context,
        src: String,
        dark: Boolean,
        bgHex: String,
    ): String? = withContext(Dispatchers.Main) {
        // 门闩先建再建 WebView:页面的 ready() 回调可能在 loadUrl 之后的任意
        // 时刻到,晚一步 ready 变量还没赋值,那一枪就丢了。
        val gate = ready ?: CompletableDeferred<Unit>().also { ready = it }

        val wv = try {
            ensureWebView(context)
        } catch (t: Throwable) {
            // 极少数 ROM 上 WebView 初始化会抛(缺 provider / 版本太老)
            return@withContext null
        }

        if (withTimeoutOrNull(READY_TIMEOUT_MS) { gate.await() } == null) {
            // 页面没就绪(资源缺失 / 脚本炸了)。重置 gate 让下次重试,别把失败
            // 缓存住 —— 第一次进页面资源还没解压完是很正常的。
            if (ready === gate) ready = null
            return@withContext null
        }

        val id = nextId.incrementAndGet()
        val result = CompletableDeferred<String>()
        pending[id] = result

        // 参数走 JSONObject.quote 而不是手拼引号 —— 源码里有引号、反斜杠、
        // 换行、`<` 都是常态,手拼必炸。
        val js = "window.__mermaidRender($id, ${JSONObject.quote(src)}, $dark, ${JSONObject.quote(bgHex)});"
        try {
            wv.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            pending.remove(id)
            return@withContext null
        }

        val payload = withTimeoutOrNull(RENDER_TIMEOUT_MS) { result.await() }
        pending.remove(id)
        if (payload == null) {
            log("渲染超时 ${RENDER_TIMEOUT_MS}ms id=$id,源码 ${src.length} 字")
            return@withContext null
        }
        if (payload.isEmpty()) {
            log("JS 侧渲染失败(空串) id=$id,源码 ${src.length} 字")
            return@withContext null
        }
        payload
    }

    private fun decodeDataUrl(dataUrl: String): ImageBitmap? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) {
            log("dataURL 畸形(无逗号): ${dataUrl.take(40)}")
            return null
        }
        if (!dataUrl.startsWith("data:image/")) {
            // canvas 超出浏览器上限时 toDataURL 返回的是字面量 "data:,"。
            // 不打日志的话这个会一路静默走到回退,极难定位。
            log("dataURL 前缀异常(疑似 canvas 超限): ${dataUrl.take(40)}")
            return null
        }
        val bytes = try {
            Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        } catch (t: Throwable) {
            log("base64 解码失败: ${t.message}")
            return null
        }
        val bmp = try {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            log("PNG 解码抛异常: ${t.message}")
            null
        } ?: run {
            log("PNG 解码返回 null,${bytes.size} 字节")
            return null
        }
        log("渲染成功 ${bmp.width}x${bmp.height}")
        return bmp.asImageBitmap()
    }

    // ── JS → Kotlin ────────────────────────────────────────────────────

    /**
     * JS 侧回调。**跑在 WebView 的 JavaBridge 线程上**,碰 Compose 状态或 View
     * 之前必须 post 回主线程。
     */
    private class Bridge {

        @JavascriptInterface
        fun ready() = onMain { MermaidRenderer.ready?.complete(Unit) }

        @JavascriptInterface
        fun onResult(id: Int, payload: String) = onMain {
            MermaidRenderer.pending.remove(id)?.complete(payload)
        }

        private fun onMain(block: () -> Unit) {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        }
    }
}
