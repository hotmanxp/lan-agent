// ui/AgentSessionScreen.kt — 原生「Agent 工作区」屏(核心交付物)。
//
// 0.15.0 起这一个屏承担两个入口:
//   1. **任务栏 tab 根**(`tab/tasks`)—— `initialBaseUrl / initialSessionId`
//      都传 null,屏自己按「最近连接的实例 → 最近一条会话」解析。这是本次改造
//      的重点:任务栏不再是卡片列表,打开就是 Agent。
//   2. **会话详情路由**(`agent-session/{baseUrl}/{instanceName}/{sid}`)—— 也就是
//      `AgentSessionScreen(...)` 那个薄包装,从实例栏 / 卡片进来时用。
//
// 因为两处共用,整个屏被抽成 `AgentSessionPane`,自己持有:
//   - 实例目录(`data/AgentInstances.kt`)+ 当前实例 + 当前会话
//   - 左侧抽屉(「会话切换面板」):顶部实例行(点开「选择实例」弹层)+ 新建会话
//     + 该实例的会话列表
// 切换实例是 **in-place** 的:改 `active` state → `api` / `store` / hydrate / SSE
// 全部按 key 重建,不 push 新路由(否则返回栈会长出一串「实例快照」)。
//
// 视觉参考 WorkBuddy 手机端对话页:
//   - 顶栏:抽屉 + (可选)返回 + 标题 + **可点的副标题**(`目录 · 模型 ›`)。刷新 /
//     在网页打开这些动作收进副标题的面板里,顶栏只留「返回 + 标题 + 副标题」。
//   - 空态:大图标 + 主文案(不是一行小字)
//   - 底部:双行白卡输入条(见 AgentSessionViews)
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.AgentInstance
import io.github.hotmanxp.lanagent.data.AgentSessionMeta
import io.github.hotmanxp.lanagent.data.AttachedImage
import io.github.hotmanxp.lanagent.data.DisplayFile
import io.github.hotmanxp.lanagent.data.compactToolsFlow
import io.github.hotmanxp.lanagent.data.ImageAttachments
import io.github.hotmanxp.lanagent.data.ModelEntry
import io.github.hotmanxp.lanagent.data.PatchSessionRequest
import io.github.hotmanxp.lanagent.data.pickDefault
import io.github.hotmanxp.lanagent.data.readAgentWorkspace
import io.github.hotmanxp.lanagent.data.resolveAgentInstances
import io.github.hotmanxp.lanagent.data.saveAgentWorkspace
import io.github.hotmanxp.lanagent.voice.VoiceAsrConfig
import io.github.hotmanxp.lanagent.voice.HoldToTalkOverlay
import io.github.hotmanxp.lanagent.voice.rememberHoldToTalk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 会话详情路由的薄包装 —— 从实例栏 / 卡片进来时实例与会话都是已知的。 */
@Composable
fun AgentSessionScreen(
    baseUrl: String,
    instanceName: String,
    sessionId: String,
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    AgentSessionPane(
        initialBaseUrl = baseUrl,
        initialInstanceName = instanceName,
        initialSessionId = sessionId,
        onBack = onBack,
        onOpenWeb = onOpenWeb,
    )
}

