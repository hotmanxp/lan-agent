// ui/AgentSessionsScreen.kt — 原生「会话列表」屏。
//
// 入口:InstancesScreen 的实例卡「会话」按钮 → agent-sessions/{baseUrl}/{instance}。
// 视觉参考 WorkBuddy 手机端左侧会话抽屉:顶栏「标题 + 实例名面包屑」,列表项是
// 圆角块(标题 + 相对时间 + 模型标签),空态/错误态都有明确文案。
//
// 数据:`GET /api/agent/sessions`(`agent.ts:1991`)。会话列表天然属于「实例」
// 而不是「某条会话」,所以拉取范围由服务端按实例 cwd 收口 —— 客户端不需要
// 传任何过滤参数。
//
// 刷新策略:STARTED 时 5s 轮询(对齐 InstancesScreen 的 2.5s 思路,但列表变化
// 慢,5s 已足够;真正的实时性在详情页由 SSE 负责)+ 顶栏手动刷新。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.AgentSessionMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSessionsScreen(
    baseUrl: String,
    instanceName: String,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val api = remember(baseUrl) { AgentApi(baseUrl) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var sessions by remember { mutableStateOf<List<AgentSessionMeta>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // 强制重跑轮询 effect 的钥匙(顶栏手动刷新用)
    var refreshTick by remember { mutableStateOf(0) }

    suspend fun refresh() {
        try {
            sessions = api.listSessions()
            error = null
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api, refreshTick) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(5_000)
            }
        }
    }
    // 相对时间定时器 —— 15s 一次,让「N 分钟前」不卡住
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    fun startNewSession() {
        if (creating) return
        creating = true
        scope.launch {
            val res = runCatching { api.createSession() }
            creating = false
            res.fold(
                onSuccess = { sid -> onOpenSession(sid) },
                onFailure = { t ->
                    snackbarHostState.showSnackbar(
                        t.message ?: t.toString()
                    )
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.webview_back_cd),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.agent_sessions_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (instanceName.isNotBlank()) {
                            Text(
                                text = instanceName,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.agent_sessions_refresh),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                sessions.isEmpty() && loading -> CenterBox { CircularProgressIndicator() }

                sessions.isEmpty() && error != null -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.agent_sessions_error, error!!),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "请确认实例以 --lan 启动且端口可达",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item("new-session") {
                        NewSessionPill(busy = creating, onClick = { startNewSession() })
                    }
                    if (error != null) {
                        item("stale-error") {
                            Text(
                                text = stringResource(R.string.agent_sessions_error, error!!),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    if (sessions.isEmpty()) {
                        item("empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.agent_sessions_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    items(items = sessions, key = { it.sessionId }) { s ->
                        SessionRow(
                            meta = s,
                            now = now,
                            onClick = { onOpenSession(s.sessionId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * 顶部「新建会话」大按钮 —— 对齐 WorkBuddy 抽屉里那颗最醒目的 pill 按钮。
 * 语义是服务端 `POST /api/agent/sessions`:立刻落一条空 transcript 并返回
 * sessionId,用户进详情页就能看到「新会话」而非等第一条消息才出现。
 */
@Composable
private fun NewSessionPill(busy: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.agent_sessions_new),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SessionRow(meta: AgentSessionMeta, now: Long, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.agent_session_untitled),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (meta.model.isNotBlank() && meta.model != "unknown") {
                        StatusChip(text = meta.model, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = formatRelativeAgoMs(meta.updatedAt, now),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // sessionId 用等宽小字打出来 —— 排障时能直接对上服务端日志。
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta.sessionId,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
