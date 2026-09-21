// data/AgentModelsTest.kt — wire 模型的解析回归测试。
//
// 这个文件存在的唯一理由:**zai 的 JSON 字段类型全部是「实测倒推」的**,
// 服务端没有 OpenAPI / JSON Schema,同一个字段在不同写入路径下形态还可能不同。
// 每踩到一个坑就在这里钉一条用例,防止以后调字段时又退回去。
//
// 跑法:./gradlew :app:testDebugUnitTest
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 必须与 `AgentApi` 里那个 Json 逐项一致 —— 复现真实解码路径,
 * 否则测试会「因为没有 isLenient 而通过」之类的假绿。
 */
private val wireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

class AgentModelsTest {

    /**
     * 真机回归:`fs.Stats.mtimeMs` 是**浮点**毫秒。
     *
     * 2026-09-14 实测报错:
     * `Unexpected symbol ':' in numeric literal at path: $.sessions[0].updatedAt`
     * —— 只要有**一条**会话的 mtime 带小数,整个会话列表页就空白。
     */
    @Test
    fun `sessions decodes fractional mtime updatedAt`() {
        val raw = """
            {"sessions":[
              {"sessionId":"sess-1","cwd":"/Users/ethan/code","model":"unknown",
               "createdAt":1789315591018,"updatedAt":1789274126878.9248,"title":"帮我"}
            ]}
        """.trimIndent()

        val res = wireJson.decodeFromString<AgentSessionsResponse>(raw)

        assertEquals(1, res.sessions.size)
        val m = res.sessions[0]
        assertEquals("sess-1", m.sessionId)
        assertEquals(1789315591018L, m.createdAt)
        // 小数被取整(误差 < 1ms,对「x 分钟前」无影响)
        assertEquals(1789274126878L, m.updatedAt)
    }

    /** 服务端 stat 失败时 `updatedAt` 回 0,UI 要能兜底成 "-"(不能变成 1970 年)。 */
    @Test
    fun `sessions tolerates zero and missing timestamps`() {
        val raw = """{"sessions":[{"sessionId":"s"},{"sessionId":"t","updatedAt":0}]}"""
        val res = wireJson.decodeFromString<AgentSessionsResponse>(raw)

        assertEquals(0L, res.sessions[0].updatedAt)
        assertEquals(0L, res.sessions[1].updatedAt)
        // 未声明的服务端字段(mainAgent / providerId 等)不应炸 —— ignoreUnknownKeys
        assertNull(res.sessions[0].title)
    }

    /**
     * transcript 的 `timestamp` 是**双形态**:assistant/user 是 epoch 毫秒数字,
     * system / queue-operation 是 ISO 字符串。声明成 `Long` 时只要有**一条**
     * system 条目,整份 transcript 就解码失败、详情页打不开。
     */
    @Test
    fun `transcript entry timestamp accepts both number and iso string`() {
        val raw = """
            {"transcript":{"meta":{"cwd":"/tmp"},"messages":[
              {"type":"assistant","timestamp":1788658221846,
               "message":{"role":"assistant","content":[{"type":"text","text":"hi"}]}},
              {"type":"system","timestamp":"2026-09-06T04:06:35.048Z",
               "message":{"role":"system","content":"boot"}},
              {"type":"custom-title","timestamp":"2026-09-06T04:06:36.000Z",
               "customTitle":"标题"}
            ]}}
        """.trimIndent()

        val t = wireJson.decodeFromString<TranscriptResponse>(raw).transcript

        assertEquals(3, t.messages.size)
        assertEquals(1788658221846L, t.messages[0].tsMs)
        // ISO 字符串那条必须能折算出来,而不是 null(→ 详情页整页解码失败)
        assertTrue(t.messages[1].tsMs!! > 0, "ISO 时间戳应能折算为 epoch ms")
        assertEquals("标题", t.messages[2].customTitle)
    }

    /** 浮点 mtime 同样会出现在 SessionCwd / V2Task 上,一并兜住。 */
    @Test
    fun `session state decodes fractional task timestamps`() {
        val raw = """
            {"cwd":{"cwd":"/tmp","updatedAt":1789274126878.9248},
             "v2Tasks":[{"id":"1","subject":"do it","status":"in_progress",
                         "updatedAt":1789274126879.5}]}
        """.trimIndent()

        val s = wireJson.decodeFromString<SessionStateResponse>(raw)

        assertEquals(1789274126878L, s.cwd?.updatedAt)
        assertEquals(1789274126879L, s.v2Tasks[0].updatedAt)
    }

