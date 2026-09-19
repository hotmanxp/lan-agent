// data/DisplayFiles.kt — `DisplayFiles` 工具的 wire 解析 + 文件元数据模型。
//
// 服务端实现:opencc-web
// `packages/zn-agent-core/src/opencc-src/server/displayFilesOpencc.ts`。
//
//   入参  `{ paths: string[] }`(绝对路径,1..20 个)
//   结果  `{"content":[{"type":"json","json":{"files":[FileMeta...]}}]}`
//         FileMeta = { path, name, size, mtime, kind, error?: { code, message } }
//         kind ∈ text | image | html | binary(按扩展名分类)
//
// **两个必须知道的坑**(决定了本模块为什么有两条解析路径):
//
// 1. **transcript 里存的是字面量 `'done'`,不是这段 JSON。** 服务端
//    `mapToolResultToToolResultBlockParam` 回灌给 LLM 的 content 恒为 `'done'`
//    (省上下文),真正的 wrapper 只从 SSE `runtime.tool_result.output` 走一次,
//    而且是 **take-and-delete**(`takeDisplayFilesOutput`,`取出即删`)。
//    所以**重新打开一条历史会话时拿不到 size / kind / error** —— 但 tool_use 的
//    `input.paths` 一直在 transcript 里。于是:
//      - [parseDisplayFileMeta]  — 从 tool_result 拿完整元数据(**仅直播态**)
//      - [parseDisplayFilePaths] — 从 tool_use 的 input 拿路径(任何时态)
//    两者用 [mergeDisplayFiles] 合并后才给渲染层(见 `AgentSessionStore`)。
//
// 2. **`mtime` 是浮点**(Node `fs.Stats.mtimeMs` 带小数 —— 与
//    [EpochMsSerializer] 注释里那条同源),`size` 也不赌它是整数。一律容错解码。
//
// zai 前端的同款渲染见 opencc-web
// `packages/zai/src/web/src/components/toolRenderers/fileDisplay.tsx`。
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
const val DISPLAY_FILES_TOOL = "DisplayFiles"

/**
 * `GET /api/fs/preview` 的 `maxBytes` 默认值 —— 同时也是它的**上限**
 * (查询参数被 clamp 到 `[1024, 1 MiB]`,`routes/fs.ts:990-992` 的
 * `PREVIEW_DEFAULT_MAX = 1_048_576`)。超过这个大小的文件服务端直接回
 * 413 ETOOBIG,所以已知尺寸超过它的就不要再发请求了。
 */
const val FILE_PREVIEW_MAX_BYTES = 1_048_576L

/** 文件类型。服务端 `classifyKind` 的四种取值。 */
enum class FileKind { Text, Image, Html, Binary }

/** wire 字符串 → [FileKind]。认不出的新类型一律归 [FileKind.Binary](不猜,不崩)。 */
fun fileKindOf(raw: String?): FileKind = when (raw?.lowercase()) {
    "image" -> FileKind.Image
    "html" -> FileKind.Html
    "text" -> FileKind.Text
    else -> FileKind.Binary
}

/**
 * DisplayFiles 卡片里的一行。
 *
 * [size] / [mtime] 可空 —— 重开历史会话时只剩 `input.paths`,服务端的 stat
 * 结果已经从 wire 上消失了(见文件头注释 1)。
 */
data class DisplayFile(
    val path: String,
    val name: String,
    val kind: FileKind,
    /** 字节数。null = 未知(只有路径的那条来源)。 */
    val size: Long? = null,
    val mtime: Long? = null,
    /** 服务端 stat 失败的原因(空 = 正常)。非空即一定不可预览。 */
    val error: String? = null,
) {
    /** 服务端判定的「不是普通文件」(目录 / 无权限 / 不存在)。 */
    val failed: Boolean get() = !error.isNullOrBlank()

    /**
     * 已知尺寸超过服务端上限 → 不必发请求(发了也是 413)。
     * 尺寸未知(null)时不拦 —— 交给服务端判,而不是在这边猜。
     */
    val tooLarge: Boolean get() = size != null && size > FILE_PREVIEW_MAX_BYTES

    /**
     * 能否预览。对齐 web 端 `FileCard` 的判据(error / 空文件 / binary 都不给)
     * 再加上服务端的尺寸上限。
     */
    val previewable: Boolean
        get() = !failed && !tooLarge && kind != FileKind.Binary && size != 0L

    /**
     * `.svg` 是**矢量图** —— `BitmapFactory` 解不了(它本质是 XML),内联缩略图
     * 会直接失败,必须交给全屏查看器里的 WebView 渲染。
     */
    val isVectorImage: Boolean
        get() = kind == FileKind.Image && path.lowercase().endsWith(".svg")
}

/** 名字兜底:路径可能以 `/` 结尾,`substringAfterLast` 会得到空串。 */
private fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

// ===== 解析:tool_result(完整元数据,仅直播态) =====

private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/**
 * 从 tool_result 的 output 抽 `files`。**任何认不出的形态都回空列表,不抛** ——
 * 这个函数跑在 SSE reduce 路径上,抛了会把整条会话流打断。
 *
 * 吃三种形态:
 *   - `JsonPrimitive`(SSE 的真实形态:output 是**字符串**,里面才是 JSON)
 *   - `{"content":[{"json":{"files":[…]}}]}`(解码后的 wrapper)
 *   - `{"files":[…]}` / 裸数组(服务端换了包装时的兜底)
 *
 * 注意 `'done'`(transcript 里的字面量)解析出来也是空列表 —— 这正是
 * 调用方要保留 input 派生那份的理由。
 */
