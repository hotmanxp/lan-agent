// service/WebViewFactory.kt — single source of truth for the WebView
// settings used by both the visible WebViewScreen and the background
// WebViewKeepAliveService. Pulled out so the two paths can't drift
// (different settings on the foreground vs background WebView would mean
// the session shape changes when the user backgrounds the app — SSE
// reconnect timers, cookie jars, etc., would silently reset).
package io.github.hotmanxp.lanagent.service

import android.content.Context
import android.graphics.Color
import android.webkit.WebView

internal object WebViewFactory {

    /**
     * Build a WebView with the project's standard LAN-tool settings and
     * immediately start loading [url]. Caller owns the returned instance
     * and must call WebView.destroy() to release Chromium's native memory
     * — for the foreground path the WebViewScreen's DisposableEffect does
     * this; for the service path, WebViewKeepAliveService.onDestroy does.
     */
    fun create(context: Context, url: String): WebView = WebView(context).apply {
        applyLanSettings(this)
        loadUrl(if (url.isBlank()) "about:blank" else url)
    }

    /**
     * 只建实例、不加载 —— 给「一段本地 HTML 字符串」这类预览用
     * (`loadDataWithBaseURL` 表达不成一个 URL,所以没法走上面那个重载)。
     * 调用方(ui/FileViewerOverlay.kt)自己负责 loadDataWithBaseURL / destroy。
     *
     * **textZoom 保持 100**:标准设置里那个 85% 是针对 opencc-web `/m`
     * 没有响应式排版的补偿(见下),本地 HTML 文件不背这个锅。
     */
    fun createForContent(context: Context): WebView = WebView(context).apply {
        applyLanSettings(this)
        settings.textZoom = 100
    }

    private fun applyLanSettings(webView: WebView) = with(webView) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Honor <meta name="viewport" content="width=device-width"> so mobile
        // CSS gets the actual device width (default 980px viewport would
        // render the page at desktop width and look squished/wrong).
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        // opencc-web /m uses fixed CSS px (16px body, 14px inputs) with no
        // responsive typography — scale the WebView's text/content down 15%
        // to match what Chrome on the same device feels like.
        settings.textZoom = 85
        // WebView's hardware-accelerated surface ignores setBackgroundColor
        // when attached to a View hierarchy (the foreground WebView draws
        // its dark backdrop in Compose instead). Here there's no View
        // hierarchy — the WebView is detached — so setBackgroundColor would
        // actually paint, but we keep it transparent for visual consistency
        // with the foreground path: the user can never see this WebView
        // anyway, and if a future debug surface attaches it the same
        // invariant holds.
        setBackgroundColor(Color.TRANSPARENT)
    }
}