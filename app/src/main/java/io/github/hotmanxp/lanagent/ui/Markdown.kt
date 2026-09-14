// ui/Markdown.kt — 自研 Markdown 渲染器(Compose)。
//
// 为什么不用现成库:项目约定「不加依赖」(AGENTS.md 技术栈表里没有 markdown
// 库),而且移动端只需要一个**子集** —— Agent 的正文产出集中在标题、列表、
// 行内代码、围栏代码、表格、引用这几类,通用库(语法高亮 / 数学公式 / HTML
// 子集)会带来几 MB 体积和一堆用不上的能力。
//
// 覆盖范围:
//   块级  # 标题 · 段落 · ``` 围栏代码 · > 引用 · -/1. 列表(可嵌套 + 任务框)
//        表格 · --- 分割线
//   行内  **粗** · *斜* · `代码` · ~~删除线~~ · [文字](链接) · 裸链接 · 转义 \
//
// 两条工程约束:
//   1. **流式安全**:SSE 边收边渲染时,``` 和表格都可能只写了一半。未闭合的
//      围栏直接当成代码块渲染(而不是把后面所有内容吞掉);表格行数不足时按
//      普通段落处理。任何情况下都不能崩、不能卡。
//   2. **解析与渲染分离**:`MarkdownParser.parse()` 不依赖 Compose,输入字符串
//      输出块列表;渲染层只做映射,方便以后补单测或换渲染实现。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 1. 模型
// ============================================================

/** 块级元素。列表项、表格行都挂在对应块里,渲染层不用再判断上下文。 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String?, val body: String) : MdBlock
    data class Bullets(val items: List<MdItem>) : MdBlock
    data class Numbered(val start: Int, val items: List<MdItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** 一个列表项。`level` 是缩进层级(0 起),`checked` 非空表示这是任务项。 */
internal data class MdItem(
    val text: String,
    val level: Int,
    val checked: Boolean? = null,
)

// ============================================================
// 2. 解析(纯函数,不依赖 Compose)
// ============================================================

