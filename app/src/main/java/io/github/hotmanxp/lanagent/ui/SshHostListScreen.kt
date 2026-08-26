// ui/SshHostListScreen.kt — SSH 主机列表 + 启动/停止 zai 半屏 sheet
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.saveSshHosts
import io.github.hotmanxp.lanagent.data.sshHostsFlow
import io.github.hotmanxp.lanagent.model.SshHost
import io.github.hotmanxp.lanagent.ssh.ExecResult
import io.github.hotmanxp.lanagent.ssh.ZaiLauncher
import io.github.hotmanxp.lanagent.ssh.managerUrlForReadyPort
import io.github.hotmanxp.lanagent.ssh.waitForZaiReadyAnyPort
import kotlinx.coroutines.launch

/**
 * Sheet state machine. Single var drives which sheet content shows.
 * Each `*Running` state has no result yet; `*Done` states have the exec
 * result. `LogView` is reached via "查看日志" button from StartDone.
 *
 * [StartDone.readyPort] is the port that responded to the multi-port
 * probe (null if zai exited non-zero OR nothing answered before the
 * probe budget ran out). The success URL is built from this port, not
 * the 9201 default — zai auto-scans if 9201 is taken.
 */
private sealed class SshSheet {
    data class StartRunning(val host: SshHost) : SshSheet()
    data class StartDone(
        val host: SshHost,
        val result: ExecResult,
        val readyPort: Int?,
        val log: String?,
    ) : SshSheet()
    data class StopRunning(val host: SshHost) : SshSheet()
    data class StopDone(val host: SshHost, val result: ExecResult) : SshSheet()
    data class LogView(val host: SshHost, val output: String) : SshSheet()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshHostListScreen(
    onBack: () -> Unit,
    onOpenWebview: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by context.sshHostsFlow().collectAsState(initial = emptyList())

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var deleteConfirm by remember { mutableStateOf<SshHost?>(null) }
    var sheet by remember { mutableStateOf<SshSheet?>(null) }

    fun persist(next: List<SshHost>) {
        scope.launch { context.saveSshHosts(next) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.webview_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.ssh_add_cd),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.ssh_empty_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = hosts, key = { it.id }) { host ->
                    SshHostRow(
                        host = host,
                        onStart = { sheet = SshSheet.StartRunning(host) },
                        onStop = { sheet = SshSheet.StopRunning(host) },
                        onEdit = { editing = host },
                        onDelete = { deleteConfirm = host },
                    )
                }
            }
        }
    }

    // Add dialog
    if (adding) {
        EditSshHostDialog(
            initial = null,
            onDismiss = { adding = false },
            onConfirm = { newHost ->
                persist(hosts + newHost)
                adding = false
            },
        )
    }

    // Edit dialog
    editing?.let { current ->
        EditSshHostDialog(
            initial = current,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                persist(hosts.map { if (it.id == current.id) updated else it })
                editing = null
            },
        )
    }

    // Delete confirm
    deleteConfirm?.let { host ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text(stringResource(R.string.ssh_action_delete)) },
            text = { Text("${host.name} (${host.user}@${host.host}:${host.port})") },
            confirmButton = {
                TextButton(onClick = {
                    persist(hosts.filter { it.id != host.id })
                    deleteConfirm = null
                }) {
                    Text(stringResource(R.string.ssh_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    // Action sheet (start / stop / log)
    sheet?.let { current ->
        SshActionSheet(
            sheet = current,
            onDismiss = { sheet = null },
            onStartDone = { done -> sheet = done },
            onStopDone = { done -> sheet = done },
            onOpenLog = { host, log ->
                sheet = SshSheet.LogView(host, log)
            },
            onOpenWebview = { url ->
                sheet = null
                onOpenWebview(url)
            },
        )
    }
}

@Composable
private fun SshHostRow(
    host: SshHost,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = host.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Text(
                text = "${host.user}@${host.host}:${host.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.ssh_action_start))
                }
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.ssh_action_stop),
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.ssh_action_edit),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.ssh_action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshActionSheet(
    sheet: SshSheet,
    onDismiss: () -> Unit,
    onStartDone: (SshSheet.StartDone) -> Unit,
    onStopDone: (SshSheet.StopDone) -> Unit,
    onOpenLog: (SshHost, String) -> Unit,
    onOpenWebview: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Drive SSH exec side effects when sheet enters Running state
    LaunchedEffect(sheet) {
        when (val s = sheet) {
            is SshSheet.StartRunning -> {
                val startCmd = ZaiLauncher.startCommand(s.host)
                val result = runCatching { ZaiLauncher.start(s.host) }
                    .getOrElse { t ->
                        // Surface SshException message in the output field
                        ExecResult(
                            command = startCmd,
                            output = "ERROR: ${t.message ?: t.javaClass.simpleName}",
                            exitCode = -1,
                            durationMs = 0,
                        )
                    }
                val readyPort: Int? = if (result.exitCode == 0) {
                    waitForZaiReadyAnyPort(host = s.host.host, preferredPort = s.host.zaiPort)
                } else null
                onStartDone(SshSheet.StartDone(s.host, result, readyPort, log = null))
            }
            is SshSheet.StopRunning -> {
                val result = runCatching { ZaiLauncher.stop(s.host) }
                    .getOrElse { t ->
                        ExecResult(
                            command = ZaiLauncher.STOP_COMMAND,
                            output = "ERROR: ${t.message ?: t.javaClass.simpleName}",
                            exitCode = -1,
                            durationMs = 0,
                        )
                    }
                onStopDone(SshSheet.StopDone(s.host, result))
            }
            else -> Unit
        }
    }

    // Auto-navigate to WebView once probe reports ready
    LaunchedEffect(sheet) {
        val s = sheet as? SshSheet.StartDone ?: return@LaunchedEffect
        val port = s.readyPort ?: return@LaunchedEffect
        // Brief delay so the user sees the "zai 已启动,正在跳转…" line
        kotlinx.coroutines.delay(800)
        onOpenWebview(managerUrlForReadyPort(s.host.host, port))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            when (val s = sheet) {
                is SshSheet.StartRunning -> {
                    Text(
                        stringResource(R.string.ssh_sheet_title_start),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    RunningLine(s.host)
                }
                is SshSheet.StartDone -> {
                    Text(
                        stringResource(R.string.ssh_sheet_title_start),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.ssh_sheet_exit_code,
                            s.result.exitCode,
                            s.result.durationMs,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = when {
                            s.readyPort != null -> stringResource(R.string.ssh_sheet_start_ok)
                            s.result.exitCode == 0 -> stringResource(R.string.ssh_sheet_start_fail)
                            else -> ""
                        },
                        color = if (s.readyPort != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (s.result.output.isNotBlank()) {
                        Text(
                            text = s.result.output,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                    if (s.readyPort == null && s.log == null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val logResult = runCatching { ZaiLauncher.tailLog(s.host) }
                                    val log = logResult.getOrNull()?.output
                                        ?: logResult.exceptionOrNull()?.message
                                        ?: "(log fetch failed)"
                                    onOpenLog(s.host, log)
                                }
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text(stringResource(R.string.ssh_sheet_view_log)) }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.ssh_sheet_dismiss)) }
                }
                is SshSheet.StopRunning -> {
                    Text(
                        stringResource(R.string.ssh_sheet_title_stop),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    RunningLine(s.host)
                }
                is SshSheet.StopDone -> {
                    Text(
                        stringResource(R.string.ssh_sheet_title_stop),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.ssh_sheet_exit_code,
                            s.result.exitCode,
                            s.result.durationMs,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (s.result.output.isNotBlank()) {
                        Text(
                            text = s.result.output,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.ssh_sheet_dismiss)) }
                }
                is SshSheet.LogView -> {
                    Text(
                        stringResource(R.string.ssh_log_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = s.output,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.ssh_sheet_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun RunningLine(host: SshHost) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(end = 12.dp),
            strokeWidth = 2.dp,
        )
        Column {
            Text(stringResource(R.string.ssh_sheet_running))
            Text(
                text = "${host.user}@${host.host}:${host.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}