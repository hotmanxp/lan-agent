// ui/TasksTabScreen.kt — 任务栏(底栏第 1 栏)
//
// 两段式布局:
//   1. **进行中** —— 跨实例聚合的活跃 Agent 会话(数据源 `data/ActiveTasks.kt`),
//      10 秒一轮。点条目直接进会话详情,不用先选实例再选会话。
//   2. **入口** —— 原有的入口卡片列表(扫码 / 增删改 / 拖拽排序 / 双按钮)。
//
// 为什么「进行中」不放首位之外的地方:这一栏叫「任务」,用户打开 App 的第一
// 疑问是「我那几个活儿跑完了吗」。所以状态在前、入口在后;卡片列表的编辑态
// (editMode)下反而**隐藏**进行中区 —— 编辑是「管理入口」的上下文,掺进任务
// 状态只会让人分不清哪块能拖。
//
// 原 HomeScreen 的顶栏四按钮(scan/instances/edit/add + ssh)在这里收敛成三个:
// 实例管理与 SSH 已升级为独立 tab,顶栏不该再有它们的影子入口。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.ActiveTask
import io.github.hotmanxp.lanagent.data.ActiveTasksCache
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.cardsFlow
import io.github.hotmanxp.lanagent.data.collectActiveTasks
import io.github.hotmanxp.lanagent.data.extractBaseUrl
import io.github.hotmanxp.lanagent.data.saveCards
import io.github.hotmanxp.lanagent.model.Card
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 进行中区最多露出的条目数 —— 再多就把「入口」挤到屏幕外了。 */
private const val MAX_ACTIVE_ROWS = 4

