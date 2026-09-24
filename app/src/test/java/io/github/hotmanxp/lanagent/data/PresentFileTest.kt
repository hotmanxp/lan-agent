// data/PresentFileTest.kt — PresentFile wire 解析的回归测试。
//
// 为什么单独一个文件(而不是并进 AgentModelsTest):这个工具的 wire 形状
// 有三个**只有实测才知道**的坑,而且都会静默降级(卡片变空 / 整页打不开),
// 靠手点很难复现:
//
//   1. tool_result 在 **transcript 里是字面量 `'done'`**(服务端
//      `mapToolResultToToolResultBlockParam`,省上下文),真正的元数据只从 SSE
//      走一次且 take-and-delete。所以「重开会话」只能靠 tool_use 的
//      `input.path` —— 这条路径必须有测试守着,否则哪天把 input 解析删了,
//      历史会话的文件卡会整片消失,而直播态一切正常(最难发现的那种坏)。
//   2. `mtime` 是 Node `fs.Stats.mtimeMs` —— **浮点**;`size` 同理不假设整数。
//   3. kind 从 DisplayFiles 的 4 种扩到 9 种(加了文档类),**认不出的一律
//      归 binary** —— 新增 kind 时如果客户端漏映射,`.pdf` 会退回「不支持预览」。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*PresentFileTest*"
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** 服务端那段 wrapper JSON(`presentFileOpencc.ts::call` 的产物)。 */
private fun wrapperJson(fileJson: String, caption: String? = null): String =
    """{"content":[{"type":"json","json":{"file":$fileJson${captionJson(caption)}}}]}"""

private fun captionJson(caption: String?): String =
    if (caption == null) "" else ",\"caption\":\"$caption\""

/** SSE `runtime.tool_result.output` 的真实形态:**一个字符串**,里面才是 JSON。 */
private fun asSseString(fileJson: String, caption: String? = null): JsonElement =
    JsonPrimitive(wrapperJson(fileJson, caption))

private fun metaOf(path: String, kind: String = "image", extra: String = ""): String =
    """{"path":"$path","name":"${path.substringAfterLast('/')}","size":1234,"mtime":1789274126878.9248,"kind":"$kind"$extra}"""

class PresentFileTest {

    /** 直播态:`output` 是 JSON 字符串,解出来应带完整元数据(含浮点 mtime 取整)+ caption。 */
    @Test
    fun `parses meta from the json string carried by sse output`() {
        val file = parsePresentFileMeta(
            asSseString(metaOf("/tmp/chart.png"), caption = "刚生成的季度图")
        )

        assertEquals("/tmp/chart.png", file?.path)
        assertEquals("chart.png", file?.name)
        assertEquals(FileKind.Image, file?.kind)
        assertEquals(1234L, file?.size)
        // 浮点 mtime 必须取整,不能整条丢掉
        assertEquals(1789274126878L, file?.mtime)
        assertEquals("刚生成的季度图", file?.caption)
        assertFalse(file!!.failed)
        assertTrue(file.viewable)
    }

    /** 同一段 JSON 以「已解码的对象」形态给过来(服务端换了包装也不会炸)。 */
    @Test
    fun `parses meta from an already decoded wrapper object`() {
        val file = parsePresentFileMeta(
            wireJson.parseToJsonElement(wrapperJson(metaOf("/tmp/a.html", kind = "html")))
        )
        assertEquals(FileKind.Html, file?.kind)
        assertNull(file?.caption)
    }

    /** `{"file":{…}}` 裸包装 / 裸 FileMeta 也认(包装层换过的兜底)。 */
    @Test
    fun `accepts bare file wrappers`() {
        val bare = wireJson.parseToJsonElement("""{"file":${metaOf("/tmp/a.ts", kind = "text")}}""")
        assertEquals(FileKind.Text, parsePresentFileMeta(bare)?.kind)

        val raw = wireJson.parseToJsonElement(metaOf("/tmp/a.ts", kind = "text"))
        assertEquals("/tmp/a.ts", parsePresentFileMeta(raw)?.path)
    }

