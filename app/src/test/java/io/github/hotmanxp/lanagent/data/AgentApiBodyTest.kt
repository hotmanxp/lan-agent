// data/AgentApiBodyTest.kt — `POST /api/agent/prompt` 请求体构造的回归测试。
//
// 这里钉的是一条**服务端契约**：文本只能走顶层 `prompt` 字段，不能自己塞进
// `contentBlocks`。服务端会把两者拼起来（agent.ts:1201-1204）：
//
//     blocks.length ? [...blocks, ...(text ? [{type:'text',text}] : [])] : text
//
// 客户端如果"贴心地"自己也加一个 text block，用户就会看到自己发的话重复两遍。
// 这个 bug 在真机上表现为「模型回了两遍同一句」，很难联想到请求体，所以钉住。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentApiBodyTest {

    private fun JsonObject.blocks(): JsonArray? = this["contentBlocks"] as? JsonArray

    @Test
    fun `plain text stays on top-level prompt without contentBlocks`() {
        val body = promptRequestBody("sess-1", "你好", emptyList())

        assertEquals("你好", body["prompt"]?.jsonPrimitive?.content)
        assertEquals("sess-1", body["sessionId"]?.jsonPrimitive?.content)
        // 纯文本路径必须不出现 contentBlocks —— 服务端要靠它退化到 string 分支
        assertNull(body.blocks())
    }

    @Test
    fun `images only omits prompt field entirely`() {
        // 只发图是合法的（zod refine: prompt 或 contentBlocks 至少一个非空）。
        // 空 prompt 时不该发一个空串过去。
        val body = promptRequestBody("sess-1", "   ", listOf(PromptImage("image/jpeg", "AAA")))

        assertNull(body["prompt"])
        val blocks = body.blocks()
        assertEquals(1, blocks?.size)
        val block = blocks!![0] as JsonObject
        assertEquals("image", block["type"]?.jsonPrimitive?.content)
        val src = block["source"] as JsonObject
        assertEquals("base64", src["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", src["media_type"]?.jsonPrimitive?.content)
        assertEquals("AAA", src["data"]?.jsonPrimitive?.content)
    }

    /** 核心契约：图文混发时 contentBlocks **只放图片**，文本留在顶层 prompt。 */
    @Test
    fun `text with images keeps blocks image-only`() {
        val body = promptRequestBody(
            sessionId = "sess-2",
            prompt = "看看这两张",
            images = listOf(
                PromptImage("image/jpeg", "IMG1"),
                PromptImage("image/jpeg", "IMG2"),
            ),
        )

        assertEquals("看看这两张", body["prompt"]?.jsonPrimitive?.content)

        val blocks = body.blocks()!!
        assertEquals(2, blocks.size)
        val types = blocks.map { (it as JsonObject)["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("image", "image"), types)
        // 一旦这里冒出 "text"，就是文本重复 bug 复发了
        assertTrue(types.none { it == "text" }, "contentBlocks 里不能有 text 块")
    }

    @Test
    fun `media type is always jpeg after re-encoding`() {
        // ImageAttachments.load 统一重编码成 JPEG，所以 media_type 恒定 ——
        // 服务端 ImageBlock 的 media_type 是枚举（jpeg/png/gif/webp），
        // 相册里的 HEIC 原样上传会被 zod 拒成 400。
        val body = promptRequestBody("s", "", listOf(PromptImage("image/jpeg", "X")))
        val src = ((body.blocks()!![0] as JsonObject)["source"]) as JsonObject
        assertEquals("image/jpeg", src["media_type"]?.jsonPrimitive?.content)
    }
}
