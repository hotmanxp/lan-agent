// ui/EditCardDialog.kt — 添加 / 编辑卡片对话框
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.model.Card

private val ACCENT_PALETTE = listOf(
    0xFF1677FF, 0xFF52C41A, 0xFF722ED1, 0xFFFA8C16, 0xFFEB2F96,
    0xFF13C2C2, 0xFFF5222D, 0xFF8C8C8C
).map { it.toInt() }

@Composable
fun EditCardDialog(
    initial: Card?,
    onDismiss: () -> Unit,
    onConfirm: (Card) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var subtitle by remember { mutableStateOf(initial?.subtitle.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var accent by remember { mutableStateOf(initial?.accent ?: ACCENT_PALETTE[0]) }
    val isEdit = initial != null
    val canSave = title.isNotBlank() && url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEdit) R.string.dialog_edit_card_title else R.string.dialog_add_card_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.dialog_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    label = { Text(stringResource(R.string.dialog_field_subtitle)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.dialog_field_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    text = stringResource(R.string.dialog_field_accent),
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCENT_PALETTE.forEach { colorInt ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(colorInt))
                                .let { base ->
                                    if (colorInt == accent) {
                                        base
                                    } else {
                                        base
                                    }
                                }
                                .padding(2.dp)
                        ) {
                            TextButton(
                                onClick = { accent = colorInt },
                                modifier = Modifier.size(28.dp)
                            ) { Text(if (colorInt == accent) "✓" else " ", color = Color.White) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = initial?.id ?: java.util.UUID.randomUUID().toString()
                    onConfirm(
                        Card(
                            id = id,
                            title = title.trim(),
                            subtitle = subtitle.trim(),
                            url = url.trim(),
                            accent = accent
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