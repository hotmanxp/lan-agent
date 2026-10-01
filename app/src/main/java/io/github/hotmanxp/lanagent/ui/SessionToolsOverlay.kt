// ui/SessionToolsOverlay.kt — 会话「工作区」面板(0.24.11)
//
// 从会话顶栏右上角打开的全屏浮层,三栏:**文件 / Bash / git**。视觉形态照
// AA(Agents Anywhere)会话详情页右上角那个按钮通向的文件页 —— 胶囊式分段
// 切换器 + 返回胶囊(见 aa/.../SessionAgentFilesScreen.kt 的 BackChip 与
// PushSwitcher),但配色用 lan-agent 自己的 MaterialTheme,因为这是**非 AA 屏**。
//
// 三栏的数据源都是 zai 已在跑的 API(见 data/SessionToolsApi.kt 顶部的端点
// 清单),**服务端零改动**。整体是只读的:浏览文件、跑命令、看 diff,不写文件、
// 不 commit。
//
// ── 三条不该踩的线 ────────────────────────────────────────────────────
// 1. **AA 那个页面其实只有「文件」一栏**:`PushView` 枚举(同文件 :458)里
//    只有 Files,Bash 在 0.24.2 被挪去了「服务」栏,git 从来没有过。所以这里
//    是"照 AA 的形态 + 用 zai 的 API 补齐三栏",不是复刻。
// 2. **切换器不能直接抄 `PushSwitcher`**:它的指示器偏移硬编码成两槽
//    (`:1008` 的 `if (view == PushView.Files) 0.dp else tabWidth + gap`),
//    容器宽度也是写死的 `.width(196.dp)`。见下面 [ToolsSwitcher]。
// 3. **Bash 栏不是终端**:`bashRepl` 每次 exec 起一个子进程,没有 PTY、没有
//    TTY 回显、没有 resize(见 SessionToolsApi 的类注释)。所以是"输出列表 +
//    单行输入",**不上 xterm.js** —— ui/SshTerminalWebView.kt 那套
//    TerminalTransport 假设持久流 + 固定行列,硬套会做出一个假终端。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Terminal
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.FsEntry
import io.github.hotmanxp.lanagent.data.HttpException
import io.github.hotmanxp.lanagent.data.GitStatus
import io.github.hotmanxp.lanagent.data.GitStatusEntry
import io.github.hotmanxp.lanagent.data.SessionToolsApi
import io.github.hotmanxp.lanagent.data.absUnderCwd
import io.github.hotmanxp.lanagent.data.parentDirOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Bash 输出缓冲上限 —— 只留最近这么多条,更早的丢掉。 */
private const val BASH_MAX_LINES = 500

/**
 * 追加一条输出并裁掉超出的旧行。
 *
 * 必须是**顶层**函数:追加方有两个(浮层里的 SSE 收集器、pane 里的执行回调),
 * 而输出缓冲是浮层持有的状态,跨 composable 边界共享。
 */
private fun MutableList<BashLine>.appendBashLine(line: BashLine) {
    add(line)
    while (size > BASH_MAX_LINES) removeAt(0)
}

/** 面板三栏。用 [ToolsSwitcher] 的下标算指示器位置,所以**顺序不能乱加**。 */
internal enum class ToolsTab { Files, Bash, Git }

/**
 * 会话工作区浮层。
 *
 * @param api 该实例的 client;null = 实例不可达,三栏统一给离线空态。
 * @param sessionId bash REPL 的作用域键(用 agent sessionId,历史天然按会话隔离)。
 * @param onOpenFile 点某个文件 → 交给宿主的 [FileViewerOverlay] 打开。
 *   这里只负责把**相对路径拼成绝对路径**再抛出去(见 [absUnderCwd])。
 * @param onReveal 「在 Mac 上打开」→ `POST /api/fs/reveal`。**刻意做成行尾的
 *   显式按钮而不是默认点按**:它会在用户 Mac 上弹出 Finder 窗口,是这个
 *   "只读"面板里唯一对外可见的副作用,不该被一次误触就触发。
 */
