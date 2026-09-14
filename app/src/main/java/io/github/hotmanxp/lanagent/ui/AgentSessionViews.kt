// ui/AgentSessionViews.kt — 会话详情页的渲染组件。
//
// 视觉参考 WorkBuddy 手机端对话页:浅灰页底 + 白色卡片;用户消息是**右侧中性
// 浅灰气泡**(不是品牌绿,也不是 IM 常见的尖角尾巴);助手正文靠左**不加气泡**
// (读起来像文档,并且支持 Markdown,见 ui/Markdown.kt);工具调用/思考过程折叠成
// 卡片默认收起;底部是**白色圆角卡**输入条 —— 上排纯文本域,下排工具条
// (语音 / 模型 chip / `+` / 右侧实心圆发送钮)。
//
// 颜色全部走 Material3 语义槽位(见 LanAgentTheme.kt 的注释:surface = 页底灰,
// surfaceContainer* = 卡片白),M3 没有对应槽位的两处(用户气泡底色、发送钮禁用态)
// 走 [LocalWbExtras] —— 所以这里不硬编码任何色值,深浅色自动适配。
package io.github.hotmanxp.lanagent.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/**
 * 用户消息气泡。对齐 WorkBuddy 手机端的三条硬特征:
 *   1. **中性浅灰底**(`#E2E4E3`),不是品牌绿 —— 青绿只给发送按钮。
 *      上一版用 `primaryContainer`(薄荷绿),整屏跟 WorkBuddy 放在一起
 *      一眼就能看出不是同一个产品。
 *   2. **四角同半径**(18dp)。上一版右下角是 4dp 的「小尖角」(IM 常见尾巴),
 *      WorkBuddy 没有尾巴。
 *   3. 气泡**右贴、宽度随内容**,长文可以占到接近满宽(不设 320dp 上限)。
 */
@Composable
internal fun UserBubble(item: AgentItem.UserText) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = LocalWbExtras.current.userBubble,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (item.text.isNotBlank()) {
                    Text(
                        text = item.text,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                // 有 URI 时直接渲染方形缩略图;纯历史(URI 丢了)退化成「N 张图片」文字。
                if (item.attachmentUris.isNotEmpty()) {
                    if (item.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                    UserImageGrid(
                        uris = item.attachmentUris,
                        onTap = { idx -> viewerIndex = idx },
                    )
                } else if (item.attachments > 0) {
                    if (item.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${item.attachments} 张图片",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
        clockOf(item.timestamp)?.let { MetaLine(it, Alignment.End) }

        // 点击缩略图进全屏查看(0.10.2 起):Dialog 全屏,黑底,图片以 Fit 居中,
        // 点空白或系统返回键关闭。
        viewerIndex?.let { idx ->
            FullScreenImageViewer(
                uris = item.attachmentUris,
                startIndex = idx,
                onDismiss = { viewerIndex = null },
            )
        }
    }
}

/**
 * 用户消息气泡内的图片附件网格。
 *
 * 全部是**正方形**缩略图(WorkBuddy 风) —— 96dp 一格,跟输入条附件 chip
 * 尺寸对齐(FlowRow 自动换行,1/2/3/4 张都用同一段代码)。
 *
 * 读图复用 [ImageAttachments.thumbnail](220px JPEG,跟输入条 chip 同一份),
 * 避免在内存里同时持有 220px + 1600px 两份像素。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UserImageGrid(
    uris: List<android.net.Uri>,
    onTap: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        uris.forEachIndexed { idx, uri ->
            UserImageThumb(uri = uri, onTap = { onTap(idx) })
        }
    }
}

@Composable
private fun UserImageThumb(uri: android.net.Uri, onTap: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = ImageAttachments.thumbnail(context, uri)
    }
    val size = 96.dp
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .size(size)
            .clickable(onClick = onTap),
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
}

/**
 * 全屏图片查看器(0.10.2 起,给用户气泡的缩略图用)。
 *
 * `Dialog(usePlatformDefaultWidth = false)` + 黑色 Box 撑满屏幕,图片
 * 用 `ContentScale.Fit` 居中保持原比例。点图片/空白或系统返回键关闭。
 * 多张时按 [startIndex] 起,左右切。
 */
@Composable
private fun FullScreenImageViewer(
    uris: List<android.net.Uri>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var index by remember(startIndex) { mutableStateOf(startIndex) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val context = LocalContext.current
            val bitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null,
                uris.getOrNull(index),
            ) {
                value = uris.getOrNull(index)?.let { ImageAttachments.fullBitmap(context, it) }
            }
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 关闭按钮(右上,白色圆形底,避免被全屏图盖掉视觉)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .navigationBarsPadding(),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(android.R.string.cancel),
                    tint = Color.White,
                )
            }
        }
    }
}

