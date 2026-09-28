// aa/feature/sessions/SessionTitleTest.kt — 会话标题派生的回归。
//
// 背景:AA 服务端建会话时 title 传什么就存什么,**不会**替客户端从首条消息派生
// 标题。所以历史上客户端把标题栏里的占位文案「新建会话」原样发上去,导致列表里
// 每条会话都叫「新建会话」。现在改成:用户没手动改标题 → title 传 null → 客户端
// 自己按首条消息派生(规则对齐 zai 的 `deriveTitleFromPrompt`,见
// opencc-web `packages/zai/src/server/routes/agent.ts:2409`)。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*SessionTitleTest*"
package io.github.hotmanxp.lanagent.aa.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTitleTest {
    @Test
    fun usesFirstLineOfTheFirstMessage() {
        assertEquals("帮我看看这个 bug", deriveSessionTitleFromContent("帮我看看这个 bug"))
    }

    @Test
    fun ignoresSurroundingWhitespace() {
        assertEquals("跑一下测试", deriveSessionTitleFromContent("\n  跑一下测试  \n"))
    }

    @Test
    fun takesOnlyTheFirstLine() {
        assertEquals("第一行", deriveSessionTitleFromContent("第一行\n第二行\n第三行"))
    }

    @Test
    fun keepsTitlesAtTheLimit() {
        val exact = "标".repeat(50)
        assertEquals(exact, deriveSessionTitleFromContent(exact))
    }

    @Test
    fun truncatesWithEllipsisOverTheLimit() {
        val long = "标".repeat(51)
        val title = deriveSessionTitleFromContent(long)
        assertEquals(50, title?.length)
        assertEquals("标".repeat(49) + "…", title)
    }

    @Test
    fun returnsNullWhenThereIsNoText() {
        // 纯附件开场没有可派生的文本 —— 交给列表兜底文案,而不是回退成「新建会话」。
        assertNull(deriveSessionTitleFromContent(""))
        assertNull(deriveSessionTitleFromContent("   \n  \n"))
    }
}
