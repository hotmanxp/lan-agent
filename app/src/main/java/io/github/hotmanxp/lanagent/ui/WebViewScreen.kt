// ui/WebViewScreen.kt — WebView 全屏详情页,无 App 顶栏;返回用系统手势
package io.github.hotmanxp.lanagent.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.readRefreshButtonPos
import io.github.hotmanxp.lanagent.data.saveRefreshButtonPos
import io.github.hotmanxp.lanagent.service.WebViewKeepAliveService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WebViewScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var canGoBack by remember { mutableStateOf(false) }
    // True between a user-initiated reload() and the next onPageStarted —
    // lets us surface a "已刷新" Snackbar so the user knows the WebView
    // actually re-fetched instead of silently re-painting cached content.
    var pendingReload by remember { mutableStateOf(false) }

    // Floating refresh button — draggable, position persisted across launches.
    // null = never moved → render at the legacy default (right-center, 6dp inset).
    // Once the user drags it once, onDragStart seeds the offset to that same
    // default pixel position so the visual jump from align() to offset() is
    // zero, then subsequent drags accumulate from there.
    var refreshBtnPos by remember { mutableStateOf<Offset?>(null) }
    var refreshLayerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val refreshBtnSizePx = with(density) { 28.dp.toPx() }
    LaunchedEffect(Unit) {
        refreshBtnPos = context.readRefreshButtonPos()
    }

    // Bridge between WebView's synchronous onShowFileChooser and the async
    // ActivityResult result. The WebView hands us a ValueCallback that we
    // must call exactly once (with the chosen URIs or null) — so we stash
    // it here and resolve it from the launcher below.
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // WebView must be declared before pickFileLauncher — the launcher's
    // callback uses it to evaluateJavascript the bridge after a pick.
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

    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Read+clear in one shot so a second pick fired before we get back
        // here can't overwrite the first callback and leak URIs.
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            cb.onReceiveValue(null)
            return@rememberLauncherForActivityResult
        }
        // Manually parse instead of using WebChromeClient.FileChooserParams
        // .parseResult(): the static helper is reported buggy on some OEM
        // ROMs — either it doesn't read Intent.clipData, or it returns null
        // when Intent.data is set to a Uri that didn't include a query
        // param it expected. Pulling Uri ourselves off data/clipData makes
        // the path predictable across Android versions.
        val data = result.data
        val uris = mutableListOf<Uri>()
        if (data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            }
            if (data.data != null) uris.add(data.data!!)
        }
        if (uris.isEmpty()) {
            cb.onReceiveValue(null)
            return@rememberLauncherForActivityResult
        }

        // Android WebView's onShowFileChooser → `<input type="file">`
        // conversion is unreliable for content:// image URIs on several
        // Android versions and OEM ROMs (mime/size often empty, worst
        // case files.length === 0 and the page's onChange silently
        // returns). zai registers window.lanAgentAttachImages so we can
        // sidestep WebView's broken conversion by reading each URI's
        // bytes ourselves and pushing the base64 directly. If the bridge
        // isn't there (non-lan-agent host) or read fails, we fall back to
        // the standard path and let WebView try.
        scope.launch {
            when (injectImagesToWebView(context.applicationContext, webView, uris.toTypedArray())) {
                InjectResult.BridgeInvoked -> {
                    // bridge took over — cancel the standard path so WebView
                    // doesn't also inject the URIs into <input>.files and fire
                    // a second change event (would duplicate the attachment).
                    cb.onReceiveValue(null)
                }
                InjectResult.BridgeMissing,
                InjectResult.ReadFailed -> {
                    // Page didn't have our bridge or our native read failed
                    // — let WebView try the standard path so the user can
                    // still pick images, just via WebView's own conversion
                    // (which may or may not work depending on OEM).
                    cb.onReceiveValue(uris.toTypedArray())
                }
            }
        }
    }

    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            canGoBack = view?.canGoBack() == true
            if (pendingReload) {
                pendingReload = false
                scope.launch { snackbarHostState.showSnackbar("已刷新") }
            }
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

    // Without a WebChromeClient, Android WebView's default onShowFileChooser
    // returns false and <input type="file"> clicks are silently dropped — so
    // the zai "上传图片" button (which triggers a hidden <input accept="image/*">)
    // does nothing. We launch the system chooser via params.createIntent(),
    // which already encodes the page's acceptTypes (image/* here) and mode.
    webView.webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams,
        ): Boolean {
            // Only one pending pick at a time: if the page re-triggers before
            // our previous result returns, the old callback must be cancelled
            // (Android docs require this) or it leaks and the old input stays
            // stuck open.
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback

            // Build the picker intent ourselves instead of relying on
            // params.createIntent(). Several OEM ROMs (MIUI / ColorOS /
            // older Android WebView) ship a createIntent() that returns an
            // Intent without FLAG_GRANT_READ_URI_PERMISSION — when the
            // system chooser then hands back a content:// URI our process
            // can't read, some implementations silently dismiss the chooser
            // (returning RESULT_CANCELED) or return RESULT_OK with empty
            // Intent.data. Doing it manually with the grant flag and
            // Intent.createChooser (forces chooser UI even with one
            // candidate) sidesteps both failure modes.
            val type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "image/*"
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                this.type = type
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(pickIntent, "选择图片").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            return try {
                pickFileLauncher.launch(chooser)
                true
            } catch (e: ActivityNotFoundException) {
                callback.onReceiveValue(null)
                filePathCallback = null
                false
            }
        }
    }

    webView.loadUrl(if (url.isBlank()) "about:blank" else url)

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    // Keep the WebView's network stack alive after the user backgrounds the
    // app — without this, Activity.onPause freezes WebView networking and
    // any open SSE / long-poll session on the LAN zai page silently drops.
    // The Foreground Service holds a detached WebView that keeps running
    // JS + networking without a Surface (Chromium rasterizes nothing, the
    // network stack still works). DisposableEffect's onDispose fires when
    // the user navigates away (back to HomeScreen), which is exactly when
    // we want the service to stop.
    //
    // applicationContext (not the Activity Context): keeping an Activity
    // reference in a singleton-style service companion would extend the
    // Activity's lifetime indirectly through the Intent chain; the
    // application Context is process-scoped and leak-safe.
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(url) {
        WebViewKeepAliveService.start(appContext, url)
        onDispose { WebViewKeepAliveService.stop(appContext) }
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
            // refreshLayerSize bounds the draggable refresh button — clamp
            // its offset so it can't be dragged off-screen or behind the IME.
            .onSizeChanged { refreshLayerSize = it }
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
                // Soft keyboard: without this, MainActivity runs edge-to-edge
                // (setDecorFitsSystemWindows(false)) and the WebView keeps its
                // full-screen bounds when the IME pops up — the focused
                // <input> stays where it was and gets covered by the keyboard,
                // so the user can't see what they're typing until they hide it.
                // imePadding shrinks the AndroidView to sit above the IME, the
                // WebView's own scroll-then-focus logic then scrolls the input
                // into the now-visible region.
                .imePadding()
        )
        // Floating refresh button. webView.reload() is silent by default —
        // SPA-style pages may look identical after reload (just internal state
        // reset), so the user has no way to tell if it actually fired. We
        // pair the call with a "pendingReload" flag and show a Snackbar in
        // onPageStarted so they get a confirmation pulse.
        //
        // Draggable + position persisted across launches. Until the user
        // drags once, it sits at the legacy default (right-center, 6dp end
        // inset); after the first drag it switches to an absolute offset
        // driven by the persisted position. pointerInput's detectDragGestures
        // consumes the drag gesture so clickable only fires on a tap with
        // no movement, keeping a single click = reload semantics intact.
        //
        // Built with Box + clickable instead of IconButton because
        // IconButton's minimumInteractiveComponentSize (48dp by default)
        // overrides Modifier.size, so the original 36dp / 28dp circles
        // kept rendering at 48dp regardless of size hint. Box honors the
        // exact size — the outer circle and inner icon shrink together.
        val savedPos = refreshBtnPos
        val baseModifier: Modifier = if (savedPos == null) {
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .size(28.dp)
        } else {
            val maxX = (refreshLayerSize.width - refreshBtnSizePx).coerceAtLeast(0f)
            val maxY = (refreshLayerSize.height - refreshBtnSizePx).coerceAtLeast(0f)
            Modifier
                .offset {
                    IntOffset(
                        savedPos.x.coerceIn(0f, maxX).toInt(),
                        savedPos.y.coerceIn(0f, maxY).toInt(),
                    )
                }
                .size(28.dp)
        }
        Box(
            modifier = baseModifier
                .background(Color(0x669CA3AF), shape = CircleShape)
                .pointerInput(refreshLayerSize, refreshBtnSizePx) {
                    val endPadPx = with(density) { 6.dp.toPx() }
                    detectDragGestures(
                        onDragStart = {
                            // Switch from align() to offset() at the same pixel
                            // position so the button doesn't jump on first drag.
                            if (refreshBtnPos == null) {
                                val defaultX = refreshLayerSize.width - refreshBtnSizePx - endPadPx
                                val defaultY = (refreshLayerSize.height - refreshBtnSizePx) / 2f
                                refreshBtnPos = Offset(defaultX, defaultY)
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val cur = refreshBtnPos ?: Offset.Zero
                            refreshBtnPos = cur + drag
                        },
                        onDragEnd = {
                            // Persist on drag end, not per-frame, to avoid
                            // hammering DataStore during a fast swipe.
                            scope.launch {
                                refreshBtnPos?.let { context.saveRefreshButtonPos(it) }
                            }
                        },
                    )
                }
                .clickable {
                    pendingReload = true
                    webView.reload()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "刷新页面",
                tint = Color(0xFF1F2937),
                modifier = Modifier.size(16.dp),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

private enum class InjectResult { BridgeInvoked, BridgeMissing, ReadFailed }

/**
 * Read each URI's bytes off-thread via ContentResolver and inject them
 * into the WebView as base64 data URLs through the page's
 * window.lanAgentAttachImages([...]) hook. Distinguishes "bridge was
 * invoked" vs "page doesn't have the bridge" vs "native read failed"
 * so the caller can pick the right cancellation / fallback path.
 */
private suspend fun injectImagesToWebView(
    context: Context,
    webView: WebView,
    uris: Array<Uri>,
): InjectResult = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val jsArray = buildString {
            append("[")
            uris.forEachIndexed { i, uri ->
                if (i > 0) append(",")
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext InjectResult.ReadFailed
                // mime priority: ContentResolver hint → magic number sniff → jpeg.
                // The old "image/*" fallback silently killed attachments: zai's
                // readImageAsBase64 only accepts jpeg/png/gif/webp, and a File
                // with type "image/*" trips ImageReadError('unsupported_mime').
                val mime = resolver.getType(uri)
                    ?: detectImageMime(bytes)
                    ?: "image/jpeg"
                // lastPathSegment can be null or a deep DocumentsContract
                // path like "image%3A1234"; fall back to a stable name.
                val filename = uri.lastPathSegment?.substringAfterLast('/')
                    ?.substringAfterLast(':')
                    ?.takeIf { it.isNotBlank() }
                    ?: "image"
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                append("{\"dataUrl\":\"data:")
                appendJsonEscaped(mime)
                append(";base64,")
                append(b64)
                append("\",\"filename\":\"")
                appendJsonEscaped(filename)
                append("\",\"mime\":\"")
                appendJsonEscaped(mime)
                append("\"}")
            }
            append("]")
        }
        Log.i("WebViewScreen", "lan-agent injecting ${uris.size} image(s) via bridge")
        // Probe-style call: only invoke if the page registered the bridge.
        // The returned boolean tells the caller whether the page actually
        // had window.lanAgentAttachImages at call time — if not (stale zai
        // build), we fall back to the standard path so the user can still
        // upload images, just via WebView's own onShowFileChooser → File
        // conversion.
        val jsCall = "if(window.lanAgentAttachImages){window.lanAgentAttachImages($jsArray);true}else{false}"
        val invoked = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(jsCall) { value ->
                Log.i("WebViewScreen", "lanAgentAttachImages call returned: $value")
                invoked.complete(value == "true")
            }
        }
        if (invoked.await()) InjectResult.BridgeInvoked else InjectResult.BridgeMissing
    } catch (e: Exception) {
        Log.w("WebViewScreen", "lan-agent image inject failed", e)
        InjectResult.ReadFailed
    }
}

