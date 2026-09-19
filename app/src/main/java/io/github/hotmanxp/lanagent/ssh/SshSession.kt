// ssh/SshSession.kt — 持久 SSH 会话:一次认证,命令走 exec 通道,交互走 PTY shell
package io.github.hotmanxp.lanagent.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.hotmanxp.lanagent.model.SshHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

/**
 * Long-lived SSH connection to one host.
 *
 * Why not [JschClient] (single-shot) for everything: password auth costs a
 * full TCP + KEX + auth round trip (~200-600ms on a LAN, worse on Wi-Fi
 * power save). The terminal screen fires many commands in a row — quick
 * command chips especially — so we authenticate once here and open a fresh
 * `exec` channel per command. Channels are cheap; sessions are not.
 *
 * Two ways to run something:
 *  - [exec] — one `exec` channel per command. Clean stdout (+ stderr merged),
 *    real exit status, no shell prompt / echo noise, no TTY mangling. This is
 *    what the command-mode UI and every quick command uses.
 *  - [openShell] — a PTY `shell` channel (see [SshShell]) for the interactive
 *    mode: prompts, sudo, `top`, `vim`. Costs prompt echo + ANSI noise, which
 *    is why it is opt-in rather than the default.
 *
 * Threading: [connect] and [exec] are suspend + IO-dispatched and serialize
 * through [mutex] (JSch's `openChannel` is not safe to call concurrently on
 * one session). A PTY shell is independent — its own reader/writer threads,
 * so a running `top` never blocks a quick command from being sent.
 *
 * Host key checking is off (`StrictHostKeyChecking=no`), same trust model as
 * [JschClient]: LAN tool, no MITM in scope. Callers must [close] on exit —
 * an abandoned session leaks a socket + a reader thread.
 */
