// ui/WebViewScreen.kt — WebView 全屏详情页,无 App 顶栏;返回用系统手势
package io.github.hotmanxp.lanagent.ui

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
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
            // Honor <meta name="viewport" content="width=device-width"> in the
            // page so mobile CSS gets the actual device width instead of the
            // default 980px desktop viewport. loadWithOverviewMode stays off
            // because it zooms-to-fit which can blow up small mobile pages.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
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
            val msg = when (error?.errorCode) {
                -8 -> context.getString(R.string.snack_not_whitelisted)
                else -> context.getString(
                    R.string.snack_load_failed,
                    error?.errorCode ?: 0,
                    error?.description ?: ""
                )
            }
            scope.launch { snackbarHostState.showSnackbar(msg) }
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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}