/**
 * Magic-number sniff for the four image MIME types zai accepts. The full
 * signature bytes are not always present in a small thumbnail header read,
 * so we match only the prefix the four formats reliably start with:
 *  - JPEG: FF D8 FF
 *  - PNG:  89 50 4E 47
 *  - GIF:  47 49 46 38 ("GIF8")
 *  - WEBP: 52 49 46 46 ... 57 45 42 50 ("RIFF" .... "WEBP", need 12 bytes)
 */
private fun detectImageMime(bytes: ByteArray): String? {
    if (bytes.size < 4) return null
    val b0 = bytes[0]; val b1 = bytes[1]; val b2 = bytes[2]; val b3 = bytes[3]
    return when {
        b0 == 0xFF.toByte() && b1 == 0xD8.toByte() && b2 == 0xFF.toByte() -> "image/jpeg"
        b0 == 0x89.toByte() && b1 == 0x50.toByte() &&
            b2 == 0x4E.toByte() && b3 == 0x47.toByte() -> "image/png"
        b0 == 0x47.toByte() && b1 == 0x49.toByte() &&
            b2 == 0x46.toByte() && b3 == 0x38.toByte() -> "image/gif"
        bytes.size >= 12 && b0 == 0x52.toByte() && b1 == 0x49.toByte() &&
            b2 == 0x46.toByte() && b3 == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"
        else -> null
    }
}

/** Minimal JSON string escape: backslash, double quote, control chars. */
private fun StringBuilder.appendJsonEscaped(s: String): StringBuilder {
    for (ch in s) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (ch.code < 0x20) {
                append("\\u%04x".format(ch.code))
            } else {
                append(ch)
            }
        }
    }
    return this
}