    /**
     * **transcript 里的字面量 `'done'`** —— 解出来必须是 null(而不是抛),
     * 否则整条会话流会被打断。
     */
    @Test
    fun `done placeholder yields null instead of throwing`() {
        assertNull(parsePresentFileMeta(JsonPrimitive("done")))
    }

    /** 垃圾输入一律 null:这段跑在 SSE reduce 路径上,抛了会断流。 */
    @Test
    fun `malformed payloads degrade to null`() {
        assertNull(parsePresentFileMeta(null))
        assertNull(parsePresentFileMeta(wireJson.parseToJsonElement("null")))
        assertNull(parsePresentFileMeta(JsonPrimitive("not json at all")))
        assertNull(parsePresentFileMeta(wireJson.parseToJsonElement("""{"content":[]}""")))
        // 缺 path 的 file 直接丢弃,而不是造一条空路径
        assertNull(parsePresentFileMeta(asSseString("""{"name":"no-path.png","kind":"image"}""")))
    }

    /** `error: {code, message}` → 失败态;失败项一律不可预览。 */
    @Test
    fun `stat failure marks the file as failed and not viewable`() {
        val file = parsePresentFileMeta(
            asSseString(
                """{"path":"/tmp/gone.png","name":"gone.png","size":0,"mtime":0,"kind":"binary",
                    "error":{"code":"ENOENT","message":"no such file or directory"}}"""
            )
        )!!
        assertTrue(file.failed)
        assertEquals("no such file or directory", file.error)
        assertFalse(file.viewable)
    }

    /**
     * **九种 kind 的映射**:文档类扩了五种,任何一条漏掉都会让 `.pdf` / `.docx`
     * 退回「不支持内联预览」(旧 DisplayFiles 时代就是这个症状)。
     */
    @Test
    fun `maps every server side kind`() {
        assertEquals(FileKind.Text, fileKindOf("text"))
        assertEquals(FileKind.Image, fileKindOf("image"))
        assertEquals(FileKind.Html, fileKindOf("html"))
        assertEquals(FileKind.Docx, fileKindOf("docx"))
        assertEquals(FileKind.Sheet, fileKindOf("sheet"))
        assertEquals(FileKind.Ppt, fileKindOf("ppt"))
        assertEquals(FileKind.Pdf, fileKindOf("pdf"))
        assertEquals(FileKind.LegacyOffice, fileKindOf("legacy-office"))
        assertEquals(FileKind.Binary, fileKindOf("binary"))
        // 大小写不敏感;认不出的新类型归 binary(不猜成可预览)
        assertEquals(FileKind.Image, fileKindOf("IMAGE"))
        assertEquals(FileKind.Binary, fileKindOf("video"))
        assertEquals(FileKind.Binary, fileKindOf(null))
        // 文档类判定与类型名
        assertTrue(FileKind.Pdf.isDocument)
        assertTrue(FileKind.LegacyOffice.isDocument)
        assertFalse(FileKind.Image.isDocument)
        assertEquals("Word 文档", fileKindLabel(FileKind.Docx))
    }

    /** 文档类只回元数据(`/api/fs/preview` 的文档分支),手机端不内联。 */
    @Test
    fun `document kinds are not renderable on mobile`() {
        val pdf = parsePresentFileMeta(
            asSseString("""{"path":"/tmp/r.pdf","name":"r.pdf","size":2048,"mtime":1,"kind":"pdf"}""")
        )!!
        assertTrue(pdf.kind.isDocument)
        assertFalse(pdf.renderable)
        assertFalse(pdf.viewable)
        assertFalse(pdf.tooLarge)
    }

    // ===== input 派生(重开会话唯一可用的那条路) =====

