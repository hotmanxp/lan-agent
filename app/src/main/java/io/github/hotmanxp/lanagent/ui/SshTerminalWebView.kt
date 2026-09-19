// ui/SshTerminalWebView.kt — xterm.js 终端载体 + JS ↔ Kotlin 桥
package io.github.hotmanxp.lanagent.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout

/** 终端页固定入口(assets 里,不走网络)。 */
private const val TERMINAL_URL = "file:///android_asset/terminal/index.html"

/** 终端页的日志 tag:`adb logcat -s LanAgentTerm` 是唯一的排障入口。 */
private const val TAG = "LanAgentTerm"

/**
 * 交互模式的真终端。xterm.js 跑在本机 assets 里(`assets/terminal/`),
 * 页面只做两件事:VT 转义解释 + 把用户输入转发回 Kotlin。SSH 完全在
 * Kotlin 侧([SshTerminalStore] + [io.github.hotmanxp.lanagent.ssh.SshShell]),
 * WebView 里没有网络访问。
 *
 * 为什么不引 Compose 终端库:任何"Compose 终端"要么不支持 VT 转义
 * (top/vim 花屏),要么就是包一层 WebView(那就直接用 xterm.js 更省事)。
 *
 * 生命周期:进入交互模式才创建,退出即 `destroy()`。`onDispose` 必须先
 * `onWebViewGone()` 摘掉注入口,否则 store 会往已销毁的 WebView 上打
 * `evaluateJavascript`。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SshTerminalWebView(
    store: SshTerminalStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bridge = remember(store) { TerminalJsBridge(store, context) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createTerminalWebView(ctx, bridge).also { bridge.view = it }
        },
        onRelease = { view ->
            // 顺序要紧:先摘注入口(store 不再往里打 JS),再 destroy。
            bridge.view = null
            store.onWebViewGone()
            view.destroy()
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createTerminalWebView(context: Context, bridge: TerminalJsBridge): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        // 全局 WebView 设过 textZoom = 85(zai 的 /m 页面没有响应式字号),
        // 终端页自己控字号,别被那一刀砍小。
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // xterm 自己画背景;给个同色底避免加载瞬间白闪
        setBackgroundColor(Color.parseColor("#0F1115"))
        // 软键盘要能作用到 xterm 的隐藏 textarea
        isFocusable = true
        isFocusableInTouchMode = true
        addJavascriptInterface(bridge, "AndroidTerm")
        // 终端页是 assets 里的本地页 + 三个本地脚本,xterm 起不来的话页面
        // 只会是一片黑,没有任何可诊断信息。把 console 与资源加载失败都
        // 打到 logcat(`adb logcat -s LanAgentTerm`),这是唯一的排障口子。
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i(
                    TAG,
                    "${msg.messageLevel()} ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}",
                )
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            /** 终端页是本地单页,任何跳转都是误触 —— 一律吞掉。 */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true

            /** LAN 工具的网络错误不弹(与 WebViewScreen 同口径),但要留痕。 */
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                Log.w(TAG, "load failed ${request.url}: ${error.description}")
            }
        }
        // ⚠️ 不要在 factory 里直接 loadUrl:那一刻 WebView 还没被测量,尺寸是
        // 0x0,页面会以「视口高度 0」完成首次布局 —— `html{height:100%}` 解析成
        // 0,paint 树被裁成 0 高,整页只剩 View 自己的背景色(实测
        // body=412x0、按键条有 44 高但一个像素都看不见)。
        // 所以:等第一次拿到**非零高度**的 layout 再加载;之后每一次尺寸变化
        // (软键盘开合 / 旋转)都推给页面,页面据此显式设定根节点高度再 fit,
        // 不依赖 WebView 自己有没有把 viewport 更新对。
        var loaded = false
        fun loadOnce() {
            if (loaded) return
            loaded = true
            Log.i(TAG, "loadUrl at view ${width}x$height")
            loadUrl(TERMINAL_URL)
        }
        doOnLayout { if (it.height > 0) loadOnce() }
        // 注意:监听器的第一个参数类型是 View(不是 WebView),所以在 apply
        // 里直接用 this 的属性/方法,不要走这个参数。
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!loaded) {
                if (height > 0) loadOnce()
            } else {
                evaluateJavascript(
                    "window.wbTerm && wbTerm.setViewport($width,$height)",
                    null,
                )
            }
        }
    }

/**
 * JS 侧 `window.AndroidTerm`。所有方法跑在 WebView 的 JavaBridge 线程
 * (不是主线程),所以:能直接做的直接做(`send` 只是入队),要碰 Compose
 * 状态的(ready)或 View 的(hideIme)必须 `post` 回主线程。
 *
 * 方向约定:
 *  - JS → Kotlin 一律 base64(`send` / `copy`),因为控制字符走字符串
 *    容易被中间的转义层改写;
 *  - Kotlin → JS 用 `evaluateJavascript` 拼源码,同样 base64 保证安全。
 */
internal class TerminalJsBridge(
    private val store: SshTerminalStore,
    private val context: Context,
) {
    @Volatile
    var view: WebView? = null

    /** xterm 初始化完成,可以接收输出了。 */
    @JavascriptInterface
    fun ready() {
        val v = view ?: return
        v.post {
            store.onWebViewReady { js -> v.evaluateJavascript(js, null) }
            // 视口尺寸不能盲信:把 View 的真实尺寸显式推给页面(页面会写死
            // html/body/#root 的高度再 refit)。addOnLayoutChangeListener 只在
            // **变化**时触发,进交互模式时尺寸没变过,所以这里必须补一次。
            v.evaluateJavascript(
                "window.wbTerm && wbTerm.setViewport(${v.width},${v.height})",
                null,
            )
        }
    }

    /** 用户按键 / 粘贴 → pty 输入。 */
    @JavascriptInterface
    fun send(b64: String) {
        store.sendToShell(bytesOfB64(b64))
    }

    /** xterm 量出的行列数 → window-change。 */
    @JavascriptInterface
    fun resize(cols: Int, rows: Int) {
        store.onPtyResize(cols, rows)
    }

    /** 终端「复制全部」→ 系统剪贴板。 */
    @JavascriptInterface
    fun copy(b64: String) {
        val text = String(bytesOfB64(b64), Charsets.UTF_8)
        if (text.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("终端输出", text))
    }

    /** 按键条上的「收起键盘」。 */
    @JavascriptInterface
    fun hideIme() {
        val v = view ?: return
        v.post {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
        }
    }

    /**
     * 诊断通道:页面把自身状态(布局尺寸 / 行列数 / 异常)以一行文本发过来,
     * 落到 logcat。终端页出问题时屏幕上往往只有一片黑,这是唯一能看到
     * 内部状态的入口 —— `adb logcat -s LanAgentTerm`。
     */
    @JavascriptInterface
    fun diag(b64: String) {
        Log.i(TAG, "[page] " + String(bytesOfB64(b64), Charsets.UTF_8))
    }
}
