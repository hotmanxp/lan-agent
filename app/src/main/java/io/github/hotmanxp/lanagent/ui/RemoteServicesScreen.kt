// ui/RemoteServicesScreen.kt — 底栏第 4 栏「远程服务」
//
// 这里放「不是 zai 实例、也没有 Agent 会话」的局域网服务:视频插帧控制台、
// 临时起的调试页、别的自建工具。和入口卡片的分工是**语义**上的:
//   卡片 → 可能有原生 Agent(右下角有「启动原生」按钮)
//   服务 → 只看不聊,点开就是 WebView
// 混在一起会让卡片的「启动原生」按钮在这些服务上必然失败。
//
// 状态点靠 [RemoteServiceProbe] 每 10 秒探一轮(N 个服务并发,单个 3 秒超时)。
// 判据是「端口有没有人应答」而不是「首页能不能打开」——见 probe 的注释。
//
// 服务清单本身是可增删的(DataStore),种子只有一条视频插帧控制台。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.RemoteServiceProbe
import io.github.hotmanxp.lanagent.data.remoteServicesFlow
import io.github.hotmanxp.lanagent.data.saveRemoteServices
import io.github.hotmanxp.lanagent.model.RemoteService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PROBE_INTERVAL_MS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteServicesScreen(
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val services by context.remoteServicesFlow().collectAsState(initial = null)

    var online by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RemoteService?>(null) }
    var deleteConfirm by remember { mutableStateOf<RemoteService?>(null) }

    val current = services ?: emptyList()
    val serviceIds = remember(current) { current.map { it.id to it.url } }

    fun persist(next: List<RemoteService>) {
        scope.launch { context.saveRemoteServices(next) }
    }

    // 探活轮询。key 用 id+url 列表:改名字/颜色不该重启探测,改地址才该。
    LaunchedEffect(serviceIds) {
        if (current.isEmpty()) {
            online = emptyMap()
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                online = coroutineScope {
                    current.map { svc ->
                        async { svc.id to RemoteServiceProbe.probe(svc) }
                    }.awaitAll().toMap()
                }
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.svc_title)) },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.svc_add_cd),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (current.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.wb_mascot),
                        contentDescription = null,
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.svc_empty_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = current, key = { it.id }) { svc ->
                    RemoteServiceRow(
                        service = svc,
                        // null = 还没探过(首帧),不显示「离线」误导用户。
                        isOnline = online[svc.id],
                        onOpen = { onOpenUrl(svc.url) },
                        onEdit = { editing = svc },
                        onDelete = { deleteConfirm = svc },
                    )
                }
            }
        }
    }

    if (adding) {
        EditRemoteServiceDialog(
            initial = null,
            onDismiss = { adding = false },
            onConfirm = { newSvc ->
                persist(current + newSvc)
                adding = false
            },
        )
    }

    editing?.let { target ->
        EditRemoteServiceDialog(
            initial = target,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                persist(current.map { if (it.id == target.id) updated else it })
                editing = null
            },
        )
    }

    deleteConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text(stringResource(R.string.svc_delete_title)) },
            text = { Text("${target.name} (${target.url})") },
            confirmButton = {
                TextButton(onClick = {
                    persist(current.filter { it.id != target.id })
                    deleteConfirm = null
                }) {
                    Text(
                        text = stringResource(R.string.svc_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun RemoteServiceRow(
    service: RemoteService,
    isOnline: Boolean?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 34.dp)
                        .background(color = Color(service.accent), RoundedCornerShape(2.dp))
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = service.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = service.subtitle.ifBlank { service.url },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OnlineBadge(isOnline)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpen) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.svc_action_open))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.svc_action_edit),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.svc_action_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * 在线 / 离线 / 未知 三态徽标。
 *
 * 在线用品牌绿(和 WorkBuddy 的运行态一致);离线用灰而不是红 —— 局域网服务
 * 没起来是常态,不是错误,红色会制造无谓的紧张感。
 */
@Composable
private fun OnlineBadge(isOnline: Boolean?) {
    val (textRes, color) = when (isOnline) {
        true -> R.string.svc_state_online to WbPalette.Green
        false -> R.string.svc_state_offline to MaterialTheme.colorScheme.onSurfaceVariant
        null -> R.string.svc_state_unknown to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = color, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(textRes),
            fontSize = 11.sp,
            color = color,
        )
    }
}
