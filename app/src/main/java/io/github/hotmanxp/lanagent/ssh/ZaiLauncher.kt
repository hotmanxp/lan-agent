// ssh/ZaiLauncher.kt — zai 启停命令预设(SSH exec 封装)
package io.github.hotmanxp.lanagent.ssh

import io.github.hotmanxp.lanagent.model.SshHost

/**
 * Pre-baked commands for starting / stopping the globally-installed
 * `zai` CLI on the user's Mac over SSH.
 *
 * Design notes:
 *  - All commands are sent to a non-interactive non-login shell (JSch's
 *    `exec` channel), so `.zshrc` / `.bashrc` are NOT sourced. The PATH
 *    available is sshd's default — which on macOS does NOT include
 *    Homebrew's `/opt/homebrew/bin` and therefore no `zai`. The
 *    `source ~/.zshenv 2>/dev/null; ... ~/.bashrc` prefix loads the
 *    user's interactive PATH so `zai` resolves.
 *  - We use the global `zai` binary (no `pnpm`, no `cd` into the
 *    opencc-web checkout) — keeps the command cwd-independent so a
 *    different SSH host without that checkout still works.
 *  - `nohup ... &` + `disown` detaches the zai process from the SSH
 *    shell, so when the channel closes the dev server keeps running.
 *    The `exec` call returns as soon as the parent shell exits
 *    (sub-second) — we do NOT wait for zai itself.
 *  - zai default port is 9201 (see Cards.kt / opencc-web/AGENTS.md).
 */
object ZaiLauncher {

    /** Default zai port when no `-p` is passed. */
    private const val ZAI_PORT = 9201

    /**
     * PATH-loader prefix. JSch's `exec` runs in a non-interactive
     * non-login shell, so `.zshrc` / `.zshenv` are NOT sourced — and
     * sshd's default PATH on macOS does NOT include user-global bin
     * directories like `~/.local/bin` (npm-global install target) or
     * `/opt/homebrew/bin`. We prepend the most common global bin paths
     * explicitly, THEN also source zshenv/bashrc to pick up anything
     * else the user has set.
     *
     * `val` (not `const val`) because Kotlin const strings forbid `$`
     * string templates. `$HOME` and `$PATH` are resolved by the REMOTE
     * shell at exec time — this string is just text until then.
     */
    private val PATH_PREFIX: String =
        "export PATH=\"\$HOME/.local/bin:\$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "source ~/.zshenv 2>/dev/null; source ~/.bashrc 2>/dev/null; "

    /**
     * Start command for a specific host. Uses [SshHost.zaiPort] so each
     * entry can pin its own port (default 9201) — useful when 9201 is
     * already held by another supervisor. Forked with nohup, detached
     * with disown, stdout/stderr redirected to /tmp/zai.log. Returns
     * within ~50ms — zai itself takes ~5s to listen on the chosen port,
     * callers must poll via [waitForZaiReadyAnyPort].
     *
     * Note: `zai --lan` in CLI mode does NOT auto-scan — if [zaiPort]
     * is occupied, zai exits EADDRINUSE. The caller picks the port, and
     * is responsible for not duplicating with existing supervisors.
     */
    fun startCommand(host: SshHost): String = PATH_PREFIX +
        "nohup zai --lan --port ${host.zaiPort} > /tmp/zai.log 2>&1 & " +
        "disown"

    /** Stop command: pkill matches the global zai process args. */
    val STOP_COMMAND = PATH_PREFIX +
        "pkill -f 'zai.*--lan' && echo stopped || echo nothing_to_stop"

    /** Read last 50 lines of the zai log; used for failure diagnostics. */
    val TAIL_LOG_COMMAND = PATH_PREFIX + "tail -50 /tmp/zai.log 2>&1"

    /**
     * Returns the default URL for the Instances manager page on this host.
     * Used to auto-jump after a successful start.
     */
    fun managerUrl(sshHost: SshHost): String =
        "http://${sshHost.host}:$ZAI_PORT/instances"

    /**
     * Executes [startCommand] on the given host. Throws [SshException]
     * if SSH itself fails; otherwise returns the exec result. A non-zero
     * exitCode here means the shell fork failed (e.g. zai not on PATH
     * even after sourcing, or zai --port <port> collided with another
     * process) — callers must check.
     */
    suspend fun start(host: SshHost): ExecResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            JschClient(host.host, host.port, host.user, host.password)
                .exec(startCommand(host))
        }

    /** Executes [STOP_COMMAND] on the given host. */
    suspend fun stop(host: SshHost): ExecResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            JschClient(host.host, host.port, host.user, host.password).exec(STOP_COMMAND)
        }

    /** Executes [TAIL_LOG_COMMAND] on the given host. */
    suspend fun tailLog(host: SshHost): ExecResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            JschClient(host.host, host.port, host.user, host.password).exec(TAIL_LOG_COMMAND)
        }
}