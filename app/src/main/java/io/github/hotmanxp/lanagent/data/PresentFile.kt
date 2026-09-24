// data/PresentFile.kt — `PresentFile` 工具的 wire 解析 + 文件元数据模型。
//
// 服务端实现:opencc-web
// `packages/zn-agent-core/src/opencc-src/server/presentFileOpencc.ts`。
//
//   入参  `{ path: string, caption?: string }`(单个绝对路径)
//   结果  `{"content":[{"type":"json","json":{"file":FileMeta,"caption":...}}]}`
//         FileMeta = { path, name, size, mtime, kind, error?: { code, message } }
//
// 取代 2026-08-20 的 DisplayFiles(多文件元数据卡 → 单文件内容卡,对齐
// 2026-09-24 的 PresentFile 设计):
//   - **单文件**(一次一个,避免 transcript 膨胀):`paths: string[]` 已移除
//   - kind 补齐文档类(docx/sheet/ppt/pdf/legacy-office)—— `.pdf` / `.docx`
//     不再被误判成 binary
//   - 会话里在卡片内**直接渲染内容**(见 `ui/AgentSessionViews.kt` 的
//     `PresentFileCard`)
//
// **两个必须知道的坑**(决定了本模块为什么有两条解析路径):
//
// 1. **transcript 里存的是字面量 `'done'`,不是这段 JSON。** 服务端
//    `mapToolResultToToolResultBlockParam` 回灌给 LLM 的 content 恒为 `'done'`
//    (省上下文),真正的 wrapper 只从 SSE `runtime.tool_result.output` 走一次,
//    而且是 **take-and-delete**(`takePresentFileOutput`,`取出即删`)。
//    所以**重新打开一条历史会话时拿不到 size / kind / error** —— 但 tool_use 的
//    `input.path` 一直在 transcript 里。于是:
//      - [parsePresentFileMeta] — 从 tool_result 拿完整元数据(**仅直播态**)
//      - [parsePresentFileInput] — 从 tool_use 的 input 拿路径(任何时态)
//    两者用 [mergePresented] 合并后才给渲染层(见 `ui/AgentSessionStore`),
//    进程内再来一层 [PresentFileCache] 兜「离开页面再回来」。
//
// 2. **`mtime` 是浮点**(Node `fs.Stats.mtimeMs` 带小数 —— 与
//    [EpochMsSerializer] 注释里那条同源),`size` 也不赌它是整数。一律容错解码。
//
// zai 前端的同款渲染见 opencc-web
// `packages/zai/src/web/src/components/toolRenderers/presentFile.tsx`。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 工具名。SSE `runtime.tool_call` / `runtime.tool_result` 的 `toolName` 字面量。 */
const val PRESENT_FILE_TOOL = "PresentFile"

/**
 * 图片预览上限(10 MiB)—— 与 opencc-web `packages/zai/src/shared/fileKind.ts`
 * 的 `IMAGE_MAX_BYTES` 同一个数字。图片**不走** `/api/fs/preview` 的 base64
 * (那条路的 `maxBytes` 被 clamp 到 1 MiB),字节直接走 `GET /api/fs/raw`
 * 原始流(见 [AgentApi.rawFile] / `routes/fs.ts:1183`)。
 */
const val IMAGE_MAX_BYTES = 10L * 1024 * 1024

/**
 * `GET /api/fs/preview` 的 `maxBytes` 默认值 —— 同时也是它的**上限**
 * (查询参数被 clamp 到 `[1024, 1 MiB]`,`routes/fs.ts:1015` 的
 * `PREVIEW_DEFAULT_MAX = PREVIEW_TEXT_MAX_BYTES`)。超过这个大小的
 * **文本 / HTML** 服务端直接回 413 ETOOBIG,所以已知尺寸超过它的就不要发请求了。
 * (图片走字节通道,文档类只回元数据,两者都不吃这条限制。)
 */
const val PREVIEW_MAX_BYTES = 1_048_576L