/**
 * Agent 工作区面板本体。
 *
 * @param initialBaseUrl 初始实例 baseUrl。**null** = 由面板自己解析(任务栏 tab 根
 *   的用法):优先上次连接的实例,它下线了就落到第一个在线的实例。
 * @param initialInstanceName 初始实例显示名(只在 [initialBaseUrl] 非空时作为首帧
 *   占位,目录回来后被真名覆盖)。
 * @param initialSessionId 初始会话 id。**null** = 自动挑该实例最近更新的一条会话。
 * @param onBack null = 作为 tab 根展示,不渲染返回箭头。
 *
 * 点 `DisplayFiles` 卡片里的某个文件时,**不分发给调用方** —— 预览层由面板自己
 * 持有([previewTarget] + [previewOpen]),叠在会话之上从右侧滑入。所以预览不占
 * 路由、不进返回栈,系统返回键由下面的 `BackHandler` 优先吃掉。
 * 预览目标是**当前活跃实例**的 baseUrl:面板能在原地切实例,路由参数会过期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSessionPane(
    initialBaseUrl: String?,
    initialInstanceName: String,
    initialSessionId: String?,
    onBack: (() -> Unit)?,
    onOpenWeb: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()

    // ===== 实例 =====
    // 详情路由进来时先用路由参数摊一个「临时实例」把首帧渲染出来(不然要等
    // 目录那一轮请求);目录回来后用真快照覆盖,拿正式的 name / online。
    var instances by remember { mutableStateOf<List<AgentInstance>>(emptyList()) }
    var directoryLoading by remember { mutableStateOf(true) }
    var bootstrapTick by remember { mutableStateOf(0) }
    var active by remember {
        mutableStateOf(
            initialBaseUrl?.let { url ->
                AgentInstance(
                    id = url,
                    name = initialInstanceName.ifBlank { url.substringAfter("://") },
                    baseUrl = url,
                    online = true,
                    isCurrent = false,
                )
            }
        )
    }
    var bootstrapDone by remember { mutableStateOf(initialSessionId != null) }
    var showInstancePicker by remember { mutableStateOf(false) }

    // ===== 会话 =====
    var currentSid by remember { mutableStateOf(initialSessionId) }
    // 切实例后到「挑出该实例最近一条会话」之间的空档 —— 不渲染空态,渲染转圈,
    // 否则切过去会闪一下「该实例还没有会话」。
    var sessionResolving by remember { mutableStateOf(false) }

    // 抽屉 state + 会话列表
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerSessions by remember { mutableStateOf<List<AgentSessionMeta>>(emptyList()) }
    var drawerLoading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var drawerNow by remember { mutableStateOf(System.currentTimeMillis()) }
    var drawerRefreshTick by remember { mutableStateOf(0) }

    val instanceBaseUrl = active?.baseUrl
    val api = remember(instanceBaseUrl) { instanceBaseUrl?.let { AgentApi(it) } }
    val store = remember(currentSid) { AgentSessionStore(currentSid.orEmpty()) }

    // 会话「精简模式」(设置栏开关,默认开)。只影响**渲染粒度** ——
    // store 里依旧是逐条 AgentItem,关掉开关立刻退回逐条工具卡,不需要重新
    // hydrate。默认值和设置栏读的是同一个 DataStore key(见 UiPrefsRepository)。
    val compactTools by context.compactToolsFlow().collectAsState(initial = true)

    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<AttachedImage>>(emptyList()) }
    var actionBusy by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var approveFile by remember { mutableStateOf<String?>(null) }
    var approveFileLoading by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val infoSheetState = rememberModalBottomSheetState()

    // DisplayFiles 预览层(从右侧滑入的全屏 overlay,见 ui/FileViewerOverlay.kt)。
    // **关闭时只把 previewOpen 置 false,previewTarget 保留** —— 滑出动画期间内容
    // 还得在场,清空的话抽屉会在滑走的过程中变成一片空白。
    var previewTarget by remember { mutableStateOf<FilePreviewTarget?>(null) }
    var previewOpen by remember { mutableStateOf(false) }

    // 模型选择 —— 状态独立于 store,因为 model 是「用户偏好」级别的字段,
    // 不需要随 transcript 一起 hydrate。`availableModels` 失败时降级
    // 为空列表(AgentInputBar chip 会显示锁头图标,不让用户点开空 picker)。
    var availableModels by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    var currentModel by remember { mutableStateOf<ModelEntry?>(null) }

    /**
     * 实例解析(只在首帧 / 手动重试时跑一次):
     *   1. 拉目录(`/api/instances` 优先,管理器不可达则回落卡片探活)
     *   2. 详情路由:只补全元信息,实例与会话都听路由的
     *   3. tab 根:先挑实例(上次连接的 → 第一个在线的),再挑会话(记住的 → 最新的)
     */
    LaunchedEffect(bootstrapTick) {
        directoryLoading = true
        val saved = runCatching { context.readAgentWorkspace() }.getOrNull()
        val dir = runCatching { context.resolveAgentInstances() }.getOrDefault(emptyList())
        instances = dir
        directoryLoading = false

        val current = active
        if (current != null) {
            active = dir.firstOrNull { it.baseUrl == current.baseUrl } ?: current
        } else if (dir.isNotEmpty()) {
            val chosen = dir.pickDefault(saved?.baseUrl)
            active = chosen
            if (chosen != null) {
                sessionResolving = true
                currentSid = pickLatestSession(chosen.baseUrl, saved?.sessionId)
                sessionResolving = false
            }
        }
        bootstrapDone = true
    }

    // hydrate + SSE。两个阶段串行(理由见文件头注释),整体随 STARTED 起停。
    // currentSid 变(抽屉切会话)或 api 变(切实例)时整个 effect 重启 ——
    // 旧 SSE 连接跟着旧 store 一起被 cancel,新 store / 新 SSE / 新 transcript 接力。
    LaunchedEffect(api, currentSid, refreshTick) {
        val a = api ?: return@LaunchedEffect
        val sid = currentSid ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                store.hydrate(a.readTranscript(sid))
                // state 是可选增强(cwd / v2Tasks),拿不到不影响对话
                runCatching { store.hydrateState(a.readState(sid)) }
            } catch (t: Throwable) {
                store.hydrateFailed(t.message ?: t.toString())
            }
            // 模型清单独立于 transcript,失败降级空列表(见 AgentApi.listAvailableModels)
            availableModels = a.listAvailableModels()
            approveFile = null
            a.eventStream(sid).collect { store.apply(it) }
        }
    }

    // 抽屉会话列表轮询 —— 5s 一轮。api 变了(切实例)自动重启,拿到的是新实例的列表。
    LaunchedEffect(api, drawerRefreshTick) {
        val a = api ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { drawerSessions = a.listSessions() }
                    .onFailure {
                        // 抽屉失败静默 —— 主会话屏已经在跑 SSE,失败不该
                        // 弹错打断用户当前工作。下次 5s 后自然重试。
                    }
                drawerLoading = false
                delay(5_000)
            }
        }
    }
    // 相对时间 tick —— 让抽屉列表的「N 分钟前」不卡在同一数字。
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            drawerNow = System.currentTimeMillis()
        }
    }

    // 记住「最近连接的实例 + 会话」。切实例过程中 currentSid 会短暂为 null,
    // 写一次 null 无害 —— 下次启动挑会话时本来就有「记住的不在列表里 → 用最新一条」的兜底。
    LaunchedEffect(instanceBaseUrl, currentSid) {
        val a = active ?: return@LaunchedEffect
        runCatching {
            context.saveAgentWorkspace(
                instanceId = a.id,
                instanceName = a.name,
                baseUrl = a.baseUrl,
                sessionId = currentSid,
            )
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

    // 渲染块:精简模式下把连续工具调用折成一「段」。用 `items.size` 当 key 是
    // 有意的 —— items 只 append,原地更新都是同类替换(见 buildAgentBlocks 注释),
    // 所以「下标 → 类型」的映射只在条数变化时才会变。渲染时按下标读**实时**值,
    // 工具输出回流因此照常刷新。
    val blocks = remember(store.items.size, compactTools) {
        buildAgentBlocks(store.items, compactTools)
    }

    // reverseLayout=true 时 index 0 在**底部**,所以「贴底」等价于
    // firstVisibleItemIndex 很小。用户往上翻超过 3 屏就不再抢滚动位置。
    // 用**块数**而不是条数:聚合段落继续吞新工具时块数不变(视口不用动),
    // 新开一段才需要把视口拉回底部。
    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty() && listState.firstVisibleItemIndex <= 3) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    val busy = store.status == AgentRunStatus.Streaming || store.status == AgentRunStatus.Retrying

    fun toast(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun send() {
        val a = api ?: return
        val sid = currentSid ?: return
        val text = input.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val images = attachments
        input = ""
        attachments = emptyList()
        // 本地乐观追加:SSE 事件面里没有「用户发了消息」这类事件
        // (runtime.* 全是助手侧),不本地追加就得等下一次 re-hydrate 才看得到。
        store.appendLocalUser(text, images.size, images.map { it.uri })
        scope.launch {
            runCatching { a.sendPrompt(sid, text, images) }
                .onFailure { toast("发送失败:${it.message ?: it}") }
        }
    }

    fun stop() {
        val a = api ?: return
        val sid = currentSid ?: return
        scope.launch {
            runCatching { a.abort(sid) }
                .onFailure { toast("中断失败:${it.message ?: it}") }
        }
    }

    /**
     * 「新建会话」统一入口(Pill / 顶栏 + / 空态按钮共用)。流程:
     *   1. POST /api/agent/sessions 拿新 sid
     *   2. drawerRefreshTick++ 立刻把新会话刷进抽屉列表
     *   3. 关抽屉,currentSid → 新 sid(LaunchedEffect 自动切 hydrate + SSE)
     */
    fun startNewSession() {
        if (creating) return
        val a = api ?: return
        creating = true
        scope.launch {
            val res = runCatching { a.createSession() }
            creating = false
            res.fold(
                onSuccess = { newSid ->
                    drawerRefreshTick++
                    scope.launch { drawerState.close() }
                    currentSid = newSid
                },
                onFailure = { t ->
                    toast("新建会话失败:${t.message ?: t}")
                },
            )
        }
    }

    /** 抽屉里点某条会话:in-place 切 sid + 关抽屉。点当前会话只关抽屉。 */
    fun switchToSession(sid: String) {
        if (sid != currentSid) currentSid = sid
        scope.launch { drawerState.close() }
    }

    /**
     * 切实例:in-place 换 `active`,清掉旧实例的会话列表,重新挑一条会话。
     * 不 push 路由 —— 否则返回栈里会堆一串「实例快照」,返回语义就乱了。
     */
    fun switchInstance(inst: AgentInstance) {
        showInstancePicker = false
        scope.launch { drawerState.close() }
        if (inst.baseUrl == instanceBaseUrl) return
        active = inst
        currentSid = null
        drawerSessions = emptyList()
        drawerLoading = true
        if (inst.online) {
            sessionResolving = true
            scope.launch {
                currentSid = pickLatestSession(inst.baseUrl, null)
                sessionResolving = false
            }
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

    // 按住说话（腾讯云实时 ASR）。**优先于**上面的系统识别：
    //   - 系统识别依赖设备上装了 RecognitionService，国行无 Google 服务的
    //     ROM 上 isRecognitionAvailable() 恒 false，按钮直接不渲染；
    //   - 腾讯云这条路只依赖网络，且支持上滑取消 / 边说边出字。
    // 没配密钥时 providerOrNull() 返回 null → 整条路不启用，安静回落到系统识别。
    // 切实例就换一份 provider —— 后端签发那条路要拼当前实例的 baseUrl。
    val asrProvider = remember(instanceBaseUrl) {
        VoiceAsrConfig.providerOrNull(instanceBaseUrl)
    }
    val holdToTalk = if (asrProvider != null) {
        rememberHoldToTalk(
            asrUrlProvider = asrProvider,
            engine = VoiceAsrConfig.engine,
            onResult = { result ->
                // 0.16.5：语音识别完成自动发送 —— 用户说完了直接落地,不需要再
                // 点发送钮。setInput 与 send() 都在主线程同步执行(Compose state
                // 的读沿用上一帧的快照),所以 send() 读到的 input.trim() 就是
                // 本次识别结果。
                input = result
                send()
            },
            onError = { toast(it) },
            onHint = { toast(it) },
        )
    } else {
        null
    }

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

    // 抽屉(会话切换面板)—— WorkBuddy 风格的左侧面板:
    //   顶部是「实例行」(点开「选择实例」弹层) + 新建会话 pill + 该实例的会话列表。
    //
    // **抽屉打开时主屏不卸载**:ModalNavigationDrawer 是 window-level 的 overlay,
    // 主屏 Composable 不重建,SSE / 输入框状态全保留。切会话 / 切实例都是改 state,
    // LaunchedEffect 按 key 重启,Scaffold 内容自动刷成新会话。
    //
    // 外面这层 Box 是**预览层的叠放宿主**:DisplayFiles 预览要盖住整个会话区
    // (含顶栏),所以跟 ModalNavigationDrawer 平级叠放,而不是塞进 Scaffold 内容里。
    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InstanceSwitcherRow(
                            instance = active,
                            loading = directoryLoading,
                            onClick = { showInstancePicker = true },
                        )
                        NewSessionPill(
                            busy = creating,
                            // 实例离线时压暗 —— 点下去必然失败,不如别让它看着能点。
                            enabled = active?.online != false,
                            onClick = { startNewSession() },
                        )
                        if (drawerSessions.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.agent_switch_sessions_section),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                        if (drawerSessions.isEmpty() && !drawerLoading) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.agent_sessions_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        drawerSessions.forEach { meta ->
                            SessionRow(
                                meta = meta,
                                now = drawerNow,
                                onClick = { switchToSession(meta.sessionId) },
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            // 抽屉在最左,back 在其次 —— 对齐 WorkBuddy 的顶栏顺序。
                            // tab 根用法(onBack == null)只有抽屉按钮。
                            Row {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Menu,
                                        contentDescription = stringResource(
                                            R.string.agent_session_open_sessions_cd
                                        ),
                                    )
                                }
                                if (onBack != null) {
                                    IconButton(onClick = onBack) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = stringResource(R.string.webview_back_cd),
                                        )
                                    }
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = if (currentSid == null) {
                                        active?.name
                                            ?: stringResource(R.string.agent_session_untitled)
                                    } else {
                                        store.title?.takeIf { it.isNotBlank() }
                                            ?: stringResource(R.string.agent_session_untitled)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 可点的副标题：刷新 / 在浏览器打开 / 全部会话元信息都
                                // 收进这个面板，顶栏才干净得下来。形态对齐 WorkBuddy 的
                                // 「小图标 + 一行灰字」面包屑。
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showInfo = true }
                                        .padding(vertical = 2.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = subtitle,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.agent_session_info_title),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { startNewSession() },
                                enabled = api != null,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = stringResource(R.string.agent_session_new_cd),
                                )
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                // 录音动效层（HoldToTalkOverlay）盖在整个内容区上：无 pointerInput，
                // 不吃触摸，按住手势仍在胶囊上。
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(padding)) {

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                !bootstrapDone || sessionResolving -> CenterSpinner()

                                active == null -> NoInstanceState(
                                    onRetry = { bootstrapTick++ },
                                    onPick = { showInstancePicker = true },
                                )

                                currentSid == null -> NoSessionState(
                                    instance = active!!,
                                    creating = creating,
                                    onCreate = { startNewSession() },
                                    onSwitch = { showInstancePicker = true },
                                )

                                store.items.isEmpty() && !store.hydrated -> CenterSpinner()

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
                                        count = blocks.size,
                                        key = { i -> blocks[blocks.size - 1 - i].key },
                                    ) { i ->
                                        AgentBlockView(
                                            block = blocks[blocks.size - 1 - i],
                                            items = store.items,
                                            api = api,
                                            // 用**活跃实例**的 baseUrl(面板能原地切实例,
                                            // 路由参数/首帧实例都会过期)。
                                            onOpenFile = { file ->
                                                instanceBaseUrl?.let { url ->
                                                    previewTarget = FilePreviewTarget(url, file.path)
                                                    previewOpen = true
                                                }
                                            },
                                        )
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
                                V2TaskStrip(store.v2Tasks, store.status)
                                QueueStrip(
                                    queue = store.queue,
                                    onCancel = { q ->
                                        queueAction("取消失败") { api?.cancelQueued(currentSid.orEmpty(), q.id) }
                                    },
                                    onSteer = { q ->
                                        queueAction("插入失败") { api?.steerQueued(currentSid.orEmpty(), q.id) }
                                    },
                                )
                                if (pending != null) {
                                    val sid = currentSid.orEmpty()
                                    PendingCard(
                                        pending = pending,
                                        busy = actionBusy,
                                        fileContent = approveFile,
                                        fileLoading = approveFileLoading,
                                        onLoadFile = {
                                            if (!approveFileLoading) {
                                                approveFileLoading = true
                                                scope.launch {
                                                    runCatching { api?.readApproveFile(sid, pending.toolUseId) }
                                                        .onSuccess { approveFile = it?.content }
                                                        .onFailure { toast("读取文件失败:${it.message ?: it}") }
                                                    approveFileLoading = false
                                                }
                                            }
                                        },
                                        onSubmitAsk = { answers ->
                                            respondPending {
                                                api?.submitAnswer(sid, pending.toolUseId, answers)
                                            }
                                        },
                                        onReject = {
                                            respondPending {
                                                if (pending.kind == "ask") {
                                                    api?.rejectAsk(sid, pending.toolUseId)
                                                } else {
                                                    // 服务端 schema 要求 rejected 必须带非空 comment
                                                    api?.respondApprove(sid, pending.toolUseId, false, "手机端驳回")
                                                }
                                            }
                                        },
                                        onPermission = { allow ->
                                            respondPending {
                                                api?.respondPermission(sid, pending.toolUseId, allow)
                                            }
                                        },
                                        onApprove = { ok ->
                                            respondPending {
                                                api?.respondApprove(
                                                    sessionId = sid,
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

                        // 运行态提示条 —— 顶栏不放状态(对齐 WorkBuddy),改成在输入框
                        // 上面单起一行,空闲时整行不渲染,不占视觉位置。
                        //
                        // 有任务清单时 status 已经 inline 到 V2TaskStrip 的 header,
                        // 否则 strip header 一行 + status 行 + 输入卡挤在屏幕底端很噪。
                        //
                        // start = 12.dp:对齐消息气泡左边距,让 StatusBadge 的三个 dot
                        // 起点跟消息文本对齐,而不是贴屏幕左边。
                        if (store.status != AgentRunStatus.Idle && store.v2Tasks.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
                            ) {
                                StatusBadge(store.status)
                            }
                        }

                        // 没有会话时整条输入区都没意义(发给谁?),直接不渲染 ——
                        // 空态里那颗「新建会话」才是此时该点的东西。
                        if (currentSid != null) {
                            AgentInputBar(
                                value = input,
                                onValueChange = { input = it },
                                busy = busy,
                                attachments = attachments,
                                onRemoveAttachment = { img -> attachments = attachments - img },
                                voice = voice,
                                holdToTalk = holdToTalk,
                                canSend = input.isNotBlank() || attachments.isNotEmpty(),
                                onSend = {
                                    // 录音中的话直接丢弃（discard 而不是 stop）—— stop 之后识别
                                    // 服务仍会异步回调结果，会把刚发出去的话重新填回已清空的
                                    // 输入框，看着像「发出去的话又回来了」。
                                    voice.discard()
                                    holdToTalk?.cancel()
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
                                            api?.patchSession(
                                                sessionId = currentSid.orEmpty(),
                                                body = PatchSessionRequest(
                                                    model = picked.model,
                                                    providerId = picked.providerId,
                                                ),
                                            )
                                        }
                                            .onSuccess {
                                                // 切换成功后不再弹 toast —— chip 已经是新模型,
                                                // 用户能在原地直接看到反馈,再 toast 一次是噪声。
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

                    // 录音中的全屏动效：绿浪涌起 + 波形（见 voice/HoldToTalkOverlay.kt）。
                    holdToTalk?.let { HoldToTalkOverlay(it) }
                }
            }
        }

        FileViewerOverlay(
            visible = previewOpen,
            target = previewTarget,
            onClose = { previewOpen = false },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // 系统返回键优先关预览层,而不是退出会话页 / 切回上一个 tab。
    BackHandler(enabled = previewOpen) { previewOpen = false }

    // 「选择实例」底部弹层 —— 挂在抽屉**外面**(ModalBottomSheet 是独立窗口),
    // 这样从抽屉里点开它也盖在抽屉之上。
    if (showInstancePicker) {
        InstancePickerSheet(
            instances = instances,
            currentBaseUrl = instanceBaseUrl,
            loading = directoryLoading,
            onPick = { switchInstance(it) },
            onDismiss = { showInstancePicker = false },
        )
    }

    if (showInfo && currentSid != null) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = infoSheetState,
        ) {
            SessionInfoSheet(
                sessionId = currentSid.orEmpty(),
                baseUrl = instanceBaseUrl.orEmpty(),
                store = store,
                // 0.15.1:上下文 current / max。current = 直播态 token 数
                // (SSE 推);max = 当前模型 capabilities.contextWindow
                // (从 availableModels 按 model 字段查,first-match,跟
                // web 端 `findAliasForModel` 的「找不到 providerId 时的
                // fallback」一致 — 我们这边模型解析也只按 model 字段)。
                contextTokens = store.contextTokens,
                contextWindow = currentModel?.capabilities?.contextWindow?.toLong(),
                onCopy = { toast("已复制会话 ID") },
                onRefresh = {
                    refreshTick++
                    showInfo = false
                },
                onOpenWeb = {
                    showInfo = false
                    onOpenWeb("${instanceBaseUrl.orEmpty()}/m?sid=${currentSid.orEmpty()}")
                },
            )
        }
    }
}

/**
 * 挑该实例的一条会话:优先「记住的那条」(它还在列表里就用它),否则最新更新的
 * 一条,一条都没有返回 null。列表拉不到(实例离线 / 超时)时**保留**记住的那条 ——
 * 至少让用户看到上次停在哪,而不是直接掉进空态。
 */
private suspend fun pickLatestSession(baseUrl: String, preferredSid: String?): String? {
    val sessions = runCatching {
        AgentApi(baseUrl, callTimeoutMs = 4_000L).listSessions()
    }.getOrNull() ?: return preferredSid
    if (sessions.isEmpty()) return null
    return preferredSid?.takeIf { sid -> sessions.any { it.sessionId == sid } }
        ?: sessions.maxByOrNull { it.updatedAt }?.sessionId
}

@Composable
private fun CenterSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 空会话的占位:WorkBuddy 机器人 + 问候语(对齐 WorkBuddy 欢迎页)。 */
@Composable
private fun AgentSessionEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Image(
                painter = painterResource(R.drawable.wb_mascot),
                contentDescription = stringResource(R.string.agent_session_empty_mascot_cd),
                modifier = Modifier.width(168.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.agent_session_empty_title),
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 目录里一个可用实例都没有 —— 几乎没有 UI 可给(没有实例就没有会话、没有输入条),
 * 所以给一段明确的指引 + 两个动作:重新检测 / 打开「选择实例」。
 */
@Composable
private fun NoInstanceState(onRetry: () -> Unit, onPick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.wb_mascot),
                contentDescription = null,
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.agent_no_instance_title),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.agent_no_instance_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.agent_no_instance_retry))
                }
                OutlinedButton(onClick = onPick) {
                    Text(stringResource(R.string.agent_switch_instance))
                }
            }
        }
    }
}

