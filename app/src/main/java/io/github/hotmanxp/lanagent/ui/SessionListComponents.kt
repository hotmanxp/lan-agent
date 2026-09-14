// ui/SessionListComponents.kt — 原生 Agent 会话列表的共享 UI 块。
//
// 0.10.5 起 AgentSessionScreen 顶部左侧加抽屉,会话列表有两处使用:
//   - AgentSessionsScreen 整页(列表 + 「新建会话」pill)
//   - AgentSessionScreen 的 ModalNavigationDrawer 内容(同套组件)
//
// 抽到独立文件避免两份 private 重复(子代理 Explore 报告建议)。所有组件
// stateless,只读 props;`AgentSessionMeta` 来自 AgentModels.kt。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentSessionMeta

/** 居中容器 — 错误 / 加载占位共用。 */
@Composable
internal fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * 顶部「新建会话」大按钮 — 对齐 WorkBuddy 抽屉里那颗最醒目的 pill 按钮。
 * 语义是服务端 `POST /api/agent/sessions`:立刻落一条空 transcript 并返回
 * sessionId,用户进详情页就能看到「新会话」而非等第一条消息才出现。
 */
@Composable
internal fun NewSessionPill(busy: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.agent_sessions_new),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 会话列表项 — 标题 + 模型/相对时间 chip + sessionId 单行小字。 */
@Composable
internal fun SessionRow(meta: AgentSessionMeta, now: Long, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.agent_session_untitled),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (meta.model.isNotBlank() && meta.model != "unknown") {
                        StatusChip(text = meta.model, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = formatRelativeAgoMs(meta.updatedAt, now),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // sessionId 用等宽小字打出来 —— 排障时能直接对上服务端日志。
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta.sessionId,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}