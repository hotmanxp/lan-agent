// ssh/SshShell.kt — PTY shell 通道:读写分离 + 流式 UTF-8 解码
package io.github.hotmanxp.lanagent.ssh

import com.jcraft.jsch.ChannelShell
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** 与终端页共用同一个 logcat tag,排障时 `-s LanAgentTerm` 一把捞全。 */
private const val TAG = "LanAgentTerm"

/**
 * An interactive PTY shell on top of a [SshSession]. Created by
 * [SshSession.openShell] — not constructible otherwise.
 *
 * Two dedicated daemon threads, because the two directions have completely
 * different timing characteristics:
 *
 *  - **reader**: blocks on the channel's piped input stream forever (a shell
 *    is silent until you type). Decodes UTF-8 *statefully* via [Utf8Stream]
 *    so a multi-byte char split across reads doesn't turn into U+FFFD —
 *    terminal output is full of `╭─` box drawing and CJK, both multi-byte.
 *  - **writer**: drains a queue. Keystrokes arrive on the WebView's JS bridge
 *    thread and must never block on a socket write (the pty window can be
 *    full when the remote is busy, e.g. mid-`top`), otherwise the UI thread
 *    stalls behind it.
 *
 * [close] is idempotent and safe from any thread — the terminal screen calls
 * it on mode switch and on `onDispose`. Whoever closes first wins the
 * [onClosed] notification, so the UI doesn't get a spurious "已断开" toast
 * for a disconnect the user just triggered.
 */
class SshShell internal constructor(
    private val channel: ChannelShell,
    private val onOutput: (String) -> Unit,
    private val onClosed: (reason: String?) -> Unit,
) {
    /**
     * Queue poison pill. A zero-length write is a no-op for a pty, so a
     * 0-byte array is unambiguous and avoids exposing a magic string.
     */
    private val poison = ByteArray(0)

    /**
     * 第二个哨兵:先 disconnect 再收工。存在的理由 —— `close()` 会被 UI
     * (切模式 / 返回键 / onDispose)在主线程调用,而 `channel.disconnect()`
     * 要写 socket(CHANNEL_CLOSE),缓冲满时是会阻塞的。于是把所有 IO 都
     * 留在 writer 线程上做,主线程只入队(见 [close])。
     * 按 `===` 身份比较,所以 1 字节的数组不会和真实按键数据混淆。
     */
    private val disconnectMarker = ByteArray(1)

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)

    /**
     * 与 [closed] 分开:close() 是先置 [closed]、再入队 disconnect 标记。
     * reader 可能在这两步之间自己读到 EOF 并抢先入队 poison(远端先关的
     * 情况),writer 看到 poison 就会直接收工 —— 于是 disconnect 被跳过,
     * 通道靠上层 SshSession 兜底才关得掉。这个标志让 writer 在收到 poison
     * 时还能判断「这次是不是用户主动关的」,是就先补一次 disconnect。
     */
    private val closeRequested = AtomicBoolean(false)

    private val reader = Thread({
        val buf = ByteArray(8192)
        val utf8 = Utf8Stream()
        var reason: String? = null
        try {
            val input = channel.inputStream
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    val text = utf8.decode(buf, n)
                    if (text.isNotEmpty()) onOutput(text)
                }
            }
        } catch (t: Throwable) {
            // A close we initiated surfaces as an IO error here; that is not
            // worth reporting, so only keep the reason if we were still open.
            if (!closed.get()) reason = t.message ?: t.javaClass.simpleName
        } finally {
            val weWereOpen = closed.compareAndSet(false, true)
            queue.offer(poison)
            if (weWereOpen) onClosed(reason ?: "shell 已断开")
        }
    }, "ssh-shell-reader").apply { isDaemon = true }

    private val writer = Thread({
        try {
            // ⚠️ 必须只取一次并复用。JSch 的 Channel.getOutputStream() **不是幂等的**:
            // 每次调用都 new 一个带缓冲的包装器(com.jcraft.jsch.Channel$1,内部有
            // dataLen/buffer/packet 三个字段)。那个包装器的 write() 只把字节攒进
            // **自己**的 buffer,真正的 SSH_MSG_CHANNEL_DATA 组装+发送发生在它自己的
            // flush() 里 —— 而 flush() 第一行就是 `if (dataLen == 0) return;`。
            // 于是 `channel.outputStream.write(x); channel.outputStream.flush()` 这种
            // 写法:数据进了实例 A 的 buffer,却去 flush 了全新实例 B(B 的 dataLen 是 0,
            // 直接返回)。字节永远留在 A 里发不出去 —— 不报错、不阻塞,远端一个字节
            // 都收不到,是最难查的那类"静默丢包"。
            val out = channel.outputStream
            while (true) {
                val item = queue.take()
                if (item === poison && !closeRequested.get()) break
                if (item === disconnectMarker || item === poison) {
                    // 先把还在缓冲里的字节冲出去,再断开 —— 否则用户最后敲的
                    // 那几个字符(比如 `exit` 的回车)会跟着 buffer 一起丢。
                    runCatching { out.flush() }
                    runCatching { channel.disconnect() }
                    break
                }
                out.write(item)
                out.flush()
            }
        } catch (_: InterruptedException) {
            // close() interrupts us; nothing to report.
        } catch (t: Throwable) {
            // Channel is gone — the reader thread owns reporting that.
            android.util.Log.w(TAG, "writer died: $t")
        }
    }, "ssh-shell-writer").apply { isDaemon = true }

    init {
        reader.start()
        writer.start()
    }

    val isClosed: Boolean get() = closed.get()

    /** Queues raw bytes (already UTF-8 encoded by the caller). */
    fun write(bytes: ByteArray) {
        if (closed.get() || bytes.isEmpty()) return
        queue.offer(bytes)
    }

    /** Convenience for escape sequences / control chars from the key bar. */
    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * Propagates a viewport change to the remote pty. JSch turns this into a
     * `window-change` request when the channel is already connected, so it is
     * safe to call on every xterm resize — that is exactly what makes `top`
     * and `vim` lay out correctly in portrait.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed.get()) return
        runCatching { channel.setPtySize(cols.coerceAtLeast(20), rows.coerceAtLeast(5), 0, 0) }
    }

    /**
     * Closes the channel and both threads. Does NOT touch the underlying
     * [SshSession] — command mode keeps working after leaving interactive
     * mode.
     *
     * Safe (and cheap) to call from the main thread: the actual
     * `channel.disconnect()` socket write is handed to the writer thread.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeRequested.set(true)
        queue.offer(disconnectMarker)
        // The reader is parked in a blocking read; the writer's disconnect()
        // closes the pipe underneath it, but interrupt() shortens the window.
        runCatching { reader.interrupt() }
    }
}
