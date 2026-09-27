// data/AgentsAnywherePrefs.kt — DataStore 持久化 Agents-Anywhere 客户端配置
// (baseUrl / accessToken / clientId)。
//
// clientId 必须**跨进程稳定** —— server 端 ticket 校验把它绑定到 user 上做
// 审计字段(`ws_tickets.py:53-54`),不能用随机 UUID 每启重生成(那样 ticket 就
// 互相覆盖了)。一次性写入,后续只读。
//
// baseUrl / clientId / override flag 走普通 DataStore —— 非敏感字段,被清了
// 顶多重新配 server 地址。
//
// accessToken / mobile-login refreshToken 走 [SecureTokenStore] —— AndroidX
// security-crypto + Keystore 主密钥包装的 EncryptedSharedPreferences(根了
// 之后也读不出明文)。**注意**:EncryptedSharedPreferences 不支持 DataStore
// 风格的 reactive Flow,所以 `accessTokenFlow` 用 `MutableStateFlow` 包一层
// 手动通知 —— UI 写值时同步刷新,够用。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hotmanxp.lanagent.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

private val Context.agentsAnywherePrefs: DataStore<Preferences> by preferencesDataStore(
    name = "lan_agent_agents_anywhere"
)

private val KEY_BASE_URL = stringPreferencesKey("base_url")
private val KEY_CLIENT_ID = stringPreferencesKey("client_id")
/**
 * 用户是否在 UI 里**显式覆盖**过 baseUrl/token。一旦覆盖,BuildConfig 兜底
 * 就不再生效(避免改完 baseUrl 还被 build-time 值偷偷覆盖)。
 *
 * 0.21.0 之前 token 直接放 DataStore —— 现在 token 走 EncryptedSharedPreferences
 * (见 [SecureTokenStore]),这个 flag 仍然留:它标记「用户有没有手动操作过」,
 * UI 上决定是否显示「已使用 BuildConfig 兜底」提示。
 */
private val KEY_USER_OVERRIDE_BASE_URL = booleanPreferencesKey("user_override_base_url")
private val KEY_USER_OVERRIDE_TOKEN = booleanPreferencesKey("user_override_access_token")

class AgentsAnywherePrefs(context: Context) {

    private val appContext = context.applicationContext
    private val secure = SecureTokenStore.get(appContext)

    /**
     * accessToken 的 reactive 镜像 —— EncryptedSharedPreferences 本身不支持
     * DataStore-style reactive Flow,所以这里维护一个 `MutableStateFlow`,
     * 每次 [setAccessToken] / `clear` / 初始化时手动 update,UI 直接 collect。
     *
     * **只在 UI 写值时同步刷新** —— 进程内其他写入路径(比如 mobile-login
     * exchange 成功)也要走 [setAccessToken],否则 UI 状态不会跟上。
     */
    private val _accessToken = MutableStateFlow(
        secure.getAccessToken() ?: BuildConfig.AGENTS_ANYWHERE_TOKEN,
    )

    /** 冷启动时把当前 keystore 里的 token 灌进 StateFlow —— 让 collectAsState 拿到初始值。 */
    init {
        _accessToken.value = secure.getAccessToken() ?: BuildConfig.AGENTS_ANYWHERE_TOKEN
    }

    /**
     * baseUrl 解析优先级:**用户 UI 值** → **BuildConfig 兜底**(来自
     * `local.properties` 里的 `agentsAnywhereBaseUrl`)→ 空串。
     *
     * 不读 DataStore 兜底而读 BuildConfig —— DataStore 改起来反复在 UI 上跳,
     * build-time 兜底只在「用户从未配置过」时生效,语义更稳。
     */
    val baseUrlFlow: Flow<String> = appContext.agentsAnywherePrefs.data.map { prefs ->
        val ui = prefs[KEY_BASE_URL].orEmpty()
        val overridden = prefs[KEY_USER_OVERRIDE_BASE_URL] == true
        when {
            overridden && ui.isNotBlank() -> ui
            !overridden && ui.isNotBlank() -> ui
            else -> BuildConfig.AGENTS_ANYWHERE_BASE_URL.ifBlank { "" }
        }
    }

    /** token 走 EncryptedSharedPreferences;BuildConfig 兜底最低优先级。 */
    val accessTokenFlow: Flow<String> = _accessToken.asStateFlow()

    /** "BuildConfig 有兜底值吗" —— 给 UI 状态条 + 字段 placeholder 用。 */
    val hasBuildConfigDefaults: Boolean
        get() = BuildConfig.AGENTS_ANYWHERE_BASE_URL.isNotBlank() ||
            BuildConfig.AGENTS_ANYWHERE_TOKEN.isNotBlank()

    /**
     * 读 clientId,不存在则生成并立即落盘。**一次性副作用** —— 不要在
     * `Flow.map` 里调它,会反复生成。
     */
    suspend fun clientId(): String {
        val existing = appContext.agentsAnywherePrefs.data
            .map { it[KEY_CLIENT_ID] }
            .first()
        if (!existing.isNullOrBlank()) return existing
        val fresh = "cli_" + UUID.randomUUID().toString().replace("-", "")
        appContext.agentsAnywherePrefs.edit { it[KEY_CLIENT_ID] = fresh }
        return fresh
    }

    /**
     * 用户从 UI 保存 baseUrl → 标上 override 标志(后续 baseUrlFlow 不会回退
     * 到 BuildConfig)。**只有 value 非空时**才标 override —— 否则"清空"等同
     * "撤销覆盖",回到兜底。
     */
    suspend fun setBaseUrl(value: String) {
        val trimmed = value.trim()
        appContext.agentsAnywherePrefs.edit { prefs ->
            prefs[KEY_BASE_URL] = trimmed
            prefs[KEY_USER_OVERRIDE_BASE_URL] = trimmed.isNotBlank()
        }
    }

    /**
     * 用户从 UI 保存 accessToken —— 走 [SecureTokenStore](EncryptedSharedPreferences)。
     * override flag 仍写 DataStore,UI 上「已使用 BuildConfig 兜底」提示要看它。
     *
     * trimmed 空 = 撤销,encrypted 那边 remove。**别传空串下去** —— encrypted
     * store 里 `getAccessToken()` 用 `takeIf { isNotBlank() }` 过滤,空串
     * 跟 null 行为一致,所以这里统一 trim + remove 反而最干净。
     */
    suspend fun setAccessToken(value: String) {
        val trimmed = value.trim()
        appContext.agentsAnywherePrefs.edit { prefs ->
            prefs[KEY_USER_OVERRIDE_TOKEN] = trimmed.isNotBlank()
        }
        if (trimmed.isNotBlank()) {
            secure.putAccessToken(trimmed)
            _accessToken.value = trimmed
        } else {
            secure.clearAccessToken()
            _accessToken.value = BuildConfig.AGENTS_ANYWHERE_TOKEN
        }
    }
}