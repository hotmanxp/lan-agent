// ui/AgentSessionViews.kt — 会话详情页的渲染组件。
//
// 视觉参考 WorkBuddy 手机端对话页:用户气泡靠右,助手内容靠左无气泡(读起来
// 更像文档),工具调用/思考过程折叠成卡片默认收起,底部是 pill 形输入条
// (圆角 + outline 边框 + 右侧圆形发送/停止按钮)。
//
// 全部状态用 Material3 的 dynamic color scheme,不硬编码亮暗色 —— 与项目
// 既有屏幕(InstanceCard / InstancesScreen)保持一致。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AskQuestion
import io.github.hotmanxp.lanagent.data.AttachedImage
import io.github.hotmanxp.lanagent.data.ImageAttachments
import io.github.hotmanxp.lanagent.data.ModelEntry
import io.github.hotmanxp.lanagent.data.PendingInteraction
import io.github.hotmanxp.lanagent.data.pretty
import io.github.hotmanxp.lanagent.data.QueuedPrompt
import io.github.hotmanxp.lanagent.data.V2Task
import io.github.hotmanxp.lanagent.data.tupleKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CLOCK = SimpleDateFormat("HH:mm", Locale.getDefault())

internal fun clockOf(ms: Long?): String? = ms?.let { CLOCK.format(Date(it)) }

// ===== 用户消息 =====

@Composable
internal fun UserBubble(item: AgentItem.UserText) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (item.text.isNotBlank()) {
                    Text(text = item.text, fontSize = 15.sp, lineHeight = 21.sp)
                }
                if (item.attachments > 0) {
                    if (item.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${item.attachments} 张图片",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
        clockOf(item.timestamp)?.let { MetaLine(it, Alignment.End) }
    }
}

// ===== 助手正文 =====

@Composable
internal fun AssistantBubble(item: AgentItem.AssistantText) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RichText(item.text)
        clockOf(item.timestamp)?.let { MetaLine(it, Alignment.Start) }
    }
}

/**
 * 极简「Markdown 子集」渲染:只认 ``` 围栏代码块,其余按纯文本。不引 markdown
 * 依赖(项目约定不加库),但代码块用等宽字体 + 可横滚的独立底框,读长 Bash
 * 输出和 diff 时不至于糊成一团。
 */
@Composable
private fun RichText(text: String) {
    val segments = remember(text) { splitFences(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is Seg.Text -> if (seg.body.isNotBlank()) {
                    Text(
                        text = seg.body.trim('\n'),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                }

                is Seg.Code -> CodeBox(seg.body, seg.lang)
            }
        }
    }
}

private sealed interface Seg {
    data class Text(val body: String) : Seg
    data class Code(val lang: String?, val body: String) : Seg
}

private fun splitFences(raw: String): List<Seg> {
    if (!raw.contains("```")) return listOf(Seg.Text(raw))
    val out = mutableListOf<Seg>()
    var i = 0
    while (i < raw.length) {
        val start = raw.indexOf("```", i)
        if (start < 0) {
            out += Seg.Text(raw.substring(i))
            break
        }
        if (start > i) out += Seg.Text(raw.substring(i, start))
        val lineEnd = raw.indexOf('\n', start + 3).let { if (it < 0) raw.length else it }
        val lang = raw.substring(start + 3, lineEnd).trim().takeIf { it.isNotEmpty() }
        val end = raw.indexOf("```", lineEnd)
        if (end < 0) {
            // 未闭合围栏(流式输出中间态)→ 整段当代码,先渲染出来
            out += Seg.Code(lang, raw.substring(lineEnd))
            break
        }
        out += Seg.Code(lang, raw.substring(lineEnd, end))
        i = end + 3
    }
    return out
}

