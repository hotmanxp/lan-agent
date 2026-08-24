// data/CardRepository.kt — 卡片 DataStore 持久化
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hotmanxp.lanagent.model.Card
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.cardsDataStore by preferencesDataStore(name = "lan_agent_cards")
private val CARDS_KEY = stringPreferencesKey("cards_json")
private val cardsSerializer = ListSerializer(Card.serializer())
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Reads the persisted card list as a Flow. Emits [defaultCards] on first run
 * (no key set yet), then whatever is stored after that.
 */
fun Context.cardsFlow(): Flow<List<Card>> = cardsDataStore.data.map { prefs ->
    val raw = prefs[CARDS_KEY]
    if (raw.isNullOrBlank()) {
        defaultCards
    } else {
        runCatching { json.decodeFromString(cardsSerializer, raw) }
            .getOrElse { defaultCards }
    }
}

/**
 * Replaces the persisted list. Atomic write — partial failure leaves the
 * previous list intact.
 */
suspend fun Context.saveCards(cards: List<Card>) {
    cardsDataStore.edit { prefs ->
        prefs[CARDS_KEY] = json.encodeToString(cardsSerializer, cards)
    }
}

/** One-shot reset back to the compile-time defaults (kills all user edits). */
suspend fun Context.resetCards() {
    cardsDataStore.edit { it.remove(CARDS_KEY) }
}