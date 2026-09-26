// ui/CodeHighlight.kt — 代码块语法高亮(内核 `dev.snipme:highlights`)。
//
// 职责只有两件:
//   1. 把「文件路径 / 语言标签」归一化成内核的 `SyntaxLanguage` 枚举;
//   2. 把内核分析出的 `CodeStructure`(按类型分好的区间集合)染成 AnnotatedString。
//
// 为什么按类型字段而非 `Highlights.getHighlights()`:`getHighlights()` 返回的是
// `ColorHighlight(location, rgb)`,内核自己的主题调色板只有 6 套(其中 light 变体
// 实际就是深色,亮色下标点/关键字会糊在白底上)。直接取 `CodeStructure` 的
// keywords / strings / comments 字段,颜色完全由本项目的 [WbCodeStyles] 决定,
// 跟 WorkBuddy 色板对齐,也不必让内核的 `SyntaxTheme` 参与。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 代码块里的各类 token 颜色,由 [WbCodeStyles] 提供深浅两套。 */
@Immutable
data class CodeStyles(
    val keyword: Color,
    val string: Color,
    val literal: Color,
    val comment: Color,
    val annotation: Color,
    val punctuation: Color,
    val mark: Color,
)

/**
 * 亮色:关键字紫 / 字符串绿 / 注释灰,这是主流浅底编辑器(Atom One Light、
 * VS Code Light+)的惯例 —— 蓝绿橙紫都压暗过,保证在白卡上有足够对比度,
 * 且都避开品牌橙 `#ff6600`,免得代码块跟「主按钮」抢视觉。
 */
private val LightCodeStyles = CodeStyles(
    keyword = Color(0xFF9C27B0),
    string = Color(0xFF2E7D32),
    literal = Color(0xFFC25E00),
    comment = Color(0xFF8C8C8C),
    annotation = Color(0xFF7A5AF8),
    punctuation = Color(0xFF303030),
    mark = Color(0xFF00796B),
)

/** 深色:亮一档保证在 `#26282C` 卡片底上可读。 */
private val DarkCodeStyles = CodeStyles(
    keyword = Color(0xFFC792EA),
    string = Color(0xFF7EC699),
    literal = Color(0xFFF0B160),
    comment = Color(0xFF9AA0A8),
    annotation = Color(0xFF9ECBFF),
    punctuation = Color(0xFFD7DBE0),
    mark = Color(0xFF4DD0E1),
)

@Composable
internal fun rememberCodeStyles(): CodeStyles =
    if (LocalWbDarkTheme.current) DarkCodeStyles else LightCodeStyles

/**
 * 内核是 highlight.js 的 Kotlin 移植,语言表只有 18 项 —— 比 web 端
 * (Shiki / highlight.js 全集)窄得多,所以这里做一层**别名收敛**:
 * 认不出的返回 null,交给 [codeLanguageForLabel] 兜底成纯文本。
 */
internal fun syntaxLanguageFor(name: String?): SyntaxLanguage? {
    val key = name?.trim()?.lowercase(Locale.ROOT)?.removePrefix(".")?.takeIf { it.isNotBlank() }
        ?: return null
    return when (key) {
        "kt", "kts", "kotlin" -> SyntaxLanguage.KOTLIN
        "java" -> SyntaxLanguage.JAVA
        "js", "jsx", "mjs", "cjs", "javascript", "node" -> SyntaxLanguage.JAVASCRIPT
        "ts", "tsx", "typescript" -> SyntaxLanguage.TYPESCRIPT
        "py", "python" -> SyntaxLanguage.PYTHON
        "rb", "ruby", "gemfile" -> SyntaxLanguage.RUBY
        "php" -> SyntaxLanguage.PHP
        "go", "golang" -> SyntaxLanguage.GO
        "rs", "rust" -> SyntaxLanguage.RUST
        "swift" -> SyntaxLanguage.SWIFT
        "c", "h" -> SyntaxLanguage.C
        "cpp", "c++", "cc", "cxx", "hpp", "hh", "hxx" -> SyntaxLanguage.CPP
        "cs", "csharp" -> SyntaxLanguage.CSHARP
        "dart" -> SyntaxLanguage.DART
        "pl", "perl" -> SyntaxLanguage.PERL
        "coffee", "coffeescript" -> SyntaxLanguage.COFFEESCRIPT
        "sh", "bash", "zsh", "shell", "shellscript", "console", "terminal" -> SyntaxLanguage.SHELL
        else -> null
    }
}

