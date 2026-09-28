// ui/ServiceHubScreen.kt — 底栏「服务」栏的 hub 页
//
// 0.24.0 之前底栏把「实例」「SSH」各占一栏,「服务」栏只剩一条转码服务卡片,
// 五个格子里两个是同类东西(局域网运维入口),层级是平的、语义是散的。
// 现在收成四栏:「服务」变成一个 hub,内含三张卡片 —— 局域网实例管理 /
// 局域网 SSH 服务 / 转码服务,各自 push 到独立路由(`service/*`)。
//
// 卡片摘要都是**现成数据源的条数**,不额外发请求:实例走
// `resolveAgentInstances()`(永不抛异常,拿不到就是 0),SSH / 转码服务直接读
// DataStore 里的 JSON 列表长度。探活细节留给子页,hub 只回答「有没有、几个」。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Terminal
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentInstance
import io.github.hotmanxp.lanagent.data.remoteServicesFlow
import io.github.hotmanxp.lanagent.data.resolveAgentInstances
import io.github.hotmanxp.lanagent.data.sshHostsFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceHubScreen(
    onOpenInstances: () -> Unit,
    onOpenSsh: () -> Unit,
    onOpenRemoteServices: () -> Unit,
) {
    val context = LocalContext.current

    // 实例目录要走网络,不能同步算。首帧 null = 还在探,摘要位留空而不是闪 0。
    var instances by remember { mutableStateOf<List<AgentInstance>?>(null) }
    LaunchedEffect(Unit) {
        instances = context.resolveAgentInstances()
    }
    val hosts by context.sshHostsFlow().collectAsState(initial = emptyList())
    val services by context.remoteServicesFlow().collectAsState(initial = null)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_services)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("instances") {
                ServiceCard(
                    icon = Lucide.Server,
                    title = stringResource(R.string.service_instances_title),
                    subtitle = instances?.let { list ->
                        val online = list.count { it.online }
                        stringResource(R.string.service_instances_summary, list.size, online)
                    } ?: stringResource(R.string.service_loading),
                    onClick = onOpenInstances,
                )
            }
            item("ssh") {
                ServiceCard(
                    icon = Lucide.Terminal,
                    title = stringResource(R.string.service_ssh_title),
                    subtitle = stringResource(R.string.service_ssh_summary, hosts.size),
                    onClick = onOpenSsh,
                )
            }
            item("remote") {
                ServiceCard(
                    icon = Lucide.Network,
                    title = stringResource(R.string.service_remote_title),
                    // null = DataStore 首帧还没回来,同样先不写死 0。
                    subtitle = services?.let {
                        stringResource(R.string.service_remote_summary, it.size)
                    } ?: stringResource(R.string.service_loading),
                    onClick = onOpenRemoteServices,
                )
            }
        }
    }
}

/**
 * 一张服务入口卡。视觉与 `SettingsScreen` 的 SettingsCard 同源(同 Surface +
 * 同圆角 + 同描边),但多一个左侧图标和右侧 chevron —— 那是「点进去」的可供性信号。
 */
@Composable
private fun ServiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.padding(start = 14.dp).weight(1f),
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