internal object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
    private val RULE = Regex("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    private val LIST = Regex("^( {0,12})([-*+]|(\\d{1,9})[.)])\\s+(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`~]*)$")

    fun parse(raw: String): List<MdBlock> {
        if (raw.isBlank()) return emptyList()
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // ---- 围栏代码(未闭合也吃掉剩余内容,流式安全) ----
            val fence = FENCE.find(line)
            if (fence != null) {
                val token = fence.groupValues[1].take(3)
                val lang = fence.groupValues[2].trim().takeIf { it.isNotEmpty() }
                val body = StringBuilder()
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(token)) {
                        i++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                out += MdBlock.Code(lang, body.toString())
                continue
            }

            // ---- 空行 ----
            if (line.isBlank()) {
                i++
                continue
            }

            // ---- 标题 ----
            val heading = HEADING.find(line)
            if (heading != null) {
                out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
                continue
            }

            // ---- 分割线 ----
            if (RULE.matches(line)) {
                out += MdBlock.Rule
                i++
                continue
            }

            // ---- 引用(连续的 > 行合并成一段) ----
            if (line.trimStart().startsWith(">")) {
                val buf = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    buf += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                // 引用里再嵌列表/代码就不再展开渲染了 —— 折叠成一整段,
                // 观感仍然清晰,复杂度省下来。
                out += MdBlock.Quote(buf.joinToString("\n").trim())
                continue
            }

            // ---- 表格(必须「表头 + 分隔行」成对出现,否则按段落走) ----
            if (line.contains('|') &&
                i + 1 < lines.size &&
                lines[i + 1].contains('-') &&
                TABLE_SEP.matches(lines[i + 1])
            ) {
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }

            // ---- 列表 ----
            val listHead = LIST.find(line)
            if (listHead != null) {
                val ordered = listHead.groupValues[3].isNotEmpty()
                val start = listHead.groupValues[3].toIntOrNull() ?: 1
                val items = mutableListOf<MdItem>()
                while (i < lines.size) {
                    val m = LIST.find(lines[i]) ?: break
                    if (m.groupValues[3].isNotEmpty() != ordered) break
                    val indent = m.groupValues[1].length
                    val content = m.groupValues[4]
                    val checked = when {
                        content.startsWith("[ ]") -> false
                        content.startsWith("[x]") || content.startsWith("[X]") -> true
                        else -> null
                    }
                    val text = if (checked != null) content.drop(3).trimStart() else content
                    items += MdItem(
                        text = text,
                        level = (indent / 2).coerceIn(0, 4),
                        checked = checked,
                    )
                    i++
                }
                out += if (ordered) MdBlock.Numbered(start, items) else MdBlock.Bullets(items)
                continue
            }

            // ---- 段落:吞到空行 / 下一个块开始为止 ----
            val buf = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.isBlank()) break
                if (buf.isNotEmpty() && isBlockStart(cur, lines.getOrNull(i + 1))) break
                buf += cur
                i++
            }
            out += MdBlock.Paragraph(buf.joinToString("\n").trim())
        }
        return out
    }

    /** 判断某行是否会开启一个新块 —— 段落遇到它就该收尾。 */
    private fun isBlockStart(line: String, next: String?): Boolean =
        FENCE.containsMatchIn(line) ||
            HEADING.matches(line) ||
            RULE.matches(line) ||
            line.trimStart().startsWith(">") ||
            LIST.matches(line) ||
            (line.contains('|') && next != null && next.contains('-') && TABLE_SEP.matches(next))

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

// ============================================================
// 3. 行内解析 → AnnotatedString
// ============================================================

/** 行内用到的几个颜色,统一从主题取,深色模式自动跟着变。 */
private data class InlineColors(
    val link: Color,
    val code: Color,
    val codeBg: Color,
    val strikethrough: Color,
)

private val MARKER_CHARS = setOf('*', '_', '`', '~', '[', ']', '(', ')', '#', '>', '\\')

/**
 * 行内 Markdown → AnnotatedString。
 *
 * 递归下降的极简实现:遇到成对标记就带着新样式递归解析内部内容,所以
 * `**粗体里的 `code`**` 这类嵌套是天然支持的。找不到闭合标记时,标记字符
 * 原样输出(流式中间态常见:半截 `**` 不能把后面整段吃掉)。
 */
private fun buildInline(text: String, c: InlineColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }

    while (i < text.length) {
        val ch = text[i]

        // 转义:\* 之类原样输出
        if (ch == '\\' && i + 1 < text.length && text[i + 1] in MARKER_CHARS) {
            plain.append(text[i + 1]); i += 2; continue
        }

        // 行内代码:内容原样,不做递归解析
        if (ch == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                flush()
                withStyle(
                    SpanStyle(
                        color = c.code,
                        background = c.codeBg,
                        fontFamily = FontFamily.Monospace,
                    )
                ) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }

        // 粗体 / 斜体 / 删除线
        val marker = when {
            text.startsWith("**", i) -> "**"
            text.startsWith("__", i) -> "__"
            text.startsWith("~~", i) -> "~~"
            ch == '*' || ch == '_' -> ch.toString()
            else -> null
        }
        if (marker != null) {
            val end = text.indexOf(marker, i + marker.length)
            // 单字符标记要防止「2*3*4」这种误伤:闭合符号后不能紧跟字母数字
            val ok = end > i + marker.length &&
                (marker.length > 1 || end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
            if (ok) {
                flush()
                val style = when (marker) {
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "~~" -> SpanStyle(
                        textDecoration = TextDecoration.LineThrough,
                        color = c.strikethrough,
                    )
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(style) {
                    append(buildInline(text.substring(i + marker.length, end), c))
                }
                i = end + marker.length
                continue
            }
        }

        // 链接 [文字](url)
        if (ch == '[') {
            val close = text.indexOf(']', i + 1)
            if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                val end = text.indexOf(')', close + 2)
                if (end > close) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, end).trim().substringBefore(' ')
                    flush()
                    if (url.isNotBlank()) {
                        withLink(LinkAnnotation.Url(url, linkStyles(c))) {
                            append(label.ifBlank { url })
                        }
                    } else {
                        append(label)
                    }
                    i = end + 1
                    continue
                }
            }
        }

        // 裸链接 http(s)://…
        if (ch == 'h' && (text.startsWith("http://", i) || text.startsWith("https://", i))) {
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            var url = text.substring(i, end)
            while (url.isNotEmpty() && url.last() in ".,;:!?)]}\u3002\uff0c\u3001") {
                url = url.dropLast(1)
            }
            flush()
            withLink(LinkAnnotation.Url(url, linkStyles(c))) { append(url) }
            i += url.length
            continue
        }

        plain.append(ch)
        i++
    }
    flush()
}