/**
 * 语言标签 → 着色语言。**只认 [syntaxLanguageFor] 里明确列出的语言**,认不出
 * 一律返回 null(退回纯文本)。
 *
 * 这里刻意**不做**「看着像标识符就退回 `DEFAULT`」的启发式,原因是它两头都不
 * 讨好:
 *   - 挡不住人话标签 —— `output` / `text` 也是纯 ASCII 字母,`isLetterOrDigit()`
 *     对 CJK 同样返回 true(所以 `命令输出` 也会被判成「像代码」);
 *   - 内核的 `DEFAULT` 是**全部语言关键字的并集**,拿去染散文会随机命中
 *     `for` / `if` / `new` / `class` 这些英文词,看着像高亮坏了。
 *
 * 内核本来也只有 18 种语言(vue / svelte / toml / ini 都没有),认不出是常态,
 * 老实不着色比乱着色强。
 */
internal fun codeLanguageForLabel(label: String?): SyntaxLanguage? = syntaxLanguageFor(label)

/**
 * 把纯文本片段染成带 token 颜色的 AnnotatedString。
 *
 * 区间语义:`PhraseLocation` 是 **`[start, end)` 左闭右开**,与 Compose
 * `addStyle(start, end)` 一致 —— 但区间**不保证合法**,必须走 [styleRanges]
 * 校验后再用(原因见该函数注释)。
 *
 * **没有长度上限**:本函数只允许在后台线程调(见 [rememberHighlightedCode]),
 * 内核对密集代码的耗时是超线性的,放主线程必卡帧;在后台线程上,大文件多算
 * 几百毫秒只是「颜色晚一点出现」,UI 不受影响。
 */
internal fun highlightCode(code: String, language: SyntaxLanguage?, styles: CodeStyles): AnnotatedString {
    if (language == null) return AnnotatedString(code)

    // 整个构建过程都兜住:内核是第三方解析器,它的输出是不可信输入 ——
    // [styleRanges] 已经挡住了已知的非法区间,这里是第二道保险,代价只是
    // 一个 try/catch,而漏掉的代价是整个会话屏幕闪退。
    return runCatching {
        val structure = Highlights.Builder().code(code).language(language).build().getCodeStructure()
        buildAnnotatedString {
            append(code)
            // 顺序即优先级:粗粒度的先铺,细粒度的后盖。注释/字符串放最后,
            // 这样字符串里出现的 `//` 或关键字不会被误染成关键字色。
            structure.annotations.forEach { styleRanges(it, styles.annotation) }
            structure.literals.forEach { styleRanges(it, styles.literal) }
            structure.punctuations.forEach { styleRanges(it, styles.punctuation) }
            structure.marks.forEach { styleRanges(it, styles.mark) }
            structure.keywords.forEach { styleRanges(it, styles.keyword, bold = true) }
            structure.strings.forEach { styleRanges(it, styles.string) }
            structure.multilineComments.forEach { styleRanges(it, styles.comment, italic = true) }
            structure.comments.forEach { styleRanges(it, styles.comment, italic = true) }
        }
    }.getOrElse { AnnotatedString(code) }
}

/**
 * 区间是否可安全交给 `AnnotatedString.Builder.addStyle`。
 *
 * 抽成顶层函数是为了可单测 —— 这条判定挡的是**已复现的闪退**,不能只靠
 * 肉眼看渲染结果来验证。
 */
internal fun isUsableRange(start: Int, end: Int, length: Int): Boolean =
    start >= 0 && end <= length && start < end

/**
 * 只接受合法区间后 `addStyle`。
 *
 * **必须校验**:内核会产出 `start > end` 的区间,直接喂给 Compose 会抛
 * `IllegalArgumentException: Reversed range is not supported`(在
 * `AnnotatedString.Builder.toAnnotatedString` 里炸),整个会话屏幕闪退。
 *
 * 已复现的真实用例(SHELL,`find … -path` 里写了个通配路径):字符串内部
 * 的「星号紧跟斜杠」被内核的块注释扫描器当成了注释结束标记,算出
 * `start=56, end=44`。也就是「代码里出现这一对字符就会崩」—— 相当常见的
 * 输入,不是边角,不能只靠 catch。
 *
 * 越界(`end > code.length`)同样丢弃:内核按 UTF-16 下标算,但这类解析器
 * 处理多字节字符时算错区间并不少见,而 `addStyle` 越界一样会抛。
 */
private fun AnnotatedString.Builder.styleRanges(
    range: PhraseLocation,
    color: Color,
    bold: Boolean = false,
    italic: Boolean = false,
) {
    if (!isUsableRange(range.start, range.end, length)) return
    addStyle(
        SpanStyle(
            color = color,
            fontWeight = if (bold) FontWeight.Medium else null,
            fontStyle = if (italic) FontStyle.Italic else null,
        ),
        range.start,
        range.end,
    )
}