    /** `{path, caption}` → 一条文件条;kind 按扩展名在客户端猜(服务端 stat 已丢失)。 */
    @Test
    fun `file is derived from tool_use input with extension based kind`() {
        val file = parsePresentFileInput(
            wireJson.parseToJsonElement("""{"path":"/Users/ethan/a/chart.png","caption":"图"}""")
        )!!

        assertEquals("/Users/ethan/a/chart.png", file.path)
        assertEquals("chart.png", file.name)
        assertEquals(FileKind.Image, file.kind)
        assertEquals("图", file.caption)
        // 尺寸未知 —— null 表示「不知道」,不是 0
        assertNull(file.size)
        // 尺寸未知时不该被尺寸门槛拦掉(交给服务端判)
        assertTrue(file.viewable)
    }

    /** input 不是 `{path:...}` 时安静地回 null(别的工具也会走这个函数)。 */
    @Test
    fun `input parser ignores non present file inputs`() {
        assertNull(parsePresentFileInput(null))
        assertNull(parsePresentFileInput(wireJson.parseToJsonElement("""{"command":"ls"}""")))
        assertNull(parsePresentFileInput(wireJson.parseToJsonElement("""{"path":""}""")))
        assertNull(parsePresentFileInput(wireJson.parseToJsonElement("""{"path":1}""")))
        assertNull(parsePresentFileInput(JsonPrimitive("plain string")))
        // 旧 DisplayFiles 的 `{paths:[...]}` 形状**不再识别**(执行期裁决:不留兼容 shim)
        assertNull(parsePresentFileInput(wireJson.parseToJsonElement("""{"paths":["/tmp/a.png"]}""")))
    }

    /** 扩展名分类 —— 与 `shared/fileKind.ts` 的九类逐条对齐。 */
    @Test
    fun `extension classification covers documents and odd paths`() {
        assertEquals(FileKind.Image, classifyByExtension("/a/b/chart.PNG"))
        assertEquals(FileKind.Html, classifyByExtension("/a/b/index.html"))
        assertEquals(FileKind.Text, classifyByExtension("/a/b/notes.MD"))
        assertEquals(FileKind.Text, classifyByExtension("/a/b/app.ts"))
        assertEquals(FileKind.Docx, classifyByExtension("/a/b/spec.docx"))
        assertEquals(FileKind.Sheet, classifyByExtension("/a/b/data.xlsx"))
        assertEquals(FileKind.Sheet, classifyByExtension("/a/b/data.csv"))
        assertEquals(FileKind.Ppt, classifyByExtension("/a/b/slide.pptx"))
        assertEquals(FileKind.Pdf, classifyByExtension("/a/b/report.pdf"))
        assertEquals(FileKind.LegacyOffice, classifyByExtension("/a/b/old.doc"))
        assertEquals(FileKind.Binary, classifyByExtension("/a/b/archive.zip"))
        // 无扩展名 / 隐藏文件不应把 `.bashrc` 当扩展名解析
        assertEquals(FileKind.Binary, classifyByExtension("/Users/ethan/Makefile"))
        assertEquals(FileKind.Binary, classifyByExtension("/Users/ethan/.bashrc"))
        assertEquals(FileKind.Binary, classifyByExtension("/a/b/dir.d/"))
    }

    // ===== 合并 =====

    /**
     * **重开会话的核心场景**:result 是 `'done'`(解析为 null),必须原样保留
     * input 派生那份 —— 否则历史会话里的文件卡会整片消失,而直播态一切正常。
     */
    @Test
    fun `merge keeps the input derived file when the result is the done placeholder`() {
        val input = parsePresentFileInput(wireJson.parseToJsonElement("""{"path":"/tmp/a.png"}"""))
        val merged = mergePresented(fromInput = input, fromResult = parsePresentFileMeta(JsonPrimitive("done")))

        assertEquals("/tmp/a.png", merged?.path)
        // 仍然是数据不全的那份(没有 size)—— 这正是「重开会话」的已知代价
        assertNull(merged?.size)
    }

