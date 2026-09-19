// data/UiPrefsRepository.kt — UI 偏好持久化(刷新按钮拖动位置 + 主题模式 +
// 会话精简模式)。单独的 DataStore(不与 cards 混)是为了卡片 schema 演进时
// 不会拖累 UI 偏好。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.uiPrefsDataStore by preferencesDataStore(name = "lan_agent_ui_prefs")
private val REFRESH_BTN_X = floatPreferencesKey("refresh_btn_x")
private val REFRESH_BTN_Y = floatPreferencesKey("refresh_btn_y")
private val THEME_MODE = stringPreferencesKey("theme_mode")
private val COMPACT_TOOLS = booleanPreferencesKey("compact_tools")

/**
 * 主题模式。落盘用 [storageKey] 字符串 —— 枚举名重排/改序都不会让老配置失真,
 * 认不出的值一律回落 [System]。
 */
enum class ThemeMode(val storageKey: String) {
    /** 跟随系统深浅色(默认)。 */
    System("system"),

    /** 强制亮色。 */
    Light("light"),

    /** 强制深色。 */
    Dark("dark");

    companion object {
        fun fromStorage(raw: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == raw } ?: System
    }
}

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

/** 主题模式 Flow。未设置过 = 跟随系统。 */
fun Context.themeModeFlow(): Flow<ThemeMode> = uiPrefsDataStore.data.map { prefs ->
    ThemeMode.fromStorage(prefs[THEME_MODE])
}

/** 写入主题模式。 */
suspend fun Context.saveThemeMode(mode: ThemeMode) {
    uiPrefsDataStore.edit { prefs -> prefs[THEME_MODE] = mode.storageKey }
}

/**
 * 会话「精简模式」开关(0.15.2)。未设置过 = **开** —— 用户装上新版打开会话
 * 就是聚合后的样子,不需要先进设置里找开关。
 *
 * 关掉后会话页退回逐条工具卡(改动前的形态)。
 */
fun Context.compactToolsFlow(): Flow<Boolean> = uiPrefsDataStore.data.map { prefs ->
    prefs[COMPACT_TOOLS] ?: true
}

/** 写入会话精简模式开关。 */
suspend fun Context.saveCompactTools(enabled: Boolean) {
    uiPrefsDataStore.edit { prefs -> prefs[COMPACT_TOOLS] = enabled }
}
