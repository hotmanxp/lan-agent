// ui/AaRuntime.kt — 造 AA 客户端的共用入口(0.24.0)
//
// 远程任务栏、会话详情、设备管理、配对、设置栏登录配置五处都要同一套东西:
// 从 `AgentsAnywherePrefs` 流式读 baseUrl / accessToken / clientId,再拼出
// [AgentsAnywhereApi] 和 [AgentsAnywhereClient]。抄五遍必然出现「某一处忘了
// `.trim()`」这种不一致,所以收成一个 hook。
//
// **key 必须带全部三个字段**:baseUrl / token / clientId 任一变化都要重建 Api,
// 否则会拿着旧 token 继续打,表现为「改了配置没反应,要重启 App」。
// `AgentsAnywhereApi` 内部的 OkHttpClient 是 companion 单例,重建 Api 实例不会
// 泄漏线程池(0.21.0 修过的坑,见该文件 companion 注释)。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.hotmanxp.lanagent.data.AgentsAnywhereApi
import io.github.hotmanxp.lanagent.data.AgentsAnywhereClient
import io.github.hotmanxp.lanagent.data.AgentsAnywherePrefs
import io.github.hotmanxp.lanagent.data.AgentsAnywhereWsClient
import kotlinx.coroutines.CoroutineScope

/** 一次「当前 AA 连接上下文」的快照。 */
data class AaRuntime(
    val prefs: AgentsAnywherePrefs,
    val baseUrl: String,
    val accessToken: String,
    val clientId: String,
    val api: AgentsAnywhereApi,
    val client: AgentsAnywhereClient,
    /** 起 WS 订阅用的 scope;屏离开自动取消,调用方不用自己管 Job。 */
    val scope: CoroutineScope,
) {
    /** 没配服务器地址 —— UI 据此显示「先去设置里配」,别去打必然失败的网络请求。 */
    val configured: Boolean get() = baseUrl.isNotBlank()

    /** 配了地址但没登录 —— 大多数端点会 401。 */
    val authenticated: Boolean get() = accessToken.isNotBlank()
}

/**
 * @param scope 用于起 WS 订阅的 scope。默认取 composable 的 —— 屏离开时自动
 *        取消,不用自己管 Job。
 */
@Composable
fun rememberAaRuntime(scope: CoroutineScope? = null): AaRuntime {
    val context = LocalContext.current
    val resolvedScope = scope ?: rememberCoroutineScope()

    val prefs = remember { AgentsAnywherePrefs(context) }
    val baseUrl by prefs.baseUrlFlow.collectAsState(initial = "")
    val accessToken by prefs.accessTokenFlow.collectAsState(initial = "")

    // clientId 不是 Flow —— 它一次性写入、之后只读(必须跨进程稳定,见 Prefs
    // 文件头)。用 produceState 异步取,**不能** runBlocking:那会阻塞主线程。
    val clientId by produceState("", prefs) { value = prefs.clientId() }
    val ws = remember { AgentsAnywhereWsClient() }

    val api = remember(baseUrl, accessToken, clientId) {
        AgentsAnywhereApi(baseUrl.trim(), accessToken.trim(), clientId)
    }
    val client = remember(api, ws, prefs) { AgentsAnywhereClient(prefs, api, ws) }

    return AaRuntime(prefs, baseUrl, accessToken, clientId, api, client, resolvedScope)
}
