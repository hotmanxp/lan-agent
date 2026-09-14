// voice/VoiceAsrConfig.kt — 语音识别凭据 / 握手地址来源的唯一取值点。
//
// 四条路，按下面的顺序判定，**先命中先用**：
//
//   1. **后端签发**（推荐，凭据不进安装包）
//      打开 `local.properties` 的 `asrSignViaBackend=true`，App 每次按住会打
//      `GET <实例 baseUrl>/api/voice/asr-token?engine=<engine>`，
//      后端返回 `{"url":"wss://…","voice_id":"…","headers":{},"dialect":"workbuddy"}`。
//      腾讯云形态可省掉后两个字段。实例 baseUrl 由调用方从实例快照（host+port）带进来。
//
//   2. **后端下发 WorkBuddy 登录态**（opencc-web 的 getASRToken，凭据不进安装包）
//      实例 baseUrl 非空即启用。App 每次按住打
//      `GET <实例 baseUrl>/api/voice/getASRToken`，服务端现读桌面端落盘的
//      auth 文件返回 `{"ok":true,"endpoint":…,"accessToken":…,"uid":…,"expiresAt":…}`。
//      客户端**只拿不刷** —— refreshToken 一次性轮换，客户端刷一次就把桌面端
//      踢下线；token 新鲜度由桌面端自己的续期保证。见 AsrUrlProvider.WorkBuddyApi。
//
//   3. **直连 WorkBuddy 自己的 ASR**（兜底：服务端没起但本地灌过凭据）
//      `local.properties` 里 `asrUseWorkBuddy=true` + `asrWbAccessToken=<JWT>`，
//      可选 `asrWbRefreshToken` / `asrWbUid` / `asrWbEndpoint`。
//      凭据从哪来、怎么续期，见 WorkBuddyAsrAuth.kt 的文件头。
//      ⚠️ accessToken 默认 3 天、refreshToken 7 天 —— 想长期跑必须走第 2 条。
//
//   4. **端上自签腾讯云**（联调 / 内网自用）
//      在 `local.properties` 里填 `asrAppId` / `asrSecretId` / `asrSecretKey`。
//      `local.properties` 在 .gitignore 里，不会进 git —— 但 **APK 反编译能拿到**，
//      所以这条路只适合自己用/局域网，别对外分发。
//
// 四条都没配 → [providerOrNull] 返回 null → 输入条回落到系统 SpeechRecognizer
// （旧的 `ui/VoiceInput.kt`），行为跟改动前完全一致。
//
// 关于「鉴权」：腾讯云是 **签名鉴权**（没有登录环节，签名即 token，见
// TencentAsrSignature.kt）；WorkBuddy 是 **Bearer 登录态**（Keycloak 签发的 JWT，
// 可续期，见 WorkBuddyAsrAuth.kt）。两条链路的采集/传输侧完全一样。
package io.github.hotmanxp.lanagent.voice

