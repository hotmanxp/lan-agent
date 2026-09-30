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

import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64 as JdkBase64
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
     * 服务端以 401/403 拒了这份凭据时调用 —— 丢弃本地缓存的 token，下一次
     * [provide] 重新去要一份。默认空实现：只有带缓存的 [WorkBuddyApi] 有东西要清。
     *
     * ⚠️ **不要在这里去调 WorkBuddy 自己的续期接口**（`/v2/plugin/auth/token/refresh`）——
     * refreshToken 是一次性轮换的，客户端刷一次就把桌面端踢下线，见
     * [WorkBuddyAsrAuth] 文件头的红线。要新 token 就通过实例后端现读桌面端
     * 落盘的 auth 文件（`GET <baseUrl>/api/voice/getASRToken`）。
     */
    fun invalidateAuth() = Unit

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
     * 生产（WorkBuddy 路线）：向实例后端要一份**现成的 WorkBuddy 登录态**。
     *
     * 后端（opencc-web / zai）`GET <baseUrl>/api/voice/getASRToken` 直接读桌面端
     * 落盘的 auth 文件返回：
     * `{"ok":true,"endpoint":"https://copilot.tencent.com","accessToken":…,
     *   "refreshToken":…,"uid":…,"expiresAt":<epoch ms>}`。
     *
     * 关键取舍：**客户端只拿不刷**。refreshToken 一次性轮换，客户端刷一次就把
     * 桌面端踢下线 —— token 的新鲜度由桌面端自己的续期保证，客户端不做任何
     * `/v2/plugin/auth/token/refresh`，也不用 WorkBuddyAsrAuth。
     *
     * ── 缓存 ────────────────────────────────────────────────────────────
     * 每次按住都打一次 GET 是白费 —— token 默认 3 天，桌面端续期后新 token 与
     * 旧 token 同时有效（Keycloak 不会因为换发就把旧的作废）。所以拿到
     * `expiresAt` 后缓存在内存里，到期前 [REFRESH_SKEW_MS] 才重新取：
     *
     *   · 服务端没给 `expiresAt`（连 JWT 的 exp 都解不出来）→ **不缓存**，
     *     每次现取。宁可比正常多一次 GET，也不要押一个不知道何时失效的 token。
     *   · 服务端以 401/403 拒了 WS 握手 → 上层调 [invalidateAuth] 清缓存，
     *     下一次 provide 会重新走一遍 GET（见 TencentRealtimeAsr.onFailure）。
     *
     * ⚠️ 服务端 `/api/voice/getASRToken` 自己**从不返回 401** —— 它读不到
     * 桌面端 auth 文件时回 503 `{ok:false,error}`。所以 401 一定来自
     * `copilot.tencent.com` 对**旧 token** 的拒签（桌面端重新登录 / 换账号），
     * 这时重新 GET 一定拿到新的。真·没登录态会以 503 的形式出现，原样上抛。
     *
     * 请求失败（服务端没起 / 没登录 / 文件加密）直接抛 —— 上层 onError 会 toast，
     * 下次按住再试。不做静默回落（用户需要知道云识别为什么不可用）。
     */
    class WorkBuddyApi(
        private val baseUrl: String,
        private val httpGet: (String) -> String,
        /** 请求路径。与服务端 routes/voice.ts 的挂载点保持一致。 */
        private val tokenPath: String = "/api/voice/getASRToken",
        /** 时钟。单测注入假时钟，生产用系统时间。 */
        private val nowMs: () -> Long = System::currentTimeMillis,
    ) : AsrUrlProvider {

        /**
         * 一份解析好的 WorkBuddy 凭据。[endpoint] 缺省时由调用方回落
         * [WorkBuddyAsrAuth.DEFAULT_ENDPOINT]；[uid] 缺省 / 空白 = 不带
         * `X-User-Id`；[expiresAtMs] 为 0 = 不知道过期时间，调用方据此**不缓存**。
         *
         * 放在类体上（而不是 companion）是因为 [ConnectorAuthFile] 也复用它 ——
         * 那条路线拿到凭据的来源是 Mac 上的 auth 文件，但解析出来的形状完全一样。
         */
        internal data class ParsedResponse(
            val endpoint: String,
            val accessToken: String,
            val uid: String?,
            val expiresAtMs: Long,
        )

        /** 缓存下来的凭据。null = 没有可用缓存，下次 provide 必须发 GET。 */
        @Volatile
        private var cached: ParsedResponse? = null

        override fun provide(engine: String): SignedAsrUrl {
            val cred = freshOrFetch()
            return WorkBuddy(
                endpoint = cred.endpoint.ifBlank { WorkBuddyAsrAuth.DEFAULT_ENDPOINT },
                // 同一次 provide 内复用，不二次请求
                tokenProvider = { cred.accessToken },
                uid = cred.uid,
            ).provide(engine)
        }

        /** 见 [AsrUrlProvider.invalidateAuth]。清掉缓存，下一次 provide 会重新问服务端。 */
        override fun invalidateAuth() {
            cached = null
        }

        private fun freshOrFetch(): ParsedResponse {
            cached?.takeIf { isFresh(it) }?.let { return it }
            val fetched = parseResponse(httpGet(baseUrl.trimEnd('/') + tokenPath))
            // 过期时间拿不到就不缓存 —— 见类注释里的取舍。
            if (fetched.expiresAtMs > 0L) cached = fetched
            return fetched
        }

        /** 距过期不足 [REFRESH_SKEW_MS] 就当作已过期，避免建连时正好卡在边界上。 */
        private fun isFresh(c: ParsedResponse): Boolean =
            c.expiresAtMs > 0L && nowMs() < c.expiresAtMs - REFRESH_SKEW_MS

        companion object {
            /** 提前 5 分钟重新取 —— 与 WorkBuddyAsrAuth.REFRESH_AHEAD_MS 同口径。 */
            private const val REFRESH_SKEW_MS = 5 * 60_000L

            /**
             * 解析服务端 JSON 响应。抽出来是为了单测能直接喂字符串构造 —
             * 端到端路径需要真 HTTP，调 `provide()` 拿不到注入点。
             *
             * 错误语义与生产一致：
             *   - `{ok: false, error: "..."}` → IllegalStateException
             *   - `accessToken` 缺失 / 空白 → IllegalArgumentException
             *
             * `expiresAt` 是 **epoch 毫秒**（服务端从 auth 文件的 `expiresAt` 或
             * JWT 的 `exp` 折算，见 opencc-web `routes/voice.ts`）。给的是 null /
             * 非正数就落成 0，语义是「不知道过期时间」。
             */
            internal fun parseResponse(body: String): ParsedResponse {
                val json = org.json.JSONObject(body)
                if (json.optBoolean("ok", true) == false) {
                    throw IllegalStateException(
                        json.optString("error").ifBlank { "服务端拒绝下发 ASR token" }
                    )
                }
                val accessToken = json.optString("accessToken")
                require(accessToken.isNotBlank()) { "服务端响应里没有 accessToken" }
                return ParsedResponse(
                    endpoint = json.optString("endpoint"),
                    accessToken = accessToken,
                    uid = json.optString("uid").takeIf { it.isNotBlank() },
                    expiresAtMs = json.optLong("expiresAt", 0L).takeIf { it > 0L } ?: 0L,
                )
            }
        }
    }

    /**
     * 远端（不在局域网）：借 Agents-Anywhere 官方的 **connector 文件通道**读 Mac 上
     * WorkBuddy 落盘的 auth 文件。
     *
     * ── 为什么是这条路 ────────────────────────────────────────────────────
     * 凭据必须来自本机（WorkBuddy 登录态在 Mac 上），但手机常常连不上 Mac 的局域网。
     * AA 官方**没有**任何「客户端注册自定义端点」的机制（服务端是托管 SaaS，改不了），
     * 唯一现成的「手机 → 官方服务端 → 你的 Mac」通道就是 connector 的文件 RPC：
     *
     *   POST {server}/api/v2/connectors/{connectorId}/fs/readText?root=~
     *        { "path": "…/workbuddy-desktop.info" }
     *      → 服务端鉴权后转成 WS RPC `fs.readText` 打到 Mac → 原路返回文件内容
     *
     * `root` 服务端纯透传、无白名单（server/agent_server/services/connector_files.py:36），
     * connector 侧只做 `expanduser().resolve()`（connector/connector/local/common.py:21-32），
     * 唯一的门是路由上的 `Depends(current_user_id)` —— 也就是只有你自己的账号能读
     * 自己那台设备。这是 connector「代你操作本机文件」的正常用法。
     *
     * ── 只读不刷（红线）────────────────────────────────────────────────────
     * 与 [WorkBuddyApi] 同理：**绝不在这里调 `/v2/plugin/auth/token/refresh`**。
     * refreshToken 一次性轮换，客户端刷一次就把 WorkBuddy 桌面端踢下线。这里是
     * 直接**读文件**而不是持有 [WorkBuddyAsrAuth] 就是为了从结构上杜绝那条路 ——
     * 续期由桌面端自己负责，它会把新 token 写回同一个文件。
     *
     * 缓存策略照抄 [WorkBuddyApi]：按 `expiresAt` 缓存，到期前 5 分钟重读；
     * 过期时间解不出来就**不缓存**（每次现读），避免押一个不知道何时失效的 token。
     * 另外每份缓存都记下取它时的 [sessionFingerprint]，指纹一变（换账号 / 重新登录）
     * 立刻丢弃 —— 否则 provider 会攥着一个已经作废的 AA token 一直按到 401。
     */
    class ConnectorAuthFile(
        /** 同步读一次 auth 文件的原文。跑在 [TencentRealtimeAsr] 的 io 线程上。 */
        private val readAuthFile: () -> String,
        /**
         * 当前**调用方会话**（AA 登录态）的指纹。返回 null 表示这条链路与会话无关，
         * 缓存也就无从因会话变化而失效。
         *
         * 之所以要它：provider 实例会被 composable 记住，可能比一次登录周期活得久。
         * 只按 `expiresAt` 判鲜的话，token 还在有效期内、但承载它的 AA 账号已经换人了，
         * 缓存照样命中，然后每次按住都吃一发 401。
         */
        private val sessionFingerprint: () -> String? = { null },
        private val nowMs: () -> Long = System::currentTimeMillis,
    ) : AsrUrlProvider {

        /** 凭据缓存。null = 没有可用缓存，下次 provide 必须重读文件。 */
        @Volatile
        private var cached: Cached? = null

        /** 凭据和取它时的会话指纹**打包成一个对象** —— 分成两个 @Volatile 字段会出现
         *  读到新凭据配旧指纹的窗口（先写 cached 后写 fingerprint 即可复现）。 */
        private data class Cached(
            val fingerprint: String?,
            val credential: WorkBuddyApi.ParsedResponse,
        )

        override fun provide(engine: String): SignedAsrUrl {
            val cred = freshOrFetch()
            return WorkBuddy(
                endpoint = WorkBuddyAsrAuth.DEFAULT_ENDPOINT,
                tokenProvider = { cred.accessToken },
                uid = cred.uid,
            ).provide(engine)
        }

        /** 见 [AsrUrlProvider.invalidateAuth]。清缓存，下一次 provide 重读文件。 */
        override fun invalidateAuth() {
            cached = null
        }

        private fun freshOrFetch(): WorkBuddyApi.ParsedResponse {
            val fingerprint = sessionFingerprint()
            cached?.takeIf { it.fingerprint == fingerprint && isFresh(it.credential) }
                ?.let { return it.credential }
            val parsed = parseAuthFile(readAuthFile())
            if (parsed.expiresAtMs > 0L) cached = Cached(fingerprint, parsed)
            return parsed
        }

        private fun isFresh(c: WorkBuddyApi.ParsedResponse): Boolean =
            c.expiresAtMs > 0L && nowMs() < c.expiresAtMs - REFRESH_SKEW_MS

        companion object {
            /** 与 [WorkBuddyApi] 同口径：建连时不卡在过期边界上。 */
            private const val REFRESH_SKEW_MS = 5 * 60_000L

            /**
             * 解析 WorkBuddy 桌面端落盘的 auth 文件。**无副作用、不续期**。
             *
             * 不是严格意义的纯函数：过期时间在只有 `expiresIn` 时要拿系统时钟当基准
             * （见 [WorkBuddyAsrAuth.resolveExpiresAt]）。调用方传进来的 `nowMs` 假
             * 时钟只作用于本 provider 的缓存判定，管不到这里 —— 单测别指望它。
             *
             * 容忍两种形态（与 opencc-web `routes/voice.ts:64-93` 同源）：
             *   - 标准：`{"account":{"uid":…},"auth":{"accessToken":…}}`
             *   - 扁平：`{"uid":…,"accessToken":…}`
             *
             * `endpoint` 恒为 `copilot.tencent.com` —— 那是产品固定网关，文件里不带，
             * 也没有别的取值（opencc-web `voice.ts:26` 同）。
             */
            internal fun parseAuthFile(raw: String): WorkBuddyApi.ParsedResponse {
                val root = org.json.JSONObject(raw)
                val auth = root.optJSONObject("auth") ?: root
                val account = root.optJSONObject("account") ?: root

                val accessToken = auth.optString("accessToken")
                require(accessToken.isNotBlank()) {
                    "WorkBuddy auth 文件里没有 accessToken（可能不是预期结构，或桌面端换了落盘格式）"
                }

                return WorkBuddyApi.ParsedResponse(
                    endpoint = WorkBuddyAsrAuth.DEFAULT_ENDPOINT,
                    accessToken = accessToken,
                    uid = account.optString("uid").takeIf { it.isNotBlank() },
                    expiresAtMs = WorkBuddyAsrAuth.resolveExpiresAt(auth),
                )
            }
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
        // 等价 android.util.Base64.encodeToString(raw, Base64.NO_WRAP)：
        // JDK 默认 76 字符换行，strip 掉；padding 保留。
        val base64 = JdkBase64.getEncoder().encodeToString(raw)
            .replace("\r", "").replace("\n", "")

        // URLEncoder 会把 + → %2B、/ → %2F、= → %3D，正是官方要求的编码强度。
        val signature = URLEncoder.encode(base64, "UTF-8")

        return SignedAsrUrl(
            url = "wss://$HOST$PATH_PREFIX/$appId?$query&signature=$signature",
            voiceId = voiceId,
        )
    }
}
