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

import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material.icons.automirrored.rounded.ArrowRight
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Html
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.AskOption
import io.github.hotmanxp.lanagent.data.AskQuestion
import io.github.hotmanxp.lanagent.data.AttachedImage
import io.github.hotmanxp.lanagent.data.BG_RECENT_TTL_MS
import io.github.hotmanxp.lanagent.data.BgAgentTask
import io.github.hotmanxp.lanagent.data.BgBashTask
import io.github.hotmanxp.lanagent.data.FileKind
import io.github.hotmanxp.lanagent.data.HttpException
import io.github.hotmanxp.lanagent.data.ImageAttachments
import io.github.hotmanxp.lanagent.data.ModelEntry
import io.github.hotmanxp.lanagent.data.PendingInteraction
import io.github.hotmanxp.lanagent.data.PresentedFile
import io.github.hotmanxp.lanagent.data.baseName
import io.github.hotmanxp.lanagent.data.classifyByExtension
import io.github.hotmanxp.lanagent.data.fileKindLabel
import io.github.hotmanxp.lanagent.data.isDocument
import io.github.hotmanxp.lanagent.data.parentDir
import io.github.hotmanxp.lanagent.data.pretty
import io.github.hotmanxp.lanagent.data.QueuedPrompt
import io.github.hotmanxp.lanagent.data.SlashItem
import io.github.hotmanxp.lanagent.data.V2Task
import io.github.hotmanxp.lanagent.data.bgDurationLabel
import io.github.hotmanxp.lanagent.data.bgRunning
import io.github.hotmanxp.lanagent.data.bgStatusLabel
import io.github.hotmanxp.lanagent.data.bgTerminal
import io.github.hotmanxp.lanagent.data.displayDetail
import io.github.hotmanxp.lanagent.data.displayName
import io.github.hotmanxp.lanagent.data.filterSlashItems
import io.github.hotmanxp.lanagent.data.parseSlashInput
import io.github.hotmanxp.lanagent.data.tupleKey
import io.github.hotmanxp.lanagent.voice.HoldPhase
import io.github.hotmanxp.lanagent.voice.HoldToTalkCapsule
import io.github.hotmanxp.lanagent.voice.HoldToTalkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                    imageVector = Icons.Rounded.Close,
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
                    imageVector = Icons.Rounded.Psychology,
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
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
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
    // 工具卡默认收起是为了压住入参/输出的噪声。`PresentFile` 不走这里 ——
    // 它有自己的卡片(见 [PresentFileCard]),默认展开、内容直接渲染。
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
                    imageVector = Icons.Rounded.Build,
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
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                val codeLang = toolCodeLabel(item)
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    item.input?.takeIf { it.isNotBlank() }?.let {
                        SectionLabel("入参")
                        CodeBox(it, codeLang)
                    }
                    item.output?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("输出")
                        CodeBox(it, codeLang)
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

// ===== PresentFile 文件卡 =====

/**
 * `PresentFile` 工具的文件卡 —— 会话流里**直接展示一个文件**。
 *
 * 与 web 端 `presentFileRenderer` 同一套语义:Agent 调这个工具的意图就是
 * 「把这个文件摊到你面前」,所以卡片**默认展开、内容直接渲染**(图片缩略图 /
 * 文本前若干行),而不是让你先点一次按钮再等抽屉弹出来。
 *
 * 手机端有渲染器的只有三类(图片 / 文本 / 网页),文档类与二进制**不假装能
 * 预览** —— 给的是「在 Mac 上打开所在目录」。↗ 打开的预览层
 * (见 `ui/FileViewerOverlay.kt`)与卡内内联区共用同一条取字节链路。
 *
 * @param api null = 实例还没解析出来(元数据照常渲染,只是点不开 / 拉不到内容)。
 * @param onOpenFile 点 ↗(或内联缩略图)→ 打开会话面板内的预览层。
 * @param onReveal 点 📂 → `POST /api/fs/reveal`,在 Mac 上打开所在目录。
 */
