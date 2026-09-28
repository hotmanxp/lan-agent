package io.github.hotmanxp.lanagent.aa.feature.sessions

import io.github.hotmanxp.lanagent.aa.api.RemoteProject
import io.github.hotmanxp.lanagent.aa.model.AgentProject

internal fun RemoteProject.toAgentProject(): AgentProject {
    return AgentProject(
        id = id,
        userId = userId,
        connectorId = connectorId,
        name = name,
        workspacePath = workspacePath,
        pinned = pinned,
        pinnedAt = pinnedAt,
        activeSessionCount = activeSessionCount,
        lastActivityAt = lastActivityAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        manuallyCreated = manuallyCreated,
        sidebarSessionCounts = sidebarSessionCounts,
    )
}
