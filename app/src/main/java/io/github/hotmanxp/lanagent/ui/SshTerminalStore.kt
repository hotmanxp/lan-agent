// ui/SshTerminalStore.kt — SSH 终端屏状态机:连接 / 命令块 / 交互 shell / JS 桥接
package io.github.hotmanxp.lanagent.ui

import android.util.Base64
import io.github.hotmanxp.lanagent.model.SshHost
import io.github.hotmanxp.lanagent.ssh.SshException
import io.github.hotmanxp.lanagent.ssh.SshSession
import io.github.hotmanxp.lanagent.ssh.SshShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Connection lifecycle for the top-bar status pill. */
internal enum class SshConnState { Idle, Connecting, Connected, Failed, Closed }

/** PTY shell lifecycle (interactive mode only). */
internal enum class SshShellState { None, Opening, Open, Closed, Failed }

/**
 * One executed command, rendered as a block in the output list.
 *
 * [output] grows **live** while the command runs ([SshSession.exec] streams
 * chunks); on completion it is replaced wholesale by the authoritative full
 * output, so a dropped stream chunk can never leave a permanent hole.
 */
internal data class SshCommandBlock(
    val id: String,
    /** Quick-command label when launched from a chip; null for typed input. */
    val label: String?,
    val command: String,
    val output: String = "",
    val running: Boolean = true,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    /** SSH-layer failure (connect/auth/channel/IO) — not a non-zero exit. */
    val error: String? = null,
)

/**
 * Holds everything the terminal screen needs, outside the composition so it
 * survives recomposition (the project has no ViewModel by convention; this
 * mirrors `AgentSessionStore`'s shape).
 *
 * Scope of the two output paths:
 *  - command mode → [blocks]; each entry is one `exec` channel on the shared
 *    [SshSession]. One command at a time on purpose: the input row doubles as
 *    the 停止 button, and interleaved output would be unreadable anyway.
 *  - interactive mode → [SshShell] + xterm.js in a WebView. Output is pushed
 *    to JS through [jsSink] (installed by the WebView composable once the
 *    page is up; anything arriving earlier is buffered in [shellBacklog]).
 *
 * [close] must be called from `onDispose` — an abandoned session leaks a
 * socket plus reader threads.
 */
