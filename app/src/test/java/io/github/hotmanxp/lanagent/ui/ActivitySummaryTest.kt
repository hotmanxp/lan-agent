// ui/ActivitySummaryTest.kt — 一行摘要的派生(0.26.3)
//
// 摘要行是**唯一**暴露「这一段干了什么」的入口,派生错了用户既看不出错在哪,
// 也点不进详情去核对。四条不变量:
//
//   1. 同类合并计数,不同名同类的工具(`Bash` / `run_shell_command`)合成一句;
//   2. **分句顺序固定**(执行 → 搜索 → 读取 → …),不跟首次出现顺序走 ——
//      否则同一段工作换个工具调用顺序,摘要文字就跟着变,视觉上像在闪;
//   3. 认不出的工具**按名字分开**且名字必须出现在文案里,否则一段全是未知
//      工具时会退化成「调用 5 次」= 什么也没说;
//   4. 纯思考段没有工具可数 → 回落成「思考过程」,不能出空串或 "执行 0 条命令"。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*ActivitySummaryTest*"
package io.github.hotmanxp.lanagent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private var seq = 0

private fun tool(name: String, input: String? = null, output: String? = "done"): AgentItem.ToolCall {
    val id = "tu-${++seq}"
    return AgentItem.ToolCall(
        key = "tool-$id",
        toolUseId = id,
        name = name,
        input = input,
        output = output,
        isError = false,
        timestamp = null,
    )
}

private fun thinking(text: String = "想一下"): AgentItem.Thinking =
    AgentItem.Thinking(key = "t-${++seq}", text = text, timestamp = null)

class ActivitySummaryTest {

    @Test
    fun `single tool collapses to one line`() {
        assertEquals("执行 1 条命令", activitySummaryText(listOf(tool("Bash"))))
        assertEquals("读取 1 个文件", activitySummaryText(listOf(tool("Read"))))
    }

    @Test
    fun `same action merges into one count across tool name variants`() {
        // Bash 与 run_shell_command 语义相同,不该散成两个分句
        val members = listOf(tool("Bash"), tool("Bash"), tool("run_shell_command"))
        assertEquals("执行 3 条命令", activitySummaryText(members))
    }

    @Test
    fun `mixed actions are ordered by canonical order not by appearance`() {
        // 先 Read 后 Bash:摘要必须仍输出「执行 …,读取 …」,不然行内文字会跳
        val members = listOf(tool("Read"), tool("Read"), tool("Bash"))
        assertEquals("执行 1 条命令,读取 2 个文件", activitySummaryText(members))
    }

    @Test
    fun `unknown tools keep their names and never merge together`() {
        // 用真没登记的名字(登记过的那些见 ACTIONS —— TodoWrite / WebFetch 都是已知类别)
        // 同为 UNKNOWN 档时按首次出现排序(sortBy 稳定),所以 SomeMcpTool 在前
        val members = listOf(tool("SomeMcpTool"), tool("SomeMcpTool"), tool("OtherMcpTool"))
        assertEquals("调用 SomeMcpTool 2 次,调用 OtherMcpTool 1 次", activitySummaryText(members))
    }

    @Test
    fun `unknown tool alone still says something`() {
        assertEquals("调用 Foo 1 次", activitySummaryText(listOf(tool("Foo"))))
    }

    @Test
    fun `thinking only segment falls back to the thinking label`() {
        assertEquals(THINKING_LABEL, activitySummaryText(listOf(thinking())))
        assertEquals(THINKING_LABEL, activitySummaryText(listOf(thinking(), thinking("再想"))))
    }

    @Test
    fun `thinking mixed with tools is described by the tools`() {
        // 思考不参与计数,但也不能把工具那一半吃掉
        val members = listOf(thinking(), tool("Bash"), thinking("再想"), tool("Bash"))
        assertEquals("执行 2 条命令", activitySummaryText(members))
    }

    @Test
    fun `running tools produce no output yet`() {
        // running 的工具 output == null:摘要照样计数(它确实跑过了),
        // 状态由 ActivityLine 的转圈 / 颜色承担,不在文案里。
        val running = tool("Bash").copy(output = null)
        assertEquals("执行 1 条命令", activitySummaryText(listOf(running)))
    }

    // ===== 详情弹层里的主语 =====

    @Test
    fun `subject picks the command for shell tools`() {
        assertEquals("npm test", toolSubject(tool("Bash", input = "npm test")))
    }

    @Test
    fun `subject picks file path from json input`() {
        assertEquals("/a/b.ts", toolSubject(tool("Read", input = """{"file_path":"/a/b.ts"}""")))
        assertEquals("foo", toolSubject(tool("Edit", input = """{"file_path":"foo","content":"x"}""")))
    }

    @Test
    fun `subject survives truncated json and multi line input`() {
        // 写文件的入参早被 capForDisplay 截断成非法 JSON —— 这不是边角路径,
        // Write / Edit 必然走这里(见 ActivitySummary.LOOSE_FIELD 的说明)。
        // 不能抛,更不能把原始 JSON 糊在标题上。
        val truncated = tool("Write", input = """{"file_path":"/a.ts","content":"很长的内容…""")
        assertEquals("/a.ts", toolSubject(truncated))
        // 同一条路,路径不是第一个键也要捞到
        assertEquals(
            "/b.ts",
            toolSubject(tool("Edit", input = """{"old_string":"x","file_path":"/b.ts","new_string":"yy""")),
        )
        // 多行命令收成一行,否则摘要行行高会跳
        assertEquals("cd /tmp", toolSubject(tool("Bash", input = "cd /tmp\nls -la")))
    }

    @Test
    fun `subject falls back to the tool name when there is no input`() {
        assertEquals("Bash", toolSubject(tool("Bash")))
        assertEquals("Bash", toolSubject(tool("Bash", input = "   ")))
    }

    @Test
    fun `subject caps long values on one line`() {
        val long = "x".repeat(200)
        val s = toolSubject(tool("Bash", input = long))
        assertTrue(s.length <= 72, "got ${s.length}")
        assertTrue(s.endsWith("…"))
    }

    // ===== 详情行文案 =====

    @Test
    fun `past tense reads as a completed action`() {
        assertEquals("已执行", activityStatFor("Bash").pastTense())
        assertEquals("已读取", activityStatFor("Read").pastTense())
        assertEquals("已调用 Foo", activityStatFor("Foo").pastTense())
    }
}
