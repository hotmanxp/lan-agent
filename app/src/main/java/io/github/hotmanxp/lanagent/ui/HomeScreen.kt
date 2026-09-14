// ui/HomeScreen.kt — 卡片列表(支持运行时增删改+拖拽排序)
//
// 0.10.5 起每张卡片右下挂「启动原生 Agent」+「打开网页」双按钮:
//   - 原生按钮:从卡片 url 抽 baseUrl,调 `${baseUrl}/api/instances` 找到当前
//     实例,跳 `agent-sessions/{baseUrl}/{name}`。URL 不合法/接口不可达时
//     两按钮都不显示,避免点了再弹 snackbar 噪声。
//   - Web 按钮:同卡片整体点击行为,直接 `webview/{url}`(保留原行为)。
//
// 编辑模式(editMode=true)下仍只显示删除 + 拖拽手柄,这两颗按钮不该出现 —
// 否则会把「编辑」和「打开」混淆。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.cardsFlow
import io.github.hotmanxp.lanagent.data.extractBaseUrl
import io.github.hotmanxp.lanagent.data.findManagerBaseUrl
import io.github.hotmanxp.lanagent.data.saveCards
import io.github.hotmanxp.lanagent.model.Card
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCardClick: (Card) -> Unit,
    onScanClick: () -> Unit,
    onInstancesClick: (String) -> Unit,
    onSshHostsClick: () -> Unit,
    onOpenNative: (baseUrl: String, instanceName: String, sid: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cards by context.cardsFlow().collectAsState(initial = null)
    val listState = rememberLazyListState()
    // Surface the persisted list — `null` until DataStore first emission.
    val currentCards = cards ?: emptyList()

    var editMode by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<Card?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showNoManagerHint by remember { mutableStateOf(false) }
    val managerBaseUrl = remember(currentCards) { findManagerBaseUrl(currentCards) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 防止同 baseUrl 的请求并发打两次(用户连点 / 同一端口多张卡)。
    // 0.10.6 起去掉了 nativeKnown 缓存 —— 旧路径要拉 /api/instances 拿实例名,
    // child 实例 404 才会失败;新路径直接 createSession,每次都拿全新 sid,
    // 缓存没意义。
    var agentBusy by remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * 「启动原生 Agent」统一入口(0.10.6 重写)—— 直接调
     * `AgentApi.createSession()` 拿新 sid 进详情页,绕开 supervisor
     * 专属的 `/api/instances` 端点(child 实例没有,会 404
     * "instance management not available on child")。
     *
     * 用 lambda + `remember` 持有是因为 Compose 要求函数在调用前声明,local
     * function 在源码顺序上也得在 itemsIndexed 之前;提到 Scaffold 之前
     * 是为了避免和 itemsIndexed 抢 scope 闭包时的可读性。
     */
    val launchAgent: (String) -> Unit = launchAgent@{ baseUrl ->
        if (baseUrl in agentBusy) return@launchAgent
        agentBusy = agentBusy + baseUrl
        scope.launch {
            try {
                // /api/agent/sessions 在所有 zai 实例(supervisor + child)都开放,
                // 不依赖 supervisor-only 的 /api/instances。
                val sid = AgentApi(baseUrl).createSession()
                // instanceName 用 baseUrl 的 host:port 部分作显示 —— 不打 API
                // 就拿不到 supervisor 视角的实例名,而 child 实例上 supervisor
                // API 又不可用,直接拿 host:port 既稳又能辨识(多张卡指向同一
                // 实例时副标题一致)。
                val instanceName = baseUrl.substringAfter("://").substringBefore('/')
                onOpenNative(baseUrl, instanceName, sid)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    if (editMode) {
                        IconButton(onClick = { editMode = false }) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.home_done_cd)
                            )
                        }
                    } else {
                        // Scan button comes first (left of edit/add) since
                        // it's the primary one-tap action; edit/add are
                        // card-management ops and live next to each other.
                        IconButton(onClick = onScanClick) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = stringResource(R.string.home_scan_cd)
                            )
                        }
                        IconButton(
                            onClick = {
                                val url = managerBaseUrl
                                if (url != null) onInstancesClick(url)
                                else showNoManagerHint = true
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = stringResource(R.string.instances_manage_cd),
                            )
                        }
                        IconButton(onClick = { editMode = true }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.home_edit_mode_cd)
                            )
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.home_add_cd)
                            )
                        }
                        // SSH hosts list — placed last (rightmost) so the
                        // primary card-management actions stay grouped
                        // together. The icon is Terminal (CLI / SSH
                        // keyboard metaphor); the SshHostListScreen has
                        // its own empty-state hint so no Snackbar here.
                        IconButton(onClick = onSshHostsClick) {
                            Icon(
                                imageVector = Icons.Filled.Memory,
                                contentDescription = stringResource(R.string.ssh_title),
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (currentCards.isEmpty()) {
            // 空态放机器人 + 一行提示 —— 跟会话页的空态同一套观感(WorkBuddy 风格)。
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.wb_mascot),
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
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(items = currentCards, key = { _, it -> it.id }) { index, card ->
                    val baseUrl = remember(card.url) { extractBaseUrl(card.url) }
                    DraggableCardItem(
                        card = card,
                        editMode = editMode,
                        listState = listState,
                        index = index,
                        totalCount = currentCards.size,
                        hasNative = baseUrl != null,
                        onClick = {
                            // 编辑模式点卡 = 弹编辑对话框;非编辑模式 = 走 Web(原行为)。
                            // URL 不合法的卡片在非编辑模式下整张卡不可点(改 onClick
                            // 逻辑被 hasNative 短路,见 DraggableCardItem 的 Card.onClick)。
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

    if (showNoManagerHint) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNoManagerHint = false },
            title = { Text(stringResource(R.string.instances_manage_cd)) },
            text = { Text(stringResource(R.string.instances_no_manager_hint)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showNoManagerHint = false }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
        )
    }
}

@Composable
private fun DraggableCardItem(
    card: Card,
    editMode: Boolean,
    listState: LazyListState,
    index: Int,
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

    Card(
        // 非编辑模式 + URL 不合法 → 卡片整体不响应点击(改走 Web 等于「点了没反应」)。
        // 否则维持原有 onClick(card 整体 = 编辑模式弹对话框;否则原 onCardClick)。
        // 用 `enabled = false` 比传空 lambda 干净 —— Compose Card.onClick 是必填参数,
        // 空 lambda 编译时会报 "Lambda type was inferred as Any" 的类型推断错。
        enabled = editMode || hasNative,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .zIndex(if (dragged) 1f else 0f)
            .graphicsLayer {
                if (dragged) shadowElevation = elevation.toPx()
            }
            .pointerInput(editMode, totalCount) {
                if (!editMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragged = true },
                    onDragEnd = { dragged = false },
                    onDragCancel = { dragged = false },
                    onDrag = { change, _ ->
                        change.consume()
                        val current = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == index }
                            ?: return@detectDragGesturesAfterLongPress
                        val center = current.offset + current.size / 2
                        val target = listState.layoutInfo.visibleItemsInfo
                            .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }
                            ?.index
                            ?: index
                        if (target != index) onMove(index, target)
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
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.home_delete_cd),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.home_drag_cd),
                    modifier = Modifier.padding(start = 4.dp)
                )
            } else {
                // 右下「启动原生 / 打开网页」双按钮(0.10.5)。两按钮都靠
                // `extractBaseUrl` 抽出的 baseUrl 决定是否显示 —— URL 不合法时整
                // 张卡片只有色条 + 标题副标题,不能点(避免点了再 snackbar 噪声)。
                if (hasNative) {
                    IconButton(onClick = onNativeClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = stringResource(R.string.home_card_native_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onWebClick) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = stringResource(R.string.home_card_web_cd),
                        )
                    }
                } else {
                    // 没合法 URL 时的占位 —— 让卡片仍然有「右端留白」避免标题
                    // 莫名贴右边。比原来直接挂一个箭头图标(误导用户以为可点)
                    // 更诚实。
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}