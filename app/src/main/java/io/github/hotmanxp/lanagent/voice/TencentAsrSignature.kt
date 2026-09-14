// voice/TencentAsrSignature.kt — 实时语音识别的**握手地址 + 鉴权**层。
//
// 两条链路共用本文件的 AsrUrlProvider 抽象：
//
//   A. 腾讯云实时 ASR（公开服务，签名鉴权）
//      wss://asr.cloud.tencent.com/asr/v2/<appid>?<sorted params>&signature=<urlencoded>
//      签名三步（官方文档口径，顺序不能变）：
//        1. 除 signature 外全部参数按 **字典序** 排序，拼成
//           "asr.cloud.tencent.com/asr/v2/<appid>?k1=v1&k2=v2…"（不含 wss://）
//        2. HMAC-SHA1(canonical, secretKey) → Base64
//        3. 把 Base64 结果做 **URLEncode**。这一步漏了或用了不编码 +/= 的库，
//           会表现为「偶发鉴权失败」——因为 Base64 里的 + / = 在不同链路上会被改写。
//
//   B. WorkBuddy 自己的 ASR（Bearer 鉴权，见 AsrUrlProvider.WorkBuddy）
//      wss://copilot.tencent.com/clientcap/v2/asr/stream?source=desktop
//      Authorization: Bearer <WorkBuddy accessToken>
//      token 的取得/续期见 WorkBuddyAsrAuth.kt。
//
// 安全：SecretKey / accessToken 放客户端等于公开。生产环境请用
// AsrUrlProvider.Remote 让后端签发（凭据只在服务端）。
package io.github.hotmanxp.lanagent.voice

import android.util.Base64
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 服务端**方言**。两条链路的采集侧完全一样（16k/mono/PCM16、裸二进制帧上行），
 * 差异只在收尾帧与下行 JSON：
 *
 * | | 腾讯云实时 ASR | WorkBuddy |
 * |---|---|---|
 * | 收尾 | 文本帧 `{"type":"end"}` | **空二进制帧** |
 * | 下行文本 | `result.slice_type` 0/1/2 分稳态非稳态 | 整段全量 `text`，**覆盖**而非追加 |
 * | 结束标志 | `final == 1` | `final` 为真 |
 * | 鉴权 | URL 里的 signature | header `Authorization: Bearer <token>` |
 */
enum class AsrDialect { TencentCloud, WorkBuddy }

/**
 * 一条握手地址 + 它捆绑的 voice_id（同一连接内所有回调都带这个 id）。
 *
 * [headers] 是本连接需要带的额外请求头 —— 腾讯云走 URL 签名时为空，
 * WorkBuddy 走 Bearer 时是 `Authorization` / `X-User-Id`。
 */
data class SignedAsrUrl(
    val url: String,
    val voiceId: String,
    val headers: Map<String, String> = emptyMap(),
    val dialect: AsrDialect = AsrDialect.TencentCloud,
)

/** 拿握手地址的地方。生产环境换成后端签发，避免凭据进安装包。 */
sealed interface AsrUrlProvider {
    fun provide(engine: String): SignedAsrUrl

    /**
     * 调试/自用：客户端本地签名（腾讯云）。
     * ⚠️ secretKey 会出现在 APK 里，只适合内网自用或本地联调。
     */
    class Local(
        private val appId: String,
        private val secretId: String,
        private val secretKey: String,
    ) : AsrUrlProvider {
        override fun provide(engine: String): SignedAsrUrl = TencentAsrSignature.sign(
            appId = appId,
            secretId = secretId,
            secretKey = secretKey,
            engine = engine,
        )
    }

