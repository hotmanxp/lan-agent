package io.github.hotmanxp.lanagent.aa.api

import org.json.JSONObject

/**
 * Drive zai instance management on a paired host from outside its LAN.
 *
 * AA's server exposes exactly one host-capability RPC that forwards a caller
 * supplied string verbatim — `POST /connectors/{id}/shell/exec` → the
 * `shell.exec` connector method. The host side of that channel is a closed
 * `zai:instance <action>` grammar (see
 * `opencc-web/packages/zai/src/server/services/aaClient/reverseDispatch.ts`),
 * so these helpers only ever emit that grammar; there is no general-purpose
 * "run this shell command" escape hatch to misuse.
 */
class ShellApi(
    private val client: ApiClient = ApiClient(),
) {
    /** Raw channel. Prefer the verb helpers below over building your own command. */
    fun exec(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        root: String,
        command: String,
        cwd: String? = null,
        timeoutMs: Int = 30_000,
    ): JSONObject {
        val body = JSONObject().apply {
            put("command", command)
            put("timeoutMs", timeoutMs)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        val response = client.postJson(
            serverUrl = serverUrl,
            path = "/connectors/${deviceId.urlEncode()}/shell/exec?root=${root.urlEncode()}",
            body = body,
            authorizationToken = authorizationToken,
        )
        return response.optJSONObject("result") ?: JSONObject()
    }

    fun listInstances(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        root: String = "~",
    ): List<RemoteInstance> {
        val result = exec(serverUrl, authorizationToken, deviceId, root, "zai:instance list")
        val array = result.optJSONArray("instances") ?: return emptyList()
        return (0 until array.length()).map { RemoteInstance.from(array.getJSONObject(it)) }
    }

    fun startInstance(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        instanceId: String,
        root: String = "~",
    ): RemoteInstance =
        RemoteInstance.from(
            exec(serverUrl, authorizationToken, deviceId, root, "zai:instance start ${instanceId.token()}")
                .optJSONObject("instance") ?: JSONObject(),
        )

    fun stopInstance(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        instanceId: String,
        root: String = "~",
    ): RemoteInstance =
        RemoteInstance.from(
            exec(serverUrl, authorizationToken, deviceId, root, "zai:instance stop ${instanceId.token()}")
                .optJSONObject("instance") ?: JSONObject(),
        )

    fun restartInstance(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        instanceId: String,
        root: String = "~",
    ): RemoteInstance =
        RemoteInstance.from(
            exec(serverUrl, authorizationToken, deviceId, root, "zai:instance restart ${instanceId.token()}")
                .optJSONObject("instance") ?: JSONObject(),
        )

    fun createInstance(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        name: String,
        cwd: String,
        port: Int? = null,
        root: String = "~",
    ): RemoteInstance {
        val portSuffix = port?.let { " $it" } ?: ""
        return RemoteInstance.from(
            exec(
                serverUrl, authorizationToken, deviceId, root,
                "zai:instance create ${name.token()} ${cwd.token()}$portSuffix",
            ).optJSONObject("instance") ?: JSONObject(),
        )
    }

    fun removeInstance(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        instanceId: String,
        root: String = "~",
    ) {
        exec(serverUrl, authorizationToken, deviceId, root, "zai:instance remove ${instanceId.token()}")
    }
}

/**
 * Keep user-supplied names, cwds and ids inside the single-token slot the host
 * grammar allows. Without this a value containing a space would silently
 * shift every later argument — and a value shaped like a flag would smuggle an
 * extra action into the command.
 */
private fun String.token(): String =
    trim().replace(Regex("\\s+"), "_").ifBlank { "_" }
