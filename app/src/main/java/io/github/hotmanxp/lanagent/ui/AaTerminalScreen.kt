// ui/AaTerminalScreen.kt — AA 远程终端(0.24.2,路由 service/aa-terminal)
//
// 「服务」栏的第四张卡,与「局域网 SSH 服务」并排。两处终端**共用同一个
// xterm.js 渲染器**([SshTerminalWebView]),只换数据源:
//   局域网 SSH → SshTerminalTransport(JSch ChannelShell)
//   AA 远程    → AaTerminalTransport(WebSocket + base64 帧)
//
// 渲染、字体、复制、快捷命令面板、IME 行为因此完全一致 —— 这也是 0.24.2 摘掉
// termux 的原因(AA 官方那套原生 TerminalView 与本项目 xterm.js 是重复能力)。
//
// ## 入口为什么是「选会话」而不是「选设备」
//
// 端点挂在设备上(`/connectors/{deviceId}/terminals-v2`),但 **root 取自会话的
// cwd**(见 AA 官方 `TerminalController`:`session.cwd ?: throw ...`)。也就是说
// 终端的实际作用域是**会话工作区**,不是设备 home。所以这里让用户选一个会话,
// 终端就开在那个工作区 —— 与 AA 官方行为一致。没有 cwd 的会话会被过滤掉。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.aa.api.SessionsApi
import io.github.hotmanxp.lanagent.aa.feature.auth.AuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一个可开终端的会话(只留终端需要的字段)。 */
private data class AaTerminalTarget(
    val sessionId: String,
    val connectorId: String,
    val title: String,
    val cwd: String,
    /** 宿主设备的在线状态(RemoteSession.connectorStatus),用来在状态条上说明。 */
    val connectorStatus: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AaTerminalScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // 凭据直接读 AA 自己的 AuthSessionStore(它的 backend 已经是本项目的
    // EncryptedSharedPreferences,见该文件 0.24.2 注释)。
    val store = remember { AuthSessionStore(context) }
    val serverUrl = remember { store.readServerUrl() }
    val accessToken = remember { store.readAccessToken() }

    var targets by remember { mutableStateOf<List<AaTerminalTarget>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AaTerminalTarget?>(null) }

    val ready = serverUrl.isNotBlank() && accessToken.isNotBlank()

    LaunchedEffect(ready) {
        if (!ready) { loaded = true; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            runCatching { SessionsApi().listSessions(serverUrl, accessToken, archived = false) }
        }.onSuccess { page ->
            targets = (page.sessions ?: emptyList())
                .filter { !it.cwd.isNullOrBlank() }
                .map {
                    AaTerminalTarget(
                        sessionId = it.id,
                        connectorId = it.connectorId,
                        // 标题可空,空了用 sessionId 前缀兜底(与 AA 官方一致)
                        title = it.title?.takeIf { t -> t.isNotBlank() } ?: it.id.take(8),
                        cwd = it.cwd.orEmpty(),
                        connectorStatus = it.connectorStatus,
                    )
                }
        }.onFailure { error = it.message ?: it.javaClass.simpleName }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selected?.let { stringResource(R.string.aa_terminal_open_for, it.title) }
                            ?: stringResource(R.string.service_aa_terminal_title)
                    )
                },
                navigationIcon = { WbBackIcon(onBack) },
                actions = {
                    if (selected != null) {
                        TextButton(onClick = { selected = null }) {
                            Text(stringResource(R.string.aa_terminal_change_session))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val target = selected
        when {
            !ready -> CenteredHint(
                Modifier.padding(padding),
                stringResource(R.string.aa_terminal_need_login),
            )
            !loaded -> CenteredHint(Modifier.padding(padding), stringResource(R.string.service_loading))
            target == null && targets.isEmpty() -> CenteredHint(
                Modifier.padding(padding),
                error ?: stringResource(R.string.aa_terminal_no_session),
            )
            target == null -> SessionPicker(
                targets = targets,
                onSelect = { selected = it },
                modifier = Modifier.padding(padding),
            )
            else -> {
                val state = remember { mutableStateOf<AaTerminalState>(AaTerminalState.Idle) }
                val transport = remember(target.sessionId, serverUrl, accessToken) {
                    AaTerminalTransport(
                        serverUrl = serverUrl,
                        accessToken = accessToken,
                        deviceId = target.connectorId,
                        root = target.cwd,
                        cwd = ".",
                        scope = scope,
                        onState = { state.value = it },
                    )
                }
                DisposableEffect(transport) {
                    transport.start()
                    onDispose { transport.stop() }
                }
                Column(Modifier.padding(padding).fillMaxSize()) {
                    AaTerminalStateBar(state.value, target.connectorStatus)
                    // 权重:WebView 吃掉剩余空间;xterm.js 自己在页面里 refit,
                    // 与 SSH 终端同款布局。
                    SshTerminalWebView(transport = transport, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AaTerminalStateBar(state: AaTerminalState, deviceName: String) {
    val text = when (state) {
        is AaTerminalState.Idle -> stringResource(R.string.aa_terminal_idle)
        is AaTerminalState.Connecting -> stringResource(R.string.aa_terminal_connecting)
        is AaTerminalState.Open -> stringResource(R.string.aa_terminal_open, deviceName)
        is AaTerminalState.Exited -> stringResource(R.string.aa_terminal_exited, state.code)
        is AaTerminalState.Failed -> stringResource(R.string.aa_terminal_failed, state.message)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CenteredHint(modifier: Modifier, text: String) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun SessionPicker(
    targets: List<AaTerminalTarget>,
    onSelect: (AaTerminalTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("hint") {
            Text(
                text = stringResource(R.string.aa_terminal_pick_session),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        items(targets, key = { it.sessionId }) { t ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { onSelect(t) },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = t.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = t.cwd,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
