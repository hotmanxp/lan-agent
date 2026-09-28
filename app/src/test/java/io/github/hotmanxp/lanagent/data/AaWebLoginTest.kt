// app/src/test/.../data/AaWebLoginTest.kt — AA OAuth Web 登录的 PKCE 纯逻辑(0.24.0)
//
// 钉四组东西,每组都对应一个真实会咬人的坑:
//
//   1. PKCE 三件套的形状(verifier 长度落在 RFC 7636 的 43-128、challenge 是
//      S256 且 base64url 无 padding、state 每次不同)
//   2. authorizeUrl 的 `/#/` 位置 —— 写成 `/?` 授权页收不到参数,表现为
//      「点了没反应」,而且不报错,极难定位
//   3. 回调解析的四个分支,其中 **state 校验**是安全项(不校验等于把授权码
//      暴露给任何能操控 WebView 的注入)
//   4. 回调只消费一次 —— 同一个 code 换两次 token 第二次必然失败
package io.github.hotmanxp.lanagent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class AaWebLoginTest {

    private fun newSession() = AaWebLogin.newSession(
        serverUrl = "https://server.example.com",
        oauthWebOrigin = "https://web.example.com",
    )

    // ── PKCE 三件套 ────────────────────────────────────────────────────

    @Test
    fun `codeVerifier 长度落在 RFC 7636 要求的 43 到 128 之间`() {
        repeat(20) {
            val verifier = AaWebLogin.newCodeVerifier()
            assertTrue(
                "verifier 长度 ${verifier.length} 越界",
                verifier.length in 43..128,
            )
        }
    }

    @Test
    fun `codeVerifier 是 URL-safe 字符集且无 padding`() {
        val verifier = AaWebLogin.newCodeVerifier()
        assertTrue("含 + 或 /", verifier.none { it == '+' || it == '/' })
        assertTrue("含 =", '=' !in verifier)
    }

    @Test
    fun `codeChallenge 是 S256 且不带 padding`() {
        val verifier = AaWebLogin.newCodeVerifier()
        val challenge = AaWebLogin.codeChallenge(verifier)
        // S256 输出固定 32 字节 → base64url 去 padding 后 43 字符。
        assertEquals(43, challenge.length)
        assertTrue("含 =", '=' !in challenge)
    }

    @Test
    fun `codeChallenge 对同一 verifier 稳定,换 verifier 就变`() {
        val v = AaWebLogin.newCodeVerifier()
        assertEquals(AaWebLogin.codeChallenge(v), AaWebLogin.codeChallenge(v))
        assertNotEquals(AaWebLogin.codeChallenge(v), AaWebLogin.codeChallenge(v + "x"))
    }

    @Test
    fun `state 每次生成都不同`() {
        val states = (1..20).map { AaWebLogin.newState() }
        assertEquals(states.size, states.toSet().size)
    }

    // ── authorizeUrl ───────────────────────────────────────────────────

    @Test
    fun `authorizeUrl 的 hash 路由位置正确`() {
        val url = newSession().authorizeUrl
        // server 前端是 hash 路由,query 必须接在 # 后面。
        assertTrue("缺 hash 路由: $url", url.startsWith("https://web.example.com/#/mobile-oauth?"))
    }

    @Test
    fun `authorizeUrl 带齐七个 OAuth 参数`() {
        val url = newSession().authorizeUrl
        val query = url.substringAfter('?')
        listOf(
            "response_type=code",
            "client_id=${AaWebLogin.CLIENT_ID}",
            "code_challenge_method=S256",
            "scope=profile",
        ).forEach { assertTrue("缺 $it: $query", query.contains(it)) }
        assertTrue(query.contains("redirect_uri="))
        assertTrue(query.contains("code_challenge="))
        assertTrue(query.contains("state="))
    }

    @Test
    fun `redirect_uri 端到端编码往返无损`() {
        val url = newSession().authorizeUrl
        val encoded = url.substringAfter("redirect_uri=").substringBefore('&')
        assertEquals(AaWebLogin.CALLBACK_URI, URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun `origin 结尾的斜杠不会拼出双斜杠`() {
        val s = AaWebLogin.newSession(
            serverUrl = "https://server.example.com",
            oauthWebOrigin = "https://web.example.com/",
        )
        assertTrue(s.authorizeUrl.startsWith("https://web.example.com/#/"))
    }

    // ── 回调解析 ───────────────────────────────────────────────────────

    @Test
    fun `正确回调解析出 code`() {
        val s = newSession()
        val result = AaWebLogin.parseCallback(
            "${AaWebLogin.CALLBACK_URI}?code=abc123&state=${s.state}",
            s,
        )
        assertEquals(AaWebLoginCallback.Success("abc123"), result)
    }

    @Test
    fun `非回跳地址是 Invalid 而不是 Error`() {
        val s = newSession()
        // 授权页内部的普通跳转也会走这里 —— 不能当成错误弹给用户。
        val result = AaWebLogin.parseCallback("https://web.example.com/login", s)
        assertTrue(result is AaWebLoginCallback.Invalid)
    }

    @Test
    fun `error 参数给出可读错误`() {
        val s = newSession()
        val result = AaWebLogin.parseCallback(
            "${AaWebLogin.CALLBACK_URI}?error=access_denied&state=${s.state}",
            s,
        )
        assertEquals(AaWebLoginCallback.Error("access_denied"), result)
    }

    /** 安全项:state 对不上必须失败,否则授权码会被交给错误的接收方。 */
    @Test
    fun `state 不匹配被拒`() {
        val s = newSession()
        val result = AaWebLogin.parseCallback(
            "${AaWebLogin.CALLBACK_URI}?code=abc&state=forged",
            s,
        )
        assertTrue(result is AaWebLoginCallback.Invalid)
    }

    @Test
    fun `缺 state 被拒`() {
        val s = newSession()
        val result = AaWebLogin.parseCallback("${AaWebLogin.CALLBACK_URI}?code=abc", s)
        assertTrue(result is AaWebLoginCallback.Invalid)
    }

    @Test
    fun `有 state 但缺 code 被拒`() {
        val s = newSession()
        val result = AaWebLogin.parseCallback(
            "${AaWebLogin.CALLBACK_URI}?state=${s.state}",
            s,
        )
        assertTrue(result is AaWebLoginCallback.Invalid)
    }

    @Test
    fun `code 做了一次 url 解码`() {
        val s = newSession()
        val result = AaWebLogin.parseCallback(
            "${AaWebLogin.CALLBACK_URI}?code=a%2Bb%2Fc&state=${s.state}",
            s,
        )
        assertEquals(AaWebLoginCallback.Success("a+b/c"), result)
    }

    @Test
    fun `回调只消费一次`() {
        val s = newSession()
        val url = "${AaWebLogin.CALLBACK_URI}?code=abc&state=${s.state}"
        assertTrue(AaWebLogin.parseCallback(url, s) is AaWebLoginCallback.Success)
        // 同一个 code 换第二次必然失败,更早一步就该挡住。
        assertTrue(AaWebLogin.parseCallback(url, s) is AaWebLoginCallback.Invalid)
    }

    @Test
    fun `被拒的回调不消费额度,合法回调仍能过`() {
        val s = newSession()
        // 先来一次 state 不匹配的 —— 它在 consume 之前就返回了。
        AaWebLogin.parseCallback("${AaWebLogin.CALLBACK_URI}?code=x&state=bad", s)
        assertTrue(
            AaWebLogin.parseCallback(
                "${AaWebLogin.CALLBACK_URI}?code=ok&state=${s.state}",
                s,
            ) is AaWebLoginCallback.Success
        )
    }
}
