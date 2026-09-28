// ui/RemoteTasksTabScreen.kt — 底栏「远程」栏(0.24.0)
//
// 底栏从五栏收成四栏时新增的一栏,承载 Agents-Anywhere 远程会话。0.21.0 那套
// 东西本来是设置栏里的一个「打开调试屏」入口(`AgentsAnywhereScreen`),现在
// 拆成三个地方:
//
//   设置栏            → 服务器地址 / 登录 / 设备配对(配置面)
//   本屏(tab/remote)  → 设备列表 + 会话列表(dashboard)
//   AaSessionScreen   → 单个会话详情(独立路由 aa-session/{sid})
//
// 本屏只管 dashboard 一条链路:connectors + sessions + projects + runtimes,
// 以及 dashboard WS 的订阅。点会话 → push 详情路由,WS 生命周期交给
// [AaSessionHolder]。设备(connector)管理在独立的 `aa-devices` 路由 ——
// 从顶部的「设备」按钮进,不再在 dashboard 里平铺一段。
//
// **dashboard WS 与 session WS 互斥**:进会话时先断 dashboard(省一条连接,
// 也避免两边同时改同一份 UI 状态)。返回时 tab 屏自己重连。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorSmartphone
import com.composables.icons.lucide.RefreshCw
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentsAnywhereConnState
import io.github.hotmanxp.lanagent.data.AgentsAnywhereDashboardState
import io.github.hotmanxp.lanagent.data.AgentsAnywhereEvent
import io.github.hotmanxp.lanagent.data.SessionSummary
import io.github.hotmanxp.lanagent.data.dispatchDashboardFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteTasksTabScreen(
    onOpenSession: (sid: String) -> Unit,
    onOpenDevices: () -> Unit,
    onGoSettings: () -> Unit,
) {
    val rt = rememberAaRuntime()

    val dashboardState = remember { AgentsAnywhereDashboardState() }
    var dashboardJob by remember { mutableStateOf<Job?>(null) }
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }

    fun startDashboard() {
        if (!rt.configured) return
        dashboardState.setConn(AgentsAnywhereConnState.Connecting, "拉 ticket + 开 WS")
        dashboardJob = rt.scope.launch {
            try {
                rt.client.subscribeDashboard(
                    baseUrl = rt.baseUrl.trim(),
                    accessToken = rt.accessToken.trim(),
                ).collect { ev ->
                    when (ev) {
                        is AgentsAnywhereEvent.Incoming ->
                            if (dispatchDashboardFrame(ev.parsed, dashboardState)) {
                                dashboardState.setConn(
                                    AgentsAnywhereConnState.Connected,
                                    "dashboard · 已连接",
                                )
                            }
                        is AgentsAnywhereEvent.Lifecycle ->
                            dashboardState.setConn(ev.kind.toConnState(), ev.message)
                        is AgentsAnywhereEvent.Unparseable -> Unit
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                // 这是**组合作用域**里的协程 —— 未捕获异常会终止整个进程。
                // 0.24.0 真机闪退过一次(错误路径 → 拉 ticket 抛异常直接掀翻 App)。
                // 任何意外都降级成状态条上的错误文案。
                dashboardState.setConn(
                    AgentsAnywhereConnState.Error,
                    err.message ?: err.javaClass.simpleName,
                )
            }
        }
    }

    // 会话列表是 REST 拉的(dashboard WS 只推 snapshot,不推列表变更),单独刷。

    fun refreshSessions() {
        rt.scope.launch {
            listError = null
            runCatching { rt.api.listSessions() }
                .onSuccess { sessions = it }
                .onFailure { listError = it.message ?: it.javaClass.simpleName }
        }
    }

    // 首帧起一次:配置齐了才连,否则会对着空 baseUrl 无限重试。
    //
    // **不要加 `started` 之类的「已经起过就别再起」标志** —— 键是 baseUrl /
    // accessToken 两个字符串,重组不会重跑本块,但**从 `aa-session/{sid}`
    // 返回时本屏会重新进组合**(DisposableEffect 已把 dashboardJob cancel
    // 掉了),这时必须重连,否则返回后会看到一个永远空白的列表。
    LaunchedEffect(rt.baseUrl, rt.accessToken) {
        if (rt.configured) {
            refreshSessions()
            startDashboard()
        }
    }

    DisposableEffect(Unit) {
        onDispose { dashboardJob?.cancel() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.agents_anywhere_title)) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!rt.configured || !rt.authenticated) {
                AaNotReady(
                    needLogin = rt.configured,
                    onGoSettings = onGoSettings,
                )
                return@Column
            }
            ConnectionStatusBar(state = dashboardState)
            AaDashboardActions(
                onRefresh = {
                    refreshSessions()
                    startDashboard()
                },
                onOpenDevices = onOpenDevices,
            )
            DashboardPane(
                state = dashboardState,
                sessions = sessions,
                sessionListError = listError,
                onSelectSession = onOpenSession,
            )
        }
    }
}

/** 刷新 / 设备管理两个动作。 */
@Composable
private fun AaDashboardActions(onRefresh: () -> Unit, onOpenDevices: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
            Icon(Lucide.RefreshCw, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.aa_refresh), fontSize = 13.sp)
        }
        OutlinedButton(onClick = onOpenDevices, modifier = Modifier.weight(1f)) {
            Icon(Lucide.MonitorSmartphone, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.aa_devices), fontSize = 13.sp)
        }
    }
}

/** 空态:没配地址 / 没登录,各给一句人话 + 一个去设置的按钮。 */
@Composable
private fun AaNotReady(needLogin: Boolean, onGoSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    if (needLogin) R.string.aa_not_logged_in else R.string.aa_no_server
                ),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = onGoSettings,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(stringResource(R.string.aa_go_settings))
            }
        }
    }
}