    /** 直播态:result 有权威元数据,应覆盖 input 的猜测值,但 caption 谁有留谁的。 */
    @Test
    fun `merge prefers result metadata and keeps caption from either side`() {
        val input = parsePresentFileInput(
            wireJson.parseToJsonElement("""{"path":"/tmp/a.unknown","caption":"来自 input"}""")
        )
        // 服务端把它判成 text —— 客户端按扩展名猜的是 binary;caption 侧缺省
        val result = parsePresentFileMeta(
            wireJson.parseToJsonElement(
                wrapperJson("""{"path":"/tmp/a.unknown","name":"a.unknown","size":7,"mtime":1,"kind":"text"}""")
            )
        )

        val merged = mergePresented(fromInput = input, fromResult = result)!!
        assertEquals(FileKind.Text, merged.kind)   // 服务端说了算
        assertEquals(7L, merged.size)
        assertEquals("来自 input", merged.caption) // 只有 input 那侧有
    }

    // ===== 预览门槛 =====

    /**
     * 三类上限各不相同(图片 10 MiB / 文本 1 MiB / 文档走自己的上限),
     * 已知超限 → 不发请求;未知(null)不拦。
     */
    @Test
    fun `oversized files are not viewable but unknown sizes are`() {
        val bigImage = PresentedFile("/tmp/big.png", "big.png", FileKind.Image, size = IMAGE_MAX_BYTES + 1)
        assertTrue(bigImage.tooLarge)
        assertFalse(bigImage.viewable)

        val okImage = PresentedFile("/tmp/ok.png", "ok.png", FileKind.Image, size = IMAGE_MAX_BYTES)
        assertFalse(okImage.tooLarge)
        assertTrue(okImage.viewable)

        // 文本走 1 MiB 那条(JSON 通道),门槛比图片低一档
        val bigText = PresentedFile("/tmp/big.md", "big.md", FileKind.Text, size = PREVIEW_MAX_BYTES + 1)
        assertTrue(bigText.tooLarge)
        assertFalse(bigText.viewable)

        val unknown = PresentedFile("/tmp/x.png", "x.png", FileKind.Image, size = null)
        assertFalse(unknown.tooLarge)
        assertTrue(unknown.viewable)

        // 文档类与 binary 不内联(binary 也没有渲染器)
        assertFalse(PresentedFile("/tmp/z.zip", "z.zip", FileKind.Binary, size = 10L).viewable)
    }

    /** SVG 是矢量图:算 image,但内联缩略图渲染不了(BitmapFactory 解不了 XML)。 */
    @Test
    fun `svg is an image but flagged as vector`() {
        val svg = PresentedFile("/tmp/logo.svg", "logo.svg", FileKind.Image, size = 900L)
        assertTrue(svg.isVectorImage)
        assertTrue(svg.viewable)

        val png = PresentedFile("/tmp/logo.PNG", "logo.PNG", FileKind.Image, size = 900L)
        assertFalse(png.isVectorImage)
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

        // 大图:200 + 只有元数据(content 缺失,字节另走 /api/fs/raw)
        val bigImage = wireJson.decodeFromString<FilePreview>(
            """{"kind":"image","mime":"image/png","size":3145728,"mtime":1}"""
        )
        assertEquals(FileKind.Image, bigImage.fileKind)
        assertNull(bigImage.content)

        // 文档类:同样只有元数据 + ext
        val pdf = wireJson.decodeFromString<FilePreview>("""{"kind":"pdf","size":9,"ext":".pdf"}""")
        assertEquals(FileKind.Pdf, pdf.fileKind)
        assertNull(pdf.content)
        assertEquals(".pdf", pdf.ext)

        // binary 分支没有 content,只有 ext
        val b = wireJson.decodeFromString<FilePreview>("""{"kind":"binary","size":9,"ext":".zip"}""")
        assertEquals(FileKind.Binary, b.fileKind)
        assertNull(b.content)
        assertEquals(".zip", b.ext)
    }
}
