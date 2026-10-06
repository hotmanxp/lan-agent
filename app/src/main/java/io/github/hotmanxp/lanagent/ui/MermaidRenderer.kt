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
// 代价是失去矢量缩放(大图放大会糊)。手机宽度上的流程图够用;要读细节就点开
// 全屏预览(见 ui/MermaidBlock.kt 的 MermaidFullscreenDialog)—— 全屏也仍是同一张
// 位图,只是不再被 360dp 的卡宽压着。真要无损放大得换 androidsvg 之类重新贴一层。
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
import android.view.View
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

/**
 * 一次成功渲染的产物。
 *
 * @param naturalWidth / naturalHeight 是 **SVG viewBox 的尺寸** —— mermaid 按文本
 *   度量算出的「本来的画布多大」,**不是** PNG 的像素宽高。PNG 宽 =
 *   ceil(naturalWidth × pickScale),而 pickScale 只存在于 JS 侧,回传 bitmap 尺寸
 *   就再也还原不出这个数。
 *
 *   卡片把图按 `ContentScale.FillWidth` 铺满,屏上显示宽度恒等于卡宽,所以
 *   「这张图相对原本设计尺寸被缩到百分之几」= 卡宽 / naturalWidth —— 这是
 *   header 上「缩至 NN%」提示的唯一数据来源(对齐 web 的 `SCALE_HINT_THRESHOLD`)。
 */
internal data class MermaidImage(
    val bitmap: ImageBitmap,
    val naturalWidth: Int,
    val naturalHeight: Int,
)

internal object MermaidRenderer {

    private const val TAG = "LanAgentMermaid"

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    private const val PAGE_URL = "file:///android_asset/mermaid/renderer.html"

    /**
     * mermaid.min.js 有 5.5 MB(12.1.0 全量图型),首次解析在低端机上不便宜,
     * 给宽一点。
     */
    private const val READY_TIMEOUT_MS = 8_000L
    private const val RENDER_TIMEOUT_MS = 5_000L

    /**
     * 缓存的是**已经光栅化好的产物**。key 用源码原文而不是 hashCode ——
     * 源码本来就被消息流持有着,再存一份字符串不心疼,但 hashCode 撞车会渲染出
     * 别人的图,那种 bug 排查起来很折磨。
     */
    private val cache = object : LruCache<String, MermaidImage>(MAX_CACHED) {}
    private const val MAX_CACHED = 24

    private var webView: WebView? = null
    private var ready: CompletableDeferred<Unit>? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger(0)

    /**
     * 渲染一段 mermaid 源码。成功返回图像,任何环节失败返回 null。
     *
     * @param bgHex 画布底色(不带 `#`)。PNG 是带底色烤进去的 —— 透明底在深色
     *   主题下会让 mermaid 的浅色节点文字糊在深色卡片上。
     */
    suspend fun render(
        context: Context,
        source: String,
        dark: Boolean,
        bgHex: String,
    ): MermaidImage? {
        val src = source.trim()
        if (src.isEmpty()) return null

        val key = "$dark|$bgHex|$src"
        cache.get(key)?.let { return it }

        val payload = requestRender(context, src, dark, bgHex) ?: return null
        val image = decodePayload(payload) ?: return null

        cache.put(key, image)
        return image
    }

    /** 在调用方离开页面时清掉。渲染中的请求不打断,反正 WebView 是进程级的。 */
    fun clearCache() = cache.evictAll()

    // ── WebView 生命周期 ───────────────────────────────────────────────

    /**
     * 请求给这个 detached WebView 的视口尺寸(px)。
     *
     * ⚠️ 为什么必须给:甘特图的宽度是**按容器宽度拉伸**的,容器宽 0 它就整个塌成
     * `viewBox="0 0 0 436"` → 渲出来一张全空图(不是回退成代码块,是**空白的图**,
     * 更容易被当成「模型画错了」)。0.28.0 之前这个 WebView 从不 measure/layout,
     * 视口一直是 0,所以**只有甘特图坏**,flowchart / sequence / treemap / radar
     * 那些走 dagre 按节点内在尺寸布局的全都正常 —— 症状极具迷惑性。
     *
     * 已用同一份源码对照坐实是环境问题而非源码问题:opencc-web 那边
     * (mermaid 12.1.0 + 真浏览器视口)渲染完全正常。
     *
     * 下面这两个值是**请求值,不是保证值**:detached 的 WebView 最终视口由
     * Chromium 自己收敛,实测 1400×1800 请求拿到的是 534×686(≈ 屏宽)。
     * 真正要保证的只是「非 0 且够宽」—— 别在这里写死期望值,排障看
     * `renderer.html` 打的 `视口 innerWidth=…` 那行日志。
     */
    private const val VIEWPORT_W = 1400
    private const val VIEWPORT_H = 1800

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
                    // 「静默回退成代码块」或「一张全空的图」,不接出来根本看不到原因。
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

                    // 手动定尺寸。detached 的 View 永远走不到 measure/layout 回调,
                    // 视口就一直是 0 —— 甘特图宽度是容器驱动的,0 宽直接塌成空图
                    // (见 VIEWPORT_W 的注释)。View 的尺寸不随内容加载重置,
                    // 这里定一次就够。
                    measure(
                        View.MeasureSpec.makeMeasureSpec(VIEWPORT_W, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(VIEWPORT_H, View.MeasureSpec.EXACTLY),
                    )
                    layout(0, 0, VIEWPORT_W, VIEWPORT_H)

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

    /**
     * 解析 JS 侧回传的 JSON payload:`{"png":"data:image/png;base64,…","nw":2472,"nh":309}`。
     *
     * 失败一律 null(由调用方回退代码块)。**每一层失败都打日志** —— 渲染失败在 UI
     * 上只有「静默变代码块」一种表现,不打日志就只能靠猜(见 §26)。
     */
    private fun decodePayload(payload: String): MermaidImage? {
        if (payload.isEmpty()) {
            log("JS 侧渲染失败(空串)")
            return null
        }

        val json = try {
            JSONObject(payload)
        } catch (t: Throwable) {
            log("payload 不是合法 JSON: ${t.message} / ${payload.take(40)}")
            return null
        }

        val dataUrl = json.optString("png")
        if (dataUrl.isEmpty()) {
            log("payload 里没有 png 字段")
            return null
        }

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

        val nw = json.optInt("nw", 0)
        val nh = json.optInt("nh", 0)
        if (nw <= 0 || nh <= 0) {
            // 不致命:图照样能显示,只是 header 上不出「缩至 NN%」。真出现说明
            // renderer.html 的 svgSize 走了 800x600 兜底,值得看一眼。
            log("自然尺寸缺失 nw=$nw nh=$nh,缩放提示不会显示")
        }
        log("渲染成功 bitmap=${bmp.width}x${bmp.height} 固有=${nw}x$nh")
        return MermaidImage(bmp.asImageBitmap(), nw, nh)
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
