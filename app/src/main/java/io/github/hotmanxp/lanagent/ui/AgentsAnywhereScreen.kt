// ui/AgentsAnywhereScreen.kt — Agents-Anywhere server 调试屏(0.21.0 重写)。
//
// 不再是「日志输出屏」,是真的能用的客户端:
//   - 配置(baseUrl/token)+ 状态条 + 手动重连
//   - dashboard 列(connectors/projects/sessions/runtimes)
//   - 选中会话后 → 气泡视图(user/assistant/system 分色,工具/系统靠卡片)
//   - 乐观发送(clientMessageId 关联 → server item 回流后替换)
//   - interrupt / steer / takeover 按钮
//   - notice 卡片(允许/拒绝 + input 字段)
//
// 视觉对齐 WorkBuddy 手机端对话页:浅灰页底 + 白卡族 + 用户中性浅灰气泡
// (`LocalWbExtras.current.userBubble`,见 ui/AgentSessionViews.kt:196)。
//
// 状态架构:
//   - Dashboard:一个 `AgentsAnywhereDashboardState`(`dashboard.snapshot` 推过来
//     就整段替换,server 自己处理 invalidation)。
//   - Session:每个会话一个 `AgentsAnywhereSessionState`,切换会话时新建。
//
// 解析放后台线程:WS 帧由 `AgentsAnywhereWsClient` 内部 `flowOn(Dispatchers.IO)`
// 处理(`data/AgentsAnywhereWsClient.kt:128`),REST 走 OkHttp + `withContext(IO)`;
// 状态本身是 `mutableStateOf`,赋值本身在主线程但只赋值引用,不开销。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentsAnywhereApi
import io.github.hotmanxp.lanagent.data.AgentsAnywhereClient
import io.github.hotmanxp.lanagent.data.AgentsAnywhereConnState
import io.github.hotmanxp.lanagent.data.AgentsAnywhereDashboardState
import io.github.hotmanxp.lanagent.data.AgentsAnywhereEvent
import io.github.hotmanxp.lanagent.data.AgentsAnywherePrefs
import io.github.hotmanxp.lanagent.data.AgentsAnywhereSessionState
import io.github.hotmanxp.lanagent.data.AgentsAnywhereWsClient
import io.github.hotmanxp.lanagent.data.AaOutgoing
import io.github.hotmanxp.lanagent.data.NoticeIn
import io.github.hotmanxp.lanagent.data.OutgoingStatus
import io.github.hotmanxp.lanagent.data.SessionSummary
import io.github.hotmanxp.lanagent.data.TimelineItem
import io.github.hotmanxp.lanagent.data.dispatchDashboardFrame
import io.github.hotmanxp.lanagent.data.dispatchSessionFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// ── 顶层屏 ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsAnywhereScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { AgentsAnywherePrefs(context) }
    val ws = remember { AgentsAnywhereWsClient() }

    // baseUrl / token —— 流式从 DataStore 取(包括 BuildConfig 兜底,见 Prefs)。
    val baseUrl by prefs.baseUrlFlow.collectAsState(initial = "")
    val accessToken by prefs.accessTokenFlow.collectAsState(initial = "")
    var clientId by remember { mutableStateOf("") }
    LaunchedEffect(prefs) { clientId = prefs.clientId() }

    // Api/Client —— baseUrl/token/clientId 变了重建,避免 stale 引用。
    // `AgentsAnywhereApi` 内部的 OkHttpClient 是 companion-object 单例,
    // 所以即使每次保存配置都新建 Api 实例也不会泄漏 dispatcher /
    // connection pool(0.21.0 之前的版本里 OkHttpClient 是 per-instance
    // 创建的,每次保存配置都泄漏一组线程池)。
    val api = remember(baseUrl, accessToken, clientId) {
        AgentsAnywhereApi(baseUrl.trim(), accessToken.trim(), clientId)
    }
    val liveClient = remember(api, ws, prefs) {
        AgentsAnywhereClient(prefs, api, ws)
    }

    // 表单态的 baseUrl/token —— 用户编辑 → 保存 → 写回 prefs。
    // 关键:**用 remember(prefs) 而不是 remember** —— 屏重建时回填上次的编辑中
    // 值,但 prefs 替换(几乎不会发生)就清空。
    var formBaseUrl by remember(prefs) { mutableStateOf(baseUrl) }
    var formAccessToken by remember(prefs) { mutableStateOf(accessToken) }
    LaunchedEffect(baseUrl) { if (formBaseUrl.isBlank()) formBaseUrl = baseUrl }
    LaunchedEffect(accessToken) { if (formAccessToken.isBlank()) formAccessToken = accessToken }

    // 两个独立状态容器 + 订阅 job。
    // **per-session state 只在 AgentsAnywhereScreen 内存活** —— backToDashboard
    // 会清空 `sessionStates`,避免用户多次进出不同会话后 `itemsById` /
    // `outgoingByCmid` 跨会话持久累积。
    val dashboardState = remember { AgentsAnywhereDashboardState() }
    val sessionStates = remember { mutableMapOf<String, AgentsAnywhereSessionState>() }
    var dashboardJob by remember { mutableStateOf<Job?>(null) }
    var sessionJob by remember { mutableStateOf<Job?>(null) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var sessionList by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var sessionListError by remember { mutableStateOf<String?>(null) }

    val selectedSession = selectedSessionId?.let { sessionStates[it] }

    fun startDashboard() {
        dashboardJob?.cancel()
        sessionJob?.cancel()
        dashboardState.setConn(AgentsAnywhereConnState.Connecting, "拉 ticket + 开 WS")
        dashboardJob = scope.launch {
            liveClient.subscribeDashboard(
                baseUrl = baseUrl.trim(),
                accessToken = accessToken.trim(),
            ).collect { ev ->
                when (ev) {
                    is AgentsAnywhereEvent.Incoming -> {
                        val applied = dispatchDashboardFrame(ev.parsed, dashboardState)
                        if (applied) {
                            dashboardState.setConn(AgentsAnywhereConnState.Connected, "dashboard · 已连接")
                        }
                    }
                    is AgentsAnywhereEvent.Lifecycle -> {
                        val kind = when (ev.kind) {
                            AgentsAnywhereEvent.Lifecycle.Kind.Connected ->
                                AgentsAnywhereConnState.Connected
                            AgentsAnywhereEvent.Lifecycle.Kind.Closed ->
                                AgentsAnywhereConnState.Disconnected
                            AgentsAnywhereEvent.Lifecycle.Kind.Failure ->
                                AgentsAnywhereConnState.Error
                            AgentsAnywhereEvent.Lifecycle.Kind.Retrying ->
                                AgentsAnywhereConnState.Error
                        }
                        dashboardState.setConn(kind, ev.message)
                    }
                    is AgentsAnywhereEvent.Unparseable -> Unit
                }
            }
        }
    }

    /**
     * @param preserveOutgoing true = `applyTimelineSnapshot` 不要清空乐观气泡
     *        (`outgoingByCmid`),只移除已被新 snapshot 覆盖的 cmid。
     *        **手动重连**场景必须传 true —— 用户刚发出去但 server 还没回流的事件,
     *        重连不能把气泡吞掉。冷启动场景(从未打开过该会话)传 false,
     *        outgoing map 本来就是空的,等价于「全部清空」。
     */
    fun openSession(sessionId: String, preserveOutgoing: Boolean = false) {
        sessionJob?.cancel()
        dashboardJob?.cancel()
        selectedSessionId = sessionId
        val st = sessionStates.getOrPut(sessionId) { AgentsAnywhereSessionState(sessionId) }
        // 冷启动路径(默认):彻底清空乐观气泡 + 已被新 snapshot 覆盖的 cmid。
        // **手动重连**路径(`preserveOutgoing=true`):不清,`applyTimelineSnapshot`
        // 内部按 cmid 配对移除已覆盖的,保留未覆盖的 —— 用户刚发出去但 server
        // 还没回流的事件不会丢。
        if (!preserveOutgoing) {
            st.clearOutgoing()
        }
        st.setConn(AgentsAnywhereConnState.Connecting, "拉 snapshot + 开 WS")

        sessionJob = scope.launch {
            // 1) 先 snapshot(冷启):拉完后才开 WS,避免 item 重复。
            val snapResult = runCatching { api.fetchSnapshot(sessionId) }
            if (snapResult.isFailure) {
                val err = snapResult.exceptionOrNull()
                val msg = (err as? io.github.hotmanxp.lanagent.data.HttpException)?.let { "${it.code} ${it.message ?: ""}".trim() }
                    ?: err?.message ?: err?.javaClass?.simpleName ?: "?"
                st.setConn(AgentsAnywhereConnState.Error, "snapshot 失败: $msg")
            }
            snapResult.getOrNull()?.let { snap ->
                // session 字段先用一下(状态条信息)
                val sessionObj = snap.session
                val title = (sessionObj["title"] as? JsonPrimitive)?.contentOrNull
                st.applyTimelineSnapshot(snap.timeline)
                snap.state?.let { st.applyRuntimeState(it) }
                snap.session.let { st.applySessionMeta(it) }
                snap.notices.forEach { st.upsertNotice(it) }
                st.setConn(AgentsAnywhereConnState.Connecting,
                    "snapshot 拉完" + (if (!title.isNullOrBlank()) " · $title" else ""))
            }

            // 2) 拉完 snapshot 后再开 WS;cursor 用 snapshot 末尾 nextSeq。
            val cursor = snapResult.getOrNull()?.timeline?.nextSeq ?: 0L
            try {
                liveClient.subscribeSession(
                    sessionId = sessionId,
                    baseUrl = baseUrl.trim(),
                    accessToken = accessToken.trim(),
                ).collect { ev ->
                    when (ev) {
                        is AgentsAnywhereEvent.Incoming -> {
                            val applied = dispatchSessionFrame(ev.parsed, st)
                            if (applied) {
                                st.setConn(AgentsAnywhereConnState.Connected, "ws · 已连接 (cursor seq:$cursor)")
                            }
                        }
                        is AgentsAnywhereEvent.Lifecycle -> {
                            val kind = when (ev.kind) {
                                AgentsAnywhereEvent.Lifecycle.Kind.Connected ->
                                    AgentsAnywhereConnState.Connected
                                AgentsAnywhereEvent.Lifecycle.Kind.Closed ->
                                    AgentsAnywhereConnState.Disconnected
                                AgentsAnywhereEvent.Lifecycle.Kind.Failure ->
                                    AgentsAnywhereConnState.Error
                                AgentsAnywhereEvent.Lifecycle.Kind.Retrying ->
                                    AgentsAnywhereConnState.Error
                            }
                            st.setConn(kind, ev.message)
                        }
                        is AgentsAnywhereEvent.Unparseable -> Unit
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    fun backToDashboard() {
        sessionJob?.cancel()
        selectedSessionId = null
        // 清空所有会话的运行时状态(items / outgoing / notices)。
        // 用户从会话视图退到 dashboard 时,所有 per-session 数据都该
        // 跟着释放 —— 下次再 openSession 走冷启动路径,不会显示陈年
        // 残留气泡。
        sessionStates.clear()
    }

    fun refreshSessionList() {
        scope.launch {
            sessionListError = null
            runCatching { api.listSessions() }
                .onSuccess { sessionList = it }
                .onFailure { sessionListError = it.message ?: it.javaClass.simpleName }
        }
    }

    fun manualReconnect() {
        if (selectedSessionId != null) {
            // 手动重连时保留乐观气泡 —— 用户刚发出去但 server 还没回的事件不能丢。
            openSession(selectedSessionId!!, preserveOutgoing = true)
        } else {
            startDashboard()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            dashboardJob?.cancel()
            sessionJob?.cancel()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedSessionId == null) {
                            stringResource(R.string.agents_anywhere_title)
                        } else {
                            stringResource(R.string.agents_anywhere_session_title)
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        if (selectedSessionId != null) backToDashboard() else onBack()
                    }) {
                        Text(
                            if (selectedSessionId != null) {
                                stringResource(R.string.agents_anywhere_back_to_dashboard)
                            } else {
                                stringResource(R.string.agents_anywhere_back)
                            }
                        )
                    }
                },
            )
        },
    ) { padding ->
        // `imePadding()` 不放 Column parent —— 对齐 `AgentSessionScreen.kt`
        // 习惯,composer 自己处理键盘 inset。理由:屏内有 dashboard 列表 /
        // 会话 timeline 多个区域,Column parent 整体抬会让所有内容一起被
        // 顶起来(状态条 / 配置卡 / 按钮行也跟着上移,视觉割裂);只让
        // composer 自己抬起,才是「聊天输入框」的常规做法。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectionStatusBar(
                state = if (selectedSession != null) selectedSession else dashboardState,
            )
            ConfigCard(
                baseUrl = formBaseUrl,
                accessToken = formAccessToken,
                hasBuildConfigDefaults = prefs.hasBuildConfigDefaults,
                onBaseUrlChange = { formBaseUrl = it },
                onAccessTokenChange = { formAccessToken = it },
                onSave = {
                    scope.launch {
                        prefs.setBaseUrl(formBaseUrl)
                        prefs.setAccessToken(formAccessToken)
                    }
                },
            )
            ButtonRow(
                isInSession = selectedSessionId != null,
                onRefreshSessions = ::refreshSessionList,
                onStartDashboard = ::startDashboard,
                onManualReconnect = ::manualReconnect,
                baseUrlConfigured = baseUrl.isNotBlank(),
            )
            Spacer(Modifier.height(4.dp))

            if (selectedSession != null) {
                SessionPane(
                    state = selectedSession,
                    api = api,
                    onBack = ::backToDashboard,
                    onInterrupt = {
                        scope.launch {
                            runCatching { api.interrupt(selectedSession.sessionId) }
                        }
                    },
                )
            } else {
                DashboardPane(
                    state = dashboardState,
                    sessions = sessionList,
                    sessionListError = sessionListError,
                    onSelectSession = ::openSession,
                )
            }
        }
    }
}

