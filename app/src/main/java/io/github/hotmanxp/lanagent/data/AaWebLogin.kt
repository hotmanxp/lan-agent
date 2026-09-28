// data/AaWebLogin.kt — Agents-Anywhere OAuth「Web 登录」的 PKCE 纯逻辑(0.24.0)
//
// 搬自 Agents-Anywhere `android/.../feature/auth/WebLoginModels.kt`,但**只搬
// 算法,不搬 ViewModel / SharedPreferences / 网络** —— 这一层刻意不 import 任何
// Android 类(连 `android.util.Base64` 都不用,走 `java.util.Base64` —— minSdk 26
// 已经够),是为了让 `AaWebLoginTest` 能在纯 JVM 上钉住 PKCE 与回调解析。
//
// ## 为什么要 Web 登录
//
// 现有 `mobile-login` 扫码流(`ui/MobileLoginDialog.kt` 文件头写明)0.21.0 起
// `/auth/mobile-login/qr` **要求 Bearer token**,只适用「已登录设备换 token」。
// 全新安装想进「远程」栏,必须走 OAuth 授权码 + PKCE 这条路。
//
// ## 回调为什么不进 AndroidManifest
//
// `CALLBACK_URI = "agents-anywhere://oauth/callback"` 在本 App 里**永远由 WebView
// 的 `shouldOverrideUrlLoading` 拦截**,不会交给系统 intent 解析。所以不需要
// 声明 `<intent-filter>`(AA 端加是为了让桌面/其他 App 的系统级深链能跳进来)。
// 保持 `agents-anywhere` 这个字符串原样也很重要:它是 server 侧登记过的
// redirect_uri,自己改成别的 scheme 会被授权页拒。
package io.github.hotmanxp.lanagent.data

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 一次 Web 登录会话的三个机密材料 —— 都**只活在本次登录**,不进 DataStore,
 * 也不进导航参数(会留在 back stack 里)。
 */
data class AaWebLoginSession(
    val serverUrl: String,
    val authorizeUrl: String,
    /** 回调用来校验的防 CSRF 串。 */
    val state: String,
    val codeVerifier: String,
) {
    /**
     * 回调只消费一次 —— 同一个 code 换两次 token 第二次必然失败,而且在这之前
     * 就该判定为「这页已经结束了」。
     */
    @Volatile
    private var consumed = false

    fun consume(): Boolean = !consumed && synchronized(this) {
        if (consumed) false else { consumed = true; true }
    }
}

/** Web 登录回调的三种结果。 */
sealed interface AaWebLoginCallback {
    data class Success(val code: String) : AaWebLoginCallback
    data class Error(val message: String) : AaWebLoginCallback
    data class Invalid(val message: String) : AaWebLoginCallback
}

object AaWebLogin {
    /**
     * server 侧登记过的移动端 client id —— **不能自创**,换掉会被授权页拒。
     * 来源:Agents-Anywhere `feature/auth/WebLoginModels.kt:205`。
     */
    const val CLIENT_ID = "agents-anywhere-mobile"

    /** 授权成功后的回跳地址。WebView 内拦截,见文件头。 */
    const val CALLBACK_URI = "agents-anywhere://oauth/callback"

    private val random = SecureRandom()

    private fun urlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    /** RFC 7636 要求 verifier 43-128 字符;AA 端用 32 字节 → 43 字符。 */
    fun newCodeVerifier(): String = urlSafe(32)

    fun newState(): String = urlSafe(24)

    /** S256:`BASE64URL(SHA256(verifier))`,同样去 padding。 */
    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * 拼授权页 URL。
     *
     * ⚠️ **路径里有 `/#/`** —— server 的 web 前端是 hash 路由,query 必须接在
     * `#` **后面**。写成 `/?mobile-oauth?…` 授权页收不到参数,表现为「点了没反应」。
     */
    fun authorizeUrl(
        oauthWebOrigin: String,
        codeVerifier: String,
        state: String,
    ): String {
        val query = listOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to CALLBACK_URI,
            "code_challenge" to codeChallenge(codeVerifier),
            "code_challenge_method" to "S256",
            "scope" to "profile",
            "state" to state,
        ).joinToString("&") { (k, v) -> "$k=${uriEncode(v)}" }
        return "${oauthWebOrigin.trimEnd('/')}/#/mobile-oauth?$query"
    }

    fun newSession(
        serverUrl: String,
        oauthWebOrigin: String,
        codeVerifier: String = newCodeVerifier(),
        state: String = newState(),
    ): AaWebLoginSession = AaWebLoginSession(
        serverUrl = serverUrl,
        authorizeUrl = authorizeUrl(oauthWebOrigin, codeVerifier, state),
        state = state,
        codeVerifier = codeVerifier,
    )

    /**
     * 解析 WebView 拦到的回跳 URL。
     *
     * 三个失败分支各有归属,别混:
     *   - 不是本 App 的回跳地址 → [AaWebLoginCallback.Invalid],**不是错误**,
     *     授权页内部的正常跳转也会走这里
     *   - 带 `error` → [AaWebLoginCallback.Error],把服务端的话带给用户
     *   - `state` 对不上 → [AaWebLoginCallback.Invalid]。**这条必须校验** ——
     *     不校验等于把授权码暴露给任何能操控 WebView 的注入
     */
    fun parseCallback(callbackUrl: String, session: AaWebLoginSession): AaWebLoginCallback {
        if (!callbackUrl.startsWith(CALLBACK_URI, ignoreCase = true)) {
            return AaWebLoginCallback.Invalid("not a callback URL")
        }
        val params = parseQuery(callbackUrl.substringAfter('?'))

        params["error"]?.let { return AaWebLoginCallback.Error(it) }
        params["error_description"]?.let { return AaWebLoginCallback.Error(it) }

        val state = params["state"]
        if (state == null || state != session.state) {
            return AaWebLoginCallback.Invalid("state mismatch")
        }

        val code = params["code"]
        if (code.isNullOrBlank()) {
            return AaWebLoginCallback.Invalid("missing code")
        }
        if (!session.consume()) {
            return AaWebLoginCallback.Invalid("callback already consumed")
        }
        return AaWebLoginCallback.Success(code)
    }

    /**
     * 自己解 query —— 不能直接用 `URI.query`,回跳地址是自定义 scheme
     * `agents-anywhere://oauth/callback?…`,`java.net.URI` 对非 http scheme
     * 的 opaque URI 取 query 行为不可靠。
     */
    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val k = uriDecode(pair.substring(0, idx))
            val v = uriDecode(pair.substring(idx + 1))
            if (k.isEmpty()) null else k to v
        }.toMap()
    }

    private fun uriEncode(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    private fun uriDecode(raw: String): String = java.net.URLDecoder.decode(raw, "UTF-8")
}
