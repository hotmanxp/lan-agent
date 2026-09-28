package io.github.hotmanxp.lanagent.aa.feature.sessions

import io.github.hotmanxp.lanagent.aa.model.AgentSession
import io.github.hotmanxp.lanagent.aa.model.SessionStatus

internal enum class SessionListIndicator {
    None,
    Busy,
    WaitingApproval,
    Unread,
}

internal fun AgentSession.listIndicator(): SessionListIndicator {
    return when {
        status == SessionStatus.WaitingApproval -> SessionListIndicator.WaitingApproval
        status in setOf(SessionStatus.Running, SessionStatus.Waiting, SessionStatus.Pending) -> SessionListIndicator.Busy
        status == SessionStatus.Idle && unread -> SessionListIndicator.Unread
        else -> SessionListIndicator.None
    }
}
