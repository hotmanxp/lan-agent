// ui/EditSshHostDialog.kt — 添加 / 编辑 SSH 主机对话框
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.model.SshHost

/**
 * Mirrors EditCardDialog's pattern (initial: T?, onDismiss, onConfirm).
 * Password field is masked with PasswordVisualTransformation — typing into
 * it still works, just shows dots. SSH port + zai port are both number-typed
 * and validated to 1..65535 before the confirm button enables.
 *
 * `zaiPort` is the port `zai --lan` should bind to; default 9201 (zai's
 * canonical default). Set it explicitly when 9201 is already held by
 * another supervisor — zai CLI mode doesn't auto-scan.
 */
@Composable
fun EditSshHostDialog(
    initial: SshHost?,
    onDismiss: () -> Unit,
    onConfirm: (SshHost) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var portText by remember { mutableStateOf(initial?.port?.toString().orEmpty().ifBlank { "22" }) }
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var zaiPortText by remember {
        mutableStateOf(initial?.zaiPort?.toString().orEmpty().ifBlank { "9201" })
    }

    val port = portText.toIntOrNull()
    val portOk = port != null && port in 1..65535
    val zaiPort = zaiPortText.toIntOrNull()
    val zaiPortOk = zaiPort != null && zaiPort in 1..65535
    val canSave = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() && portOk && zaiPortOk

    val isEdit = initial != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEdit) R.string.ssh_dialog_edit_title else R.string.ssh_dialog_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_dialog_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.ssh_dialog_field_host)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.ssh_dialog_field_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = portText.isNotBlank() && !portOk,
                    supportingText = {
                        if (portText.isNotBlank() && !portOk) {
                            Text(stringResource(R.string.ssh_dialog_error_port_range))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = zaiPortText,
                    onValueChange = { zaiPortText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.ssh_dialog_field_zai_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = zaiPortText.isNotBlank() && !zaiPortOk,
                    supportingText = {
                        if (zaiPortText.isNotBlank() && !zaiPortOk) {
                            Text(stringResource(R.string.ssh_dialog_error_port_range))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.ssh_dialog_field_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ssh_dialog_field_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = initial?.id ?: java.util.UUID.randomUUID().toString()
                    onConfirm(
                        SshHost(
                            id = id,
                            name = name.trim(),
                            host = host.trim(),
                            port = port ?: 22,
                            user = user.trim(),
                            password = password,
                            zaiPort = zaiPort ?: 9201,
                        )
                    )
                },
                enabled = canSave
            ) { Text(stringResource(R.string.dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}