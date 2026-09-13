// data/AgentModels.kt — zai Agent 会话的 wire 模型。
//
// 全部字段名/可选性来自 opencc-web 服务端实测(2026-09-13 调研):
//   - `GET /api/agent/sessions`      → { sessions: AgentSessionMeta[] }
//   - `GET /api/agent/sessions/:id`  → { transcript: { meta, messages } }
//   - `GET /api/agent/sessions/:id/state` → { cwd, v2Tasks, bashTasks, agentTasks }
//   - `GET /api/event?sid=`          → SSE,data 是 JSON 事件对象
//
// **transcript.messages 是磁盘 JSONL 原文**,不是归一化事件流。里面混着
// 非消息控制行(`custom-title` / `session-meta`)和 Anthropic 原生 content
// blocks,所以:
//   1. 必须按 `type` + `message != null` 双重过滤(见 AgentTranscript 的解析)
//   2. `message.content` 既可能是 String(user 纯文本 prompt),也可能是
//      ContentBlock 数组,用 JsonElement 接住再手工分支
//   3. `isMeta == true` 的条目 LLM 可见但 UI 必须隐藏
//
// 服务端用 `explicitNulls = false` + `ignoreUnknownKeys = true` 解析,新增
// 字段不会炸老客户端(对齐 InstancesApi 的 Json 配置)。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// ===== 容错数值 =====

/**
 * 容忍「整数 / 浮点 / 数字字符串」三种写法的 epoch 毫秒序列化器。
 *
 * **为什么必须有**(2026-09-14 真机踩到):`GET /api/agent/sessions` 里
 * `updatedAt` 直接来自 Node `fs.Stats.mtimeMs` —— 那是**浮点**毫秒
 * (`1789274126878.9248`),而 `createdAt` 走 `Date.now()` 是整数。字段声明成
 * 裸 `Long` 时,只要有**一条**会话的 mtime 带小数,`decodeFromString` 就抛
 * `Unexpected symbol ':' in numeric literal at path: $.sessions[0].updatedAt`,
 * 整个会话列表页直接报错打不开。
 *
 * 精度损失 < 1ms,对「x 分钟前」的展示无影响。所有时间戳字段一律带上它,
 * 别再按「服务端应该给整数」的假设去赌。
 */
object EpochMsSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.hotmanxp.lanagent.EpochMs", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long = decodeEpochMs(decoder) ?: 0L
}

/** [EpochMsSerializer] 的可空版本(字段可能整体缺省)。 */
object EpochMsNullableSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.hotmanxp.lanagent.EpochMs?", PrimitiveKind.LONG)

    @OptIn(ExperimentalSerializationApi::class)  // encodeNull 仍是实验 API
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long? = decodeEpochMs(decoder)
}

private fun decodeEpochMs(decoder: Decoder): Long? {
    val input = decoder as? JsonDecoder ?: return decoder.decodeLong()
    return input.decodeJsonElement().toEpochMs()
}

// ===== 会话列表 =====

/**
 * `store.list()` 的返回项(`legacyTranscriptStore.ts:46-61`)。
 *
 * 注意几个坑:
 *   - `updatedAt` 取自文件 mtime,stat 失败时是 `0`(不是 null),UI 要兜底
 *   - `updatedAt` 是 **float**(`fs.Stats.mtimeMs` 带小数),必须走 [EpochMsSerializer]
 *   - `model` 可能是字面量 `"unknown"`(新会话没选过模型)
 *   - `permissionMode` 一定有值
 *   - `createdAt` 在进程重启后可能退化成「当前时间」(REGISTRY miss)
 */
@Serializable
data class AgentSessionMeta(
    val sessionId: String,
    val cwd: String = "",
    val model: String = "unknown",
    val providerId: String? = null,
    val permissionMode: String? = null,
    @Serializable(with = EpochMsSerializer::class)
    val createdAt: Long = 0L,
    val title: String? = null,
    @Serializable(with = EpochMsSerializer::class)
    val updatedAt: Long = 0L,
)

@Serializable
data class AgentSessionsResponse(val sessions: List<AgentSessionMeta> = emptyList())

@Serializable
data class CreateSessionResponse(val sessionId: String)

// ===== transcript =====

@Serializable
data class TranscriptResponse(val transcript: Transcript = Transcript())

@Serializable
data class Transcript(
    val meta: TranscriptMeta = TranscriptMeta(),
    val messages: List<TranscriptEntry> = emptyList(),
)

/**
 * transcript 的 meta。`updatedAt` / `mainAgent` 只在 REGISTRY 命中时存在,
 * 进程重启后读盘路径不回填(缺口是服务端行为,不是解析问题)。
 */