@Composable
internal fun SessionToolsOverlay(
    api: SessionToolsApi?,
    sessionId: String,
    onOpenFile: (String) -> Unit,
    onReveal: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(ToolsTab.Files) }

    // Bash 的状态**提到浮层这一层**,不放进 ToolsBashPane。
    //
    // AnimatedContent 在切栏时会把旧内容 dispose 掉 —— 状态和 SSE 订阅要是
    // 待在 pane 里,切去 git 再切回来就会:重连一次抖动 + 丢掉已经跑出来的
    // 输出。`sleep 30` 跑到一半切个栏,回来就只剩空白,那是很糟的体验。
    // 提到这里,生命周期就等于浮层本身:开面板建流,关面板断流,中间切栏不动。
    val bashLines = remember { mutableStateListOf<BashLine>() }
    var bashRunning by remember { mutableStateOf(false) }
    // 输出**必须有上限**。bashRepl 的 chunk 是按行推的,一条
    // `yes` / `cat 大日志` 就能把 buffer 撑到几万条,而 callbackFlow 的
    // channel 只有 64 格 —— 收集端跟不上时 trySend 还会静默丢弃,两头都失控。
    // 只留最近 N 条,是个够用的终端滚屏语义。

    // 执行命令的 scope 也提到这一层。放在 pane 里的话,切栏时 pane 被 dispose
    // → scope 取消 → `runCatching` 收到 CancellationException 走 onFailure →
    // **退出码 / 被信号终止那两行永远出不来**(实测 stop 后什么都不显示)。
    // 提到浮层,生命周期 = 面板,切栏不断、关面板才断。
    val bashScope = rememberCoroutineScope()
    if (api != null) {
        LaunchedEffect(sessionId) {
            api.bashEvents(sessionId).collect { ev ->
                when (ev.kind) {
                    "stdout" -> ev.chunk?.let { bashLines.appendBashLine(BashLine(it, BashLine.Kind.Stdout)) }
                    "stderr" -> ev.chunk?.let { bashLines.appendBashLine(BashLine(it, BashLine.Kind.Stderr)) }
                    "error" -> ev.message?.let { bashLines.appendBashLine(BashLine(it, BashLine.Kind.Stderr)) }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // **必须垫 IME**:App 走 `setDecorFitsSystemWindows(false)`(AGENTS.md §10),
            // inset 全靠各屏自己处理。键盘弹起时不垫,Bash 栏底部那条命令输入框
            // 会被整个盖住 —— 恰恰是打字时唯一要用的控件。
            .imePadding(),
    ) {
        ToolsHeader(tab = tab, onSelect = { tab = it }, onClose = onClose)

        when {
            api == null -> ToolsEmpty(stringResource(R.string.agent_tools_offline))
            else -> AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val slide = if (forward) 1 else -1
                    (slideInHorizontally { it / 6 * slide } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it / 6 * slide } + fadeOut())
                },
                label = "agent-tools-tab",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    ToolsTab.Files -> ToolsFilesPane(api = api, onOpenFile = onOpenFile, onReveal = onReveal)
                    ToolsTab.Bash -> ToolsBashPane(
                        api = api,
                        sessionId = sessionId,
                        scope = bashScope,
                        lines = bashLines,
                        running = bashRunning,
                        onRunningChange = { bashRunning = it },
                    )
                    ToolsTab.Git -> ToolsGitPane(api = api, onOpenFile = onOpenFile)
                }
            }
        }
    }
}

// ===== 顶栏 =====

