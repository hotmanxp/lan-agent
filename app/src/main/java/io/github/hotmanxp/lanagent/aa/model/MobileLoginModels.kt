package io.github.hotmanxp.lanagent.aa.model

data class MobileLoginQrPayload(
    val serverUrl: String,
    val userId: String,
    val loginToken: String,
    val expiresAt: String?,
)