fun parseDisplayFileMeta(output: JsonElement?): List<DisplayFile> {
    if (output == null || output is JsonNull) return emptyList()
    val root = when (output) {
        is JsonPrimitive -> output.contentOrNull?.let { parseJsonOrNull(it) } ?: return emptyList()
        else -> output
    }
    val files = root.filesArrayOrNull() ?: return emptyList()
    return files.mapNotNull { it.toDisplayFileOrNull() }
}

private fun parseJsonOrNull(raw: String): JsonElement? =
    runCatching { json.parseToJsonElement(raw) }.getOrNull()

private fun JsonElement.filesArrayOrNull(): JsonArray? = when (this) {
    is JsonArray -> this
    is JsonObject -> this["files"]?.let { it as? JsonArray }
        ?: this["content"]?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("json")
            ?.let { it as? JsonObject }
            ?.get("files")
            ?.let { it as? JsonArray }
    else -> null
}

private fun JsonElement.toDisplayFileOrNull(): DisplayFile? {
    val obj = this as? JsonObject ?: return null
    val path = obj.str("path")?.takeIf { it.isNotBlank() } ?: return null
    val err = obj["error"] as? JsonObject
    return DisplayFile(
        path = path,
        name = obj.str("name")?.takeIf { it.isNotBlank() } ?: nameOf(path),
        kind = fileKindOf(obj.str("kind")),
        size = obj["size"].toDisplayNumber(),
        mtime = obj["mtime"].toDisplayNumber(),
        error = err?.str("message") ?: err?.str("code"),
    )
}

/** 非时间戳的展示型数值(`size`)—— 与 [toEpochMs] 同一套容错规则(整数 / 浮点 / 数字字符串)。 */
private fun JsonElement?.toDisplayNumber(): Long? = this.toEpochMs()

// ===== 解析:tool_use 的 input(只有路径 —— 但任何时态都有) =====

/**
 * 从 tool_use 的 `input` 抽路径。`kind` 按扩展名在**客户端**分类
 * ([classifyByExtension]),因为服务端那份 stat 结果在重开会话时已经没了。
 *
 * 猜错的代价很小:预览请求回来时带的 `kind` 会覆盖它(见 `AgentSessionStore`),
 * 而 `size` 未知时 [DisplayFile.previewable] 也不拦,照样让用户点。
 */
fun parseDisplayFilePaths(input: JsonElement?): List<DisplayFile> {
    val arr = (input as? JsonObject)?.get("paths") as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        // `isString` 不是多余的:zod schema 保证了 string,但 `contentOrNull` 会把
        // 数字/布尔也转成文本(`1` → "1"),凭空造出一条假路径。非字符串一律丢弃。
        val prim = el as? JsonPrimitive ?: return@mapNotNull null
        if (!prim.isString) return@mapNotNull null
        val path = prim.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DisplayFile(path = path, name = nameOf(path), kind = classifyByExtension(path))
    }
}

/**
 * 按扩展名分类 —— **规则必须与 opencc-web
 * `displayFilesOpencc.ts:38-70` 的 TEXT_EXTS / HTML_EXTS / IMAGE_EXTS 保持同步**
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
)

/**
 * 合并两条来源:tool_result 的元数据(有真 size / kind / error)为准,
 * input 的路径列表补它缺的项(重开会话时 result 是 `'done'`,只剩 input;
 * 服务端理论上两者同序同长,但按 path 去重更稳)。
 */
fun mergeDisplayFiles(
    fromInput: List<DisplayFile>,
    fromResult: List<DisplayFile>,
): List<DisplayFile> {
    if (fromResult.isEmpty()) return fromInput
    if (fromInput.isEmpty()) return fromResult
    val seen = fromResult.mapTo(HashSet()) { it.path }
    return fromResult + fromInput.filter { it.path !in seen }
}

/**
 * 进程内的 DisplayFiles 元数据缓存(`toolUseId → 带 stat 结果的那份列表`)。
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
object DisplayFilesCache {

    /** 上限只是防泄漏:一次会话里 DisplayFiles 调用次数是个位数。 */
    private const val MAX_ENTRIES = 64

    private val entries = object : LinkedHashMap<String, List<DisplayFile>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<DisplayFile>>): Boolean =
            size > MAX_ENTRIES
    }

    /** 记下带 stat 结果的那份,并原样返回(方便调用方串联)。 */
    @Synchronized
    fun remember(toolUseId: String, files: List<DisplayFile>): List<DisplayFile> {
        if (toolUseId.isBlank() || files.isEmpty()) return files
        entries[toolUseId] = files
        return files
    }

    @Synchronized
    fun recall(toolUseId: String?): List<DisplayFile> =
        if (toolUseId.isNullOrBlank()) emptyList() else entries[toolUseId].orEmpty()
}

// ===== GET /api/fs/preview 的响应 =====

/**
 * `GET /api/fs/preview?path=` 的响应(`routes/fs.ts:1020-1085`)。
 *
 * `content` 的编码**按 kind 分岔**:image 是 base64,text / html 是原文,
 * binary 整个字段缺失(只有 ext)。
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
    /** 仅 binary 分支回填(如 `.zip`),给「不支持预览」的文案用。 */
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
