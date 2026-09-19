// data/DisplayFilesTest.kt — DisplayFiles wire 解析的回归测试。
//
// 为什么单独一个文件(而不是并进 AgentModelsTest):这个工具的 wire 形状
// 有两个**只有实测才知道**的坑,而且都会静默降级(卡片变空 / 整页打不开),
// 靠手点很难复现:
//
//   1. tool_result 在 **transcript 里是字面量 `'done'`**(服务端
//      `mapToolResultToToolResultBlockParam`,省上下文),真正的元数据只从 SSE
//      走一次且 take-and-delete。所以「重开会话」只能靠 tool_use 的
//      `input.paths` —— 这条路径必须有测试守着,否则哪天把 input 解析删了,
//      历史会话的文件卡会整片消失,而直播态一切正常(最难发现的那种坏)。
//   2. `mtime` 是 Node `fs.Stats.mtimeMs` —— **浮点**;`size` 同理不假设整数。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*DisplayFilesTest*"
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** SSE `runtime.tool_result.output` 的真实形态:**一个字符串**,里面才是 JSON。 */
private fun sseOutput(vararg fileJson: String): String = buildString {
    append("""{"content":[{"type":"json","json":{"files":[""")
    append(fileJson.joinToString(","))
    append("]}}]}")
}

/** 把 wrapper JSON 包成 wire 上那个「字符串里的 JSON」形态。 */
private fun asSseString(vararg fileJson: String): JsonElement = JsonPrimitive(sseOutput(*fileJson))

private fun metaOf(path: String, kind: String = "image", extra: String = ""): String =
    """{"path":"$path","name":"${path.substringAfterLast('/')}","size":1234,"mtime":1789274126878.9248,"kind":"$kind"$extra}"""

class DisplayFilesTest {

    /** 直播态:`output` 是 JSON 字符串,解出来应带完整元数据(含浮点 mtime 取整)。 */
    @Test
    fun `parses meta from the json string carried by sse output`() {
        val files = parseDisplayFileMeta(asSseString(metaOf("/tmp/chart.png")))

        assertEquals(1, files.size)
        val f = files[0]
        assertEquals("/tmp/chart.png", f.path)
        assertEquals("chart.png", f.name)
        assertEquals(FileKind.Image, f.kind)
        assertEquals(1234L, f.size)
        // 浮点 mtime 必须取整,不能整条丢掉
        assertEquals(1789274126878L, f.mtime)
        assertFalse(f.failed)
        assertTrue(f.previewable)
    }

    /** 同一段 JSON 以「已解码的对象」形态给过来(服务端换了包装也不会炸)。 */
    @Test
    fun `parses meta from an already decoded wrapper object`() {
        val files = parseDisplayFileMeta(
            wireJson.parseToJsonElement(sseOutput(metaOf("/tmp/a.html", kind = "html")))
        )
        assertEquals(1, files.size)
        assertEquals(FileKind.Html, files[0].kind)
    }

    /**
     * **transcript 里的字面量 `'done'`** —— 解出来必须是空列表(而不是抛),
     * 否则整条会话流会被打断。
     */
    @Test
    fun `done placeholder yields no files instead of throwing`() {
        assertTrue(parseDisplayFileMeta(JsonPrimitive("done")).isEmpty())
    }

    /** 垃圾输入一律空列表:这段跑在 SSE reduce 路径上,抛了会断流。 */
    @Test
    fun `malformed payloads degrade to an empty list`() {
        assertTrue(parseDisplayFileMeta(null).isEmpty())
        assertTrue(parseDisplayFileMeta(wireJson.parseToJsonElement("null")).isEmpty())
        assertTrue(parseDisplayFileMeta(JsonPrimitive("not json at all")).isEmpty())
        assertTrue(parseDisplayFileMeta(wireJson.parseToJsonElement("""{"content":[]}""")).isEmpty())
        // 缺 path 的条目跳过,但同批里合法的照常保留
        val mixed = asSseString("""{"name":"no-path.png","kind":"image"}""", metaOf("/tmp/ok.png"))
        assertEquals(listOf("/tmp/ok.png"), parseDisplayFileMeta(mixed).map { it.path })
    }

