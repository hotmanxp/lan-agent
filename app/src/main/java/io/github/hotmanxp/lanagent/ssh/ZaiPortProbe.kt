// ssh/ZaiPortProbe.kt — 用 OkHttp 探测 zai 端口是否在监听(多端口轮询)
package io.github.hotmanxp.lanagent.ssh

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Default port scan order (fallback when no [preferredPort] supplied).
 * `zai dev` picks 8101 then auto-scans upward, `zai start` picks 9201
 * (no auto-scan). We try both ranges so the probe succeeds regardless
 * of which subcommand the launcher used or which earlier zai supervisor
 * is squatting on the canonical port.
 */
private val DEFAULT_PORT_ORDER: List<Int> = buildList {
    // dev range: 8101 + auto-scan up to 8110
    addAll(8101..8110)
    // start range: 9201 + 9202 (most likely auto-scanned fallback)
    addAll(9201..9202)
}

/**
 * Builds the candidate list: [preferredPort] first (most likely hit),
 * then the [DEFAULT_PORT_ORDER] excluding duplicates.
 */
private fun buildPortList(preferredPort: Int): List<Int> =
    (listOf(preferredPort) + DEFAULT_PORT_ORDER).distinct()

/**
 * Polls the candidate ports in [ports] until one returns 2xx or all
 * [attemptsPerPort] are exhausted per port. Returns the port that
 * succeeded, or null if nothing answered.
 *
 * Used after [ZaiLauncher.start] to confirm the dev server actually came
 * up — `nohup ... & disown` returns the moment the shell forks, which
 * is ~50ms ahead of zai opening its listen socket.
 *
 * [preferredPort] is the port the SshHost was configured with (the one
 * START_COMMAND passes to `zai --lan --port`); we try it first so the
 * common case (port matches) wins in one round trip.
 */
suspend fun waitForZaiReadyAnyPort(
    host: String,
    preferredPort: Int? = null,
    ports: List<Int> = if (preferredPort != null) buildPortList(preferredPort) else DEFAULT_PORT_ORDER,
    attemptsPerPort: Int = 2,
    intervalMs: Long = 700,
    perAttemptTimeoutMs: Long = 1200,
): Int? {
    val client = OkHttpClient.Builder()
        .connectTimeout(perAttemptTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(perAttemptTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(perAttemptTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
    for (port in ports) {
        val url = "http://$host:$port/instances"
        val req = Request.Builder().url(url).get().build()
        repeat(attemptsPerPort) { i ->
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return port
                }
            } catch (_: Throwable) {
                // connection refused / timeout / DNS — try again next loop
            }
            if (i < attemptsPerPort - 1) delay(intervalMs)
        }
    }
    return null
}

/**
 * Builds the URL the SshHostListScreen should navigate to after a
 * successful start. Defaults to /instances on the ready port — most
 * LAN tool users point at the dev manager page.
 */
fun managerUrlForReadyPort(host: String, port: Int): String =
    "http://$host:$port/instances"