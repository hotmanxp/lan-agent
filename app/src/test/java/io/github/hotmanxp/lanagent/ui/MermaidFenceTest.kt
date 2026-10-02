// ui/MermaidFenceTest.kt — 围栏块识别
//
// 值得钉的原因:判定写错一个字符的后果分两头 —— 把普通代码块误判成 mermaid
// 会让用户看到一堆渲染失败的空卡片;把 mermaid 漏判则开关看起来"没生效"。
// 两头都是用户立刻能看出来的,放在真机上验成本比单测高得多。
package io.github.hotmanxp.lanagent.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidFenceTest {

    @Test
    fun `裸 mermaid 围栏命中`() {
        assertTrue(isMermaidFence("mermaid"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(isMermaidFence("Mermaid"))
        assertTrue(isMermaidFence("MERMAID"))
    }

    @Test
    fun `带属性时只看第一个词`() {
        assertTrue(isMermaidFence("mermaid {theme:dark}"))
        assertTrue(isMermaidFence("mermaid\t{scale:1.5}"))
    }

    @Test
    fun `前后空白不误伤`() {
        assertTrue(isMermaidFence("  mermaid  "))
    }

    @Test
    fun `别的语言不命中`() {
        assertFalse(isMermaidFence("kotlin"))
        assertFalse(isMermaidFence("js"))
        // 名字里含 mermaid 但不等于 —— 整体比对会错判成真
        assertFalse(isMermaidFence("mermaidjs"))
    }

    @Test
    fun `null 与空串不命中`() {
        assertFalse(isMermaidFence(null))
        assertFalse(isMermaidFence(""))
        assertFalse(isMermaidFence("   "))
    }

    @Test
    fun `缩进代码块(无 info)不命中`() {
        // IndentedCodeBlock 的 info 是 null,不该被当成 mermaid
        assertFalse(isMermaidFence(null))
    }
}
