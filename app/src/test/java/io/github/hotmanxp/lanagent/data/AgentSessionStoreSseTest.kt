// data/AgentSessionStoreSseTest.kt — AgentSessionStore 对 SSE 事件的 reduce 回归测试。
//
// 0.15.1 新增的「上下文 current / max」面板那行的 current,来自 SSE 三路:
//   - runtime.started.contextTokens
//   - runtime.done.contextTokens
//   - session/projection key="context.tokens"
// 这个文件把它们各自的 wire 形态钉住,防止以后调字段时再退回去 ——
// 三路里任何一条断掉,「上下文」那一行就再也看不到数字,只能 "— / —",
// 真机上很难联想到是 SSE 字段解析炸了。
package io.github.hotmanxp.lanagent.data

import io.github.hotmanxp.lanagent.ui.AgentSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionStoreSseTest {

    // 复现 AgentSessionStore.apply 内部的解码路径(Json { ignoreUnknownKeys; isLenient; explicitNulls = false })
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun ev(type: String, payload: JsonObject): AgentEvent = AgentEvent(
        id = null,
        type = type,
        payload = payload,
    )

    // ===== 后台任务 =====

    private fun storeWithAgentTask(id: String, status: String, finishedAt: Long?): AgentSessionStore {
        val store = AgentSessionStore("sess-1")
        val fin = if (finishedAt != null) ""","finishedAt":$finishedAt""" else ""
        store.apply(
            ev(
                "agent_task.changed",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","seq":7,
                        "task":{"id":"$id","status":"$status","createdAt":1,
                                "input":{"prompt":"调研 codegraph"},
                                "agentType":"Explore"$fin}}"""
                ) as JsonObject,
            ),
        )
        return store
    }

    /** `agent_task.changed` 的 payload 是 `{sessionId, task}`,task 是全量快照。 */
    @Test
    fun `agent task changed upserts by id`() {
        val store = storeWithAgentTask("task_1", "running", null)

        assertEquals(1, store.bgAgentTasks.size)
        val t = store.bgAgentTasks[0]
        assertEquals("task_1", t.id)
        assertEquals("running", t.status)
        assertEquals("Explore", t.agentType)
        assertEquals("调研 codegraph", t.input?.prompt)
    }

    /** queued → running → completed 是**同一条**任务推三次,不能变成三行。 */
    @Test
    fun `agent task changed replaces in place`() {
        val store = storeWithAgentTask("task_1", "running", null)
        store.apply(
            ev(
                "agent_task.changed",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","task":{"id":"task_1","status":"completed",
                        "createdAt":1,"finishedAt":${System.currentTimeMillis()}}}"""
                ) as JsonObject,
            ),
        )

        assertEquals(1, store.bgAgentTasks.size)
        assertEquals("completed", store.bgAgentTasks[0].status)
    }

    /** 终态任务超过 TTL 就不再占位(running 永不清)。 */
    @Test
    fun `agent task prune drops only stale terminal tasks`() {
        val now = System.currentTimeMillis()
        val stale = storeWithAgentTask("old", "completed", now - BG_RECENT_TTL_MS - 1)
        assertEquals(0, stale.bgAgentTasks.size)

        val fresh = storeWithAgentTask("new", "completed", now)
        assertEquals(1, fresh.bgAgentTasks.size)

        val running = storeWithAgentTask("live", "running", null)
        assertEquals(1, running.bgAgentTasks.size)
    }

    /** bash 侧字段名是 `taskId`(agent 侧是 `id`),按 taskId 去重。 */
    @Test
    fun `bash task changed upserts by taskId`() {
        val store = AgentSessionStore("sess-1")
        val startedAt = System.currentTimeMillis() - 100
        fun push(status: String) {
            val fin = if (status == "running") "" else ""","finishedAt":${System.currentTimeMillis()}"""
            store.apply(
                ev(
                    "bash_task.changed",
                    json.parseToJsonElement(
                        """{"sessionId":"sess-1","task":{"taskId":"bash_1","sessionId":"sess-1",
                            "command":"npm run dev","description":"起服务",
                            "startedAt":$startedAt,"status":"$status"$fin}}"""
                    ) as JsonObject,
                ),
            )
        }

        push("running")
        push("completed")

        assertEquals(1, store.bgBashTasks.size)
        assertEquals("completed", store.bgBashTasks[0].status)
        assertEquals("起服务", store.bgBashTasks[0].description)
    }

    /** 没 id 的条目直接丢掉 —— 没 id 就无法按后续增量去重,留着必然重复。 */
    @Test
    fun `background tasks without id are ignored`() {
        val store = AgentSessionStore("sess-1")
        store.apply(
            ev(
                "agent_task.changed",
                json.parseToJsonElement("""{"sessionId":"sess-1","task":{"status":"running"}}""") as JsonObject,
            ),
        )
        store.apply(ev("agent_task.changed", json.parseToJsonElement("""{"sessionId":"sess-1"}""") as JsonObject))

        assertEquals(0, store.bgAgentTasks.size)
    }

    /**
     * state 快照是 bash 侧冷启动的**唯一**来源(`bash_task.changed` 没有
     * 服务端合成重推),所以 hydrateState 必须把两份列表都装上。
     */
    @Test
    fun `hydrate state seeds background tasks`() {
        val store = AgentSessionStore("sess-1")
        store.hydrateState(
            SessionStateResponse(
                agentTasks = listOf(BgAgentTask(id = "task_1", status = "running")),
                bashTasks = listOf(BgBashTask(taskId = "bash_1", status = "running", command = "ls")),
            ),
        )

        assertEquals(1, store.bgAgentTasks.size)
        assertEquals(1, store.bgBashTasks.size)
        assertTrue(store.hasDockContent)
    }

    @Test
    fun `runtime started captures contextTokens`() {
        val store = AgentSessionStore("sess-1")
        store.apply(
            ev(
                "runtime.started",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","turnIndex":0,"apiRequestCount":1,"contextTokens":12345}"""
                ) as JsonObject,
            ),
        )

        assertEquals(12345L, store.contextTokens)
    }

    @Test
    fun `runtime done updates contextTokens`() {
        val store = AgentSessionStore("sess-1")
        // 先模拟 started 推一个较小值
        store.apply(
            ev(
                "runtime.started",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","turnIndex":0,"contextTokens":1000}"""
                ) as JsonObject,
            ),
        )
        // done 推更大的值 → 直接覆盖(数字单调上升)
        store.apply(
            ev(
                "runtime.done",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","turnIndex":0,"contextTokens":15000}"""
                ) as JsonObject,
            ),
        )

        assertEquals(15000L, store.contextTokens)
    }

    @Test
    fun `session projection context-tokens sets contextTokens`() {
        val store = AgentSessionStore("sess-1")
        store.apply(
            ev(
                "session/projection",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","key":"context.tokens","value":67890,"seq":42}"""
                ) as JsonObject,
            ),
        )

        assertEquals(67890L, store.contextTokens)
    }

    @Test
    fun `session projection ignores other keys`() {
        val store = AgentSessionStore("sess-1")
        store.apply(
            ev(
                "session/projection",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","key":"title","value":"新标题","seq":42}"""
                ) as JsonObject,
            ),
        )

        // 别的 key 不污染 contextTokens(不要让未来加新投影键时误清这个字段)
        assertNull(store.contextTokens)
    }

    @Test
    fun `runtime started without contextTokens leaves it null`() {
        val store = AgentSessionStore("sess-1")
        store.apply(
            ev(
                "runtime.started",
                json.parseToJsonElement(
                    """{"sessionId":"sess-1","turnIndex":0}"""
                ) as JsonObject,
            ),
        )

        // 老版本服务端不带这个字段(向后兼容) → 不应该炸
        assertNull(store.contextTokens)
    }
}