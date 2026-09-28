package io.github.hotmanxp.lanagent.aa.feature.sessions

import io.github.hotmanxp.lanagent.aa.model.AgentSession
import java.util.UUID

/**
 * A locally prepared session. The Server session and runtime thread are created only when the
 * user sends the first real message from the conversation screen.
 */
data class NewSessionDraft(
    val connectorId: String,
    val runtime: String,
    val title: String?,
    val cwd: String?,
    val deviceName: String,
    val runtimeLabel: String,
    val knownSessionIds: Set<String>,
    val selections: NewSessionSelections = NewSessionSelections(),
    val runtimeId: String = runtime,
    val runtimeType: String = runtime,
    val runtimeName: String = runtimeLabel,
    val attachmentsEnabled: Boolean = true,
    val localSessionId: String = newLocalSessionId(),
    val projectId: String = "",
) {
    fun previewSession(): AgentSession {
        return AgentSession(
            id = localSessionId,
            connectorId = connectorId,
            projectId = projectId,
            deviceName = deviceName,
            title = title.orEmpty(),
            summary = "",
            cwd = cwd,
            workspaceLabel = cwd.orEmpty(),
            runtime = runtimeType,
            runtimeLabel = runtimeLabel,
            status = io.github.hotmanxp.lanagent.aa.model.SessionStatus.Idle,
            statusLabel = "",
            updatedAtLabel = "",
            metaLabel = "",
            pinned = false,
            archived = false,
            unread = false,
            lastReadSeq = 0,
            takeover = true,
            connectorOnline = true,
            live = true,
            sortKey = "",
            updatedSeq = 0,
            runtimeId = runtimeId,
            runtimeType = runtimeType,
            runtimeName = runtimeName,
        )
    }

    companion object {
        const val LOCAL_NEW_SESSION_ID = "local:new-session"

        fun newLocalSessionId(): String = "$LOCAL_NEW_SESSION_ID:${UUID.randomUUID()}"
    }
}

internal fun NewSessionDraft.firstMessageRequest(
    content: String,
    selections: NewSessionSelections,
    attachments: List<NewSessionAttachmentPart>,
    clientMessageId: String,
): NewSessionCreateDraft = NewSessionCreateDraft(
    connectorId = connectorId,
    runtime = runtimeType,
    title = title,
    cwd = cwd,
    content = content.trim(),
    selections = selections,
    attachments = attachments,
    clientMessageId = clientMessageId,
    knownSessionIds = knownSessionIds,
    runtimeId = runtimeId,
    runtimeType = runtimeType,
    projectId = projectId,
)

/**
 * AA 服务端不会替我们从首条消息派生会话标题(建会话时传什么就存什么),所以客户端得自己算。
 * 规则对齐 zai 的 `deriveTitleFromPrompt`:取第一行、超 50 字截断加省略号。
 *
 * @return 派生出的标题;没有任何文字(纯附件开场)时返回 null,交给列表的兜底文案。
 */
internal fun deriveSessionTitleFromContent(content: String): String? {
    val firstLine = content.trim().lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.isEmpty()) return null
    if (firstLine.length <= SESSION_TITLE_MAX_LEN) return firstLine
    return firstLine.take(SESSION_TITLE_MAX_LEN - 1) + "…"
}

private const val SESSION_TITLE_MAX_LEN = 50