@Composable
internal fun PresentFileCard(
    item: AgentItem.ToolCall,
    api: AgentApi?,
    onOpenFile: (PresentedFile) -> Unit,
    onReveal: (PresentedFile) -> Unit,
) {
    // 连路径都没解出来(工具还没回 input / 脏数据)→ 退回通用工具卡,至少入参
    // 还看得到,而不是整条消息凭空消失。
    val file = item.file ?: return ToolCallCard(item)

    // 与旧的文件卡一致:内容就是「让你看东西」,默认展开,少一次点击。
    var expanded by remember(item.key) { mutableStateOf(true) }

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
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = fileKindIcon(file.kind),
                    contentDescription = null,
                    tint = if (file.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (file.failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 副标题可能整体为空(冷启动 + 未知类型),空串就别渲染 ——
                    // 否则行里会多出一条空文本占位。
                    fileSubtitle(file).takeIf { it.isNotBlank() }?.let { subtitle ->
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = if (file.failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (item.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (file.viewable && api != null) {
                    CardIconAction(
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        description = "大尺寸预览",
                        onClick = { onOpenFile(file) },
                    )
                }
                if (!file.failed && api != null) {
                    CardIconAction(
                        icon = Icons.Rounded.FolderOpen,
                        description = "在 Mac 上打开所在目录",
                        onClick = { onReveal(file) },
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    file.caption?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    PresentFileBody(file = file, api = api, onOpenFile = onOpenFile)
                    Spacer(Modifier.height(6.dp))
                    // 路径单独一行、等宽小字 —— 「Agent 给我看的是哪个文件」光看
                    // basename 常常不够(同名文件在多个 worktree 里很常见)。
                    Text(
                        text = file.path,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 卡片头部的图标动作。**不用 `IconButton`** —— 它的 48dp 最小交互尺寸会把
 * 卡片头部撑成两行高(与「浮刷新按钮」踩过的同一条,见 AGENTS.md §11)。
 */
@Composable
private fun CardIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

/** 一行说明(不可内联 / 失败 / 过大)。 */
@Composable
private fun NoticeLine(text: String, isError: Boolean = false) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * 卡内内联区 —— 按 kind 分岔(对齐 web 端 §6.4 的表格):
 * 文本 → 前 [INLINE_TEXT_LINES] 行 + 卡内展开;图片 → 采样缩略图(点开大图);
 * 网页 / 矢量图 → 一行说明 + ↗;文档类 / 二进制 / 过大 / 失败 → 一行说明。
 */
@Composable
private fun PresentFileBody(
    file: PresentedFile,
    api: AgentApi?,
    onOpenFile: (PresentedFile) -> Unit,
) {
    when {
        file.failed -> NoticeLine(file.error.orEmpty().ifBlank { "文件不可用" }, isError = true)

        file.tooLarge -> NoticeLine(tooLargeNotice(file))

        file.kind == FileKind.Image && !file.isVectorImage -> InlineFileImage(
            file = file,
            api = api,
            onOpenFile = onOpenFile,
        )

        file.kind == FileKind.Text -> InlineFileText(file = file, api = api)

        file.kind == FileKind.Image -> NoticeLine("矢量图（SVG）· 点 ↗ 查看")

        file.kind == FileKind.Html -> NoticeLine("网页 · 点 ↗ 查看")

        file.kind.isDocument ->
            NoticeLine("${fileKindLabel(file.kind)} · 手机端不支持预览，可用 📂 在 Mac 上打开")

        else -> NoticeLine("此文件类型不支持内联预览")
    }
}

/** 超过该类上限时的说明 —— 指向真正能用的那条路。 */
private fun tooLargeNotice(file: PresentedFile): String {
    val size = file.size?.let { formatBytes(it) }
    return when (file.kind) {
        FileKind.Image ->
            "图片${size?.let { " $it" }.orEmpty()}超过 10 MiB，请用 📂 在 Mac 上打开"

        FileKind.Text, FileKind.Html ->
            "${fileKindLabel(file.kind)}${size?.let { " $it" }.orEmpty()}超过 1 MiB，暂不内联预览，可用 📂 在 Mac 上打开"

        else -> "${fileKindLabel(file.kind)}过大，可用 📂 在 Mac 上打开"
    }
}

/**
 * 内联图片。字节走 `GET /api/fs/raw`(≤ [IMAGE_MAX_BYTES],服务端保证),
 * 在 IO 线程**采样解码** —— 一张 2 MiB 的 PNG 解出来可能就是 4000×4000,
 * `ARGB_8888` 下约 64 MB,不采样几张就能把低端机的堆推爆。
 *
 * 超过 [INLINE_THUMB_MAX_BYTES] 的整图不进内联区(字节 + 解码内存都翻倍),
 * 但仍可从 ↗ 打开大图 —— 预览层是黑底全屏,那才是看大图的地方。
 */
@Composable
private fun InlineFileImage(
    file: PresentedFile,
    api: AgentApi?,
    onOpenFile: (PresentedFile) -> Unit,
) {
    if (api == null) {
        NoticeLine("实例未连接，暂时读不到图片")
        return
    }
    val size = file.size
    if (size != null && size > INLINE_THUMB_MAX_BYTES) {
        NoticeLine("图片较大（${formatBytes(size)}）· 点 ↗ 查看")
        return
    }

    val state by produceState<ThumbState>(ThumbState.Loading, api, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeSampled(api.rawFile(file.path)) ?: throw IllegalStateException("图片解码失败")
            }.fold(
                onSuccess = { ThumbState.Ok(it) },
                onFailure = { ThumbState.Failed(previewErrorMessage(it)) },
            )
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 240.dp)
            .clickable { onOpenFile(file) },
    ) {
        when (val s = state) {
            is ThumbState.Loading -> Box(Modifier.fillMaxWidth().height(96.dp), Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp)
            }

            is ThumbState.Ok -> Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            )

            is ThumbState.Failed -> Text(
                text = s.message,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 内联文本。`/api/fs/preview` 只回 ≤ 1 MiB 的文本,超限走 [tooLargeNotice] 那条
 * 分支(根本不发请求)。
 *
 * **默认只渲染前 [INLINE_TEXT_LINES] 行**:会话流里的卡片不该被一个 800 行的
 * 文件撑成一屏。展开后交给既有渲染(`.md` 走自研 Markdown,其余等宽代码块)。
 */
@Composable
private fun InlineFileText(file: PresentedFile, api: AgentApi?) {
    if (api == null) {
        NoticeLine("实例未连接，暂时读不到内容")
        return
    }
    var expanded by remember(file.path) { mutableStateOf(false) }
    var retry by remember(file.path) { mutableStateOf(0) }

    val state by produceState<TextState>(TextState.Loading, api, file.path, retry) {
        value = withContext(Dispatchers.IO) {
            runCatching { api.previewFile(file.path).content }
                .fold(
                    onSuccess = { c ->
                        if (c.isNullOrEmpty()) TextState.Failed("服务端未返回内容") else TextState.Ok(c)
                    },
                    onFailure = { TextState.Failed(previewErrorMessage(it)) },
                )
        }
    }

    when (val s = state) {
        is TextState.Loading -> Text(
            text = "加载中…",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is TextState.Failed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = s.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text("重试", fontSize = 12.sp) }
        }

        is TextState.Ok -> {
            val lines = s.content.lines()
            Column {
                if (expanded) {
                    if (isMarkdownPath(file.path)) {
                        MarkdownText(s.content)
                    } else {
                        CodeBox(s.content, codeLanguageLabel(file.path))
                    }
                    TextButton(onClick = { expanded = false }) { Text("收起", fontSize = 12.sp) }
                } else {
                    Text(
                        text = lines.take(INLINE_TEXT_LINES).joinToString("\n"),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (lines.size > INLINE_TEXT_LINES) {
                        TextButton(onClick = { expanded = true }) {
                            Text("展开全部（${lines.size} 行）", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface TextState {
    data object Loading : TextState
    data class Ok(val content: String) : TextState
    data class Failed(val message: String) : TextState
}

private fun isMarkdownPath(path: String): Boolean =
    path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }

// ===== 本轮产物 =====

/**
 * 「本轮产物」块 —— 每轮对话结束后,在消息段末尾列出这一轮**生成 / 修改过的
 * 文件**(清单由 `deriveTurnArtifacts` 从消息流派生,见 `ui/TurnArtifacts.kt`)。
 *
 * 为什么值得单独一块:用户不必翻找散落在 transcript 里的 `Edit` / `Write` 调用,
 * 也不必指望模型记得调 `PresentFile`。对齐 web 端 `TurnArtifactsBlock`。
 *
 * 默认展开,文件数 > [ARTIFACTS_AUTO_COLLAPSE] 时默认折叠(块头仍显示总数)。
 * 折叠态存在**本组件**的 remember 里,而块 key 取该轮首条用户消息 —— 后续消息
 * append 不会重挂载,用户的展开/收起意图不会被重置。
 */
@Composable
internal fun TurnArtifactsBlock(
    files: List<ArtifactFile>,
    onOpenFile: (PresentedFile) -> Unit,
) {
    var open by remember { mutableStateOf(files.size <= ARTIFACTS_AUTO_COLLAPSE) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "本轮产物 · ${files.size} 个文件",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
            if (open) {
                Spacer(Modifier.height(2.dp))
                files.forEach { ArtifactRow(file = it, onOpenFile = onOpenFile) }
            }
        }
    }
}

/**
 * 产物清单的一行:类型图标 + 文件名 + 上级目录 + `×N` + 徽标。整行可点 →
 * 会话内的预览层(与 PresentFile 卡片同一个 [onOpenFile])。
 *
 * 这里只有**路径**(不落盘、不预检):解析不出来的路径点开后会由预览层显示
 * 「文件不存在或已被移动」,比在列表里预检更省事也更准。
 */
@Composable
private fun ArtifactRow(file: ArtifactFile, onOpenFile: (PresentedFile) -> Unit) {
    val kind = classifyByExtension(file.path)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                onOpenFile(PresentedFile(path = file.path, name = baseName(file.path), kind = kind))
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = fileKindIcon(kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = baseName(file.path),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            parentDir(file.path).takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (file.count > 1) {
            Text(
                text = "×${file.count}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ArtifactBadge(file = file)
    }
}

/** 徽标:`写入` 走绿系 / `编辑` 走紫系(与 web 端同一对色)。 */
@Composable
private fun ArtifactBadge(file: ArtifactFile) {
    val color = if (file.written) ARTIFACT_WRITTEN else ARTIFACT_EDITED
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Text(
            text = file.label,
            fontSize = 10.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

private val ARTIFACT_WRITTEN = Color(0xFF22C55E)
private val ARTIFACT_EDITED = Color(0xFFA78BFA)

/** 一行的次要说明:`1.2 MB · 3 分钟前 · 图片`;失败时直接给错误原因。 */
private fun fileSubtitle(file: PresentedFile): String {
    if (file.failed) return file.error.orEmpty()
    val parts = ArrayList<String>(3)
    file.size?.let { parts.add(formatBytes(it)) }
    file.mtime?.takeIf { it > 0L }?.let { parts.add(formatRelativeAgoMs(it)) }
    parts.add(fileKindLabel(file.kind))
    return parts.joinToString(" · ")
}

private fun fileKindIcon(kind: FileKind) = when (kind) {
    FileKind.Image -> Icons.Rounded.Photo
    FileKind.Html -> Icons.Rounded.Html
    FileKind.Text -> Icons.Rounded.Description
    FileKind.Docx -> Icons.Rounded.Article
    FileKind.Sheet -> Icons.Rounded.TableChart
    FileKind.Ppt -> Icons.Rounded.Slideshow
    FileKind.Pdf -> Icons.Rounded.PictureAsPdf
    FileKind.LegacyOffice -> Icons.AutoMirrored.Rounded.InsertDriveFile
    FileKind.Binary -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** 字节数。小数固定用 `.`(默认 Locale 会在部分地区给逗号)。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * 预览失败 → 给人看的一句话。413 / 415 / 404 与「网络不通」对用户的含义完全
 * 不同,不能都写成「加载失败」。
 */
internal fun previewErrorMessage(t: Throwable): String = when {
    t is HttpException && t.code == 413 -> "文件过大，不支持内联预览"
    t is HttpException && t.code == 415 -> "该文件类型不走字节预览通道"
    t is HttpException && t.code == 400 -> "该路径不是文件（可能是目录）"
    t is HttpException && t.code == 403 -> "无权限读取该文件"
    t is HttpException && t.code == 404 -> "文件不存在或已被移动"
    t is HttpException -> "预览失败（HTTP ${t.code}）"
    else -> t.message ?: "预览加载失败"
}

private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Ok(val bitmap: android.graphics.Bitmap) : ThumbState
    data class Failed(val message: String) : ThumbState
}

/**
 * 内联缩略图的最长边。**比 `ImageAttachments.fullBitmap` 的 1600px 小一档** ——
 * 缩略图最宽也就占满一张卡(手机上约 1080px),再大只是白占内存
 * (1024px 的 `ARGB_8888` ≈ 4MB,1600px ≈ 10MB 的差距)。想看细节点进全屏
 * 查看器,那边不采样。
 */
private const val THUMB_MAX_EDGE = 1024

/**
 * 内联缩略图的整图字节上限。服务端字节通道允许到 10 MiB
 * (`IMAGE_MAX_BYTES`),但**内联 + 解码**是「字节数组 + 采样后的 bitmap」两份
 * 内存,4 MiB 是手机端的务实分界:更大的图只给「点 ↗ 查看」,既不进内联区也
 * 不占会话流的内存。
 */
private const val INLINE_THUMB_MAX_BYTES = 4L * 1024 * 1024

/** 内联文本默认渲染的行数(展开后交给 Markdown / 代码块渲染全部)。 */
private const val INLINE_TEXT_LINES = 12

/**
 * 两遍采样解码(先只读 bounds,再按 `inSampleSize` 解)。与
 * `ImageAttachments` 同款 —— **必须采样**:一张 1 MiB 的 PNG 解出来可能就是
 * 4000×4000,`ARGB_8888` 下约 64 MB,不采样几张就能把低端机的堆推爆。
 */
internal fun decodeSampled(bytes: ByteArray, maxEdge: Int = THUMB_MAX_EDGE): android.graphics.Bitmap? {
    if (bytes.isEmpty()) return null
    // 第一遍:只读尺寸(inJustDecodeBounds 时不分配像素)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    while (longEdge / (sample * 2) >= maxEdge) sample *= 2
    // 第二遍:按采样率真正解码
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/**
 * 精简模式下的**聚合工具卡**(0.15.2)。
 *
 * 一整段工作(截图里那种 `mcp__cua-driver__click` × 7,以及编码会话里更常见的
 * 「Bash → 思考 → Bash → 思考」)先压成**一行**:图标 + 「工具调用 · N 次」+
 * 名字汇总 + 状态 + 箭头。点整行才铺开成一张张 [ToolCallCard] 与 [ThinkingBubble],
 * 每张再各自点开才看入参/输出 —— 两级折叠,默认只看得到「这段时间干了 N 件事」。
 *
 * **成员是混合的**:思考过程不打断段落(理由见 `buildAgentBlocks`),所以展开后
 * 思考卡按原顺序排在工具卡之间,内容一点没少。
 *
 * 为什么整行可点而不是下拉手势:会话流本身就是可滚列表,下拉手势要跟
 * LazyColumn 抢纵向手势,还不好发现;整行点击无歧义、单手也好点。
 *
 * 展开状态用 `remember(groupKey)`:段落继续增长时 key 不变(取首条成员的 key),
 * 所以流式追加不会把已展开的段落合回去。滚动出屏幕被回收时状态丢失 ——
 * 与 [ToolCallCard] 的既有行为一致(都是普通 `remember`,不做持久化)。
 */
@Composable
internal fun ToolGroupCard(
    members: List<AgentItem>,
    groupKey: String,
    api: AgentApi?,
    onOpenFile: (PresentedFile) -> Unit,
    onReveal: (PresentedFile) -> Unit,
) {
    var expanded by remember(groupKey) { mutableStateOf(false) }
    val tools = members.filterIsInstance<AgentItem.ToolCall>()
    val running = tools.any { it.running }
    val failed = tools.count { it.isError }
    // 配色与 ToolCallCard 同源:error 红 > 运行中暖橙 > 中性灰。
    val accent = when {
        failed > 0 -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "工具调用 · ${tools.size} 次",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = summarizeToolNames(tools),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = accent,
                    )
                } else {
                    StatusChip(
                        text = if (failed > 0) "$failed 失败" else "完成",
                        color = accent,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (expanded) {
            // 按 transcript 原顺序铺开 —— 工具卡与思考卡交错,跟不聚合时的顺序一致。
            members.forEach { AgentItemView(it, api, onOpenFile, onReveal) }
        }
    }
}

/** `Bash ×3 · Read ×1` —— 按首次出现顺序合并同名工具,只给出现多次的加 `×N`。 */
private fun summarizeToolNames(tools: List<AgentItem.ToolCall>): String {
    val counts = LinkedHashMap<String, Int>()
    tools.forEach { counts[it.name] = (counts[it.name] ?: 0) + 1 }
    return counts.entries.joinToString(" · ") { (name, n) ->
        if (n > 1) "$name ×$n" else name
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

// ===== 底部任务栏(任务清单 + 后台任务)=====

/** 后台任务区最多铺几行 —— 底部固定区是挤压出来的,不允许被任务列表吃掉。 */
private const val BG_RUNNING_ROWS = 5
private const val BG_RECENT_ROWS = 3

/** 「最近结束」窗口判定:终态任务超过 [BG_RECENT_TTL_MS] 就不再占位。 */
private fun bgStale(ts: Long, now: Long): Boolean = now - ts > BG_RECENT_TTL_MS

/**
 * 底部任务栏。**一张卡两段**:后台任务(agent 子代理 + 后台 bash)与任务清单。
 *
 * 为什么合成一张卡而不是各起一张:底部固定区是**垂直空间最贵**的地方 ——
 * 两张卡各带一行 header,就是两行纯装饰。合起来之后 header 一行同时承载
 * 运行态、后台任务数、任务清单进度,展开后按段落铺行,不展开时只占一行。
 *
 * header 里的东西按「有什么显示什么」拼:
 *   - 主 agent 运行态([StatusBadge],Streaming / Retrying / Aborted / Error)
 *   - 后台任务 chip(`◗ N 运行中`)—— 只在**有后台任务在跑**时出现,它是
 *     「主 agent 看起来闲着,其实子代理还在干活」的唯一信号
 *   - 任务清单进度(`任务清单 3/7`)—— 只有任务清单时才出现
 * 两者都没有时整块 return(调用方也会先看 `store.hasDockContent`)。
 *
 * [now] 是调用方传进来的时钟(会话屏已有一个 15s tick 的 `clockNow`):
 * 「最近结束」那一档需要它才会自己过期,否则完成的任务会一直挂在栏上。
 */
@Composable
internal fun TaskDockStrip(
    v2Tasks: List<V2Task>,
    agentTasks: List<BgAgentTask>,
    bashTasks: List<BgBashTask>,
    status: AgentRunStatus = AgentRunStatus.Idle,
    now: Long = System.currentTimeMillis(),
) {
    // 先按 TTL 滤掉过期终态(store 侧同档裁剪,两条路径互为兜底 —— 会话安静
    // 下来之后没有新事件,光靠 store 那条「完成」会一直挂着)。
    val liveAgents = agentTasks.filter { bgRunning(it.status) || !bgStale(it.finishedAt ?: it.createdAt, now) }
    val liveBash = bashTasks.filter { bgRunning(it.status) || !bgStale(it.finishedAt ?: it.startedAt, now) }

    // 跑中的排前面(它们才是用户要盯的),终态按结束时间倒序垫在后面。
    val runningAgents = liveAgents.filter { bgRunning(it.status) }
        .sortedByDescending { it.startedAt ?: it.createdAt }
    val recentAgents = liveAgents.filter { bgTerminal(it.status) }
        .sortedByDescending { it.finishedAt ?: it.createdAt }
    val runningBash = liveBash.filter { bgRunning(it.status) }.sortedByDescending { it.startedAt }
    val recentBash = liveBash.filter { bgTerminal(it.status) }.sortedByDescending { it.finishedAt ?: it.startedAt }

    val runningCount = runningAgents.size + runningBash.size
    val bgCount = liveAgents.size + liveBash.size
    if (v2Tasks.isEmpty() && bgCount == 0) return

    var expanded by remember { mutableStateOf(true) }
    val done = v2Tasks.count { it.status == "completed" }

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
                // 运行态 inline 到 header 左侧 —— 三点动画贴左边(跟输入卡上
                // StatusBadge Row 一致),右侧整组靠右。Idle 时整块不渲染。
                if (status != AgentRunStatus.Idle) {
                    StatusBadge(status)
                }
                Spacer(Modifier.weight(1f))
                if (v2Tasks.isEmpty()) {
                    // 只有后台任务 → header 标题就是「后台任务」,否则整行没有
                    // 任何说明文字,用户不知道这栏是什么。
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = if (runningCount > 0) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Text(text = "后台任务", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (runningCount > 0) "$runningCount 运行中" else "$bgCount 完成",
                        fontSize = 11.sp,
                        color = if (runningCount > 0) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    // 有任务清单 → 后台任务退成一个 chip,只在真的有东西在跑时
                    // 出现(否则「任务清单」旁边的第二组数字只是噪声)。
                    if (runningCount > 0) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "$runningCount 运行中",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(text = "任务清单", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "$done/${v2Tasks.size}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    // 两段都在时才需要段落标题 —— 只有一段的话 header 已经说清了。
                    val bothSections = bgCount > 0 && v2Tasks.isNotEmpty()
                    if (bgCount > 0) {
                        if (bothSections) SectionLabel("后台任务")
                        runningAgents.take(BG_RUNNING_ROWS).forEach { t ->
                            BgTaskRow(
                                icon = Icons.Rounded.SmartToy,
                                title = t.displayName,
                                detail = t.displayDetail,
                                status = t.status,
                                duration = bgDurationLabel(t.startedAt ?: t.createdAt, t.finishedAt, t.status),
                            )
                        }
                        runningBash.take(BG_RUNNING_ROWS).forEach { t ->
                            BgTaskRow(
                                icon = Icons.Rounded.Terminal,
                                title = "",
                                detail = t.displayDetail,
                                status = t.status,
                                duration = null,
                            )
                        }
                        recentAgents.take(BG_RECENT_ROWS).forEach { t ->
                            BgTaskRow(
                                icon = Icons.Rounded.SmartToy,
                                title = t.displayName,
                                detail = t.displayDetail,
                                status = t.status,
                                duration = bgDurationLabel(t.startedAt ?: t.createdAt, t.finishedAt, t.status),
                            )
                        }
                        recentBash.take(BG_RECENT_ROWS).forEach { t ->
                            BgTaskRow(
                                icon = Icons.Rounded.Terminal,
                                title = "",
                                detail = t.displayDetail,
                                status = t.status,
                                duration = bgDurationLabel(t.startedAt, t.finishedAt, t.status),
                            )
                        }
                    }
                    if (v2Tasks.isNotEmpty()) {
                        if (bothSections) SectionLabel("任务清单")
                        v2Tasks.forEach { t ->
                            val isDone = t.status == "completed"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp),
                            ) {
                                Icon(
                                    imageVector = if (isDone) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowRight,
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
}

/**
 * 后台任务的一行:`[图标] 名字 描述 ……… 状态 · 耗时`。
 *
 * 名字/描述挤在**同一个 Text** 里(用 AnnotatedString 分段加样式)而不是两个
 * Text —— 两个 Text 各自 ellipsize 会让「名字很长」时描述被整体挤没,合成一个
 * 就只有一个省略点,窄屏上表现稳定。[title] 为空时(后台 bash 没有名字)
 * 只渲染描述。
 */
@Composable
private fun BgTaskRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    status: String,
    duration: String?,
) {
    val running = bgRunning(status)
    val failed = status == "failed" || status == "killed"
    val tint = when {
        failed -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.tertiary
        status == "completed" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = buildAnnotatedString {
                if (title.isNotBlank()) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(title) }
                    if (detail.isNotBlank()) append("  ")
                }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(detail) }
            },
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (duration != null) "${bgStatusLabel(status)} · $duration" else bgStatusLabel(status),
            fontSize = 11.sp,
            color = statusColor,
            maxLines = 1,
        )
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
                            imageVector = Icons.Rounded.Close,
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
    val state = rememberAskAnswerState(questions)

    ActionCard(title = "需要你确认", accent = MaterialTheme.colorScheme.primary) {
        questions.forEach { q ->
            AskQuestionPanel(
                question = q,
                selected = state.selectedLabels(q.question),
                otherActive = state.isOtherSelected(q.question),
                otherText = state.otherText(q.question),
                onToggle = { label -> state.toggle(q, label) },
                onOtherTextChange = { text -> state.setOtherText(q.question, text) },
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = "提交",
                enabled = state.allAnswered() && !busy,
                busy = busy,
                filled = true,
                onClick = { onSubmit(state.payload()) },
            )
            ActionButton(text = "拒绝", enabled = !busy, busy = false, filled = false, onClick = onReject)
        }
    }
}

/**
 * 一道 ask 题的渲染:题面 + options 行 + (末尾追加的 Other 行 + 文本框)。
 *
 * Other 选项对齐 opencc-web 的 `QuestionCard.tsx`:UI 在 LLM 给出的 options
 * 末尾**自动追加**一个 "Other" 项,选 Other 时下方出单行文本框,用户输入的真实
 * 文本存在父级 [AskAnswerState] 的 `otherTexts` 映射里 —— `answers` 始终保持
 * `__other__` 占位符(不要把用户输入写回 answers,否则 Compose 重组时 Other
 * 行 selected 状态翻成 false,文本框被卸载,焦点丢到外面)。
 */
@Composable
private fun AskQuestionPanel(
    question: AskQuestion,
    selected: Set<String>,
    otherActive: Boolean,
    otherText: String,
    onToggle: (String) -> Unit,
    onOtherTextChange: (String) -> Unit,
) {
    if (question.header.isNotBlank()) {
        Text(
            text = question.header,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(text = question.question, fontSize = 13.sp, lineHeight = 19.sp)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        question.options.forEach { opt ->
            AskOptionRow(
                option = opt,
                selected = selected.contains(opt.label),
                onClick = { onToggle(opt.label) },
            )
        }
        AskOptionRow(
            option = AskOption(label = OTHER_LABEL),
            selected = otherActive,
            onClick = { onToggle(OTHER_VALUE) },
        )
        if (otherActive) {
            OtherTextField(
                value = otherText,
                onChange = onOtherTextChange,
            )
        }
    }
}

@Composable
private fun AskOptionRow(
    option: AskOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
                option.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                option.preview?.takeIf { it.isNotBlank() }?.let { prev ->
                    Spacer(Modifier.height(4.dp))
                    PreviewText(text = prev)
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * Other 文本框。autoFocus 在第一次 mount 时抢焦点;后续 user typing 不再
 * 触发 LaunchedEffect,焦点留在字段内。
 */
@Composable
private fun OtherTextField(
    value: String,
    onChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = {
            Text("请输入你的回答", fontSize = 13.sp)
        },
        textStyle = TextStyle(fontSize = 13.sp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp)
            .focusRequester(focusRequester),
    )
}

/**
 * 选项预览片段(对齐 web `QuestionCard.tsx` 的 `PreviewText`):
 * > 200 字截断 + 「展开」按钮。LLM 在 AskUserQuestion 工具的 option 上填
 * preview 来对比方案 A vs B,日常单选几乎不出现。
 */
private const val PREVIEW_LIMIT = 200

@Composable
private fun PreviewText(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    val display = if (expanded || text.length <= PREVIEW_LIMIT) {
        text
    } else {
        text.take(PREVIEW_LIMIT) + "…"
    }
    Column {
        Text(
            text = display,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(4.dp),
                )
                .padding(8.dp),
        )
        if (text.length > PREVIEW_LIMIT) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.height(22.dp),
            ) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * AskCard 的状态壳:answers(选项标签 / `__other__` 占位) + otherTexts
 * (Other 文本框的真实输入)。`mutableStateMapOf` 做细粒度 reactivity,
 * 单条 key 变更只触发读了它的 Composable 重组 —— 这是为什么 Other 文本框
 * 可以边打字边保留焦点:输入写 otherTexts,只重组 OutlinedTextField 本身,
 * 不会让 AskOptionRow 的 selected 状态翻成 false 把 Input 卸载掉。
 *
 * @suppress:测试用 public API,但 Compose `@Composable` 不能跨模块 export。
 * 单测不需要 Compose,只验纯函数 [buildAskPayload] / [allAnsweredFor]。
 */
@Composable
private fun rememberAskAnswerState(questions: List<AskQuestion>): AskAnswerState {
    val answers = remember(questions) { mutableStateMapOf<String, String>() }
    val otherTexts = remember(questions) { mutableStateMapOf<String, String>() }
    return AskAnswerState(questions, answers, otherTexts)
}

internal class AskAnswerState internal constructor(
    private val questions: List<AskQuestion>,
    private val answers: SnapshotStateMap<String, String>,
    private val otherTexts: SnapshotStateMap<String, String>,
) {
    fun selectedLabels(questionText: String): Set<String> {
        val raw = answers[questionText] ?: return emptySet()
        return raw.split(", ").filter { it.isNotBlank() }.toSet()
    }

    fun isOtherSelected(questionText: String): Boolean =
        answers[questionText]?.split(", ")?.contains(OTHER_VALUE) == true

    fun otherText(questionText: String): String = otherTexts[questionText].orEmpty()

    fun setOtherText(questionText: String, text: String) {
        if (text.isEmpty()) otherTexts.remove(questionText) else otherTexts[questionText] = text
    }

    /**
     * 单选/多选状态切换。对齐 web `QuestionCard.tsx` 的语义:
     * - 单选 Radio:覆盖(不取消),切到 Other 时清空 otherText 等用户输入
     * - 多选 Checkbox:点击已选项 = 移除(antd Checkbox.Group 默认行为)
     * - Other 永远 join 在首位(简化提交时的槽替换)
     */
    fun toggle(question: AskQuestion, label: String) {
        val raw = answers[question.question]
        val current = raw?.split(", ")?.toMutableList() ?: mutableListOf()
        if (question.multiSelect) {
            if (current.contains(label)) {
                current.remove(label)
                if (current.isEmpty()) answers.remove(question.question)
                else answers[question.question] = current.joinToString(", ")
            } else {
                if (label == OTHER_VALUE) {
                    current.removeAll { it == OTHER_VALUE }
                    current.add(0, OTHER_VALUE)
                } else {
                    current.add(label)
                }
                answers[question.question] = current.joinToString(", ")
            }
            // 切走 Other 时清空 otherText 残留(对齐 web QuestionCard.tsx:115)
            if (!current.contains(OTHER_VALUE)) otherTexts.remove(question.question)
        } else {
            // Radio:覆盖;切到 Other 也清空 otherText 让用户重新输入
            answers[question.question] = label
            otherTexts.remove(question.question)
        }
    }

    fun allAnswered(): Boolean = allAnsweredFor(answers.toMap(), otherTexts.toMap(), questions)

    fun payload(): Map<String, String> =
        buildAskPayload(answers.toMap(), otherTexts.toMap(), questions)
}

/**
 * 把 UI 状态折成服务端 [AgentApi.submitAnswer] 期望的 wire answers:
 * 单选 = label / Other 文本;多选 = `", "` join + Other 槽替换成文本。
 *
 * @suppress:测试 import。
 */
internal fun buildAskPayload(
    answers: Map<String, String>,
    otherTexts: Map<String, String>,
    questions: List<AskQuestion>,
): Map<String, String> {
    val out = LinkedHashMap<String, String>(answers.size)
    questions.forEach { q ->
        val raw = answers[q.question] ?: return@forEach
        val other = otherTexts[q.question].orEmpty()
        out[q.question] = if (q.multiSelect) {
            raw.split(", ")
                .map { if (it == OTHER_VALUE) other else it }
                .filter { it.isNotBlank() }
                .joinToString(", ")
        } else if (raw == OTHER_VALUE) {
            other
        } else {
            raw
        }
    }
    return out
}

/**
 * Submit 启用条件。对齐 web `QuestionCard.tsx` 的 `isAnswered`:
 * - 单选:必须选中一项;若选 Other,文本非空
 * - 多选:至少选一项;若包含 Other,文本非空
 *
 * @suppress:测试 import。
 */
internal fun allAnsweredFor(
    answers: Map<String, String>,
    otherTexts: Map<String, String>,
    questions: List<AskQuestion>,
): Boolean = questions.all { q ->
    val raw = answers[q.question] ?: return@all false
    val hasOther = if (q.multiSelect) {
        raw.split(", ").contains(OTHER_VALUE)
    } else {
        raw == OTHER_VALUE
    }
    !hasOther || otherTexts[q.question].orEmpty().trim().isNotEmpty()
}

/**
 * 与 opencc-web `packages/zai/src/web/src/components/QuestionCard.tsx` 的
 * `OTHER_OPTION_VALUE` 常量对齐 —— answers 里出现这个 sentinel 表示「选了
 * Other,真实文本在 otherTexts 里」。
 */
internal const val OTHER_VALUE = "__other__"

/** 用户面上看到的「Other」标签。 */
internal const val OTHER_LABEL = "Other"

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

                fileContent != null -> CodeBox(fileContent, codeLanguageLabel(pending.filePath))
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
 * 注:左侧图标是真的语音输入,**两条路,优先按住说话**:
 *   1. 配了腾讯云实时 ASR(见 `voice/VoiceAsrConfig`)→ [HoldToTalkButton]
 *      (按住说话 / 上滑取消 / 边说边出字)。这条**不依赖系统识别服务**,
 *      国行无 Google 服务的 ROM 上也能用 —— 正是平台方案的老死穴。
 *   2. 没配 → 回落到系统 SpeechRecognizer(见 [VoiceInputController]),点按切换;
 *      设备不支持时**不渲染**而不是画个灰图标占位。
 */
/**
 * 取「还在打命令名」阶段的关键字:输入以 `/` 开头、且首 token 还没出现空白时,
 * 返回 `/` 之后的内容(空串 = 刚敲下斜杠);否则 null = 面板该收起。
 *
 * **面板开关由这个函数推导**,不另存 boolean:补全成 `/name ` 之后多出一个
 * 空格,条件自然不成立,面板自己收 —— 不必在每个改 value 的地方记得手动关。
 *
 * 排除多行:粘进来一整段以 `/` 开头的文本不该被当成命令输入。
 */
private fun slashQueryOf(value: String): String? {
    val trimmed = value.trimStart()
    if (!trimmed.startsWith("/")) return null
    val body = trimmed.substring(1)
    if (body.contains('\n')) return null
    if (body.any { it == ' ' || it == '\t' }) return null
    return body
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    attachments: List<AttachedImage>,
    onRemoveAttachment: (AttachedImage) -> Unit,
    voice: VoiceInputController,
    /**
     * 按住说话的腾讯云 ASR 状态机。null = 没配密钥,回落到 [voice]。
     * 两者只会有一个在用:非 null 时 hold-to-talk 优先。
     */
    holdToTalk: HoldToTalkState?,
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
    /**
     * 命令候选(来自 `GET /api/slash`,模型见 data/SlashCommands.kt)。
     *
     * **空列表 = 不启用命令面板**:离线 / 服务端太老 / 拉取失败都会走到这里,
     * 此时敲 `/` 就是普通字符,行为与加这个功能之前完全一致 —— 命令面板是
     * 增强,不能成为发不出消息的新故障点。
     */
    slashItems: List<SlashItem>,
    /** 清单还在路上。只影响面板空态文案(「正在加载」vs「没有匹配」)。 */
    slashLoading: Boolean,
    /**
     * 用户选定了一条候选(点整行,或回车时输入已**精确命中**该命令名)。
     *
     * 输入条只做「面板交互 + 补全」;真正执行(打 `/api/agent/command`、
     * 按返回类型分流)在 AgentSessionScreen —— 那里才有 API 实例和 sessionId。
     */
    onRunSlash: (SlashItem) -> Unit,
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    // 语音模式两段式：点语音图标 → 输入框区域变成「按住 说话」大胶囊（WorkBuddy
    // 同款）；识别完成由 `holdToTalk.onResult` 直接 send() 自动发出，胶囊不自动
    // 收回 —— 用户可以接着按,也可以点左下角键盘图标手动切回打字模式。
    var voiceMode by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val modelSheetState = rememberModalBottomSheetState()

    // ---- 命令面板 ----
    // 面板开关**完全由输入推导**(见 [slashQueryOf]):补全成 `/name ` 后多了
    // 一个空格,条件自然不成立,面板自己收起 —— 不用在每个改 value 的地方
    // 记得手动关。唯一的例外是 Esc,所以单独记「这个内容被手动关过」。
    var slashDismissedFor by remember { mutableStateOf<String?>(null) }
    var slashIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val slashQuery = slashQueryOf(value)
    val slashMatches = remember(slashItems, slashQuery) {
        if (slashQuery == null) emptyList() else filterSlashItems(slashItems, slashQuery)
    }
    val showSlash = slashQuery != null && slashItems.isNotEmpty() && slashDismissedFor != value

    // 过滤条件一变,高亮回到第一条 —— 否则接着敲字符时高亮会停在中间某个
    // 已经不相关的位置上(选中项必须跟着候选集合收敛)。
    LaunchedEffect(slashQuery) { slashIndex = 0 }

    /** 环形上下移动。候选为空时不动。 */
    fun slashMove(delta: Int) {
        if (slashMatches.isEmpty()) return
        slashIndex = ((slashIndex + delta) % slashMatches.size + slashMatches.size) % slashMatches.size
    }

    /** 只补全不执行:`/name ` + 光标留在末尾,等用户敲参数。 */
    fun slashComplete(item: SlashItem) {
        onValueChange("/${item.name} ")
        slashIndex = 0
        // 点击候选行可能让输入框丢焦点(软键盘收起),补回来。
        runCatching { focusRequester.requestFocus() }
    }

    /**
     * 选中一条候选:执行,还是只补全?**按 web 端 `selectSlashItem` 的分支**:
     *
     *   - `type == "local"` 的命令(`/clear` `/compact` `/status`)→ **选中即执行**
     *     (web 走的也是这条路,参数传空;`/compact` 虽然挂着 `[--force]` 提示,
     *     但它是 local,web 同样立刻执行 —— 别用「有没有 argumentHint」当判据);
     *   - 其余(prompt 命令 / skill)→ 只补全成 `/name `,让用户补上参数
     *     (skill 的 `$ARGUMENTS`)再自己发。误触一行就触发一次模型调用,
     *     比多按一次发送键贵得多。
     */
    fun slashRun(item: SlashItem) {
        if (!item.isSkill && item.isLocal) onRunSlash(item) else slashComplete(item)
    }

    /**
     * 回车在面板打开时的语义:**能精确命中才执行,否则先补全**。
     *
     * fuzzy 排序的第一条未必是用户想要的(`/comm` 的头名可能不是 commit),
     * 而误执行一条命令的代价(比如 `/clear`)远大于多按一次回车。所以只有
     * `parseSlashInput` 出来的名字与某条候选**完全相等**才直接跑,其余情况
     * 把高亮那条补全成 `/name ` 交给用户。
     */
    fun slashSubmitKeyboard() {
        val typed = parseSlashInput(value)?.name
        val exact = slashMatches.firstOrNull { it.name == typed }
        if (exact != null) slashRun(exact) else slashMatches.getOrNull(slashIndex)?.let { slashComplete(it) }
    }

    /**
     * 物理键盘导航,返回 true = 事件已消费。
     *
     * 只认 KeyDown(KeyUp 会再来一次,不管的话一次按键走两步);面板没开时
     * 一律不消费 —— 把按键让回正常路径(比如 Enter 该触发 keyboardActions)。
     */
    fun slashKey(ev: KeyEvent): Boolean {
        if (!showSlash || ev.type != KeyEventType.KeyDown) return false
        when (ev.key) {
            Key.DirectionDown -> {
                slashMove(1); return true
            }
            Key.DirectionUp -> {
                slashMove(-1); return true
            }
            Key.Tab -> {
                slashMatches.getOrNull(slashIndex)?.let { slashComplete(it) }; return true
            }
            Key.Enter, Key.NumPadEnter -> {
                slashSubmitKeyboard(); return true
            }
            Key.Escape -> {
                slashDismissedFor = value; return true
            }
        }
        return false
    }

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
                // 底部导航栏已常驻(见 MainScaffold),inset 由它扣过一次;
                // 这里再 padding 就双重了。键盘 inset(imePadding)仍归这里管。
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

            // 命令面板:只在「还在打命令名」阶段出现,挂在输入卡**上方**(与附件
            // 条同一层)。不做跟光标的 popup —— 手机上没那个空间,内联还能保证
            // 它不被软键盘遮住。
            //
            // `weight(1f, fill = false)` 是这个面板**必须**有的:输入条的 Column
            // 高度被软键盘 inset 压过,面板(6 行 ≈ 324dp)+ 输入卡一旦超过剩余
            // 空间,Column 会直接把排在后面的输入卡挤出可视区 —— 表现就是「敲了
            // 个 `/`,面板弹出来,输入框没了」,用户看不到自己打的命令名。
            // weight 让面板只能吃掉「输入卡量完之后剩下的那点高度」,fill=false
            // 保证空间够时它仍然按内容收缩(不撑满)。
            if (showSlash) {
                SlashCommandPanel(
                    items = slashMatches,
                    selectedIndex = slashIndex,
                    loading = slashLoading,
                    onPick = { slashRun(it) },
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(bottom = 8.dp),
                )
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
                    // ---- 第一行:文本域,语音模式下变成「按住 说话」大胶囊 ----
                    if (voiceMode && holdToTalk != null) {
                        HoldToTalkCapsule(
                            state = holdToTalk,
                            baseText = value,
                            modifier = Modifier.padding(end = 10.dp, bottom = 10.dp),
                        )
                    } else {
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
                            // 软键盘的「发送」键在面板打开时改变语义:先补全(只有
                            // 精确命中命令名才真执行),而不是把半截命令名当消息发出去。
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (showSlash) slashSubmitKeyboard()
                                    else if (reallyCanSend) onSend()
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp, max = 150.dp)
                                .focusRequester(focusRequester)
                                // 物理键盘(外接 / 平板 / 掌机)的导航键。手机的软键盘
                                // 没有方向键,所以 ↑↓ / Tab 是「有则更好」;真正兜住
                                // 手机交互的是候选行点击 + 上面的发送键分支。
                                .onPreviewKeyEvent { ev -> slashKey(ev) },
                        )
                    }
                    } // else:非语音模式的文本域

                    // ---- 第二行:工具条(左:语音/键盘/模型/附件;右:发送) ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // 左下角图标三态:
                        //   - voiceMode=true:键盘图标(切回打字模式),主色高亮
                        //   - voiceMode=false + holdToTalk:语音图标(进入语音模式)
                        //   - voiceMode=false + 系统 SpeechRecognizer:语音图标
                        //     (直接 toggle 录音,识别结果回填输入框,需手动点发送)
                        // 语音模式下识别完成会自动 send(),胶囊不收回 —— 用户
                        // 既可以接着按说话,也可以点这个键盘图标切回打字。
                        if (voiceMode) {
                            InputBarIcon(
                                icon = Icons.Rounded.Keyboard,
                                contentDescription = stringResource(R.string.agent_input_keyboard),
                                tint = MaterialTheme.colorScheme.primary,
                                onClick = { voiceMode = false },
                            )
                        } else if (holdToTalk != null) {
                            // 云 ASR:点语音图标进入语音模式(输入框变「按住 说话」大胶囊),
                            // 不依赖系统识别服务,国行无 Google 服务的 ROM 上也照常能用。
                            InputBarIcon(
                                icon = Icons.Rounded.GraphicEq,
                                contentDescription = stringResource(R.string.agent_input_voice),
                                tint = MaterialTheme.colorScheme.onSurface,
                                onClick = { voiceMode = true },
                            )
                        } else if (voice.available) {
                            // 系统 SpeechRecognizer 兜底:点按切换录音,识别结果回填输入框。
                            InputBarIcon(
                                icon = Icons.Rounded.GraphicEq,
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

                        // chip 撑满中间空间,把右侧 `+` 与发送钮顶到右边挨着。
                        // 短模型名时空出来的水平由 chip 内部的 Text fill=true
                        // 承载(左对齐 + 空白),长名时由 Text 的 weight + Ellipsis
                        // 自动收尾,不会反过来挤扁右侧两个按钮。
                        ModelChip(
                            model = currentModel,
                            enabled = availableModels.isNotEmpty(),
                            onClick = { showModelPicker = true },
                            modifier = Modifier.weight(1f, fill = true),
                        )

                        // `+` 与发送钮**并存**(WorkBuddy 行为)。附件/粘贴收进
                        // 这个面板,所以卡下方不再需要 icon row。
                        InputBarIcon(
                            icon = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.agent_input_more),
                            tint = MaterialTheme.colorScheme.onSurface,
                            onClick = { showMoreMenu = true },
                        )

                        if (busy) {
                            InputBarCircle(
                                icon = Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.agent_input_stop),
                                container = MaterialTheme.colorScheme.error,
                                content = MaterialTheme.colorScheme.onError,
                                enabled = true,
                                onClick = onStop,
                            )
                        } else {
                            InputBarCircle(
                                icon = Icons.Rounded.ArrowUpward,
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
                    icon = Icons.Rounded.AddPhotoAlternate,
                    title = stringResource(R.string.agent_input_add_image),
                    subtitle = stringResource(R.string.agent_input_add_image_sub),
                    onClick = {
                        showMoreMenu = false
                        onPickImage()
                    },
                )
                InputSheetAction(
                    icon = Icons.Rounded.ContentPaste,
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
 * 卡内工具条上的模型 chip:`(圆点/图标) 模型名 ⌄`。
 *
 * 对齐 WorkBuddy:模型选择器是**输入卡的一部分**,而不是输入卡下方的独立入口。
 * 无底色、无描边(点中区靠 clip 后的 ripple 提示)。
 *
 * **显示的是 `model` 字段(纯模型 id),不是 `alias`** —— alias 可能带 provider
 * 前缀(如 `builtin-openplatform/gpt-4-turbo`),chip 里那一长串 provider
 * 是冗余的(provider 在 picker 分组时已经看过一次),纯 id 既短又能
 * 跟服务端 / log 对得上号。
 *
 * 外部传 `modifier = Modifier.weight(1f, fill = true)`,让 chip 撑满
 * 工具条中间剩余空间,把右侧的 `+` / 发送钮顶到右边挨着 —— 不然 chip
 * 自然宽度时 `+` 与发送钮之间会留一段空。chip 内部 Text 也是
 * `weight(1f, fill = true)`,长模型名走 ellipsis,短名左对齐 + 留白在右。
 *
 * `enabled = false`(拿不到模型列表)时整块变淡且不可点,避免点开一个空 picker。
 */
@Composable
private fun ModelChip(
    model: ModelEntry?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Psychology,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        // chip 外部是 fill=true 时,Text 内部也用 fill=true,
        // 让模型名在 chip 内贴左展示 + 留白在右,长名时由 weight 把
        // 宽度预算给 Text 后走 ellipsis 截断 —— 不会因为 chip 撑满了
        // 整个中间就把文本挤到右边的 ⌄ 之外。
        Text(
            text = model?.model ?: stringResource(R.string.agent_input_model_short),
            fontSize = 14.sp,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
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
                // provider header:用本组第一条非空 description 作为人读名
                // (服务端把 provider 显示名塞进 ModelEntry.description);
                // 没 description 才回落原始 providerId,避免显示
                // `provider_1789379862098` 这种 hash。
                val headerLabel = entries.firstNotNullOfOrNull { it.description?.takeIf { d -> d.isNotBlank() } }
                    ?: providerId
                Text(
                    text = headerLabel,
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
                        imageVector = Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    // 单行布局:description 已上提到 provider header,行内
                    // 不再展示,只剩 [Icon] [Name(weight=1)] [✓]。
                    Text(
                        text = entry.label ?: entry.alias,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
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
                imageVector = Icons.Rounded.Close,
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
    // 全部走 onSurfaceVariant —— 状态条只做轻提示,不抢输入框注意力;
    // 配色再花哨用户也不会停下来看,反而显得啰嗦。
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (status) {
            AgentRunStatus.Streaming,
            AgentRunStatus.Retrying -> {
                // 活跃态:三个小点循环淡入淡出,代替"运行中"/"重试中"文字。
                PulsingDots(color = color)
            }
            AgentRunStatus.Aborted -> {
                Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
                Text(text = "已中断", fontSize = 11.sp, color = color)
            }
            AgentRunStatus.Error -> {
                Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
                Text(text = "出错", fontSize = 11.sp, color = color)
            }
            AgentRunStatus.Idle -> Unit // 调用方已用 `if (status != Idle)` 过滤
        }
    }
}

/**
 * 三个小点波浪式淡入淡出,代替"运行中..."文本(0.10.2 起)。颜色由调用方
 * 传入 —— 这里刻意不取主题 primary,只做轻提示,不抢主按钮/发送按钮的
 * 颜色身份。
 *
 * 实现:单个 0→1 循环进度,三个 dot 按相位偏移(0/0.33/0.66)采样
 * `sin(progress * π)` —— sin 半周期天然给出"渐亮 → 渐暗"曲线,三个点
 * 相位错开 1/3 周期就成波浪。比 `infiniteRepeatable` 配 `initialStartDelay`
 * 更稳(后者不在所有 Compose 版本里都支持),也比三个独立 `animateFloat`
 * 更省重组开销。
 */
@Composable
private fun PulsingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "status-dots")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "p",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // alpha ∈ [0.25, 1.0],留 0.25 底线避免完全消失 —— 比"亮 → 全黑 → 亮"
        // 更克制。
        val baseMin = 0.25f
        Box(
            Modifier
                .size(5.dp)
                .alpha(baseMin + (1f - baseMin) * sinWave(progress, 0f))
                .background(color, CircleShape),
        )
        Box(
            Modifier
                .size(5.dp)
                .alpha(baseMin + (1f - baseMin) * sinWave(progress, 1f / 3f))
                .background(color, CircleShape),
        )
        Box(
            Modifier
                .size(5.dp)
                .alpha(baseMin + (1f - baseMin) * sinWave(progress, 2f / 3f))
                .background(color, CircleShape),
        )
    }
}

/** `sin(cycle * π)` 半周期曲线,cycle ∈ [0, 1) → 返回 ∈ [0, 1],0.5 处峰值。 */
private fun sinWave(progress: Float, phase: Float): Float {
    val cycle = ((progress + phase) % 1f + 1f) % 1f
    return kotlin.math.sin(cycle * Math.PI.toFloat())
}
