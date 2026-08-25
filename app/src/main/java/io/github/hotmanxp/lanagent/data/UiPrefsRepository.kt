// data/UiPrefsRepository.kt — UI 偏好持久化(目前只存刷新按钮拖动位置)。
// 单独的 DataStore(不与 cards 混)是为了卡片 schema 演进时不会拖累 UI 偏好。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.uiPrefsDataStore by preferencesDataStore(name = "lan_agent_ui_prefs")
private val REFRESH_BTN_X = floatPreferencesKey("refresh_btn_x")
private val REFRESH_BTN_Y = floatPreferencesKey("refresh_btn_y")

/**
 * Returns the persisted drag position of the floating refresh button, or
 * null if it has never been moved (caller should fall back to the default
 * right-center placement).
 */
suspend fun Context.readRefreshButtonPos(): Offset? = uiPrefsDataStore.data.map { prefs ->
    val x = prefs[REFRESH_BTN_X]
    val y = prefs[REFRESH_BTN_Y]
    if (x != null && y != null) Offset(x, y) else null
}.first()

/** Persist the drag position. x/y are pixel offsets relative to the WebView box's top-left. */
suspend fun Context.saveRefreshButtonPos(pos: Offset) {
    uiPrefsDataStore.edit { prefs ->
        prefs[REFRESH_BTN_X] = pos.x
        prefs[REFRESH_BTN_Y] = pos.y
    }
}