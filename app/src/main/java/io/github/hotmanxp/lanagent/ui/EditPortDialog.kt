// ui/EditPortDialog.kt — 单实例「编辑启动端口」对话框(单行字段,与 web 端
// port-edit Modal 对标)。portEnabled=true 时 InputNumber 必填;否则发送 null
// 显式清除回 auto。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import io.github.hotmanxp.lanagent.data.InstanceSnapshot
import io.github.hotmanxp.lanagent.data.InstancesApi
import io.github.hotmanxp.lanagent.data.PatchValue
import kotlinx.coroutines.launch

@Composable
fun EditPortDialog(
    api: InstancesApi,
    inst: InstanceSnapshot,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val pinnedInitially = inst.startPort != null
    var portEnabled by remember { mutableStateOf(pinnedInitially) }
    var portText by remember { mutableStateOf(inst.startPort?.toString().orEmpty()) }
    var portErr by remember { mutableStateOf<Int?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val portNumber = portText.toIntOrNull()
    val canSubmit = !submitting && (!portEnabled || (portNumber != null && portNumber in 1024..65535))

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.instances_dialog_edit_port_title)) },
        text = {
            Column {
                Text(
                    text = "「${inst.name}」",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    val pErr: Int? = when {
                        !portEnabled -> null
                        portNumber == null || portNumber !in 1024..65535 ->
                            R.string.instances_dialog_error_port_range
                        else -> null
                    }
                    portErr = pErr
                    if (pErr != null) return@TextButton
                    submitting = true
                    scope.launch {
                        val res = runCatching {
                            api.patchInstance(
                                id = inst.id,
                                port = if (portEnabled) PatchValue.Set(portNumber!!) else PatchValue.Null,
                            )
                        }
                        submitting = false
                        if (res.isSuccess) {
                            onDismiss()
                        } else {
                            onError(res.exceptionOrNull()?.message ?: "patch failed")
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.instances_dialog_save_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!submitting) onDismiss() }) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}