// ui/SettingsScreen.kt — 底栏第 5 栏「设置」
//
// 四块,刻意保持薄:
//   1. 外观 —— 主题模式(跟随系统 / 浅色 / 深色)。写 DataStore,MainActivity
//      读出来喂给 LanAgentTheme,所以是**全局即时生效**,不需要重启。
//   2. 入口卡片(0.15.0 从任务栏搬过来)—— 增删改 / 拖拽排序 / 扫码 / 双按钮。
//      它仍是 `findManagerBaseUrl` 与「管理器不可达时的兜底实例目录」的数据源
//      (见 data/AgentInstances.kt),同时也是任意 URL 的快捷入口;任务栏不再
//      承载它,但能力一个没少。任务栏现在默认就是原生 Agent 页。
//   3. 数据 —— 各类配置的条数概览 + 「恢复默认入口卡片」这一个破坏性动作。
//   4. 关于 —— 版本号 / 包名,排障时截图能直接看出装的是哪一版。
//
// 不做的事:账号、同步、通知开关。lan-agent 是局域网工具,这些都没有。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.BuildConfig
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.ThemeMode
import io.github.hotmanxp.lanagent.data.cardsFlow
import io.github.hotmanxp.lanagent.data.compactToolsFlow
import io.github.hotmanxp.lanagent.data.remoteServicesFlow
import io.github.hotmanxp.lanagent.data.resetCards
import io.github.hotmanxp.lanagent.data.saveCompactTools
import io.github.hotmanxp.lanagent.data.saveThemeMode
import io.github.hotmanxp.lanagent.data.sshHostsFlow
import io.github.hotmanxp.lanagent.data.themeModeFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onScan: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpenSession: (baseUrl: String, instanceName: String, sid: String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by context.themeModeFlow().collectAsState(initial = ThemeMode.System)
    val compactTools by context.compactToolsFlow().collectAsState(initial = true)
    val cards by context.cardsFlow().collectAsState(initial = null)
    val hosts by context.sshHostsFlow().collectAsState(initial = emptyList())
    val services by context.remoteServicesFlow().collectAsState(initial = null)

    var confirmReset by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("appearance") {
                SettingsCard(
                    title = stringResource(R.string.settings_section_appearance),
                    subtitle = stringResource(R.string.settings_theme_subtitle),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        ThemeModeRow(
                            mode = mode,
                            selected = mode == themeMode,
                            onSelect = { scope.launch { context.saveThemeMode(mode) } },
                        )
                    }
                }
            }

            // 会话(0.15.2)—— 目前只有「工具调用精简模式」一项。放外观后面:
            // 两者都是「改了立刻见效」的显示偏好。
            item("session") {
                SettingsCard(title = stringResource(R.string.settings_section_session)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_compact_tools),
                        subtitle = stringResource(R.string.settings_compact_tools_sub),
                        checked = compactTools,
                        onCheckedChange = { on -> scope.launch { context.saveCompactTools(on) } },
                    )
                }
            }

            // 入口卡片(0.15.0 从任务栏搬来)。放在外观后面、数据前面 ——
            // 它是这一屏里唯一需要动手的东西,不该埋在最底下。
            item("cards") {
                CardListSection(
                    onScan = onScan,
                    onOpenUrl = onOpenUrl,
                    onOpenSession = onOpenSession,
                    snackbarHostState = snackbarHostState,
                )
            }

            item("data") {
                SettingsCard(title = stringResource(R.string.settings_section_data)) {
                    CountRow(
                        label = stringResource(R.string.settings_count_cards),
                        value = cards?.size,
                    )
                    CountRow(
                        label = stringResource(R.string.settings_count_hosts),
                        value = hosts.size,
                    )
                    CountRow(
                        label = stringResource(R.string.settings_count_services),
                        value = services?.size,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { confirmReset = true },
                        modifier = Modifier.padding(start = 0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_reset_cards),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            item("about") {
                SettingsCard(title = stringResource(R.string.settings_section_about)) {
                    CountRow(
                        label = stringResource(R.string.settings_about_version),
                        valueText = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                    CountRow(
                        label = stringResource(R.string.settings_about_package),
                        valueText = BuildConfig.APPLICATION_ID,
                        monospace = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_about_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_cards)) },
            text = { Text(stringResource(R.string.settings_reset_cards_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { context.resetCards() }
                    confirmReset = false
                }) {
                    Text(
                        text = stringResource(R.string.settings_reset_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** 分组卡片 —— WorkBuddy 设置页那种「白底圆角块 + 组标题」。 */
@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val label = stringResource(
        when (mode) {
            ThemeMode.System -> R.string.settings_theme_system
            ThemeMode.Light -> R.string.settings_theme_light
            ThemeMode.Dark -> R.string.settings_theme_dark
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** 一行「标题 + 说明 + 右侧 Switch」。整行可点(不用精确命中 Switch)。 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CountRow(
    label: String,
    value: Int? = null,
    valueText: String? = null,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText ?: value?.toString().orEmpty().ifBlank { "—" },
            fontSize = 12.sp,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
