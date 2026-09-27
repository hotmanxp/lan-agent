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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import io.github.hotmanxp.lanagent.data.AttachmentRef
import io.github.hotmanxp.lanagent.data.SecureTokenStore
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
import java.util.UUID
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.QrCode
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X

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
    // mobile-login QR dialog —— 单独一态,避免跟 ConfigCard 的表单态互相干扰。
    var showMobileLogin by remember { mutableStateOf(false) }

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
                onMobileLogin = { showMobileLogin = true },
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

    // Mobile-login QR dialog —— 挂在 Scaffold 外层,确保盖在所有内容之上
    // (Scaffold 内层会被 imePadding / navigationBarsPadding 折腾)。
    // 触发条件:用户点 ConfigCard 的「扫码登录」,且 baseUrl 已配。
    if (showMobileLogin && baseUrl.isNotBlank()) {
        MobileLoginDialog(
            api = api,
            baseUrl = baseUrl,
            onSuccess = { accessToken, refreshToken ->
                // exchange 拿到新 token —— 写 encrypted store + DataStore override
                // flag,然后重连(dashboard / 当前 session 任一)。SecureTokenStore
                // 持有者是单例,直接通过 prefs.setAccessToken 走,不走原 datastore
                // 路径 —— 那里现在只写 override flag,token 实体由 SecureTokenStore
                // 自己管。
                prefs.setAccessToken(accessToken)
                SecureTokenStore.get(context).putRefreshToken(refreshToken)
                formAccessToken = accessToken
                manualReconnect()
            },
            onDismiss = { showMobileLogin = false },
        )
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
    onMobileLogin: () -> Unit,
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
                // 扫码登录 —— 已登录设备的浏览器扫码 → server 推 approved →
                // exchange 拿 token。baseUrl 必填,token 这条路 server 端会要求
                // Bearer,所以这条流**只适用于已登录设备换 token**,不适合全新
                // 设备首次登录(对话框里有 hint 提示)。
                TextButton(
                    onClick = onMobileLogin,
                    enabled = baseUrl.isNotBlank(),
                ) {
                    Icon(
                        Lucide.QrCode,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.agents_anywhere_mobile_login),
                        fontSize = 12.sp,
                    )
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
            Icon(Lucide.RefreshCw, contentDescription = null,
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
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        SessionHeader(
            state = state,
            onInterrupt = onInterrupt,
            onToggleTakeover = {
                val sid = state.sessionId
                val on = (state.sessionMeta?.get("takeover") as? JsonPrimitive)?.contentOrNull == "true"
                scope.launch {
                    runCatching { if (on) api.disableTakeover(sid) else api.enableTakeover(sid) }
                }
            },
        )
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
    onToggleTakeover: () -> Unit,
) {
    val meta = state.sessionMeta
    val title = (meta?.get("title") as? JsonPrimitive)?.contentOrNull
        ?: state.sessionId.take(16)
    val status = state.runtimeState?.status ?: "—"
    val takeoverOn = (meta?.get("takeover") as? JsonPrimitive)?.contentOrNull
        ?.equals("true", ignoreCase = true) == true
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
                    text = listOfNotNull(
                        "status=$status",
                        if (takeoverOn) "takeover" else null,
                        "sid=${state.sessionId.take(12)}",
                    ).joinToString(" · "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // 接管 toggle:对照 opencc-web SessionHeader 的行为 —— 接管中 = 锁住
            // (`Lucide.Lock` 闭合),未接管 = 锁开(`Lucide.LockOpen`)。
            // server 端 session.meta.takeover=true 时锁闭 + primary tint 表示
            // 「已接管」,false 时锁开 + onSurfaceVariant outline 表示「释放」。
            // 点一下在 POST / DELETE 之间切;server 通过 session.meta.updated
            // 推回最新值,这里单纯反映。
            IconButton(onClick = onToggleTakeover) {
                Icon(
                    imageVector = if (takeoverOn) Lucide.Lock else Lucide.LockOpen,
                    contentDescription = stringResource(
                        if (takeoverOn) R.string.agents_anywhere_takeover_off
                        else R.string.agents_anywhere_takeover_on
                    ),
                    tint = if (takeoverOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isStreaming) {
                IconButton(onClick = onInterrupt) {
                    Icon(
                        Glyph.SolidSquare,
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
    // 然后**走 Markdown 渲染** —— server 端的 assistant 回复几乎都带 markdown
    // 格式(代码块 / 列表 / 链接),plain Text 看起来像一坨。
    // tool / artifact bubble **不渲染 markdown**(任务要求)。
    // 解析放后台线程:MarkdownParser.parse 是纯 Kotlin 同步函数;真实瓶颈在
    // CodeBox 里跑的 `highlightCode`(已挪到 Dispatchers.Default,见
    // ui/CodeHighlight.kt)。MarkdownText 内部已经 `remember(markdown)` 缓存,
    // 单次消息渲染不会触发重复解析。
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
                    MarkdownText(
                        markdown = text,
                        // 用户气泡短文本偏多,assistant 长文也只过 200dp 不卡。
                        // 不强制限宽 —— bubble 自身 fillMaxWidth,内部让 Markdown
                        // 自己走 inline 元素(列表/代码块带横向滚动撑宽度)。
                        baseStyle = LocalTextStyle.current.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        compact = isUser,
                    )
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
                    Glyph.Hammer,
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
                Lucide.TriangleAlert,
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
                Lucide.Paperclip,
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
                // 走 Markdown 渲染 —— 见 MessageBubble 内的注释,用户可能粘贴
                // 带 markdown 的内容进来。
                MarkdownText(
                    markdown = out.content,
                    baseStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    compact = true,
                )
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

/** Composer 内的附件状态机 —— 严格说就是「选好了 / 上传中 / 拿到 fileId / 失败」四态。 */
private sealed class ComposerAttachment {
    abstract val uri: android.net.Uri
    abstract val displayName: String
    abstract val mime: String

    /** 已选好,等点发送时上传。 */
    data class Pending(
        override val uri: android.net.Uri,
        override val displayName: String,
        override val mime: String,
    ) : ComposerAttachment()

    /** 正在上传 —— UI 显示 spinner + tertiary accent。 */
    data class Uploading(
        override val uri: android.net.Uri,
        override val displayName: String,
        override val mime: String,
    ) : ComposerAttachment()

    /** 上传完成 —— 持有 fileId,等 send 时塞进 MessageCreateRequest.attachments。 */
    data class Uploaded(
        override val uri: android.net.Uri,
        override val displayName: String,
        override val mime: String,
        val fileId: String,
    ) : ComposerAttachment()

    /** 上传失败 —— chip 红边,允许用户点 X 移除重选。 */
    data class Failed(
        override val uri: android.net.Uri,
        override val displayName: String,
        override val mime: String,
        val reason: String,
    ) : ComposerAttachment()
}

/** 单次发送最多 5 条附件 — 对齐 server `sessions_fs.py:106`(`max 5 / 25MiB each`)。 */
private const val MAX_ATTACHMENT_COUNT = 5

@Composable
private fun Composer(state: AgentsAnywhereSessionState, api: AgentsAnywhereApi) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draft by remember(state.sessionId) { mutableStateOf("") }
    var sending by remember(state.sessionId) { mutableStateOf(false) }
    // 附件 chip 列表 — key 用 sessionId,切会话时整段重置,跟 draft 同步。
    var attachments by remember(state.sessionId) {
        mutableStateOf<List<ComposerAttachment>>(emptyList())
    }

    // 文件选择器 —— `OpenMultipleDocuments` 走 Storage Access Framework(API 19+),
    // 不要任何存储权限。mime = `*/*` 让用户能选任意类型,跟「附件」语义对齐
    // (server 端 multipart mime 由 client 在 form-data 里声明,不强校验)。
    val pickDocuments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val room = MAX_ATTACHMENT_COUNT - attachments.size
        if (room <= 0) return@rememberLauncherForActivityResult
        val picked = uris.take(room).map { uri ->
            val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment.orEmpty()
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            ComposerAttachment.Pending(uri, name, mime)
        }
        attachments = attachments + picked
    }

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
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // 附件 chip 条 —— 有附件时才渲染,没附件不占行高。
            if (attachments.isNotEmpty()) {
                AttachmentChips(
                    attachments = attachments,
                    onRemove = { att -> attachments = attachments - att },
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 附件按钮:挂在输入条左侧,跟发送钮视觉上对称。
                IconButton(
                    onClick = { pickDocuments.launch(arrayOf("*/*")) },
                    enabled = attachments.size < MAX_ATTACHMENT_COUNT &&
                        !sending &&
                        state.conn != AgentsAnywhereConnState.NotConfigured,
                ) {
                    Icon(
                        Lucide.Paperclip,
                        contentDescription = stringResource(R.string.agents_anywhere_attach),
                        tint = if (attachments.size < MAX_ATTACHMENT_COUNT) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    )
                }
                Spacer(Modifier.width(4.dp))
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
                        // 文本或附件至少一个非空才能发 —— 纯附件(没文字说明)也合法。
                        if ((text.isBlank() && attachments.isEmpty()) || sending) {
                            return@IconButton
                        }
                        val cmid = "cmid-${UUID.randomUUID()}"
                        state.trackOutgoing(cmid, text, OutgoingStatus.Sending)
                        sending = true
                        draft = ""
                        // **发送时把 attachments 全程保留引用**,失败时还原回
                        // chip 列表(用户重试不用重选)。
                        val sentAttachments = attachments
                        scope.launch {
                            // ① 先把所有 Pending / Failed 状态的附件上传(Uploaded 直接复用 fileId)。
                            val refs = mutableListOf<AttachmentRef>()
                            val failed = mutableListOf<ComposerAttachment>()
                            for (att in sentAttachments) {
                                when (att) {
                                    is ComposerAttachment.Uploaded -> {
                                        refs += AttachmentRef(fileId = att.fileId)
                                    }
                                    is ComposerAttachment.Pending,
                                    is ComposerAttachment.Failed -> {
                                        // 标 Uploading —— UI 立刻刷 spinner,
                                        // 用户能看到「正在上传哪几条」而不是干等。
                                        val uploading = ComposerAttachment.Uploading(
                                            uri = att.uri,
                                            displayName = att.displayName,
                                            mime = att.mime,
                                        )
                                        attachments = attachments.map { existing ->
                                            if (existing.uri == att.uri) uploading else existing
                                        }
                                        runCatching {
                                            val bytes = context.contentResolver
                                                .openInputStream(att.uri)
                                                ?.use { it.readBytes() }
                                                ?: throw IllegalStateException("无法读取附件字节")
                                            api.uploadAttachment(
                                                sessionId = state.sessionId,
                                                bytes = bytes,
                                                filename = att.displayName,
                                                mediaType = att.mime,
                                            )
                                        }.onSuccess { uploaded ->
                                            refs += AttachmentRef(fileId = uploaded.fileId)
                                            attachments = attachments.map { existing ->
                                                if (existing.uri == att.uri) ComposerAttachment.Uploaded(
                                                    uri = att.uri,
                                                    displayName = att.displayName,
                                                    mime = att.mime,
                                                    fileId = uploaded.fileId,
                                                ) else existing
                                            }
                                        }.onFailure { err ->
                                            failed += att.copyReason(
                                                err.message ?: err.javaClass.simpleName,
                                            )
                                            attachments = attachments.map { existing ->
                                                if (existing.uri == att.uri) ComposerAttachment.Failed(
                                                    uri = att.uri,
                                                    displayName = att.displayName,
                                                    mime = att.mime,
                                                    reason = err.message ?: err.javaClass.simpleName,
                                                ) else existing
                                            }
                                        }
                                    }
                                    is ComposerAttachment.Uploading -> {
                                        // 极少触达 —— 同一 chip 在两次连点间跨了状态。
                                        // 不处理,继续推进 refs(理论上不应有 fileId)。
                                    }
                                }
                            }
                            // ② 上传全失败 → 不发消息,标记整条 outgoing 失败,
                            // 让用户能直接看到哪些附件卡住。
                            if (failed.isNotEmpty()) {
                                attachments = sentAttachments.map { a ->
                                    val m = failed.firstOrNull { it.uri == a.uri }
                                    if (m != null) m else a
                                }
                                sending = false
                                state.updateOutgoingStatus(cmid, OutgoingStatus.Failed)
                                return@launch
                            }
                            // ③ 发消息。
                            runCatching {
                                api.sendMessage(
                                    sessionId = state.sessionId,
                                    content = text,
                                    attachments = refs,
                                    clientMessageId = cmid,
                                )
                            }.onSuccess { resp ->
                                sending = false
                                if (resp.ok) {
                                    state.updateOutgoingStatus(cmid, OutgoingStatus.Sent)
                                    // 整条消息成功 → 清空附件 chip 列表。
                                    attachments = emptyList()
                                } else {
                                    state.updateOutgoingStatus(cmid, OutgoingStatus.Failed)
                                }
                            }.onFailure {
                                state.updateOutgoingStatus(cmid, OutgoingStatus.Failed)
                                sending = false
                            }
                        }
                    },
                    enabled = (draft.isNotBlank() || attachments.isNotEmpty()) &&
                        !sending &&
                        attachments.none {
                            it is ComposerAttachment.Pending ||
                                it is ComposerAttachment.Uploading
                        },
                ) {
                    if (sending) {
                        Icon(
                            Glyph.SolidSquare, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Icon(
                            Lucide.ArrowUp,
                            contentDescription = stringResource(R.string.agents_anywhere_send),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 把任意 [ComposerAttachment] 状态转成携带错误信息的 [ComposerAttachment.Failed]。 */
private fun ComposerAttachment.copyReason(reason: String): ComposerAttachment.Failed =
    when (this) {
        is ComposerAttachment.Pending -> ComposerAttachment.Failed(uri, displayName, mime, reason)
        is ComposerAttachment.Failed -> ComposerAttachment.Failed(uri, displayName, mime, reason)
        is ComposerAttachment.Uploading -> ComposerAttachment.Failed(uri, displayName, mime, reason)
        is ComposerAttachment.Uploaded -> ComposerAttachment.Failed(uri, displayName, mime, reason)
    }

/** 附件 chip 行 —— 横向 LazyRow,溢出可滚。 */
@Composable
private fun AttachmentChips(
    attachments: List<ComposerAttachment>,
    onRemove: (ComposerAttachment) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(attachments.size) { idx ->
            val att = attachments[idx]
            AttachmentChip(att = att, onRemove = { onRemove(att) })
        }
    }
}

@Composable
private fun AttachmentChip(
    att: ComposerAttachment,
    onRemove: () -> Unit,
) {
    val accent = when (att) {
        is ComposerAttachment.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
        is ComposerAttachment.Uploading -> MaterialTheme.colorScheme.tertiary
        is ComposerAttachment.Uploaded -> MaterialTheme.colorScheme.primary
        is ComposerAttachment.Failed -> MaterialTheme.colorScheme.error
    }
    val label = when (att) {
        is ComposerAttachment.Pending -> att.displayName
        is ComposerAttachment.Uploading ->
            "${att.displayName} · ${stringResource(R.string.agents_anywhere_attach_uploading)}"
        is ComposerAttachment.Uploaded ->
            "${att.displayName} · ${stringResource(R.string.agents_anywhere_attach_uploaded)}"
        is ComposerAttachment.Failed ->
            "${att.displayName} · ${
                stringResource(R.string.agents_anywhere_attach_failed, att.reason)
            }"
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            if (att is ComposerAttachment.Uploading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = accent,
                )
                Spacer(Modifier.width(4.dp))
            } else {
                Icon(
                    Lucide.Paperclip,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                color = accent,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(R.string.agents_anywhere_attach_remove),
                    tint = accent,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * 查 SAF 返回 URI 的显示名 —— 大多数 DocumentsProvider 都支持
 * `OpenableColumns.DISPLAY_NAME`,失败时退到 `lastPathSegment`。
 */
private fun queryDisplayName(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? {
    return runCatching {
        resolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
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
                Lucide.TriangleAlert, contentDescription = null,
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
            // notice.message 也走 Markdown —— server 端的 message 经常是
            // "请选择文件:" + 内嵌文件名 / 路径,plain Text 显示能看但
            // 不带任何结构。compact=true 让多块之间的间距收紧到 4dp。
            MarkdownText(
                markdown = notice.message,
                baseStyle = LocalTextStyle.current.copy(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                compact = true,
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
                        if (isPending) Lucide.Check else Lucide.Play,
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
                    Icon(Lucide.X, contentDescription = null,
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