/** 文档类走 `/api/fs/raw` 的上限,同 `shared/fileKind.ts` 的 `DOCUMENT_MAX_BYTES`。 */
private const val DOCX_MAX_BYTES = 30L * 1024 * 1024
private const val PPT_MAX_BYTES = 50L * 1024 * 1024

/**
 * 文件类型。服务端 `classifyKind` 的九种取值 —— 与 `shared/fileKind.ts` 的
 * `FilePreviewKind` 一一对应。
 *
 * 认不出的新类型一律归 [Binary](不猜,不崩):宁可显示「不支持内联预览」,
 * 也不要凭空给一个会 415 / 413 的预览入口。
 */
enum class FileKind {
    Text,
    Image,
    Html,

    /** 文档类:手机端没有渲染器,只给「在 Mac 上打开所在目录」。 */
    Docx,
    Sheet,
    Ppt,
    Pdf,

    /** 旧版二进制 Office(`.doc/.ppt/.rtf`)与 ODF(`.odt/.odp`)—— 无渲染库。 */
    LegacyOffice,

    Binary,
}

/** wire 字符串 → [FileKind]。 */
fun fileKindOf(raw: String?): FileKind = when (raw?.lowercase()) {
    "text" -> FileKind.Text
    "image" -> FileKind.Image
    "html" -> FileKind.Html
    "docx" -> FileKind.Docx
    "sheet" -> FileKind.Sheet
    "ppt" -> FileKind.Ppt
    "pdf" -> FileKind.Pdf
    "legacy-office" -> FileKind.LegacyOffice
    else -> FileKind.Binary
}

/** 文档类 —— 手机端不渲染,但能给「Word 文档」这种可读的类型名。 */
val FileKind.isDocument: Boolean
    get() = this == FileKind.Docx || this == FileKind.Sheet || this == FileKind.Ppt ||
        this == FileKind.Pdf || this == FileKind.LegacyOffice

/** 类型名(卡片上的说明文案)。 */
fun fileKindLabel(kind: FileKind): String = when (kind) {
    FileKind.Text -> "文本"
    FileKind.Image -> "图片"
    FileKind.Html -> "网页"
    FileKind.Docx -> "Word 文档"
    FileKind.Sheet -> "表格"
    FileKind.Ppt -> "演示文稿"
    FileKind.Pdf -> "PDF 文档"
    FileKind.LegacyOffice -> "旧版 / 不支持格式的文档"
    FileKind.Binary -> "二进制文件"
}

/**
 * PresentFile 卡片里的那一行(单文件工具 → 至多一条)。
 *
 * [size] / [mtime] 可空 —— 重开历史会话时只剩 `input.path`,服务端的 stat
 * 结果已经从 wire 上消失了(见文件头注释 1)。
 */
data class PresentedFile(
    val path: String,
    val name: String,
    val kind: FileKind,
    /** 字节数。null = 未知(只有路径的那条来源)。 */
    val size: Long? = null,
    val mtime: Long? = null,
    /** 服务端 stat 失败的原因(空 = 正常)。非空即一定不可预览。 */
    val error: String? = null,
    /** 模型给的一句话说明(`PresentFile` 的 `caption`,最长 200 字)。 */
    val caption: String? = null,
) {
    /** 服务端判定的「不是普通文件」(目录 / 无权限 / 不存在)。 */
    val failed: Boolean get() = !error.isNullOrBlank()

    /**
     * `.svg` 是**矢量图** —— `BitmapFactory` 解不了(它本质是 XML),内联缩略图
     * 会直接失败,必须交给全屏查看器里的 WebView 渲染。
     */
    val isVectorImage: Boolean
        get() = kind == FileKind.Image && path.lowercase().endsWith(".svg")

    /** 该 kind 在本机的预览上限。0 = 这条通道没有尺寸门槛(文档类 / binary)。 */
    val maxBytes: Long
        get() = when (kind) {
            FileKind.Image -> IMAGE_MAX_BYTES
            FileKind.Text, FileKind.Html -> PREVIEW_MAX_BYTES
            FileKind.Docx, FileKind.Sheet -> DOCX_MAX_BYTES
            FileKind.Ppt, FileKind.Pdf -> PPT_MAX_BYTES
            else -> 0L
        }

    /**
     * 已知尺寸超过上限 → 不必发请求(发了也是 413 / 打不开)。
     * 尺寸未知(null)时不拦 —— 交给服务端判,而不是在这边猜。
     */
    val tooLarge: Boolean get() = size != null && maxBytes > 0L && size > maxBytes

    /**
     * 手机端有渲染器的三类:图片、文本、网页。
     * 文档类(docx/sheet/ppt/pdf/legacy-office)与 binary 都**不假装能预览** ——
     * 卡片给的是「在 Mac 上打开所在目录」,而不是一个点开只能看到
     * 「不支持内联预览」的入口。
     */
    val renderable: Boolean
        get() = kind == FileKind.Image || kind == FileKind.Text || kind == FileKind.Html

    /** 能否打开会话内的预览层(`ui/FileViewerOverlay.kt`)。 */
    val viewable: Boolean get() = !failed && renderable && !tooLarge

    /** 会拉 `/api/fs/preview` 拿内容的只有文本(图片走字节通道,HTML 只在预览层渲染)。 */
    val needsContent: Boolean get() = kind == FileKind.Text
}