internal class SshTerminalStore(
    val host: SshHost,
    private val scope: CoroutineScope,
    private val session: SshSession = SshSession(host),
) {
    // ------------------------------------------------------------ 命令模式状态

    var connState by mutableStateOf(SshConnState.Idle)
        private set
    var connError by mutableStateOf<String?>(null)
        private set

    /** Newest last; the screen keeps the viewport pinned to the bottom. */
    val blocks = mutableStateListOf<SshCommandBlock>()

    /** Non-null while a command is in flight — drives the 停止 button. */
    var runningCommandId by mutableStateOf<String?>(null)
        private set

    // ------------------------------------------------------------ 交互模式状态

    var interactive by mutableStateOf(false)
        private set
    var shellState by mutableStateOf(SshShellState.None)
        private set

    private var shell: SshShell? = null
    private var commandJob: Job? = null

    /**
     * JS injection sink, installed by `SshTerminalWebView` when xterm.js
     * signals ready and cleared on dispose. Null ⇒ interactive mode is not
     * on screen, so shell output goes to [shellBacklog] instead.
     */
    private var jsSink: ((String) -> Unit)? = null
    private val shellBacklog = StringBuilder()

    private var ptyCols = 80
    private var ptyRows = 24

    // ---------------------------------------------------------------- 连接

    /** Idempotent; safe to call from `LaunchedEffect(host.id)`. */
    fun connect() {
        if (connState == SshConnState.Connecting || connState == SshConnState.Connected) return
        connState = SshConnState.Connecting
        connError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { session.connect() }
                connState = SshConnState.Connected
            } catch (t: Throwable) {
                connState = SshConnState.Failed
                connError = t.message ?: t.javaClass.simpleName
                // 交互模式里握手失败必须让终端说话,否则用户对着黑屏等
                pushToJs { "wbTerm.banner('${b64Of("连接失败:${connError ?: ""}")}')" }
                shellState = SshShellState.Failed
            }
        }
    }

    fun disconnect() {
        commandJob?.cancel()
        commandJob = null
        runningCommandId = null
        shell?.close()
        shell = null
        shellState = SshShellState.None
        session.close()
        connState = SshConnState.Closed
    }

    // ------------------------------------------------------------ 命令执行

    /**
     * Runs [command] as a new block. No-op while another command runs (the
     * chips and the input row are disabled in that state, this is the
     * belt-and-braces guard).
     */
    fun run(command: String, label: String? = null) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        if (runningCommandId != null) return

        val id = UUID.randomUUID().toString()
        // 插到 index 0(而不是 add 到末尾):输出列表是 reverseLayout,
        // index 0 贴底。新命令插在最前面才能紧贴输入行出现,且视口会自动
        // 跟住它 —— 追加到末尾的话新块会跑到列表顶端、被挤出可视区。
        blocks.add(0, SshCommandBlock(id = id, label = label, command = trimmed))
        runningCommandId = id

        commandJob = scope.launch {
            // 流式回调在 IO 线程,攒够一批再切主线程刷 —— 每条 8KB 输出都切一次
            // 主线程的话,`ls -R` 这种会把 UI 卡住。
            val pending = StringBuilder()
            val dirty = AtomicBoolean(false)

            fun drain() {
                while (true) {
                    dirty.set(false)
                    val chunk = synchronized(pending) {
                        val s = pending.toString()
                        pending.setLength(0)
                        s
                    }
                    if (chunk.isEmpty()) return
                    updateBlock(id) { it.copy(output = it.output + chunk) }
                }
            }

            try {
                if (connState != SshConnState.Connected) {
                    withContext(Dispatchers.IO) { session.connect() }
                    connState = SshConnState.Connected
                    connError = null
                }
                val result = session.exec(trimmed) { chunk ->
                    synchronized(pending) { pending.append(chunk) }
                    if (dirty.compareAndSet(false, true)) {
                        scope.launch(Dispatchers.Main.immediate) { drain() }
                    }
                }
                drain()
                updateBlock(id) {
                    it.copy(
                        output = result.output,
                        running = false,
                        exitCode = result.exitCode,
                        durationMs = result.durationMs,
                        timedOut = result.timedOut,
                    )
                }
            } catch (c: CancellationException) {
                // 用户点了停止。已经收到的输出留着 —— 那正是用户想看的东西。
                updateBlock(id) { it.copy(running = false, cancelled = true) }
                throw c
            } catch (t: Throwable) {
                updateBlock(id) {
                    it.copy(running = false, error = t.message ?: t.javaClass.simpleName)
                }
                if (t is SshException) {
                    connState = SshConnState.Failed
                    connError = t.message
                }
            } finally {
                runningCommandId = null
                commandJob = null
            }
        }
    }

    /** 停止当前命令:取消协程 → 取消 SshSession.exec 的读循环 → 断开通道。 */
    fun stopRunning() {
        commandJob?.cancel()
    }

    fun clearBlocks() = blocks.clear()

    /** 重新执行某个块里的命令(块本身不删,直接新增一条)。 */
    fun rerun(block: SshCommandBlock) = run(block.command, block.label)

    private inline fun updateBlock(id: String, transform: (SshCommandBlock) -> SshCommandBlock) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index >= 0) blocks[index] = transform(blocks[index])
    }

    // ------------------------------------------------------------ 交互模式

    /**
     * Called when switching into interactive mode. The actual channel opens
     * on [onWebViewReady] — xterm.js must exist first, otherwise the login
     * banner (prompt) is written into the void.
     */
    fun requestInteractive() {
        interactive = true
        if (shellState == SshShellState.Open) return
        shellState = SshShellState.Opening
    }

    /** Leaves interactive mode: kills the pty shell, keeps the SSH session. */
    fun leaveInteractive() {
        interactive = false
        shell?.close()
        shell = null
        shellState = SshShellState.None
        shellBacklog.setLength(0)
    }

    /** xterm.js is up. Open the pty with the size the page already measured. */
    fun onWebViewReady(sink: (String) -> Unit) {
        jsSink = sink
        flushBacklog()
        // 页面加载那一刻 AndroidView 可能还没量出尺寸(WebView 初建时为 0),
        // JS 的首次 doFit 会直接 return。这里显式再 fit 一次:量到真实尺寸后
        // 它会回调 resize → 要么此时 shell 已开(直接 window-change),
        // 要么还没开(更新 ptyCols/ptyRows,openShell 用它建 pty)。
        sink("wbTerm.fit()")
        if (!interactive || shellState == SshShellState.Open) return
        openShell()
    }

    fun onWebViewGone() {
        jsSink = null
    }

    private fun openShell() {
        shellState = SshShellState.Opening
        scope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    session.openShell(
                        cols = ptyCols,
                        rows = ptyRows,
                        onOutput = { chunk -> pushShellOutput(chunk) },
                        onClosed = { reason ->
                            scope.launch(Dispatchers.Main.immediate) {
                                shellState = SshShellState.Closed
                                pushToJs {
                                    "wbTerm.banner('${b64Of(reason ?: "连接已断开")}')"
                                }
                            }
                        },
                    )
                }
                shell = opened
                shellState = SshShellState.Open
                // 建通道期间用户可能已经旋转过屏幕 / 收起过键盘,把当前量到的
                // 尺寸再压一次,免得 pty 停在首次那个 80x24 上。走 IO 线程:
                // window-change 是 socket 写,不能在主线程做。
                withContext(Dispatchers.IO) { opened.resize(ptyCols, ptyRows) }
                pushToJs {
                    "wbTerm.banner('${b64Of("已连接 ${host.user}@${host.host}:${host.port}")}')"
                }
            } catch (t: Throwable) {
                shellState = SshShellState.Failed
                pushToJs {
                    "wbTerm.banner('${b64Of("交互通道打开失败:${t.message ?: t.javaClass.simpleName}")}')"
                }
            }
        }
    }

    /** Keystrokes / pastes from xterm.js → pty. */
    fun sendToShell(bytes: ByteArray) {
        shell?.write(bytes)
    }

    /** Viewport change from xterm.js → window-change on the pty. */
    fun onPtyResize(cols: Int, rows: Int) {
        if (cols < 20 || rows < 5) return       // 布局中间态,忽略
        ptyCols = cols
        ptyRows = rows
        shell?.resize(cols, rows)
    }

    private fun pushShellOutput(chunk: String) {
        val b64 = b64Of(chunk)
        scope.launch(Dispatchers.Main.immediate) {
            val sink = jsSink
            if (sink == null) {
                // 页面还没就绪(或已销毁):先攒着,onWebViewReady 时补发
                shellBacklog.append(b64).append('\n')
            } else {
                sink("wbTerm.write('$b64')")
            }
        }
    }

    private fun flushBacklog() {
        val sink = jsSink ?: return
        if (shellBacklog.isEmpty()) return
        val buffered = shellBacklog.toString()
        shellBacklog.setLength(0)
        buffered.split('\n').forEach { line ->
            if (line.isNotEmpty()) sink("wbTerm.write('$line')")
        }
    }

    /**
     * 顶栏的「清屏」按钮:命令模式清块列表,交互模式让 xterm 自己 reset()
     * (同时回到主屏缓冲区,`vim` 退出后残留的备用屏内容也会一起清掉)。
     * 两条路都清,因为用户切模式前后的屏上内容本来就分属两套视图。
     */
    fun clearTerminalView() {
        pushToJs { "wbTerm.reset()" }
    }

    /** 命令模式里连上/断开时,交互终端也给一行 —— 两边状态别不一致。 */
    private fun pushToJs(build: () -> String) {
        val sink = jsSink ?: return
        // build() 里用了 host 字段拼接,构造必须在主线程之外做也无妨(纯字符串)
        scope.launch(Dispatchers.Main.immediate) { sink(build()) }
    }

    fun close() {
        commandJob?.cancel()
        shell?.close()
        shell = null
        session.close()
        jsSink = null
    }
}

/** UTF-8 → base64(NO_WRAP),供 evaluateJavascript 的字符串实参使用。 */
internal fun b64Of(text: String): String =
    Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

/** @JavascriptInterface 传进来的 base64 → 原始字节。 */
internal fun bytesOfB64(b64: String): ByteArray =
    runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrElse { ByteArray(0) }
