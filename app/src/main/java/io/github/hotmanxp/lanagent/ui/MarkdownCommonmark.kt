// ui/MarkdownCommonmark.kt — commonmark-java AST 渲染(0.23.0)
//
// 替掉原来的自研 `MarkdownParser`(旧实现在 ui/Markdown.kt 的历史版本里)。
// 引擎与扩展组合照 Agents-Anywhere `ui/screens/sessiondetail/AgentMarkdownText.kt`
// 取同一套(commonmark 0.24.0 + autolink / strikethrough / tables / task-list-items)。
//
// ## 换掉自研版换到了什么
//
// 1. **规范兜住流式半截语法**。CommonMark 规定未闭合的 ``` 围栏吃到文档末尾 ——
//    流式打到一半的代码块,规范本身就渲染成代码块,不用自己写状态机。
// 2. **GFM 表格 / 删除线 / autolink / 任务列表** 四个扩展白拿。
// 3. AST 信息量比「拍扁的块列表」多(嵌套列表、表格单元格对齐),后面想加
//    脚注 / 定义列表有地基。
//
// ## 换掉丢了什么
//
// 无实质退化。自研版「未闭合围栏当代码块」的手写规则由规范接管;
// 「半截行内标记原样输出」的结果与规范一致(都当普通字符)。
//
// 真正要额外处理的是**表格**:GFM 要求「表头行 + 分隔行」成对,流式打到只有
// 表头时会被解析成普通段落。见 [normalizeMarkdownTables]。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Text as CmText
import org.commonmark.parser.Parser
import org.commonmark.Extension

/** 顶层单例 —— Parser 构造代价高(要建扩展链),全局复用。 */
private val markdownParser: Parser = Parser.builder()
    .extensions(
        listOf<Extension>(
            AutolinkExtension.create(),
            StrikethroughExtension.create(),
            TablesExtension.create(),
            TaskListItemsExtension.create(),
        ),
    )
    .build()

// ── 流式安全的表格预处理 ───────────────────────────────────────────────

private val TableDelimiterCellRegex = Regex(""":?-{1,}:?""")

/**
 * 打断「疑似表头 + 分隔行」与前文段落的粘连。
 *
 * 没有这一步时,流式打到:
 * ```
 * 下面是一段话
 * | 列 A | 列 B |
 * | --- | --- |
 * ```
 * 整块会被 commonmark 当成**一个段落**(软换行不断段),表格不渲染。
 * 在疑似表头前插空行把它和上文断开,表格才会被识别。
 *
 * ⚠️ 必须自己跟踪 inFence —— 代码块里的 `| a | b |` 是代码不是表格,
 * 不跟踪围栏就会往代码块中间插空行,代码直接碎掉。
 */
internal fun normalizeMarkdownTables(markdown: String): String {
    if (!markdown.contains('|')) return markdown

    val lines = markdown.lines()
    if (lines.size < 2) return markdown

    val normalized = mutableListOf<String>()
    var inFence = false

    lines.forEachIndexed { index, line ->
        val next = lines.getOrNull(index + 1)
        if (
            !inFence &&
            next != null &&
            isPotentialTableHeaderRow(line) &&
            isTableDelimiterRow(next) &&
            normalized.lastOrNull()?.isNotBlank() == true
        ) {
            normalized += ""
        }
        normalized += line
        if (line.isMarkdownFenceBoundary()) inFence = !inFence
    }
    return normalized.joinToString("\n")
}

private fun isPotentialTableHeaderRow(line: String): Boolean {
    val t = line.trim()
    return t.isNotEmpty() && t.contains('|') && !t.startsWith("```")
}

private fun isTableDelimiterRow(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith('|') || !t.endsWith('|')) return false
    val cells = t.trim('|').split('|').map { it.trim() }
    return cells.isNotEmpty() && cells.all { it.isNotEmpty() && TableDelimiterCellRegex.matches(it) }
}

private fun String.isMarkdownFenceBoundary(): Boolean =
    trimStart().startsWith("```") || trimStart().startsWith("~~~")

// ── 渲染入口 ───────────────────────────────────────────────────────────

/**
 * Markdown 正文渲染。
 *
 * @param compact 紧凑模式(思考过程展开时用):块间距收紧。
 */
