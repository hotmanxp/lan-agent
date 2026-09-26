// ui/SvgPreviewHtmlTest.kt — SVG 全屏预览 HTML 的回归测试。
//
// 这个 bug 的形态很值得单测守着:**桌面 Chrome 打开同一份 HTML 完全正常**,
// 只有在 Android WebView 里才塌成白屏。所以"我在浏览器里试过了"这个验证
// 手段在这里会给出错误结论,只能靠钉住那两条约束。
//
// 0.19.1 → 0.19.2 的实测(模拟器 asr_repro / WebView 113):
//
//   缺 doctype 时 `document.compatMode` 是 `BackCompat`(quirks),
//   body 高度算不出来 → `<img>` 的 `getBoundingClientRect()` 是 `[宽, 0]`:
//
//     probe: imgs=1  nw=600 nh=360 complete=true  rect=[980,0]  mode=BackCompat
//
//   图片本身就解码成功了(nw/nh/complete 全对),纯粹是布局塌成 0 高。
//   补 `<!DOCTYPE html>` 后 mode 变 `CSS1Compat`,但 body 仍算不出高 →
//   必须再让 `img` 用 `position:fixed`(包含块是视口,不依赖 body 高度):
//
//     probe: rect=[412,802]  wv=[412,802]  mode=CSS1Compat
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*SvgPreviewHtmlTest*"
package io.github.hotmanxp.lanagent.ui

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SvgPreviewHtmlTest {

    private val html = buildSvgPreviewHtml("PHN2Zy8+")

    @Test
    fun `以 doctype 开头 —— 缺了会落进 quirks 模式`() {
        // quirks 模式下 body 高度算不出来,height:100% / max-height:100%
        // 挂在它下面都会塌成 0 → 整片白。
        assertTrue(
            html.startsWith("<!DOCTYPE html>"),
            "首行必须是 doctype,否则 WebView 走 BackCompat 布局",
        )
    }

    @Test
    fun `img 用 position fixed —— 包含块是视口而非 body`() {
        // 这是真正撑开高度的那条约束:body 在 WebView 里高度为 0,
        // 依赖 body 高度的 width/height:100% 都救不回来。
        assertContains(html, "position:fixed")
    }

    @Test
    fun `base64 原样嵌进 data url`() {
        assertContains(html, "src=\"data:image/svg+xml;base64,PHN2Zy8+\"")
    }

    @Test
    fun `base64 里的加号与斜杠不被转义`() {
        // Base64.NO_WRAP 的输出含 `+` `/`,拼进 data URL 是合法的;
        // 若哪天有人"顺手"做了 URL 编码,解码端会拿到坏字节。
        val h = buildSvgPreviewHtml("ab+/cd==")
        assertContains(h, "base64,ab+/cd==")
    }
}
