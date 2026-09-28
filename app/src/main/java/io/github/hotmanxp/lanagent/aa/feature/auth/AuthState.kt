package io.github.hotmanxp.lanagent.aa.feature.auth

data class QrLoginState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

data class QrWaitingState(
    val status: String = "pending_web_confirm",
    val isExchanging: Boolean = false,
    val errorMessage: String? = null,
)
