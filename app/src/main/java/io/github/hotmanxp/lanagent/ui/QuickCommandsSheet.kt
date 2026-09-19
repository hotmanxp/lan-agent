// ui/QuickCommandsSheet.kt — 快捷命令管理(增 / 改 / 删 / 排序)
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.model.QuickCommand
import java.util.UUID

/**
 * 快捷命令管理半屏。列表是全局的(所有主机共用一份),顺序即 chip 行里的
 * 显示顺序 —— 排序用上/下移而不是拖拽:条目可能十几条,而这套 UI 的
 * 交互成本必须低于"直接在终端里敲一遍命令",拖拽在手机上并不便宜。
 *
 * 每次改动整份写回([onSave]),不做增量更新 —— DataStore 的写入是原子的,
 * 整份替换让"排序 + 增删改"共用同一个数据路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickCommandsSheet(
    commands: List<QuickCommand>,
    onDismiss: () -> Unit,
    onSave: (List<QuickCommand>) -> Unit,
    onRun: (QuickCommand) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickCommand?>(null) }
    var deleting by remember { mutableStateOf<QuickCommand?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(
                text = stringResource(R.string.ssh_quick_manage_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.ssh_quick_manage_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (commands.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QuickCommandEmptyHint(onManage = { adding = true })
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = commands, key = { it.id }) { qc ->
                        val index = commands.indexOfFirst { it.id == qc.id }
                        QuickCommandRow(
                            command = qc,
                            canMoveUp = index > 0,
                            canMoveDown = index in 0 until commands.lastIndex,
                            onRun = { onRun(qc) },
                            onEdit = { editing = qc },
                            onMoveUp = {
                                if (index > 0) {
                                    val next = commands.toMutableList()
                                    next.add(index - 1, next.removeAt(index))
                                    onSave(next)
                                }
                            },
                            onMoveDown = {
                                if (index in 0 until commands.lastIndex) {
                                    val next = commands.toMutableList()
                                    next.add(index + 1, next.removeAt(index))
                                    onSave(next)
                                }
                            },
                            onDelete = { deleting = qc },
                        )
                    }
                }
            }

            Button(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.ssh_quick_add))
            }
        }
    }

    if (adding) {
        EditQuickCommandDialog(
            initial = null,
            onDismiss = { adding = false },
            onConfirm = { created ->
                onSave(commands + created)
                adding = false
            },
        )
    }

    editing?.let { current ->
        EditQuickCommandDialog(
            initial = current,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                onSave(commands.map { if (it.id == current.id) updated else it })
                editing = null
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ssh_quick_delete_title)) },
            text = { Text(target.label) },
            confirmButton = {
                TextButton(onClick = {
                    onSave(commands.filter { it.id != target.id })
                    deleting = null
                }) {
                    Text(
                        text = stringResource(R.string.ssh_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun QuickCommandRow(
    command: QuickCommand,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = command.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        if (command.confirm) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.padding(start = 6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.ssh_quick_confirm_badge),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = command.command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onRun, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.ssh_quick_run),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniIconAction(
                    icon = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.ssh_quick_move_up),
                    enabled = canMoveUp,
                    onClick = onMoveUp,
                )
                MiniIconAction(
                    icon = Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(R.string.ssh_quick_move_down),
                    enabled = canMoveDown,
                    onClick = onMoveDown,
                )
                MiniIconAction(
                    icon = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.ssh_action_edit),
                    enabled = true,
                    onClick = onEdit,
                )
                MiniIconAction(
                    icon = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.ssh_action_delete),
                    enabled = true,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** 32dp 的小动作钮 —— IconButton 会被 48dp 最小交互尺寸撑开(见 AGENTS)。 */
@Composable
private fun MiniIconAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 新增 / 编辑快捷命令。`confirm` 开关 = 执行前弹二次确认,给 `rm -rf` /
 * `reboot` 这类命令用:chip 行上误触一次的成本远低于手敲错一条命令。
 */
@Composable
internal fun EditQuickCommandDialog(
    initial: QuickCommand?,
    onDismiss: () -> Unit,
    onConfirm: (QuickCommand) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var confirm by remember { mutableStateOf(initial?.confirm ?: false) }

    val canSave = label.isNotBlank() && command.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.ssh_quick_dialog_add_title
                    else R.string.ssh_quick_dialog_edit_title
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.ssh_quick_field_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.ssh_quick_field_command)) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ssh_quick_field_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.ssh_quick_field_confirm_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = confirm, onCheckedChange = { confirm = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onConfirm(
                        QuickCommand(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            label = label.trim(),
                            command = command.trim(),
                            confirm = confirm,
                        )
                    )
                },
            ) { Text(stringResource(R.string.dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