// ── 连接状态条 ─────────────────────────────────────────────────────────

@Composable
private fun ConnectionStatusBar(state: Any) {
    val (kind, text) = when (state) {
        is AgentsAnywhereDashboardState -> state.conn to state.statusText
        is AgentsAnywhereSessionState -> state.conn to state.statusText
        else -> AgentsAnywhereConnState.NotConfigured to "未连接"
    }
    val (color, label) = when (kind) {
        AgentsAnywhereConnState.NotConfigured -> Color(0xFF9E9E9E) to "未配置"
        AgentsAnywhereConnState.Disconnected -> Color(0xFF9E9E9E) to "已断开"
        AgentsAnywhereConnState.Connecting -> Color(0xFFFFA000) to "连接中"
        AgentsAnywhereConnState.Connected -> Color(0xFF2E7D32) to "已连接"
        AgentsAnywhereConnState.Error -> Color(0xFFC62828) to "错误"
        AgentsAnywhereConnState.Cancelled -> Color(0xFF9E9E9E) to "已取消"
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── 配置卡 ─────────────────────────────────────────────────────────────

@Composable
private fun ConfigCard(
    baseUrl: String,
    accessToken: String,
    hasBuildConfigDefaults: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onAccessTokenChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.agents_anywhere_config_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text(stringResource(R.string.agents_anywhere_field_base_url)) },
                placeholder = { Text("http://192.168.1.10:8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = accessToken,
                onValueChange = onAccessTokenChange,
                label = { Text(stringResource(R.string.agents_anywhere_field_access_token)) },
                placeholder = { Text("eyJhbGciOi...") },
                singleLine = true,
                supportingText = {
                    Text(
                        text = stringResource(R.string.agents_anywhere_field_access_token_hint),
                        fontSize = 10.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSave) {
                    Text(stringResource(R.string.agents_anywhere_save))
                }
                if (hasBuildConfigDefaults) {
                    Text(
                        text = stringResource(R.string.agents_anywhere_local_props_active),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── 按钮行 ─────────────────────────────────────────────────────────────

@Composable
private fun ButtonRow(
    isInSession: Boolean,
    baseUrlConfigured: Boolean,
    onRefreshSessions: () -> Unit,
    onStartDashboard: () -> Unit,
    onManualReconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onRefreshSessions,
            enabled = baseUrlConfigured && !isInSession,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.agents_anywhere_list_sessions), fontSize = 12.sp)
        }
        Button(
            onClick = if (isInSession) onManualReconnect else onStartDashboard,
            enabled = baseUrlConfigured,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(
                    if (isInSession) R.string.agents_anywhere_reconnect
                    else R.string.agents_anywhere_subscribe_dashboard
                ),
                fontSize = 12.sp,
            )
        }
    }
}

// ── Dashboard 列表 ────────────────────────────────────────────────────

@Composable
private fun DashboardPane(
    state: AgentsAnywhereDashboardState,
    sessions: List<SessionSummary>,
    sessionListError: String?,
    onSelectSession: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item("section-sessions") {
            SectionTitle(
                text = stringResource(R.string.agents_anywhere_dashboard_sessions)
                    + " (${sessions.size})"
            )
        }
        if (sessions.isEmpty()) {
            item("sessions-empty") {
                Text(
                    text = sessionListError
                        ?: stringResource(R.string.agents_anywhere_dashboard_sessions_empty),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                )
            }
        } else {
            items(sessions, key = { it.id }) { s ->
                SessionRow(
                    session = s,
                    onClick = { onSelectSession(s.id) },
                )
            }
        }

        item("section-projects") {
            SectionTitle(text = stringResource(R.string.agents_anywhere_dashboard_projects)
                + " (${state.projects.size})")
        }
        items(state.projects, key = { it.toString() }) { proj ->
            SimpleJsonRow(proj)
        }

        item("section-connectors") {
            SectionTitle(text = stringResource(R.string.agents_anywhere_dashboard_connectors)
                + " (${state.connectors.size})")
        }
        items(state.connectors, key = { it.toString() }) { conn ->
            SimpleJsonRow(conn)
        }

        item("section-runtimes") {
            SectionTitle(text = stringResource(R.string.agents_anywhere_dashboard_runtimes)
                + " (${state.runtimes.size})")
        }
        item("runtimes-empty") {
            Text(
                text = stringResource(R.string.agents_anywhere_dashboard_runtimes_empty),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
    val title = session.title?.takeIf { it.isNotBlank() } ?: session.id
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                val meta = listOfNotNull(
                    session.status,
                    session.runtime,
                    session.cwd,
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = session.id.take(8),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SimpleJsonRow(obj: JsonObject) {
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["id"] as? JsonPrimitive)?.contentOrNull
        ?: obj.toString().take(40)
    val meta = listOfNotNull(
        (obj["status"] as? JsonPrimitive)?.contentOrNull,
        (obj["runtimeType"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["runtime"] as? JsonPrimitive)?.contentOrNull,
        (obj["workspacePath"] as? JsonPrimitive)?.contentOrNull,
    ).joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(text = name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── 会话视图 ───────────────────────────────────────────────────────────

@Composable
private fun SessionPane(
    state: AgentsAnywhereSessionState,
    api: AgentsAnywhereApi,
    onBack: () -> Unit,
    onInterrupt: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SessionHeader(state = state, onInterrupt = onInterrupt)
        if (state.openNotices().isNotEmpty()) {
            NoticeStrip(state = state, api = api)
        }
        TimelineView(state = state, modifier = Modifier.weight(1f))
        Composer(state = state, api = api)
    }
}

@Composable
private fun SessionHeader(
    state: AgentsAnywhereSessionState,
    onInterrupt: () -> Unit,
) {
    val meta = state.sessionMeta
    val title = (meta?.get("title") as? JsonPrimitive)?.contentOrNull
        ?: state.sessionId.take(16)
    val status = state.runtimeState?.status ?: "—"
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    text = "status=$status · sid=${state.sessionId.take(12)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (state.isStreaming) {
                IconButton(onClick = onInterrupt) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.agents_anywhere_interrupt),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── Timeline 渲染 ─────────────────────────────────────────────────────

@Composable
private fun TimelineView(state: AgentsAnywhereSessionState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val items = state.orderedItems()
    val outgoing = state.outgoingByCmid.values
        .filter { it.status != OutgoingStatus.Sent }
        .sortedBy { it.sentAt }

    // 自动滚到底部:新消息到达 / outgoing 状态变 都触发。
    LaunchedEffect(items.size, outgoing.size) {
        if (items.isNotEmpty() || outgoing.isNotEmpty()) {
            listState.animateScrollToItem((items.size + outgoing.size) - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (items.isEmpty() && outgoing.isEmpty()) {
            item("empty") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.agents_anywhere_session_empty),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(items, key = { it.id }) { item ->
            TimelineBubble(item = item)
        }
        items(outgoing, key = { it.clientMessageId }) { out ->
            OutgoingBubble(out = out)
        }
    }
}

@Composable
private fun TimelineBubble(item: TimelineItem) {
    when (item.type) {
        "message" -> MessageBubble(item = item)
        "tool" -> ToolBubble(item = item)
        "system" -> SystemNoteBubble(item = item)
        "marker" -> MarkerNoteBubble(item = item)
        "artifact" -> ArtifactBubble(item = item)
        else -> SystemNoteBubble(item = item)
    }
}

@Composable
private fun MessageBubble(item: TimelineItem) {
    val isUser = item.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) LocalWbExtras.current.userBubble
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.onSurface

    // content 可能是 string / {text, ...} / 多块内容 —— 简单抽出可见字符串。
    val text = extractMessageText(item.content)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        // **不设 320dp 上限** —— 对齐 `AgentSessionViews.UserBubble` 的风格,
        // 长文占满可用宽,WorkBuddy 风格的"气泡随内容自适应"而不是 IM 那种
        // 固定最大宽度。
        Surface(
            color = bubbleColor,
            contentColor = textColor,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (text.isNotBlank()) {
                    Text(text = text, fontSize = 14.sp, lineHeight = 20.sp)
                } else {
                    Text(
                        text = "(空消息)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.status == "running" || item.status == "pending") {
                    Text(
                        text = item.status,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "${item.id.takeLast(6)} · seq=${item.updatedSeq}",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ToolBubble(item: TimelineItem) {
    val accent = when (item.status) {
        "running", "pending" -> MaterialTheme.colorScheme.tertiary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // `source.itemType` 是 server 端的工具名(e.g. `Read` / `Bash` /
    // `Edit`),最贴切;兜底走 `source.event` / type。
    val name = item.source?.itemType?.takeIf { it.isNotBlank() }
        ?: item.source?.event?.takeIf { it.isNotBlank() }
        ?: item.type
        .ifBlank { "tool" }
    // **摘要 80 字上限** —— 工具输出/入参经常 KB 级,这里只给个标题党提示,
    // 真要看全内容走会话详情(0.21.0 调试屏不打算承担完整工具输出渲染)。
    val inputHint = item.source?.let { src ->
        // `source` 当前只透传 cmid / runtime 等元数据,真实 input 在
        // `item.content`(payload.tool_call.input)里 —— 抽出可见字符串截 80 字。
        extractMessageText(item.content).take(80).ifBlank { null }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Construction,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = item.status,
                    fontSize = 10.sp,
                    color = accent,
                )
            }
            if (!inputHint.isNullOrBlank()) {
                Text(
                    text = inputHint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SystemNoteBubble(item: TimelineItem) {
    val txt = extractMessageText(item.content).ifBlank { "(system)" }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = txt,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MarkerNoteBubble(item: TimelineItem) {
    val txt = extractMessageText(item.content).ifBlank { "(marker)" }
    Text(
        text = "— $txt —",
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun ArtifactBubble(item: TimelineItem) {
    val txt = extractMessageText(item.content).ifBlank { item.id }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Rounded.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = txt, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OutgoingBubble(out: AaOutgoing) {
    val align = Alignment.End
    val color = when (out.status) {
        OutgoingStatus.Sending -> LocalWbExtras.current.userBubble.copy(alpha = 0.6f)
        OutgoingStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        OutgoingStatus.Sent -> LocalWbExtras.current.userBubble
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        // 不设 320dp 上限 —— 同 MessageBubble,长文占满宽。
        Surface(
            color = color,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = out.content, fontSize = 14.sp, lineHeight = 20.sp)
                Text(
                    text = when (out.status) {
                        OutgoingStatus.Sending -> "发送中…"
                        OutgoingStatus.Failed -> "失败 · 点重发"
                        OutgoingStatus.Sent -> "已发送"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Composer ──────────────────────────────────────────────────────────

@Composable
private fun Composer(state: AgentsAnywhereSessionState, api: AgentsAnywhereApi) {
    val scope = rememberCoroutineScope()
    var draft by remember(state.sessionId) { mutableStateOf("") }
    var sending by remember(state.sessionId) { mutableStateOf(false) }

    // **imePadding 挂在 Composer 自己身上**(不是 Column parent),对齐
    // `AgentSessionScreen.kt` 的做法 —— 键盘弹起只让输入区上抬,
    // dashboard / timeline / 状态条不被一起顶起来,视觉更稳。
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(R.string.agents_anywhere_input_hint)) },
                enabled = !sending && state.conn != AgentsAnywhereConnState.NotConfigured,
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isBlank() || sending) return@IconButton
                    val cmid = "cmid-${System.currentTimeMillis()}"
                    state.trackOutgoing(cmid, text, OutgoingStatus.Sending)
                    sending = true
                    draft = ""
                    scope.launch {
                        runCatching {
                            api.sendMessage(
                                sessionId = state.sessionId,
                                content = text,
                                clientMessageId = cmid,
                            )
                        }.onSuccess { resp ->
                            sending = false
                            if (resp.ok) {
                                // 标记"已发送",等 server 回真实 item 后由 timeline.item_created
                                // 按 cmid 移除;如果 server 推完才收到 ok,这里已经标了 Sent 也无害。
                                state.updateOutgoingStatus(cmid, OutgoingStatus.Sent)
                            } else {
                                state.updateOutgoingStatus(cmid, OutgoingStatus.Failed)
                            }
                        }.onFailure {
                            state.updateOutgoingStatus(cmid, OutgoingStatus.Failed)
                            sending = false
                        }
                    }
                },
                enabled = draft.isNotBlank() && !sending,
            ) {
                if (sending) {
                    Icon(Icons.Rounded.Stop, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error)
                } else {
                    Icon(Icons.Rounded.Send,
                        contentDescription = stringResource(R.string.agents_anywhere_send),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ── Notice 卡片条 ─────────────────────────────────────────────────────

@Composable
private fun NoticeStrip(state: AgentsAnywhereSessionState, api: AgentsAnywhereApi) {
    val scope = rememberCoroutineScope()
    val notices = state.openNotices()

    // 不在 strip 外层套统一背景 —— 0.21.0 之前所有 severity 都用
    // `errorContainer.copy(alpha=0.18f)` 看起来全是错的(实际有 info /
    // success / warning)。改成每条 NoticeCard 自己的 container 色。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notices.forEach { notice ->
            NoticeCard(notice = notice,
                onRespond = { actionId, input ->
                    scope.launch {
                        runCatching {
                            api.respondNotice(
                                sessionId = state.sessionId,
                                noticeId = notice.noticeId,
                                actionId = actionId,
                                input = input,
                            )
                        }
                    }
                })
        }
    }
}

@Composable
private fun NoticeCard(
    notice: NoticeIn,
    onRespond: (actionId: String, input: JsonObject?) -> Unit,
) {
    var pendingActionId by remember(notice.noticeId) { mutableStateOf<String?>(null) }
    var inputText by remember(notice.noticeId) { mutableStateOf("") }
    val accent = when (notice.severity) {
        "error" -> MaterialTheme.colorScheme.error
        "warning" -> Color(0xFFE65100)
        "success" -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.primary
    }
    // **整张 notice 卡按 severity 用对应 container 色**,不再统一
    // errorContainer —— info / success notice 不该长得像 error。
    // border 也跟着 accent,outline 区分比 alpha 弱化更清晰。
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when (notice.severity) {
            "error" -> MaterialTheme.colorScheme.errorContainer
            "warning" -> MaterialTheme.colorScheme.tertiaryContainer
            "success" -> Color(0xFFE6F4EA)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Warning, contentDescription = null,
                tint = accent, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = notice.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = notice.interactionType ?: notice.type,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!notice.message.isNullOrBlank()) {
            Text(
                text = notice.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 输入字段:当用户选了需要 input 的 action 才显示。
        if (pendingActionId != null) {
            val action = notice.actions.firstOrNull { it.actionId == pendingActionId }
            val needInput = action?.input?.required == true || action?.input?.schema != null
            if (needInput) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text(stringResource(R.string.agents_anywhere_notice_input_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            notice.actions.forEach { action ->
                val isPending = pendingActionId == action.actionId
                val danger = action.style == "danger"
                TextButton(
                    onClick = {
                        if (!isPending) {
                            pendingActionId = action.actionId
                            inputText = ""
                        } else {
                            val input = if (inputText.isNotBlank()) {
                                buildJsonObject {
                                    put("text", inputText)
                                }
                            } else null
                            onRespond(action.actionId, input)
                            pendingActionId = null
                            inputText = ""
                        }
                    },
                ) {
                    Icon(
                        if (isPending) Icons.Rounded.Check else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = action.label,
                        fontSize = 11.sp,
                        color = if (danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (notice.actions.isNotEmpty()) {
                TextButton(onClick = {
                    pendingActionId = null
                    inputText = ""
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = null,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.agents_anywhere_notice_cancel),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        }
    }
}

// ── 工具 ──────────────────────────────────────────────────────────────

/**
 * `content` 是 `Any` 形态,常见值:
 *   - 字符串 → 直接用
 *   - `{ text: "...", ... }` → 取 text 字段
 *   - `{ parts: [{text:"a"},...] }` → 拼接
 *   - `{ blocks: [...] }` → 同上
 * 都不是 → 序列化整段截前 200 字。**永远不抛** —— 不合规的 content 不该让
 * 整页崩。
 */
private fun extractMessageText(content: kotlinx.serialization.json.JsonElement?): String {
    if (content == null) return ""
    return runCatching {
        when (content) {
            is JsonPrimitive -> {
                if (content.isString) content.contentOrNull.orEmpty()
                else content.content
            }
            is JsonObject -> {
                val textField = (content["text"] as? JsonPrimitive)?.contentOrNull
                if (!textField.isNullOrEmpty()) return@runCatching textField
                val parts = content["parts"] as? kotlinx.serialization.json.JsonArray
                if (parts != null) {
                    return@runCatching parts.joinToString("") { p ->
                        (p as? JsonObject)?.let { extractMessageText(it) }.orEmpty()
                    }
                }
                val blocks = content["blocks"] as? kotlinx.serialization.json.JsonArray
                if (blocks != null) {
                    return@runCatching blocks.joinToString("") { b ->
                        (b as? JsonObject)?.let { extractMessageText(it) }.orEmpty()
                    }
                }
                content.toString().take(200)
            }
            else -> content.toString().take(200)
        }
    }.getOrDefault("")
}
