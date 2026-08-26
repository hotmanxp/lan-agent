// model/SshHost.kt — SSH 主机配置数据模型
package io.github.hotmanxp.lanagent.model

import kotlinx.serialization.Serializable

/**
 * One SSH host entry. `password` is stored in cleartext DataStore alongside
 * the rest of the entry (same trust model as Card.url — LAN tool, no
 * Keystore encryption in Phase 2). Edit / delete by [id]; [name] is the
 * user-facing label shown on the SshHostListScreen row.
 *
 * [zaiPort] is the port the `zai --lan` server should bind to (or probe
 * for). Defaults to 9201 (zai's canonical default). LAN tools usually
 * pick the next free port when 9201 is held by another supervisor — set
 * this explicitly to avoid the collision.
 */
@Serializable
data class SshHost(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String,
    val zaiPort: Int = 9201,
)