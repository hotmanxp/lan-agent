// ui/RemoteInstancesScreen.kt — 「服务 → 远程实例管理」
//
// 走 AA 官方 Cloud + shell.exec 通道,管理远端 zai 的实例:
//   列表来自 `DevicesApi.listDevices` + `ShellApi.listInstances`,
//   操作(start / restart / remove)走同一通道。
// 鉴权复用 AA 客户端已登录的 Bearer token,不需要新建账号体系。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CirclePause
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.aa.api.RemoteDevice
import io.github.hotmanxp.lanagent.aa.api.RemoteInstance
import io.github.hotmanxp.lanagent.aa.feature.instances.RemoteInstancesUiState
import io.github.hotmanxp.lanagent.aa.feature.instances.RemoteInstancesViewModel
import io.github.hotmanxp.lanagent.data.InstanceState

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
                onStop = viewModel::stop,
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
    onStop: (String) -> Unit,
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
                onStop = { onStop(instance.id) },
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
    onStop: () -> Unit,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 状态图标要保住自然宽度,名字吃剩余空间并省略号截断。反过来
                        // 名字会贪婪吃满、把图标挤没了(见 pitfalls 的
                        // android-compose-row-flexible-element)。
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    StateIcon(instance.state)
                }
                Text(
                    text = "port=${instance.port ?: "—"}  cwd=${instance.cwd}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = instance.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (operating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                RowAction(
                    icon = Lucide.Play,
                    label = "启动",
                    enabled = !instance.isRunning,
                    onClick = onStart,
                )
                RowAction(
                    icon = Lucide.CircleStop,
                    label = "停止",
                    enabled = instance.isRunning,
                    onClick = onStop,
                )
                RowAction(
                    icon = Lucide.RefreshCw,
                    label = "重启",
                    enabled = true,
                    onClick = onRestart,
                )
                RowAction(
                    icon = Lucide.Trash2,
                    label = "删除",
                    enabled = true,
                    onClick = onRemove,
                )
            }
        }
    }
}

/**
 * 状态从文字徽标改成图标:徽标一颗就占 ~55dp,四个动作按钮并排时名字被截成
 * 「ope…」。16dp 图标把这块地还给了文本区。颜色走 [stateContent] —— 与
 * 实例栏同一套色板,不在这里另写死 hex(运行中 = 绿 #52C41A)。
 */
@Composable
private fun StateIcon(state: String) {
    // InstanceState 的枚举名与 wire 字符串同名,认得出就直接复用色板。
    val mapped = remember(state) { InstanceState.entries.firstOrNull { it.name == state } }
    val (icon, tint) = when (mapped) {
        InstanceState.running -> Lucide.CirclePlay to stateContent(InstanceState.running)
        InstanceState.starting, InstanceState.stopping ->
            Lucide.Activity to stateContent(mapped)
        InstanceState.stopped -> Lucide.CirclePause to stateContent(InstanceState.stopped)
        else -> Lucide.Circle to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = icon,
        contentDescription = state,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}

/**
 * 行内动作按钮压到 40dp:`IconButton` 内部锁死 `minimumInteractiveComponentSize`
 * (48dp),四个并排 = 192dp,文本区只剩 ~140dp。同 AGENTS.md §11 浮刷新按钮的
 * 做法,自己拼触点。contentDescription 走 Icon —— clickable 会和它合并成同一个
 * 无障碍节点。
 */
@Composable
private fun RowAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        LocalContentColor.current
    } else {
        LocalContentColor.current.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun stringResource(id: Int): String =
    androidx.compose.ui.res.stringResource(id = id)