@Composable
private fun ToolsHeader(
    tab: ToolsTab,
    onSelect: (ToolsTab) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // **必须垫状态栏**:这个浮层是盖在 Scaffold 内容之上的,不参与
            // 系统的 inset 分发(AGENTS.md §10),不垫的话返回胶囊和切换器
            // 会直接压在时间/电量下面。
            .statusBarsPadding()
            .height(58.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackChip(onClick = onClose)
        // weight 而不是写死宽度:三段胶囊 + 返回胶囊在 360dp 窄屏上写死宽度
        // 会把第三段顶出屏幕(实测 git 栏整个看不见)。
        ToolsSwitcher(
            current = tab,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 返回胶囊 —— 形态照 AA 的 `BackChip`(SessionAgentFilesScreen.kt:968)。 */
@Composable
private fun BackChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Lucide.ChevronLeft,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = stringResource(R.string.agent_tools_back),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 三段胶囊切换器 —— 形态照 AA 的 `PushSwitcher`。
 *
 * **不能直接抄 AA 那份**:它的指示器偏移硬编码成两槽
 * (`if (view == PushView.Files) 0.dp else tabWidth + gap`),容器宽度也写死
 * `.width(196.dp)`(SessionAgentFilesScreen.kt:1008/1010)—— 两段专用。
 *
 * 这里改成**每槽自带底色**而不是外挂一个滑动的指示器。理由不是偷懒:滑动
 * 指示器要按槽宽算偏移,而槽宽由 weight 随屏宽变化,就得引 BoxWithConstraints
 * 那一套宽度换算;每槽自带 `animateColorAsState` 的底色是同一个视觉
 * (选中态浮起的白色药丸),零宽度算式,窄屏上也不会错位。
 */
@Composable
private fun ToolsSwitcher(
    current: ToolsTab,
    onSelect: (ToolsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember { ToolsTab.entries }
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry ->
            val selected = entry == current
            val tint by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "agent-tools-tab-tint",
            )
            // 药丸底色跟着选中态淡入淡出。
            val pill by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                label = "agent-tools-pill",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(pill)
                    .clickable { onSelect(entry) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (entry) {
                        ToolsTab.Files -> Lucide.Folder
                        ToolsTab.Bash -> Lucide.Terminal
                        ToolsTab.Git -> Lucide.GitBranch
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(
                        when (entry) {
                            ToolsTab.Files -> R.string.agent_tools_tab_files
                            ToolsTab.Bash -> R.string.agent_tools_tab_bash
                            ToolsTab.Git -> R.string.agent_tools_tab_git
                        }
                    ),
                    color = tint,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

// ===== 文件栏 =====

@Composable
private fun ToolsFilesPane(
    api: SessionToolsApi,
    onOpenFile: (String) -> Unit,
    onReveal: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cwd by remember { mutableStateOf("") }
    var cwdName by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 世代号:两次快速点不同目录,响应可能乱序到达,晚回来的旧请求会覆盖新
    // 目录的列表。只认当前世代(同 AgentSessionStore 的 requestGeneration)。
    var generation by remember { mutableStateOf(0) }

    fun load(target: String) {
        loading = true
        error = null
        val gen = ++generation
        scope.launch {
            runCatching { api.fsList(target) }
                .onSuccess { list ->
                    if (gen != generation) return@onSuccess
                    if (list.ok) {
                        entries = list.entries
                        dir = target
                    } else {
                        entries = emptyList()
                        error = list.error
                    }
                }
                .onFailure { if (gen == generation) error = it.message }
            if (gen == generation) loading = false
        }
    }

    // 根 = instance cwd。它同时是「相对路径 → 绝对路径」那座桥:
    // /api/fs/list 给相对路径,而预览层要绝对路径(fs.ts:1085 明确不限 cwd)。
    LaunchedEffect(Unit) {
        runCatching { api.systemCwd() }
            .onSuccess {
                cwd = it.cwd
                cwdName = it.cwdName.ifBlank { it.cwd }
                load("")
            }
            // cwd 拿不到就**别列目录**:相对路径拼不出绝对路径,点文件会算出
            // "/src/a.ts" 这种从文件系统根算起的错路径,而且 /api/fs/preview
            // 不沙箱,不会报 403 —— 只会静默打开错的东西。
            .onFailure { error = it.message }
    }

    val parent = parentDirOrNull(dir)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (dir.isBlank()) {
                    stringResource(R.string.agent_tools_root)
                } else {
                    "${cwdName}/$dir"
                },
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            // 根之上没有东西 —— parent 为 null 时禁用,而不是点了没反应。
            IconTextButton(
                icon = Lucide.ChevronUp,
                enabled = parent != null && !loading,
                contentDescription = stringResource(R.string.agent_tools_up_cd),
            ) { parent?.let { load(it) } }
            IconTextButton(
                icon = Lucide.RefreshCw,
                enabled = !loading,
                contentDescription = stringResource(R.string.agent_tools_refresh_cd),
            ) { load(dir) }
        }

        when {
            // cwd 没拿到之前不渲染任何行 —— 相对路径此刻还拼不出绝对路径,
            // 点了会打开从 "/" 起算的错文件,而服务端不沙箱、不会报错。
            cwd.isBlank() && error != null -> ToolsEmpty(error!!)

            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }

            error != null -> ToolsEmpty(error!!)

            entries.isEmpty() -> ToolsEmpty(stringResource(R.string.agent_tools_dir_empty))

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        // 整行点开:目录下钻,文件进预览。预览层只认**绝对**路径,
                        // 而 /fs/list 给的是相对 instance cwd 的 —— 这就是
                        // systemCwd() 那个调用存在的理由。
                        onRowClick = {
                            if (entry.isDirectory) {
                                load(entry.path)
                            } else {
                                onOpenFile(absUnderCwd(cwd, entry.path))
                            }
                        },
                        onReveal = { onReveal(entry.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FsEntry,
    onRowClick: () -> Unit,
    onReveal: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Lucide.Folder else Lucide.FileText,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = entry.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory && entry.size != null) {
            Text(
                text = humanSize(entry.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 「在 Mac 上打开」只给文件。目录整行点击就是下钻,再挂一个做同样
        // 事的按钮纯属噪音。这个动作会在用户 Mac 上弹 Finder 窗口(本面板
        // 唯一对外可见的副作用),所以做成显式按钮而不是行的默认点按。
        if (!entry.isDirectory) {
            IconTextButton(
                icon = Lucide.FolderOpen,
                enabled = true,
                contentDescription = stringResource(R.string.agent_tools_reveal_cd),
                onClick = onReveal,
            )
        }
    }
}

// ===== git 栏 =====

@Composable
private fun ToolsGitPane(api: SessionToolsApi, onOpenFile: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<GitStatusEntry?>(null) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            runCatching { api.gitStatus() }
                .onSuccess { status = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize()) {
        status?.branch?.let {
            Text(
                text = stringResource(R.string.agent_tools_git_branch, it),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
            )
        }
        if (status?.truncated == true) {
            Text(
                text = stringResource(R.string.agent_tools_git_truncated),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }

        val current = status
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }

            error != null -> ToolsEmpty(error!!)

            // 「不是仓库」与「仓库干净」是两回事,别混成一句话。
            current != null && !current.ok -> ToolsEmpty(
                current.error ?: stringResource(R.string.agent_tools_git_not_repo)
            )

            current != null && current.isClean ->
                ToolsEmpty(stringResource(R.string.agent_tools_git_clean))

            current == null || current.entries.isEmpty() -> ToolsEmpty(
                stringResource(R.string.agent_tools_git_clean)
            )

            else -> Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = rememberLazyListState(),
                ) {
                    items(current.entries, key = { it.path }) { entry ->
                        GitRow(
                            entry = entry,
                            selected = entry.path == selected?.path,
                            onClick = { selected = entry },
                        )
                    }
                }
                selected?.let { entry ->
                    // 未跟踪文件要看内容,得拼**仓库根**而不是 instance cwd ——
                    // git 的 path 是相对 repo root 的(gitService.ts:419 同样
                    // 按 repo root 解析)。`root` 拿不到就不给这个入口,
                    // 而不是拼一个错的绝对路径让用户撞 404。
                    val repoRoot = current.root
                    GitDetailBar(
                        entry = entry,
                        api = api,
                        onOpenFile = if (repoRoot != null) {
                            { onOpenFile(absUnderCwd(repoRoot, it)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GitRow(entry: GitStatusEntry, selected: Boolean, onClick: () -> Unit) {
    val badge = entry.xy.trim()
    val badgeColor = when {
        entry.isUntracked -> MaterialTheme.colorScheme.onSurfaceVariant
        badge.contains('D') -> MaterialTheme.colorScheme.error
        badge.contains('A') -> Color(0xFF2E7D32)
        badge.contains('M') -> Color(0xFFC25E00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 26.dp, height = 18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (badge.isEmpty()) "—" else badge,
                color = badgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = entry.path,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ===== git 详情(diff)=====

/**
 * 选中某个改动文件后的 diff 面板。
 *
 * **渲染方式刻意不走 `highlightCode`**:那套是给「整段源码」上色的,而
 * `git diff` 的正文带 `+` / `-` / ` ` 行前缀,直接丢进去会让着色器把前缀
 * 当语法符号。按行首前缀着色才是 diff 该有的样子,也更好读。
 *
 * 未跟踪文件(`xy == "??"`)单独处理:服务端 `git.ts:209` 把 `isUntracked`
 * 硬编码成 false,而 `svcDiff` 跑的是 `git diff`(按定义不含未跟踪文件),
 * 所以会拿到**空 diff 且 ok 为真**。这里不假装有 diff,而是如实说明并给
 * 一个走文件预览的入口。
 */
@Composable
private fun GitDetailBar(
    entry: GitStatusEntry,
    api: SessionToolsApi,
    onOpenFile: ((String) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var diff by remember(entry.path) { mutableStateOf<String?>(null) }
    var loading by remember(entry.path) { mutableStateOf(true) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.path) {
        loading = true
        error = null
        runCatching { api.gitDiff(entry.path, entry.staged) }
            .onSuccess { diff = it.diff }
            .onFailure { error = it.message }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = stringResource(R.string.agent_tools_diff_of, entry.path),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
        // 一条 `xy == "MM"` 同时有暂存区和工作区改动,而 `gitDiff(staged=)`
        // 一次只看一侧。不标出来的话用户会以为 diff 就是全部。
        val side = if (entry.staged) R.string.agent_tools_git_staged
        else R.string.agent_tools_git_worktree
        Text(
            text = stringResource(side),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 4.dp),
        )
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(18.dp)) }

            entry.isUntracked -> Column(Modifier.padding(horizontal = 8.dp)) {
                Text(
                    text = stringResource(R.string.agent_tools_git_untracked),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onOpenFile?.let { open ->
                    TextButton(onClick = { open(entry.path) }) {
                        Text(
                            text = stringResource(R.string.agent_tools_git_view_file),
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            error != null -> Text(
                text = error!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )

            diff.isNullOrBlank() -> Text(
                text = stringResource(R.string.agent_tools_git_no_diff),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                items(diff!!.lines()) { line ->
                    val (bg, fg) = when {
                        line.startsWith("+++") || line.startsWith("---") ->
                            MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent
                        line.startsWith("+") ->
                            Color(0xFF2E7D32).copy(alpha = 0.13f) to Color(0xFF2E7D32)
                        line.startsWith("-") ->
                            MaterialTheme.colorScheme.error.copy(alpha = 0.13f) to
                                MaterialTheme.colorScheme.error
                        line.startsWith("@@") ->
                            Color.Transparent to Color(0xFF7A5AF8)
                        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = line.ifEmpty { " " },
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = fg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

// ===== Bash 栏 =====

/** 一条输出。命令与输出按到达顺序平铺,不做 ANSI / 颜色重放。 */
private data class BashLine(val text: String, val kind: Kind) {
    enum class Kind { Command, Stdout, Stderr, Meta }
}

/**
 * Bash 栏。
 *
 * [lines] / [running] / SSE 订阅都由 [SessionToolsOverlay] 持有并传进来 ——
 * 这个 pane 只管画和收键盘。切栏时 AnimatedContent 会 dispose 本 pane,
 * 状态留在外面才不会被冲掉(理由见调用处的注释)。
 */
@Composable
private fun ToolsBashPane(
    api: SessionToolsApi,
    sessionId: String,
    scope: CoroutineScope,
    lines: MutableList<BashLine>,
    running: Boolean,
    onRunningChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 新输出滚到底。
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    fun run() {
        val command = input.trim()
        if (command.isEmpty() || running) return
        input = ""
        error = null
        onRunningChange(true)
        lines.appendBashLine(BashLine("\$ $command", BashLine.Kind.Command))
        scope.launch {
            // **CancellationException 必须放行,不能进 runCatching**。
            // execute() 跑在 withContext(Dispatchers.IO) 上,它有即时取消:
            // scope 一取消(关面板)就抛 CancellationException,被 runCatching
            // 吞掉之后 onSuccess 不走、onFailure 反而把
            // "StandaloneCoroutine was cancelled" 当成错误显示出来。
            // 协程取消不是错误,让它正常往上抛。
            val result = try {
                api.runCommand(sessionId, command, null)
            } catch (ce: CancellationException) {
                throw ce
            } catch (failure: Throwable) {
                // 409 = 上一条没停掉(bashRepl.ts:61)。这不是崩溃,是状态冲突,
                // 单独给一句话比甩 HTTP 409 强。
                val busy = (failure as? HttpException)?.code == 409
                error = if (busy) {
                    context.getString(R.string.agent_tools_bash_busy)
                } else {
                    failure.message
                }
                onRunningChange(false)
                return@launch
            }
            // 回调里不是 composable context,拿不到 stringResource ——
            // 提前把 Context 抓在闭包外,这里用 getString。
            if (result.signal != null) {
                lines.appendBashLine(
                    BashLine(
                        context.getString(R.string.agent_tools_bash_killed, result.signal),
                        BashLine.Kind.Meta,
                    )
                )
            } else if (result.code != null) {
                lines.appendBashLine(
                    BashLine(
                        context.getString(R.string.agent_tools_bash_exit, result.code),
                        BashLine.Kind.Meta,
                    )
                )
            }
            onRunningChange(false)
        }
    }

    fun stop() {
        scope.launch {
            runCatching { api.abortCommand(sessionId) }
                .onFailure { error = it.message }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                ToolsEmpty(stringResource(R.string.agent_tools_bash_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    state = listState,
                ) {
                    items(lines.size) { idx ->
                        val line = lines[idx]
                        Text(
                            text = line.text,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when (line.kind) {
                                BashLine.Kind.Command -> MaterialTheme.colorScheme.primary
                                BashLine.Kind.Stdout -> MaterialTheme.colorScheme.onSurface
                                BashLine.Kind.Stderr -> MaterialTheme.colorScheme.error
                                BashLine.Kind.Meta -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        if (error != null) {
            Text(
                text = error!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(
                        if (running) {
                            stringResource(R.string.agent_tools_bash_running)
                        } else {
                            stringResource(R.string.agent_tools_bash_placeholder)
                        },
                        fontSize = 13.sp,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                IconTextButton(
                    icon = Lucide.Square,
                    enabled = true,
                    contentDescription = stringResource(R.string.agent_tools_bash_stop_cd),
                ) { stop() }
            } else {
                IconTextButton(
                    icon = Lucide.Play,
                    enabled = input.isNotBlank(),
                    contentDescription = stringResource(R.string.agent_tools_bash_run_cd),
                ) { run() }
            }
        }
    }
}

// ===== 公共小件 =====

@Composable
private fun ToolsEmpty(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IconTextButton(
    icon: ImageVector,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
