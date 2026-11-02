// ui/AgentSessionStoreWatchdogTest.kt — 工具调用超时兜底在 store 这一层(0.30.2)
//
// 真机症状:Grep 卡住后工具卡永远转圈,超过 30 秒也没有超时错误。
// 根因在引擎侧(core bundle 里缺 ripgrep 看门狗),但** App 侧必须能兜住 **,
// 否则用户看到的就是一个永远不结束的转圈。
//
// 这里钉住 store 这一层的三条:
//  1. Grep 卡住 → 超过阈值后被标成错误卡(不再转圈),且带可读说明;
//  2. 有结果到达的卡不被误伤;
//  3. Bash(允许跑 10 分钟)不被误伤。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*AgentSessionStoreWatchdogTest*"
package io.github.hotmanxp.lanagent.ui

import io.github.hotmanxp.lanagent.data.AgentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

private fun event(type: String, payload: JsonObjectBuilder.() -> Unit): AgentEvent {
    val obj = buildJsonObject { put("type", type); payload() }
    return AgentEvent(null, type, obj)
}

private fun toolCall(store: AgentSessionStore, toolUseId: String, toolName: String) {
    store.apply(event("runtime.tool_call") {
        put("toolUseId", toolUseId)
        put("toolName", toolName)
        put("input", buildJsonObject { put("pattern", "a") })
    })
}

class AgentSessionStoreWatchdogTest {

    @Test
    fun `Grep 卡住超过阈值后被标成错误卡`() {
        val store = AgentSessionStore("sess-1")
        toolCall(store, "tu-1", "Grep")

        // 还没到阈值:不动它。
        assertTrue(store.expireStalledToolCalls(System.currentTimeMillis()).isEmpty())
        assertTrue((store.items.first() as AgentItem.ToolCall).running)

        // 远超阈值:标成错误卡。
        val far = System.currentTimeMillis() + ToolCallWatchdog.SEARCH_TIMEOUT_MS + 1_000
        val expired = store.expireStalledToolCalls(far)
        assertEquals(listOf("tool-tu-1"), expired)

        val card = store.items.first() as AgentItem.ToolCall
        assertFalse(card.running, "超时后不该再转圈")
        assertTrue(card.isError, "超时应该标成错误")
        assertTrue(
            card.output?.contains("没有返回") == true,
            "错误文案要说清是引擎没返回,实际: ${card.output}",
        )
    }

    @Test
    fun `有结果到达的卡不被误伤`() {
        val store = AgentSessionStore("sess-2")
        toolCall(store, "tu-2", "Grep")
        store.apply(event("runtime.tool_result") {
            put("toolUseId", "tu-2")
            put("output", buildJsonObject { put("ok", true) })
        })

        val far = System.currentTimeMillis() + ToolCallWatchdog.SEARCH_TIMEOUT_MS + 1_000
        assertTrue(store.expireStalledToolCalls(far).isEmpty())

        val card = store.items.first() as AgentItem.ToolCall
        assertFalse(card.isError, "已经有结果的卡不该被标成错误")
    }

    @Test
    fun `Bash 不被误伤`() {
        val store = AgentSessionStore("sess-3")
        toolCall(store, "tu-3", "Bash")

        // core 允许 Bash 跑 10 分钟 —— 超过 60s 不该被判超时。
        val far = System.currentTimeMillis() + 10 * 60_000
        assertTrue(store.expireStalledToolCalls(far).isEmpty())
        assertTrue((store.items.first() as AgentItem.ToolCall).running)
    }

    @Test
    fun `同一个 key 的重复 tool_call 不重置计时`() {
        val store = AgentSessionStore("sess-4")
        toolCall(store, "tu-4", "Grep")
        // 重试来的同一个 key —— 计时不能被推后,否则反复重试就永远不超时。
        toolCall(store, "tu-4", "Grep")

        val far = System.currentTimeMillis() + ToolCallWatchdog.SEARCH_TIMEOUT_MS + 1_000
        assertEquals(listOf("tool-tu-4"), store.expireStalledToolCalls(far))
    }

    @Test
    fun `多张卡同时挂住时全部超时`() {
        val store = AgentSessionStore("sess-5")
        toolCall(store, "tu-5", "Grep")
        toolCall(store, "tu-6", "Glob")

        // 两张卡同时超时的路径里,计时表会被逐个删条目 —— 删的同时不能
        // 拿着同一个表的迭代器继续走(会抛 ConcurrentModificationException)。
        val far = System.currentTimeMillis() + ToolCallWatchdog.SEARCH_TIMEOUT_MS + 1_000
        val expired = store.expireStalledToolCalls(far)
        assertEquals(listOf("tool-tu-5", "tool-tu-6"), expired)
        assertTrue(store.items.all { (it as AgentItem.ToolCall).isError })
    }
}
