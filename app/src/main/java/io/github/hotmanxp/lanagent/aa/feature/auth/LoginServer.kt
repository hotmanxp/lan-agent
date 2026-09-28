package io.github.hotmanxp.lanagent.aa.feature.auth

import io.github.hotmanxp.lanagent.aa.api.normalizeServerOrigin
import io.github.hotmanxp.lanagent.aa.api.usesLocalNetworkHost
import java.net.URI
import io.github.hotmanxp.lanagent.BuildConfig

internal data class LoginServerConnection(
    val serverUrl: String,
    val oauthWebOrigin: String,
)

internal fun resolveLoginServer(
    serverUrl: String,
    development: Boolean = BuildConfig.DEBUG,
): LoginServerConnection {
    val origin = requireNotNull(normalizeServerOrigin(serverUrl)) {
        "Enter a valid backend address."
    }
    val uri = URI(origin)
    // The local stack serves the API on 8000 and the login page on 5174.
    // Include LAN hosts and emulator gateways so a debug APK works on a phone.
    val webOrigin = if (development && uri.port == 8000 && usesLocalNetworkHost(uri.host)) {
        URI(uri.scheme, null, uri.host, 5174, null, null, null).toString()
    } else {
        origin
    }
    return LoginServerConnection(serverUrl = origin, oauthWebOrigin = webOrigin)
}