import io.github.hotmanxp.lanagent.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object VoiceAsrConfig {

    /** 中文通用。中英混说 / 方言需求换成 `16k_zh_en`（计费档位不同）。WorkBuddy 模式忽略此项。 */
    const val ENGINE_DEFAULT = "16k_zh"

    /** 后端签发接口的路径（挂在实例的 baseUrl 上）。 */
    const val TOKEN_PATH = "/api/voice/asr-token"

    /** opencc-web 下发 WorkBuddy 登录态的路径（挂在实例的 baseUrl 上）。 */
    const val WB_TOKEN_PATH = "/api/voice/getASRToken"

    val engine: String
        get() = BuildConfig.ASR_ENGINE.ifBlank { ENGINE_DEFAULT }

    /** 端上自签所需的三个凭据是否齐了。 */
    val hasLocalCredentials: Boolean
        get() = BuildConfig.ASR_APP_ID.isNotBlank() &&
            BuildConfig.ASR_SECRET_ID.isNotBlank() &&
            BuildConfig.ASR_SECRET_KEY.isNotBlank()

    val useBackendSigning: Boolean
        get() = BuildConfig.ASR_SIGN_VIA_BACKEND

    /** WorkBuddy 直连模式：开了开关且至少有 accessToken。 */
    val hasWorkBuddyCredentials: Boolean
        get() = BuildConfig.ASR_USE_WORKBUDDY && BuildConfig.ASR_WB_ACCESS_TOKEN.isNotBlank()

    /**
     * WorkBuddy 凭据的持有者。**缓存单例** —— 续期发生在它内部，
     * 每次按住重建会把刚刷到的新 token 丢掉，下一轮又拿旧的去撞 401。
     */
    @Volatile
    private var workBuddyAuth: WorkBuddyAsrAuth? = null

    /** 返回 null = 没开或没配。线程安全。 */
    fun workBuddyAuthOrNull(): WorkBuddyAsrAuth? {
        workBuddyAuth?.let { return it }
        if (!hasWorkBuddyCredentials) return null
        return synchronized(this) {
            workBuddyAuth ?: WorkBuddyAsrAuth(
                endpoint = BuildConfig.ASR_WB_ENDPOINT.ifBlank { WorkBuddyAsrAuth.DEFAULT_ENDPOINT },
                accessToken = BuildConfig.ASR_WB_ACCESS_TOKEN,
                refreshToken = BuildConfig.ASR_WB_REFRESH_TOKEN.takeIf { it.isNotBlank() },
                // 手填的 token 也自解释：exp 直接从 JWT 里解。
                expiresAtMs = WorkBuddyAsrAuth.expiresAtFromJwt(BuildConfig.ASR_WB_ACCESS_TOKEN),
                uid = BuildConfig.ASR_WB_UID.takeIf { it.isNotBlank() },
            ).also { workBuddyAuth = it }
        }
    }

    /**
     * 本机是否开启了「按住说话」。false 时输入条走系统 SpeechRecognizer。
     * 只做配置判断，不做任何 IO，可以安全地在组合期调用。
     */
    fun isHoldToTalkEnabled(instanceBaseUrl: String? = null): Boolean =
        providerOrNull(instanceBaseUrl) != null

    /**
     * 取握手地址的来源。返回 null = 未配置，调用方应回落。
     *
     * @param instanceBaseUrl 当前实例的 `http://host:port`。给且开了后端签发时走 [AsrUrlProvider.Remote]。
     *                        注意这里只是拼串，真正的 HTTP 请求发生在
     *                        [TencentRealtimeAsr.start] 的 io 线程上，不会卡主线程。
     */
    fun providerOrNull(instanceBaseUrl: String? = null): AsrUrlProvider? = when {
        useBackendSigning && !instanceBaseUrl.isNullOrBlank() ->
            AsrUrlProvider.Remote(
                endpoint = instanceBaseUrl.trimEnd('/') + TOKEN_PATH,
                httpGet = ::httpGet,
            )

        hasWorkBuddyCredentials -> workBuddyAuthOrNull()?.let { auth ->
            AsrUrlProvider.WorkBuddy(
                endpoint = BuildConfig.ASR_WB_ENDPOINT.ifBlank { WorkBuddyAsrAuth.DEFAULT_ENDPOINT },
                // 现场取 token：快过期时 WorkBuddyAsrAuth 会先续期再返回。
                // provide() 跑在 TencentRealtimeAsr 的 io 线程上，同步刷新不会卡 UI。
                tokenProvider = auth::token,
                uid = auth.currentUid,
            )
        }

        hasLocalCredentials ->
            AsrUrlProvider.Local(
                appId = BuildConfig.ASR_APP_ID,
                secretId = BuildConfig.ASR_SECRET_ID,
                secretKey = BuildConfig.ASR_SECRET_KEY,
            )

        else -> null
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** 后端签发的同步 GET。调用方保证在 io 线程上。 */
    private fun httpGet(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("签发识别地址失败：HTTP ${resp.code}")
            }
            return resp.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("签发识别地址失败：响应为空")
        }
    }
}