// ===== 助手正文 =====

/**
 * 助手正文。**不加气泡** —— 跟 WorkBuddy 一样让正文像文档一样铺开,Markdown
 * 由 [MarkdownText] 渲染(标题 / 列表 / 代码块 / 表格 / 引用,见 ui/Markdown.kt)。
 */
@Composable
internal fun AssistantBubble(item: AgentItem.AssistantText) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownText(item.text)
        clockOf(item.timestamp)?.let { MetaLine(it, Alignment.Start) }
    }
}

// ===== 思考过程(默认折叠) =====

@Composable
internal fun ThinkingBubble(item: AgentItem.Thinking) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
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
                // 思考过程也走 Markdown 渲染(Agent 的思考里经常带列表/代码),
                // 用 compact 间距 + 次要文字色,视觉上仍从属于正文。
                MarkdownText(
                    markdown = item.text,
                    compact = true,
                    baseStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
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
    // 完成态用中性灰(对齐 onSurfaceVariant / InkMutedLight),不抢品牌色
    // —— 品牌平安橙留给发送按钮 / 主按钮这些真正需要点睛的位置。
    // running 用 tertiary 暖橙,error 用 error 红,差异由状态承担。
    val accent = when {
        item.isError -> MaterialTheme.colorScheme.error
        item.running -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
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
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
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
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth(),
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

// ===== 底部输入条(WorkBuddy 双行白卡) =====

/**
 * 底部输入条。**结构照抄 WorkBuddy 手机端** —— 一张白色圆角卡,里面上下两行:
 *
 *     ┌────────────────────────────────────────────┐
 *     │ 输入消息…                                   │   ← 第一行:纯文本域
 *     │ (波形)  ◍ deepseek-v4.1  ⌄   (＋)      (➤) │   ← 第二行:工具条
 *     └────────────────────────────────────────────┘
 *
 * 与上一版的差异(这版才真的像 WorkBuddy):
 *   1. **两行**,不是一行 —— 上一版把语音图标、文本、`+` 挤在同一行,且文本
 *      在左图标在右,跟 WorkBuddy「文字在上、工具条在下」不是一回事。
 *   2. **模型 chip 进了卡内**(logo + 别名 + `⌄`),不再挂在卡下方的 icon row。
 *   3. **发送钮常驻**在最右:空输入 = 浅灰禁用态圆钮(点了没反应),
 *      有内容 = 品牌平安橙,运行中 = 停止。上一版是「空输入时把发送钮换成 `+`」,
 *      按钮会随输入状态跳变,WorkBuddy 是 `+` 与发送钮**并存**。
 *   4. 卡下方的 icon row(图片/粘贴/模型/更多)**删掉** —— WorkBuddy 没有这行。
 *      功能没丢:图片/粘贴收进 `+` 弹出的面板,模型走卡内 chip。
 *
 * 注:左侧图标是真的语音输入(走系统 SpeechRecognizer,见
 * [VoiceInputController]),设备不支持时**不渲染**而不是画个灰图标占位。
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
     * 只读(不让用户点开空 picker)。
     */
    currentModel: ModelEntry?,
    availableModels: List<ModelEntry>,
    onModelChange: (ModelEntry) -> Unit,
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val modelSheetState = rememberModalBottomSheetState()

    // 有内容才让发送钮「亮」起来。注意:圆钮**始终渲染**,只是禁用态换颜色 ——
    // WorkBuddy 就是这么做的,空输入时按钮不消失,布局因此不跳。
    val hasContent = value.isNotBlank() || attachments.isNotEmpty()
    val reallyCanSend = hasContent && canSend

    // 收音中的呼吸感反馈。没有它的话,用户按下后 1–2 秒内毫无动静
    // (识别服务首字延迟就是这么久),会以为按钮坏了。
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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        ) {
            // 附件条只在有图时出现,挂在白卡上方(不挤占输入宽度)
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

            // 输入白卡。WorkBuddy 的输入区观感 = 「浮在浅灰页面上的一张白色圆角卡」:
            // 24dp 圆角 + 极轻投影,不要描边(描边会让它看起来像输入框,而不是卡片)。
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, RoundedCornerShape(24.dp), clip = false),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
                ) {
                    // ---- 第一行:文本域(占满整行宽度,不与按钮抢横向空间) ----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 10.dp, bottom = 10.dp),
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
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (reallyCanSend) onSend() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp, max = 150.dp),
                        )
                    }

                    // ---- 第二行:工具条(左:语音/模型/附件;右:发送) ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (voice.available) {
                            InputBarIcon(
                                icon = Icons.Default.GraphicEq,
                                contentDescription = stringResource(R.string.agent_input_voice),
                                tint = if (voice.listening) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.alpha(if (voice.listening) voiceAlpha else 1f),
                                onClick = { voice.toggle(value) },
                            )
                        }

                        ModelChip(
                            model = currentModel,
                            enabled = availableModels.isNotEmpty(),
                            onClick = { showModelPicker = true },
                        )

                        // `+` 与发送钮**并存**(WorkBuddy 行为)。附件/粘贴收进
                        // 这个面板,所以卡下方不再需要 icon row。
                        InputBarIcon(
                            icon = Icons.Default.Add,
                            contentDescription = stringResource(R.string.agent_input_more),
                            tint = MaterialTheme.colorScheme.onSurface,
                            onClick = { showMoreMenu = true },
                        )

                        Spacer(Modifier.weight(1f))

                        if (busy) {
                            InputBarCircle(
                                icon = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.agent_input_stop),
                                container = MaterialTheme.colorScheme.error,
                                content = MaterialTheme.colorScheme.onError,
                                enabled = true,
                                onClick = onStop,
                            )
                        } else {
                            InputBarCircle(
                                icon = Icons.Default.ArrowUpward,
                                contentDescription = stringResource(R.string.agent_input_send),
                                container = MaterialTheme.colorScheme.primary,
                                // 禁用态:浅灰底 + 白箭头(对齐 WorkBuddy 空输入时的样子)
                                containerDisabled = LocalWbExtras.current.sendDisabled,
                                content = MaterialTheme.colorScheme.onPrimary,
                                enabled = reallyCanSend,
                                onClick = onSend,
                            )
                        }
                    }
                }
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
 * 卡内工具条上的模型 chip:`(圆点/图标) 别名 ⌄`。
 *
 * 对齐 WorkBuddy:模型选择器是**输入卡的一部分**,而不是输入卡下方的独立入口。
 * 无底色、无描边(点中区靠 clip 后的 ripple 提示),窄屏上别名超长时省略号 ——
 * chip 最大 150dp,不跟发送钮抢宽度。
 *
 * `enabled = false`(拿不到模型列表)时整块变淡且不可点,避免点开一个空 picker。
 */
