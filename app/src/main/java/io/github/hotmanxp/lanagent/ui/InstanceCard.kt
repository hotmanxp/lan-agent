// ui/InstanceCard.kt — 单张实例卡片的 Compose 实现,与 web 端 Instances.tsx
// 的 `<Card>` 子树视觉对标(name + 状态 Tag + LAN Switch + 内核/端口/cwd/...
// 描述列表 + 启动/停止/重启/删除/打开 动作行)。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.InstanceKernel
import io.github.hotmanxp.lanagent.data.InstanceSnapshot
import io.github.hotmanxp.lanagent.data.InstanceState

/** 与 web 端 STATE_TAG_COLOR 对齐(默认 / 蓝 / 绿 / 橙 / 红)。 */
internal fun stateContainer(state: InstanceState): Color = when (state) {
    InstanceState.stopped -> Color(0xFFEBEBEB)
    InstanceState.starting -> Color(0xFFE6F4FF)
    InstanceState.running -> Color(0xFFF6FFED)
    InstanceState.stopping -> Color(0xFFFFF7E6)
    InstanceState.down -> Color(0xFFFFE8E8)
}

internal fun stateContent(state: InstanceState): Color = when (state) {
    InstanceState.stopped -> Color(0xFF595959)
    InstanceState.starting -> Color(0xFF1677FF)
    InstanceState.running -> Color(0xFF52C41A)
    InstanceState.stopping -> Color(0xFFFA8C16)
    InstanceState.down -> Color(0xFFF5222D)
}

internal fun stateLabelRes(state: InstanceState): Int = when (state) {
    InstanceState.stopped -> R.string.instances_state_stopped
    InstanceState.starting -> R.string.instances_state_starting
    InstanceState.running -> R.string.instances_state_running
    InstanceState.stopping -> R.string.instances_state_stopping
    InstanceState.down -> R.string.instances_state_down
}

/**
 * 死透了(down + 超过 3 分钟)的实例 UI 上视作 stopped — 与 web Instances.tsx 的
 * effectiveState 同款语义。
 */
internal val STALE_THRESHOLD_MS = 3L * 60 * 1000
internal fun effectiveState(s: InstanceSnapshot, now: Long = System.currentTimeMillis()): InstanceState {
    if (s.state == InstanceState.down) {
        val last = parseIsoMsOrNull(s.lastHeartbeatAt) ?: return s.state
        if (now - last > STALE_THRESHOLD_MS) return InstanceState.stopped
    }
    return s.state
}

@Composable
internal fun StateTag(state: InstanceState) {
    val bg = stateContainer(state)
    val fg = stateContent(state)
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(stateLabelRes(state)),
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun KernelTag(kernel: InstanceKernel?) {
    val (label, bg, fg) = when (kernel) {
        InstanceKernel.opencc -> Triple(
            "opencc",
            Color(0xFFF0F0F0),
            Color(0xFF595959),
        )
        InstanceKernel.dsh -> Triple(
            "dsh",
            Color(0xFFF9F0FF),
            Color(0xFF722ED1),
        )
        null -> Triple(
            stringResource(R.string.instances_field_kernel_inherit),
            Color(0xFFF0F0F0),
            Color(0xFF595959),
        )
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun CurrentTag() {
    Box(
        modifier = Modifier
            .background(Color(0xFFE6F4FF), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.instances_current_tag),
            color = Color(0xFF1677FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun InstanceCard(
    inst: InstanceSnapshot,
    now: Long,
    lanBusy: Boolean,
    actionBusy: Boolean,
    onToggleLan: (Boolean) -> Unit,
    onAction: (Action) -> Unit,
    onEditPort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = effectiveState(inst, now)
    val canStart = !inst.isCurrent && (state == InstanceState.stopped || state == InstanceState.down)
    val canStop = !inst.isCurrent && (state == InstanceState.running || state == InstanceState.starting)
    val canRestart = !inst.isCurrent && state == InstanceState.running
    val canDelete = !inst.isCurrent
    val showOpen = inst.port != null && !inst.isCurrent

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: name + tags + LAN switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = inst.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (inst.isCurrent) CurrentTag()
                StateTag(state)
            }

            // LAN row (only for non-current)
            if (!inst.isCurrent) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.instances_lan),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = inst.lan == true,
                        onCheckedChange = onToggleLan,
                        enabled = !lanBusy,
                    )
                    if (lanBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                    if (inst.lan == true) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE6FFFB), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.instances_lan_tag),
                                color = Color(0xFF13C2C2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // Description list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DescRow(
                    label = stringResource(R.string.instances_field_kernel),
                    value = { KernelTag(inst.kernel) },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_start_port),
                    value = {
                        if (inst.startPort == null) {
                            Text(
                                text = stringResource(R.string.instances_field_auto),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = inst.startPort.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (!inst.isCurrent) {
                                    TextButton(
                                        onClick = onEditPort,
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.instances_action_edit_port),
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_port),
                    value = { TextValue(inst.port?.toString() ?: "-") },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_cwd),
                    value = { TextValue(inst.cwd, mono = true) },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_pid),
                    value = { TextValue(inst.pid?.toString() ?: "-") },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_started_at),
                    value = { TextValue(formatTimestamp(inst.startedAt, now)) },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_runtime),
                    value = {
                        TextValue(
                            parseIsoMsOrNull(inst.startedAt)
                                ?.let { formatRuntimeMs(now - it) }
                                ?: "-"
                        )
                    },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_created_at),
                    value = { TextValue(formatTimestamp(inst.createdAt, now)) },
                )
                DescRow(
                    label = stringResource(R.string.instances_field_last_heartbeat),
                    value = { TextValue(formatRelativeAgo(inst.lastHeartbeatAt, now)) },
                )
                if (inst.lastError != null) {
                    DescRow(
                        label = stringResource(R.string.instances_field_error),
                        value = {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFFFE8E8), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = inst.lastError.message,
                                    color = Color(0xFFF5222D),
                                    fontSize = 12.sp,
                                )
                            }
                        },
                    )
                }
            }

            // Actions
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ActionBtn(
                    label = stringResource(R.string.instances_action_start),
                    enabled = canStart,
                    onClick = { onAction(Action.Start) },
                )
                ActionBtn(
                    label = stringResource(R.string.instances_action_stop),
                    enabled = canStop,
                    onClick = { onAction(Action.Stop) },
                )
                ActionBtn(
                    label = stringResource(R.string.instances_action_restart),
                    enabled = canRestart,
                    onClick = { onAction(Action.Restart) },
                )
                ActionBtn(
                    label = stringResource(R.string.instances_action_delete),
                    enabled = canDelete,
                    destructive = true,
                    onClick = { onAction(Action.Delete) },
                )
                if (showOpen) {
                    ActionBtn(
                        label = stringResource(R.string.instances_action_open),
                        enabled = true,
                        onClick = { onAction(Action.Open) },
                    )
                }
                if (actionBusy) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
            }
        }
    }
}

enum class Action { Start, Stop, Restart, Delete, Open }

@Composable
private fun ActionBtn(
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DescRow(
    label: String,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.width(72.dp),
        )
        Box(modifier = Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun TextValue(text: String, mono: Boolean = false) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        maxLines = if (mono) 2 else 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}