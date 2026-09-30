// aa/feature/sessiondetail/AaAsrProvider.kt — AA 远端会话的 ASR 凭据来源。
//
// 解决的问题：AA 会话跑在官方托管服务端上，手机离开局域网后够不到本机，
// 而 WorkBuddy 登录态只在 Mac 上 → 以前这里借的是 lan-agent 的 **LAN 实例**
// （SessionDetailScreen 里的 resolveAgentInstances），出了局域网就借不到，
// 语音按钮直接不渲染。
//
// 换成 AA 官方的 connector 文件通道：服务端**不改任何代码**、Mac 上**不多跑任何
// 进程**，只借 `fs.readText` 这条已有的 RPC 把本机 auth 文件读回来。链路见
// `voice/TencentAsrSignature.kt` 的 `ConnectorAuthFile` 类注释。
//
// 这里刻意**不留任何静态状态**：provider 实例由 composable 记住，登录态每次现读，
// 指纹变了由 ConnectorAuthFile 自己丢弃凭据缓存。存一份静态缓存既会攥住作废的
// token，又等于在进程里多留一份 accessToken 副本。
package io.github.hotmanxp.lanagent.aa.feature.sessiondetail

import io.github.hotmanxp.lanagent.aa.api.ApiException
import io.github.hotmanxp.lanagent.aa.api.FilesApi
import io.github.hotmanxp.lanagent.aa.feature.auth.AuthSessionStore
import io.github.hotmanxp.lanagent.voice.AsrUrlProvider
import java.security.MessageDigest

object AaAsrProvider {

    /**
     * WorkBuddy 桌面端落盘的登录态（macOS 明文 JSON）。
     *
     * 路径来自 opencc-web `packages/zai/src/server/routes/voice.ts:29-33` 的
     * `defaultAuthFilePath()`。这里存**相对 home 的路径**、root 传 `~` —— 手机
     * 并不知道 Mac 的用户名，靠 connector 侧的 `Path.expanduser()` 展开
     * （`connector/connector/local/common.py:21-25`）。
     */
    private const val AUTH_FILE_RELATIVE_PATH =
        "Library/Application Support/CodeBuddyExtension/Data/Public/auth/workbuddy-desktop.info"

    /** root = `~`，配合上面的相对路径定位到 home。 */
    private const val HOME_ROOT = "~"

    /** auth 文件只有几百字节，64 KiB 余量足够，也不会被 connector 的 4 MiB 上限挡下。 */
    private const val MAX_BYTES = 64 * 1024

    /**
     * 造一个能用的 provider；拿不到必要信息时返回 null（调用方据此不渲染语音按钮，
     * 别让用户按了才失败）。
     *
     * @param connectorId 目标设备的 connector id —— AA 会话自带（`AgentSession.connectorId`），
     *   就是那台跑着 connector 的 Mac。
     * @param filesApi **必须**是 App 里那个共用的实例（带 `onUnauthorized` 接线），
     *   否则 AA 登录过期时这条路径感知不到，会一直拿着废 token 撞 401。
     */
    fun create(
        sessionStore: AuthSessionStore,
        connectorId: String,
        filesApi: FilesApi,
    ): AsrUrlProvider? {
        if (connectorId.isBlank()) return null
        return AsrUrlProvider.ConnectorAuthFile(
            // 现读登录态，不在建 provider 那一刻就把它闭包住 —— composable 记住的
            // provider 可能比一次登录周期活得久。
            readAuthFile = { readAuthFile(sessionStore, filesApi, connectorId) },
            sessionFingerprint = { sessionFingerprint(sessionStore) },
        )
    }

    private fun readAuthFile(
        sessionStore: AuthSessionStore,
        filesApi: FilesApi,
        connectorId: String,
    ): String {
        val serverUrl = sessionStore.readServerUrl()
        val accessToken = sessionStore.readAccessToken()
        if (serverUrl.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("AA 登录已过期，请重新登录后再使用语音输入")
        }
        try {
            val file = filesApi.readTextFile(
                serverUrl = serverUrl,
                authorizationToken = accessToken,
                deviceId = connectorId,
                root = HOME_ROOT,
                path = AUTH_FILE_RELATIVE_PATH,
                maxBytes = MAX_BYTES,
            )
            // 截断的 JSON 会在下一步炸成一句莫名其妙的 JSONException，不如在这里说清。
            require(!file.truncated) {
                "WorkBuddy 登录态文件被截断（超过 ${MAX_BYTES / 1024} KiB），" +
                    "这不像正常的登录态文件"
            }
            return file.content
        } catch (e: Exception) {
            throw translate(e)
        }
    }

    /**
     * 把这条通道特有的失败翻译成人话。
     *
     * 401 说的是**AA 登录**过期（`ApiClient` 给的字面量是 "Unauthorized request."），
     * 跟 WorkBuddy 一点关系没有 —— 这两种必须分开说，否则用户会去重登 WorkBuddy，
     * 而真正过期的是 AA 账号。其余错误（404 / 连接不上 / 读不到）基本都是「这台
     * 设备上没装或没登录 WorkBuddy」，或 `root="~"` 没展开（connector 靠 `HOME` 环境
     * 变量展开，见 `connector/connector/local/common.py:25`）—— 后者返回的也是个
     * 含义不明的 404。
     */
    private fun translate(e: Exception): Exception = when {
        e is ApiException && e.statusCode == 401 ->
            IllegalStateException("AA 登录已过期，请重新登录后再使用语音输入", e)

        e is IllegalStateException -> e

        else -> IllegalStateException(
            "读取 Mac 上的 WorkBuddy 登录态失败（${e.message ?: "未知原因"}）。" +
                "请确认这台设备已安装并登录 WorkBuddy 桌面端，且 AA connector 在线。",
            e,
        )
    }

    /**
     * 当前 AA 登录态的指纹。**存哈希而不是原文** —— 这个值只用来判断「会话有没有换」，
     * 不需要能逆推出 token，也不该在内存里多留一份明文副本。
     */
    private fun sessionFingerprint(sessionStore: AuthSessionStore): String? {
        val serverUrl = sessionStore.readServerUrl()
        val accessToken = sessionStore.readAccessToken()
        if (serverUrl.isBlank() || accessToken.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$serverUrl|$accessToken".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