@Serializable
data class TranscriptMeta(
    val cwd: String? = null,
    val model: String? = null,
    val sessionId: String? = null,
    val title: String? = null,
    val providerId: String? = null,
    val mainAgent: String? = null,
    val permissionMode: String? = null,
    @Serializable(with = EpochMsNullableSerializer::class)
    val createdAt: Long? = null,
    @Serializable(with = EpochMsNullableSerializer::class)
    val updatedAt: Long? = null,
)

/**
 * JSONL 里的一行。除 `type` 外全部可空 —— 控制行(`custom-title` /
 * `session-meta` / `queue-operation` / `file-history-snapshot`)只有
 * `uuid/timestamp/customTitle` 这类字段,用同一个类接住,再在解析阶段按需
 * 丢弃,比开两个 sealed 分支省事。
 *
 * **`timestamp` 必须用 `JsonElement` 而不是 `Long`**:实测同一个文件里两种
 * 形态并存 —— `assistant` / `user` 条目是 epoch 毫秒**数字**(`1788658221846`),
 * 而 `system` / `queue-operation` 这类系统条目是 ISO-8601**字符串**
 * (`"2026-09-06T04:06:35.048Z"`)。如果声明成 `Long`(即便 `isLenient=true`),
 * 只要有**一条** system 条目就会让整份 transcript 反序列化失败 ——
 * 表现是整个会话详情页直接报错打不开。用 [tsMs] 统一折算。
 */
@Serializable
data class TranscriptEntry(
    val uuid: String? = null,
    val parentUuid: String? = null,
    val type: String? = null,
    val timestamp: JsonElement? = null,
    val message: TranscriptBody? = null,
    val cwd: String? = null,
    val sessionId: String? = null,
    val userType: String? = null,
    val version: String? = null,
    val isSidechain: Boolean = false,
    val isMeta: Boolean = false,
    /** 控制行 `custom-title` 的载荷。 */
    val customTitle: String? = null,
) {
    /** 归一化后的 epoch 毫秒(数字直取,ISO 字符串解析);无法识别 → null。 */
    val tsMs: Long? get() = timestamp.toEpochMs()
}

@Serializable
data class TranscriptBody(
    val role: String? = null,
    /** 既可能是 `JsonPrimitive`(纯文本),也可能是 `JsonArray`(ContentBlock[])。 */
    val content: JsonElement? = null,
)

/**
 * Anthropic content block 的宽松投影。服务端 zod schema 全部 `.passthrough()`,
 * 所以字段只按 `type` 取用,其余忽略。
 */
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    val toolUseId: String? = null,
    val content: JsonElement? = null,
    val isError: Boolean = false,
)

/** 把 `message.content` 统一成 `List<ContentBlock>`;String → 单个 text block。 */
fun TranscriptBody.toContentBlocks(): List<ContentBlock> {
    return when (val c = content) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> listOf(ContentBlock(type = "text", text = c.contentOrNull.orEmpty()))
        is JsonArray -> c.mapNotNull { it.toContentBlockOrNull() }
        else -> emptyList()
    }
}

private fun JsonElement.toContentBlockOrNull(): ContentBlock? {
    val obj = this as? JsonObject ?: return null
    val type = obj.str("type") ?: return null
    return ContentBlock(
        type = type,
        text = obj.str("text"),
        thinking = obj.str("thinking"),
        id = obj.str("id"),
        name = obj.str("name"),
        input = obj["input"],
        toolUseId = obj.str("tool_use_id"),
        content = obj["content"],
        isError = obj.bool("is_error") ?: false,
    )
}

// ===== session state =====

@Serializable
data class SessionStateResponse(
    val cwd: SessionCwd? = null,
    val v2Tasks: List<V2Task> = emptyList(),
    /**
     * bash 后台任务 / 后台 agent 任务。两端都只用来驱动「任务抽屉」,
     * 会话详情页暂不渲染 — 用 `JsonElement` 原样接住,避免为了不展示的
     * 字段维护一套完整数据类。后续要做任务面板时再补类型。
     */
    val bashTasks: List<JsonElement> = emptyList(),
    val agentTasks: List<JsonElement> = emptyList(),
)

@Serializable
data class SessionCwd(
    val cwd: String = "",
    @Serializable(with = EpochMsSerializer::class) val updatedAt: Long = 0L,
)

/**
 * V2 任务(`sessionState.ts:11-21` 的 `V2TaskItemWire`)。
 * `status` 取值:`pending` / `in_progress` / `completed`(服务端 schema 放开为 string)。
 */
@Serializable
data class V2Task(
    val id: String,
    val subject: String = "",
    val description: String? = null,
    val activeForm: String? = null,
    val status: String = "pending",
    val blocks: List<String> = emptyList(),
    val blockedBy: List<String> = emptyList(),
    val owner: String? = null,
    @Serializable(with = EpochMsSerializer::class) val updatedAt: Long = 0L,
)

