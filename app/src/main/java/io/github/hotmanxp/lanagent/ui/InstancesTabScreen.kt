// ui/InstancesTabScreen.kt — 「服务」栏下「局域网实例管理」页的包装
//
// InstancesScreen 需要 baseUrl 才能拉 `/api/instances`,而这个 baseUrl 一直是从
// 入口卡片里认出来的(URL 以 `/instances` 结尾的那张,见 data/Cards.kt)。
// 以前是首页顶栏的 Storage 按钮 + 找不到就弹对话框;0.14.0 升成一级栏目,
// 0.24.0 又随「实例 / SSH」一起降级成「服务」栏下的一个路由 —— 那套
// 「没有管理器怎么办」的引导必须常驻在这一页,否则用户只会看到一片空白。
//
// 0.15.0:入口卡片本体搬到了设置栏 —— 所以引导按钮也指向设置,不是任务栏。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.cardsFlow
import io.github.hotmanxp.lanagent.data.findManagerBaseUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesTabScreen(
    /** 0.24.0 起这一页是「服务」栏下的路由,不再传 null —— 顶栏要有返回箭头。 */
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSessions: (instanceBaseUrl: String, instanceName: String) -> Unit,
    onGoSettings: () -> Unit,
) {
    val context = LocalContext.current
    val cards by context.cardsFlow().collectAsState(initial = null)
    val baseUrl = remember(cards) { cards?.let { findManagerBaseUrl(it) } }

    // 首帧等 DataStore;直接判空会闪一下「没配置管理器」的引导。
    if (cards == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.instances_title)) },
                    navigationIcon = { WbBackIcon(onBack) },
                )
            },
        ) { }
        return
    }

    if (baseUrl == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.instances_title)) },
                    navigationIcon = { WbBackIcon(onBack) },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.instances_no_manager_title),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.instances_no_manager_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = onGoSettings) {
                        Text(stringResource(R.string.instances_no_manager_action))
                    }
                }
            }
        }
        return
    }

    InstancesScreen(
        baseUrl = baseUrl,
        onBack = onBack,
        onOpenUrl = onOpenUrl,
        onOpenSessions = onOpenSessions,
    )
}