/** 名字兜底:路径可能以 `/` 结尾,`substringAfterLast` 会得到空串。 */
private fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

// ===== 解析:tool_result(完整元数据 + caption,仅直播态) =====

private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/**
 * 从 tool_result 的 output 抽 `{ file, caption }`。**任何认不出的形态都回 null,
 * 不抛** —— 这个函数跑在 SSE reduce 路径上,抛了会把整条会话流打断。
 *
 * 吃四种形态(见 [fileAndCaption]):
 *   - `JsonPrimitive`(SSE 的真实形态:output 是**字符串**,里面才是 JSON)
 *   - `{"content":[{"json":{"file":{…},"caption":…}}]}`(服务端形状)
 *   - `{"file":{…}}`(包装层被拆掉)
 *   - 裸的 FileMeta(`{"path":…}`)
 *
 * 注意 `'done'`(transcript 里的字面量)解析出来也是 null —— 这正是
 * 调用方要保留 input 派生那份的理由。
 */
fun parsePresentFileMeta(output: JsonElement?): PresentedFile? {
    if (output == null || output is JsonNull) return null
    val root = when (output) {
        is JsonPrimitive -> output.contentOrNull?.let { parseJsonOrNull(it) } ?: return null
        else -> output
    }
    val obj = root as? JsonObject ?: return null
    val (fileObj, caption) = obj.fileAndCaption() ?: return null
    return fileObj.toPresentedFileOrNull(caption)
}

private fun parseJsonOrNull(raw: String): JsonElement? =
    runCatching { json.parseToJsonElement(raw) }.getOrNull()

/**
 * 四种包装 → (那个 `file` 对象, caption)。
 *
 * **caption 与 `file` 平级**,不在 file 里面 —— 服务端形状是
 * `{"type":"json","json":{"file":{…},"caption":"…"}}`。在根对象上找 caption
 * 是找不到的(踩过一次:卡片渲染正常、文案永远为空,静默降级)。
 */
private fun JsonObject.fileAndCaption(): Pair<JsonObject, String?>? {
    (this["file"] as? JsonObject)?.let { return it to captionOf(this) }
    (this["content"] as? JsonArray)?.let { content ->
        val first = content.firstOrNull() as? JsonObject ?: return null
        (first["json"] as? JsonObject)?.let { inner ->
            (inner["file"] as? JsonObject)?.let { return it to captionOf(inner) }
            // 裸 FileMeta 塞在 content[0].json 里时的兜底
            if (inner.containsKey("path")) return inner to captionOf(inner)
        }
        // 裸 FileMeta 直接塞在 content[0] 里时的兜底
        if (first.containsKey("path")) return first to captionOf(first)
        return null
    }
    // 整个对象就是 FileMeta(包装层被拆掉)
    if (containsKey("path")) return this to captionOf(this)
    return null
}

private fun captionOf(obj: JsonObject): String? = obj.str("caption")?.takeIf { it.isNotBlank() }