// ===== 队列 / prompt =====

@Serializable
data class QueuedPrompt(val id: String, val text: String = "")

/**
 * 要随 prompt 发出去的图片块（`contentBlocks` 的元素）。
 *
 * 和 [AttachedImage] 分开是为了**可测性**：`AttachedImage` 持有
 * `android.net.Uri`，在 JVM 单测里是个 stub（一碰就抛 `Stub!`）。这里只留
 * 真正上线要序列化的两个字段，单测直接构造即可。
 */
data class PromptImage(val mediaType: String, val base64: String)

@Serializable
data class PromptResponse(
    val sessionId: String? = null,
    val queued: Boolean = false,
    val queueLength: Int = 0,
    val pending: List<QueuedPrompt> = emptyList(),
)

@Serializable
data class AbortResponse(val ok: Boolean = false, val sessionId: String? = null, val aborted: Boolean = false)

/**
 * 动作类端点的统一响应壳。服务端不同端点回不同字段名:
 *   - `{ok:true}` / `{ok:false, error:'...'}` — abort / queue edit·steer / answer /
 *     permission-response / approve
 *   - `{removed:true|false}` — queue/cancel 的专用字段
 * 所以两个都接住,用 [failed] 统一判断成功与否(缺字段一律视为成功,只有显式
 * `false` 才算业务失败 —— 例如 `queue-item-not-found`)。
 */
@Serializable
data class ActionResult(
    val ok: Boolean? = null,
    val removed: Boolean? = null,
    val error: String? = null,
) {
    val failed: Boolean get() = ok == false || removed == false || !error.isNullOrBlank()
}

// ===== ask / permission / approve =====

@Serializable
data class AskOption(val label: String, val description: String? = null)

@Serializable
data class AskQuestion(
    val question: String = "",
    val header: String = "",
    val options: List<AskOption> = emptyList(),
)

/**
 * 会话内一个待处理的交互请求。三种来源共用这个壳,用 [kind] 区分渲染:
 *   - `ask`        → `prompt.ask`(`POST /api/agent/answer`)
 *   - `permission` → `prompt.permission`(`POST /api/agent/permission-response`)
 *   - `approve`    → `prompt.approve`(`POST /api/agent/approve`)
 */
@Serializable
data class PendingInteraction(
    val kind: String,
    val toolUseId: String,
    // ask
    val questions: List<AskQuestion> = emptyList(),
    // permission
    val toolName: String? = null,
    val description: String? = null,
    val input: JsonElement? = null,
    val message: String? = null,
    // approve
    val title: String? = null,
    val summary: String? = null,
    val filePath: String? = null,
)

@Serializable
data class ApproveFileResponse(
    val toolUseId: String? = null,
    val filePath: String? = null,
    val content: String = "",
    val bytes: Long = 0L,
)

// ===== SSE =====

/**
 * 一条解析好的 SSE 帧。
 *
 * 服务端 `writeSse` 写三行:`id: <seq>` / `event: <type>` / `data: <整事件 JSON>`。
 * 心跳是**注释行** `: heartbeat`,解析时整行丢弃。
 *
 * `data` 里的 JSON 自带 `type` 字段(与 `event:` 同值),所以 [type] 优先取
 * `event:` 行、回退到 payload 的 `type`。
 *
 * 事件类型有 40+ 种,且新增类型不该让老客户端崩 —— 所以不建 sealed 继承,
 * 统一用 [JsonObject] + 具名取值器按需读字段。
 */
class AgentEvent(
    val id: String?,
    val type: String,
    val payload: JsonObject,
) {
    val seq: Long = payload.long("seq") ?: id?.toLongOrNull() ?: 0L
    val sessionId: String? = payload.str("sessionId")

    fun str(key: String): String? = payload.str(key)
    fun long(key: String): Long? = payload.long(key)
    fun int(key: String): Int? = long(key)?.toInt()
    fun bool(key: String): Boolean? = payload.bool(key)
    fun obj(key: String): JsonObject? = payload[key] as? JsonObject
    fun arr(key: String): JsonArray? = payload[key] as? JsonArray
}

// ===== JsonObject 取值 helper =====

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long? = this[key].toEpochMs()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** 把任意 JsonElement 转成给人看的字符串(工具入参 / 返回值渲染用)。 */
fun JsonElement.pretty(): String = when (this) {
    is JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}

/**
 * 时间戳字段的形态折算:
 *   - 整数(`1788658221846`)→ 直接当 epoch 毫秒
 *   - **浮点**(`1789274126878.9248`)→ 取整当 epoch 毫秒。Node 的
 *     `fs.Stats.mtimeMs` 就是这种带小数的 double,漏掉这条分支会让整份
 *     sessions 列表反序列化失败(见 [EpochMsSerializer] 的说明)
 *   - 字符串(`"2026-09-06T04:06:35.048Z"`)→ ISO-8601 解析
 * 其它(对象/数组/null)→ null。失败不抛,调用方拿到 null 就按「无时间」渲染。
 */