/**
 * 实例在、但一条会话都没有 —— 不自动建会话(每次打开 App 都多攒一条空会话),
 * 让用户点一下「新建会话」。实例离线时改成提示,不给按钮(点了必然失败)。
 */
@Composable
private fun NoSessionState(
    instance: AgentInstance,
    creating: Boolean,
    onCreate: () -> Unit,
    onSwitch: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.wb_mascot),
                contentDescription = null,
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = instance.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (instance.online) R.string.agent_no_session_hint
                    else R.string.agent_instance_offline_hint
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier.width(220.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (instance.online) {
                    NewSessionPill(busy = creating, onClick = onCreate)
                }
                OutlinedButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.agent_switch_instance))
                }
            }
        }
    }
}

/**
 * 副标题点开的会话信息面板。刷新 / 在浏览器打开这两个动作原本挂在顶栏
 * actions 上，收进这里是为了让顶栏跟 WorkBuddy 一样只剩「返回 + 标题 + 副标题」。
 *
 * 0.15.1 起新增「上下文: current / max」行:
 *   - `current` 来自 SSE 推上来的 [AgentSessionStore.contextTokens](SSE
 *     三路:runtime.started / runtime.done / session/projection key='context.tokens'),
 *     null 时按 "—" 显示。
 *   - `max` 来自当前模型的 `ModelEntry.capabilities.contextWindow`(服务端
 *     `GET /api/agent/settings` 响应里),null 时按 "—" 显示。
 *   - 两边都未知 → "— / —",与 opencc-web `ConversationInfoCard` 的
 *     `fmtTokens` 对齐(< 1000 保留原文,>= 1000 → `${Math.round(n/1000)}K`)。
 */
