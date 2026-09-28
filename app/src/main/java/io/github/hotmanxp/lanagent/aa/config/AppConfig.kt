package io.github.hotmanxp.lanagent.aa.config

import io.github.hotmanxp.lanagent.aa.api.normalizeServerOrigin
import io.github.hotmanxp.lanagent.BuildConfig

object AppConfig {
    // Debug builds can override the backend in android/local.properties.
    val OFFICIAL_SERVER_URL: String = BuildConfig.AA_SERVER_URL
    const val DESKTOP_DOWNLOAD_URL = "https://agents-anywhere.com/download"
    // Replace this placeholder with the fixed APK address before distribution.
    const val UPDATE_DOWNLOAD_URL = "https://downloads.example.invalid/agents-anywhere.apk"

    fun isOfficialServer(serverUrl: String): Boolean {
        val officialOrigin = normalizeServerOrigin(OFFICIAL_SERVER_URL) ?: return false
        return normalizeServerOrigin(serverUrl) == officialOrigin
    }
}
