// ui/EditRemoteServiceDialog.kt — 添加 / 编辑远程服务对话框
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.model.RemoteService

/** 色条候选。和 EditCardDialog 同一套观感(预置几个,不做取色盘)。 */
private val accentChoices = listOf(
    0xFF1677FF, 0xFF52C41A, 0xFF722ED1, 0xFFFA8C16, 0xFF13C2C2, 0xFFEB2F96,
).map { it.toInt() }

/**
 * 与 EditSshHostDialog 同款三段式(initial: T?, onDismiss, onConfirm)。
 *
 * URL 只做「含 scheme://」的轻校验 —— 真正的合法性由 `extractBaseUrl` 在
 * 探活/打开时判定,输入框里没必要逼用户理解 URL 语法。保存时如果没填 scheme,
 * 自动补 `http://`(局域网服务几乎不会跑 https,这个默认 99% 是对的)。
 */
@Composable
fun EditRemoteServiceDialog(
    initial: RemoteService?,
    onDismiss: () -> Unit,
    onConfirm: (RemoteService) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var subtitle by remember { mutableStateOf(initial?.subtitle.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var probePath by remember { mutableStateOf(initial?.probePath.orEmpty()) }
    var accent by remember { mutableIntStateOf(initial?.accent ?: accentChoices.first()) }

    val canSave = name.isNotBlank() && url.isNotBlank()
    val isEdit = initial != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isEdit) R.string.svc_dialog_edit_title else R.string.svc_dialog_add_title
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.svc_dialog_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.svc_dialog_field_url)) },
                    placeholder = { Text(stringResource(R.string.svc_dialog_field_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    label = { Text(stringResource(R.string.svc_dialog_field_subtitle)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = probePath,
                    onValueChange = { probePath = it },
                    label = { Text(stringResource(R.string.svc_dialog_field_probe)) },
                    placeholder = { Text("/") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = { Text(stringResource(R.string.svc_dialog_field_probe_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    text = stringResource(R.string.svc_dialog_field_accent),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    accentChoices.forEach { choice ->
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color(choice), RoundedCornerShape(13.dp))
                                .clickable { accent = choice },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (choice == accent) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .background(Color.White, RoundedCornerShape(5.dp))
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        RemoteService(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            subtitle = subtitle.trim(),
                            url = normalizeServiceUrl(url),
                            accent = accent,
                            probePath = probePath.trim(),
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

/** 缺 scheme 时补 `http://`;其余原样(去掉首尾空白)。 */
internal fun normalizeServiceUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    return if (trimmed.contains("://")) trimmed else "http://$trimmed"
}
