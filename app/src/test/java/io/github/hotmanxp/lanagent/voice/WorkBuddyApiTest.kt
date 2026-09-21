// voice/WorkBuddyApiTest.kt — `AsrUrlProvider.WorkBuddyApi` 的回归保护。
//
// 覆盖范围：
//   · 服务端 JSON 响应解析（ok / accessToken / endpoint / uid 四种字段组合）
//   · HTTP 异常 / 错误响应 → 抛异常路径
//   · 端到端：解析结果 → WorkBuddy provider → SignedAsrUrl 形状
//   · baseUrl 末尾 `/` 拼接不会产生 `//api/voice/getASRToken`
//
// 之前的 `AsrUrlProviderTest` 因为 `org.json.JSONObject` 在 JVM 单测下是 Android
// stub，跳过了这条 provider。这条测试需要 `app/build.gradle.kts` 加
// `testImplementation(libs.org.json)`（真实实现覆盖 stub）才能跑起来 ——
// build.gradle.kts 已配。
package io.github.hotmanxp.lanagent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val fakeJwt = "fake-jwt"

/** 假时钟原点和几个常用跨度。缓存过期判定全部按毫秒算。 */
private const val NOW = 1_700_000_000_000L
private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN

/** 拼一份完整的服务端响应；不指定某字段时直接省略该 key。 */
private fun serverResponse(
    ok: Boolean = true,
    endpoint: String? = "https://copilot.tencent.com",
    accessToken: String = fakeJwt,
    uid: String? = null,
    error: String? = null,
    /** epoch 毫秒。null = 服务端也不知道过期时间（响应里不带这个 key）。 */
    expiresAt: Long? = null,
): String {
    val parts = mutableListOf<String>()
    parts += "\"ok\":$ok"
    endpoint?.let { parts += "\"endpoint\":\"$it\"".replace("\\", "\\\\") }
    parts += "\"accessToken\":\"$accessToken\""
    uid?.let { parts += "\"uid\":\"$it\"" }
    error?.let { parts += "\"error\":\"$it\"".replace("\\", "\\\\") }
    expiresAt?.let { parts += "\"expiresAt\":$it" }
    return "{" + parts.joinToString(",") + "}"
}

/**
 * 假时钟 + 计数的 provider 夹具。
 *
 * [now] 可以改，用来把时间推到缓存过期 / 进入 skew；[fetches] 数发了几次 GET ——
 * 「有没有吃缓存」全靠它断言。[token] 可以换，模拟桌面端重新登录后服务端下发新 token。
 */
private class Harness(expiresAtMs: Long?) {
    var now: Long = NOW
    var fetches: Int = 0
    var token: String = fakeJwt

    val provider = AsrUrlProvider.WorkBuddyApi(
        baseUrl = "http://192.168.1.10:9201",
        httpGet = {
            fetches++
            serverResponse(accessToken = token, expiresAt = expiresAtMs)
        },
        nowMs = { now },
    )
}

class WorkBuddyApiTest {

    /** 构造一个 provider，`httpGet` 由调用方注入（一般返回上面的 serverResponse）。 */
    private fun provider(
        body: String,
        baseUrl: String = "http://192.168.1.10:9201",
        tokenPath: String = "/api/voice/getASRToken",
    ) = AsrUrlProvider.WorkBuddyApi(
        baseUrl = baseUrl,
        httpGet = {
            // 顺便验一下 url 形状（baseUrl trim + path 拼接）
            val expected = baseUrl.trimEnd('/') + tokenPath
            assertEquals(expected, it, "httpGet 收到的 URL 不对")
            body
        },
        tokenPath = tokenPath,
    )

    // ── 解析层(parseResponse)──────────────────────────────────────────

    @Test
    fun `parseResponse accepts full payload`() {
        val parsed = AsrUrlProvider.WorkBuddyApi.parseResponse(
            serverResponse(endpoint = "https://copilot.tencent.com", uid = "u-1")
        )
        assertEquals("https://copilot.tencent.com", parsed.endpoint)
        assertEquals(fakeJwt, parsed.accessToken)
        assertEquals("u-1", parsed.uid)
    }

    @Test
    fun `parseResponse leaves endpoint blank when missing so caller can default-fallback`() {
        // 注意：缺省回落由调用方负责（provide() 里 .ifBlank { DEFAULT_ENDPOINT }）。
        // parseResponse 自身**不**做回落 —— 让它保持纯函数无副作用。
        val parsed = AsrUrlProvider.WorkBuddyApi.parseResponse(
            serverResponse(endpoint = null)
        )
        assertEquals("", parsed.endpoint, "缺 endpoint 时 parseResponse 必须原样返回空串")
    }

