// ui/SshTerminalScreen.kt — SSH 终端:命令模式(默认)+ 交互模式(xterm.js)
package io.github.hotmanxp.lanagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.quickCommandsFlow
import io.github.hotmanxp.lanagent.data.saveQuickCommands
import io.github.hotmanxp.lanagent.data.sshHostsFlow
import io.github.hotmanxp.lanagent.model.QuickCommand
import io.github.hotmanxp.lanagent.model.SshHost
import kotlinx.coroutines.launch

/**
 * 手机上的 SSH 工作台。两种形态刻意分开,因为它们的取舍完全不同:
 *
 * | | 命令模式(默认) | 交互模式 |
 * |---|---|---|
 * | 通道 | 每条命令一个 `exec` 通道 | 一个 PTY `shell` 通道 |
 * | 输出 | 干净(无提示符/回显),带 exit code + 耗时 | 原始字节流,提示符/颜色/全屏程序都在 |
 * | 快捷命令 | 直接执行,结果显示成块 | 把命令"敲"进终端,由远端 shell 执行 |
 * | 适合 | 看状态、抄输出、跑一条就完事 | sudo / top / vim / 需要连着敲几条 |
 *
 * 连接是共享的:[SshTerminalStore] 里只有一个 [io.github.hotmanxp.lanagent.ssh.SshSession],
 * 切模式不会重新认证(切回交互模式会新开一个 pty —— 也就是新开一个登录 shell,
 * 这是符合直觉的)。
 *
 * 快捷命令列表是**全局**的(`lan_agent_quick_commands` DataStore),不跟主机走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTerminalScreen(
    hostId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // initial = null 用来区分「还没读到 DataStore」和「读到了但没这台主机」——
    // 否则冷启动会先闪一下「主机不存在」。
    val hosts by context.sshHostsFlow().collectAsState(initial = null)
    val host = hosts?.firstOrNull { it.id == hostId }

    when {
        hosts == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        host == null -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.ssh_terminal_host_missing_title)) },
            text = { Text(stringResource(R.string.ssh_terminal_host_missing_body)) },
            confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.ssh_sheet_dismiss)) } },
        )

        else -> SshTerminalContent(host = host, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshTerminalContent(host: SshHost, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(host.id) { SshTerminalStore(host, scope) }
    val commands by context.quickCommandsFlow().collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    var manageOpen by remember { mutableStateOf(false) }
    var confirmRun by remember { mutableStateOf<QuickCommand?>(null) }

    /** 快捷命令/输入的统一入口,按当前模式走不同通道。 */
    fun execute(command: String, label: String?) {
        if (command.isBlank()) return
        if (store.interactive) {
            // 交互模式下"执行"= 把命令敲进终端 + 回车,由远端 shell 自己解析,
            // 这样 alias / cd 之后的相对路径 / 交互式确认都跟真终端一致。
            store.sendToShell((command.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
        } else {
            store.run(command, label)
        }
    }

    LaunchedEffect(host.id) { store.connect() }
    DisposableEffect(host.id) {
        onDispose { store.close() }
    }

    // 交互模式下先把终端收起来,再一次返回才退屏 —— 否则误触返回键
    // 会连 pty 一起关掉,正在 sudo 的输入就没了。
    BackHandler(enabled = store.interactive) { store.leaveInteractive() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(host.name, maxLines = 1)
                        Text(
                            text = "${host.user}@${host.host}:${host.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.webview_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (store.interactive) store.leaveInteractive()
                            else store.requestInteractive()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = stringResource(
                                if (store.interactive) R.string.ssh_terminal_cd_to_command
                                else R.string.ssh_terminal_cd_to_interactive
                            ),
                            tint = if (store.interactive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { manageOpen = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = stringResource(R.string.ssh_quick_manage_title),
                        )
                    }
                    IconButton(
                        onClick = {
                            store.clearBlocks()
                            store.clearTerminalView()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(R.string.ssh_terminal_cd_clear),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStatusRow(
                store = store,
                onRetry = { store.connect() },
            )

            if (store.interactive) {
                // ⚠️ 这里**不要**加 imePadding。软键盘开合时 WebView 必须收缩
                // (xterm 的 ResizeObserver 据此 refit → window-change 同步到 pty),
                // 但收缩的来源只能是下面输入行那一处 imePadding —— Column 的
                // weight(1f) 会把剩余高度分给 WebView。两处都加的话 ime 高度被
                // 扣两次,WebView 被压到接近 0 高,xterm 的 paint 树随之塌掉,
                // 屏幕上只剩 WebView 自己的背景色(实测:键盘弹出瞬间终端整片变白,
                // 收起键盘又恢复)。
                SshTerminalWebView(
                    store = store,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                CommandBlockList(
                    store = store,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            QuickCommandStrip(
                commands = commands,
                enabled = true,
                onRun = { qc -> if (qc.confirm) confirmRun = qc else execute(qc.command, qc.label) },
                onManage = { manageOpen = true },
            )

            // 两种模式都留一条原生输入行:
            // - 命令模式:执行 → exec 通道 → 输出成块;
            // - 交互模式:把整行 + 回车写进 pty,由远端 shell 自己解析。
            //   交互模式**不依赖**它(xterm 自己收键盘),但它是可验证、
            //   且输入长命令时最省事的一条路;软键盘与 xterm 配合出问题时
            //   也有兜底。
            CommandInputRow(
                value = input,
                onValueChange = { input = it },
                running = !store.interactive && store.runningCommandId != null,
                showStop = !store.interactive,
                onRun = {
                    execute(input, null)
                    input = ""
                },
                onStop = { store.stopRunning() },
            )
        }
    }

    if (manageOpen) {
        QuickCommandsSheet(
            commands = commands,
            onDismiss = { manageOpen = false },
            onSave = { next -> scope.launch { context.saveQuickCommands(next) } },
            onRun = { qc ->
                manageOpen = false
                if (qc.confirm) confirmRun = qc else execute(qc.command, qc.label)
            },
        )
    }

    confirmRun?.let { qc ->        AlertDialog(
            onDismissRequest = { confirmRun = null },
            title = { Text(stringResource(R.string.ssh_quick_confirm_title)) },
            text = {
                Column {
                    Text(qc.label, fontWeight = FontWeight.Medium)
                    Text(
                        text = qc.command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRun = null
                    execute(qc.command, qc.label)
                }) { Text(stringResource(R.string.ssh_quick_run)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRun = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------------ 状态行

@Composable
private fun ConnectionStatusRow(store: SshTerminalStore, onRetry: () -> Unit) {
    val dot = when (store.connState) {
        SshConnState.Connected -> MaterialTheme.colorScheme.primary
        SshConnState.Connecting -> MaterialTheme.colorScheme.tertiary
        SshConnState.Failed -> MaterialTheme.colorScheme.error
        SshConnState.Idle, SshConnState.Closed -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (store.connState) {
        SshConnState.Idle -> stringResource(R.string.ssh_terminal_status_idle)
        SshConnState.Connecting -> stringResource(R.string.ssh_terminal_status_connecting)
        SshConnState.Connected -> stringResource(R.string.ssh_terminal_status_connected)
        SshConnState.Failed -> store.connError ?: stringResource(R.string.ssh_terminal_status_failed)
        SshConnState.Closed -> stringResource(R.string.ssh_terminal_status_closed)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (store.connState == SshConnState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
        )
        if (store.connState == SshConnState.Failed || store.connState == SshConnState.Closed) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.ssh_terminal_reconnect))
            }
        }
        Spacer(Modifier.weight(1f))
        // 交互模式额外报 pty 通道自己的状态 —— 连接是好的但 shell 通道
        // 打不开(比如服务端 sshd 禁了 pty)时,这两件事必须能分开看。
        if (store.interactive) {
            val shellLabel = when (store.shellState) {
                SshShellState.None -> ""
                SshShellState.Opening -> stringResource(R.string.ssh_shell_opening)
                SshShellState.Open -> stringResource(R.string.ssh_shell_open)
                SshShellState.Closed -> stringResource(R.string.ssh_shell_closed)
                SshShellState.Failed -> stringResource(R.string.ssh_shell_failed)
            }
            Text(
                text = shellLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 输出块

/** 单条命令的输出上限。手机上没人翻 100KB 输出,留住尾巴就够了。 */
private const val MAX_OUTPUT_CHARS = 20_000

private fun capTail(text: String): String =
    if (text.length <= MAX_OUTPUT_CHARS) {
        text
    } else {
        "… 已截断(共 ${text.length} 字符,只显示最后 $MAX_OUTPUT_CHARS)\n" +
            text.takeLast(MAX_OUTPUT_CHARS)
    }

@Composable
private fun CommandBlockList(store: SshTerminalStore, modifier: Modifier = Modifier) {
    if (store.blocks.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.ssh_terminal_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        // 新块插在 index 0,reverseLayout 让它贴底 —— 流式输出时视口自动
        // 跟住新内容,不用每帧手算滚动偏移(与 AgentSessionScreen 同法)。
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = store.blocks, key = { it.id }) { block ->
            CommandBlockCard(block = block, onRerun = { store.rerun(block) })
        }
    }
}

@Composable
private fun CommandBlockCard(block: SshCommandBlock, onRerun: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                block.label?.let { label ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = "\$ ${block.command}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            if (block.output.isNotBlank()) {
                Box(Modifier.padding(top = 8.dp)) {
                    // label 传 "output" 而不是留空 —— CodeBox 的标签位空着会
                    // 回落成 "code"(它本来是给 Markdown 代码块用的)。
                    // 走 label 而非 lang:命令输出不是某种语言,不该拿去猜着色规则。
                    CodeBox(body = capTail(block.output), label = "output")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    block.running -> {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.ssh_terminal_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    block.error != null -> Text(
                        text = block.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> {
                        val code = block.exitCode
                        Text(
                            text = if (code == null) "" else "exit $code",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (code == 0) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                        block.durationMs?.let {
                            Text(
                                text = " · ${it}ms",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (block.timedOut) {
                            Text(
                                text = " · ${stringResource(R.string.ssh_terminal_timed_out)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (block.cancelled) {
                            Text(
                                text = " · ${stringResource(R.string.ssh_terminal_cancelled)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!block.running) {
                    TextButton(onClick = onRerun) {
                        Text(stringResource(R.string.ssh_terminal_rerun), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 快捷命令

@Composable
private fun QuickCommandStrip(
    commands: List<QuickCommand>,
    enabled: Boolean,
    onRun: (QuickCommand) -> Unit,
    onManage: () -> Unit,
) {
    // 一律用 AssistChip 的默认样式:它的 border/colors 工厂方法在
    // material3 1.3 里每个参数都是必填(没有默认值),为了换个描边色去写
    // 8 个颜色参数不划算 —— 语义配色交给主题。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        commands.forEach { qc ->
            AssistChip(
                onClick = { if (enabled) onRun(qc) },
                label = { Text(qc.label, maxLines = 1) },
                leadingIcon = if (qc.confirm) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = stringResource(R.string.ssh_quick_cd_confirm),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                } else null,
            )
        }
        AssistChip(
            onClick = onManage,
            label = {
                Text(
                    text = stringResource(
                        if (commands.isEmpty()) R.string.ssh_quick_add
                        else R.string.ssh_quick_manage
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

// ------------------------------------------------------------------ 输入行

@Composable
private fun CommandInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    /**
     * 交互模式下没有「正在运行」这个概念(pty 是长命的),按钮永远是
     * 「回车」而不是「停止」—— 但停止在 pty 里也没意义(要中断得发
     * Ctrl+C,那是按键条上那个键)。
     */
    showStop: Boolean = true,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (showStop) R.string.ssh_terminal_input_hint
                            else R.string.ssh_terminal_input_hint_shell
                        ),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (!running && value.isNotBlank()) onRun() },
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp),
                )
            }
            CircleButton(
                icon = if (running && showStop) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (running && showStop) R.string.ssh_terminal_stop else R.string.ssh_terminal_send_line
                ),
                container = if (running && showStop) MaterialTheme.colorScheme.error
                else if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                enabled = (running && showStop) || value.isNotBlank(),
                onClick = { if (running && showStop) onStop() else onRun() },
            )
        }
    }
}

/**
 * 发送/停止圆钮。用 Box + clickable 而不是 IconButton —— IconButton 的
 * `minimumInteractiveComponentSize = 48dp` 会盖掉 `Modifier.size`
 * (WebViewScreen 的浮刷新按钮踩过同一个坑)。
 */
@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    container: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 空态里也会出现的「去添加」出口,放在这里免得 UI 文件互相 import。 */
@Composable
internal fun QuickCommandEmptyHint(onManage: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.ssh_quick_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onManage, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.ssh_quick_add))
        }
    }
}
