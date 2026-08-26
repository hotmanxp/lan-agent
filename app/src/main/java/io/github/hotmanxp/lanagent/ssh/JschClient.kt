// ssh/JschClient.kt — JSch SSH2 客户端封装
package io.github.hotmanxp.lanagent.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

/**
 * SSH exec result. `output` is the merged stdout/stderr stream that JSch
 * returns when `setInputStream(null)` is used (JSch multiplexes both onto
 * the channel input stream). `exitCode` is `-1` if the channel closed
 * without reporting (very rare — usually means timeout / disconnect).
 */
data class ExecResult(
    val command: String,
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
)

/**
 * Thrown for any SSH failure (connect refused, auth failed, channel open
 * error, IO error). Wraps the JSch JSchException + any IO error so callers
 * only need to catch one type. `message` carries enough for the UI sheet;
 * callers should NOT log the underlying cause elsewhere because it may
 * contain the password (JSchException from auth does not, but be safe).
 */
class SshException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Single-shot SSH client: each [exec] call opens a fresh Session, runs one
 * command, then disconnects. No pooling / keep-alive — LAN tool, called
 * rarely (start/stop zai, fetch log). Sessions that outlive the call
 * would leak FDs, so always use the helpers here, not raw JSch.
 *
 * Strict host key checking is off — first connect trusts whatever key the
 * server offers. This is a LAN tool with no MITM threat model; if you
 * later add it, do `JSch.setKnownHosts(stream)` once at app start.
 */
class JschClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val connectTimeoutMs: Int = 10_000,
    private val channelTimeoutMs: Int = 10_000,
) {
    private val jsch = JSch()

    /**
     * Opens a Session. Caller MUST disconnect when done (use [use]).
     * Throws [SshException] on connect / auth failure.
     */
    private fun openSession(): Session {
        val session = try {
            jsch.getSession(user, host, port)
        } catch (t: Throwable) {
            throw SshException("invalid SSH target $user@$host:$port", t)
        }
        session.setPassword(password)
        // LAN tool: trust host key on first connect. Do not enable in any
        // context where MITM is in scope.
        session.setConfig("StrictHostKeyChecking", "no")
        session.timeout = connectTimeoutMs
        try {
            session.connect(connectTimeoutMs)
        } catch (t: Throwable) {
            session.disconnect()
            throw SshException("SSH connect failed: ${t.message ?: t.javaClass.simpleName}", t)
        }
        return session
    }

    /**
     * Runs `command` and waits for it to return. The command itself should
     * be non-blocking on the remote side (e.g. wrap with `nohup ... &`
     * + `disown`) — this only waits for the shell to fork-and-exit, not
     * for the launched process.
     */
    fun exec(command: String): ExecResult {
        val start = System.currentTimeMillis()
        val session = openSession()
        val channel: ChannelExec = try {
            (session.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                // No stdin — JSch merges stderr onto stdout when inputStream
                // is null, which is what we want for short exec output.
                inputStream = null
            }
        } catch (t: Throwable) {
            session.disconnect()
            throw SshException("open channel failed: ${t.message ?: t.javaClass.simpleName}", t)
        }
        try {
            channel.connect(channelTimeoutMs)
            val output = StringBuilder()
            val input = channel.inputStream
            val buf = ByteArray(4096)
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (t: Throwable) {
                    throw SshException("read output failed: ${t.message ?: t.javaClass.simpleName}", t)
                }
                if (n < 0) break
                output.append(buf, 0, n)
            }
            // Wait briefly for the exit status to propagate after EOF.
            val exitDeadline = System.currentTimeMillis() + 1_000
            while (channel.exitStatus == null && System.currentTimeMillis() < exitDeadline) {
                Thread.sleep(50)
            }
            return ExecResult(
                command = command,
                output = output.toString(),
                exitCode = channel.exitStatus ?: -1,
                durationMs = System.currentTimeMillis() - start,
            )
        } finally {
            try { channel.disconnect() } catch (_: Throwable) {}
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }
}