private fun JsonObject.toPresentedFileOrNull(caption: String?): PresentedFile? {
    val path = str("path")?.takeIf { it.isNotBlank() } ?: return null
    val err = this["error"] as? JsonObject
    return PresentedFile(
        path = path,
        name = str("name")?.takeIf { it.isNotBlank() } ?: nameOf(path),
        kind = fileKindOf(str("kind")),
        size = this["size"].toTolerantLong(),
        mtime = this["mtime"].toTolerantLong(),
        error = err?.str("message") ?: err?.str("code"),
        caption = caption,
    )
}

/** 非时间戳的展示型数值(`size`)—— 与 [toEpochMs] 同一套容错规则(整数 / 浮点 / 数字字符串)。 */
private fun JsonElement?.toTolerantLong(): Long? = this.toEpochMs()

// ===== 解析:tool_use 的 input(只有路径 + caption —— 但任何时态都有) =====

/**
 * 从 tool_use 的 `input` 抽 `{ path, caption? }`。`kind` 按扩展名在**客户端**
 * 分类([classifyByExtension]),因为服务端那份 stat 结果在重开会话时已经没了。
 *
 * 猜错的代价很小:预览层拿到的 `kind` 会覆盖它,而 `size` 未知时
 * [PresentedFile.viewable] 也不拦,照样让用户点。
 */
fun parsePresentFileInput(input: JsonElement?): PresentedFile? {
    val obj = input as? JsonObject ?: return null
    // `isString` 不是多余的:`contentOrNull` 会把数字/布尔也转成文本
    // (`1` → "1"),凭空造出一条假路径。非字符串一律丢弃。
    val prim = obj["path"] as? JsonPrimitive ?: return null
    if (!prim.isString) return null
    val path = prim.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return PresentedFile(
        path = path,
        name = nameOf(path),
        kind = classifyByExtension(path),
        caption = obj.str("caption")?.takeIf { it.isNotBlank() },
    )
}

/**
 * 按扩展名分类 —— **规则必须与 opencc-web
 * `packages/zai/src/shared/fileKind.ts` 的 TEXT_EXTS / HTML_EXTS / … 保持一致**
 * (那边注释里也点名了这层同步关系)。只用于 input 派生那条路,直播态下会被
 * 服务端的权威 `kind` 覆盖。
 */
fun classifyByExtension(path: String): FileKind {
    val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return FileKind.Binary
    return when {
        ext in IMAGE_EXTS -> FileKind.Image
        ext in HTML_EXTS -> FileKind.Html
        ext in TEXT_EXTS -> FileKind.Text
        ext in DOCX_EXTS -> FileKind.Docx
        ext in SHEET_EXTS -> FileKind.Sheet
        ext in PPT_EXTS -> FileKind.Ppt
        ext in PDF_EXTS -> FileKind.Pdf
        ext in LEGACY_OFFICE_EXTS -> FileKind.LegacyOffice
        else -> FileKind.Binary
    }
}

private val IMAGE_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif", "svg",
)

private val HTML_EXTS = setOf("html", "htm")

private val TEXT_EXTS = setOf(
    "md", "markdown", "txt", "json", "jsonc", "json5",
    "yaml", "yml", "toml", "ini", "cfg", "conf",
    "ts", "tsx", "js", "jsx", "mjs", "cjs",
    "css", "scss", "less", "xml",
    "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
    "py", "rb", "go", "rs", "java", "kt", "swift",
    "c", "cc", "cpp", "h", "hpp",
    "sql", "graphql", "gql",
    "env", "gitignore", "gitattributes", "lock", "log",
)

private val DOCX_EXTS = setOf("docx", "docm")

/** SheetJS 能读的表格格式(含 `.csv` —— 它归表格不归文本,与 `/api/fs/preview` 一致)。 */
private val SHEET_EXTS = setOf("xlsx", "xlsm", "xlsb", "xls", "ods", "csv")

private val PPT_EXTS = setOf("pptx", "pptm")

private val PDF_EXTS = setOf("pdf")

private val LEGACY_OFFICE_EXTS = setOf("doc", "ppt", "rtf", "odt", "odp")

