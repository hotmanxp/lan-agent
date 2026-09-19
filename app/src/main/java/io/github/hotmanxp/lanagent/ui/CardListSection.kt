// ui/CardListSection.kt — 「入口卡片」列表(增删改 / 拖拽排序 / 双按钮)。
//
// 0.14.x 这段东西住在任务栏(HomeScreen → TasksTabScreen),顶栏挂着
// 扫码 / 编辑 / 添加三个按钮,列表项右侧是「启动原生 Agent + 打开网页」。
//
// **0.15.0 搬到了设置栏**:任务栏的默认落点改成原生 Agent 页之后,入口卡片
// 不再是第一屏的东西 —— 实例走「实例」栏 + 会话切换面板就够了。但卡片本身
// 还有两个不可替代的用途,所以不能直接删:
//
//   1. **兜底实例发现** —— `findManagerBaseUrl(cards)` 仍是「实例管理器在哪」
//      的唯一来源;管理器不可达时 `resolveAgentInstances()` 还会拿卡片 URL
//      逐个探活当实例目录(见 data/AgentInstances.kt)。Wi-Fi 换网、Mac IP 变了
//      需要改的就是这里。
//   2. **任意 URL 的快捷入口** —— 非 zai 的页面也从这进。
//
// 所以它降级成「低频管理面」放进设置,功能一个没少。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.cardsFlow
import io.github.hotmanxp.lanagent.data.extractBaseUrl
import io.github.hotmanxp.lanagent.data.saveCards
import io.github.hotmanxp.lanagent.model.Card
import kotlinx.coroutines.launch

/**
 * 卡片列表主体。**自包含**:自己读 DataStore、自己管编辑态、自己弹增改对话框,
 * 调用方只需要给三个导航动作 + 一个 snackbar host。
 */
@Composable
fun CardListSection(
    onScan: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSession: (baseUrl: String, instanceName: String, sid: String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cards by context.cardsFlow().collectAsState(initial = null)
    val listState = rememberLazyListState()
    val currentCards = cards ?: emptyList()

    var editMode by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<Card?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var agentBusy by remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * 「启动原生 Agent」—— 直接调 `AgentApi.createSession()` 拿新 sid 进详情页,
     * 绕开 supervisor 专属的 `/api/instances`(child 实例没有该端点,会 404)。
     */
    fun launchAgent(baseUrl: String) {
        if (baseUrl in agentBusy) return
        agentBusy = agentBusy + baseUrl
        scope.launch {
            try {
                val sid = AgentApi(baseUrl).createSession()
                val instanceName = baseUrl.substringAfter("://").substringBefore('/')
                onOpenSession(baseUrl, instanceName, sid)
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.home_card_native_failed,
                        t.message ?: t.toString(),
                    )
                )
            } finally {
                agentBusy = agentBusy - baseUrl
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 扫码 / 编辑 / 添加这三个按钮原来挂在 TasksTabScreen 的 TopAppBar 上,
        // 搬过来收成一行小区块头 —— 设置栏里再长一个 TopAppBar 就喧宾夺主了。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_card_section),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (editMode) {
                IconButton(onClick = { editMode = false }) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.home_done_cd),
                    )
                }
            } else {
                IconButton(onClick = onScan) {
                    Icon(
                        imageVector = Icons.Rounded.QrCodeScanner,
                        contentDescription = stringResource(R.string.home_scan_cd),
                    )
                }
                IconButton(onClick = { editMode = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.home_edit_mode_cd),
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.home_add_cd),
                    )
                }
            }
        }

        if (editMode) {
            Text(
                text = stringResource(R.string.settings_card_edit_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }

        // 首帧等 DataStore;直接判空会闪一下「暂未配置入口」。
        if (cards == null) return@Column

        if (currentCards.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.empty_cards_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            return@Column
        }

        // 不用 LazyColumn:它已经在外层设置栏的 LazyColumn 里,嵌套同向滚动会打架;
        // 卡片是个位数,直接铺开。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            currentCards.forEachIndexed { index, card ->
                val baseUrl = remember(card.url) { extractBaseUrl(card.url) }
                DraggableCardItem(
                    card = card,
                    editMode = editMode,
                    listState = listState,
                    index = index,
                    listIndex = index,
                    totalCount = currentCards.size,
                    hasNative = baseUrl != null,
                    onClick = {
                        if (editMode) editingCard = card else onOpenUrl(card.url)
                    },
                    onDelete = {
                        val next = currentCards.toMutableList().also { it.removeAt(index) }
                        scope.launch { context.saveCards(next) }
                    },
                    onMove = { from, to ->
                        if (from == to) return@DraggableCardItem
                        val next = currentCards.toMutableList().also {
                            val moved = it.removeAt(from)
                            it.add(to, moved)
                        }
                        scope.launch { context.saveCards(next) }
                    },
                    onNativeClick = { baseUrl?.let { launchAgent(it) } },
                    onWebClick = { onOpenUrl(card.url) },
                )
            }
        }
    }

    if (showAddDialog) {
        EditCardDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { newCard ->
                scope.launch { context.saveCards(currentCards + newCard) }
                showAddDialog = false
            }
        )
    }

    editingCard?.let { editing ->
        EditCardDialog(
            initial = editing,
            onDismiss = { editingCard = null },
            onConfirm = { updated ->
                val next = currentCards.map { if (it.id == editing.id) updated else it }
                scope.launch { context.saveCards(next) }
                editingCard = null
            }
        )
    }
}

