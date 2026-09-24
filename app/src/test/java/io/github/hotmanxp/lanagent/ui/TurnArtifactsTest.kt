// ui/TurnArtifactsTest.kt — 「本轮产物」的派生 + 渲染块插入 + 写入白名单。
//
// 这个特性**没有后端**(纯渲染期派生),所以坏起来是静默的:块要么不出现,
// 要么把上一轮的文件算进来,手点很难发现。三条关键不变量:
//
//   1. **轮次以用户消息切分**:`items` 里只有 `AgentItem.UserText` 是边界
//      (服务端的 turnIndex 恒为 0,不能用)。
//   2. **流式中的轮次不出块**,已结束的轮次永久留在原位、只含本轮文件。
//   3. 白名单:**只有写入类工具**计入 —— `Read` / `Grep` 也有 `file_path` 字段,
//      泛化会把只读调用误报成产物。
//
// 另加两条渲染块断言:`PresentFile` 永不进工具折叠组卡(对齐 web
// `skipOuterGroup`),产物块插在锚点所属渲染块**之后**。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*TurnArtifactsTest*"
package io.github.hotmanxp.lanagent.ui

import io.github.hotmanxp.lanagent.data.WriteTarget
import io.github.hotmanxp.lanagent.data.writeTargetOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

private var keySeq = 0

private fun user(text: String): AgentItem.UserText =
    AgentItem.UserText(key = "u-${++keySeq}", text = text, timestamp = null)

private fun text(body: String): AgentItem.AssistantText =
    AgentItem.AssistantText(key = "a-${++keySeq}", text = body, timestamp = null)

private fun think(): AgentItem.Thinking =
    AgentItem.Thinking(key = "t-${++keySeq}", text = "想一下", timestamp = null)

private fun tool(
    name: String,
    path: String? = null,
    toolUseId: String = "tu-${++keySeq}",
): AgentItem.ToolCall {
    val target = path?.let { writeTargetOf(name, wireJson.parseToJsonElement("""{"file_path":"$it"}""")) }
    return AgentItem.ToolCall(
        key = "tool-$toolUseId",
        toolUseId = toolUseId,
        name = name,
        input = path?.let { """{"file_path":"$it"}""" },
        output = "done",
        isError = false,
        timestamp = null,
        write = target,
    )
}

private fun present(path: String, toolUseId: String = "tu-${++keySeq}"): AgentItem.ToolCall =
    AgentItem.ToolCall(
        key = "tool-$toolUseId",
        toolUseId = toolUseId,
        name = "PresentFile",
        input = """{"path":"$path"}""",
        output = "done",
        isError = false,
        timestamp = null,
    )

class TurnArtifactsTest {

    // ===== 写入白名单 =====

    /** 四个写入类工具各自的路径字段;`Read` / `Grep` / 未知工具一律 null。 */
    @Test
    fun `write whitelist covers the four write tools and rejects read tools`() {
        fun target(name: String, json: String) = writeTargetOf(name, wireJson.parseToJsonElement(json))

        assertEquals("写入", target("Write", """{"file_path":"/a.ts"}""")?.label)
        assertEquals("/a.ts", target("Write", """{"file_path":"/a.ts"}""")?.path)
        assertEquals(true, target("Write", """{"file_path":"/a.ts"}""")?.written)

        assertEquals("编辑", target("Edit", """{"file_path":"/a.ts"}""")?.label)
        assertEquals("编辑", target("MultiEdit", """{"file_path":"/a.ts"}""")?.label)
        // NotebookEdit 用的是 notebook_path
        assertEquals("编辑", target("NotebookEdit", """{"notebook_path":"/n.ipynb"}""")?.label)
        assertEquals("/n.ipynb", target("NotebookEdit", """{"notebook_path":"/n.ipynb"}""")?.path)

        assertNull(target("Read", """{"file_path":"/a.ts"}"""))
        assertNull(target("Grep", """{"path":"/a.ts"}"""))
        assertNull(target("Write", """{"content":"no path"}"""))
        // 非字符串路径不认(contentOrNull 会把数字也转成文本 —— 那会造出假产物)
        assertNull(target("Write", """{"file_path":1}"""))
        assertNull(target("Write", """{"file_path":""}"""))
        assertNull(writeTargetOf("Write", null))
    }