/**
 * 合并两条来源:tool_result 的元数据(有真 size / kind / error)为准,
 * input 那份补它缺的(重开会话时 result 是 `'done'`,只剩 input)。
 *
 * caption 只可能来自其中一条(两条各自从自己的 JSON 里读),谁有就用谁的 ——
 * 服务端两侧都会带,但历史会话只有 input 那侧有。
 */
fun mergePresented(
    fromInput: PresentedFile?,
    fromResult: PresentedFile?,
): PresentedFile? {
    if (fromResult == null) return fromInput
    if (fromInput == null) return fromResult
    return fromResult.copy(caption = fromResult.caption ?: fromInput.caption)
}

/**
 * 进程内的 PresentFile 元数据缓存(`toolUseId → 带 stat 结果的那份`)。
 *
 * **为什么必须有**:那份元数据**只存在于直播态** —— transcript 里是字面量
 * `'done'`,服务端又 take-and-delete。而 NavHost 在跳到别的路由时会销毁会话页
 * 的 composition(点文件进查看器、切底栏 tab 都算),返回时重新 `hydrate` ——
 * 于是卡片上的「293.6 KB · 5 天前」会凭空消失(真机实测:进一次查看器回来,
 * 副标题就从 `293.6 KB · 5 天前 · 图片` 退化成 `图片`)。同一段会话里再也不会
 * 恢复,因为 wire 上已经没有第二份了。
 *
 * 与 `data/ActiveTasks.kt` 的 `ActiveTasksCache` 同款取舍:**只为「离开再回来」
 * 兜底,进程内、不落盘**。冷启动后依旧以 transcript 为准(那时只有路径)——
 * 这不是可以靠缓存消灭的限制,是服务端的形状决定的。
 *
 * 全部访问都在主线程(store 只在 Compose 状态里改),但仍加锁 —— 这是个全局
 * 单例,以后被别的线程碰到不该出意外。
 */
object PresentFileCache {

    /** 上限只是防泄漏:一次会话里 PresentFile 调用次数是几十次量级。 */
    private const val MAX_ENTRIES = 64

    private val entries = object : LinkedHashMap<String, PresentedFile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, PresentedFile>): Boolean =
            size > MAX_ENTRIES
    }

    /** 记下带 stat 结果的那份,并原样返回(方便调用方串联)。 */
    @Synchronized
    fun remember(toolUseId: String, file: PresentedFile): PresentedFile {
        if (toolUseId.isNotBlank()) entries[toolUseId] = file
        return file
    }

    @Synchronized
    fun recall(toolUseId: String?): PresentedFile? =
        if (toolUseId.isNullOrBlank()) null else entries[toolUseId]
}

// ===== GET /api/fs/preview 的响应 =====

/**
 * `GET /api/fs/preview?path=` 的响应(`routes/fs.ts:1043`)。
 *
 * `content` 的编码**按 kind 分岔**:image 是 base64(≤ 1 MiB 才有),
 * text / html 是原文,binary / 文档类 / 超限图片整个字段缺失(只有 ext / mime)。
 */
@Serializable
data class FilePreview(
    val kind: String = "binary",
    val mime: String? = null,
    val content: String? = null,
    @Serializable(with = TolerantLongSerializer::class)
    val size: Long = 0L,
    @Serializable(with = TolerantLongSerializer::class)
    val mtime: Long = 0L,
    /** 仅 binary / 文档类分支回填(如 `.zip` / `.pdf`),给「不支持预览」的文案用。 */
    val ext: String? = null,
) {
    val fileKind: FileKind get() = fileKindOf(kind)
}

/**
 * 容忍「整数 / 浮点 / 数字字符串」的 Long 解码器 —— 与 [EpochMsSerializer]
 * 同一套规则,只是名字对**非时间戳**字段(如 `size`)更诚实。
 */
object TolerantLongSerializer : KSerializer<Long> by EpochMsSerializer

/** `POST /api/fs/reveal` 的响应:在 Mac 上打开文件所在目录。 */
@Serializable
data class RevealResponse(val ok: Boolean = false, val error: String? = null)