@Composable
private fun SessionInfoSheet(
    sessionId: String,
    baseUrl: String,
    store: AgentSessionStore,
    contextTokens: Long?,
    contextWindow: Long?,
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
        // 0.15.1:上下文 current / max。等宽数字避免「12K」/「200K」跳,
        // 跟 web 端的 tabular-nums 等价。
        InfoRow(
            stringResource(R.string.agent_session_info_context),
            "${formatTokenCount(contextTokens)} / ${formatTokenCount(contextWindow)}",
            mono = true,
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
            icon = Icons.Rounded.Refresh,
            label = stringResource(R.string.agent_sessions_refresh),
            onClick = onRefresh,
        )
        InfoActionRow(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            label = stringResource(R.string.agent_session_open_web),
            onClick = onOpenWeb,
        )
    }
}

/**
 * 把 token 数按 K 收口显示。< 1000 保留原文(避免「0K」歧义),>= 1000 →
 * `${Math.round(n / 1000)}K`(1,000,000 → "1000K",按 web 端 `fmtTokens`
 * 的口径,即 million 级别也走 K 不切 M)。null → "—",跟 web 端
 * ConversationInfoCard 一致。
 */
internal fun formatTokenCount(n: Long?): String {
    if (n == null) return "—"
    if (n < 1_000L) return n.toString()
    return "${Math.round(n / 1_000.0)}K"
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

/**
 * @param api 当前实例的客户端 —— `DisplayFiles` 的文件卡片要用它拉预览。
 *   null = 实例还没解析出来(元数据照常渲染,只是点不开)。
 * @param onOpenFile 点某一行文件 → 打开会话面板内的预览层
 *   (见 ui/FileViewerOverlay.kt)。
 */
@Composable
internal fun AgentItemView(
    item: AgentItem,
    api: AgentApi?,
    onOpenFile: (DisplayFile) -> Unit,
) {
    when (item) {
        is AgentItem.UserText -> UserBubble(item)
        is AgentItem.AssistantText -> AssistantBubble(item)
        is AgentItem.Thinking -> ThinkingBubble(item)
        is AgentItem.ToolCall -> ToolCallCard(item, api, onOpenFile)
        is AgentItem.Note -> NoteRow(item)
    }
}

/**
 * 渲染块 → 组件。块里存的是**下标**,这里按实时 items 取值 —— 所以工具输出
 * 回流(`applyToolResult` 原地替换)能立刻反映到已渲染的段落里。
 *
 * `mapNotNull { items.getOrNull(it) }`:下标由 `buildAgentBlocks` 与 items
 * 同步产生,理论上取不到 null;兜一下是为了「按下标取值」这种弱引用本身
 * 不出意外(真缺一条也只是少渲染一张卡,不会崩)。
 */
@Composable
private fun AgentBlockView(
    block: AgentBlock,
    items: List<AgentItem>,
    api: AgentApi?,
    onOpenFile: (DisplayFile) -> Unit,
) {
    when (block) {
        is AgentBlock.Single ->
            items.getOrNull(block.index)?.let { AgentItemView(it, api, onOpenFile) }

        is AgentBlock.ToolGroup -> ToolGroupCard(
            members = block.indices.mapNotNull { items.getOrNull(it) },
            groupKey = block.key,
            api = api,
            onOpenFile = onOpenFile,
        )
    }
}