fun JsonElement?.toEpochMs(): Long? {
    if (this == null || this is JsonNull) return null
    val prim = this as? JsonPrimitive ?: return null
    val raw = prim.contentOrNull ?: return null
    raw.toLongOrNull()?.let { return it }
    prim.doubleOrNull?.let { return it.toLong() }
    val normalized = if (raw.endsWith("Z")) raw else "${raw}Z"
    return runCatching { java.time.Instant.parse(normalized).toEpochMilli() }.getOrNull()
}

/**
 * 展示用截断。手机屏幕上没人会读完 200KB 的工具输出,而把它整段塞进
 * Compose 的 Text 会真的卡住渲染(单 Text 长文本布局是 O(n))。所以入库时
 * 就截断,并在尾部标出原始长度,让用户知道这里被省略了。
 */
fun String.capForDisplay(max: Int = DISPLAY_TEXT_CAP, label: String = "输出"): String =
    if (length <= max) this else "${take(max)}\n\n…（$label 已截断，原始 ${length} 字符）"

/** 工具输出的展示上限。输入一般小得多,单独给一个更紧的档。 */
const val DISPLAY_TEXT_CAP = 20_000
const val DISPLAY_INPUT_CAP = 6_000

/** 工具返回值可能是 `{content: [...]}` / `[{type:text,text}]` / 裸字符串,统一抽成文本。 */
fun JsonElement.toolResultText(): String {
    if (this is JsonNull) return ""
    if (this is JsonPrimitive) return contentOrNull.orEmpty()
    if (this is JsonArray) {
        val parts = mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull.orEmpty()
                is JsonObject -> el.str("text") ?: el.pretty()
                else -> el.pretty()
            }
        }
        return parts.joinToString("\n")
    }
    val obj = this as? JsonObject ?: return pretty()
    // 常见形态: { content: "..." } / { content: [...] } / { stdout: "...", stderr: "..." }
    obj["content"]?.let { c ->
        if (c !is JsonNull) return c.toolResultText()
    }
    val stdout = obj.str("stdout")
    val stderr = obj.str("stderr")
    if (stdout != null || stderr != null) {
        return listOfNotNull(
            stdout?.takeIf { it.isNotBlank() },
            stderr?.takeIf { it.isNotBlank() },
        ).joinToString("\n")
    }
    return pretty()
}

// ===== 模型清单 / 模型切换 =====

/**
 * 单条模型配置。字段名对齐 opencc-web `packages/zai/src/shared/settings.ts`
 * 的 `ModelEntry` 接口(providerId / model / alias / label / description),
 * 缺的字段(`baseUrl` / `capabilities`)本端暂用不上,按需扩展。
 *
 * 同一 `model` 名出现在多个 provider profile 时,**`(providerId, model)` 元组**
 * 才是 picker 的真正唯一键(`ModelEntry.providerId` 文档),picker 渲染
 * 「当前」标记要按元组判等,不能光比 `model`。
 *
 * 来源:服务端 `GET /api/agent/settings` 响应的 `models: ModelEntry[]` 字段,
 * 见 [AgentApi.listAvailableModels]。
 */
@Serializable
data class ModelEntry(
    val model: String,
    val alias: String = model,
    val label: String? = null,
    val description: String? = null,
    val providerId: String? = null,
)

/**
 * `GET /api/agent/settings` 的响应投影。本端目前只关心 `models` 列表(给
 * model picker 渲染下拉),其余字段(settings.outputStyle / maxVisibleMessages
 * 等)由 web 端负责持久化,手机端不消费。
 */
@Serializable
data class AgentSettingsResponse(
    val models: List<ModelEntry> = emptyList(),
)

/**
 * PATCH /api/agent/sessions/:id 的请求体投影。当前只用来切 model,服务端
 * 还支持改 `title` / `cwd` / `providerId` / `permissionMode`,这里只暴露
 * 模型相关字段,需要再扩。
 *
 * `providerId` 留空 = 不动服务端已记录的 providerId(避免「只换 model」
 * 把 providerId 误清成 null,见 web 端 patchSessionModel 注释
 * `useAgentStore.ts:1280-1288`)。
 */
@Serializable
data class PatchSessionRequest(
    val model: String,
    val providerId: String? = null,
)

/**
 * 渲染 picker 时的「当前选中」key —— 与 `entryTupleKey` 同形,
 * 但用 Kotlin 数据类表达,UI 层直接 `data class Equality` 即可。
 */
fun ModelEntry.tupleKey(): String = "${this.providerId ?: ""}::${this.model}"
