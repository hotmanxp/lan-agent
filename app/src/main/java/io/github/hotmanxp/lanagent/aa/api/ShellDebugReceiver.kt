package io.github.hotmanxp.lanagent.aa.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.hotmanxp.lanagent.aa.feature.auth.AuthSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * debug-only entry point for the AA shell.exec channel. Registered in
 * `AndroidManifest.xml` under the `debuggable` guard so production builds
 * never ship this.
 *
 * Trigger from the host:
 * ```
 *   adb shell am broadcast -a io.github.hotmanxp.lanagent.aa.SHELL \
 *     -n io.github.hotmanxp.lanagent/.aa.api.ShellDebugReceiver
 *   adb logcat -d -s ShellDebug:I  # see the result
 * ```
 *
 * `action` (intent extra, default `list`):
 *   - `list`     → list devices, then `zai:instance list` on the first one
 *   - `start ID` → start a stopped instance by id
 *   - `reject`   → send a non-`zai:instance` command and expect a 400 back
 *
 * Used to confirm `AA Cloud → AA shell.exec → my new reverseDispatch handler`
 * is actually live without having to drive the UI.
 */
class ShellDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AuthSessionStore(context)
        val serverUrl = store.readServerUrl()
        val token = store.readAccessToken()
        val action = intent.getStringExtra("action") ?: "list"
        Log.i(TAG, "debug shell: server=$serverUrl token.len=${token.length} action=$action")
        if (serverUrl.isBlank() || token.isBlank()) {
            Log.w(TAG, "no AA auth session — open lan-agent and sign in once")
            return
        }

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                runAction(serverUrl, token, action)
            } catch (e: Throwable) {
                Log.e(TAG, "shell debug action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runAction(serverUrl: String, token: String, action: String) {
        val devices = DevicesApi().listDevices(serverUrl, token)
        Log.i(TAG, "devices: count=${devices.size} ids=${devices.joinToString { it.id }}")
        val device = devices.firstOrNull() ?: run {
            Log.w(TAG, "no device on this account"); return
        }
        val shell = ShellApi()
        when {
            action == "list" -> {
                val instances = shell.listInstances(serverUrl, token, device.id)
                Log.i(TAG, "instances: count=${instances.size}")
                instances.forEach {
                    Log.i(TAG, "  - id=${it.id} name=${it.name} state=${it.state} port=${it.port} cwd=${it.cwd}")
                }
            }
            action.startsWith("start ") -> {
                val id = action.removePrefix("start ").trim()
                val snap = shell.startInstance(serverUrl, token, device.id, id)
                Log.i(TAG, "started id=$id -> state=${snap.state} port=${snap.port}")
            }
            action == "reject" -> {
                try {
                    shell.exec(serverUrl, token, device.id, "~", "rm -rf /")
                    Log.e(TAG, "SECURITY FAILURE: rm -rf / was accepted")
                } catch (e: Throwable) {
                    Log.i(TAG, "rm -rf / correctly rejected: ${e.message}")
                }
            }
            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    companion object {
        const val TAG = "ShellDebug"
    }
}