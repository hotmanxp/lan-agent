// ui/InstanceCard.kt — 单张实例卡片的 Compose 实现,与 web 端 Instances.tsx
// 的 `<Card>` 子树视觉对标。
//
// 视觉结构:
//   ┌─┃ 头部(name + 状态 tag + 当前 tag)              ┃─┐
//   │ ┃  LAN 开关 + 启动端口(同排)                    ┃ │
//   │ ┃ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ┃ │
//   │ ┃ 配置:内核 + 启动端口 + 运行端口(2 列)          ┃ │
//   │ ┃ 运行时:运行时长 + 最后心跳 + 错误(2 列)        ┃ │
//   │ ┃ 系统:工作目录(全宽) + PID/启动/创建(2 列)     ┃ │
//   │ ┃ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ┃ │
//   │ ┃ 动作:启动 / 停止 / 重启 / 删除 / 打开(图标)    ┃ │
//   └───────────────────────────────────────────────────┘
//   左侧 8dp 色条颜色与实例状态对齐(running=绿、stopped=灰、down=红 等)。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.InstanceRuntimeCore
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

/** 左侧色条用色 — 与 Ant Design state 色对齐的中等饱和度版本,确保深浅背景下都可识别。 */
internal fun stateAccent(state: InstanceState): Color = when (state) {
    InstanceState.stopped -> Color(0xFF8C8C8C)
    InstanceState.starting -> Color(0xFF1677FF)
    InstanceState.running -> Color(0xFF389E0D)
    InstanceState.stopping -> Color(0xFFD46B08)
    InstanceState.down -> Color(0xFFCF1322)
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
internal fun RuntimeCoreTag(runtimeCore: InstanceRuntimeCore?) {
    val (label, bg, fg) = when (runtimeCore) {
        InstanceRuntimeCore.default -> Triple(
            "default",
            Color(0xFFF0F0F0),
            Color(0xFF595959),
        )
        InstanceRuntimeCore.inproc -> Triple(
            "inproc",
            Color(0xFFE6F4FF),
            Color(0xFF1677FF),
        )
        InstanceRuntimeCore.spawn -> Triple(
            "spawn",
            Color(0xFFF9F0FF),
            Color(0xFF722ED1),
        )
        InstanceRuntimeCore.repl -> Triple(
            "repl",
            Color(0xFFFFFBE6),
            Color(0xFFD48806),
        )
        null -> Triple(
            stringResource(R.string.instances_field_runtime_core_inherit),
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左侧 8dp 状态色条 — 用饱和度更高的色,与卡片形成清晰对比
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .background(stateAccent(state)),
            )
            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                // ── 头部:name + tags ──
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
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (inst.isCurrent) CurrentTag()
                    StateTag(state)
                }

                // ── LAN + 启动端口 ──
                if (!inst.isCurrent) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = inst.lan == true,
                            onCheckedChange = onToggleLan,
                            enabled = !lanBusy,
                        )
                        Text(
                            text = stringResource(R.string.instances_lan),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (lanBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                        } else if (inst.lan == true) {
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
                        TextButton(
                            onClick = onEditPort,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.instances_action_edit_port),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── 信息网格 ──
                Spacer(Modifier.height(12.dp))
                InfoGrid(
                    cells = listOf(
                        InfoCellSpec(
                            icon = Icons.Filled.Bolt,
                            label = stringResource(R.string.instances_field_runtime_core),
                            value = { RuntimeCoreTag(inst.runtimeCore) },
                        ),
                        InfoCellSpec(
                            icon = Icons.Filled.Storage,
                            label = stringResource(R.string.instances_field_port),
                            value = { TextValue(inst.port?.toString() ?: "-") },
                        ),
                        InfoCellSpec(
                            icon = Icons.Filled.Schedule,
                            label = stringResource(R.string.instances_field_runtime),
                            value = {
                                TextValue(
                                    parseIsoMsOrNull(inst.startedAt)
                                        ?.let { formatRuntimeMs(now - it) }
                                        ?: "-"
                                )
                            },
                        ),
                        InfoCellSpec(
                            icon = Icons.Filled.Favorite,
                            label = stringResource(R.string.instances_field_last_heartbeat),
                            value = { TextValue(formatRelativeAgo(inst.lastHeartbeatAt, now)) },
                        ),
                        InfoCellSpec(
                            icon = Icons.Filled.Folder,
                            label = stringResource(R.string.instances_field_cwd),
                            value = { TextValue(inst.cwd, mono = true) },
                            wide = true,
                        ),
                        InfoCellSpec(
                            icon = Icons.Filled.Memory,
                            label = stringResource(R.string.instances_field_pid),
                            value = { TextValue(inst.pid?.toString() ?: "-") },
                        ),
                        InfoCellSpec(
                            icon = Icons.Filled.Schedule,
                            label = stringResource(R.string.instances_field_started_at),
                            value = { TextValue(formatTimestamp(inst.startedAt, now)) },
                        ),
                        InfoCellSpec(
                            icon = Icons.Filled.Schedule,
                            label = stringResource(R.string.instances_field_created_at),
                            value = { TextValue(formatTimestamp(inst.createdAt, now)) },
                        ),
                    ),
                )

                // ── 错误(条件渲染) ──
                if (inst.lastError != null) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFF1F0))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = inst.lastError.message,
                            color = Color(0xFFF5222D),
                            fontSize = 12.sp,
                        )
                    }
                }

                // ── 动作 ──
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ActionIconBtn(
                        icon = Icons.Filled.PlayArrow,
                        label = stringResource(R.string.instances_action_start),
                        enabled = canStart,
                        onClick = { onAction(Action.Start) },
                    )
                    ActionIconBtn(
                        icon = Icons.Filled.Stop,
                        label = stringResource(R.string.instances_action_stop),
                        enabled = canStop,
                        onClick = { onAction(Action.Stop) },
                    )
                    ActionIconBtn(
                        icon = Icons.Filled.Refresh,
                        label = stringResource(R.string.instances_action_restart),
                        enabled = canRestart,
                        onClick = { onAction(Action.Restart) },
                    )
                    ActionIconBtn(
                        icon = Icons.Filled.Delete,
                        label = stringResource(R.string.instances_action_delete),
                        enabled = canDelete,
                        destructive = true,
                        onClick = { onAction(Action.Delete) },
                    )
                    if (showOpen) {
                        ActionIconBtn(
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            label = stringResource(R.string.instances_action_open),
                            enabled = true,
                            onClick = { onAction(Action.Open) },
                        )
                    }
                    if (actionBusy) {
                        Spacer(modifier = Modifier.width(4.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
            }
        }
    }
}

