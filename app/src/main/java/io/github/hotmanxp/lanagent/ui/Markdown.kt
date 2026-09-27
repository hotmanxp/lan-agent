// ui/Markdown.kt — Markdown 渲染门面 + 代码块(0.23.0 重写)
//
// 0.23.0 起用 commonmark-java 替代原自研解析器(672 行 Regex 状态机)。
// 引擎 / 扩展组合 / 流式安全的表格预处理都在 [CommonmarkText]
// (ui/MarkdownCommonmark.kt),本文件只留两件事:
//   1. [MarkdownText] 门面 —— 保住原调用点的参数形态,九个调用点零改动;
//   2. [CodeBox] 代码块 —— commonmark 的 FencedCodeBlock / IndentedCodeBlock
//      以及工具卡、文件预览、SSH 输出都复用它。
//
// 为什么换掉自研版:
//
//   | 维度         | 自研(旧)                    | commonmark(新)             |
//   |--------------|-----------------------------|---------------------------|
//   | 流式半截围栏 | 手写「未闭合当代码块」规则  | **规范本身**吃到文档末尾   |
//   | GFM 表格     | 手写,且要防半截表头        | 扩展提供,只需打断段落粘连 |
//   | 删除线       | 手写                        | 扩展提供                   |
//   | autolink     | 手写                        | 扩展提供                   |
//   | 任务列表     | 手写                        | 扩展提供                   |
//   | 嵌套 / 对齐  | 拍平成块列表,信息丢失      | AST 全量保留               |
//
// 代价:APK 多约 400 KB(commonmark 本体 ~330 KB + 4 个扩展)。
// 「不加依赖」那条约定本来就是为 markdown 引擎写的,现在用更规范的引擎换掉
// 自研,取舍反而更划算 —— 自研的维护成本是长期付的。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Copy

/** 正文基础样式。保留是因为 [MarkdownText] 的签名里有这个参数(调用点兼容)。 */
@Composable
internal fun markdownBodyStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium

/**
 * 渲染一段 Markdown。
 *
 * **委托给 [CommonmarkText]**。本函数保留为门面,是为了让九个调用点零改动。
 *
 * @param baseStyle 正文样式。commonmark 路径下**不生效**(渲染样式由
 *   [CommonmarkText] 按 heading 级别自定),保留只为调用点签名兼容。
 * @param compact   紧凑模式:块间距收紧,给思考过程这类副文本用。
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") baseStyle: TextStyle = markdownBodyStyle(),
    compact: Boolean = false,
) {
    if (markdown.isBlank()) return
    CommonmarkText(markdown = markdown, modifier = modifier.fillMaxWidth(), compact = compact)
}

@Composable
internal fun CodeBox(body: String, lang: String? = null, label: String? = null) {
    val clipboard = LocalClipboardManager.current
    val code = remember(body) { body.trim('\n') }
    val styles = rememberCodeStyles()
    val language = remember(lang) { codeLanguageForLabel(lang) }
    val highlighted = rememberHighlightedCode(code, language, styles)
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
                    text = (label ?: lang)?.takeIf { it.isNotBlank() } ?: "code",
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
                            imageVector = Lucide.Copy,
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
                text = highlighted,
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