private fun linkStyles(c: InlineColors) = TextLinkStyles(
    style = SpanStyle(color = c.link, textDecoration = TextDecoration.Underline),
)

// ============================================================
// 4. 渲染
// ============================================================

/** 正文默认样式 —— 15sp/23sp 行高,对齐 WorkBuddy 的正文观感。 */
@Composable
internal fun markdownBodyStyle(): TextStyle =
    LocalTextStyle.current.copy(
        fontSize = 15.sp,
        lineHeight = 23.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )

/**
 * 渲染一段 Markdown。
 *
 * @param baseStyle 正文样式(默认见 [markdownBodyStyle])
 * @param compact   紧凑模式:块间距 7dp → 4dp,给思考过程这类副文本用
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = markdownBodyStyle(),
    compact: Boolean = false,
) {
    if (markdown.isBlank()) return
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val colors = InlineColors(
        link = MaterialTheme.colorScheme.primary,
        code = MaterialTheme.colorScheme.onSurface,
        codeBg = MaterialTheme.colorScheme.surfaceContainerHighest,
        strikethrough = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val body = baseStyle.copy(color = MaterialTheme.colorScheme.onSurface)
    val muted = baseStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = buildInline(block.text, colors),
                    fontSize = when (block.level) {
                        1 -> 21.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 15.5.sp
                    },
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        top = if (block.level <= 2) 5.dp else 2.dp,
                    ),
                )

                is MdBlock.Paragraph -> Text(
                    text = buildInline(block.text, colors),
                    style = body,
                )

                is MdBlock.Code -> CodeBox(block.body, block.lang)

                is MdBlock.Bullets -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.items.forEach { item ->
                        ListItemRow(item, body, muted, colors)
                    }
                }

                is MdBlock.Numbered -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.items.forEachIndexed { idx, item ->
                        ListItemRow(
                            item = item,
                            body = body,
                            muted = muted,
                            colors = colors,
                            ordinal = "${block.start + idx}.",
                        )
                    }
                }

                is MdBlock.Quote -> QuoteBlock(block.text, muted, colors)

                is MdBlock.Table -> TableBlock(block, body, colors)

                MdBlock.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** 列表项。有 `ordinal` 是数字列表,否则画项目符号;任务项画勾选框。 */
@Composable
private fun ListItemRow(
    item: MdItem,
    body: TextStyle,
    muted: TextStyle,
    colors: InlineColors,
    ordinal: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.level * 16).dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            item.checked != null -> Icon(
                imageVector = if (item.checked) {
                    Icons.Default.CheckCircle
                } else {
                    Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (item.checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(14.dp),
            )

            ordinal != null -> Text(text = ordinal, style = muted, modifier = Modifier.width(24.dp))

            else -> Text(
                text = when (item.level % 3) {
                    0 -> "•"
                    1 -> "◦"
                    else -> "▪"
                },
                style = muted,
                modifier = Modifier.width(14.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = buildInline(item.text, colors),
            style = body,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuoteBlock(text: String, style: TextStyle, colors: InlineColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = buildInline(text, colors),
            style = style,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table, body: TextStyle, colors: InlineColors) {
    val columns = maxOf(
        table.header.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columns == 0) return

    // 窄屏放不下时整表横向滚动(而不是把每列挤成竖排单字),列宽给一个下限。
    val minCell = 96.dp
    val cell = Modifier
        .widthIn(min = minCell)
        .padding(horizontal = 10.dp, vertical = 8.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                repeat(columns) { col ->
                    Text(
                        text = buildInline(table.header.getOrElse(col) { "" }, colors),
                        style = body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        modifier = cell,
                    )
                }
            }
            table.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row {
                    repeat(columns) { col ->
                        Text(
                            text = buildInline(row.getOrElse(col) { "" }, colors),
                            style = body.copy(fontSize = 13.sp, lineHeight = 19.sp),
                            modifier = cell,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 代码块。等宽字体 + 独立底框 + 可横滚,右上角给一个复制键 —— 手机上抄
 * 命令 / diff 的场景比桌面还多。
 */
@Composable
internal fun CodeBox(body: String, lang: String? = null) {
    val clipboard = LocalClipboardManager.current
    val code = remember(body) { body.trim('\n') }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = lang?.takeIf { it.isNotBlank() } ?: "code",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (code.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { clipboard.setText(AnnotatedString(code)) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制代码",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "复制",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
