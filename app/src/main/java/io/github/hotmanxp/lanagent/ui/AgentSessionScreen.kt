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

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.AgentInstance
import io.github.hotmanxp.lanagent.data.AgentSessionMeta
import io.github.hotmanxp.lanagent.data.AttachedImage
import io.github.hotmanxp.lanagent.data.InstancesApi
import io.github.hotmanxp.lanagent.data.CMD_TYPE_CLEARED
import io.github.hotmanxp.lanagent.data.CMD_TYPE_COMPACTED
import io.github.hotmanxp.lanagent.data.CMD_TYPE_ERROR
import io.github.hotmanxp.lanagent.data.CMD_TYPE_MESSAGE
import io.github.hotmanxp.lanagent.data.CMD_TYPE_PROMPT
import io.github.hotmanxp.lanagent.data.CMD_TYPE_STATUS
import io.github.hotmanxp.lanagent.data.CMD_TYPE_UNKNOWN
import io.github.hotmanxp.lanagent.data.compactedInfo
import io.github.hotmanxp.lanagent.data.PRESENT_FILE_TOOL
import io.github.hotmanxp.lanagent.data.PresentedFile
import io.github.hotmanxp.lanagent.data.errorText
import io.github.hotmanxp.lanagent.data.messageText
import io.github.hotmanxp.lanagent.data.parseSlashInput
import io.github.hotmanxp.lanagent.data.renderedPrompt
import io.github.hotmanxp.lanagent.data.SlashItem
import io.github.hotmanxp.lanagent.data.statusText
import io.github.hotmanxp.lanagent.data.unknownInput
import io.github.hotmanxp.lanagent.data.compactToolsFlow
import io.github.hotmanxp.lanagent.data.ImageAttachments
import io.github.hotmanxp.lanagent.data.ModelEntry
import io.github.hotmanxp.lanagent.data.PatchSessionRequest
import io.github.hotmanxp.lanagent.data.SessionToolsApi
import io.github.hotmanxp.lanagent.data.pickDefault
import io.github.hotmanxp.lanagent.data.readAgentWorkspace
import io.github.hotmanxp.lanagent.data.resolveAgentInstances
import io.github.hotmanxp.lanagent.data.saveAgentWorkspace
import io.github.hotmanxp.lanagent.voice.VoiceAsrConfig
import io.github.hotmanxp.lanagent.voice.HoldToTalkOverlay
import io.github.hotmanxp.lanagent.voice.rememberHoldToTalk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.MessageSquarePlus
import com.composables.icons.lucide.RefreshCw

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
 * 点 `PresentFile` 卡片里的某个文件时,**不分发给调用方** —— 预览层由面板自己
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
    val clipboard = LocalClipboardManager.current
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
    /** 正在重启的实例 id(面板里那行转圈);非 null 时禁掉其它行的重启按钮。 */
    var restartingId by remember { mutableStateOf<String?>(null) }
    /** 待确认的重启目标 —— 确认弹窗的入参,确认后清空。 */
    var restartConfirm by remember { mutableStateOf<AgentInstance?>(null) }

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
    /**
     * 屏内共享的「现在」时钟,15s 一跳(见下面的 ticker)。两个消费方:
     *   - 抽屉会话列表的「N 分钟前」相对时间
     *   - 底部任务栏「最近结束」窗口的过期判定(见 TaskDockStrip 的 now)
     * 所以叫 clockNow 而不是 drawerNow —— 它已经不是抽屉专用的了。
     */
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
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

    // 「详情」弹层(0.26.3)—— 会话流里那行摘要点开后的全部内容。
    //
    // 存的是**段的 key,不是下标也不是成员快照**:
    //   - 存成员快照 → 工具输出是原地替换(`applyToolResult` 换掉 items 里的对象),
    //     快照会让「点开一条正在跑的命令、等它跑完看输出」永远停在点开那一刻;
    //   - 存下标     → 段是**活的**,Agent 接着在同一段里又调了几次工具,
    //     冻结的下标只覆盖点开那一刻已有的那些,后面的静默不出现。
    // 按 key 每次重组重新从 [blocks] 解析,两种情况都自然是实时的。
    var activityDetailKey by remember(currentSid) { mutableStateOf<String?>(null) }
    val activitySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // PresentFile 预览层(从右侧滑入的全屏 overlay,见 ui/FileViewerOverlay.kt)。
    // **关闭时只把 previewOpen 置 false,previewTarget 保留** —— 滑出动画期间内容
    // 还得在场,清空的话抽屉会在滑走的过程中变成一片空白。
    var previewTarget by remember { mutableStateOf<FilePreviewTarget?>(null) }
    var previewOpen by remember { mutableStateOf(false) }

    // 「工作区」浮层(0.24.11,见 ui/SessionToolsOverlay.kt)。与 previewTarget
    // 共用预览层:文件栏点文件只是换个 target,预览层盖在面板之上,关掉
    // 预览后面板还在场。
    var toolsOpen by remember { mutableStateOf(false) }
    val toolsApi = remember(instanceBaseUrl) {
        instanceBaseUrl?.let { SessionToolsApi(it) }
    }

    // 模型选择 —— 状态独立于 store,因为 model 是「用户偏好」级别的字段,
    // 不需要随 transcript 一起 hydrate。`availableModels` 失败时降级
    // 为空列表(AgentInputBar chip 会显示锁头图标,不让用户点开空 picker)。
    var availableModels by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    var currentModel by remember { mutableStateOf<ModelEntry?>(null) }

    // ---- 命令面板(/命令 + Skill,见 data/SlashCommands.kt)----
    // 清单按**实例**缓存(`remember(api)`):同一实例的 `/api/slash` 基本是静态的
    // (内置命令 + 磁盘上的 skill),没必要每次切会话都重拉,切实例才重拉。
    //
    // 拉失败会降级成空列表 → 输入条自动不启用面板(见 AgentInputBar 的
    // slashItems 注释)。**不弹错**:命令面板是增强,它坏了不该影响发消息。
    var slashItems by remember(api) { mutableStateOf<List<SlashItem>>(emptyList()) }
    var slashLoading by remember(api) { mutableStateOf(false) }
    LaunchedEffect(api) {
        val a = api ?: return@LaunchedEffect
        slashLoading = true
        slashItems = a.listSlashCommands()
        slashLoading = false
    }

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
                // 陈旧回调守卫:hydrate / readState 两个 HTTP 请求在切实例、切会话、
                // 或者 refreshTick 触发重启时都可能在飞。旧请求回来得比新的早,
                // 于是会把**上一个实例的 transcript** 写进当前 store —— 症状是切会话
                // 后消息流闪一下变成旧内容,或者更糟:一个已下线的实例的慢响应
                // 覆盖了新实例的。
                // 取号-校验的写法对齐 Agents-Anywhere 的 SessionRealtimeController
                // (connectionGeneration / runtimeRefreshGeneration 双代号)。
                val gen = store.beginRequest()
                val transcript = a.readTranscript(sid)
                if (!store.isCurrentRequest(gen)) return@repeatOnLifecycle
                store.hydrate(transcript)
                // state 是可选增强(cwd / v2Tasks),拿不到不影响对话
                runCatching { a.readState(sid) }
                    .onSuccess { if (store.isCurrentRequest(gen)) store.hydrateState(it) }
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
    // 相对时间 tick —— 让抽屉列表的「N 分钟前」不卡在同一数字,同时驱动底部
    // 任务栏「最近结束」窗口的过期(见 clockNow 声明处)。
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            clockNow = System.currentTimeMillis()
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

    // 每轮的「产物」清单(这一轮生成 / 修改了哪些文件)。同样是**渲染期派生**:
    // 数据源就是这一份 items,不落 transcript、不需要后端配合(见 ui/TurnArtifacts.kt)。
    // 它依赖轮次是否结束 —— 流式进行中不出块,所以 status 也是 key。
    val turnArtifacts = remember(store.items.size, store.status) {
        deriveTurnArtifacts(store.items, closed = store.status.turnClosed)
    }

    // 渲染块:精简模式下把连续工具调用折成一「段」,再把每轮的产物块按锚点插进去。
    // 用 `items.size` 当 key 是有意的 —— items 只 append,原地更新都是同类替换
    // (见 buildAgentBlocks 注释),所以「下标 → 类型」的映射只在条数变化时才会变。
    // 渲染时按下标读**实时**值,工具输出回流因此照常刷新。
    val blocks = remember(store.items.size, compactTools, turnArtifacts) {
        buildAgentBlocks(store.items, compactTools, turnArtifacts)
    }

    // 自动跟随:0.23.0 起不再用「firstVisibleItemIndex <= 3」这种位置快照判
    // 用户意图 —— 那个判据在用户上滑**不到 3 屏**时仍会被新消息拽回底部,
    // 正好砸在用户正在回看的区间上。改成由 [userScrollDetection] 捕捉真实手势:
    // 手指一动就暂停跟随,滑回底部再恢复。
    val autoFollow = rememberAutoFollowController(listState, scope)

    // 用**块数**而不是条数做 key:聚合段落继续吞新工具时块数不变(视口不用动),
    // 新开一段才需要把视口拉回底部。
    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty() && !autoFollow.paused) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    val busy = store.status == AgentRunStatus.Streaming || store.status == AgentRunStatus.Retrying

    fun toast(msg: String) {
        scope.launch { snackbarHostState.showToast(msg) }
    }

    /** 失败提示 —— 红边 + 警示图标(0.22.0)。 */
    fun toastError(msg: String) {
        scope.launch { snackbarHostState.showErrorToast(msg) }
    }

    /**
     * 复制用户消息正文(用户气泡左侧那枚复制按钮)。对齐 AA 的 `copyMessageText`:
     * 去掉尾部换行再进剪贴板,给一句「已复制」。
     *
     * 文案要在 composable 作用域里读,再被普通函数 copyText 用 —— 不能
     * 在 copyText 里直接 `stringResource(...)`,那是 @Composable,会编不过。
     */
    val copiedMessage = stringResource(R.string.agent_bubble_copied)

    fun copyText(text: String) {
        val payload = text.trimEnd('\r', '\n')
        if (payload.isBlank()) return
        clipboard.setText(AnnotatedString(payload))
        toast(copiedMessage)
    }

    /**
     * 在 Mac 上打开该文件所在目录(`POST /api/fs/reveal`,macOS 侧是 `open -R`)。
     * 成功与失败都给一句反馈 —— 不留静默失败(与预览层里那个 📂 一致)。
     */
    fun revealOnMac(a: AgentApi, path: String) {
        scope.launch {
            val ok = runCatching { a.revealFile(path) }.getOrDefault(false)
            if (ok) snackbarHostState.showToast("已在 Mac 上打开所在目录")
            else snackbarHostState.showErrorToast("打开目录失败（Mac 可能没起图形界面）")
        }
    }

    /**
     * 执行一条命令。**模板展开在服务端**(见 data/SlashCommands.kt 文件头),
     * 本端只按返回的 type 分流 —— 对齐 web 端 `AgentInputBox.handleSend`:
     *   - `prompt`:把服务端渲染好的文本当**普通消息**发出去,但本地展示的是
     *     用户敲的原文(`/commit fix: xxx`),否则消息流里会冒出一段用户从没
     *     写过的长文;
     *   - 其余(local):本地消费,不产生模型调用 —— `cleared` 清屏、`status` /
     *     `message` 落提示条、`error` / `unknown` 报错。
     *
     * @param name 命令名(不含 `/`;插件项是带前缀的全名,服务端按全名解析)。
     */
    fun runCommand(name: String, rawText: String) {
        val a = api ?: return
        val sid = currentSid ?: return
        val args = parseSlashInput(rawText)?.args.orEmpty()
        // 先清输入框:命令已经在飞了,留着那段文字只会让人以为没发出去。
        input = ""
        scope.launch {
            runCatching { a.runCommand(sid, name, args) }.fold(
                onSuccess = { res ->
                    when (res.type) {
                        CMD_TYPE_PROMPT -> {
                            val rendered = res.renderedPrompt().orEmpty()
                            if (rendered.isBlank()) {
                                toastError(context.getString(R.string.agent_cmd_failed, "空 prompt"))
                                return@fold
                            }
                            store.appendLocalUser(rawText)
                            runCatching { a.sendPrompt(sid, rendered) }
                                .onFailure {
                                    toastError(context.getString(R.string.agent_cmd_failed, it.message ?: "$it"))
                                }
                        }

                        CMD_TYPE_CLEARED -> {
                            // 服务端已清 transcript,本地列表必须一起清(store.clearAll)
                            store.clearAll()
                            toast(context.getString(R.string.agent_cmd_cleared))
                        }

                        CMD_TYPE_COMPACTED -> {
                            val info = res.compactedInfo()
                            toast(context.getString(R.string.agent_cmd_compacted, info?.first ?: 0))
                            info?.second?.takeIf { it.isNotBlank() }?.let { store.appendNote(it) }
                        }

                        CMD_TYPE_STATUS -> store.appendNote(
                            res.statusText().orEmpty().ifBlank { "/status 没有可显示的内容" }
                        )

                        CMD_TYPE_MESSAGE -> store.appendNote(
                            res.messageText().orEmpty().ifBlank { "/$name 没有返回内容" }
                        )

                        CMD_TYPE_ERROR -> store.appendNote(
                            res.errorText().orEmpty().ifBlank { "命令执行失败" },
                            isError = true,
                        )

                        CMD_TYPE_UNKNOWN -> toast(
                            context.getString(
                                R.string.agent_cmd_unknown,
                                res.unknownInput() ?: "/$name",
                            )
                        )

                        // 服务端将来加新 type 时不要静默吞掉:原样落一条提示条,
                        // 至少看得见「服务端回了点本端还不认识的东西」。
                        else -> store.appendNote("/$name → ${res.type}")
                    }
                },
                onFailure = {
                    toastError(context.getString(R.string.agent_cmd_failed, it.message ?: "$it"))
                },
            )
        }
    }

    fun send() {
        val a = api ?: return
        val sid = currentSid ?: return
        val text = input.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        // 命令优先:**不以「命中本地清单」为准,只以语法为准**(对齐 web 端
        // `handleSend` 的 `^[A-Za-z0-9:_-]+$` 闸)—— 清单只喂面板,执行交给
        // 服务端判:服务端不认识的命令会回 `unknown`,由本端报「未知命令」,
        // 比「清单请求失败就把 /clear 当普通消息发给模型」安全得多。
        parseSlashInput(text)?.let { parsed ->
            runCommand(parsed.name, text)
            return
        }
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
     *   1. POST /api/agent/sessions 拿新 sid(body 带上当前模型 → 新会话同款)
     *   2. drawerRefreshTick++ 立刻把新会话刷进抽屉列表
     *   3. 关抽屉,currentSid → 新 sid(LaunchedEffect 自动切 hydrate + SSE)
     */
    fun startNewSession() {
        if (creating) return
        val a = api ?: return
        // 必须在 create 之前取:create 成功后 currentSid 换新,transcript hydrate
        // 会用新会话的 meta 重算 currentModel,那时已经读不到旧值了。
        // 走 `currentModel` 而不是 `store.model` —— 前者是用户 picker 选完
        // 之后的乐观态(见下面的 onModelChange),正是「上一个会话的模型选择」;
        // store.model 只是 hydrate 时那一瞬的服务端原值。
        // 没会话 / 模型未知(空串 / 'unknown')时为 null → body 不带 model,
        // 新会话回落服务端默认模型,维持旧行为。
        val inherited = currentModel
        creating = true
        scope.launch {
            val res = runCatching { a.createSession(inherited?.model, inherited?.providerId) }
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

    /**
     * 重启子实例(`POST /api/instances/{id}/restart`,对齐 web 端 Instances 页的
     * 「重启」)。三段式,和 AgentInstances.canRestart 的注释对得上:
     *
     *   1. **POST** —— 服务端做 `doStop` + `doStart`。子进程不理 SIGINT 时
     *      这一步最长 11.5s(InstancesApi 的 actionClient 专门放宽了读超时)。
     *   2. **轮询到在线** —— POST 的响应只代表 supervisor 已经 spawn 出子进程,
     *      那时 `state` 还是 `starting`;`running` 要等子进程发 ready IPC
     *      (opencc-web `instanceSupervisor.ts` 的 attachChild)。端口也是那会儿
     *      才定的,自动扫端口时**可能换**,所以必须重拉目录,不能信响应里的快照。
     *   3. **重挂** —— 被重启的就是当前实例时,SSE 已被 SIGINT 掐断,
     *      `refreshTick++` 让 hydrate + eventStream 整条 effect 重启。
     *
     * 弹层**不自动关**:让用户看着那一行从转圈变回「在线」,比弹层一关、
     * 目录悄悄变绿更有反馈。currentSid 不动 —— transcript 落在实例 cwd 的
     * ~/.zai/tasks/ 下,重启不换 cwd,原会话照样在。
     */
    fun performRestart(target: AgentInstance) {
        val manager = target.managerBaseUrl ?: return
        if (restartingId != null) return
        restartingId = target.id
        val wasActive = target.id == active?.id
        scope.launch {
            val posted = runCatching { InstancesApi(manager).restartInstance(target.id) }
            if (posted.isFailure) {
                restartingId = null
                toastError(
                    context.getString(
                        R.string.agent_instance_restart_failed,
                        posted.exceptionOrNull()?.message ?: posted.toString(),
                    )
                )
                return@launch
            }

            val online = context.awaitInstanceOnline(target.id)
            instances = runCatching { context.resolveAgentInstances() }
                .getOrDefault(instances)
            restartingId = null

            if (online == null) {
                toastError(context.getString(R.string.agent_instance_restart_timeout, target.name))
                return@launch
            }
            toast(context.getString(R.string.agent_instance_restart_done, target.name))
            // 端口变了 → `active` 的 baseUrl 得换,`remember(instanceBaseUrl)`
            // 自然造出新 AgentApi;没变也要刷 —— SSE 那条连接已经死了。
            if (wasActive) {
                active = online
                refreshTick++
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
    // 拍照与选图**共用这一条管道** —— 两个 launcher 的回调只差一个 uri。
    fun attachImage(uri: android.net.Uri) {
        if (attachments.size >= ImageAttachments.MAX_COUNT) {
            toast("最多只能带 ${ImageAttachments.MAX_COUNT} 张图片")
            return
        }
        scope.launch {
            runCatching { ImageAttachments.load(context, uri) }
                .onSuccess { attachments = attachments + it }
                .onFailure { toast("读取图片失败:${it.message ?: it}") }
        }
    }
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        attachImage(uri)
    }

    // 拍照（Trae 的「拍照」方砖）。CAMERA 权限清单里已声明（为 ScanQrScreen），
    // 这里补运行时申请：已授权直接开取景框，未授权先发一次请求，
    // 拒了只 toast —— 不做二次教育弹窗，用户再点一次方砖会重新触发。
    var showCamera by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCamera = true else toast("没有相机权限，无法拍照")
    }
    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showCamera = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
    // 外面这层 Box 是**预览层的叠放宿主**:PresentFile 预览要盖住整个会话区
    // (含顶栏),所以跟 ModalNavigationDrawer 平级叠放,而不是塞进 Scaffold 内容里。
    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 关掉边缘右滑:手势会和消息列表的横向拖动(嵌套滚动 / 表格横滑)打架,
            // 误触发比少个手势烦人得多。会话面板只从顶栏左上角按钮开。
            // 代价是这个 flag 会连「点蒙层关闭」一起关掉(1.3.1 源码里 scrim 的
            // onClick 同样挂在 gesturesEnabled 下),返回键另有一处 BackHandler 兜。
            gesturesEnabled = false,
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
                                now = clockNow,
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
                            //
                            // 0.26.3:图标从 Lucide.NotebookTabs(带标签页的笔记本)
                            // 换成 Lucide.Menu(三条杠),并套上圆形底 —— 顶栏左右两侧
                            // 现在是同一套「圆形/药丸容器 + 线性图标」语言。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TopBarCircleButton(
                                    imageVector = Lucide.Menu,
                                    contentDescription = stringResource(
                                        R.string.agent_session_open_sessions_cd
                                    ),
                                    onClick = { scope.launch { drawerState.open() } },
                                )
                                if (onBack != null) {
                                    TopBarCircleButton(
                                        imageVector = Lucide.ArrowLeft,
                                        contentDescription = stringResource(R.string.webview_back_cd),
                                        onClick = onBack,
                                    )
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
                                        imageVector = Lucide.Folder,
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
                                        imageVector = Lucide.ChevronRight,
                                        contentDescription = stringResource(R.string.agent_session_info_title),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        },
                        actions = {
                            // 0.26.3 照 Trae 把这一格从「单个工作区按钮」改成
                            // **一个药丸里的两个图标**:左边气泡+ = 新增会话,
                            // 右边「…」= 工作区(文件 / Bash / git)。
                            //
                            // 历史:0.24.11 这里原本就是「+ 新建会话」,当时因为抽屉里的
                            // NewSessionPill 和空态按钮已经覆盖了入口而被换成工作区;
                            // 现在两格并列,两个入口都不再藏在抽屉里。
                            //
                            // 尺寸刻意不用 IconButton:`minimumInteractiveComponentSize`
                            // 会把每个按钮撑到 48dp,两个加起来 ~96dp,顶栏标题
                            // 在 360dp 宽的屏上只剩 ~150dp。这里自绘 38dp 圆形
                            // + noRippleClickable(同浮刷新按钮那套做法)。
                            val dark = LocalWbDarkTheme.current
                            val pillSurface =
                                if (dark) WbPalette.CardDark else WbPalette.CardLight
                            val pillBorder =
                                if (dark) WbPalette.HairlineDark else WbPalette.HairlineLight
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(pillSurface)
                                    .border(1.dp, pillBorder, CircleShape)
                                    // 内边距 1dp 撑开药丸描边,末端 4dp 让药丸到屏幕
                                    // 右边距跟左边那个圆钮到左边距看着一样宽。
                                    .padding(start = 1.dp, top = 1.dp, bottom = 1.dp, end = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TopBarPillButton(
                                    imageVector = Lucide.MessageSquarePlus,
                                    contentDescription = stringResource(R.string.agent_sessions_new),
                                    // 实例离线时点下去必然失败,跟抽屉里那个 Pill
                                    // 同一个判据;`creating` 期间也不让连点。
                                    enabled = api != null && active?.online != false && !creating,
                                    onClick = { startNewSession() },
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 1.dp, height = 16.dp)
                                        .background(pillBorder)
                                )
                                TopBarPillButton(
                                    imageVector = Lucide.Ellipsis,
                                    contentDescription = stringResource(R.string.agent_tools_cd),
                                    // `currentSid != null` 是硬条件:没有会话时
                                    // sessionId 是空串,`/api/bash/repl//events` 匹配不上
                                    // 路由 → 每 15s 一次 404 无限重连,面板开着就一直烧。
                                    enabled = api != null && currentSid != null,
                                    onClick = { toolsOpen = true },
                                )
                            }
                        },
                    )
                },
                // 换成 WbToastHost(0.22.0):提示从底部挪到**顶部**、1.6s 自动消失、
                // 带对勾/警示图标。底部那条会跟输入条(双行白卡)打架,而且 M3 默认
                // 4s 太长 —— 点完复制早该知道了。
                // ⚠️ 顶部挂载后 **不再需要 imePadding**:本页是 edge-to-edge +
                // adjustResize(见 MainActivity),窗口不为键盘缩高,原先底部
                // SnackbarHost 会藏到键盘后面(「命令执行失败」静默消失),
                // 挪到顶部就天然避开了这个坑。
                snackbarHost = { WbToastHost(snackbarHostState) },
            ) { padding ->
                // 录音动效层（HoldToTalkOverlay）盖在整个内容区上：无 pointerInput，
                // 不吃触摸，按住手势仍在胶囊上。
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(padding)) {

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                !bootstrapDone || sessionResolving -> SessionSkeleton()

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

                                store.items.isEmpty() && !store.hydrated -> SessionSkeleton()

                                store.items.isEmpty() -> AgentSessionEmptyState()

                                else -> LazyColumn(
                                    state = listState,
                                    // 倒序布局:index 0 贴底,流式追加时视口自动跟住新内容,
                                    // 不需要每帧手动算滚动偏移。
                                    reverseLayout = true,
                                    // 手势检测(0.23.0):用户一碰就暂停自动跟随,
                                    // 滑回底部再恢复。只在 UserInput 来源时记 ——
                                    // 内容变高导致的程序性滚动不该算成「用户在看历史」。
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .userScrollDetection(autoFollow),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(
                                        count = blocks.size,
                                        key = { i -> blocks[blocks.size - 1 - i].key },
                                        // 按渲染类型分组,让 LazyColumn 复用 composition。
                                        // 5 种 block 的构图成本差一个数量级(正文气泡 /
                                        // 用户气泡 / 工具卡 / 文件卡 / 产物块),不分组的话
                                        // 滚动时槽位会按 key 逐个销毁重建,白烧帧。
                                        // 用 `::class` 而不是变体名 —— 变体增删时这里自动跟上。
                                        contentType = { i -> blocks[blocks.size - 1 - i]::class },
                                    ) { i ->
                                        AgentBlockView(
                                            block = blocks[blocks.size - 1 - i],
                                            items = store.items,
                                            api = api,
                                            listState = listState,
                                            // 用**活跃实例**的 baseUrl(面板能原地切实例,
                                            // 路由参数/首帧实例都会过期)。
                                            onOpenFile = { file ->
                                                instanceBaseUrl?.let { url ->
                                                    previewTarget = FilePreviewTarget(url, file.path)
                                                    previewOpen = true
                                                }
                                            },
                                            onReveal = { file ->
                                                api?.let { revealOnMac(it, file.path) }
                                            },
                                            onCopy = { text -> copyText(text) },
                                            onShowActivity = { activityDetailKey = it },
                                        )
                                    }
                                }
                            }
                        }

                        // 底部固定区:任务栏(任务清单 + 后台任务)/ 队列 / 待处理交互。
                        // 整体限高 + 可滚,避免 ask 卡片选项多时把输入条挤出屏幕。
                        val pending = store.pending
                        if (store.hasDockContent || store.queue.isNotEmpty() || pending != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TaskDockStrip(
                                    v2Tasks = store.v2Tasks,
                                    agentTasks = store.bgAgentTasks,
                                    bashTasks = store.bgBashTasks,
                                    status = store.status,
                                    now = clockNow,
                                )
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
                        // 任务栏在渲染时 status 已经 inline 到它的 header(见
                        // TaskDockStrip),否则 strip header 一行 + status 行 + 输入卡
                        // 挤在屏幕底端很噪。判定用 hasDockContent —— 只要有任务栏就
                        // 不重复画这一行,后台任务在跑而任务清单为空时同样适用。
                        //
                        // start = 12.dp:对齐消息气泡左边距,让 StatusBadge 的三个 dot
                        // 起点跟消息文本对齐,而不是贴屏幕左边。
                        if (store.status != AgentRunStatus.Idle && !store.hasDockContent) {
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
                                onTakePhoto = {
                                    if (attachments.size >= ImageAttachments.MAX_COUNT) {
                                        toast("最多只能带 ${ImageAttachments.MAX_COUNT} 张图片")
                                    } else {
                                        openCamera()
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
                                // 命令面板:/api/slash 拿到的候选 + 「选定后怎么执行」。
                                // 输入条负责面板交互与补全,执行(打接口 + 分流)在这里。
                                slashItems = slashItems,
                                slashLoading = slashLoading,
                                onRunSlash = { item -> runCommand(item.name, input) },
                            )
                        }
                    }

                    // 录音中的全屏动效：绿浪涌起 + 波形（见 voice/HoldToTalkOverlay.kt）。
                    holdToTalk?.let { HoldToTalkOverlay(it) }
                }
            }
        }

        // 叠放顺序 = 声明顺序,**后面的在上面**。
        //
        // 面板先声明、预览后声明:从文件栏点开文件时,预览要盖在面板之上;
        // 反过来点开了却看不见(这层不透光,底下什么都看不到)。
        if (toolsOpen) {
            SessionToolsOverlay(
                api = toolsApi,
                sessionId = currentSid.orEmpty(),
                onOpenFile = { absPath ->
                    previewTarget = FilePreviewTarget(baseUrl = instanceBaseUrl.orEmpty(), path = absPath)
                    previewOpen = true
                },
                onReveal = { relPath ->
                    val client = api
                    if (client == null) return@SessionToolsOverlay
                    scope.launch {
                        runCatching { client.revealFile(relPath) }
                            .onFailure { toast("打开失败:${it.message ?: it}") }
                    }
                },
                onClose = { toolsOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        FileViewerOverlay(
            visible = previewOpen,
            target = previewTarget,
            onClose = { previewOpen = false },
            modifier = Modifier.fillMaxSize(),
        )

        // 拍照取景框(`+` → 添加到对话 → 拍照)。独立 Dialog 窗口,盖在一切之上;
        // 拍完直接把 uri 交给 attachImage,与相册选图同一条重编码管道。
        if (showCamera) {
            CameraCaptureDialog(
                onDismiss = { showCamera = false },
                onCaptured = { uri ->
                    showCamera = false
                    attachImage(uri)
                },
                onError = { msg ->
                    showCamera = false
                    toast(msg)
                },
            )
        }
    }

    // 系统返回键的优先级 = **后注册的赢**(Compose 的 BackHandler 语义)。
    // 所以顺序必须与叠放顺序相反:先抽屉后面板再预览 —— 三者同时开着时(从抽屉
    // 里点开工作区面板再点文件就是这个状态),按返回依次关掉最上面那层。
    //
    // 抽屉这行不能省:ModalNavigationDrawer 的 gesturesEnabled = false 会把
    // 它自己的手势 **和** 返回键处理一起关掉(实测 1.3.1:抽屉开着时按返回直接
    // 退到桌面),得自己补回来,否则「打开面板看看又后悔」就没退路了。
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = toolsOpen) { toolsOpen = false }
    BackHandler(enabled = previewOpen) { previewOpen = false }

    // 「选择实例」底部弹层 —— 挂在抽屉**外面**(ModalBottomSheet 是独立窗口),
    // 这样从抽屉里点开它也盖在抽屉之上。
    if (showInstancePicker) {
        InstancePickerSheet(
            instances = instances,
            currentBaseUrl = instanceBaseUrl,
            loading = directoryLoading,
            restartingId = restartingId,
            onPick = { switchInstance(it) },
            onRestart = { restartConfirm = it },
            onDismiss = { showInstancePicker = false },
        )
    }

    // 「详情」弹层(0.26.3)—— 一行摘要点开后看全部内容。挂在抽屉外面,
    // 与「选择实例」弹层同理(ModalBottomSheet 是独立窗口)。
    activityDetailKey?.let { key ->
        // 段找不到了(切会话 / hydrate 重来,key 对不上)→ 空列表,弹层显示空的
        // 「详情」而不是上一个会话的内容。不可达路径,兜底而已。
        val indices = (blocks.firstOrNull { it.key == key } as? AgentBlock.ToolGroup)?.indices.orEmpty()
        ActivityDetailSheet(
            indices = indices,
            items = store.items,
            sheetState = activitySheetState,
            onDismiss = { activityDetailKey = null },
        )
    }

    // 重启确认 —— 重启会 SIGINT 掉子进程,当前会话的 SSE 随之断开(下面会自动
    // 重挂),但正在跑的 agent 任务确实会丢。破坏性动作,先问一句。
    restartConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { restartConfirm = null },
            title = { Text(stringResource(R.string.agent_instance_restart_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.agent_instance_restart_confirm_desc,
                        target.name,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restartConfirm = null
                    performRestart(target)
                }) {
                    Text(stringResource(R.string.agent_instance_restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { restartConfirm = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
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

/**
 * 轮询实例目录直到 [id] 那条变成在线,返回最终快照(超时返回 null)。
 *
 * **为什么必须轮询而不是用 POST 的响应**:服务端的 `restartInstance` 是
 * `await doStop(); return doStart()`(opencc-web `instanceSupervisor.ts:511`),
 * 而 `doStart` 在 `spawn` 之后**立刻**返回 —— `state` 要等子进程通过 IPC 发来
 * `{type:'ready', port}` 才翻成 `running`(同文件的 `attachChild`)。所以 POST
 * 回来的快照永远是 `starting` + 可能过期的端口。
 *
 * 轮询窗口 [timeoutMs] 要盖住 doStop 最坏情况之外的启动时间:doStop 自身最长
 * 11.5s(10s SIGINT 超时 + 1.5s grace)已经花在 POST 里了,这里只需要等子进程
 * 起来 —— 但 Node 冷启动 + 监听端口在慢盘/低端机上仍可能几秒,给 40s 余量。
 */
private suspend fun Context.awaitInstanceOnline(
    id: String,
    timeoutMs: Long = 40_000L,
    intervalMs: Long = 1_200L,
): AgentInstance? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val hit = runCatching { resolveAgentInstances() }
            .getOrNull()
            ?.firstOrNull { it.id == id }
        if (hit?.online == true) return hit
        delay(intervalMs)
    }
    return null
}

/**
 * 顶栏圆形按钮 / 药丸按钮的直径(0.26.3)。
 *
 * 刻意小于 M3 的 48dp `minimumInteractiveComponentSize`:顶栏左右一共四个
 * 按钮(左 1–2 个 + 右药丸 2 个),按 48dp 算要吃掉 190dp+,360dp 宽的屏上
 * 标题只剩一条。同 `ui/WebViewScreen.kt` 浮刷新按钮那套做法 —— 自绘 + clickable。
 */
private val TOP_BAR_BUTTON_SIZE = 38.dp

/**
 * 顶栏圆形按钮:白卡圆底 + hairline 边 + 线性图标(0.26.3)。
 *
 * 不用 `IconButton` 的原因见 [TOP_BAR_BUTTON_SIZE]。
 */
@Composable
private fun TopBarCircleButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalWbDarkTheme.current
    val surface = if (dark) WbPalette.CardDark else WbPalette.CardLight
    val border = if (dark) WbPalette.HairlineDark else WbPalette.HairlineLight
    Box(
        modifier = modifier
            .size(TOP_BAR_BUTTON_SIZE)
            .clip(CircleShape)
            .background(surface)
            .border(1.dp, border, CircleShape)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * 药丸里的图标按钮(0.26.3)。底色和边框由外层药丸统一给,这里只画图标 + 命中区。
 * 禁用态靠 icon tint 压到 38% —— 自绘按钮没有 M3 的 disabled contentColor。
 */
@Composable
private fun TopBarPillButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TOP_BAR_BUTTON_SIZE)
            .clip(CircleShape)
            .noRippleClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(19.dp),
        )
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
            icon = Lucide.RefreshCw,
            label = stringResource(R.string.agent_sessions_refresh),
            onClick = onRefresh,
        )
        InfoActionRow(
            icon = Lucide.ArrowUpRight,
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
 * @param api 当前实例的客户端 —— `PresentFile` 的文件卡要用它拉预览 / 字节。
 *   null = 实例还没解析出来(元数据照常渲染,只是点不开)。
 * @param onOpenFile 点文件 → 打开会话面板内的预览层(见 ui/FileViewerOverlay.kt)。
 * @param onReveal 点 📂 → 在 Mac 上打开该文件所在目录(`POST /api/fs/reveal`)。
 * @param onCopy 复制用户消息正文(用户气泡左侧那枚复制按钮)。
 */
@Composable
internal fun AgentItemView(
    item: AgentItem,
    api: AgentApi?,
    listState: LazyListState,
    onOpenFile: (PresentedFile) -> Unit,
    onReveal: (PresentedFile) -> Unit,
    onCopy: (String) -> Unit,
) {
    when (item) {
        is AgentItem.UserText -> UserBubble(item, onCopy)
        is AgentItem.AssistantText -> AssistantBubble(item)
        is AgentItem.Thinking -> ThinkingBubble(item, listState)
        // `PresentFile` 是**自包含展示类**工具:卡片自己就是内容(图片 / 文本
        // 内联渲染),不进通用工具卡的入参/输出形态 —— 对齐 web 端
        // `presentFileRenderer.skipOuterGroup`。没有文件条时(脏数据 / 旧会话)
        // 由 PresentFileCard 自己退回通用卡。
        is AgentItem.ToolCall ->
            if (item.name == PRESENT_FILE_TOOL) {
                PresentFileCard(item, api, listState, onOpenFile, onReveal)
            } else {
                ToolCallCard(item, listState)
            }

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
    listState: LazyListState,
    onOpenFile: (PresentedFile) -> Unit,
    onReveal: (PresentedFile) -> Unit,
    onCopy: (String) -> Unit,
    onShowActivity: (String) -> Unit,
) {
    when (block) {
        is AgentBlock.Single ->
            items.getOrNull(block.index)?.let { AgentItemView(it, api, listState, onOpenFile, onReveal, onCopy) }

        // 精简模式 = 一行摘要 + 点开看详情(0.26.3)。本变体只在 compact 时产生
        // (buildAgentBlocks 的规则),关掉精简时 store 侧就把段拆回 Single,
        // 渲染层不再兜一份旧卡片。
        is AgentBlock.ToolGroup -> ActivityLine(
            members = block.indices.mapNotNull { items.getOrNull(it) },
            onClick = { onShowActivity(block.key) },
        )

        is AgentBlock.Artifacts ->
            TurnArtifactsBlock(files = block.files, listState = listState, onOpenFile = onOpenFile)
    }
}