    // ===== 轮次切分与结算 =====

    /** 没有用户消息 → 没有轮次 → 空(头部被裁掉的历史片段就是这个形态)。 */
    @Test
    fun `no user message yields no artifacts`() {
        assertTrue(deriveTurnArtifacts(listOf(text("嗨"), tool("Edit", "/a.ts")), closed = true).isEmpty())
    }

    /** 单轮:文件按首次出现顺序去重,同一路径合并 + 计数,Write 优先决定徽标。 */
    @Test
    fun `single closed turn collects files in first appearance order`() {
        val items = listOf(
            user("改一下"),
            tool("Read", "/a.ts"),
            tool("Edit", "/tmp/b.ts"),
            tool("Edit", "/a.ts"),
            tool("Edit", "/a.ts"),
            tool("Write", "/tmp/c.md"),
            text("好了"),
        )

        val turns = deriveTurnArtifacts(items, closed = true)

        assertEquals(1, turns.size)
        val turn = turns.single()
        assertEquals(items.lastIndex, turn.endIndex)
        assertEquals(items[0].key, turn.turnKey)   // 该轮首条用户消息的 key
        assertEquals(listOf("/tmp/b.ts", "/a.ts", "/tmp/c.md"), turn.files.map { it.path })
        assertEquals(listOf(1, 2, 1), turn.files.map { it.count })
        assertEquals(listOf("编辑", "编辑", "写入"), turn.files.map { it.label })
        assertEquals(listOf(false, false, true), turn.files.map { it.written })
    }

    /** 同一路径先 Edit 后 Write → 徽标必须是「写入」(与出现顺序无关)。 */
    @Test
    fun `write wins the badge regardless of order`() {
        val items = listOf(
            user("改"),
            tool("Edit", "/a.ts"),
            tool("Write", "/a.ts"),
        )

        val file = deriveTurnArtifacts(items, closed = true).single().files.single()
        assertEquals("写入", file.label)
        assertEquals(true, file.written)
        assertEquals(2, file.count)
    }

    /** 本轮没动过文件(全是只读 + 正文)→ 不出块。 */
    @Test
    fun `turn without writes yields no block`() {
        val items = listOf(user("看看"), tool("Read", "/a.ts"), text("看完了"))
        assertTrue(deriveTurnArtifacts(items, closed = true).isEmpty())
    }

    /** **流式中的最后一轮不出块**(避免文件列表边跑边跳);已结束的照常出。 */
    @Test
    fun `open last turn is not finalized`() {
        val items = listOf(user("改"), tool("Edit", "/a.ts"))

        assertTrue(deriveTurnArtifacts(items, closed = false).isEmpty())
        assertEquals(1, deriveTurnArtifacts(items, closed = true).size)
    }

    /** 上一轮被新轮顶掉即视为结束 —— 哪怕此刻整体还在流式(用户抢先发了下一条)。 */
    @Test
    fun `previous turn is finalized as soon as a new user message arrives`() {
        val items = listOf(
            user("第一轮"),
            tool("Edit", "/a.ts"),
            user("第二轮"),
            tool("Edit", "/b.ts"),
        )

        val turns = deriveTurnArtifacts(items, closed = false)

        assertEquals(1, turns.size)
        assertEquals(1, turns.single().endIndex)                 // 锚点是第一轮最后一条(item[1])
        assertEquals(listOf("/a.ts"), turns.single().files.map { it.path })
    }

