// data/SlashCommandsTest.kt — 命令面板的输入解析 / 过滤 / 结果分流的回归测试。
//
// 这里钉三类**真机上很难排查**的问题:
//   1. 路径误判 —— 用户往对话里贴 `/Users/ethan/code/xxx`,如果被判成命令,
//      消息就发不出去(被当成「未知命令 unknown」吞掉),用户只会看到"发了没反应";
//   2. 过滤排序 —— 命令段必须整体排在 skill 段之前(哪怕某个 skill 分数更高),
//      否则打 `/stat` 时回车选中 `strategic-compact`,用户以为命令坏了;
//   3. 服务端返回形状 —— `payload` 随 type 变,取值帮手必须只认自己那一种,
//      不能串味(比如把 unknown 的 input 当成 prompt 的 rendered 发出去)。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlashCommandsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    // ===== 输入解析 =====

    @Test
    fun `path input is not a command`() {
        // `/` 不在命令名字符集里 → 首 token 含斜杠 → 判成普通消息。
        assertNull(parseSlashInput("/Users/ethan/code/lan-agent"))
        assertNull(parseSlashInput("/etc/hosts 看看这个文件"))
        assertNull(parseSlashInput("~/notes/foo.md"))
        // 中文名也不行:命令名只认 ASCII,否则「/中文输入法」会被判成命令名
        // 叫「中文输入法」、再被 matchCommand 判成 unknown 命令。
        assertNull(parseSlashInput("/中文 你好"))
        assertNull(parseSlashInput("/。"))
    }

    @Test
    fun `bare slash and non slash are not commands`() {
        assertNull(parseSlashInput("/"))
        assertNull(parseSlashInput(""))
        assertNull(parseSlashInput("clear"))
        // 前导空白可以有(用户手滑打了空格),但名字本身要合法。
        assertNotNull(parseSlashInput("  /clear"))
    }

    @Test
    fun `name and args are split on first whitespace`() {
        val plain = assertNotNull(parseSlashInput("/clear"))
        assertEquals("clear", plain.name)
        assertEquals("", plain.args)
        assertEquals("/clear", plain.raw)

        val withArgs = assertNotNull(parseSlashInput("/compact --force"))
        assertEquals("compact", withArgs.name)
        assertEquals("--force", withArgs.args)

        // 插件命令名带 `:` —— 必须整体作为名字,不能被冒号切开。
        val plugin = assertNotNull(parseSlashInput("/superpowers:commit 修一下"))
        assertEquals("superpowers:commit", plugin.name)
        assertEquals("修一下", plugin.args)
    }

    @Test
    fun `multiline paste keeps full args`() {
        val input = assertNotNull(parseSlashInput("/commit 第一行\n第二行"))
        assertEquals("commit", input.name)
        assertEquals("第一行\n第二行", input.args)
    }

    // ===== 模糊过滤 =====

    private val sample = listOf(
        SlashItem(kind = "command", name = "clear", description = "清空当前对话", type = "local", isBuiltIn = true),
        SlashItem(kind = "command", name = "compact", description = "手动压缩当前对话", type = "local", isBuiltIn = true),
        SlashItem(kind = "command", name = "commit", description = "提交改动", type = "prompt"),
        SlashItem(kind = "skill", name = "chat-to-skill", description = "把这次对话总结成 skill"),
        SlashItem(kind = "skill", name = "compact-notes", description = "压缩长文本为要点"),
    )

    @Test
    fun `name prefix hit ranks above scattered name hit`() {
        // `cl` 对 clear 是「首位 + 连续」命中,对 chat-to-skill 是散落命中。
        val hits = filterSlashItems(sample, "cl")
        assertEquals("clear", hits.first().name)
        assertTrue(hits.any { it.name == "chat-to-skill" }, "两者都是名字命中,都该在候选里")
    }

    @Test
    fun `description only hit does not qualify`() {
        // web 端的准入规则:名字 / 插件名无分 → 直接丢掉,描述命中不算数。
        // "压缩" 只出现在两条描述里,谁都进不来。
        assertTrue(filterSlashItems(sample, "压缩").isEmpty())
        // 描述命中只在名字已经命中时起加权作用。
        val hits = filterSlashItems(sample, "compact")
        assertEquals("compact", hits.first().name)
        assertTrue(hits.any { it.name == "compact-notes" }, "skill 靠名字命中,也该在:${hits.map { it.name }}")
    }

    @Test
    fun `commands always rank before skills even when skill scores higher`() {
        // 真机回归:打 `stat` 时 /strategic-compact 的名字分 + 描述分一度高过
        // /status,导致回车选错。web 是「两段拼接」,命令段整体在前 —— 钉死它。
        val items = listOf(
            SlashItem(kind = "skill", name = "strategic-compact", description = "Suggests manual context compaction at logical intervals..."),
            SlashItem(kind = "command", name = "status", description = "查看当前会话状态", type = "local", isBuiltIn = true),
        )
        assertEquals("status", filterSlashItems(items, "stat").first().name)
        // skill 自己那条 query 仍然要能捞出来(别把 skill 段整个丢了)。
        assertEquals("strategic-compact", filterSlashItems(items, "strategic").first().name)
    }

    @Test
    fun `plugin name narrows to that plugin and outranks plain name hit`() {
        val items = listOf(
            SlashItem(kind = "skill", name = "brainstorming", description = "脑暴"),
            SlashItem(kind = "skill", name = "plugin:superpowers:commit", description = "提交", displayName = "commit", pluginName = "superpowers"),
        )
        // 敲插件名 → 该插件的项被加权带上来。
        assertEquals("plugin:superpowers:commit", filterSlashItems(items, "superpowers").first().name)
        // 敲可见名 → 命中去前缀名,调用名仍是全名。
        assertEquals("plugin:superpowers:commit", filterSlashItems(items, "commit").first().name)
    }

    @Test
    fun `command sorts before skill on empty query`() {
        // 空查询(刚敲下 `/`)→ 命令整段在前,段内按名字升序。
        val all = filterSlashItems(sample, "")
        assertEquals(listOf("clear", "commit", "compact"), all.filter { !it.isSkill }.map { it.name })
        assertTrue(all.first().isSkill.not(), "空查询时命令应排在 skill 前面")
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(filterSlashItems(sample, "zzzzzz").isEmpty())
        // 0 = 没命中(不是「弱命中」);空 query 也返回 0。
        assertEquals(0, fuzzyScore("zzz", "clear"))
        assertEquals(0, fuzzyScore("", "clear"))
        assertEquals(0, fuzzyScore("clear", ""))
    }

    @Test
    fun `limit is respected`() {
        assertEquals(2, filterSlashItems(sample, "", limit = 2).size)
    }

    // ===== 服务端返回分流 =====

    private fun run(body: String): CommandRunResponse = json.decodeFromString(body)

    @Test
    fun `prompt result exposes rendered only for prompt type`() {
        val prompt = run("""{"type":"prompt","payload":{"rendered":"请帮我提交"}}""")
        assertEquals("请帮我提交", prompt.renderedPrompt())
        // 其它取值帮手在 prompt 上必须返回 null —— 别把 rendered 当 message 用。
        assertNull(prompt.messageText())
        assertNull(prompt.errorText())
        assertNull(prompt.statusText())

        val unknown = run("""{"type":"unknown","payload":{"input":"/nope"}}""")
        assertNull(unknown.renderedPrompt())
        assertEquals("/nope", unknown.unknownInput())
    }

    @Test
    fun `compacted payload carries count and summary`() {
        val res = run("""{"type":"compacted","payload":{"removedMessages":12,"summary":"之前在做 X"}}""")
        val info = assertNotNull(res.compactedInfo())
        assertEquals(12, info.first)
        assertEquals("之前在做 X", info.second)

        // summary 缺失时不能崩(服务端老版本可能不带)。
        val bare = run("""{"type":"compacted","payload":{}}""")
        assertEquals(0, assertNotNull(bare.compactedInfo()).first)
    }

    @Test
    fun `status text formats server fields`() {
        val res = run(
            """{"type":"status","payload":{"sessionId":"abcdef1234567890",
               "cwd":"/Users/ethan/code/lan-agent","model":"gpt-5","version":"0.1.0"}}"""
        )
        val text = assertNotNull(res.statusText())
        assertTrue(text.contains("会话  abcdef12"), text)
        assertTrue(text.contains("/Users/ethan/code/lan-agent"), text)
        assertTrue(text.contains("模型  gpt-5"), text)
    }

    @Test
    fun `cleared has null payload and no text helpers`() {
        val res = run("""{"type":"cleared","payload":null}""")
        assertEquals(CMD_TYPE_CLEARED, res.type)
        assertNull(res.renderedPrompt())
        assertNull(res.messageText())
    }

    @Test
    fun `slash list decodes item with unknown extra fields`() {
        // 服务端给 skill 项不带 type / isBuiltIn —— 默认值必须兜住。
        val list = json.decodeFromString<SlashListResponse>(
            """{"items":[{"kind":"skill","name":"deep-research","description":"多 agent 调研",
                "futureField":123}]}"""
        )
        val item = list.items.single()
        assertEquals("deep-research", item.name)
        assertEquals("deep-research", item.label)
        assertTrue(item.isSkill)
        assertNull(item.type)
        assertTrue(item.isSkill && !item.takesArgs)
    }

    @Test
    fun `argumentHint tolerates list shape from server`() {
        // 回归:opencc 项目专属命令(/release-opencc /sync-upperstream)在
        // 服务端 `slashList` 实现里把 argumentHint 写成 JSON 数组
        // (`["<version_type>"]`),不是字符串。客户端模型是 String?,整条
        // item 反序列化失败 → SlashListResponse.items 整列表失败 →
        // listSlashCommands runCatching 兜成 emptyList() → AgentInputBar
        // 看到 slashItems.isNotEmpty() == false,硬开关直接禁掉面板。
        // 这条命令是 opencc 项目独有,所以「opencc 项目面板不弹 / 其他项目 OK」。
        val raw = """{"items":[
            {"kind":"command","name":"release-opencc","description":"bump version",
             "type":"prompt","argumentHint":["<version_type>"]},
            {"kind":"command","name":"clear","description":"清空","type":"local",
             "isBuiltIn":true,"argumentHint":null}
        ]}"""
        // 回归:opencc 项目专属命令(/release-opencc /sync-upperstream)在
        // 服务端 `slashList` 实现里把 argumentHint 写成 JSON 数组
        // (`["<version_type>"]`),不是字符串。客户端模型是 String?,整条
        // item 反序列化失败 → SlashListResponse.items 整列表失败 →
        // listSlashCommands runCatching 兜成 emptyList() → AgentInputBar
        // 看到 slashItems.isNotEmpty() == false,硬开关直接禁掉面板。
        // 这两条命令是 opencc 项目独有,所以「opencc 项目面板不弹 / 其他项目 OK」。
        //
        // 修复方向:把 argumentHint 收成 JsonElement?,渲染时按 String /
        // Array / null 三态分别处理(数组 → joinToString(", "))。
        // 修复后下面两个断言都必须 PASS。
        val decoded = runCatching { json.decodeFromString<SlashListResponse>(raw) }
        val list = decoded.getOrNull()
        assertNotNull(list, "argumentHint 类型分歧不能让整表解码抛:err=${decoded.exceptionOrNull()?.message}")
        val names = list.items.map { it.name }
        assertTrue("release-opencc" in names, "list-shape hint 的 item 必须存活,实际=$names")
        assertTrue("clear" in names, "正常 item 也必须存活,实际=$names")
    }

    @Test
    fun `argumentHintText renders string array and null`() {
        // 渲染侧三态:字符串原样、数组 → ", " 拼接、null → 空串。
        // 这条直接钉住 argumentHintText 助手的行为,改渲染代码时能立刻看出影响。
        val items = listOf(
            SlashItem(name = "compact", argumentHint = buildJsonString("\"[--force]\"")),
            SlashItem(name = "release-opencc", argumentHint = buildJsonArray("""["<version_type>"]""")),
            SlashItem(name = "clear", argumentHint = null),
        )
        assertEquals("[--force]", items[0].argumentHintText())
        assertTrue(items[1].argumentHintText().contains("<version_type>"), "array hint 必须提到元素:实际=${items[1].argumentHintText()}")
        assertEquals("", items[2].argumentHintText())
        // takesArgs 跟着 argumentHintText 走 —— 任意形态有内容都算「要补全」。
        assertTrue(items[0].takesArgs)
        assertTrue(items[1].takesArgs)
        assertTrue(!items[2].takesArgs)
    }

    // ---- 测试小工具:服务端 raw JSON → JsonElement ----
    private fun buildJsonString(raw: String): JsonElement = Json.parseToJsonElement(raw)
    private fun buildJsonArray(raw: String): JsonElement = Json.parseToJsonElement(raw)

    @Test
    fun `plugin item label strips prefix but keeps full name for invocation`() {
        val item = SlashItem(
            kind = "skill",
            name = "plugin:superpowers:brainstorming",
            description = "脑暴",
            displayName = "brainstorming",
            pluginName = "superpowers",
        )
        assertEquals("brainstorming", item.label)
        // 调用名必须是全名 —— 服务端按 full name 解析。
        assertEquals("plugin:superpowers:brainstorming", item.name)
    }
}