    /** `error: {code, message}` → 失败态;失败项一律不可预览。 */
    @Test
    fun `stat failure marks the file as failed and unpublishable`() {
        val f = parseDisplayFileMeta(
            asSseString(
                """{"path":"/tmp/gone.png","name":"gone.png","size":0,"mtime":0,"kind":"binary",
                    "error":{"code":"ENOENT","message":"no such file or directory"}}"""
            )
        ).single()
        assertTrue(f.failed)
        assertEquals("no such file or directory", f.error)
        assertFalse(f.previewable)
    }

    /** 认不出的 `kind`(服务端以后新增类型)归 Binary —— 不崩、也不误判成可预览。 */
    @Test
    fun `unknown kind falls back to binary`() {
        val f = parseDisplayFileMeta(
            asSseString("""{"path":"/tmp/x.zzz","name":"x.zzz","size":10,"mtime":1,"kind":"video"}""")
        ).single()
        assertEquals(FileKind.Binary, f.kind)
    }

    // ===== input 派生(重开会话唯一可用的那条路) =====

    /** `{paths: [...]}` → 路径列表;kind 按扩展名在客户端猜(服务端 stat 已丢失)。 */
    @Test
    fun `paths are derived from tool_use input with extension based kinds`() {
        val input = wireJson.parseToJsonElement(
            """{"paths":["/Users/ethan/a/chart.png","/tmp/report.html","/tmp/notes.md","/tmp/blob.bin"]}"""
        )

        val files = parseDisplayFilePaths(input)

        assertEquals(4, files.size)
        assertEquals(FileKind.Image, files[0].kind)
        assertEquals(FileKind.Html, files[1].kind)
        assertEquals(FileKind.Text, files[2].kind)
        assertEquals(FileKind.Binary, files[3].kind)
        // 名字取 basename;尺寸未知 —— null 表示「不知道」,不是 0
        assertEquals("chart.png", files[0].name)
        assertEquals(null, files[0].size)
        // 尺寸未知时不该被尺寸门槛拦掉(交给服务端判)
        assertTrue(files[0].previewable)
    }

    /** input 不是 `{paths:...}` 时安静地回空列表(别的工具也会走这个函数)。 */
    @Test
    fun `input parser ignores non display files inputs`() {
        assertTrue(parseDisplayFilePaths(null).isEmpty())
        assertTrue(parseDisplayFilePaths(wireJson.parseToJsonElement("""{"command":"ls"}""")).isEmpty())
        assertTrue(parseDisplayFilePaths(JsonPrimitive("plain string")).isEmpty())
        // 空串 / 非字符串条目跳过
        assertTrue(
            parseDisplayFilePaths(wireJson.parseToJsonElement("""{"paths":["",1,null]}""")).isEmpty()
        )
    }

    // ===== 合并 =====

    /**
     * **重开会话的核心场景**:result 是 `'done'`(解析为空),必须原样保留
     * input 派生那份 —— 否则历史会话里的文件卡会整片消失,而直播态一切正常。
     */
    @Test
    fun `merge keeps input derived list when the result is the done placeholder`() {
        val input = parseDisplayFilePaths(
            wireJson.parseToJsonElement("""{"paths":["/tmp/a.png","/tmp/b.html"]}""")
        )
        val result = parseDisplayFileMeta(JsonPrimitive("done"))

        val merged = mergeDisplayFiles(input, result)

        assertEquals(listOf("/tmp/a.png", "/tmp/b.html"), merged.map { it.path })
        // 仍然是数据不全的那份(没有 size)—— 这正是「重开会话」的已知代价
        assertEquals(null, merged[0].size)
    }