@Composable
internal fun CommonmarkText(
    markdown: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // remember 的 key 用**原始** markdown —— 规范化是纯函数,原文不变则结果不变。
    val source = remember(markdown) { normalizeMarkdownTables(markdown) }
    val document = remember(source) {
        runCatching { markdownParser.parse(source.ifBlank { "_(no content)_" }) as Document }
            .getOrElse { Document() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        document.children().forEach { renderNode(it) }
    }
}

/** 块级节点分发。行内节点在 [inlineAnnotated] 里处理,不到这里。 */
@Composable
private fun renderNode(node: org.commonmark.node.Node) {
    // 0.26.0 对话字号:全局缩放因子(0.85 / 1.0 / 1.15)。一次性读,在本节点
    // 分支里复用,避免每次 Text() 都触发一次 CompositionLocal 读取。
    val fontScale = LocalMessageFontScale.current
    when (node) {
        is Heading -> {
            val (size, weight) = when (node.level) {
                1 -> 18.sp to FontWeight.Bold
                2 -> 16.sp to FontWeight.SemiBold
                3 -> 15.sp to FontWeight.SemiBold
                else -> 14.sp to FontWeight.Medium
            }
            val scaledSize = size * fontScale
            Text(
                text = inlineAnnotated(node, linkColor),
                fontSize = scaledSize,
                fontWeight = weight,
                lineHeight = scaledSize * 1.4f,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        is Paragraph -> Text(
            text = inlineAnnotated(node, linkColor),
            // bodyMedium 在 Material3 默认 14sp;乘以 fontScale 后落到目标字号。
            // 这里**不**用 bodyLarge(默认 16sp,起点就大),会和小档叠加超界。
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Mermaid 围栏块走 WebView 渲染;开关关掉、或渲染失败(语法错 / 流式
        // 半截)时由 MermaidBlock 调 fallback 退回普通代码块 —— 和改动前一样。
        is FencedCodeBlock -> {
            val literal = node.literal
            val info = node.info
            if (isMermaidFence(info) && LocalMermaidEnabled.current) {
                MermaidBlock(source = literal) { CodeBox(literal, info) }
            } else {
                CodeBox(literal, info)
            }
        }

        is IndentedCodeBlock -> CodeBox(node.literal, null)

        is BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                node.children().filterIsInstance<ListItem>().forEachIndexed { i, item ->
                    Row(modifier = Modifier.padding(start = ((i % 4) * 10).dp)) {
                        Text(
                            "•",
                            fontSize = 14.sp * fontScale,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) { item.children().forEach { renderNode(it) } }
                    }
                }
            }
        }

        is OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                node.children().filterIsInstance<ListItem>().forEachIndexed { i, item ->
                    Row {
                        Text(
                            "${node.startNumber + i}.",
                            fontSize = 14.sp * fontScale,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) { item.children().forEach { renderNode(it) } }
                    }
                }
            }
        }

        is BlockQuote -> {
            // 左侧一根竖线 + 缩进,不给背景色 —— 引用块在 agent 输出里不常见,
            // 重底色会和工具卡撞。
            Row {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                        .padding(vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { node.children().forEach { renderNode(it) } }
            }
        }

        is TableBlock -> CommonmarkTable(node)

        is ThematicBreak -> HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        else -> {
            // 容器类节点(未知扩展产出的):递归子节点,别把内容吞掉。
            if (node.firstChild != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.children().forEach { renderNode(it) }
                }
            }
        }
    }
}

/** 取子节点为 List —— commonmark 的 `Node.children()` 返回 Iterable,直接 forEach 也行,但这里统一成 List 方便 filterIsInstance。 */
private fun org.commonmark.node.Node.children(): List<org.commonmark.node.Node> {
    val out = ArrayList<org.commonmark.node.Node>()
    var c = firstChild
    while (c != null) {
        out.add(c)
        c = c.next
    }
    return out
}

/**
 * GFM 表格。整表 `horizontalScroll`,列宽给 `widthIn(min = 96.dp)` 下限 ——
 * 三列以上的窄屏否则会把每列压成竖排的一个字。
 *
 * commonmark 的 [TableBlock] 是**扁平**结构:子节点就是一串 [TableRow],
 * 第一行是表头(靠 `TableCell.isHeader` 也能判,这里直接用行序),没有
 * header / body 之分、也没有 columnCount —— 列数自己数。
 */
@Composable
private fun CommonmarkTable(table: TableBlock) {
    val rows = table.children().filterIsInstance<TableRow>()
    if (rows.isEmpty()) return
    val columnCount = rows.maxOf { row -> row.children().count { it is TableCell } }
    val minWidth = (columnCount * 96).dp

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { rowIndex, row ->
                val isHeader = rowIndex == 0
                Row(
                    modifier = Modifier
                        .widthIn(min = minWidth)
                        .then(
                            if (isHeader) {
                                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    // 补齐短行缺的格子,否则列会错位(脏表格 / 手写表格常见)。
                    repeat(columnCount) { col ->
                        val cell = row.children().filterIsInstance<TableCell>().getOrNull(col)
                        TableCellText(cell, isHeader)
                    }
                }
                HorizontalDivider(
                    thickness = if (isHeader) 1.dp else 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun RowScope.TableCellText(cell: TableCell?, isHeader: Boolean) {
    val fontScale = LocalMessageFontScale.current
    Text(
        text = cell?.let { inlineAnnotated(it, linkColor) } ?: AnnotatedString(""),
        fontSize = 12.sp * fontScale,
        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = when (cell?.alignment) {
            TableCell.Alignment.CENTER -> TextAlign.Center
            TableCell.Alignment.RIGHT -> TextAlign.Right
            else -> TextAlign.Start
        },
        modifier = Modifier.widthIn(min = 96.dp),
    )
}

/**
 * 把行内节点拼成 [AnnotatedString]。**递归**走一遍,所以嵌套的
 * `**bold 里 *italic* 也有 [link](x)` 能正确叠加样式。
 *
 * 链接用 [LinkAnnotation.Url] 而不是裸 URL —— Compose 1.7+ 会自动挂
 * `LocalUriHandler`,`Text` 一点就跳转,不用自己加 clickable。
 */
private fun inlineAnnotated(parent: org.commonmark.node.Node, link: Color): AnnotatedString = buildAnnotatedString {
    var child = parent.firstChild
    while (child != null) {
        when (child) {
            is CmText -> append(child.literal)

            is Code -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                append(child.literal)
                pop()
            }

            is StrongEmphasis -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInline(child, link)
                pop()
            }

            is Emphasis -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInline(child, link)
                pop()
            }

            is Strikethrough -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInline(child, link)
                pop()
            }

            is Link -> appendLink(child, link)

            is SoftLineBreak -> append('\n')
            is HardLineBreak -> append('\n')

            else -> {
                // 未知行内节点:递归进去,至少别把内容吞掉。
                if (child.firstChild != null) appendInline(child, link)
            }
        }
        child = child.next
    }
}

/** 递归追加子节点的行内内容(不套样式,继承当前 builder 的 push 状态)。 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    parent: org.commonmark.node.Node,
    link: Color,
) {
    var child = parent.firstChild
    while (child != null) {
        when (child) {
            is CmText -> append(child.literal)
            is Code -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                append(child.literal)
                pop()
            }

            is StrongEmphasis -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInline(child, link)
                pop()
            }

            is Emphasis -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInline(child, link)
                pop()
            }

            is Strikethrough -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInline(child, link)
                pop()
            }

            is Link -> appendLink(child, link)
            is SoftLineBreak -> append('\n')
            is HardLineBreak -> append('\n')
            else -> if (child.firstChild != null) appendInline(child, link)
        }
        child = child.next
    }
}

/** 链接:套 [LinkAnnotation.Url],Compose 1.7+ 会自动接上 `LocalUriHandler`。 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLink(node: Link, link: Color) {
    pushLink(
        LinkAnnotation.Url(
            url = node.destination,
            styles = TextLinkStyles(style = SpanStyle(color = link)),
        ),
    )
    appendInline(node, link)
    pop()
}

/**
 * 链接色。**不用** `colorScheme.primary`(那是品牌平安橙 / 青绿)—— 链接需要的是
 * 「能点」的暗示,品牌色留给发送按钮这类真正的主操作。
 *
 * 是个 @Composable getter 而不是普通函数:Builder 扩展是非组合上下文,
 * 在那里读 `MaterialTheme` 会编译不过。
 */
private val linkColor: Color
    @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF0A66C2)
    } else {
        Color(0xFF7FB3FF)
    }

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