@Composable
internal fun CodeBox(body: String, lang: String? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (!lang.isNullOrBlank()) {
                Text(
                    text = lang,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                )
            }
            Text(
                text = body.trim('\n'),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

// ===== 思考过程(默认折叠) =====

@Composable
internal fun ThinkingBubble(item: AgentItem.Thinking) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "思考过程",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${item.text.length} 字",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Text(
                    text = item.text,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}

// ===== 工具调用卡 =====

@Composable
internal fun ToolCallCard(item: AgentItem.ToolCall) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val accent = when {
        item.isError -> MaterialTheme.colorScheme.error
        item.running -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = accent,
                    )
                } else {
                    StatusChip(
                        text = if (item.isError) "失败" else "完成",
                        color = accent,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    item.input?.takeIf { it.isNotBlank() }?.let {
                        SectionLabel("入参")
                        CodeBox(it)
                    }
                    item.output?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("输出")
                        CodeBox(it)
                    }
                    if (item.input.isNullOrBlank() && item.output.isNullOrBlank()) {
                        Text(
                            text = "无入参/输出记录",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
internal fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text = text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

// ===== 运行期提示条 =====

@Composable
internal fun NoteRow(item: AgentItem.Note) {
    val color = if (item.isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = item.text,
            fontSize = 12.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MetaLine(text: String, align: Alignment.Horizontal) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalAlignment = align,
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ===== V2 任务条 =====

@Composable
internal fun V2TaskStrip(tasks: List<V2Task>) {
    if (tasks.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    val done = tasks.count { it.status == "completed" }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "任务清单",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$done/${tasks.size}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    tasks.forEach { t ->
                        val isDone = t.status == "completed"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Icon(
                                imageVector = if (isDone) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowRight,
                                contentDescription = null,
                                tint = if (isDone) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = t.subject.ifBlank { t.id },
                                fontSize = 12.sp,
                                color = if (isDone) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===== 队列 =====

@Composable
internal fun QueueStrip(
    queue: List<QueuedPrompt>,
    onCancel: (QueuedPrompt) -> Unit,
    onSteer: (QueuedPrompt) -> Unit,
) {
    if (queue.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "排队中 (${queue.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            queue.forEach { q ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = q.text,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSteer(q) }) {
                        Text("插入", fontSize = 11.sp)
                    }
                    IconButton(onClick = { onCancel(q) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "取消",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

// ===== ask / permission / approve =====

/**
 * 三种待处理交互统一渲染。ask 需要逐题选选项,permission / approve 是
 * 二选一确认。
 */
@Composable
internal fun PendingCard(
    pending: PendingInteraction,
    busy: Boolean,
    fileContent: String?,
    fileLoading: Boolean,
    onLoadFile: () -> Unit,
    onSubmitAsk: (Map<String, String>) -> Unit,
    onReject: () -> Unit,
    onPermission: (Boolean) -> Unit,
    onApprove: (Boolean) -> Unit,
) {
    when (pending.kind) {
        "ask" -> AskCard(pending.questions, busy, onSubmitAsk, onReject)
        "permission" -> PermissionCard(pending, busy, onPermission)
        "approve" -> ApproveCard(pending, busy, fileContent, fileLoading, onLoadFile, onApprove)
    }
}

@Composable
private fun AskCard(
    questions: List<AskQuestion>,
    busy: Boolean,
    onSubmit: (Map<String, String>) -> Unit,
    onReject: () -> Unit,
) {
    // key = 问题原文(服务端 answers 的 key 就是问题文本)
    var answers by remember(questions) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val allAnswered = questions.all { answers[it.question] != null }

    ActionCard(title = "需要你确认", accent = MaterialTheme.colorScheme.primary) {
        questions.forEach { q ->
            if (q.header.isNotBlank()) {
                Text(
                    text = q.header,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = q.question, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                q.options.forEach { opt ->
                    val selected = answers[q.question] == opt.label
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { answers = answers + (q.question to opt.label) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = opt.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                )
                                opt.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = "提交",
                enabled = allAnswered && !busy,
                busy = busy,
                filled = true,
                onClick = { onSubmit(answers) },
            )
            ActionButton(text = "拒绝", enabled = !busy, busy = false, filled = false, onClick = onReject)
        }
    }
}

@Composable
private fun PermissionCard(
    pending: PendingInteraction,
    busy: Boolean,
    onDecide: (Boolean) -> Unit,
) {
    ActionCard(title = "工具权限确认", accent = MaterialTheme.colorScheme.tertiary) {
        Text(
            text = pending.toolName ?: "未知工具",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        pending.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pending.message?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pending.input?.let { el ->
            val text = el.pretty()
            if (text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                CodeBox(text)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("允许", !busy, busy, true) { onDecide(true) }
            ActionButton("拒绝", !busy, false, false) { onDecide(false) }
        }
    }
}

@Composable
private fun ApproveCard(
    pending: PendingInteraction,
    busy: Boolean,
    fileContent: String?,
    fileLoading: Boolean,
    onLoadFile: () -> Unit,
    onDecide: (Boolean) -> Unit,
) {
    var showFile by remember(pending.toolUseId) { mutableStateOf(false) }
    ActionCard(title = "文档待审核", accent = MaterialTheme.colorScheme.primary) {
        Text(
            text = pending.title ?: "待审核变更",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        pending.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pending.filePath?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            showFile = !showFile
            if (showFile && fileContent == null) onLoadFile()
        }) {
            Text(if (showFile) "收起文件" else "查看文件", fontSize = 12.sp)
        }
        if (showFile) {
            when {
                fileLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.5.dp,
                )

                fileContent != null -> CodeBox(fileContent)
                else -> Text(
                    text = "文件内容不可用",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("批准", !busy, busy, true) { onDecide(true) }
            ActionButton("驳回", !busy, false, false) { onDecide(false) }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(accent, CircleShape),
                )
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    busy: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    if (filled) {
        Surface(
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
            contentColor = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.5.dp,
                        color = LocalContentColor.current,
                    )
                }
                Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ===== 底部输入条(WorkBuddy 单胶囊结构) =====

/**
 * 底部输入条。结构对齐 WorkBuddy 手机端：
 *
 *     ┌───────────────────────────────────────────┐
 *     │  (波形)   发消息给 Agent…             (+)  │
 *     └───────────────────────────────────────────┘
 *
 * **关键是一个 Surface 装下全部**（语音图标 + 文本 + 动作按钮）。上一版是
 * 「独立输入框 + 框外挂一个圆形按钮」，两套圆角并排既占宽度又显碎，窄屏上
 * 文本框被压掉近一半。
 *
 * 右侧按钮按 WorkBuddy 约定三态：
 *   - 有内容 → 发送箭头(primary 实心圆)
 *   - 运行中 → 停止(error 实心圆)
 *   - 空输入 → `+`（无底色），点开「添加图片 / 粘贴剪贴板」
 *
 * 注：截图里 WorkBuddy 的右侧是 `+`、左侧是语音；lan-agent 的左侧图标同样
 * 是语音（`GraphicEq` 波形），但**功能是真的**——走系统 SpeechRecognizer
 * （见 [VoiceInputController]），设备不支持时置灰而不是点了没反应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    attachments: List<AttachedImage>,
    onRemoveAttachment: (AttachedImage) -> Unit,
    voice: VoiceInputController,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPaste: () -> Unit,
    /**
     * 当前 session 的模型 + 后端可选项。picker 直接用 `(providerId, model)`
     * 元组判「当前」(见 [tupleKey]),`availableModels` 为空时 chip 退化为
     * 只读 + 锁头图标(不让用户点开空 picker)。
     */
    currentModel: ModelEntry?,
    availableModels: List<ModelEntry>,
    onModelChange: (ModelEntry) -> Unit,
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val modelSheetState = rememberModalBottomSheetState()

    val hasContent = value.isNotBlank() || attachments.isNotEmpty()

    // 收音中的呼吸感反馈。没有它的话，用户按下后 1–2 秒内毫无动静
    // （识别服务首字延迟就是这么久），会以为按钮坏了。
    val pulse = rememberInfiniteTransition(label = "voice-pulse")
    val voiceAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-alpha",
    )

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // 附件条只在有图时出现，挂在药丸上方（不挤占输入宽度）
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEach { img ->
                        AttachmentChip(image = img, onRemove = { onRemoveAttachment(img) })
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(26.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 模型切换不放在输入框左侧 —— 下方 icon row 已经有「模型」入口,
                    // 重复展示只会让左侧挤掉语音/输入区的横向空间。模型
                    // 入口由 icon row 的 Psychology 项统一承担。

                    // 语音图标:设备识别服务不可用时**直接不渲染**(而不是画个
                    // 灰图标让人以为能用)。原来置灰 + 可点 + 点开 toast 的
                    // 体验被人吐槽过 —— 不可用就别让它出现在界面上占位,
                    // 用户的视觉认知里就不存在这个功能。
                    if (voice.available) {
                        InputBarIcon(
                            icon = Icons.Default.GraphicEq,
                            contentDescription = stringResource(R.string.agent_input_voice),
                            tint = if (voice.listening) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.alpha(if (voice.listening) voiceAlpha else 1f),
                            onClick = { voice.toggle(value) },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 15.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.agent_session_input_hint),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = TextStyle(
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 22.dp, max = 150.dp),
                        )
                    }

                    when {
                        busy -> InputBarCircle(
                            icon = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.agent_input_stop),
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                            enabled = true,
                            onClick = onStop,
                        )

                        hasContent -> InputBarCircle(
                            icon = Icons.Default.ArrowUpward,
                            contentDescription = stringResource(R.string.agent_input_send),
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            enabled = canSend,
                            onClick = onSend,
                        )

                        else -> InputBarIcon(
                            icon = Icons.Default.Add,
                            contentDescription = stringResource(R.string.agent_input_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { showMoreMenu = true },
                        )
                    }
                }
            }

            // 输入卡下方的 icon row(对齐 WorkBuddy 的「快捷入口」行)。
            // 4 个图标:添加图片 / 粘贴 / 切换模型(快捷触发 chip 等价行为) /
            // 「更多」收纳菜单。horizontalArrangement.spacedBy 让按钮均布,
            // 第一枚左贴,最后一枚右贴,中间等间距 —— 视觉密度感更像工具栏
            // 而不是按钮列表。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconRowAction(
                    icon = Icons.Default.AddPhotoAlternate,
                    label = stringResource(R.string.agent_input_add_image_short),
                    enabled = attachments.size < ImageAttachments.MAX_COUNT,
                    onClick = onPickImage,
                )
                IconRowAction(
                    icon = Icons.Default.ContentPaste,
                    label = stringResource(R.string.agent_input_paste_short),
                    onClick = onPaste,
                )
                IconRowAction(
                    icon = Icons.Default.Psychology,
                    label = currentModel?.alias ?: stringResource(R.string.agent_input_model_short),
                    enabled = availableModels.isNotEmpty(),
                    onClick = {
                        if (availableModels.isNotEmpty()) showModelPicker = true
                    },
                )
                IconRowAction(
                    icon = Icons.Default.Add,
                    label = stringResource(R.string.agent_input_more_short),
                    onClick = { showMoreMenu = true },
                )
            }
        }
    }

    if (showMoreMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMoreMenu = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                InputSheetAction(
                    icon = Icons.Default.AddPhotoAlternate,
                    title = stringResource(R.string.agent_input_add_image),
                    subtitle = stringResource(R.string.agent_input_add_image_sub),
                    onClick = {
                        showMoreMenu = false
                        onPickImage()
                    },
                )
                InputSheetAction(
                    icon = Icons.Default.ContentPaste,
                    title = stringResource(R.string.agent_input_paste),
                    subtitle = stringResource(R.string.agent_input_paste_sub),
                    onClick = {
                        showMoreMenu = false
                        onPaste()
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showModelPicker) {
        ModalBottomSheet(
            onDismissRequest = { showModelPicker = false },
            sheetState = modelSheetState,
        ) {
            ModelPickerSheetContent(
                current = currentModel,
                models = availableModels,
                onSelect = { picked ->
                    showModelPicker = false
                    if (picked.tupleKey() != currentModel?.tupleKey()) {
                        onModelChange(picked)
                    }
                },
            )
        }
    }
}

/**
 * icon row 里的单个图标按钮。尺寸对齐语音/发送的视觉量级(22dp icon),
 * 但点中区走 44dp 标准触摸目标,避免窄屏误触。clickable 比 IconButton
 * 更省事:不会撞 minimumInteractiveComponentSize=48dp 覆盖 (webview 踩过)。
 */
@Composable
private fun IconRowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 模型 picker 弹层内容。当前选中按 `(providerId, model)` 元组判等
 * (与 web 端 `ModelPickerPanel.isCurrentEntry` 对齐),列表按 provider
 * 分组以便同一 model 名跨 provider 时不混行。
 */
@Composable
private fun ModelPickerSheetContent(
    current: ModelEntry?,
    models: List<ModelEntry>,
    onSelect: (ModelEntry) -> Unit,
) {
    val groups = remember(models) {
        models.groupBy { it.providerId ?: "default" }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.agent_input_pick_model_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        groups.forEach { (providerId, entries) ->
            if (groups.size > 1 && providerId.isNotBlank() && providerId != "default") {
                Text(
                    text = providerId,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            entries.forEach { entry ->
                val isCurrent = current?.tupleKey() == entry.tupleKey()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(entry) }
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.label ?: entry.alias,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!entry.description.isNullOrBlank()) {
                            Text(
                                text = entry.description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.agent_input_model_current),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 药丸里的裸图标按钮（无底色），用于语音和 `+`。 */
@Composable
private fun InputBarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(22.dp),
        )
    }
}

/** 药丸里的实心圆按钮（发送 / 停止）。 */
@Composable
private fun InputBarCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 外层固定 44dp 保点击区、内层 36dp 才是可见圆。外层不变，
    // 所以三态切换时整条不会左右跳。
    //
    // vertical padding 必须是 4dp：这样「4 + 44」= 52dp，与左侧语音图标
    // 和单行文本区的高度完全一致 —— 三者在 `Bottom` 对齐下圆心都落在
    // 距底 26dp，视觉上就是居中的。padding 给 6dp 会让圆钮比语音图标高
    // 8dp，单行状态下看着比左边的图标「往下沉」。
    // 用 Box + clickable 而不是 IconButton —— IconButton 的
    // minimumInteractiveComponentSize=48dp 会覆盖 Modifier.size
    // (WebViewScreen 踩过同一个坑)。
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (enabled) container else container.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 输入条上方的一枚图片附件缩略图（右上角 X 删除）。 */
@Composable
private fun AttachmentChip(image: AttachedImage, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, image.id) {
        value = ImageAttachments.thumbnail(context, image.uri)
    }

    Box(modifier = Modifier.size(64.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(64.dp),
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.agent_input_attachment_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.agent_input_remove_attachment),
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** `+` 菜单里的一行。 */
@Composable
private fun InputSheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ===== 状态条 =====

@Composable
internal fun StatusBadge(status: AgentRunStatus) {
    val (label, color) = when (status) {
        AgentRunStatus.Idle -> "空闲" to MaterialTheme.colorScheme.onSurfaceVariant
        AgentRunStatus.Streaming -> "运行中" to MaterialTheme.colorScheme.primary
        AgentRunStatus.Retrying -> "重试中" to MaterialTheme.colorScheme.tertiary
        AgentRunStatus.Aborted -> "已中断" to MaterialTheme.colorScheme.outline
        AgentRunStatus.Error -> "出错" to MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (status == AgentRunStatus.Streaming || status == AgentRunStatus.Retrying) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = color)
        } else {
            Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        }
        Text(text = label, fontSize = 11.sp, color = color)
    }
}
