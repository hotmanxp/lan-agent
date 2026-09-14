// voice/WorkBuddyAsrAuth.kt — WorkBuddy 身份的持有者：拿到能用的 accessToken，并在快过期时续期。
//
// ── WorkBuddy 的 token 是怎么来的（从 app.asar 还原）────────────────────────
//
// 它的鉴权跟腾讯云 ASR 的「签名」完全不同，是**登录发 token**：
//
//   1. POST /v2/plugin/auth/state?platform=workbuddy
//      header: X-No-Authorization: true（其余 X-No-* 同理，表示这次请求不带身份）
//      → { state, authUrl }
//   2. 用系统浏览器打开 authUrl，用户在网页上完成登录（微信/QQ/手机号）
//   3. 轮询 GET /v2/plugin/auth/token?state=<state>（未完成时服务端回 RetryFetchToken）
//      → { accessToken, refreshToken, expiresIn, refreshExpiresIn, ... }
//   4. GET /v2/plugin/account ＋ GET /v2/plugin/accounts（Bearer 带上面拿到的 token）
//
//   endpoint = https://copilot.tencent.com   prefixPath = /plugin   platform = workbuddy
//   这些值来自服务端下发的产品配置，本地缓存于
//   ~/.workbuddy/cache/acc-product-config-v3.json
//
// token 是 **Keycloak** 签的 JWT（iss = https://www.workbuddy.cn/auth/realms/copilot），
// scope 里带 offline_access，所以有 refreshToken：
//
//   accessToken  默认 3 天（expiresIn = 259200）
//   refreshToken 默认 7 天（refreshExpiresIn = 604799）
//
// 续期：POST /v2/plugin/auth/token/refresh
//   header: X-Refresh-Token: <refreshToken>
//           X-Auth-Refresh-Source: plugin
//           X-Domain: <endpoint 的 host>
//   → { data: { accessToken, refreshToken, ... } }
//
// ⚠️ 服务端会**轮换** refreshToken —— 拿到新值必须存下来，否则第二次续期会失败。
//    桌面端把它落盘到：
//      ~/Library/Application Support/CodeBuddyExtension/Data/Public/auth/workbuddy-desktop.info
//    （本机实测该文件是**明文** JSON —— at-rest 加密那套走了降级路径。）
//
// ── 这个类在 App 里的定位 ──────────────────────────────────────────────────
//
// Android 端读不到 macOS 上那个文件，所以现实路径只有两条：
//   A. 后端把 token 下发给 App（生产，见 hold-to-talk/wb-auth/ 的说明）
//   B. 手动把 auth 文件内容喂给 [fromAuthFileJson]（联调，3 天换一次）
// 两条都用这个类统一持有，续期逻辑只写一份。
package io.github.hotmanxp.lanagent.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class WorkBuddyAsrAuth(
    private val endpoint: String = DEFAULT_ENDPOINT,
    accessToken: String,
    private var refreshToken: String? = null,
    private var expiresAtMs: Long = 0L,
    private var uid: String? = null,
    private val client: OkHttpClient = sharedClient,
) {

    @Volatile
    private var accessToken: String = accessToken

    /** 当前账号 uid，握手时要塞进 `X-User-Id`。没有就返回 null（服务端也认 token）。 */
    val currentUid: String?
        get() = uid

    /**
     * 取一个**当前可用**的 accessToken；距过期不足 [REFRESH_AHEAD_MS] 就先续期。
     *
     * ⚠️ 可能发网络请求 —— 必须在后台线程调用。
     * [AsrUrlProvider.WorkBuddy.provide] 已经在 `TencentRealtimeAsr` 的 io 线程上跑，
     * 直接传 `auth::token` 是安全的。
     */
    @Synchronized
    fun token(): String {
        if (needsRefresh()) refreshNow()
        return accessToken
    }

    /** 后端下发 / 外部刷新后同步进来，避免本地这份比服务端旧。 */
    @Synchronized
    fun update(accessToken: String, refreshToken: String? = null, expiresAtMs: Long = 0L, uid: String? = null) {
        if (accessToken.isNotBlank()) this.accessToken = accessToken
        refreshToken?.takeIf { it.isNotBlank() }?.let { this.refreshToken = it }
        if (expiresAtMs > 0) this.expiresAtMs = expiresAtMs
        uid?.takeIf { it.isNotBlank() }?.let { this.uid = it }
    }

    /** 导出给后端接力的紧凑形态（不含敏感字段以外的元数据）。 */
    @Synchronized
    fun snapshot(): JSONObject = JSONObject().apply {
        put("accessToken", accessToken)
        refreshToken?.let { put("refreshToken", it) }
        put("expiresAt", expiresAtMs)
        uid?.let { put("uid", it) }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun needsRefresh(): Boolean {
        // 没拿到过期时间（比如只喂了个裸 token）就不主动续期，交给服务端 401 兜底。
        if (expiresAtMs <= 0L) return false
        return System.currentTimeMillis() >= expiresAtMs - REFRESH_AHEAD_MS
    }

    @Synchronized
    private fun refreshNow() {
        val rt = refreshToken
            ?: throw IOException("WorkBuddy refreshToken 缺失，无法续期；请重新登录或重新导入凭据")

        val host = runCatching { URI(endpoint).authority }.getOrNull().orEmpty()
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + REFRESH_PATH)
            .post("{}".toRequestBody(JSON))
            .header("X-Refresh-Token", rt)
            .header("X-Auth-Refresh-Source", "plugin")
            .apply { if (host.isNotEmpty()) header("X-Domain", host) }
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                throw IOException("WorkBuddy 登录态已失效（HTTP ${response.code}），需要重新登录")
            }
            if (!response.isSuccessful) {
                throw IOException("续期失败：HTTP ${response.code} ${text.take(180)}")
            }
            val json = JSONObject(text)
            if (json.optInt("code", 0) != 0) {
                throw IOException("续期失败：${json.optString("msg").ifBlank { text.take(180) }}")
            }
            val data = json.optJSONObject("data")
                ?: throw IOException("续期失败：响应里没有 data")

            data.optString("accessToken").takeIf { it.isNotBlank() }?.let { accessToken = it }
            // 服务端会轮换 refreshToken，务必存新的 —— 否则下一次续期必然失败。
            data.optString("refreshToken").takeIf { it.isNotBlank() }?.let { refreshToken = it }
            expiresAtMs = resolveExpiresAt(data)
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://copilot.tencent.com"

        private const val REFRESH_PATH = "/v2/plugin/auth/token/refresh"

        /** 提前 5 分钟续期：一次按住说话的时长足够短，不会在通话中途换 token。 */
        private const val REFRESH_AHEAD_MS = 5 * 60_000L

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 从 WorkBuddy 的 auth 文件内容构造。
         *
         * 兼容两种形态：
         *   - 原样喂 auth 文件（`{"account":{...},"auth":{...}}`）
         *   - 喂精简后的 `{"accessToken":...,"refreshToken":...,"expiresAt":...,"uid":...}`
         *
         * 桌面端那个文件在 macOS 上是：
         *   ~/Library/Application Support/CodeBuddyExtension/Data/Public/auth/workbuddy-desktop.info
         */
        fun fromAuthFileJson(raw: String, endpoint: String = DEFAULT_ENDPOINT): WorkBuddyAsrAuth {
            val root = JSONObject(raw)
            val auth = root.optJSONObject("auth") ?: root
            val account = root.optJSONObject("account")

            val access = auth.optString("accessToken")
            require(access.isNotBlank()) { "没有 accessToken —— 确认喂进来的是 WorkBuddy 的 auth 文件" }

            return WorkBuddyAsrAuth(
                endpoint = endpoint,
                accessToken = access,
                refreshToken = auth.optString("refreshToken").takeIf { it.isNotBlank() },
                expiresAtMs = resolveExpiresAt(auth),
                uid = account?.optString("uid")?.takeIf { it.isNotBlank() },
            )
        }

        /**
         * 过期时间有两种表达：直接给 `expiresAt`，或给 `lastRefreshTime + expiresIn`。
         * 后者才是服务端原始形态（见 calculateExpiresAt 的还原）；再不行就从 JWT 的 exp 解。
         */
        private fun resolveExpiresAt(node: JSONObject): Long {
            node.optLong("expiresAt", 0L).takeIf { it > 0L }?.let { return it }
            val expiresIn = node.optLong("expiresIn", 0L)
            if (expiresIn > 0L) {
                val base = node.optLong("lastRefreshTime", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                return base + expiresIn * 1000L
            }
            return expiresAtFromJwt(node.optString("accessToken"))
        }

        /**
         * 从 JWT payload 里读 `exp` 并转成毫秒。解不出来返回 0 —— 语义是「不主动续期」，
         * 让服务端 401 兜底。
         *
         * 只做 base64url 解码，**不校验签名**（签名本来就该由服务端验）。
         * 这让「只贴一个裸 accessToken」也能用：过期时间自解释，不必手填。
         */
        fun expiresAtFromJwt(token: String): Long {
            val parts = token.split('.')
            if (parts.size < 2) return 0L
            return runCatching {
                val flags = android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_WRAP or
                    android.util.Base64.NO_PADDING
                val payload = String(android.util.Base64.decode(parts[1], flags), Charsets.UTF_8)
                JSONObject(payload).optLong("exp", 0L) * 1000L
            }.getOrDefault(0L)
        }
    }
}
