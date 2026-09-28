// data/SecureTokenStore.kt — AndroidX security-crypto 包出来的加密
// SharedPreferences,存 Agents-Anywhere accessToken / mobile-login refreshToken。
//
// **只**为敏感凭据服务(baseUrl / clientId / BuildConfig 兜底仍走普通 DataStore
// 即可,被清了顶多重新配 server 地址)。
//
// Master key 由 Android Keystore 管(`MasterKey.KeyScheme.AES256_GCM`),
// EncryptedSharedPreferences 自己再加 AES256_SIV 包装 key + AES256_GCM 包装
// value。整条链是「系统 Keystore 不可导出 + 双层对称」,root 之后也没法直接读出
// 明文 token —— 这是 lib 推荐用法,不是自己造的加密方案。
//
// **降级路径** —— 极少数设备(老 Keystore 实现 / 设备被 root 后禁了 Tink)在
// `EncryptedSharedPreferences.create` 会抛 `GeneralSecurityException` /
// `KeyStoreException`。App 不该因此崩,直接走明文临时文件兜底(只在这一台
// 设备上,且只在 keystore 不可用期间)。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * 单例的加密凭据存储。`Context` 仅用于 `applicationContext` 拿,实例不持有
 * Activity 引用(避免泄漏)。
 *
 * 用法:`SecureTokenStore.get(applicationContext).putAccessToken(token)`。
 */
class SecureTokenStore private constructor(context: Context) {

    private val backend: SharedPreferences = runCatching {
        // AES256_GCM 是 lib 默认推荐,Keystore 在大部分 Android 9+ 设备上可正常
        // 初始化。`setUserAuthenticationRequired` 不开 —— UI 流程要求 token 即时
        // 可读,不该被屏锁挡住。
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { err ->
        // Keystore 不可用 —— 退到明文 prefs,只在内存里标记一次,日志看清是
        // 哪一类设备(后续可考虑直接不存 / 上报)。
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plaintext", err)
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun getAccessToken(): String? =
        backend.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun putAccessToken(value: String) {
        backend.edit().putString(KEY_ACCESS_TOKEN, value).apply()
    }

    fun clearAccessToken() {
        backend.edit().remove(KEY_ACCESS_TOKEN).apply()
    }

    /** mobile-login exchange 返回的 refreshToken —— 暂存,加密形态。 */
    fun getRefreshToken(): String? =
        backend.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun putRefreshToken(value: String) {
        backend.edit().putString(KEY_REFRESH_TOKEN, value).apply()
    }

    fun clearRefreshToken() {
        backend.edit().remove(KEY_REFRESH_TOKEN).apply()
    }

    /**
     * AA 设备凭据(0.24.0)—— `POST /connectors` 的一次性 `connectorToken`,
     * 配对时(`POST /pairing/claim`)要用,按 connectorId 存。
     *
     * **一次性**:server 只在注册那一刻返回它,之后取不到。丢了只能重新注册
     * 一台设备,所以拿到就必须落盘(见 `AaPairingScreen` 第 ① 步)。
     *
     * @return 没有任何一个 key 时返回空 Map。
     */
    fun allAaConnectorTokens(): Map<String, String> =
        backend.all
            .filterKeys { it.startsWith(KEY_AA_CONNECTOR_TOKEN_PREFIX) }
            .mapNotNull { (k, v) ->
                val id = k.removePrefix(KEY_AA_CONNECTOR_TOKEN_PREFIX)
                (v as? String)?.takeIf { it.isNotBlank() }?.let { id to it }
            }
            .toMap()

    fun getAaConnectorToken(connectorId: String): String? =
        backend.getString(KEY_AA_CONNECTOR_TOKEN_PREFIX + connectorId, null)
            ?.takeIf { it.isNotBlank() }

    fun putAaConnectorToken(connectorId: String, token: String) {
        backend.edit().putString(KEY_AA_CONNECTOR_TOKEN_PREFIX + connectorId, token).apply()
    }

    fun removeAaConnectorToken(connectorId: String) {
        backend.edit().remove(KEY_AA_CONNECTOR_TOKEN_PREFIX + connectorId).apply()
    }

    fun clearAll() {
        backend.edit().clear().apply()
    }

    /**
     * 加密 SharedPreferences 后端本身,给**外部**模块存自己的凭据用。
     *
     * 0.24.2 移植 AA 官方客户端后,它的 `AuthSessionStore` 原本是
     * `getSharedPreferences("agents_anywhere_auth", MODE_PRIVATE)` —— **明文**,
     * accessToken / refreshToken 直接躺在磁盘上。lan-agent 这边早就有加密实现,
     * 所以不去搬一份新的明文存储,而是让它指到这里:key 名全部不变,只有 backend
     * 从明文换成 Keystore 包装的加密 prefs。
     */
    fun encryptedPrefs(): SharedPreferences = backend

    companion object {
        private const val TAG = "SecureTokenStore"
        private const val FILE_NAME = "lan_agent_secure_tokens"
        private const val FALLBACK_FILE_NAME = "lan_agent_secure_tokens_fallback_plain"
        private const val KEY_ACCESS_TOKEN = "agents_anywhere_access_token"
        private const val KEY_REFRESH_TOKEN = "agents_anywhere_refresh_token"
        private const val KEY_AA_CONNECTOR_TOKEN_PREFIX = "agents_anywhere_connector_token_"

        @Volatile
        private var instance: SecureTokenStore? = null

        fun get(context: Context): SecureTokenStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: SecureTokenStore(context.applicationContext).also { instance = it }
            }
        }
    }
}