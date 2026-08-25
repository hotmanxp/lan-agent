// ui/CreateInstanceDialog.kt — 新建实例的对话框(name + cwd + LAN + 内核 + 启动端口)。
// 字段集合与 web 端 Instances.tsx 的 <Modal> 创建表单对标。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.hotmanxp.lanagent.data.InstanceKernel
import io.github.hotmanxp.lanagent.data.InstancesApi
import kotlinx.coroutines.launch

data class CreateInstanceInput(
    val name: String,
    val cwd: String,
    val lan: Boolean,
    val port: Int?,
    val kernel: InstanceKernel?,  // null = inherit global
)

@Composable
fun CreateInstanceDialog(
    api: InstancesApi,
    initialCwd: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (CreateInstanceInput) -> Result<Unit>,
    onError: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf(initialCwd) }
    var lan by remember { mutableStateOf(false) }
    var portEnabled by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("") }
    var kernel by remember { mutableStateOf<InstanceKernel?>(null) }
    var kernelMenu by remember { mutableStateOf(false) }
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
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.instances_dialog_field_kernel),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        Button(
                            onClick = { kernelMenu = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = kernel?.name ?: stringResource(R.string.instances_dialog_field_kernel_inherit),
                                fontSize = 13.sp,
                            )
                        }
                        DropdownMenu(
                            expanded = kernelMenu,
                            onDismissRequest = { kernelMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.instances_dialog_field_kernel_inherit), fontSize = 13.sp) },
                                onClick = { kernel = null; kernelMenu = false },
                            )
                            InstanceKernel.entries.forEach { k ->
                                DropdownMenuItem(
                                    text = { Text(k.name, fontSize = 13.sp) },
                                    onClick = { kernel = k; kernelMenu = false },
                                )
                            }
                        }
                    }
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
                                kernel = kernel,
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