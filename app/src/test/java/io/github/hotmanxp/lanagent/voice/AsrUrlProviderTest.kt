// voice/AsrUrlProviderTest.kt — 握手地址构造的回归保护。
//
// 覆盖范围：
//   · AsrUrlProvider.Local —— 调 TencentAsrSignature.sign()，纯 HMAC-SHA1 链路
//   · AsrUrlProvider.WorkBuddy —— 纯字符串拼接（https→wss、Authorization、session_id 正则）
//
// 不覆盖：
//   · AsrUrlProvider.Remote —— 内部用 org.json.JSONObject 解析后端响应，
//     org.json 在纯 JVM 单元测试里只有 Android stub（无 Robolectric），会 Stub! 崩。
//   · WorkBuddyAsrAuth.expiresAtFromJwt / fromAuthFileJson —— 同上（android.util.Base64、
//     org.json.JSONObject）。这类走集成 / 装机手测覆盖。
package io.github.hotmanxp.lanagent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsrUrlProviderTest {

    // ── Local ────────────────────────────────────────────────────────────

    @Test
    fun `Local provider signs via Tencent Cloud path`() {
        val signed = AsrUrlProvider.Local(
            appId = "1250000000",
            secretId = "AKIDz8krAsJ39gP9YkqB3z0K1z2K3z4K5K6K7",
            secretKey = "Gu6tS9kG7K8kG7K8kG7K8kG7K8kG7K8k",
        ).provide("16k_zh")

        assertTrue(signed.url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1250000000?"))
        // 腾讯云走 URL 签名，不带 header
        assertTrue(signed.headers.isEmpty(), "腾讯云签名鉴权不应该带 header：${signed.headers}")
        assertEquals(AsrDialect.TencentCloud, signed.dialect)
        assertEquals(32, signed.voiceId.length)
    }

    @Test
    fun `Local provider passes engine through`() {
        val signed = AsrUrlProvider.Local(
            appId = "1250000000",
            secretId = "AKIDz8krAsJ39gP9YkqB3z0K1z2K3z4K5K6K7",
            secretKey = "Gu6tS9kG7K8kG7K8kG7K8kG7K8kG7K8k",
        ).provide("16k_zh_en")
        assertTrue(
            signed.url.contains("engine_model_type=16k_zh_en"),
            "engine 应该透传：${signed.url}",
        )
    }

    // ── WorkBuddy ────────────────────────────────────────────────────────

    @Test
    fun `WorkBuddy provider upgrades https to wss`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "fake-jwt" },
        ).provide("16k_zh")

        assertTrue(signed.url.startsWith("wss://copilot.tencent.com/"), "URL 头：${signed.url}")
        assertEquals(AsrDialect.WorkBuddy, signed.dialect)
        assertEquals("Bearer fake-jwt", signed.headers["Authorization"])
        assertTrue(signed.url.contains("source=desktop"), "必须带 source=desktop：${signed.url}")
        assertTrue(signed.url.contains("/clientcap/v2/asr/stream"), "URL path 必须是 stream：${signed.url}")
    }

    @Test
    fun `WorkBuddy provider keeps http as ws for local development`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "http://localhost:8443",
            tokenProvider = { "fake-jwt" },
        ).provide("16k_zh")

        assertTrue(signed.url.startsWith("ws://localhost:8443/"), "URL 头：${signed.url}")
    }

    @Test
    fun `WorkBuddy provider rejects non-http schemes`() {
        val provider = AsrUrlProvider.WorkBuddy(
            endpoint = "ftp://example.com",
            tokenProvider = { "fake-jwt" },
        )
        assertFailsWith<IllegalArgumentException> {
            provider.provide("16k_zh")
        }
    }

    @Test
    fun `WorkBuddy provider requires non-empty token`() {
        val provider = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "" },
        )
        assertFailsWith<IllegalArgumentException> {
            provider.provide("16k_zh")
        }
    }

    @Test
    fun `WorkBuddy provider includes X-User-Id when uid provided`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "fake-jwt" },
            uid = "9e5512e4-34fc-4419-9122-914c64f69c1d",
        ).provide("16k_zh")
        assertEquals("9e5512e4-34fc-4419-9122-914c64f69c1d", signed.headers["X-User-Id"])
    }

    @Test
    fun `WorkBuddy provider omits X-User-Id when uid is blank`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "fake-jwt" },
            uid = "   ",
        ).provide("16k_zh")
        assertFalse("X-User-Id" in signed.headers, "uid 空白时不应带 X-User-Id")
    }

    @Test
    fun `WorkBuddy provider includes session_id when it matches regex`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "fake-jwt" },
            sessionId = "abc123-_",
        ).provide("16k_zh")
        assertTrue(
            signed.url.contains("session_id=abc123-_"),
            "合法 session_id 必须带上：${signed.url}",
        )
    }

    @Test
    fun `WorkBuddy provider drops session_id that fails regex`() {
        // 正则是 ^[a-zA-Z0-9-_]{1,128}$ — 带空格或超长都该丢
        val tooLong = "a".repeat(129)
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "fake-jwt" },
            sessionId = tooLong,
        ).provide("16k_zh")
        assertFalse(
            "session_id" in signed.url,
            "超长 session_id 应当被服务端正则拒，这里必须丢弃：${signed.url}",
        )
    }

    @Test
    fun `WorkBuddy provider session_id with spaces is rejected`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "fake-jwt" },
            sessionId = "abc 123",
        ).provide("16k_zh")
        assertFalse("session_id" in signed.url, "含空格的 session_id 必丢：${signed.url}")
    }

    @Test
    fun `WorkBuddy provider voiceId is 32-char hex`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com",
            tokenProvider = { "fake-jwt" },
        ).provide("16k_zh")
        assertEquals(32, signed.voiceId.length)
        assertTrue(signed.voiceId.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `WorkBuddy provider trims trailing slash on endpoint`() {
        val signed = AsrUrlProvider.WorkBuddy(
            endpoint = "https://copilot.tencent.com/",
            tokenProvider = { "fake-jwt" },
        ).provide("16k_zh")
        // 不该出现 //clientcap
        assertFalse(
            signed.url.contains("//clientcap"),
            "endpoint 末尾 / 应该 trim 掉，否则变 //clientcap：${signed.url}",
        )
        // path 必须存在
        assertTrue(
            signed.url.contains("/clientcap/v2/asr/stream"),
            "必须包含 asr/stream 路径：${signed.url}",
        )
    }
}
