// data/SshRepository.kt — SSH host DataStore 持久化
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hotmanxp.lanagent.model.SshHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.sshHostsDataStore by preferencesDataStore(name = "lan_agent_ssh_hosts")
private val SSH_HOSTS_KEY = stringPreferencesKey("ssh_hosts_json")
private val sshHostsSerializer = ListSerializer(SshHost.serializer())
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Reads the persisted SSH host list as a Flow. Empty on first run — there
 * is no compile-time seed (SSH credentials can't ship pre-filled).
 */
fun Context.sshHostsFlow(): Flow<List<SshHost>> = sshHostsDataStore.data.map { prefs ->
    val raw = prefs[SSH_HOSTS_KEY]
    if (raw.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { json.decodeFromString(sshHostsSerializer, raw) }
            .getOrElse { emptyList() }
    }
}

/**
 * Replaces the persisted list. Atomic write — partial failure leaves the
 * previous list intact.
 */
suspend fun Context.saveSshHosts(hosts: List<SshHost>) {
    sshHostsDataStore.edit { prefs ->
        prefs[SSH_HOSTS_KEY] = json.encodeToString(sshHostsSerializer, hosts)
    }
}