enum class Action { Start, Stop, Restart, Delete, Open }

@Composable
private fun ActionIconBtn(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.heightIn(min = 56.dp),
    ) {
        if (destructive) {
            // 删除:实色 error 背景 + onError 前景 + 红色环,一眼识别危险动作
            val destroyContainer =
                if (enabled) MaterialTheme.colorScheme.error else Color.Transparent
            val destroyContent =
                if (enabled) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            val destroyBorder =
                if (enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outlineVariant
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(
                        width = 1.dp,
                        color = destroyBorder,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.IconButton(
                    onClick = onClick,
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = destroyContainer,
                        contentColor = destroyContent,
                    ),
                ) {
                    Icon(imageVector = icon, contentDescription = label)
                }
            }
        } else {
            // 普通动作:透明背景 + outline 边框,主色 icon 让"启动/打开"更醒目
            val borderColor =
                if (enabled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.outlineVariant
            val contentColor =
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            OutlinedIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(40.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    contentColor = contentColor,
                ),
            ) {
                Icon(imageVector = icon, contentDescription = label)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = if (enabled) {
                if (destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/** 单格规格 — `wide = true` 占整行,其余按 2 列等宽排列。 */
private data class InfoCellSpec(
    val icon: ImageVector,
    val label: String,
    val value: @Composable () -> Unit,
    val wide: Boolean = false,
)

/**
 * 信息网格:按 2 列等宽铺开,`wide = true` 的格子占整行。
 *
 * 顺序来自 `cells`:每 2 个一组 wide=false 的进同一行;遇到 wide=true 则单独占
 * 一行,并向后推进一个占位 slot 让下一对从左列重新开始。
 */
@Composable
private fun InfoGrid(cells: List<InfoCellSpec>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        var i = 0
        while (i < cells.size) {
            val cell = cells[i]
            if (cell.wide) {
                Row(modifier = Modifier.fillMaxWidth()) { InfoCellRow(cell) }
                i++
            } else {
                val next = cells.getOrNull(i + 1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) { InfoCellRow(cell) }
                    if (next != null && !next.wide) {
                        Box(modifier = Modifier.weight(1f)) { InfoCellRow(next) }
                        i += 2
                    } else {
                        // 右列空 — 仍占空间,让左列与上方对齐
                        Box(modifier = Modifier.weight(1f))
                        i++
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCellRow(spec: InfoCellSpec) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = spec.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = spec.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { spec.value() }
        }
    }
}

@Composable
private fun TextValue(text: String, mono: Boolean = false) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        maxLines = if (mono) 2 else 1,
        overflow = TextOverflow.Ellipsis,
    )
}
