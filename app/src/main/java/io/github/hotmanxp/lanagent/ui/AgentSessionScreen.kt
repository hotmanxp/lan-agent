// ui/AgentSessionScreen.kt — 原生「Agent 会话详情」屏(核心交付物)。
//
// 入口:AgentSessionsScreen 点某条会话 → agent-session/{baseUrl}/{sid}。
// 视觉参考 WorkBuddy 手机端对话页:
//   - 顶栏:返回 + 标题 + **可点的副标题**(`目录 · 模型 ›`)。WorkBuddy 把刷新 /
//     分享这些动作收进了副标题的详情面板里,顶栏只留「返回 + 标题 + 副标题」,
//     所以这里也一样 —— 顶栏 actions 只在**非空闲**时挂一个状态标签。
//   - 空态:大图标 + 主文案 + 副文案(不是一行小字)
//   - 底部:单胶囊输入条(见 AgentInputBar),含语音 / 图片 / 粘贴
//
// 数据流(顺序很重要):
//   1. `GET /api/agent/sessions/:id`  拉 transcript 历史 → store.hydrate()
//   2. `GET /api/agent/sessions/:id/state` 拉 cwd + v2Tasks → store.hydrateState()
//   3. `GET /api/event?sid=` 连 SSE → store.apply() 增量 reduce
//
// 为什么必须「先 hydrate 再连 SSE」:服务端在**首次连接**(不带
// Last-Event-ID)时会过滤掉 runtime.delta / thinking / tool_call / tool_result
// (eventBus.ts:100-108 的 STREAMING_REPLAY_EXCLUDE),避免与 transcript 重复。
// 反过来说,这些内容只能靠 hydrate 拿到;而连上之后的实时增量才走 SSE。
//
// 生命周期:整个 hydrate + SSE 都包在 repeatOnLifecycle(STARTED) 里 ——
// 切后台就断流省电,回前台重跑一遍 hydrate(自愈:断连期间丢的事件靠
// transcript 补回来)。
package io.github.hotmanxp.lanagent.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.AttachedImage
import io.github.hotmanxp.lanagent.data.ImageAttachments
import io.github.hotmanxp.lanagent.data.ModelEntry
import io.github.hotmanxp.lanagent.data.PatchSessionRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSessionScreen(
    baseUrl: String,
    sessionId: String,
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { AgentApi(baseUrl) }
    val store = remember(sessionId) { AgentSessionStore(sessionId) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<AttachedImage>>(emptyList()) }
    var actionBusy by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var approveFile by remember { mutableStateOf<String?>(null) }
    var approveFileLoading by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val infoSheetState = rememberModalBottomSheetState()

    // 模型选择 —— 状态独立于 store,因为 model 是「用户偏好」级别的字段,
    // 不需要随 transcript 一起 hydrate。`availableModels` 失败时降级
    // 为空列表(AgentInputBar chip 会显示锁头图标,不让用户点开空 picker)。
    var availableModels by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    var currentModel by remember { mutableStateOf<ModelEntry?>(null) }

    // hydrate + SSE。两个阶段串行(理由见文件头注释),整体随 STARTED 起停。
    LaunchedEffect(api, sessionId, refreshTick) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                store.hydrate(api.readTranscript(sessionId))
                // state 是可选增强(cwd / v2Tasks),拿不到不影响对话
                runCatching { store.hydrateState(api.readState(sessionId)) }
            } catch (t: Throwable) {
                store.hydrateFailed(t.message ?: t.toString())
            }
            // 模型清单独立于 transcript,失败降级空列表(见 AgentApi.listAvailableModels)
            availableModels = api.listAvailableModels()
            approveFile = null
            api.eventStream(sessionId).collect { store.apply(it) }
        }
    }

    // transcript 完成后用 store.model 反查 currentModel —— store.model 是
    // transcript meta 里的字面量字符串,要在 availableModels 里找匹配的
    // ModelEntry 才能正确显示 label/alias。找不到 → null(chip 显示 ?)。
    LaunchedEffect(store.model, availableModels) {
        val storedModel = store.model
        if (storedModel.isNullOrBlank() || storedModel == "unknown") {
            currentModel = null
            return@LaunchedEffect
        }
        currentModel = availableModels.firstOrNull { it.model == storedModel }
            ?: ModelEntry(model = storedModel)
    }

    val itemCount = store.items.size
    // reverseLayout=true 时 index 0 在**底部**,所以「贴底」等价于
    // firstVisibleItemIndex 很小。用户往上翻超过 3 屏就不再抢滚动位置。
    LaunchedEffect(itemCount) {
        if (itemCount > 0 && listState.firstVisibleItemIndex <= 3) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    val busy = store.status == AgentRunStatus.Streaming || store.status == AgentRunStatus.Retrying

    fun toast(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val images = attachments
        input = ""
        attachments = emptyList()
        // 本地乐观追加:SSE 事件面里没有「用户发了消息」这类事件
        // (runtime.* 全是助手侧),不本地追加就得等下一次 re-hydrate 才看得到。
        store.appendLocalUser(text, images.size)
        scope.launch {
            runCatching { api.sendPrompt(sessionId, text, images) }
                .onFailure { toast("发送失败:${it.message ?: it}") }
        }
    }

    fun stop() {
        scope.launch {
            runCatching { api.abort(sessionId) }
                .onFailure { toast("中断失败:${it.message ?: it}") }
        }
    }

    /** 读系统剪贴板拼到输入框尾部。 */
    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString()
        } else {
            null
        }
        if (text.isNullOrBlank()) {
            toast("剪贴板是空的")
        } else {
            // 已有内容时另起一行，不然粘上去会和原文本黏成一个词
            input = if (input.isBlank()) text else "${input.trimEnd()}\n$text"
        }
    }

    // Photo Picker：Android 13+ 走系统选择器（不需要任何存储权限），
    // 低版本自动回落到 ACTION_OPEN_DOCUMENT，同样不需要权限。
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (attachments.size >= ImageAttachments.MAX_COUNT) {
            toast("最多只能带 ${ImageAttachments.MAX_COUNT} 张图片")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching { ImageAttachments.load(context, uri) }
                .onSuccess { attachments = attachments + it }
                .onFailure { toast("读取图片失败:${it.message ?: it}") }
        }
    }

    // 语音输入：走系统 SpeechRecognizer，识别结果回填到输入框。
    // onMessage 里的 toast 会在识别服务的异步回调里被调到，所以走 toast()
    // 而不是直接改 state。
    val voice = rememberVoiceInput(
        onMessage = { toast(it) },
        onText = { input = it },
    )

    /** 一次性动作统一加忙锁 + 失败弹 snackbar,避免连点重复提交。 */
    fun guarded(block: suspend () -> Unit) {
        if (actionBusy) return
        actionBusy = true
        scope.launch {
            runCatching { block() }
                .onFailure { toast(it.message ?: it.toString()) }
            actionBusy = false
        }
    }

    /**
     * 回应一个待处理交互(ask / permission / approve)。
     *
     * **无论成败都要 `clearPending()`** —— 服务端对过期请求回 404
     * (`no_pending_review` / `no_pending_permission`),如果只在成功时清卡片,
     * 用户会被一张永远点不掉的卡片卡住(SSE replay 也可能带出旧请求)。
     * 所以这里内嵌一层 runCatching 把失败就地消化成 snackbar。
     */
    fun respondPending(block: suspend () -> Unit) {
        guarded {
            runCatching { block() }
                .onFailure { toast(it.message ?: it.toString()) }
            store.clearPending()
        }
    }

    fun queueAction(failPrefix: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onFailure { toast("$failPrefix:${it.message ?: it}") }
        }
    }

    // 副标题对齐 WorkBuddy 的「实例 | 目录」形态：目录名 + 模型。
    val subtitle = buildString {
        store.cwd?.takeIf { it.isNotBlank() }?.let { append(it.substringAfterLast('/')) }
        store.model?.takeIf { it.isNotBlank() && it != "unknown" }?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
    }.ifEmpty { stringResource(R.string.agent_session_subtitle_fallback) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.webview_back_cd),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = store.title?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.agent_session_untitled),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 可点的副标题：刷新 / 在浏览器打开 / 全部会话元信息都
                        // 收进这个面板，顶栏才干净得下来。
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showInfo = true }
                                .padding(vertical = 2.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = subtitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.agent_session_info_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                },
                actions = {
                    // 空闲是常态，常态不该占位置；只在真的在跑/出错时才提示。
                    if (store.status != AgentRunStatus.Idle) {
                        StatusBadge(store.status)
                        Spacer(Modifier.width(8.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    store.items.isEmpty() && !store.hydrated -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    store.items.isEmpty() -> AgentSessionEmptyState()

                    else -> LazyColumn(
                        state = listState,
                        // 倒序布局:index 0 贴底,流式追加时视口自动跟住新内容,
                        // 不需要每帧手动算滚动偏移。
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            count = store.items.size,
                            key = { i -> store.items[store.items.size - 1 - i].key },
                        ) { i ->
                            AgentItemView(store.items[store.items.size - 1 - i])
                        }
                    }
                }
            }

            // 底部固定区:任务清单 / 队列 / 待处理交互。整体限高 + 可滚,
            // 避免 ask 卡片选项多时把输入条挤出屏幕。
            val pending = store.pending
            if (store.v2Tasks.isNotEmpty() || store.queue.isNotEmpty() || pending != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    V2TaskStrip(store.v2Tasks)
                    QueueStrip(
                        queue = store.queue,
                        onCancel = { q ->
                            queueAction("取消失败") { api.cancelQueued(sessionId, q.id) }
                        },
                        onSteer = { q ->
                            queueAction("插入失败") { api.steerQueued(sessionId, q.id) }
                        },
                    )
                    if (pending != null) {
                        PendingCard(
                            pending = pending,
                            busy = actionBusy,
                            fileContent = approveFile,
                            fileLoading = approveFileLoading,
                            onLoadFile = {
                                if (!approveFileLoading) {
                                    approveFileLoading = true
                                    scope.launch {
                                        runCatching { api.readApproveFile(sessionId, pending.toolUseId) }
                                            .onSuccess { approveFile = it.content }
                                            .onFailure { toast("读取文件失败:${it.message ?: it}") }
                                        approveFileLoading = false
                                    }
                                }
                            },
                            onSubmitAsk = { answers ->
                                respondPending {
                                    api.submitAnswer(sessionId, pending.toolUseId, answers)
                                }
                            },
                            onReject = {
                                respondPending {
                                    if (pending.kind == "ask") {
                                        api.rejectAsk(sessionId, pending.toolUseId)
                                    } else {
                                        // 服务端 schema 要求 rejected 必须带非空 comment
                                        api.respondApprove(sessionId, pending.toolUseId, false, "手机端驳回")
                                    }
                                }
                            },
                            onPermission = { allow ->
                                respondPending {
                                    api.respondPermission(sessionId, pending.toolUseId, allow)
                                }
                            },
                            onApprove = { ok ->
                                respondPending {
                                    api.respondApprove(
                                        sessionId = sessionId,
                                        toolUseId = pending.toolUseId,
                                        approved = ok,
                                        comment = if (ok) null else "手机端驳回",
                                    )
                                }
                            },
                        )
                    }
                }
            }

            AgentInputBar(
                value = input,
                onValueChange = { input = it },
                busy = busy,
                attachments = attachments,
                onRemoveAttachment = { img -> attachments = attachments - img },
                voice = voice,
                canSend = input.isNotBlank() || attachments.isNotEmpty(),
                onSend = {
                    // 录音中的话直接丢弃（discard 而不是 stop）—— stop 之后识别
                    // 服务仍会异步回调结果，会把刚发出去的话重新填回已清空的
                    // 输入框，看着像「发出去的话又回来了」。
                    voice.discard()
                    send()
                },
                onStop = { stop() },
                onPickImage = {
                    if (attachments.size >= ImageAttachments.MAX_COUNT) {
                        toast("最多只能带 ${ImageAttachments.MAX_COUNT} 张图片")
                    } else {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
                onPaste = { pasteFromClipboard() },
                currentModel = currentModel,
                availableModels = availableModels,
                onModelChange = { picked ->
                    // 乐观切换:UI 立刻跟手,服务端失败再 toast + 回滚。
                    // store.model 是 private set,不在这里写回 ——
                    // 成功后下次 refreshTick++ 重新 hydrate,transcript
                    // meta 会带回服务端的权威 model;失败则保持原状。
                    val previous = currentModel
                    currentModel = picked
                    scope.launch {
                        runCatching {
                            api.patchSession(
                                sessionId = sessionId,
                                body = PatchSessionRequest(
                                    model = picked.model,
                                    providerId = picked.providerId,
                                ),
                            )
                        }
                            .onSuccess {
                                toast(context.getString(R.string.agent_input_model_switched, picked.alias))
                            }
                            .onFailure { err ->
                                currentModel = previous
                                toast(
                                    context.getString(
                                        R.string.agent_input_model_switch_failed,
                                        err.message ?: err.toString(),
                                    )
                                )
                            }
                    }
                },
            )
        }
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = infoSheetState,
        ) {
            SessionInfoSheet(
                sessionId = sessionId,
                baseUrl = baseUrl,
                store = store,
                onCopy = { toast("已复制会话 ID") },
                onRefresh = {
                    refreshTick++
                    showInfo = false
                },
                onOpenWeb = {
                    showInfo = false
                    onOpenWeb("$baseUrl/m?sid=$sessionId")
                },
            )
        }
    }
}

