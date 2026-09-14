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

class WorkBuddyApiTest {

    private val fakeJwt = "fake-jwt"

    /** 拼一份完整的服务端响应；不指定某字段时直接省略该 key。 */
    private fun serverResponse(
        ok: Boolean = true,
        endpoint: String? = "https://copilot.tencent.com",
        accessToken: String = fakeJwt,
        uid: String? = null,
        error: String? = null,
    ): String {
        val sb = StringBuilder("{")
        val parts = mutableListOf<String>()
        parts += "\"ok\":$ok"
        endpoint?.let { parts += "\"endpoint\":\"$it\"".replace("\\", "\\\\") }
        parts += "\"accessToken\":\"$accessToken\""
        uid?.let { parts += "\"uid\":\"$it\"" }
        error?.let { parts += "\"error\":\"$it\"".replace("\\", "\\\\") }
        sb.append(parts.joinToString(","))
        sb.append("}")
        return sb.toString()
    }

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