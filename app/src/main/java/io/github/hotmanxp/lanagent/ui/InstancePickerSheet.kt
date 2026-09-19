// ui/InstancePickerSheet.kt — 「选择实例」底部弹层。
//
// 形态对着 WorkBuddy 手机端的「选择设备」弹层做的(用户给的参考图),只把
// 语义从「设备」换成「实例」—— 这是本 App 里第一次同时出现多个 Agent 宿主,
// 需要一个明确的切换入口。结构:
//
//   选择实例                                     ✕
//   ┌────────────────────────────────────────────┐
//   │ 🖥  opencc-web     在线                  ✓ │
//   ├────────────────────────────────────────────┤
//   │ 🖥  code           在线                    │
//   ├────────────────────────────────────────────┤
//   │ 🖥  user-fac       离线                    │
//   └────────────────────────────────────────────┘
//
// 三个刻意的选择:
//   1. **在线/离线是文案不是颜色块** —— 参考图里 tag 很轻(浅底 + 小字),
//      这里沿用同一档:在线用实例栏那套绿(#52C41A 系),离线用中性灰。
//      整行不加红/不加禁用态:离线实例仍然可点(用户可能就是想看看它为什么挂)。
//   2. **对勾只在当前实例上出现**,不用 RadioButton —— 切实例是「跳过去」而不是
//      「选中后确认」,点一下就该生效并关闭弹层。
//   3. 顺序沿用目录顺序(见 data/AgentInstances.kt 的注释:不做在线优先排序,
//      免得行位置随实例起落跳动)。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentInstance

/** 与实例栏 `stateContent` 的 running 绿同源(#52C41A),在线态全 App 一个绿。 */
private val OnlineGreen = Color(0xFF52C41A)

/** 在线 tag 的浅底(running 的 stateContainer 同色 #F6FFED)。 */
private val OnlineGreenBg = Color(0xFFF6FFED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstancePickerSheet(
    instances: List<AgentInstance>,
    /** 当前绑定的实例 baseUrl —— 它那行右侧打勾。 */
    currentBaseUrl: String?,
    loading: Boolean,
    onPick: (AgentInstance) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.agent_pick_instance_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // **必须限高 + 可滚**:实例多的时候(常见 8+ 个)整块会超出弹层
                // 可视高度,ModalBottomSheet 的内容默认不滚动 —— 不限高就是「列表被
                // 屏幕切掉一半,下面几个点不到」。360dp ≈ 6 行多一点。
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        loading && instances.isEmpty() -> PickerPlaceholder(
                            text = stringResource(R.string.agent_pick_instance_loading),
                            showSpinner = true,
                        )

                        instances.isEmpty() -> PickerPlaceholder(
                            text = stringResource(R.string.agent_pick_instance_empty),
                            showSpinner = false,
                            hint = stringResource(R.string.agent_pick_instance_empty_hint),
                        )

                        else -> instances.forEachIndexed { index, inst ->
                            InstancePickerRow(
                                instance = inst,
                                selected = inst.baseUrl == currentBaseUrl,
                                onClick = { onPick(inst) },
                            )
                            if (index != instances.lastIndex) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(start = 52.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerPlaceholder(text: String, showSpinner: Boolean, hint: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 1.8.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstancePickerRow(
    instance: AgentInstance,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            // 本机 supervisor 与子实例区分一下图标,扫一眼就知道点的是哪个。
            imageVector = if (instance.isCurrent) Icons.Rounded.Dns else Icons.Rounded.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = instance.name,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        OnlineTag(online = instance.online)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.agent_pick_instance_current),
                tint = OnlineGreen,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 「在线 / 离线」小 tag —— 浅底 + 小字,和参考图里那颗一样轻。 */
@Composable
private fun OnlineTag(online: Boolean) {
    val bg = if (online) OnlineGreenBg else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (online) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .background(color = bg, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(
                if (online) R.string.agent_instance_online else R.string.agent_instance_offline
            ),
            fontSize = 10.sp,
            color = fg,
        )
    }
}

/** 抽屉顶部那颗「当前实例」行 —— 点开本弹层。 */
@Composable
internal fun InstanceSwitcherRow(
    instance: AgentInstance?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instance?.name
                    ?: stringResource(R.string.agent_instance_none),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    loading -> stringResource(R.string.agent_pick_instance_loading)
                    instance == null -> stringResource(R.string.agent_instance_none_hint)
                    instance.online -> stringResource(R.string.agent_instance_online)
                    else -> stringResource(R.string.agent_instance_offline)
                },
                fontSize = 11.sp,
                color = if (instance?.online == true) OnlineGreen
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = stringResource(R.string.agent_pick_instance_title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