/** 空会话的占位（大图标 + 主文案 + 副文案，对齐 WorkBuddy 的空态观感）。 */
@Composable
private fun AgentSessionEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.agent_session_empty_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.agent_session_empty),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 副标题点开的会话信息面板。刷新 / 在浏览器打开这两个动作原本挂在顶栏
 * actions 上，收进这里是为了让顶栏跟 WorkBuddy 一样只剩「返回 + 标题 + 副标题」。
 */
@Composable
private fun SessionInfoSheet(
    sessionId: String,
    baseUrl: String,
    store: AgentSessionStore,
    onCopy: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.agent_session_info_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        InfoRow(stringResource(R.string.agent_session_info_status), statusLabel(store.status))
        InfoRow(stringResource(R.string.agent_session_info_cwd), store.cwd ?: "-", mono = true)
        InfoRow(stringResource(R.string.agent_session_info_model), store.model ?: "-")
        InfoRow(
            stringResource(R.string.agent_session_info_messages),
            store.items.size.toString(),
        )
        InfoRow(
            label = stringResource(R.string.agent_session_info_session_id),
            value = sessionId,
            mono = true,
            onClick = {
                // 点一下即复制整条 sessionId（长按选中手机上很难用）
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("sessionId", sessionId)
                )
                onCopy()
            },
        )
        InfoRow(stringResource(R.string.agent_session_info_endpoint), baseUrl, mono = true)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(4.dp))

        InfoActionRow(
            icon = Icons.Default.Refresh,
            label = stringResource(R.string.agent_sessions_refresh),
            onClick = onRefresh,
        )
        InfoActionRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            label = stringResource(R.string.agent_session_open_web),
            onClick = onOpenWeb,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClick)
                        .padding(vertical = 2.dp)
                } else {
                    Modifier.padding(vertical = 2.dp)
                }
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(text = label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun statusLabel(status: AgentRunStatus): String = when (status) {
    AgentRunStatus.Idle -> "空闲"
    AgentRunStatus.Streaming -> "运行中"
    AgentRunStatus.Retrying -> "重试中"
    AgentRunStatus.Aborted -> "已中断"
    AgentRunStatus.Error -> "出错了"
}

@Composable
private fun AgentItemView(item: AgentItem) {
    when (item) {
        is AgentItem.UserText -> UserBubble(item)
        is AgentItem.AssistantText -> AssistantBubble(item)
        is AgentItem.Thinking -> ThinkingBubble(item)
        is AgentItem.ToolCall -> ToolCallCard(item)
        is AgentItem.Note -> NoteRow(item)
    }
}
