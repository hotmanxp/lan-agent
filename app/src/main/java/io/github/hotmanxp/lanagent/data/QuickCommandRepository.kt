// data/QuickCommandRepository.kt — 快捷命令 DataStore 持久化(全局共用一份)
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hotmanxp.lanagent.model.QuickCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Own DataStore file (`lan_agent_quick_commands`) rather than riding on
 * the SSH-host store: the list is global (not per-host) and editing a
 * command must never touch credential rows.
 *
 * Same shape as CardRepository / SshRepository — one JSON string under
 * one key, decoded defensively (a corrupt blob degrades to an empty
 * list, it does not crash the screen).
 */
private val Context.quickCommandsDataStore by preferencesDataStore(name = "lan_agent_quick_commands")
private val QUICK_COMMANDS_KEY = stringPreferencesKey("quick_commands_json")
private val quickCommandsSerializer = ListSerializer(QuickCommand.serializer())
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Persisted quick commands, in user order. Empty on first run. */
fun Context.quickCommandsFlow(): Flow<List<QuickCommand>> = quickCommandsDataStore.data.map { prefs ->
    val raw = prefs[QUICK_COMMANDS_KEY]
    if (raw.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { json.decodeFromString(quickCommandsSerializer, raw) }
            .getOrElse { emptyList() }
    }
}

/** Replaces the persisted list atomically. */
suspend fun Context.saveQuickCommands(commands: List<QuickCommand>) {
    quickCommandsDataStore.edit { prefs ->
        prefs[QUICK_COMMANDS_KEY] = json.encodeToString(quickCommandsSerializer, commands)
    }
}
