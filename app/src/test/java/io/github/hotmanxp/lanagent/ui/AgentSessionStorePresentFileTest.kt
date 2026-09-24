// ui/AgentSessionStorePresentFileTest.kt — PresentFile 在 store 这一层的回归。
//
// 这里钉的是一个**真机观察到、且只有特定操作序列才会出现**的退化:
//
//   1. Agent 调 PresentFile → SSE `runtime.tool_result` 带着 stat 元数据 →
//      卡片显示「293.6 KB · 5 天前 · 图片」。
//   2. 点文件进全屏查看器(NavHost 销毁会话页 composition),返回 →
//      重新 hydrate → transcript 里 tool_result 是字面量 `'done'` →
//      **size / mtime 凭空消失**,副标题退化成只剩「图片」。
//
// 第 3 条用例守的就是第 2 步:进程内缓存(`PresentFileCache`)必须把元数据补回来。
// 第 2 条用例守着「缓存也没命中时不能崩、要退化成只有路径」—— 冷启动就是这个
// 状态,它是服务端形状决定的既定限制,不是 bug,但**不能变成没有卡片**。
//
// 第 4 条守的是「本轮产物」的原料:写入类工具的落点必须在**入库时**从原始入参
// 抽出来(入参文本会被 capForDisplay 截断,渲染期再解已经是非法 JSON)。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*AgentSessionStorePresentFileTest*"
package io.github.hotmanxp.lanagent.ui

import io.github.hotmanxp.lanagent.data.AgentEvent
import io.github.hotmanxp.lanagent.data.FileKind
import io.github.hotmanxp.lanagent.data.TranscriptResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** 服务端那段 wrapper JSON(`presentFileOpencc.ts::call` 的产物)。 */
private fun wrapperJson(path: String, size: Long = 1234, caption: String? = null): String {
    val cap = if (caption == null) "" else ",\"caption\":\"$caption\""
    val file =
        """{"path":"$path","name":"${path.substringAfterLast('/')}","size":$size,"mtime":1789274126878.9248,"kind":"image"}"""
    return """{"content":[{"type":"json","json":{"file":$file$cap}}]}"""
}

private fun event(type: String, payload: JsonObjectBuilder.() -> Unit): AgentEvent {
    val obj = buildJsonObject { put("type", type); payload() }
    return AgentEvent(null, type, obj)
}

/** 直播态:`runtime.tool_call`(带 input)→ `runtime.tool_result`(带元数据)。 */
private fun liveTurn(
    store: AgentSessionStore,
    toolUseId: String,
    path: String,
    seq: Long,
    caption: String? = null,
    size: Long = 1234,
) {
    store.apply(event("runtime.tool_call") {
        put("toolUseId", toolUseId)
        put("toolName", "PresentFile")
        // **`input` 是 JSON 对象,不是字符串** —— 服务端 `agent.ts:534` 那行是
        // `JSON.parse(buf)`,schema 也是 `input: z.unknown()`。传字符串会让
        // input 派生那条路整条失效(而直播态因为能从 tool_result 兜底,症状
        // 完全看不出来 —— 上一版测试就是这么放过去的)。
        put("input", buildJsonObject {
            put("path", path)
            caption?.let { put("caption", it) }
        })
        put("ts", 1789274126878L)
        put("seq", seq)
    })
    store.apply(event("runtime.tool_result") {
        put("toolUseId", toolUseId)
        put("output", wrapperJson(path, size = size, caption = caption))
        put("seq", seq + 1)
    })
}

/**
 * 重新 hydrate 时服务端给的那份 transcript:`tool_use` 带 input,
 * `tool_result` 的 content 是**字面量 `'done'`**(服务端
 * `mapToolResultToToolResultBlockParam` 的产物)。
 */
private fun doneTranscript(toolUseId: String, path: String, toolName: String = "PresentFile") =
    wireJson.decodeFromString<TranscriptResponse>(
        """
        {"transcript":{"meta":{"cwd":"/tmp"},"messages":[
          {"type":"assistant","timestamp":1789274126878,
           "message":{"role":"assistant","content":[
             {"type":"tool_use","id":"$toolUseId","name":"$toolName","input":{"path":"$path"}}
           ]}},
          {"type":"user","timestamp":1789274126879,
           "message":{"role":"user","content":[
             {"type":"tool_result","tool_use_id":"$toolUseId","content":"done"}
           ]}}
        ]}}
        """.trimIndent()
    ).transcript

class AgentSessionStorePresentFileTest {

