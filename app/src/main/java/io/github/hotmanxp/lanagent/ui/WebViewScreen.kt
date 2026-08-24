// ui/WebViewScreen.kt — WebView 详情页 + 全屏 + 透明自动隐藏顶栏 + Snackbar 错误
package io.github.hotmanxp.lanagent.ui

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hotmanxp.lanagent.R
import kotlinx.coroutines.launch

@Composable
fun WebViewScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var currentUrl by remember(url) { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    // Auto-hide top bar on scroll. true = visible.
    var topBarVisible by remember { mutableStateOf(true) }

    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Don't override viewport — let opencc-web's own <meta viewport>
            // tag handle mobile sizing. useWideViewPort + loadWithOverviewMode
            // both zoom-fit content to width, which forces a desktop layout
            // on a mobile route and inflates the perceived font size.
            settings.textZoom = 100
        }
    }

    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            url?.let { currentUrl = it }
            canGoBack = view?.canGoBack() == true
            topBarVisible = true
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

    // Auto-hide: scrolling down hides, scrolling up (or near top) shows.
    webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        val delta = scrollY - oldScrollY
        topBarVisible = when {
            scrollY <= 8 -> true                  // near top — always show
            delta < -2 -> true                    // scroll up — show
            delta > 2 -> false                    // scroll down — hide
            else -> topBarVisible                 // micro-jitter
        }
    }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    BackHandler {
        if (canGoBack) webView.goBack() else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // WebView fills the whole screen.
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent, slimmer (40dp) top bar. Slides up/down on scroll.
        // statusBarsPadding keeps it clear of the transient status-bar reveal.
        AnimatedVisibility(
            visible = topBarVisible,
            enter = slideInVertically(animationSpec = tween(180)) { -it },
            exit = slideOutVertically(animationSpec = tween(180)) { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.22f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.webview_back_cd),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = currentUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        color = Color.White,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { webView.reload() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.webview_refresh_cd),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Snackbar overlay at the bottom.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}