/** 聚合轮询间隔。比实例屏的 2.5s 慢:这里要扇出打 N 个实例,不能太狠。 */
private const val ACTIVE_POLL_MS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksTabScreen(
    onCardClick: (Card) -> Unit,
    onScanClick: () -> Unit,
    onOpenSession: (baseUrl: String, instanceName: String, sid: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val cards by context.cardsFlow().collectAsState(initial = null)
    val listState = rememberLazyListState()
    // Surface the persisted list — `null` until DataStore first emission.
    val currentCards = cards ?: emptyList()

    var editMode by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<Card?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var agentBusy by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 初值取进程内缓存 —— 从会话详情返回时 NavHost 已销毁本屏,没有缓存的话
    // 「进行中」区会空白到下一轮聚合回来(一个不可达实例就是 2.5 秒)。
    var activeTasks by remember { mutableStateOf(ActiveTasksCache.tasks) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // 跨实例聚合:卡片列表变了(增删改)才重启轮询。key 直接给 DataStore 的
    // List(结构相等),所以重新挂载时重复发射同一份数据不会白跑一轮。
    //
    // ⚠️ 必须区分「还没读到」(null)和「真的没有卡片」(emptyList):用
    // `currentCards` 当 key 的话,返回本屏的首帧总是 null→emptyList,会把
    // ActiveTasksCache 清掉 —— 缓存等于白做。这就是上一版返回后「进行中」
    // 区依然空白的原因。
    LaunchedEffect(cards) {
        val list = cards ?: return@LaunchedEffect
        if (list.isEmpty()) {
            activeTasks = emptyList()
            ActiveTasksCache.tasks = emptyList()
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                activeTasks = collectActiveTasks(list)
                delay(ACTIVE_POLL_MS)
            }
        }
    }
    // 相对时间定时器 —— 让「N 分钟前」自己走。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    /**
     * 「启动原生 Agent」统一入口 —— 直接调 `AgentApi.createSession()` 拿新 sid
     * 进详情页,绕开 supervisor 专属的 `/api/instances`(child 实例没有该端点,
     * 会 404 "instance management not available on child")。
     */
    val launchAgent: (String) -> Unit = launchAgent@{ baseUrl ->
        if (baseUrl in agentBusy) return@launchAgent
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

    val visibleTasks = activeTasks.take(MAX_ACTIVE_ROWS)
    // 进行中区占掉的 LazyColumn 条目数(分区头 1 + 任务行 N + 入口分区头 1)。
    // 拖拽排序靠 layoutInfo 的绝对 index 命中,所以卡片必须换算成列表下标 ——
    // 忘了这一步的典型症状:编辑态一拖,动的却是上面第 N 个任务行。
    val sectionOffset = if (!editMode && visibleTasks.isNotEmpty()) 1 + visibleTasks.size + 1 else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editMode) R.string.home_edit_mode_cd else R.string.tab_tasks
                        )
                    )
                },
                actions = {
                    if (editMode) {
                        IconButton(onClick = { editMode = false }) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.home_done_cd)
                            )
                        }
                    } else {
                        IconButton(onClick = onScanClick) {
                            Icon(
                                imageVector = Icons.Rounded.QrCodeScanner,
                                contentDescription = stringResource(R.string.home_scan_cd)
                            )
                        }
                        IconButton(onClick = { editMode = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.home_edit_mode_cd)
                            )
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.home_add_cd)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            currentCards.isEmpty() && cards == null -> Unit  // 首帧等 DataStore,不闪空态

            currentCards.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.wb_mascot),
                        contentDescription = null,
                        modifier = Modifier.width(132.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.empty_cards_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (!editMode && visibleTasks.isNotEmpty()) {
                    val runningCount = visibleTasks.count { it.running }
                    item("active-header") {
                        SectionHeader(
                            title = stringResource(R.string.tasks_active_section),
                            // 一个都没在跑时不写「0 个在跑」—— 那是噪声,不是信息。
                            trailing = if (runningCount > 0) {
                                stringResource(R.string.tasks_active_count, runningCount)
                            } else {
                                ""
                            },
                        )
                    }
                    items(items = visibleTasks, key = { it.baseUrl + it.meta.sessionId }) { task ->
                        ActiveTaskRow(
                            task = task,
                            now = now,
                            onClick = {
                                onOpenSession(
                                    task.baseUrl,
                                    task.instanceName,
                                    task.meta.sessionId,
                                )
                            },
                        )
                    }
                    item("entry-header") {
                        SectionHeader(
                            title = stringResource(R.string.tasks_entry_section),
                            trailing = stringResource(
                                R.string.tasks_entry_count,
                                currentCards.size,
                            ),
                        )
                    }
                }

                itemsIndexed(items = currentCards, key = { _, it -> it.id }) { index, card ->
                    val baseUrl = remember(card.url) { extractBaseUrl(card.url) }
                    DraggableCardItem(
                        card = card,
                        editMode = editMode,
                        listState = listState,
                        index = index,
                        listIndex = index + sectionOffset,
                        totalCount = currentCards.size,
                        hasNative = baseUrl != null,
                        onClick = {
                            if (editMode) editingCard = card else onCardClick(card)
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
                        onNativeClick = {
                            if (baseUrl == null) return@DraggableCardItem
                            launchAgent(baseUrl)
                        },
                        onWebClick = { onCardClick(card) },
                    )
                }
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

/** 分区标题 —— 左侧小字分组名,右侧计数。WorkBuddy 列表里的「分组行」观感。 */
@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 一条「进行中任务」。左侧状态点(运行中=品牌色实心,刚跑完=浅灰空心),
 * 中间标题 + 「实例 · 模型 · 时间」,右侧未完成任务数徽标。
 */
@Composable
private fun ActiveTaskRow(task: ActiveTask, now: Long, onClick: () -> Unit) {
    val dotColor = if (task.running) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color = dotColor, shape = RoundedCornerShape(4.dp))
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = task.meta.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.agent_session_untitled),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        task.instanceName.takeIf { it.isNotBlank() },
                        task.meta.model.takeIf { it.isNotBlank() && it != "unknown" },
                        formatRelativeAgoMs(task.meta.updatedAt, now),
                    ).joinToString(" · "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (task.activeTaskCount > 0) {
                Spacer(Modifier.width(8.dp))
                StatusChip(
                    text = stringResource(R.string.tasks_active_badge, task.activeTaskCount),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (task.running) {
                Spacer(Modifier.width(8.dp))
                StatusChip(
                    text = stringResource(R.string.tasks_running_badge),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DraggableCardItem(
    card: Card,
    editMode: Boolean,
    listState: LazyListState,
    /** 卡片在 `currentCards` 里的下标 —— 拖拽回调用它。 */
    index: Int,
    /** 同一张卡在 LazyColumn 里的绝对下标(= 卡片下标 + 进行中区占位)。 */
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
        // 非编辑模式 + URL 不合法 → 卡片整体不响应点击(改走 Web 等于「点了没反应」)。
        enabled = editMode || hasNative,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
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
                        // 换算回卡片下标再回调 —— 上层只认卡片坐标。
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