    /** 多轮各自成块、互不累加、锚点准确。 */
    @Test
    fun `each turn gets its own block with its own files`() {
        val items = listOf(
            user("第一轮"),
            tool("Edit", "/a.ts"),
            text("好"),
            user("第二轮"),
            tool("Write", "/b.md"),
            tool("Edit", "/c.ts"),
        )

        val turns = deriveTurnArtifacts(items, closed = true)

        assertEquals(2, turns.size)
        assertEquals(listOf("/a.ts"), turns[0].files.map { it.path })
        // 第一轮止于「第二轮用户消息」的**前一条**(item[2] = 那句「好」),不含边界
        assertEquals(2, turns[0].endIndex)
        assertEquals(items[0].key, turns[0].turnKey)
        assertEquals(listOf("/b.md", "/c.ts"), turns[1].files.map { it.path })
        assertEquals(items.lastIndex, turns[1].endIndex)
        assertTrue(turns[0].turnKey != turns[1].turnKey)
    }

    // ===== 渲染块插入 =====

    /** 产物块插在锚点所属渲染块**之后**;compact 关闭时逐条渲染,顺序不变。 */
    @Test
    fun `artifacts block is inserted after its anchor block`() {
        val items = listOf(
            user("改"),
            tool("Edit", "/a.ts"),
            tool("Edit", "/b.ts"),      // 两次工具 → 聚合段
            text("好了"),
        )
        val turns = deriveTurnArtifacts(items, closed = true)

        val blocks = buildAgentBlocks(items, compact = true, artifacts = turns)

        // 锚点是该轮最后一条消息(结尾那段正文)→ 块插在它**之后**
        // [UserText][ToolGroup][AssistantText][Artifacts]
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is AgentBlock.Single)
        assertTrue(blocks[1] is AgentBlock.ToolGroup)
        assertTrue(blocks[2] is AgentBlock.Single)
        val artifacts = blocks[3] as AgentBlock.Artifacts
        assertEquals(listOf("/a.ts", "/b.ts"), artifacts.files.map { it.path })
    }

    /** 没有产物时块序列与改动前逐位一致(不引入任何多余元素)。 */
    @Test
    fun `no artifacts keeps the block sequence untouched`() {
        val items = listOf(user("改"), tool("Edit", "/a.ts"), tool("Edit", "/b.ts"), text("好了"))

        assertEquals(
            buildAgentBlocks(items, compact = true).map { it.key },
            buildAgentBlocks(items, compact = true, artifacts = emptyList()).map { it.key },
        )
        assertEquals(3, buildAgentBlocks(items, compact = true).size)
    }

    // ===== PresentFile 永不进工具组卡 =====

    /**
     * `PresentFile` 像正文一样**打断段落**:它自带内容,收进「工具调用 · N 次」
     * 等于把用户要看的东西藏进折叠卡(对齐 web `skipOuterGroup`)。
     */
    @Test
    fun `present file breaks tool groups instead of joining them`() {
        val items = listOf(
            user("看看"),
            tool("Edit", "/a.ts"),
            tool("Edit", "/b.ts"),
            present("/tmp/chart.png"),
            tool("Bash"),
            tool("Bash"),
        )

        val blocks = buildAgentBlocks(items, compact = true, artifacts = emptyList())

        // [User][Group(2 edits)][PresentFile Single][Group(2 bash)]
        assertEquals(4, blocks.size)
        assertTrue(blocks[1] is AgentBlock.ToolGroup)
        val single = blocks[2] as AgentBlock.Single
        assertEquals("PresentFile", (items[single.index] as AgentItem.ToolCall).name)
        assertTrue(blocks[3] is AgentBlock.ToolGroup)
        // 聚合段里不能夹着 PresentFile
        (blocks[1] as AgentBlock.ToolGroup).indices.forEach {
            assertTrue((items[it] as AgentItem.ToolCall).name != "PresentFile")
        }
    }

    /** 落单的 PresentFile(整轮只有它)→ 一张单卡,不构成段落。 */
    @Test
    fun `lone present file stays a single block`() {
        val items = listOf(user("看看"), present("/tmp/chart.png"))

        val blocks = buildAgentBlocks(items, compact = true, artifacts = emptyList())

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is AgentBlock.Single)
        assertTrue(blocks[1] is AgentBlock.Single)
    }
}