    @Test
    fun `parseResponse returns null uid when missing or blank`() {
        val noUid = AsrUrlProvider.WorkBuddyApi.parseResponse(serverResponse(uid = null))
        assertNull(noUid.uid)

        val blankUid = AsrUrlProvider.WorkBuddyApi.parseResponse(serverResponse(uid = "   "))
        assertNull(blankUid.uid, "uid 是空白字符时也应视为 null，不带 X-User-Id")
    }

    @Test
    fun `parseResponse throws on ok false`() {
        assertFailsWith<IllegalStateException> {
            AsrUrlProvider.WorkBuddyApi.parseResponse(
                serverResponse(ok = false, error = "未登录")
            )
        }
    }

    @Test
    fun `parseResponse throws on ok false with no error message`() {
        // error 字段缺省时也要抛 —— 不能静默吞掉
        assertFailsWith<IllegalStateException> {
            AsrUrlProvider.WorkBuddyApi.parseResponse(serverResponse(ok = false, error = null))
        }
    }

    @Test
    fun `parseResponse requires accessToken`() {
        assertFailsWith<IllegalArgumentException> {
            AsrUrlProvider.WorkBuddyApi.parseResponse(serverResponse(accessToken = ""))
        }
    }

    @Test
    fun `parseResponse treats missing ok as true`() {
        // 旧版本服务端没 ok ok 字段时不应拒 —— 走「默认真」的语义
        val body = """{"endpoint":"https://copilot.tencent.com","accessToken":"$fakeJwt"}"""
        val parsed = AsrUrlProvider.WorkBuddyApi.parseResponse(body)
        assertEquals(fakeJwt, parsed.accessToken)
    }

    // ── 端到端(provide)───────────────────────────────────────────────

    @Test
    fun `provide builds a WorkBuddy SignedAsrUrl from server payload`() {
        val signed = provider(
            body = serverResponse(uid = "u-1"),
        ).provide("16k_zh")

        assertTrue(signed.url.startsWith("wss://copilot.tencent.com/"), "URL 头：${signed.url}")
        assertTrue(signed.url.contains("/clientcap/v2/asr/stream"))
        assertEquals(AsrDialect.WorkBuddy, signed.dialect)
        assertEquals("Bearer $fakeJwt", signed.headers["Authorization"])
        assertEquals("u-1", signed.headers["X-User-Id"])
    }

    @Test
    fun `provide falls back to DEFAULT_ENDPOINT when server omits endpoint`() {
        val signed = provider(
            body = serverResponse(endpoint = null),
        ).provide("16k_zh")
        assertTrue(
            signed.url.startsWith("wss://copilot.tencent.com/"),
            "缺 endpoint 必须回落 DEFAULT：${signed.url}",
        )
    }

    @Test
    fun `provide omits X-User-Id when uid is blank`() {
        val signed = provider(
            body = serverResponse(uid = "   "),
        ).provide("16k_zh")
        assertFalse(
            "X-User-Id" in signed.headers,
            "uid 空白时不应带 X-User-Id：${signed.headers}",
        )
    }

    @Test
    fun `provide trims trailing slash on baseUrl`() {
        val p = AsrUrlProvider.WorkBuddyApi(
            baseUrl = "http://192.168.1.10:9201/",
            httpGet = { url ->
                assertFalse(
                    url.contains("//api"),
                    "baseUrl 末尾 / 必须 trim，否则拼出 //api：$url",
                )
                assertTrue(url.endsWith("/api/voice/getASRToken"), "URL：$url")
                serverResponse()
            },
        )
        val signed = p.provide("16k_zh")
        assertNotNull(signed)
    }

    @Test
    fun `provide propagates httpGet exception`() {
        val p = AsrUrlProvider.WorkBuddyApi(
            baseUrl = "http://192.168.1.10:9201",
            httpGet = { throw java.io.IOException("服务端没起") },
        )
        assertFailsWith<java.io.IOException> {
            p.provide("16k_zh")
        }
    }

    @Test
    fun `provide uses custom tokenPath when provided`() {
        val p = AsrUrlProvider.WorkBuddyApi(
            baseUrl = "http://localhost:9201",
            httpGet = { url ->
                assertTrue(url.endsWith("/custom/path"), "custom tokenPath 必须生效：$url")
                serverResponse()
            },
            tokenPath = "/custom/path",
        )
        assertNotNull(p.provide("16k_zh"))
    }

    // ── 缓存 / 401 自愈 ──────────────────────────────────────────────

