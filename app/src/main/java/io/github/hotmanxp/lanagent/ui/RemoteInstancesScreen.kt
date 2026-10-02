// ui/RemoteInstancesScreen.kt — 「服务 → 远程实例管理」
//
// 走 AA 官方 Cloud + shell.exec 通道,管理远端 zai 的实例:
//   列表来自 `DevicesApi.listDevices` + `ShellApi.listInstances`,
//   操作(start / restart / remove)走同一通道。
// 鉴权复用 AA 客户端已登录的 Bearer token,不需要新建账号体系。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.aa.api.RemoteDevice
import io.github.hotmanxp.lanagent.aa.api.RemoteInstance
import io.github.hotmanxp.lanagent.aa.feature.instances.RemoteInstancesUiState
import io.github.hotmanxp.lanagent.aa.feature.instances.RemoteInstancesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteInstancesScreen(
    onBack: () -> Unit,
    viewModel: RemoteInstancesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.service_aa_instances_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Lucide.RefreshCw, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is RemoteInstancesUiState.Loading -> CenteredMessage(padding, "正在加载…")
            is RemoteInstancesUiState.NotSignedIn -> CenteredMessage(
                padding,
                "需要先在「远程」栏登录 Agents Anywhere (AA).",
            )
            is RemoteInstancesUiState.Error -> CenteredMessage(padding, "失败: ${s.message}")
            is RemoteInstancesUiState.Loaded -> LoadedBody(
                padding = padding,
                state = s,
                onStart = viewModel::start,
                onRestart = viewModel::restart,
                onRemove = viewModel::remove,
            )
        }
    }
}

@Composable
private fun CenteredMessage(padding: PaddingValues, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LoadedBody(
    padding: PaddingValues,
    state: RemoteInstancesUiState.Loaded,
    onStart: (String) -> Unit,
    onRestart: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val device = state.devices.firstOrNull { it.id == state.selectedDeviceId }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("device") {
            DeviceHeader(device = device, count = state.instances.size)
        }
        items(state.instances, key = { it.id }) { instance ->
            InstanceRow(
                instance = instance,
                operating = state.operatingId == instance.id,
                onStart = { onStart(instance.id) },
                onRestart = { onRestart(instance.id) },
                onRemove = { onRemove(instance.id) },
            )
        }
    }
}

@Composable
private fun DeviceHeader(device: RemoteDevice?, count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = device?.name ?: "未选择设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = "${device?.id ?: "—"} · status=${device?.status ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$count 个实例",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstanceRow(
    instance: RemoteInstance,
    operating: Boolean,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = instance.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StateBadge(instance.state)
                }
                Text(
                    text = "port=${instance.port ?: "—"}  cwd=${instance.cwd}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = instance.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (operating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onStart, enabled = !instance.isRunning) {
                    Icon(Lucide.Play, contentDescription = "Start")
                }
                IconButton(onClick = onRestart) {
                    Icon(Lucide.RefreshCw, contentDescription = "Restart")
                }
                IconButton(onClick = onRemove) {
                    Icon(Lucide.Trash2, contentDescription = "Remove")
                }
            }
        }
    }
}

@Composable
private fun StateBadge(state: String) {
    val (label, color) = when (state) {
        "running" -> "running" to MaterialTheme.colorScheme.primary
        "stopped" -> "stopped" to MaterialTheme.colorScheme.outline
        else -> state to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun stringResource(id: Int): String =
    androidx.compose.ui.res.stringResource(id = id)