/**
 * 带缓存 + 后台计算的入口。[language] 为 null 时返回纯文本。
 *
 * **耗时部分必须在后台线程**:内核解析对密集代码是超线性的(5000 行 ≈ 710ms,
 * 见文件末尾基准),直接在 `remember {}` 里算就是主线程卡帧。所以这里用
 * `produceState`:先渲染纯文本立即可见,`Dispatchers.Default` 算完高亮再原位
 * 替换 —— 大文件「先白后彩」而不是「先卡后彩」。
 *
 * 缓存键含语言与明暗 —— 主题切换后必须重算,否则深色下会残留浅色 token 色。
 * 语言为 null 时内核根本不会跑,直接同步返回,不值得开协程。
 */
@Composable
internal fun rememberHighlightedCode(
    code: String,
    language: SyntaxLanguage?,
    styles: CodeStyles,
): AnnotatedString {
    if (language == null) return remember(code) { AnnotatedString(code) }
    return produceState(
        initialValue = AnnotatedString(code),
        key1 = code,
        key2 = language,
        key3 = styles,
    ) {
        value = withContext(Dispatchers.Default) { highlightCode(code, language, styles) }
    }.value
}

/**
 * 工具调用卡的代码语言提示。
 *
 * 优先用**该调用写入的文件路径**(`Write` / `Edit` / `MultiEdit` /
 * `NotebookEdit` 的 `write` 字段,入参里已经抽好)—— 这是最可靠的信号:
 * 同一次 `Write` 的入参是目标语言的代码,输出只是一句「已写入」。
 *
 * 取不到路径时只认 `Bash`(入参就是 shell 命令 → `sh`),其余返回 null 退化成
 * 纯文本。**故意不按工具名大面积兜底**:写文件类工具的入参是 JSON 结构体
 * (`{"file_path": …, "content": …}`),拿「全语言关键字并集」去染它,颜色会
 * 落在字符串内容里,看着像高亮坏了 —— 宁可不着色。
 */
internal fun toolCodeLabel(item: AgentItem.ToolCall): String? =
    item.write?.path?.let { codeLanguageLabel(it) }
        ?: if (item.name == "Bash" || item.name == "BashOutput") "sh" else null

/**
 * 从文件路径取出语言标签(`/a/b/Main.kt` → `kt`),直接喂给 [CodeBox] 的
 * `lang` 参数 —— 再由 [codeLanguageForLabel] 收敛成内核枚举,调用点不必知道
 * `SyntaxLanguage` 的存在。
 *
 * 取不到扩展名(无点 / 隐藏文件 / 以点结尾)返回 null,交给纯文本兜底。
 */
internal fun codeLanguageLabel(path: String?): String? {
    val base = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
    val dot = base.lastIndexOf('.')
    if (dot <= 0 || dot == base.length - 1) return null
    return base.substring(dot + 1)
}

// ── 性能取舍(实测数据)─────────────────────────────────────────────────
//
// 在 M 系主机上对内核 1.0.0 实测(密集代码,KOTLIN):
//
//   100 行 / 2.6k 字符      1.0 ms
//   400 行 / 10.7k 字符     5.3 ms
//   800 行 / 21.5k 字符    17.6 ms   ← 已超一帧(16.7ms)
//   5000 行 / 137k 字符   710.2 ms   ← 主线程必崩
//
// 注意这不是线性的:瓶颈在内核的标点/括号定位器(`MarkLocator` 对每个标点做
// 全串 `indicesOf`,O(字符数 × 不同标点数))。同样 137k 字符的**纯文本**(无
// 标点)只要 22.8ms —— 所以卡顿由代码密度决定,行数只是它的近似。
//
// 阈值方案演进:先是 4000 字符 → 普通 100 行源码就 3-6k,大量文件被挡成纯
// 文本;再是 1000 行 → 仍要维护「超限没颜色」的降级。现在**不再设阈值**:
// `rememberHighlightedCode` 把解析挪到 `Dispatchers.Default`,主线程零耗时,
// 大文件「先纯文本立即可见,高亮算完原位替换」。代价只是大文件颜色晚几百毫秒
// 出现,不再有「没有颜色」这一说。唯一要保持的纪律: `highlightCode` **只准在
// 后台线程调** —— 哪天有人把它挪回 `remember {}` 同步算,5000 行文件会当场
// 把主线程卡住 700ms。
