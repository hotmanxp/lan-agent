// ui/AaWebLoginScreen.kt — AA OAuth Web 登录(0.24.0,路由 aa-web-login)
//
// ## 为什么需要这一屏
//
// 0.21.0 起 `POST /auth/mobile-login/qr` **要求 Bearer token**,扫码流只适用
// 「已登录设备换 token」。全新安装想进「远程」栏,只能走 OAuth 授权码 + PKCE
// (server `routes/api/auth.py` 的 mobile-oauth)。
//
// ## 回调为什么不进 AndroidManifest
//
// `agents-anywhere://oauth/callback` **全程在 WebView 的
// `shouldOverrideUrlLoading` 里拦**,不会交给系统 intent 解析,所以不需要
// `<intent-filter>`。字符串保持原样是因为它就是 server 侧登记过的 redirect_uri,
// 改了会被授权页拒(见 `data/AaWebLogin.kt` 文件头)。
//
// ## WebView 用哪个工厂
//
// 走 `WebViewFactory.create` —— AGENTS.md §8 要求 settings 单源,前台保活服务
// 与 `WebViewScreen` 都从那儿建,自己另写一份会让 settings 漂移。
// 但**不能**用 `WebViewScreen` 本身:它是通用全屏页,没有回调拦截钩子。
package io.github.hotmanxp.lanagent.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AaWebLogin
import io.github.hotmanxp.lanagent.data.AaWebLoginCallback
import io.github.hotmanxp.lanagent.data.AaWebLoginSession
import io.github.hotmanxp.lanagent.data.SecureTokenStore
import io.github.hotmanxp.lanagent.service.WebViewFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AaWebLoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt = rememberAaRuntime()
    val snackbar = remember { SnackbarHostState() }

    var session by remember { mutableStateOf<AaWebLoginSession?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }

    // 授权页 origin:server 的 web 前端通常与 API 同源不同端口(AA 仓库里是
    // `oauthWebOrigin` 单独配的)。这里保守取 baseUrl 的 origin —— 拿不到就
    // 退 baseUrl 本身,让用户看到失败信息而不是白屏。
    val oauthOrigin = remember(rt.baseUrl) {
        runCatching { java.net.URI(rt.baseUrl.trim()).let { "${it.scheme}://${it.authority}" } }
            .getOrDefault(rt.baseUrl.trim())
    }

    fun begin() {
        if (!rt.configured) return
        val s = AaWebLogin.newSession(serverUrl = rt.baseUrl.trim(), oauthWebOrigin = oauthOrigin)
        session = s
        started = true
    }

    fun onCallbackUrl(url: String) {
        val s = session ?: return
        when (val cb = AaWebLogin.parseCallback(url, s)) {
            is AaWebLoginCallback.Success -> scope.launch {
                runCatching { rt.api.oauthToken(cb.code, s.codeVerifier) }
                    .onSuccess { token ->
                        rt.prefs.setAccessToken(token.accessToken)
                        token.refreshToken?.let {
                            SecureTokenStore.get(context).putRefreshToken(it)
                        }
                        status = context.getString(R.string.aa_web_login_done)
                        // 回设置栏:token 变更会让 accessTokenFlow emit,那边自动刷新。
                        onBack()
                    }
                    .onFailure { err ->
                        status = context.getString(
                            R.string.aa_web_login_failed,
                            err.message ?: err.javaClass.simpleName,
                        )
                    }
            }
            is AaWebLoginCallback.Error -> {
                status = context.getString(R.string.aa_web_login_failed, cb.message)
            }
            // Invalid 既可能是 state 不匹配(要当错误)也可能只是授权页内部的
            // 普通跳转(不是回跳)—— 后者静默忽略即可,不弹错误吓人。
            is AaWebLoginCallback.Invalid -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aa_web_login_title)) },
                navigationIcon = { WbBackIcon(onBack) },
            )
        },
        snackbarHost = { WbToastHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!started) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.aa_web_login_hint),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Button(
                            onClick = ::begin,
                            enabled = rt.configured,
                            modifier = Modifier.padding(top = 20.dp),
                        ) {
                            Text(stringResource(R.string.aa_web_login_start))
                        }
                    }
                }
            } else {
                AaLoginWebView(
                    url = session?.authorizeUrl.orEmpty(),
                    onIntercept = ::onCallbackUrl,
                )
            }
            status?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/**
 * 授权页 WebView。
 *
 * `shouldOverrideUrlLoading` **两个重载都要接**:API 24+ 走 `WebResourceRequest`,
 * 但同一次跳转也可能落到旧重载;只接一个的表现是「有的链接拦得住有的拦不住」,
 * 很难复现。
 */
@Composable
private fun AaLoginWebView(url: String, onIntercept: (String) -> Unit) {
    val context = LocalContext.current
    // 回调捕获的是首帧的闭包;rememberUpdatedState 让它始终指向最新的
    // (onCallbackUrl 依赖 session / api,这些都会变)。
    val currentOnIntercept by rememberUpdatedState(onIntercept)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebViewFactory.createForContent(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = handle(request?.url?.toString())

                    @Deprecated("兼容旧重载", ReplaceWith(""))
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        handle(url)

                    fun handle(target: String?): Boolean {
                        if (target == null) return false
                        // 只在命中回跳 scheme 时吞掉;其余一律让 WebView 自己走,
                        // 否则授权页内的正常跳转全被吃掉。
                        if (target.startsWith(AaWebLogin.CALLBACK_URI, ignoreCase = true)) {
                            currentOnIntercept(target)
                            return true
                        }
                        return false
                    }
                }
            }
        },
        update = { webView ->
            if (webView.url != url && url.isNotBlank()) webView.loadUrl(url)
        },
    )
}
