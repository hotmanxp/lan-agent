// ui/AgentSessionStoreDisplayFilesTest.kt — DisplayFiles 在 store 这一层的回归。
//
// 这里钉的是一个**真机观察到、且只有特定操作序列才会出现**的退化:
//
//   1. Agent 调 DisplayFiles → SSE `runtime.tool_result` 带着 stat 元数据 →
//      卡片显示「293.6 KB · 5 天前 · 图片」。
//   2. 点文件进全屏查看器(NavHost 销毁会话页 composition),返回 →
//      重新 hydrate → transcript 里 tool_result 是字面量 `'done'` →
//      **size / mtime 凭空消失**,副标题退化成只剩「图片」。
//
// 第 3 条用例守的就是第 2 步:进程内缓存(`DisplayFilesCache`)必须把元数据补回来。
// 第 2 条用例守着「缓存也没命中时不能崩、要退化成只有路径」—— 冷启动就是这个
// 状态,它是服务端形状决定的既定限制,不是 bug,但**不能变成空列表**。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*AgentSessionStoreDisplayFilesTest*"
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** 服务端那段 wrapper JSON(`displayFilesOpencc.ts::call` 的产物)。 */
private fun wrapperJson(vararg fileEntries: String): String =
    """{"content":[{"type":"json","json":{"files":[${fileEntries.joinToString(",")}]}}]}"""

private fun fileEntry(path: String, size: Long, kind: String = "image"): String =
    """{"path":"$path","name":"${path.substringAfterLast('/')}","size":$size,"mtime":1789274126878.9248,"kind":"$kind"}"""

private fun event(type: String, payload: JsonObjectBuilder.() -> Unit): AgentEvent {
    val obj = buildJsonObject { put("type", type); payload() }
    return AgentEvent(null, type, obj)
}

/** 直播态:`runtime.tool_call`(带 input.paths)→ `runtime.tool_result`(带元数据)。 */
private fun liveTurn(store: AgentSessionStore, toolUseId: String, path: String, seq: Long) {
    store.apply(event("runtime.tool_call") {
        put("toolUseId", toolUseId)
        put("toolName", "DisplayFiles")
        put("input", """{"paths":["$path"]}""")
        put("ts", 1789274126878L)
        put("seq", seq)
    })
    store.apply(event("runtime.tool_result") {
        put("toolUseId", toolUseId)
        put("output", wrapperJson(fileEntry(path, 1234)))
        put("seq", seq + 1)
    })
}

/**
 * 重新 hydrate 时服务端给的那份 transcript:`tool_use` 带 input,
 * `tool_result` 的 content 是**字面量 `'done'`**(服务端
 * `mapToolResultToToolResultBlockParam` 的产物)。
 */
private fun doneTranscript(toolUseId: String, path: String) = wireJson.decodeFromString<TranscriptResponse>(
    """
    {"transcript":{"meta":{"cwd":"/tmp"},"messages":[
      {"type":"assistant","timestamp":1789274126878,
       "message":{"role":"assistant","content":[
         {"type":"tool_use","id":"$toolUseId","name":"DisplayFiles","input":{"paths":["$path"]}}
       ]}},
      {"type":"user","timestamp":1789274126879,
       "message":{"role":"user","content":[
         {"type":"tool_result","tool_use_id":"$toolUseId","content":"done"}
       ]}}
    ]}}
    """.trimIndent()
).transcript

private fun AgentSessionStore.singleToolFiles() =
    (items.single() as AgentItem.ToolCall).files.single()

class AgentSessionStoreDisplayFilesTest {

    /** 直播态:两张事件就够 —— 卡片带真 size(浮点 mtime 取整)。 */
    @Test
    fun `live turn puts stat metadata on the card`() {
        val store = AgentSessionStore("sess-live")
        liveTurn(store, "tu-live-1", "/tmp/chart.png", seq = 1)

        val item = store.items.single() as AgentItem.ToolCall
        assertEquals("DisplayFiles", item.name)
        assertEquals(1, item.files.size)
        assertEquals(1234L, item.files[0].size)
        assertEquals(1789274126878L, item.files[0].mtime)
        // 有元数据 → 图片可预览(会走 /api/fs/preview)
        assertTrue(item.files[0].previewable)
    }

    /**
     * 冷启动 / 缓存未命中:只剩 `input.paths` —— 有文件、有路径、可预览,
     * 但**没有 size / 时间**。这是服务端形状决定的既定限制,不许退化成空列表
     * (空列表 = 卡片整片消失,那才是 bug)。
     */
    @Test
    fun `rehydrate without cache degrades to paths only but keeps the card`() {
        val store = AgentSessionStore("sess-cold")
        store.hydrate(doneTranscript("tu-cold-1", "/tmp/cold.png"))

        val file = store.singleToolFiles()
        assertEquals("/tmp/cold.png", file.path)
        assertEquals("cold.png", file.name)
        assertNull(file.size)
        // kind 靠扩展名在客户端猜出来,所以仍然能预览
        assertTrue(file.previewable)
    }

    /**
     * **本次修复守的就是这条**:直播过一轮之后再重新 hydrate(点进文件查看器
     * 再返回、切底栏 tab 都会触发),元数据必须从进程内缓存补回来。
     */
    @Test
    fun `rehydrate after a live turn restores metadata from the cache`() {
        val toolUseId = "tu-cached-1"
        val path = "/tmp/cached.png"

        // 1. 直播一轮 —— 顺带把元数据写进 DisplayFilesCache
        liveTurn(AgentSessionStore("sess-a"), toolUseId, path, seq = 1)

        // 2. 新的 store(= 会话页被销毁后重建)重新 hydrate,transcript 里只有 'done'
        val reborn = AgentSessionStore("sess-a")
        reborn.hydrate(doneTranscript(toolUseId, path))

        val file = reborn.singleToolFiles()
        assertEquals(1234L, file.size)                        // 没有退化成 null
        assertEquals(1789274126878L, file.mtime)
        assertEquals(FileKind.Image, file.kind)
    }
}
