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
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.AskQuestion
import io.github.hotmanxp.lanagent.data.AttachedImage
import io.github.hotmanxp.lanagent.data.DisplayFile
import io.github.hotmanxp.lanagent.data.FileKind
import io.github.hotmanxp.lanagent.data.HttpException
import io.github.hotmanxp.lanagent.data.ImageAttachments
import io.github.hotmanxp.lanagent.data.ModelEntry
import io.github.hotmanxp.lanagent.data.PendingInteraction
import io.github.hotmanxp.lanagent.data.pretty
import io.github.hotmanxp.lanagent.data.QueuedPrompt
import io.github.hotmanxp.lanagent.data.SlashItem
import io.github.hotmanxp.lanagent.data.V2Task
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
internal fun ToolCallCard(
    item: AgentItem.ToolCall,
    api: AgentApi?,
    onOpenFile: (DisplayFile) -> Unit,
) {
    // `DisplayFiles` 有文件列表时走**文件卡片**形态(见 [DisplayFilesBody]):
    // 它的 output 是一段给前端渲染用的元数据 JSON,照普通工具卡渲染只会让
    // 用户看到一坨 JSON。
    val files = item.files
    val isFiles = files.isNotEmpty()
    // 工具卡默认收起是为了压住入参/输出的噪声;文件卡本身没有噪声,
    // 而且它出现就意味着「让你看东西」—— 默认展开,少一次点击。
    var expanded by remember(item.key) { mutableStateOf(isFiles) }
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
                    imageVector = if (isFiles) Icons.Rounded.FolderOpen else Icons.Rounded.Build,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (isFiles) "文件 · ${files.size}" else item.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = if (isFiles) FontFamily.Default else FontFamily.Monospace,
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
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    if (isFiles) {
                        DisplayFilesBody(files = files, api = api, onOpenFile = onOpenFile)
                    } else {
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
}

// ===== DisplayFiles 文件卡片 =====

/**
 * `DisplayFiles` 工具的文件列表。每行:类型图标 + 文件名 + `大小 · 修改时间` +
 * 路径;图片再多一张内联缩略图。整行可点 → 打开会话面板内从右侧滑入的预览层
 * (见 `ui/FileViewerOverlay.kt`)。
 *
 * **为什么不做「元数据单独一段、点击才加载」**:Agent 调这个工具的意图就是
 * 「给你看这东西」,再收一层等于让用户多点一次。图片缩略图由 LazyColumn 的
 * 懒组合天然收敛 —— 卡片滚出屏幕就停止加载。
 */
@Composable
private fun DisplayFilesBody(
    files: List<DisplayFile>,
    api: AgentApi?,
    onOpenFile: (DisplayFile) -> Unit,
) {
    // 只有前 [MAX_INLINE_IMAGES] 张图片内联渲染缩略图。
    //
    // 为什么必须设上限:整张卡是 LazyColumn 的**一个** item,所以「可见」= 卡里
    // 所有缩略图同时组合、同时发请求。服务端单张上限 1 MiB,20 张就是 20 个并发
    // 请求 + 20 份解码后的 bitmap —— 采样到 1024px 也还有 4MB/张,足够把低端机
    // 的堆推爆。超出的那些照常是**一行元数据**,点进去照样看得到大图。
    val inlineable = remember(files) {
        files.asSequence()
            .filter { it.kind == FileKind.Image && it.previewable && !it.isVectorImage }
            .take(MAX_INLINE_IMAGES)
            .map { it.path }
            .toSet()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { file ->
            DisplayFileRow(
                file = file,
                api = api,
                onOpenFile = onOpenFile,
                inlineThumb = file.path in inlineable,
            )
        }
    }
}

@Composable
private fun DisplayFileRow(
    file: DisplayFile,
    api: AgentApi?,
    onOpenFile: (DisplayFile) -> Unit,
    inlineThumb: Boolean,
) {
    // api == null = 实例还没解析出来;此时行仍渲染(元数据来自 transcript,
    // 不依赖网络),只是点不开。
    val canOpen = file.previewable && api != null
    val tone = when {
        file.failed -> MaterialTheme.colorScheme.error
        canOpen -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen) { onOpenFile(file) },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
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
                        color = tone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 副标题可能整体为空:冷启动后 binary 文件既没有 size 也没有
                    // 可说的类型(`过多大` 只在已知尺寸时才成立)。空串就别渲染 ——
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
                if (canOpen) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "预览",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 图片内联缩略图。SVG 排除在外 —— BitmapFactory 解不了矢量图,
            // 交给全屏查看器的 WebView(点行即可)。
            if (inlineThumb && api != null) {
                Spacer(Modifier.height(8.dp))
                RemoteImageThumb(api = api, file = file, onTap = { onOpenFile(file) })
            }

            // 路径单独一行、等宽小字 —— 排查「Agent 给我看的是哪个文件」时
            // 这个名字往往不够(同名文件在多个 worktree 里很常见)。
            Spacer(Modifier.height(4.dp))
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

/** 一行的次要说明:`1.2 MB · 3 分钟前`;失败时直接给错误原因。 */
private fun fileSubtitle(file: DisplayFile): String {
    if (file.failed) return file.error.orEmpty()
    val parts = ArrayList<String>(3)
    file.size?.let { parts.add(formatBytes(it)) }
    file.mtime?.takeIf { it > 0L }?.let { parts.add(formatRelativeAgoMs(it)) }
    when (file.kind) {
        FileKind.Image -> parts.add("图片")
        FileKind.Html -> parts.add("网页")
        FileKind.Text -> parts.add("文本")
        FileKind.Binary -> if (file.tooLarge) parts.add("过大，暂不预览")
    }
    return parts.joinToString(" · ")
}

private fun fileKindIcon(kind: FileKind) = when (kind) {
    FileKind.Image -> Icons.Rounded.Photo
    FileKind.Html -> Icons.Rounded.Html
    FileKind.Text -> Icons.Rounded.Description
    FileKind.Binary -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** 字节数。小数固定用 `.`(默认 Locale 会在部分地区给逗号)。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * 预览失败 → 给人看的一句话。413 / 404 与「网络不通」对用户的含义完全不同,
 * 不能都写成「加载失败」。
 */
internal fun previewErrorMessage(t: Throwable): String = when {
    t is HttpException && t.code == 413 -> "文件过大，不支持内联预览"
    t is HttpException && t.code == 400 -> "该路径不是文件（可能是目录）"
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
 * 内联图片缩略图 —— 字节走 `GET /api/fs/preview` 取回(base64),在 IO 线程
 * 采样解码。
 *
 * **必须采样**:服务端上限 1 MiB(`FILE_PREVIEW_MAX_BYTES`),但一张 1 MiB 的
 * PNG 解出来可能就是 4000×4000,`ARGB_8888` 下约 64MB —— 一次渲染几张就能
 * 把低端机推爆。
 * 采样算法与 `ImageAttachments` 的两遍解码同款(先只读 bounds,再按
 * `inSampleSize` 解)。
 */
@Composable
private fun RemoteImageThumb(api: AgentApi, file: DisplayFile, onTap: () -> Unit) {
    val state by produceState<ThumbState>(ThumbState.Loading, api, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val preview = api.previewFile(file.path)
                val encoded = preview.content
                if (preview.fileKind != FileKind.Image || encoded.isNullOrEmpty()) {
                    throw IllegalStateException("服务端未返回图片内容")
                }
                decodeSampled(Base64.decode(encoded, Base64.DEFAULT))
                    ?: throw IllegalStateException("图片解码失败")
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
            .clickable(onClick = onTap),
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
 * 内联缩略图的最长边。**比 `ImageAttachments.fullBitmap` 的 1600px 小一档** ——
 * 缩略图最宽也就占满一张卡(手机上约 1080px),再大只是白占内存
 * (1024px 的 `ARGB_8888` ≈ 4MB,1600px ≈ 10MB,几张就是几十 MB 的差距)。
 * 想看细节点进全屏查看器,那边不采样。
 */
private const val THUMB_MAX_EDGE = 1024

/** 一张文件卡片里最多内联几张图片缩略图(理由见 `DisplayFilesBody`)。 */
private const val MAX_INLINE_IMAGES = 4

private fun decodeSampled(bytes: ByteArray, maxEdge: Int = THUMB_MAX_EDGE): android.graphics.Bitmap? {
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
    onOpenFile: (DisplayFile) -> Unit,
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
            members.forEach { AgentItemView(it, api, onOpenFile) }
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

// ===== V2 任务条 =====

/**
 * 任务清单卡。**0.10.5 起 header inline 渲染运行态**(Streaming / Retrying
 * 三点动画 + Aborted / Error 文字),调用方不需要再在 strip 下方单起一行
 * StatusBadge —— 否则 strip header 一行 + status 行 + 输入卡挤一起,视觉很噪。
 *
 * 调用方决定:没任务清单时仍按老路径单独渲染 StatusBadge 行(见
 * `AgentSessionScreen.kt` 的运行态提示条)。
 */
@Composable
internal fun V2TaskStrip(
    tasks: List<V2Task>,
    status: AgentRunStatus = AgentRunStatus.Idle,
) {
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
                // 0.10.5:运行态 inline 到 header 左侧 —— 三点动画贴左边
                // (跟输入卡上 StatusBadge Row 一致,顶栏原本就是左对齐),
                // 「任务清单 4/5 ▼」整体靠右。Idle 时整块不渲染,
                // header 只有右半边「任务清单 | 4/5 | ▼」。
                if (status != AgentRunStatus.Idle) {
                    StatusBadge(status)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "任务清单",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "$done/${tasks.size}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
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
                                    imageVector = Icons.Rounded.Check,
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
