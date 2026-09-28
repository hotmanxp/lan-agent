// ui/AaDevicesScreen.kt — AA 远程设备列表(0.24.0,路由 aa-devices)
//
// 「远程任务」栏的设备分区和设置栏的「管理设备」指向同一页。列的是 server
// 侧已认领的 connector(= 装了 Connector 的桌面设备),能看在线状态、撤销授权。
//
// 在线判定刻意宽松(见 `AaConnector.online`):只把 `offline` 当离线,其余(空 /
// 未来新增状态)一律当在线 —— 反过来「未知当离线」会让服务端加个新状态就集体
// 变灰,比误报在线更难排查。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AaConnector
import kotlinx.coroutines.launch

/** 在线绿 —— 与实例栏的 running 绿同源(`#52C41A`),全 App 一个「在线」色。 */
private val AaOnlineGreen = Color(0xFF52C41A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AaDevicesScreen(
    onBack: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    val rt = rememberAaRuntime()
    var devices by remember { mutableStateOf<List<AaConnector>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var revoking by remember { mutableStateOf<AaConnector?>(null) }

    fun refresh() {
        rt.scope.launch {
            loading = true
            error = null
            runCatching { rt.api.listAaConnectors() }
                .onSuccess { devices = it }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    LaunchedEffect(rt.baseUrl, rt.accessToken) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aa_devices_title)) },
                navigationIcon = { WbBackIcon(onBack) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.aa_refresh), fontSize = 13.sp)
                }
                OutlinedButton(onClick = onOpenPairing, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.aa_devices_add), fontSize = 13.sp)
                }
            }

            if (devices.isEmpty() && !loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = error ?: stringResource(R.string.aa_devices_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(devices, key = { it.id }) { conn ->
                        AaConnectorRow(connector = conn, onRevoke = { revoking = conn })
                    }
                }
            }
        }
    }

    // 撤销是不可逆的破坏性动作 —— 二次确认,并且说清后果(要重新配对)。
    revoking?.let { target ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.aa_devices_revoke)) },
            text = {
                Text(
                    stringResource(
                        R.string.aa_devices_revoke_confirm,
                        target.name ?: target.id,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rt.scope.launch {
                        runCatching { rt.api.revokeConnector(target.id) }
                            .onFailure { error = it.message }
                        revoking = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.aa_devices_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AaConnectorRow(connector: AaConnector, onRevoke: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态点:在线绿 / 离线灰。离线用灰不用红 —— 设备掉线是常态,不是错误。
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (connector.online) AaOnlineGreen else Color(0xFF9E9E9E)),
            )
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    text = connector.name?.takeIf { it.isNotBlank() } ?: connector.id,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                val meta = listOfNotNull(
                    stringResource(
                        if (connector.online) R.string.aa_devices_online else R.string.aa_devices_offline
                    ),
                    connector.deviceOs,
                ).joinToString(" · ")
                Text(text = meta, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRevoke) {
                Text(
                    text = stringResource(R.string.aa_devices_revoke),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
