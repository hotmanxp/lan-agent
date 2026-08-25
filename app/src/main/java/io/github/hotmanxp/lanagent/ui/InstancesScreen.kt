// ui/InstancesScreen.kt — 原生「实例管理」屏,与 web 端 /instances 页面视觉对标。
//
// 数据来自 InstancesApi(baseUrl),由 InstancesApi.kt 提供;卡片渲染由
// InstanceCard.kt 提供;新建/编辑端口模态框分别在
// CreateInstanceDialog.kt / EditPortDialog.kt。
//
// 轮询策略:STARTED 时 2.5s 一刷(对齐 web 端 SSE + 60s runtime tick 的实时感),
// STARTED 之外停止(lifecycle.repeatOnLifecycle)。
package io.github.hotmanxp.lanagent.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.InstanceSnapshot
import io.github.hotmanxp.lanagent.data.InstancesApi
import io.github.hotmanxp.lanagent.data.PatchValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(
    baseUrl: String,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val api = remember(baseUrl) { InstancesApi(baseUrl) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var instances by remember { mutableStateOf<List<InstanceSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var createOpen by remember { mutableStateOf(false) }
    var portEdit by remember { mutableStateOf<InstanceSnapshot?>(null) }
    var deleteConfirm by remember { mutableStateOf<InstanceSnapshot?>(null) }
    val lanBusy = remember { mutableStateListOf<String>() }
    val actionBusy = remember { mutableStateListOf<String>() }

    suspend fun refresh() {
        try {
            val list = api.listInstances()
            instances = list
            error = null
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    // STARTED 时轮询 2.5s 一刷;STOPPED 自动停
    LaunchedEffect(api) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(2_500)
            }
        }
    }
    // 运行时长定时器 — 30s 一次,让「X 分 Y 秒」不卡在同一数字
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun toggleLan(inst: InstanceSnapshot, next: Boolean) {
        if (inst.id in lanBusy) return
        lanBusy += inst.id
        scope.launch {
            val res = runCatching {
                api.patchInstance(inst.id, lan = PatchValue.Set(next))
            }
            lanBusy -= inst.id
            if (res.isFailure) {
                snackbarHostState.showSnackbar(res.exceptionOrNull()?.message ?: "lan toggle failed")
            } else {
                refresh()
            }
        }
    }

    fun performAction(inst: InstanceSnapshot, action: Action) {
        if (action == Action.Open) {
            val port = inst.port
            if (port == null) {
                scope.launch { snackbarHostState.showSnackbar("实例无运行端口") }
                return
            }
            val host = Uri.parse(baseUrl).host ?: return
            onOpenUrl("http://$host:$port")
            return
        }
        if (inst.id in actionBusy) return
        actionBusy += inst.id
        scope.launch {
            val res = runCatching {
                when (action) {
                    Action.Start -> api.startInstance(inst.id)
                    Action.Stop -> api.stopInstance(inst.id)
                    Action.Restart -> api.restartInstance(inst.id)
                    Action.Delete -> {
                        api.deleteInstance(inst.id); null
                    }
                    Action.Open -> null  // unreachable: handled above
                }
            }
            actionBusy -= inst.id
            if (res.isFailure) {
                snackbarHostState.showSnackbar(res.exceptionOrNull()?.message ?: "operation failed")
            }
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.instances_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.webview_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.instances_refresh),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { createOpen = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.instances_create)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                instances.isEmpty() && loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                instances.isEmpty() && error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                instances.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.instances_empty_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items = instances, key = { it.id }) { inst ->
                            InstanceCard(
                                inst = inst,
                                now = now,
                                lanBusy = inst.id in lanBusy,
                                actionBusy = inst.id in actionBusy,
                                onToggleLan = { next -> toggleLan(inst, next) },
                                onAction = { action ->
                                    if (action == Action.Delete) {
                                        deleteConfirm = inst
                                    } else {
                                        performAction(inst, action)
                                    }
                                },
                                onEditPort = { portEdit = inst },
                            )
                        }
                        item("bottom-spacer") {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        val currentCwd = instances.firstOrNull { it.isCurrent }?.cwd.orEmpty()
        CreateInstanceDialog(
            api = api,
            initialCwd = currentCwd,
            onDismiss = { createOpen = false },
            onSubmit = { input ->
                runCatching {
                    api.createInstance(
                        name = input.name,
                        cwd = input.cwd,
                        lan = input.lan,
                        port = input.port,
                        kernel = input.kernel,
                    )
                    Unit
                }
            },
            onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
        )
    }

    portEdit?.let { inst ->
        EditPortDialog(
            api = api,
            inst = inst,
            onDismiss = { portEdit = null },
            onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
        )
    }

    deleteConfirm?.let { inst ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text(stringResource(R.string.instances_delete_confirm_title)) },
            text = { Text(stringResource(R.string.instances_delete_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = null
                    performAction(inst, Action.Delete)
                }) {
                    Text(stringResource(R.string.instances_action_delete), color = MaterialTheme.colorScheme.error)
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