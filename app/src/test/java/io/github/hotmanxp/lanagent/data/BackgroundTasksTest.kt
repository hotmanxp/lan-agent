// data/BackgroundTasksTest.kt — 后台任务展示辅助函数的回归测试。
//
// 这些函数决定任务栏那一行长什么样,单测的价值全在**边界**:
//   - agent 与 bash 两套 status 枚举文案不同(killed 是「已终止」不是「已取消」)
//   - 认不出的状态必须原样返回,不能让整行空白
//   - 跑中的任务不能显示耗时(那需要一个每帧刷新的时钟)
//   - prompt 里的换行必须被压掉,否则会把任务卡撑高
package io.github.hotmanxp.lanagent.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundTasksTest {

    @Test
    fun `status label covers both agent and bash enums`() {
        assertEquals("运行中", bgStatusLabel("running"))
        assertEquals("排队中", bgStatusLabel("queued"))
        assertEquals("完成", bgStatusLabel("completed"))
        assertEquals("失败", bgStatusLabel("failed"))
        // 两套枚举的分歧点:agent 用 cancelled,bash 用 killed
        assertEquals("已取消", bgStatusLabel("cancelled"))
        assertEquals("已终止", bgStatusLabel("killed"))
        // 新状态不该让整行空白 —— 原样透出,至少还能看出服务端说了什么
        assertEquals("paused", bgStatusLabel("paused"))
    }

    @Test
    fun `running is never terminal`() {
        assertTrue(bgRunning("running"))
        assertTrue(bgRunning("queued"))
        assertTrue(bgTerminal("completed"))
        assertTrue(bgTerminal("failed"))
        assertTrue(bgTerminal("killed"))
        // 未知状态按终态处理 —— 至少不会永远挂在任务栏上
        assertTrue(bgTerminal("paused"))
    }

    @Test
    fun `duration only shows for terminal tasks`() {
        assertEquals("12s", bgDurationLabel(1_000, 13_000, "completed"))
        assertEquals("1m30s", bgDurationLabel(1_000, 91_000, "failed"))
        // 跑中 → null(需要一个实时时钟,任务栏里不划算)
        assertNull(bgDurationLabel(1_000, null, "running"))
        // 时间戳缺失 / 倒挂 / 0 占位 → null,不显示 "-1s" 或 "0s" 这种噪声
        assertNull(bgDurationLabel(null, 13_000, "completed"))
        assertNull(bgDurationLabel(1_000, null, "completed"))
        assertNull(bgDurationLabel(13_000, 1_000, "completed"))
        assertNull(bgDurationLabel(0, 13_000, "completed"))
    }

    @Test
    fun `one line collapses whitespace and truncates`() {
        assertEquals("a b c", "  a \n b\t\tc \n".bgOneLine())
        assertEquals("abc…", "abcdef".bgOneLine(max = 3))
        // 恰好等于上限时不加省略号
        assertEquals("abc", "abc".bgOneLine(max = 3))
    }

    @Test
    fun `agent display name falls back to Agent`() {
        assertEquals("Explore", BgAgentTask(id = "1", agentType = "Explore").displayName)
        // agentType 缺失 / 空白 → 兜底,不能让行首空着
        assertEquals("Agent", BgAgentTask(id = "1").displayName)
        assertEquals("Agent", BgAgentTask(id = "1", agentType = "  ").displayName)
    }

    @Test
    fun `agent detail prefers description over prompt`() {
        val withDesc = BgAgentTask(
            id = "1",
            description = "调研 codegraph",
            input = BgAgentInput(prompt = "很长的原始 prompt"),
        )
        assertEquals("调研 codegraph", withDesc.displayDetail)

        val promptOnly = BgAgentTask(id = "1", input = BgAgentInput(prompt = "只给了 prompt"))
        assertEquals("只给了 prompt", promptOnly.displayDetail)

        // 两样都没有 → 空串(渲染层只画标题,不留悬空分隔)
        assertEquals("", BgAgentTask(id = "1").displayDetail)
    }

    @Test
    fun `bash detail prefers description over command`() {
        assertEquals("起服务", BgBashTask(taskId = "b", command = "npm run dev", description = "起服务").displayDetail)
        assertEquals("npm run dev", BgBashTask(taskId = "b", command = "npm run dev").displayDetail)
    }
}