    @Test
    fun `provide reuses the cached credential while it is fresh`() {
        val h = Harness(expiresAtMs = NOW + 3 * DAY)
        h.provider.provide("16k_zh")
        h.provider.provide("16k_zh")
        assertEquals(1, h.fetches, "距过期还有 3 天,第二次必须吃缓存而不是再发 GET")
    }

    @Test
    fun `provide re-fetches once the cached credential is inside the 5 minute skew`() {
        val h = Harness(expiresAtMs = NOW + 10 * MIN)
        h.provider.provide("16k_zh")
        assertEquals(1, h.fetches, "距过期 10 分钟,应该吃缓存")

        h.now = NOW + 6 * MIN // 只剩 4 分钟,小于提前量 5 分钟
        h.provider.provide("16k_zh")
        assertEquals(2, h.fetches, "进入 5 分钟 skew 必须重取,否则建连就卡在到期边界上")
    }

    @Test
    fun `provide re-fetches after the cached credential expired`() {
        val h = Harness(expiresAtMs = NOW + DAY)
        h.provider.provide("16k_zh")
        h.now = NOW + DAY + 1
        h.provider.provide("16k_zh")
        assertEquals(2, h.fetches)
    }

    @Test
    fun `invalidateAuth forces the next provide to re-fetch`() {
        val h = Harness(expiresAtMs = NOW + 3 * DAY)
        h.provider.provide("16k_zh")
        h.provider.invalidateAuth()
        h.provider.provide("16k_zh")
        assertEquals(2, h.fetches, "401 之后必须重新问服务端,不能继续拿缓存里那份撞墙")
    }

    @Test
    fun `invalidateAuth picks up the rotated token the server has by then`() {
        // 模拟「桌面端重新登录」：服务端现读文件已经换了一份 token。
        // 客户端 401 → 清缓存 → 重取，必须拿到新的那份。
        val h = Harness(expiresAtMs = NOW + 3 * DAY)
        val first = h.provider.provide("16k_zh")
        assertEquals("Bearer $fakeJwt", first.headers["Authorization"])

        h.token = "rotated-jwt"
        h.provider.invalidateAuth()
        val second = h.provider.provide("16k_zh")
        assertEquals("Bearer rotated-jwt", second.headers["Authorization"])
    }

    @Test
    fun `provide does not cache when the server omits expiresAt`() {
        val h = Harness(expiresAtMs = null)
        h.provider.provide("16k_zh")
        h.provider.provide("16k_zh")
        assertEquals(2, h.fetches, "过期时间未知就不缓存 —— 宁可多发一次 GET，也不押没把握的 token")
    }

    // ── 解析层：expiresAt ────────────────────────────────────────────

    @Test
    fun `parseResponse reads expiresAt in epoch millis`() {
        val parsed = AsrUrlProvider.WorkBuddyApi.parseResponse(
            serverResponse(expiresAt = 1_789_619_362_000L) // 13 位,与服务端口径一致
        )
        assertEquals(1_789_619_362_000L, parsed.expiresAtMs)
    }

    @Test
    fun `parseResponse leaves expiresAt at zero when missing or non positive`() {
        assertEquals(
            0L,
            AsrUrlProvider.WorkBuddyApi.parseResponse(serverResponse()).expiresAtMs,
            "响应里没有 expiresAt 时必须落成 0",
        )
        assertEquals(
            0L,
            AsrUrlProvider.WorkBuddyApi.parseResponse(serverResponse(expiresAt = 0L)).expiresAtMs,
        )
        assertEquals(
            0L,
            AsrUrlProvider.WorkBuddyApi.parseResponse(serverResponse(expiresAt = -1L)).expiresAtMs,
        )
    }

    @Test
    fun `provide does not call tokenProvider a second time within the same call`() {
        // tokenProvider 是 `{ accessToken }` —— 同一次 provide 只能取一次，
        // 否则相当于在 provide 跑的时候又重发 HTTP / 重新构造，会让上层
        // 误以为 token 变了。这里用一个会累计调用次数的 lambda 钉死。
        var calls = 0
        val capturedToken = "captured-$fakeJwt"
        val p = AsrUrlProvider.WorkBuddyApi(
            baseUrl = "http://192.168.1.10:9201",
            httpGet = { serverResponse(accessToken = capturedToken) },
        )
        // 直接调底层 WorkBuddy provider 验证：用同样的 tokenProvider 测试一次 build
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = {
                calls++
                capturedToken
            },
        ).provide("16k_zh")
        assertEquals(1, calls, "WorkBuddy.provide 必须只调一次 tokenProvider：$calls")
        assertEquals("Bearer $capturedToken", signed.headers["Authorization"])
    }
}