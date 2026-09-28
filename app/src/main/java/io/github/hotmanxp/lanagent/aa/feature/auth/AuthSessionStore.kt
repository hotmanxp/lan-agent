package io.github.hotmanxp.lanagent.aa.feature.auth

import android.content.Context
import io.github.hotmanxp.lanagent.aa.api.AuthResponse
import io.github.hotmanxp.lanagent.aa.api.MobileLoginExchangeResponse
import io.github.hotmanxp.lanagent.aa.api.normalizeServerOrigin

class AuthSessionStore(context: Context) : AuthSessionReader {
    // 0.24.2:原本是明文 getSharedPreferences("agents_anywhere_auth")。lan-agent
    // 已有 EncryptedSharedPreferences + Keystore 的实现(SecureTokenStore),
    // 所以不重复造一份明文凭据存储,直接指过去 —— key 名全部不变,只有 backend
    // 换成加密的。
    private val preferences = io.github.hotmanxp.lanagent.data.SecureTokenStore
        .get(context.applicationContext)
        .encryptedPrefs()

    override fun readServerUrl(): String {
        val stored = preferences.getString(KEY_SERVER_URL, "").orEmpty()
        return normalizeServerOrigin(stored).orEmpty()
    }

    override fun readAccessToken(): String {
        return preferences.getString(KEY_ACCESS_TOKEN, "").orEmpty()
    }

    fun readUserId(): String {
        return preferences.getString(KEY_USER_ID, "").orEmpty()
    }

    fun readRole(): String {
        return preferences.getString(KEY_ROLE, "").orEmpty()
    }

    fun hasAuthSession(): Boolean {
        return readServerUrl().isNotBlank() && readAccessToken().isNotBlank()
    }

    fun saveServerUrl(serverUrl: String) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl.asServerOrigin())
            .apply()
    }

    fun saveAuthSession(serverUrl: String, auth: AuthResponse) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl.asServerOrigin())
            .putString(KEY_ACCESS_TOKEN, auth.accessToken)
            .putString(KEY_TOKEN_TYPE, auth.tokenType)
            .putString(KEY_USER_ID, auth.userId)
            .putString(KEY_ROLE, auth.role)
            .apply()
    }

    fun saveMobileAuthSession(serverUrl: String, exchange: MobileLoginExchangeResponse) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl.asServerOrigin())
            .putString(KEY_ACCESS_TOKEN, exchange.auth.accessToken)
            .putString(KEY_TOKEN_TYPE, exchange.auth.tokenType)
            .putString(KEY_USER_ID, exchange.auth.userId)
            .putString(KEY_ROLE, exchange.auth.role)
            .putString(KEY_REFRESH_TOKEN, exchange.refreshToken)
            .putString(KEY_REFRESH_EXPIRES_AT, exchange.expiresAt)
            .apply()
    }

    @Synchronized
    fun clearAuthSession() {
        clearAuthSessionKeepingServerUrl()
    }

    @Synchronized
    fun clearAuthSessionIfTokenMatches(accessToken: String): Boolean {
        if (!shouldClearAuthSession(readAccessToken(), accessToken)) return false
        clearAuthSessionKeepingServerUrl()
        return true
    }

    private fun clearAuthSessionKeepingServerUrl() {
        val serverUrl = readServerUrl()
        preferences.edit()
            .clear()
            .putString(KEY_SERVER_URL, serverUrl)
            .apply()
    }

    private fun String.asServerOrigin(): String {
        return requireNotNull(normalizeServerOrigin(this)) {
            "Server URL must be an HTTP(S) origin."
        }
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ROLE = "role"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
    }
}

interface AuthSessionReader {
    fun readServerUrl(): String

    fun readAccessToken(): String
}

internal fun shouldClearAuthSession(currentAccessToken: String, unauthorizedAccessToken: String): Boolean {
    return unauthorizedAccessToken.isNotBlank() && currentAccessToken == unauthorizedAccessToken
}