class SshSession(
    private val host: SshHost,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val channelTimeoutMs: Int = DEFAULT_CHANNEL_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_CHANNEL_TIMEOUT_MS = 10_000

        /**
         * Default per-command ceiling. Deliberately generous: a `git pull` on
         * a cold repo or a `docker compose up` legitimately takes a minute.
         * Hitting this is NOT an error — the output collected so far is
         * returned with `timedOut = true` and the remote process is left
         * alone (we only close the channel). The UI offers a 停止 button for
         * the "it's obviously stuck" case, which cancels the coroutine.
         */
        const val DEFAULT_EXEC_TIMEOUT_MS = 120_000L

        /**
         * Keepalive: with a NAT / Wi-Fi power-save in the middle, an idle
         * session dies silently and the next command fails with a confusing
         * "session is down". 20s × 3 misses ≈ 60s to detect a dead peer.
         */
        private const val SERVER_ALIVE_INTERVAL_MS = 20_000
        private const val SERVER_ALIVE_COUNT_MAX = 3

        /** Poll cadence while waiting for output / channel close. */
        private const val POLL_INTERVAL_MS = 15L

        /** Grace period for the exit status to land after CHANNEL_CLOSE. */
        private const val EXIT_STATUS_GRACE_MS = 1_000L
    }

    private val jsch = JSch()
    private val mutex = Mutex()

    /**
     * 单线程 daemon,只用来做断开连接的收尾工作(见 [close])。放在类实例上
     * 而不是 companion 上:会话是一次性的,线程不跨会话复用,close() 之后
     * 就没有引用它的东西了(线程本身 daemon,不阻进程退出)。
     */
    private val teardown = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-teardown").apply { isDaemon = true }
    }

    @Volatile
    private var session: Session? = null

    /** True when a live, authenticated session exists. Cheap — poll-safe. */
    fun isConnected(): Boolean = session?.isConnected == true

    /**
     * Connects (or no-ops if already connected). Safe to call from a
     * LaunchedEffect on every recomposition. Throws [SshException] with a
     * user-facing message on connect/auth failure.
     */
    suspend fun connect() {
        mutex.withLock {
            if (isConnected()) return
            doConnect()
        }
    }

    /** Must be called with [mutex] held. Blocking — IO dispatcher required. */
    private suspend fun doConnect(): Session = withContext(Dispatchers.IO) {
        val opened = try {
            jsch.getSession(host.user, host.host, host.port)
        } catch (t: Throwable) {
            throw SshException("无效的 SSH 目标 ${host.user}@${host.host}:${host.port}", t)
        }
        opened.setPassword(host.password)
        opened.setConfig("StrictHostKeyChecking", "no")
        opened.timeout = connectTimeoutMs
        runCatching {
            opened.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
            opened.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
        }
        try {
            opened.connect(connectTimeoutMs)
        } catch (t: Throwable) {
            runCatching { opened.disconnect() }
            throw SshException(authAwareMessage(t), t)
        }
        session = opened
        opened
    }

    /**
     * [JSchException] messages are famously terse ("Auth fail", "socket is
     * not established"). Map the two the user can actually act on; pass the
     * rest through so nothing is hidden.
     */
    private fun authAwareMessage(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        return when {
            raw.contains("Auth fail", ignoreCase = true) ->
                "认证失败:用户名或密码错误(${host.user}@${host.host})"
            raw.contains("refused", ignoreCase = true) ->
                "连接被拒绝:${host.host}:${host.port} 上没跑 sshd(或端口不对)"
            raw.contains("timed out", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) ->
                "连接超时:${host.host}:${host.port} 不可达(防火墙 / IP 变了?)"
            else -> "SSH 连接失败:$raw"
        }
    }

    private suspend fun ensureSession(): Session {
        mutex.withLock {
            val live = session
            if (live != null && live.isConnected) return live
            return doConnect()
        }
    }

    /**
     * Runs [command] and waits for it to finish (or for [timeoutMs]).
     *
     * The command runs in a **non-interactive, non-login** shell — no
     * `.zshrc`, no aliases, and sshd's default PATH (which on macOS lacks
     * `/opt/homebrew/bin`). Quick commands that need either should carry
     * their own `export PATH=...` / `source` prefix; see
     * [ZaiLauncher.PATH_PREFIX] for the pattern.
     *
     * Never blocks the caller's thread: all IO happens on [Dispatchers.IO].
     * Cancelling the calling coroutine disconnects the channel (and the UI's
     * 停止 button relies on that) — the remote process is left running, which
     * is the honest semantic: we can't signal it without a TTY.
     *
     * [onOutput] (optional) receives decoded chunks **as they arrive, on the
     * IO thread** — a 90s `docker compose up` should show progress instead of
     * a spinner, and the return value still carries the authoritative full
     * output so a dropped chunk can never corrupt the transcript. Callers
     * must marshal to the main thread themselves.
     */
    suspend fun exec(
        command: String,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val live = ensureSession()
        val channel = try {
            (live.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                // inputStream = null ⇒ no stdin, and JSch multiplexes stderr
                // onto the same stream so we get one ordered transcript.
                inputStream = null
            }
        } catch (t: Throwable) {
            throw SshException("打开 exec 通道失败:${t.message ?: t.javaClass.simpleName}", t)
        }

        try {
            try {
                channel.connect(channelTimeoutMs)
            } catch (t: Throwable) {
                throw SshException("exec 通道连接失败:${t.message ?: t.javaClass.simpleName}", t)
            }

            val sink = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val input = channel.inputStream
            val deadline = System.currentTimeMillis() + timeoutMs
            // Only allocated when the caller wants live output — a stateful
            // decoder per exec keeps multi-byte chars intact across reads.
            val live = onOutput?.let { Utf8Stream() }
            var timedOut = false

            while (true) {
                // Cancellation must be honoured mid-command, otherwise the
                // 停止 button would only take effect at the next command.
                coroutineContext.ensureActive()

                val available = try {
                    input.available()
                } catch (t: Throwable) {
                    throw SshException("读取输出失败:${t.message ?: t.javaClass.simpleName}", t)
                }

                if (available > 0) {
                    val n = try {
                        input.read(buf, 0, minOf(available, buf.size))
                    } catch (t: Throwable) {
                        throw SshException("读取输出失败:${t.message ?: t.javaClass.simpleName}", t)
                    }
                    if (n > 0) {
                        sink.write(buf, 0, n)
                        live?.let { decoder ->
                            val chunk = decoder.decode(buf, n)
                            if (chunk.isNotEmpty()) onOutput(chunk)
                        }
                    }
                } else if (channel.isClosed) {
                    // Drained: JSch only reports closed after the server's
                    // CHANNEL_CLOSE, which is queued behind any last output.
                    if (input.available() > 0) continue
                    break
                } else if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    break
                } else {
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }

            // Exit status can land a few ms after CHANNEL_CLOSE. JSch's
            // getExitStatus() returns a **primitive int**, not Integer — -1
            // is the "not reported" sentinel, so the wait must compare
            // against -1 (a `== null` check here is dead code and the
            // compiler says so).
            var exitCode = channel.exitStatus
            val exitDeadline = System.currentTimeMillis() + EXIT_STATUS_GRACE_MS
            while (exitCode < 0 && !timedOut && System.currentTimeMillis() < exitDeadline) {
                Thread.sleep(POLL_INTERVAL_MS)
                exitCode = channel.exitStatus
            }

            ExecResult(
                command = command,
                output = decodeUtf8(sink.toByteArray()),
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - start,
                timedOut = timedOut,
            )
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /**
     * Opens a PTY shell channel for the interactive terminal. The returned
     * [SshShell] owns its reader/writer threads — close it when leaving
     * interactive mode, the underlying [SshSession] stays connected so
     * command mode keeps working instantly.
     *
     * [onOutput] / [onClosed] are invoked on background threads; marshal to
     * the UI thread yourself.
     */
    suspend fun openShell(
        cols: Int,
        rows: Int,
        onOutput: (String) -> Unit,
        onClosed: (String?) -> Unit,
    ): SshShell {
        val live = ensureSession()
        val channel = try {
            (live.openChannel("shell") as ChannelShell).apply {
                setPty(true)
                // Not just cosmetics: TERM decides whether the remote sends
                // colour / cursor addressing at all, and `top` / `vim` read
                // COLUMNS/LINES off the pty size below.
                setPtyType("xterm-256color")
                setPtySize(cols.coerceAtLeast(20), rows.coerceAtLeast(5), 0, 0)
            }
        } catch (t: Throwable) {
            throw SshException("打开 shell 通道失败:${t.message ?: t.javaClass.simpleName}", t)
        }

        try {
            channel.connect(channelTimeoutMs)
        } catch (t: Throwable) {
            runCatching { channel.disconnect() }
            throw SshException("shell 通道连接失败:${t.message ?: t.javaClass.simpleName}", t)
        }

        return SshShell(channel, onOutput, onClosed)
    }

    /**
     * Disconnects. Idempotent, and safe to call from the main thread —
     * `Session.disconnect()` writes EOF/CHANNEL_CLOSE to the socket and can
     * block when the peer is wedged or the send buffer is full, so the
     * actual teardown runs on a dedicated daemon thread. Callers
     * (`DisposableEffect.onDispose`, the disconnect button, the back handler)
     * are all UI-thread paths; blocking there is an ANR waiting to happen.
     */
    fun close() {
        val live = session ?: return
        session = null
        runCatching { teardown.execute { runCatching { live.disconnect() } } }
    }
}

/**
 * Streaming UTF-8 → String. A single `String(bytes, UTF_8)` per read is
 * wrong: a 3-byte CJK char straddling two 8KB reads becomes U+FFFD twice.
 * A stateful [CharsetDecoder] keeps the partial sequence between calls.
 */
internal class Utf8Stream {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    fun decode(buf: ByteArray, len: Int): String {
        if (len <= 0) return ""
        val chars = CharBuffer.allocate(len + 4)
        decoder.decode(ByteBuffer.wrap(buf, 0, len), chars, false)
        chars.flip()
        return chars.toString()
    }
}

/** One-shot decode for short, complete buffers (exec output). */
private fun decodeUtf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