/**
 * 一张可拖拽的入口卡片。**为什么同时收 `index` 和 `listIndex`**:拖拽命中靠
 * `layoutInfo` 的绝对下标,而回调要的是卡片下标 —— 两者在列表被包进别的容器后
 * 不一定相等(0.14.0 任务栏加「进行中」区时就踩过这个坑)。这里当前相等,
 * 仍保留两个参数,免得以后又被隐式耦合。
 */
@Composable
private fun DraggableCardItem(
    card: Card,
    editMode: Boolean,
    listState: LazyListState,
    index: Int,
    listIndex: Int,
    totalCount: Int,
    hasNative: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onNativeClick: () -> Unit,
    onWebClick: () -> Unit,
) {
    var dragged by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(if (dragged) 8.dp else 0.dp, label = "elevation")
    val offset = listIndex - index

    Card(
        // 非编辑模式 + URL 不合法 → 卡片整体不响应点击(点了没反应比弹错更克制)。
        enabled = editMode || hasNative,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragged) 1f else 0f)
            .graphicsLayer {
                if (dragged) shadowElevation = elevation.toPx()
            }
            .pointerInput(editMode, totalCount, listIndex) {
                if (!editMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragged = true },
                    onDragEnd = { dragged = false },
                    onDragCancel = { dragged = false },
                    onDrag = { change, _ ->
                        change.consume()
                        val current = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == listIndex }
                            ?: return@detectDragGesturesAfterLongPress
                        val center = current.offset + current.size / 2
                        val target = listState.layoutInfo.visibleItemsInfo
                            .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }
                            ?.index
                            ?: listIndex
                        val targetCard = target - offset
                        if (targetCard != index && targetCard in 0 until totalCount) {
                            onMove(index, targetCard)
                        }
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 40.dp)
                    .background(color = Color(card.accent), shape = RoundedCornerShape(2.dp))
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = card.subtitle.ifBlank { card.url },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (editMode) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.home_delete_cd),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.home_drag_cd),
                    modifier = Modifier.padding(start = 4.dp)
                )
            } else {
                if (hasNative) {
                    IconButton(onClick = onNativeClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Chat,
                            contentDescription = stringResource(R.string.home_card_native_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onWebClick) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = stringResource(R.string.home_card_web_cd),
                        )
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