    /**
     * 直连 **WorkBuddy 自己的 ASR**（`<endpoint>/clientcap/v2/asr/stream`）。
     *
     * WorkBuddy 没有 OAuth 换票环节，它的身份就是一个 Keycloak 签发的 accessToken；
     * token 的取得方式见 [WorkBuddyAsrAuth] 的文件头。这里只负责拼握手地址。
     *
     * @param tokenProvider 每次建连现场取一次 token —— 这样 [WorkBuddyAsrAuth] 能
     *   在快过期时刷新，而不必重启 App。
     */
    class WorkBuddy(
        private val endpoint: String,
        private val tokenProvider: () -> String,
        private val uid: String? = null,
        private val sessionId: String? = null,
        /** 服务端要求的来源标识。抓包实测 = `desktop`。 */
        private val source: String = "desktop",
    ) : AsrUrlProvider {

        override fun provide(engine: String): SignedAsrUrl {
            val token = tokenProvider()
            require(token.isNotBlank()) { "WorkBuddy accessToken 为空，先登录" }

            val base = endpoint.trimEnd('/')
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> throw IllegalArgumentException("endpoint 必须是 http(s) 地址：$endpoint")
            }

            // session_id 只在符合服务端正则时带上，否则整个握手会被拒。
            val query = buildList {
                sessionId?.takeIf { SESSION_ID_RE.matches(it) }?.let { add("session_id=$it") }
                add("source=$source")
            }.joinToString("&")

            val headers = buildMap {
                put("Authorization", "Bearer $token")
                uid?.takeIf { it.isNotBlank() }?.let { put("X-User-Id", it) }
            }

            return SignedAsrUrl(
                url = "$ws/clientcap/v2/asr/stream?$query",
                voiceId = java.util.UUID.randomUUID().toString().replace("-", ""),
                headers = headers,
                dialect = AsrDialect.WorkBuddy,
            )
        }

        private companion object {
            val SESSION_ID_RE = Regex("^[a-zA-Z0-9-_]{1,128}$")
        }
    }

    /**
     * 生产：向后端要一条拼好的握手地址（腾讯云或 WorkBuddy 都走这里）。
     * 后端返回 `{"url":"wss://…","voice_id":"…","headers":{},"dialect":"workbuddy"}`，
     * 客户端拿到就用，凭据不落地。后两个字段可省，缺省即腾讯云形态。
     */
    class Remote(
        private val endpoint: String,
        private val httpGet: (String) -> String,
    ) : AsrUrlProvider {
        override fun provide(engine: String): SignedAsrUrl {
            val body = httpGet("$endpoint?engine=$engine")
            val json = org.json.JSONObject(body)

            val headers = json.optJSONObject("headers")?.let { obj ->
                buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
            } ?: emptyMap()

            val dialect = when (json.optString("dialect").lowercase()) {
                "workbuddy" -> AsrDialect.WorkBuddy
                else -> AsrDialect.TencentCloud
            }

            return SignedAsrUrl(
                url = json.getString("url"),
                voiceId = json.optString("voice_id"),
                headers = headers,
                dialect = dialect,
            )
        }
    }
}

object TencentAsrSignature {
    private const val HOST = "asr.cloud.tencent.com"
    private const val PATH_PREFIX = "/asr/v2"

    /**
     * 生成完整握手地址。
     *
     * @param engine 引擎模型，中文通用用 `16k_zh`；中英+方言大模型可用 `16k_zh_en`
     *               （计费档位不同，见官方「计费概述（在线版）」）。
     */
    fun sign(
        appId: String,
        secretId: String,
        secretKey: String,
        engine: String = "16k_zh",
        /** 签名有效期（秒）。必须 > 0 且 < 90 天。 */
        ttlSeconds: Long = 3600,
    ): SignedAsrUrl {
        val voiceId = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis() / 1000
        val nonce = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1

        // sortedMapOf ⇒ 天然字典序，正是签名要求的顺序。
        val params = sortedMapOf(
            "engine_model_type" to engine,
            "expired" to (now + ttlSeconds).toString(),
            "nonce" to nonce.toString(),
            "secretid" to secretId,
            "timestamp" to now.toString(),
            "voice_id" to voiceId,
            // PCM 裸流。默认值是 4(speex)，不改会 4007 解码失败。
            "voice_format" to "1",
            // 静音切句，长语音更稳。
            "needvad" to "1",
            // 去掉句末句号，回填输入框更干净。
            "filter_punc" to "1",
            // 中文数字智能转阿拉伯数字：「三块五」→「3块5」。
            "convert_num_mode" to "1",
            // 不回调空结果，少一批没用的消息。
            "filter_empty_result" to "1",
        )

        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val canonical = "$HOST$PATH_PREFIX/$appId?$query"

        // Base64(HMAC-SHA1)。注意 Mac 实例不是线程安全的，每次新建。
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        }
        val raw = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val base64 = Base64.encodeToString(raw, Base64.NO_WRAP)

        // URLEncoder 会把 + → %2B、/ → %2F、= → %3D，正是官方要求的编码强度。
        val signature = URLEncoder.encode(base64, "UTF-8")

        return SignedAsrUrl(
            url = "wss://$HOST$PATH_PREFIX/$appId?$query&signature=$signature",
            voiceId = voiceId,
        )
    }
}