    /** 直播态:两张事件就够 —— 卡片带真 size(浮点 mtime 取整)+ caption。 */
    @Test
    fun `live turn puts stat metadata and caption on the card`() {
        val store = AgentSessionStore("sess-live")
        liveTurn(store, "tu-live-1", "/tmp/chart.png", seq = 1, caption = "季度图")

        val file = store.items.single().let { (it as AgentItem.ToolCall).file }
        assertNotNull(file)
        assertEquals("/tmp/chart.png", file.path)
        assertEquals(1234L, file.size)
        assertEquals(1789274126878L, file.mtime)
        assertEquals("季度图", file.caption)
        assertEquals(FileKind.Image, file.kind)
        // 有元数据 → 图片可预览(会走 /api/fs/raw)
        assertTrue(file.viewable)
    }

    /**
     * 冷启动 / 缓存未命中:只剩 `input.path` —— 有路径、可预览,但**没有
     * size / 时间**。这是服务端形状决定的既定限制,不许退化成「没有文件」
     * (那就是整张卡消失,才是 bug)。
     */
    @Test
    fun `rehydrate without cache degrades to path only but keeps the card`() {
        val store = AgentSessionStore("sess-cold")
        store.hydrate(doneTranscript("tu-cold-1", "/tmp/cold.png"))

        val file = (store.items.single() as AgentItem.ToolCall).file
        assertNotNull(file)
        assertEquals("/tmp/cold.png", file.path)
        assertEquals("cold.png", file.name)
        assertNull(file.size)
        // kind 靠扩展名在客户端猜出来,所以仍然能预览
        assertTrue(file.viewable)
    }

    /**
     * **本次修复守的就是这条**:直播过一轮之后再重新 hydrate(点进文件查看器
     * 再返回、切底栏 tab 都会触发),元数据必须从进程内缓存补回来。
     */
    @Test
    fun `rehydrate after a live turn restores metadata from the cache`() {
        val toolUseId = "tu-cached-1"
        val path = "/tmp/cached.png"

        // 1. 直播一轮 —— 顺带把元数据写进 PresentFileCache
        liveTurn(AgentSessionStore("sess-a"), toolUseId, path, seq = 1, caption = "缓存里的说明")

        // 2. 新的 store(= 会话页被销毁后重建)重新 hydrate,transcript 里只有 'done'
        val reborn = AgentSessionStore("sess-a")
        reborn.hydrate(doneTranscript(toolUseId, path))

        val file = (reborn.items.single() as AgentItem.ToolCall).file
        assertNotNull(file)
        assertEquals(1234L, file.size)                          // 没有退化成 null
        assertEquals(1789274126878L, file.mtime)
        assertEquals("缓存里的说明", file.caption)
        assertEquals(FileKind.Image, file.kind)
    }

    /**
     * 写入类工具的落点在**入库时**抽好(给「本轮产物」用)。
     * `Read` 之类只读工具不该被误报成产物 —— 它有同样的 `file_path` 字段。
     */
    @Test
    fun `write targets are captured at ingest time and only for write tools`() {
        val store = AgentSessionStore("sess-writes")
        store.apply(event("runtime.tool_call") {
            put("toolUseId", "tu-w")
            put("toolName", "Write")
            put("input", buildJsonObject { put("file_path", "/tmp/a.ts"); put("content", "x") })
        })
        store.apply(event("runtime.tool_call") {
            put("toolUseId", "tu-e")
            put("toolName", "Edit")
            put("input", buildJsonObject {
                put("file_path", "/tmp/a.ts")
                put("old_string", "x")
                put("new_string", "y")
            })
        })
        store.apply(event("runtime.tool_call") {
            put("toolUseId", "tu-r")
            put("toolName", "Read")
            put("input", buildJsonObject { put("file_path", "/tmp/a.ts") })
        })

        val calls = store.items.filterIsInstance<AgentItem.ToolCall>()
        assertEquals("/tmp/a.ts", calls[0].write?.path)
        assertEquals("写入", calls[0].write?.label)
        assertEquals(true, calls[0].write?.written)
        assertEquals("编辑", calls[1].write?.label)
        assertNull(calls[2].write)   // Read 不产生产物
    }

    /** 非 PresentFile 的工具照旧不碰 file 字段(通用工具卡形态不变)。 */
    @Test
    fun `other tools keep a null file`() {
        val store = AgentSessionStore("sess-other")
        store.apply(event("runtime.tool_call") {
            put("toolUseId", "tu-b")
            put("toolName", "Bash")
            put("input", buildJsonObject { put("command", "ls") })
        })

        val call = store.items.single() as AgentItem.ToolCall
        assertNull(call.file)
        assertNull(call.write)
    }
}
