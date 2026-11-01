package io.github.hotmanxp.lanagent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 密钥空白字符处理。
 *
 * 背景:用户复制 API key 时下划线常被打成空格(输入法联想 / 软键盘长按 /
 * PDF 复制)。Node 的 `Headers.append` 拒绝含空白字符的 header 值,报
 * "is an invalid header value",而且报错里会把整个 key 打出来。
 */
class EngineCredentialsTest {

    @Test
    fun `sanitized 去掉密钥里的所有空白`() {
        val c = EngineCredentials(
            baseUrl = " https://api.example.com ",
            model = " gpt-4o ",
            apiKey = "sk-cp-abc_Bvp dKQHRk\tM1e9\nbPMY4",
        )
        val clean = c.sanitized()
        assertEquals("sk-cp-abc_BvpdKQHRkM1e9bPMY4", clean.apiKey)
        assertEquals("https://api.example.com", clean.baseUrl)
        assertEquals("gpt-4o", clean.model)
    }

    @Test
    fun `strippedWhitespaceCount 统计去掉的字符数`() {
        val c = EngineCredentials(apiKey = "a b\tc\nd")
        assertEquals(3, c.strippedWhitespaceCount())
        assertEquals(0, EngineCredentials(apiKey = "abcd").strippedWhitespaceCount())
    }

    @Test
    fun `没有空白时 sanitized 是幂等的`() {
        val c = EngineCredentials(apiKey = "sk-abc_123")
        assertEquals(c, c.sanitized())
        assertEquals(0, c.strippedWhitespaceCount())
    }

    @Test
    fun `去掉空白后 key 仍然非空才算完整`() {
        val c = EngineCredentials(baseUrl = "u", model = "m", apiKey = "   ")
        assertTrue(!c.sanitized().isComplete)
    }
}
