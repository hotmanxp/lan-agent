// ui/ToolCallWatchdogTest.kt — 工具调用超时兜底(0.30.2)
//
// 钉住三件事:
//  1. 只有 Grep/Glob 纳入看门狗 —— Bash 允许跑 10 分钟,套 60s 会误杀;
//  2. 阈值(60s)必须大于引擎侧最坏情况(30s rg 超时 + 5s core 看门狗),
//     否则会在 core 正常报错之前抢先把它盖掉;
//  3. 设备时钟被往回拨时不能误判超时。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*ToolCallWatchdogTest*"
package io.github.hotmanxp.lanagent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCallWatchdogTest {

    @Test
    fun `Grep 与 Glob 纳入看门狗`() {
        assertTrue(ToolCallWatchdog.isWatched("Grep"))
        assertTrue(ToolCallWatchdog.isWatched("Glob"))
    }

    @Test
    fun `Bash 与其它长跑工具不纳入`() {
        // core 允许 Bash 跑 10 分钟(BASH_MAX_TIMEOUT_MS),
        // Agent 子代理同理 —— 给它们套 60s 会把正常的活判成超时。
        assertFalse(ToolCallWatchdog.isWatched("Bash"))
        assertFalse(ToolCallWatchdog.isWatched("Agent"))
        assertFalse(ToolCallWatchdog.isWatched("Read"))
        assertNull(ToolCallWatchdog.timeoutFor("Bash"))
    }

    @Test
    fun `阈值大于引擎侧最坏情况`() {
        // 引擎侧:30s rg 超时 + 5s core 看门狗 = 35s。
        // App 侧阈值必须 > 35s,否则会抢在 core 正常报错之前。
        assertTrue(
            ToolCallWatchdog.SEARCH_TIMEOUT_MS > 35_000,
            "App 侧阈值 ${ToolCallWatchdog.SEARCH_TIMEOUT_MS} 必须大于引擎侧的 35s",
        )
    }

    @Test
    fun `不到阈值不判超时`() {
        val start = 1_000_000L
        assertFalse(ToolCallWatchdog.isTimedOut("Grep", start, start + 59_999))
    }

    @Test
    fun `到阈值判超时`() {
        val start = 1_000_000L
        assertTrue(ToolCallWatchdog.isTimedOut("Grep", start, start + 60_000))
        assertTrue(ToolCallWatchdog.isTimedOut("Glob", start, start + 90_000))
    }

    @Test
    fun `不纳入的工具永远不判超时`() {
        val start = 1_000_000L
        assertFalse(ToolCallWatchdog.isTimedOut("Bash", start, start + 3_600_000))
    }

    @Test
    fun `时钟回拨不误判超时`() {
        // 设备时钟被往回拨(now < startedAt)时,差值会变成负数。
        // 若不显式挡住,`now - startedAt >= limit` 在负数上恒为 false,
        // 但用绝对值比较的实现就会误杀。这里钉住行为。
        val start = 1_000_000L
        assertFalse(ToolCallWatchdog.isTimedOut("Grep", start, start - 5_000))
    }
}
