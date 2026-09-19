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