@Composable
private fun ModelChip(
    model: ModelEntry?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = model?.alias ?: stringResource(R.string.agent_input_model_short),
            fontSize = 14.sp,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 116.dp),
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
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

/** 卡内工具条上的裸图标按钮（无底色），用于语音和 `+`。 */
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
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(21.dp),
        )
    }
}

/**
 * 工具条最右的实心圆钮(发送 / 停止)。
 *
 * 外层 44dp 保点击区、内层 38dp 才是可见圆 —— 内层几乎填满,所以圆看起来是
 * 「整块实心」而不是「按钮里嵌了个小圆」(WorkBuddy 的发送钮就是一个饱满的
 * 实心圆)。永不变尺寸,只有颜色变,所以空输入 → 有内容时布局不跳。
 *
 * 禁用态用 [containerDisabled](浅蓝灰 #E0E3E8 + 白箭头),这是 WorkBuddy
 * 空输入时的样子:按钮**在**,只是按不动。
 */
@Composable
private fun InputBarCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    containerDisabled: androidx.compose.ui.graphics.Color? = null,
) {
    // 用 Box + clickable 而不是 IconButton —— IconButton 的
    // minimumInteractiveComponentSize=48dp 会覆盖 Modifier.size
    // (WebViewScreen 踩过同一个坑)。
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) container
                    else (containerDisabled ?: container.copy(alpha = 0.4f))
                ),
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
