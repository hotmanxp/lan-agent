// data/AgentsAnywherePrefs.kt — DataStore 持久化 Agents-Anywhere 客户端配置
// (baseUrl / accessToken / clientId)。
//
// clientId 必须**跨进程稳定** —— server 端 ticket 校验把它绑定到 user 上做
// 审计字段(`ws_tickets.py:53-54`),不能用随机 UUID 每启重生成(那样 ticket 就
// 互相覆盖了)。一次性写入,后续只读。
//
// baseUrl / accessToken 允许用户在 UI 改 → DataStore;重启后回填表单。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hotmanxp.lanagent.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.agentsAnywherePrefs: DataStore<Preferences> by preferencesDataStore(
    name = "lan_agent_agents_anywhere"
)

private val KEY_BASE_URL = stringPreferencesKey("base_url")
private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
private val KEY_CLIENT_ID = stringPreferencesKey("client_id")
/**
 * 用户是否在 UI 里**显式覆盖**过 baseUrl/token。一旦覆盖,BuildConfig 兜底
 * 就不再生效(避免改完 baseUrl 还被 build-time 值偷偷覆盖)。
 */
private val KEY_USER_OVERRIDE_BASE_URL = booleanPreferencesKey("user_override_base_url")
private val KEY_USER_OVERRIDE_TOKEN = booleanPreferencesKey("user_override_access_token")

class AgentsAnywherePrefs(private val context: Context) {

    /**
     * baseUrl 解析优先级:**用户 UI 值** → **BuildConfig 兜底**(来自
     * `local.properties` 里的 `agentsAnywhereBaseUrl`)→ 空串。
     *
     * 不读 DataStore 兜底而读 BuildConfig —— DataStore 改起来反复在 UI 上跳,
     * build-time 兜底只在「用户从未配置过」时生效,语义更稳。
     */
    val baseUrlFlow: Flow<String> = context.agentsAnywherePrefs.data.map { prefs ->
        val ui = prefs[KEY_BASE_URL].orEmpty()
        val overridden = prefs[KEY_USER_OVERRIDE_BASE_URL] == true
        when {
            overridden && ui.isNotBlank() -> ui
            !overridden && ui.isNotBlank() -> ui
            else -> BuildConfig.AGENTS_ANYWHERE_BASE_URL.ifBlank { "" }
        }
    }

    /** 同上 —— token 兜底。 */
    val accessTokenFlow: Flow<String> = context.agentsAnywherePrefs.data.map { prefs ->
        val ui = prefs[KEY_ACCESS_TOKEN].orEmpty()
        val overridden = prefs[KEY_USER_OVERRIDE_TOKEN] == true
        when {
            overridden && ui.isNotBlank() -> ui
            !overridden && ui.isNotBlank() -> ui
            else -> BuildConfig.AGENTS_ANYWHERE_TOKEN.ifBlank { "" }
        }
    }

    /** "BuildConfig 有兜底值吗" —— 给 UI 状态条 + 字段 placeholder 用。 */
    val hasBuildConfigDefaults: Boolean
        get() = BuildConfig.AGENTS_ANYWHERE_BASE_URL.isNotBlank() ||
            BuildConfig.AGENTS_ANYWHERE_TOKEN.isNotBlank()

    /**
     * 读 clientId,不存在则生成并立即落盘。**一次性副作用** —— 不要在
     * `Flow.map` 里调它,会反复生成。
     */
    suspend fun clientId(): String {
        val existing = context.agentsAnywherePrefs.data
            .map { it[KEY_CLIENT_ID] }
            .first()
        if (!existing.isNullOrBlank()) return existing
        val fresh = "cli_" + UUID.randomUUID().toString().replace("-", "")
        context.agentsAnywherePrefs.edit { it[KEY_CLIENT_ID] = fresh }
        return fresh
    }

    /**
     * 用户从 UI 保存 baseUrl → 标上 override 标志(后续 baseUrlFlow 不会回退
     * 到 BuildConfig)。**只有 value 非空时**才标 override —— 否则"清空"等同
     * "撤销覆盖",回到兜底。
     */
    suspend fun setBaseUrl(value: String) {
        val trimmed = value.trim()
        context.agentsAnywherePrefs.edit { prefs ->
            prefs[KEY_BASE_URL] = trimmed
            prefs[KEY_USER_OVERRIDE_BASE_URL] = trimmed.isNotBlank()
        }
    }

    suspend fun setAccessToken(value: String) {
        val trimmed = value.trim()
        context.agentsAnywherePrefs.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = trimmed
            prefs[KEY_USER_OVERRIDE_TOKEN] = trimmed.isNotBlank()
        }
    }
}