    /** 直播态:result 有权威元数据,应按 path 覆盖 input 的猜测值。 */
    @Test
    fun `merge prefers result metadata and keeps input only for missing paths`() {
        val input = parseDisplayFilePaths(
            wireJson.parseToJsonElement("""{"paths":["/tmp/a.unknown","/tmp/b.png"]}""")
        )
        // 服务端只回了 a 的元数据(且把它判成 text —— 客户端猜的是 binary)
        val result = parseDisplayFileMeta(
            wireJson.parseToJsonElement(
                sseOutput("""{"path":"/tmp/a.unknown","name":"a.unknown","size":7,"mtime":1,"kind":"text"}""")
            )
        )

        val merged = mergeDisplayFiles(input, result)

        assertEquals(2, merged.size)
        assertEquals(FileKind.Text, merged[0].kind)   // 服务端说了算
        assertEquals(7L, merged[0].size)
        assertEquals("/tmp/b.png", merged[1].path)    // result 没提的靠 input 补
    }

    // ===== 预览门槛 =====

    /** 已知超过服务端 1 MiB 上限(`routes/fs.ts` 的 PREVIEW_DEFAULT_MAX)→ 不发请求;未知(null)不拦。 */
    @Test
    fun `oversized files are not previewable but unknown sizes are`() {
        val big = DisplayFile("/tmp/big.png", "big.png", FileKind.Image, size = FILE_PREVIEW_MAX_BYTES + 1)
        assertTrue(big.tooLarge)
        assertFalse(big.previewable)

        val unknown = DisplayFile("/tmp/big.png", "big.png", FileKind.Image, size = null)
        assertFalse(unknown.tooLarge)
        assertTrue(unknown.previewable)

        // 空文件给不出内容
        assertFalse(DisplayFile("/tmp/e.md", "e.md", FileKind.Text, size = 0L).previewable)
        // binary 永远不内联
        assertFalse(DisplayFile("/tmp/z.zip", "z.zip", FileKind.Binary, size = 10L).previewable)
    }

    /** SVG 是矢量图:算 image,但内联缩略图渲染不了(BitmapFactory 解不了 XML)。 */
    @Test
    fun `svg is an image but flagged as vector`() {
        val svg = DisplayFile("/tmp/logo.svg", "logo.svg", FileKind.Image, size = 900L)
        assertTrue(svg.isVectorImage)
        assertTrue(svg.previewable)

        val png = DisplayFile("/tmp/logo.PNG", "logo.PNG", FileKind.Image, size = 900L)
        assertFalse(png.isVectorImage)
    }

    /** 无扩展名 / 隐藏文件不应把 `.bashrc` 当扩展名解析。 */
    @Test
    fun `extension classification tolerates odd paths`() {
        assertEquals(FileKind.Binary, classifyByExtension("/Users/ethan/Makefile"))
        assertEquals(FileKind.Binary, classifyByExtension("/Users/ethan/.bashrc"))
        assertEquals(FileKind.Text, classifyByExtension("/a/b/notes.MD"))  // 大小写不敏感
        assertEquals(FileKind.Binary, classifyByExtension("/a/b/dir.d/"))
    }

    /** `GET /api/fs/preview` 的响应:size 也走容错解码,kind 走同一套映射。 */
    @Test
    fun `file preview decodes tolerant size and maps kind`() {
        val p = wireJson.decodeFromString<FilePreview>(
            """{"kind":"image","mime":"image/png","content":"AAAA","size":2048,"mtime":1789274126878.9248}"""
        )
        assertEquals(FileKind.Image, p.fileKind)
        assertEquals(2048L, p.size)
        assertEquals(1789274126878L, p.mtime)

        // binary 分支没有 content,只有 ext
        val b = wireJson.decodeFromString<FilePreview>("""{"kind":"binary","size":9,"ext":".zip"}""")
        assertEquals(FileKind.Binary, b.fileKind)
        assertEquals(null, b.content)
        assertEquals(".zip", b.ext)
    }
}
