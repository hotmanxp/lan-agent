package io.github.hotmanxp.lanagent.aa.api

import org.json.JSONObject

/**
 * One zai instance as reported by the host over the `zai:instance` channel.
 *
 * The host returns two different shapes for this same concept: `list` carries
 * the full inventory (`app` / `aaVisible`) while `start` / `stop` echo back a
 * trimmed summary. Both parse into this one type so callers don't branch on
 * which call produced it.
 */
data class RemoteInstance(
    val id: String,
    val name: String,
    val state: String,
    val port: Int? = null,
    val cwd: String = "",
    val app: String? = null,
    val aaVisible: Boolean = true,
) {
    val isRunning: Boolean get() = state == "running"

    companion object {
        fun from(json: JSONObject): RemoteInstance = RemoteInstance(
            id = json.optString("id"),
            name = json.optString("name", json.optString("id")),
            state = json.optString("state", "unknown"),
            port = if (json.has("port") && !json.isNull("port")) json.optInt("port") else null,
            cwd = json.optString("cwd", ""),
            app = if (json.has("app") && !json.isNull("app")) json.optString("app") else null,
            aaVisible = json.optBoolean("aaVisible", true),
        )
    }
}
