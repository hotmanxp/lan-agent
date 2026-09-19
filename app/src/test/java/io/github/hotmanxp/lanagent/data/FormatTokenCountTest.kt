// data/FormatTokenCountTest.kt — formatTokenCount 的回归测试。
//
// 0.15.1 新增。展示「上下文 current / max」行时调用,口径对齐 opencc-web
// `ConversationInfoCard.fmtTokens`:
//   - < 1000 → 原文(避免「0K」歧义)
//   - >= 1000 → `${Math.round(n / 1000)}K`(million 级别也走 K,不切 M)
//   - null → "—"(跟 web 端"该边未知时用 — 占位"一致)
package io.github.hotmanxp.lanagent.data

import io.github.hotmanxp.lanagent.ui.formatTokenCount
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTokenCountTest {

    @Test
    fun `null renders as dash`() {
        assertEquals("—", formatTokenCount(null))
    }

    @Test
    fun `zero renders as zero not zeroK`() {
        // < 1000 必须保留原文,避免空上下文被显示成 "0K" 看不出来是 0 还是 999
        assertEquals("0", formatTokenCount(0))
    }

    @Test
    fun `sub-thousand renders raw`() {
        assertEquals("200", formatTokenCount(200))
        assertEquals("999", formatTokenCount(999))
    }

    @Test
    fun `thousand-and-above rounds to K`() {
        assertEquals("1K", formatTokenCount(1_000))
        assertEquals("12K", formatTokenCount(12_345))
        assertEquals("200K", formatTokenCount(199_500)) // Math.round: 199.5 → 200
    }

    @Test
    fun `million-level stays in K no M switch`() {
        // 跟 web 端 fmtTokens 同口径:million 级也走 K,不切 M(用户明确要求 K 单位)
        assertEquals("1000K", formatTokenCount(1_000_000))
        // 1234567 / 1000 = 1234.567 → Math.round → 1235
        assertEquals("1235K", formatTokenCount(1_234_567))
    }
}