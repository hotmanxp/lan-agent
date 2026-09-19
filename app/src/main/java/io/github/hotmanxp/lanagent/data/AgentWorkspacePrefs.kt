// data/AgentWorkspacePrefs.kt — Agent 工作区状态(最近连接的实例 + 会话)。
//
// 为什么单独开一个 DataStore:语义上它跟 `lan_agent_ui_prefs`(刷新按钮位置 /
// 主题)和 `lan_agent_cards`(入口卡片)都不是一回事 —— 它是「任务栏下次打开
// 落在哪」这份业务状态。混进 cards 的 schema 演进会拖累它,混进 ui_prefs 又会
// 让「UI 偏好」这个名字名不副实。
//
// 存四个字段而不是一个 JSON 串:字段少、各自独立演进,坏了也只坏一个;
// 整条读失败(比如被外部改坏)就当没存过,回落「第一个在线实例」,不炸屏。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.agentWorkspaceDataStore by preferencesDataStore(name = "lan_agent_agent_workspace")
private val WS_INSTANCE_ID = stringPreferencesKey("agent_instance_id")
private val WS_INSTANCE_NAME = stringPreferencesKey("agent_instance_name")
private val WS_BASE_URL = stringPreferencesKey("agent_base_url")
private val WS_SESSION_ID = stringPreferencesKey("agent_session_id")

/**
 * 最近一次连接的 opencc 实例 + 停在哪条会话。
 *
 * [baseUrl] 是唯一的**匹配键**(id 会随实例定义重建而变,baseUrl 不会);
 * [instanceName] 只是给首帧渲染用的显示名,不参与匹配。
 */
data class AgentWorkspace(
    val instanceId: String? = null,
    val instanceName: String = "",
    val baseUrl: String = "",
    val sessionId: String? = null,
)

/** 读一次(挂起)。从没存过返回 null。 */
suspend fun Context.readAgentWorkspace(): AgentWorkspace? = agentWorkspaceDataStore.data
    .map { prefs ->
        val base = prefs[WS_BASE_URL].orEmpty()
        if (base.isBlank()) {
            null
        } else {
            AgentWorkspace(
                instanceId = prefs[WS_INSTANCE_ID],
                instanceName = prefs[WS_INSTANCE_NAME].orEmpty(),
                baseUrl = base,
                sessionId = prefs[WS_SESSION_ID],
            )
        }
    }
    .first()

/** Flow 形态(设置栏概览用)。 */
fun Context.agentWorkspaceFlow(): Flow<AgentWorkspace?> = agentWorkspaceDataStore.data.map { prefs ->
    val base = prefs[WS_BASE_URL].orEmpty()
    if (base.isBlank()) {
        null
    } else {
        AgentWorkspace(
            instanceId = prefs[WS_INSTANCE_ID],
            instanceName = prefs[WS_INSTANCE_NAME].orEmpty(),
            baseUrl = base,
            sessionId = prefs[WS_SESSION_ID],
        )
    }
}

/**
 * 写一次。四个字段在同一个 `edit` 事务里落盘 —— 分开写会出现「实例已换、
 * 会话还是旧的」这种半截状态,下次启动就会去新实例找旧会话。
 */
suspend fun Context.saveAgentWorkspace(
    instanceId: String?,
    instanceName: String,
    baseUrl: String,
    sessionId: String?,
) {
    agentWorkspaceDataStore.edit { prefs ->
        if (instanceId.isNullOrBlank()) prefs.remove(WS_INSTANCE_ID) else prefs[WS_INSTANCE_ID] = instanceId
        prefs[WS_INSTANCE_NAME] = instanceName
        prefs[WS_BASE_URL] = baseUrl
        if (sessionId.isNullOrBlank()) prefs.remove(WS_SESSION_ID) else prefs[WS_SESSION_ID] = sessionId
    }
}