    /**
     * state 快照里的后台任务(冷启动唯一来源)。
     *
     * 钉三件事:
     *   1. 两套字段名**不一样** —— agent 侧是 `id`,bash 侧是 `taskId`
     *      (写成一个字段名就会静默解不出东西,列表永远空的,真机上极难定位)
     *   2. `agentTasks[].input` 可能整体缺省,必须是可空类型
     *   3. bash 的 `stdout` / `exitCode` / `isBackgrounded` 等字段我们**故意
     *      不接**(可能几 MB),ignoreUnknownKeys 要能直接吃掉
     */
    @Test
    fun `session state decodes background tasks`() {
        val raw = """
            {"agentTasks":[
               {"id":"task_1","status":"running",
                "input":{"prompt":"调研 SSE","cwd":"/Users/ethan/code"},
                "createdAt":1789274126878,"startedAt":1789274126900,
                "parentSessionId":"sess-1","agentType":"Explore","description":"调研"},
               {"id":"task_2","status":"completed","createdAt":1789274126000,
                "finishedAt":1789274126000,"resultText":"很长很长的结果","eventCount":42}
             ],
             "bashTasks":[
               {"taskId":"bash_1","sessionId":"sess-1","command":"npm run dev",
                "description":"起服务","startedAt":1789274126900,"status":"running",
                "stdout":"listening on 5173","stderr":"","exitCode":0,"signal":null,
                "isBackgrounded":true,"notified":false}
             ]}
        """.trimIndent()

        val s = wireJson.decodeFromString<SessionStateResponse>(raw)

        assertEquals(2, s.agentTasks.size)
        assertEquals("Explore", s.agentTasks[0].agentType)
        assertEquals("调研 SSE", s.agentTasks[0].input?.prompt)
        // input 缺省必须解成 null,而不是让整份 state 解码失败
        assertNull(s.agentTasks[1].input)
        assertEquals("completed", s.agentTasks[1].status)

        assertEquals(1, s.bashTasks.size)
        assertEquals("bash_1", s.bashTasks[0].taskId)
        assertEquals("npm run dev", s.bashTasks[0].command)
        assertEquals("running", s.bashTasks[0].status)
    }

    /** 缺少 bashTasks / agentTasks 字段(老服务端 / 端点降级)时按空列表兜底。 */
    @Test
    fun `session state tolerates missing background task lists`() {
        val s = wireJson.decodeFromString<SessionStateResponse>("""{"cwd":{"cwd":"/tmp"}}""")

        assertTrue(s.agentTasks.isEmpty())
        assertTrue(s.bashTasks.isEmpty())
    }

    /** 工具返回值三种形态都要能抽成文本(裸字符串 / content 数组 / 对象)。 */
    @Test
    fun `tool result text flattens all server shapes`() {
        val arrayShape = wireJson.parseToJsonElement(
            """[{"type":"text","text":"line1"},{"type":"text","text":"line2"}]"""
        )
        assertEquals("line1\nline2", arrayShape.toolResultText())

        val wrapped = wireJson.parseToJsonElement("""{"content":[{"type":"text","text":"ok"}]}""")
        assertEquals("ok", wrapped.toolResultText())

        val bare = wireJson.parseToJsonElement("\"plain\"")
        assertEquals("plain", bare.toolResultText())
    }

    /**
     * 0.15.1:`GET /api/agent/settings` 返回的 ModelEntry 必须能把
     * `capabilities.contextWindow` 解析出来 —— 这是「上下文 current / max」
     * 面板那行的 max 来源。ProviderProfile 写了 capabilities 时才能用,
     * 没写时 capabilities 整个字段是 null(用户看不到 max,显示 "—")。
     */
    @Test
    fun `model entry decodes capabilities contextWindow`() {
        val raw = """{"models":[
            {"model":"MiniMax-M3","alias":"M3","label":"M3 旗舰",
             "providerId":"openai-platform",
             "capabilities":{"contextWindow":200000,"supportsVision":true}},
            {"model":"unknown-llm","alias":"X"}
        ]}""".trimIndent()
        val res = wireJson.decodeFromString<AgentSettingsResponse>(raw)

        assertEquals(2, res.models.size)
        assertEquals(200000, res.models[0].capabilities?.contextWindow)
        assertEquals(true, res.models[0].capabilities?.supportsVision)
        // 缺 capabilities 字段 = null(而不是抛错)
        assertNull(res.models[1].capabilities)
    }
}
