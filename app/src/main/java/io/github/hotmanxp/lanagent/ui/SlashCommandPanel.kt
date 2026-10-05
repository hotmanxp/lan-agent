// ui/SlashCommandPanel.kt — 输入框上方的「/命令」候选面板。
//
// 对齐 opencc-web 的命令下拉(AgentInputBox.tsx):用户敲 `/` 后立刻出候选、
// 打字符实时过滤、↑↓ 移动、Tab 补全、Enter 执行、Esc 关掉。差别在形态 ——
// 手机没有浮层空间给一个「贴着光标的 dropdown」,所以做成**输入卡上方的一张
// 内联卡**(跟附件条同一层):既不会被软键盘压住,也能和数据层保持同一份
// 状态(不需要在输入框和 popup 之间同步 index)。
//
// 面板只管「列出来 + 高亮 + 点击回调」;**过滤和排序在 data 层**
// (data/SlashCommands.kt 的 [filterSlashItems]),键盘事件在 AgentInputBar。
//
// 同文件末尾的 [SlashPickerSheet] 是**另一条触发路径**:输入条 `+` 按钮弹出的
// 浏览层(带搜索框),对齐 web 端 `+` 的 `QuickCommandPopover`。它不换行渲染器 ——
// 行还是下面这个 [SlashRow],清单还是 `filterSlashItems`,只有触发方式和外框不同。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.SlashItem
import io.github.hotmanxp.lanagent.data.filterSlashItems
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Terminal

/** 面板可见行数上限 —— 再多就滚动,别把消息区挤没了。 */
private const val VISIBLE_ROWS = 6

/** 单行高度(名字行 + 描述行 + 内边距)。 */
private val ROW_HEIGHT = 54.dp

/** 浏览弹层的列表高度上限 —— 搜索框 + 这个高度刚好占满一屏不顶到状态栏。 */
private val PICKER_MAX_HEIGHT = 420.dp

/**
 * 候选面板。
 *
 * @param items 已过滤/排序的候选(空 + [loading] = 首次拉清单中)。
 * @param selectedIndex 当前高亮行下标(由键盘导航驱动)。越界时不高亮。
 * @param loading 清单还没回来 —— 显示「加载中」而不是「无匹配」,两者对
 *   用户含义不同(一个等,一个改关键字)。
 * @param onPick 点击/回车选中。**与 Enter 同义**:是否补全待参数由调用方决定。
 */
@Composable
internal fun SlashCommandPanel(
    items: List<SlashItem>,
    selectedIndex: Int,
    loading: Boolean,
    onPick: (SlashItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, shape, clip = false),
    ) {
        when {
            items.isEmpty() -> EmptyRow(loading = loading)
            else -> {
                val listState = rememberLazyListState()
                // 键盘移动到**视野外**才滚动 —— 不能在视野内也滚:那样每按一次
                // ↓ 都把选中行顶到第一行(整列跟着跳),看着像列表在乱窜。
                // 往下越界时只推一行(保持选中行落在末行),往上越界时对齐到顶。
                LaunchedEffect(selectedIndex, items.size) {
                    if (selectedIndex !in items.indices) return@LaunchedEffect
                    val info = listState.layoutInfo.visibleItemsInfo
                    val first = listState.firstVisibleItemIndex
                    val last = info.lastOrNull()?.index ?: first
                    val rows = (last - first).coerceAtLeast(1)
                    when {
                        selectedIndex < first -> runCatching { listState.scrollToItem(selectedIndex) }
                        selectedIndex > last ->
                            runCatching { listState.scrollToItem((selectedIndex - rows).coerceAtLeast(0)) }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ROW_HEIGHT * VISIBLE_ROWS)
                        // 面板贴着输入卡,底部留 6dp 免得贴边
                        .padding(vertical = 6.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.name }) { index, item ->
                        SlashRow(
                            item = item,
                            selected = index == selectedIndex,
                            onClick = { onPick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashRow(
    item: SlashItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            // 选中底色与模型 picker 的「当前项」同一套(primaryContainer 半透明),
            // 整个 App 里「高亮 = 这块」只此一种表达。
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (item.isSkill) Lucide.Sparkles else Lucide.Terminal,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "/${item.label}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                item.argumentHintText().takeIf { it.isNotBlank() }?.let { hint ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = hint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        KindBadge(item)
    }
}

/** 右侧小徽标:命令 / 技能。插件项额外标出所属插件。 */
@Composable
private fun KindBadge(item: SlashItem) {
    val text = when {
        item.isSkill && item.pluginName != null -> stringResource(R.string.agent_slash_badge_skill_plugin, item.pluginName)
        item.isSkill -> stringResource(R.string.agent_slash_badge_skill)
        item.pluginName != null -> stringResource(R.string.agent_slash_badge_command_plugin, item.pluginName)
        item.isBuiltIn == true -> stringResource(R.string.agent_slash_badge_builtin)
        else -> stringResource(R.string.agent_slash_badge_command)
    }
    Text(
        text = text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyRow(loading: Boolean) {
    Text(
        text = stringResource(if (loading) R.string.agent_slash_loading else R.string.agent_slash_empty),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    )
}

/**
 * `+` 面板点「命令与技能」弹出的**浏览层**(对齐 web 端 `QuickCommandPopover`,
 * `AgentInputBox.tsx:1940`)。
 *
 * 与 [SlashCommandPanel] 的分工:那面板是「输入法实时补全」,只在用户敲 `/`
 * 的那一瞬间出现,还占着输入卡上方的空间;本弹层是「显式按钮触发」,可以停下来
 * 慢慢翻,自带搜索框。两条路径共享同一份 [SlashItem] 清单与同一套
 * `filterSlashItems`(命令段整体在前、skill 段在后),只是触发方式不同。
 *
 * **纯插入,不自动执行**:local 命令(/clear /compact /status)在浏览列表里
 * 误触一下就是清空整个会话,代价远大于多按一次发送键。插进输入框之后由用户
 * 点发送,执行走 `AgentSessionScreen.send()` 的 `parseSlashInput` 闸统一分流。
 *
 * 不传 `selectedIndex`:手机没有物理方向键,靠点选,不做一个永远没人挪的假高亮。
 */
@Composable
internal fun SlashPickerSheet(
    items: List<SlashItem>,
    loading: Boolean,
    onBack: () -> Unit,
    onPick: (SlashItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(items, query) { filterSlashItems(items, query) }
    // 打开就把焦点交给搜索框(web 弹层同款):清单常有一百多条,打比翻快。
    val searchRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchRequester.requestFocus() } }

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题行兼返回入口:单 sheet 两阶段,进了清单页得给一条看得见的退路
        // (系统返回键也接了,见调用点的 BackHandler)。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(start = 12.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Lucide.ArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.agent_slash_picker_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.agent_slash_picker_search),
                    fontSize = 14.sp,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Lucide.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            },
            textStyle = TextStyle(fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(searchRequester),
        )
        if (matches.isEmpty()) {
            EmptyRow(loading = loading)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PICKER_MAX_HEIGHT)
                    .padding(vertical = 6.dp),
            ) {
                itemsIndexed(matches, key = { _, item -> item.name }) { _, item ->
                    SlashRow(item = item, selected = false, onClick = { onPick(item) })
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}
