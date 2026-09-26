// ui/CodeHighlightTest.kt — 代码块语法高亮的语言映射回归。
//
// 高亮本身(颜色、区间)是渲染细节,不在这里测;这里钉的是**语言推断**这条
// 纯逻辑,因为它有三个容易静默出错的地方:
//
//   1. 内核(dev.snipme:highlights)只认 18 种语言,别名的收敛表写漏一个,
//      结果是「悄悄不着色」—— 不报错,只是没高亮,很难发现;
//   2. `codeLanguageForLabel` 的「像代码就兜底」启发式必须挡住人话标签
//      (`output` / `text` / 中文),否则会把 SSH 输出染成关键字色;
//   3. `codeLanguageLabel` 取扩展名要能处理隐藏文件 / 无扩展名 / 以点结尾,
//      返回 null 才是正确降级,不能返回空串(空串会被当成语言去查表)。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*CodeHighlightTest*"
package io.github.hotmanxp.lanagent.ui

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeHighlightTest {

    // ---- syntaxLanguageFor:别名收敛 ----

    @Test
    fun aliasesMapToKernelLanguages() {
        assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguageFor("kt"))
        assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguageFor("kts"))
        assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguageFor("Kotlin"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, syntaxLanguageFor("tsx"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, syntaxLanguageFor("jsx"))
        assertEquals(SyntaxLanguage.SHELL, syntaxLanguageFor("bash"))
        assertEquals(SyntaxLanguage.SHELL, syntaxLanguageFor("zsh"))
        assertEquals(SyntaxLanguage.CPP, syntaxLanguageFor("c++"))
        assertEquals(SyntaxLanguage.CSHARP, syntaxLanguageFor("cs"))
        assertEquals(SyntaxLanguage.PYTHON, syntaxLanguageFor("py"))
    }

    @Test
    fun unknownLanguageIsNull() {
        assertNull(syntaxLanguageFor("toml"))
        assertNull(syntaxLanguageFor("vue"))
        assertNull(syntaxLanguageFor(""))
        assertNull(syntaxLanguageFor(null))
    }

    /** 带前导点的扩展名(`.kt`)也要认 —— 路径可能整段传进来。 */
    @Test
    fun leadingDotIsStripped() {
        assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguageFor(".kt"))
        assertEquals(SyntaxLanguage.GO, syntaxLanguageFor(".go"))
    }

    // ---- codeLanguageForLabel:围栏标签 ----

    @Test
    fun knownFenceLabelWins() {
        assertEquals(SyntaxLanguage.RUST, codeLanguageForLabel("rust"))
        assertEquals(SyntaxLanguage.SHELL, codeLanguageForLabel("sh"))
    }

    /**
     * 内核不收的语言(`vue` / `toml` / `ini` …)一律不着色。
     *
     * 这里刻意**不**退回内核的 `DEFAULT`:它是全部语言关键字的并集,拿去染
     * 散文会随机命中 `for` / `if` / `new` 这类英文词,呈现成「乱七八糟的彩色」
     * 比不着色更糟。
     */
    @Test
    fun unknownLanguageIsNotHighlighted() {
        assertNull(codeLanguageForLabel("vue"))
        assertNull(codeLanguageForLabel("toml"))
        assertNull(codeLanguageForLabel("brainfuck"))
    }

    /**
     * 人话标签**不能**着色。这是 `output` 那条 bug 的回归:`output` 是纯 ASCII
     * 字母,「像标识符就是代码」的启发式会放它过去;`命令输出` 更暴露 ——
     * `Char.isLetterOrDigit()` 对 CJK 也返回 true。两者都必须返回 null。
     */
    @Test
    fun proseLabelsAreNotTreatedAsCode() {
        assertNull(codeLanguageForLabel("output"))
        assertNull(codeLanguageForLabel("命令输出"))
        assertNull(codeLanguageForLabel("some long descriptive label here"))
        assertNull(codeLanguageForLabel(""))
        assertNull(codeLanguageForLabel(null))
    }

    // ---- codeLanguageLabel:路径 → 标签 ----

    @Test
    fun pathExtensionBecomesLabel() {
        assertEquals("kt", codeLanguageLabel("/Users/x/Main.kt"))
        assertEquals("ts", codeLanguageLabel("src/a/b/index.ts"))
        assertEquals("md", codeLanguageLabel("README.md"))
    }

    @Test
    fun extensionlessPathIsNull() {
        assertNull(codeLanguageLabel("/usr/bin/makefile"))
        assertNull(codeLanguageLabel("Dockerfile"))
        assertNull(codeLanguageLabel("/a/b/"))
        assertNull(codeLanguageLabel(""))
        assertNull(codeLanguageLabel(null))
    }

    /** 隐藏文件 `.gitignore` 的点在开头,不是扩展名分隔符。 */
    @Test
    fun dotfileIsNotAnExtension() {
        assertNull(codeLanguageLabel("/repo/.gitignore"))
    }

    /** 以点结尾(`foo.`)取不到扩展名。 */
    @Test
    fun trailingDotIsNull() {
        assertNull(codeLanguageLabel("/a/b/weird."))
    }

    // ---- isUsableRange:闪退守卫 ----

    /**
     * 这条守卫对应一个**真机上已复现的闪退**(2026-09-26):
     *
     * `Reversed range is not supported` — `AnnotatedString.Builder.toAnnotatedString`
     * 抛的。根因:内核扫描 SHELL 代码时,把字符串里的「星号紧跟斜杠」
     * (形如 `-path "…/node_modules/…"`)误判成块注释结束标记,产出一个
     * `start=56, end=44` 的反向区间。
     *
     * 触发条件是「代码里出现这一对字符」,在 shell 命令里非常常见 —— 所以
     * 这条不能退化成"以后注意点",必须由测试钉住。
     */
    @Test
    fun reversedRangeIsRejected() {
        assertFalse(isUsableRange(start = 56, end = 44, length = 59))
        assertFalse(isUsableRange(start = 5, end = 5, length = 59))   // 空区间也无意义
    }

    @Test
    fun outOfBoundsRangeIsRejected() {
        assertFalse(isUsableRange(start = 0, end = 60, length = 59))  // end 越界
        assertFalse(isUsableRange(start = -1, end = 5, length = 59))  // start 为负
        assertFalse(isUsableRange(start = 0, end = -1, length = 59))
    }

    @Test
    fun normalRangeIsAccepted() {
        assertTrue(isUsableRange(start = 0, end = 59, length = 59))   // 整段
        assertTrue(isUsableRange(start = 26, end = 33, length = 136))
        assertTrue(isUsableRange(start = 0, end = 1, length = 1))
    }

    /**
     * 确认**内核确实会产出反向区间** —— 不是为了修某个我们想象出来的问题。
     *
     * 如果哪天内核升级后这个断言失败了,说明上游修掉了这个 bug,那么
     * [isUsableRange] 的守卫就从「必需」降级为「防御性」;但**不要**顺手删掉
     * 守卫:越界区间是第三方解析器的常规失败模式。
     */
    @Test
    fun kernelReallyProducesReversedRange() {
        val code = """find . -type f -newermt today -not -path "*/node_modules/*""""
        val structure = Highlights.Builder()
            .code(code)
            .language(SyntaxLanguage.SHELL)
            .build()
            .getCodeStructure()
        val reversed = structure.multilineComments.filter { it.start > it.end }
        assertTrue(
            reversed.isNotEmpty(),
            "预期内核为 SHELL 产出反向的块注释区间;若为空说明上游已修复,可放宽守卫",
        )
    }
}
