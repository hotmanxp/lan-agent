// ui/CreateInstanceDialog.kt — 新建实例的对话框(name + 实例类型 + cwd + LAN + 启动端口)。
// 字段集合与 web 端 Instances.tsx 的 <Modal> 创建表单对标。
//
// 注意:`runtimeCore` 字段于 2026-09-12 opencc-web 阶段 3 删除后从本对话框移除
// (运行时只剩 `repl` 一种形态,不需要用户切换)。详见
// docs/superpowers/specs/2026-08-30-inproc-repl-extract-design.md §5.1。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.InstanceAppProfile
import io.github.hotmanxp.lanagent.data.InstancesApi
import kotlinx.coroutines.launch

data class CreateInstanceInput(
    val name: String,
    val cwd: String,
    val lan: Boolean,
    val port: Int?,
    val app: InstanceAppProfile? = null,    // 启动 profile(0.8.0 新增);null = 标准实例,
                                            // InstanceAppProfile.TaskFactory = 任务工厂
                                            // 实例(对齐 web 端 InstanceDefinition.app
                                            // = 'task-factory',spawn 时传 --app)。
)

@Composable
fun CreateInstanceDialog(
    api: InstancesApi,
    initialCwd: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (CreateInstanceInput) -> Result<Unit>,
    onError: (String) -> Unit,
    /** 0.8.0 新增:由 InstancesScreen 传入「新建任务工厂实例」快捷按钮的预选 app。null = 标准实例。 */
    initialApp: InstanceAppProfile? = null,
) {
    var name by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf(initialCwd) }
    // --lan 默认 true:lan-agent 是手机端 APP,必须通过 LAN IP 访问 zai 实例;
    // 若不勾,supervisor spawn 时 zai 绑 127.0.0.1,手机访问 ${host}:${port}/m 拒连
    // (参见 data/Cards.kt 的 host 取自 LAN IP)。用户可在 dialog 内取消勾选 —
    // 仅当用户**明确**通过 SSH 隧道 / 本机访问时才用得到。
    var lan by remember { mutableStateOf(true) }
    var portEnabled by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("") }
    // 实例类型 Radio:标准实例(null) / 任务工厂实例(InstanceAppProfile.TaskFactory)。
    // initialApp 由调用方传 — InstancesScreen 的「新建任务工厂实例」快捷按钮传 TaskFactory,
    // 「新建实例」主按钮传 null(标准)。变更后即写进 CreateInstanceInput.app,提交时透传。
    var app by remember { mutableStateOf<InstanceAppProfile?>(initialApp) }
    var pickerOpen by remember { mutableStateOf(false) }
    var nameErr by remember { mutableStateOf<Int?>(null) }
    var cwdErr by remember { mutableStateOf<Int?>(null) }
    var portErr by remember { mutableStateOf<Int?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val portNumber: Int? = portText.toIntOrNull()
    val canSubmit = name.isNotBlank() && cwd.isNotBlank() &&
        (!portEnabled || (portNumber != null && portNumber in 1024..65535)) &&
        !submitting

    if (pickerOpen) {
        DirectoryPickerDialog(
            api = api,
            initialPath = cwd,
            onDismiss = { pickerOpen = false },
            onSelect = { picked ->
                cwd = picked
                pickerOpen = false
            },
        )
    }

    fun validate(): Boolean {
        val nErr = if (name.isBlank()) R.string.instances_dialog_error_name_required else null
        val cErr = if (cwd.isBlank()) R.string.instances_dialog_error_cwd_required else null
        val pErr: Int? = when {
            !portEnabled -> null
            portNumber == null || portNumber !in 1024..65535 ->
                R.string.instances_dialog_error_port_range
            else -> null
        }
        nameErr = nErr
        cwdErr = cErr
        portErr = pErr
        return nErr == null && cErr == null && pErr == null
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.instances_dialog_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameErr = null },
                    label = { Text(stringResource(R.string.instances_dialog_field_name)) },
                    isError = nameErr != null,
                    supportingText = nameErr?.let { { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // ── 实例类型 Radio(0.8.0 新增,对齐 web 端 Instances.tsx app-radio) ──
                // 标准实例(app=null)与任务工厂实例(app=TaskFactory)二选一。
                // 没有「请选择」中间态 — 默认标准实例,Radio 默认选中第一项。
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        text = stringResource(R.string.instances_dialog_field_app),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppChoice(
                            selected = app == null,
                            label = stringResource(R.string.instances_app_standard),
                            onSelect = { app = null },
                        )
                        Spacer(Modifier.width(12.dp))
                        AppChoice(
                            selected = app == InstanceAppProfile.TaskFactory,
                            label = stringResource(R.string.instances_app_task_factory),
                            onSelect = { app = InstanceAppProfile.TaskFactory },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = cwd,
                        onValueChange = { cwd = it; cwdErr = null },
                        label = { Text(stringResource(R.string.instances_dialog_field_cwd)) },
                        placeholder = { Text(stringResource(R.string.instances_dialog_field_cwd_hint), fontSize = 12.sp) },
                        isError = cwdErr != null,
                        supportingText = cwdErr?.let { { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { pickerOpen = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.instances_dialog_field_browse), fontSize = 13.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = lan,
                        onCheckedChange = { lan = it },
                    )
                    Text(
                        text = stringResource(R.string.instances_dialog_field_lan),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.instances_dialog_field_port_mode),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = portEnabled,
                        onCheckedChange = { portEnabled = it; if (!it) portErr = null },
                    )
                    Text(
                        text = if (portEnabled) stringResource(R.string.instances_dialog_port_manual)
                               else stringResource(R.string.instances_dialog_port_auto),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (portEnabled) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit); portErr = null },
                        label = { Text(stringResource(R.string.instances_dialog_field_port)) },
                        isError = portErr != null,
                        supportingText = portErr?.let { { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    if (!validate()) return@TextButton
                    submitting = true
                    scope.launch {
                        val res = onSubmit(
                            CreateInstanceInput(
                                name = name.trim(),
                                cwd = cwd.trim(),
                                lan = lan,
                                port = if (portEnabled) portNumber else null,
                                app = app,
                            )
                        )
                        submitting = false
                        if (res.isSuccess) {
                            onDismiss()
                        } else {
                            onError(res.exceptionOrNull()?.message ?: "create failed")
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.instances_dialog_create_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!submitting) onDismiss() }) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/**
 * 实例类型 Radio 选项 — 对齐 web 端 Instances.tsx 的 `<Radio value="standard">`
 * / `<Radio value="task-factory">`。Material3 RadioButton 自身不带 label 排版,
 * 所以用 `Modifier.selectable` + 手动 `Row` 把 RadioButton + Text 拼成一个
 * 整组可点的 tap target(整个 Row 都响应点击,而不是只能点小圆圈)。
 */
@Composable
private fun AppChoice(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = androidx.compose.ui.semantics.Role.RadioButton,
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(4.dp))
        Text(text = label, fontSize = 13.sp)
    }
}