// ui/WebViewScreen.kt — WebView 全屏详情页,无 App 顶栏;返回用系统手势
package io.github.hotmanxp.lanagent.ui

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hotmanxp.lanagent.R
import kotlinx.coroutines.launch

@Composable
fun WebViewScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Honor <meta name="viewport" content="width=device-width"> so mobile
            // CSS gets the actual device width (default 980px viewport would
            // render the page at desktop width and look squished/wrong).
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            // opencc-web /m uses fixed CSS px (16px body, 14px inputs) with no
            // responsive typography — this looks oversized on typical Android
            // phones. Scale the WebView's text/content down 15% to match what
            // Chrome on the same device feels like.
            settings.textZoom = 85
            // setBackgroundColor on a WebView rarely shows: WebView's surface
            // is hardware-accelerated and the drawable background paints
            // behind, not under, the surface. We draw the dark backdrop in
            // Compose (see Box below) and let the WebView be transparent so
            // the surface shows the Compose-painted color through it.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            canGoBack = view?.canGoBack() == true
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            // Silently ignore. LAN tools hit transient net::ERR_FAILED all the
            // time (server bouncing, sub-resources behind proxies, etc.) and a
            // Snackbar per failed request is noise. The page itself still
            // renders whatever loaded successfully.
        }
    }

    webView.loadUrl(if (url.isBlank()) "about:blank" else url)

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    // System back gesture / button: first press pops in-page history if any,
    // final press pops out to HomeScreen.
    BackHandler {
        if (canGoBack) webView.goBack() else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Dark backdrop painted by Compose (NOT by WebView.setBackgroundColor
            // — that doesn't show under the WebView's hardware-accelerated
            // surface). This is the color visible around any WebView padding
            // or before the page paints its own background.
            .background(Color(0xFF1F2937))
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                // Status bar is visible (per MainActivity) and the activity draws
                // edge-to-edge; windowInsetsPadding keeps the WebView's first row
                // from hiding behind the clock/battery. The 4dp top lets the
                // content breathe a hair below the bar.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 4.dp)
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}