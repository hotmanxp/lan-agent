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
        loadUrl(if (url.isBlank()) "about:blank" else url)
    }
}