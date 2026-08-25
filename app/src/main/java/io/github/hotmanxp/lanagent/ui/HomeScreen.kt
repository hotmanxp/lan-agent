// ui/HomeScreen.kt — 卡片列表(支持运行时增删改+拖拽排序)
package io.github.hotmanxp.lanagent.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.cardsFlow
import io.github.hotmanxp.lanagent.data.findManagerBaseUrl
import io.github.hotmanxp.lanagent.data.saveCards
import io.github.hotmanxp.lanagent.model.Card
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCardClick: (Card) -> Unit,
    onScanClick: () -> Unit,
    onInstancesClick: (String) -> Unit,
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
                    }
                }
            )
        }
    ) { padding ->
        if (currentCards.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.empty_cards_hint))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(items = currentCards, key = { _, it -> it.id }) { index, card ->
                    DraggableCardItem(
                        card = card,
                        editMode = editMode,
                        listState = listState,
                        index = index,
                        totalCount = currentCards.size,
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
                        }
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dragged by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(if (dragged) 8.dp else 0.dp, label = "elevation")

    Card(
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
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.card_open_cd)
                )
            }
        }
    }
}