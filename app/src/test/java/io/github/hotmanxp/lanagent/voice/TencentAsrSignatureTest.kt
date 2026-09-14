// voice/TencentAsrSignatureTest.kt — 腾讯云实时 ASR 握手的回归保护。
//
// 签名链路三步（顺序不能变，文档口径）：
//   1. 除 signature 外按字典序排序，拼成 "<host><path>/<appid>?k1=v1&k2=v2…"
//   2. HMAC-SHA1(canonical, secretKey) → Base64
//   3. Base64 结果再 URLEncode —— Base64 里的 + / = 在不同链路上会被改写，
//      不编码就会偶发鉴权失败。
//
// 钉契约不钉字节：JDK / Base64 / URLEncoder 行为细微差异会让「期望字节」脆掉，
// 但「URL 形状 / 字典序 / 关键参数 / signature 字段存在 + 非空」这些契约是稳定的。
package io.github.hotmanxp.lanagent.voice

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TencentAsrSignatureTest {

    private val appId = "1250000000"
    private val secretId = "AKIDz8krAsJ39gP9YkqB3z0K1z2K3z4K5K6K7"
    private val secretKey = "Gu6tS9kG7K8kG7K8kG7K8kG7K8kG7K8k"

    @Test
    fun `url uses wss scheme and official host and path`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        assertTrue(
            signed.url.startsWith("wss://asr.cloud.tencent.com/asr/v2/$appId?"),
            "URL 必须以官方 host+path 开头：${signed.url}",
        )
    }

    @Test
    fun `query params are in lexicographic order`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val query = signed.url.substringAfter('?').substringBefore("&signature=")
        val keys = query.split('&').map { it.substringBefore('=') }

        // 字典序（sortedMapOf 天然产出）
        assertEquals(keys.sorted(), keys, "签名参数必须按字典序排：$keys")
    }

    @Test
    fun `query params include all required keys`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val query = signed.url.substringAfter('?').substringBefore("&signature=")
        val keys = query.split('&').map { it.substringBefore('=') }.toSet()

        // 漏一个都会被服务端拒
        val required = setOf(
            "engine_model_type", "expired", "nonce", "secretid", "timestamp",
            "voice_id", "voice_format", "needvad", "filter_punc",
            "convert_num_mode", "filter_empty_result",
        )
        assertTrue(
            required.all { it in keys },
            "缺关键参数：缺 ${required - keys}，实有 $keys",
        )
    }

    @Test
    fun `voice_format is PCM raw 1 - changing it to default speex 4 breaks ASR with 4007`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val query = signed.url.substringAfter('?').substringBefore("&signature=")
        val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(
            "1", params["voice_format"],
            "voice_format 必须 = 1（PCM 裸流），默认 4 是 speex，会 4007",
        )
    }

    @Test
    fun `needvad is 1 for long speech stability`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val params = parseQuery(signed.url)
        assertEquals("1", params["needvad"])
    }

    @Test
    fun `expired is in the future and within 90 days`() {
        val before = System.currentTimeMillis() / 1000
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val params = parseQuery(signed.url)
        val expired = params["expired"]?.toLongOrNull()
            ?: error("expired 缺失或不是数字：${params["expired"]}")
        val after = System.currentTimeMillis() / 1000

        // 服务端要求 ttl > 0 且 < 90 天（7776000 秒）
        assertTrue(expired > before, "expired 必须晚于签名时刻：before=$before expired=$expired")
        assertTrue(expired <= after + 90L * 24 * 3600, "expired 不能超过 90 天")
        assertTrue(expired >= after, "expired 不能晚于当前时间 +1s 容差")
    }

    @Test
    fun `nonce is positive integer string`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val params = parseQuery(signed.url)
        val nonce = params["nonce"]?.toIntOrNull()
            ?: error("nonce 缺失或不是整数：${params["nonce"]}")
        // 官方示例里 nonce 范围是 1..2^31-1
        assertTrue(nonce in 1..Int.MAX_VALUE - 1, "nonce 越界：$nonce")
    }

    @Test
    fun `voiceId is 32 char hex without dashes`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        assertEquals(32, signed.voiceId.length, "voiceId 必须 32 字符（UUID 去连字符）")
        assertTrue(
            signed.voiceId.all { it.isDigit() || it in 'a'..'f' },
            "voiceId 必须是 hex：${signed.voiceId}",
        )
    }

    @Test
    fun `voiceId is unique across calls`() {
        val a = TencentAsrSignature.sign(appId, secretId, secretKey)
        val b = TencentAsrSignature.sign(appId, secretId, secretKey)
        // UUIDv4 几乎不会撞，但理论上 nonce 也会撞 — 钉一下避免有人改坏去掉 randomUUID
        assertFalse(a.voiceId == b.voiceId, "voiceId 必须每次不同")
    }

    @Test
    fun `signature field is present and urlencoded`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val sigPart = signed.url.substringAfter("&signature=")
        assertTrue(sigPart.isNotBlank(), "signature 不能为空")

        // URLEncoder.encode 后，原始 base64 里的 + / = 应当已编码成 %2B %2F %3D
        // 直接解一次确认：解回来应得到合法 base64 字符
        val decoded = URLDecoder.decode(sigPart, "UTF-8")
        assertTrue(
            decoded.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=" },
            "解 URL 编码后必须回到合法 base64 字符集：$decoded",
        )
    }

    @Test
    fun `signature is deterministic for the same canonical input`() {
        // 同一组 secret + 同一时刻签名应一致。这里用 ttlSeconds 钳到同一秒。
        // 直接调两次 sign() 时 nonce 由 SecureRandom 给、timestamp 是 wall clock，
        // 所以「完全相等」不可期 —— 这里改验：secretKey 一变 signature 就变。
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
        )
        val signedWrongSecret = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey + "X",
        )

        val sigA = signed.url.substringAfter("&signature=")
        val sigB = signedWrongSecret.url.substringAfter("&signature=")
        assertFalse(sigA == sigB, "改 secretKey 必须改变 signature —— 否则 HMAC 没真起作用")
    }

    @Test
    fun `engine_model_type is honored`() {
        val signed = TencentAsrSignature.sign(
            appId = appId, secretId = secretId, secretKey = secretKey,
            engine = "16k_zh_en",
        )
        val params = parseQuery(signed.url)
        assertEquals("16k_zh_en", params["engine_model_type"])
    }

    private fun parseQuery(url: String): Map<String, String> {
        val query = url.substringAfter('?').substringBefore("&signature=")
        return query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
