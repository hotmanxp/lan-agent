// data/SlashCommands.kt — 「/命令 + Skill」面板的数据层(对齐 opencc-web)。
//
// 两条端点(见 data/AgentApi.kt 的端点表):
//   GET  /api/slash           → { items: SlashItem[] }  命令 + skill 合并清单
//   POST /api/agent/command   → { type, payload }       执行 / 展开一条命令
//
// **为什么「展开」放服务端**:本地命令(/clear /compact /status)要在服务端动
// transcript;prompt 类命令(/commit 等)和 skill 要把 SKILL.md 的 markdown
// 渲染成真正发给模型的 prompt(替换 `$ARGUMENTS` / `$1` / 追加 `ARGUMENTS: xxx`)。
// 手机端只负责把 `/name args` 拆出来发过去、再把回来的东西分流显示 —— 自己拼
// 模板的话,同一份 skill 会在 web 和 App 上渲染出两套文本,以后改模板要改两处。
//
// 本文件的 fuzzy 过滤与输入解析都是**纯函数**(不依赖 Android / Compose),
// 单测钉住「`/Users/ethan/x` 不能被当成命令」这类边界。
//
// 端点实现:opencc-web `packages/zai/src/server/routes/slash.ts`(列表)、
// `routes/command.ts`(执行)、`services/commands/slashList.ts`(清单形状)。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ===== wire 模型 =====

/**
 * `GET /api/slash` 的一条候选。字段与 opencc-web `SlashItem` 同名同形
 * (camelCase,直接映射,不需要 `@SerialName`)。
 *
 * 两种 [kind]:
 *   - `command` — 注册表里的命令(builtin / user / plugin),带 [type];
 *   - `skill`   — `~/.agents/skills` 等目录里的 SKILL.md(见服务端 `listSkills`)。
 *     没有 [type]:它永远走「渲染成 prompt」那条路(服务端 command 路由的
 *     skill fallthrough,见 `routes/command.ts:89`)。
 */
@Serializable
data class SlashItem(
    val kind: String = KIND_COMMAND,
    /** 调用名(`/name`)。插件项是带前缀的全名(`superpowers:commit`)。 */
    val name: String,
    val description: String = "",
    /**
     * 参数占位提示。**服务端历史上只返字符串**(`"[--force]"`),
     * 但 opencc 项目专属命令(/release-opencc /sync-upperstream)会把
     * 多形态参数写成 JSON 数组(`["<version_type>"]`)—— 紧绑成 `String?`
     * 会让整条 item 反序列化失败,进而让 `SlashListResponse.items` 整次作废,
     * 兜成空列表后 `AgentInputBar` 看到 `slashItems.isEmpty()` 直接禁掉
     * 命令面板。所以这里收 `JsonElement?`,渲染前用 [argumentHintText]
     * 统一成字符串。
     */
    val argumentHint: JsonElement? = null,
    val whenToUse: String? = null,
    val isBuiltIn: Boolean? = null,
    val isConflict: Boolean? = null,
    /**
     * **仅 `kind == "command"` 有值**:
     *   - `local`  — 服务端本地执行,回结果让我们显示(/clear /compact /status);
     *   - `prompt` — 服务端渲染成一段 prompt,再当作普通消息发给模型。
     */
    val type: String? = null,
    /** 插件项去掉插件前缀后的名字 —— 面板左侧显示这个,调用仍用全名 [name]。 */
    val displayName: String? = null,
    val pluginName: String? = null,
) {
    /** 面板左侧展示名。 */
    val label: String get() = displayName ?: name

    val isSkill: Boolean get() = kind == KIND_SKILL

    /** 服务端本地命令(结果自己消费,不发模型)。 */
    val isLocal: Boolean get() = type == COMMAND_LOCAL

    /** 需要用户补参数(`[--force]` 这种)—— 选中时先补全 `/name ` 而不是直接跑。 */
    val takesArgs: Boolean get() = argumentHintText().isNotEmpty()

    /**
     * 三态渲染:字符串原样;数组 → 用 `, ` 拼回形如 `[a, b, c]` 的展示串;
     * 其它(null / 数字 / 嵌套对象)→ 空串。**只用于面板右侧的提示 chip**,
     * 真正的执行路径走服务端 `command.ts` 自己解 argumentHint,这里只管显示。
     */
    fun argumentHintText(): String = when (val h = argumentHint) {
        null -> ""
        is JsonPrimitive -> h.content
        is JsonArray -> h.joinToString(", ") { it.toString() }
        else -> ""
    }

    companion object {
        const val KIND_COMMAND = "command"
        const val KIND_SKILL = "skill"
        const val COMMAND_LOCAL = "local"
        const val COMMAND_PROMPT = "prompt"
    }
}

@Serializable
data class SlashListResponse(val items: List<SlashItem> = emptyList())

/**
 * `POST /api/agent/command` 的响应。`payload` 的形状随 [type] 变(见
 * `routes/command.ts` 的 `CommandDoneResult`),所以这里是裸 [JsonElement],
 * 由下面的取值帮手按 type 拆 —— 跟项目里其它 wire 处理一个路子(不建 sealed,
 * 新增 kind 不该让老客户端反序列化失败)。
 */
@Serializable
data class CommandRunResponse(
    val type: String,
    val payload: JsonElement? = null,
)

/** `command` 响应的 type 取值(与服务端 `CommandDoneResult` 严格对齐)。 */
const val CMD_TYPE_CLEARED = "cleared"
const val CMD_TYPE_COMPACTED = "compacted"
const val CMD_TYPE_STATUS = "status"
const val CMD_TYPE_MESSAGE = "message"
const val CMD_TYPE_PROMPT = "prompt"
const val CMD_TYPE_ERROR = "error"
const val CMD_TYPE_UNKNOWN = "unknown"

/** 面板一次最多列多少条(服务端 100+ 条 skill,全渲染没必要)。 */
const val SLASH_MAX_ITEMS = 30

/**
 * `type=prompt` 的渲染结果 —— **真正要发给模型的那段文本**。
 * 调用方拿它走 `sendPrompt`,展示层则用用户敲的原始 `/name args`
 * (对齐 web 端 `submitPrompt(rendered, { commandText })`)。
 */
fun CommandRunResponse.renderedPrompt(): String? =
    if (type != CMD_TYPE_PROMPT) null else (payload as? JsonObject)?.str("rendered")

/** `type=message` 的文本(/handoff 恢复路径等)。 */
fun CommandRunResponse.messageText(): String? =
    if (type != CMD_TYPE_MESSAGE) null else (payload as? JsonObject)?.str("text")

/** `type=error` 的错误说明。 */
fun CommandRunResponse.errorText(): String? =
    if (type != CMD_TYPE_ERROR) null else (payload as? JsonObject)?.str("message")

/** `type=unknown` 里回显的未知命令文本(如 `/nope`)。 */
fun CommandRunResponse.unknownInput(): String? =
    if (type != CMD_TYPE_UNKNOWN) null else (payload as? JsonObject)?.str("input")

/** `type=compacted` 压掉了多少条消息 + 摘要。 */
fun CommandRunResponse.compactedInfo(): Pair<Int, String?>? {
    if (type != CMD_TYPE_COMPACTED) return null
    val obj = payload as? JsonObject ?: return null
    val removed = obj.long("removedMessages")?.toInt() ?: 0
    return removed to obj.str("summary")
}

/**
 * `type=status` 的 payload → 多行纯文本(对齐 web 端 status 面板的字段)。
 * 服务端 `builtin/status.ts` 填的是 sessionId / cwd / cwdName / branch /
 * model / version;`branch` 服务端恒为空串(由前端补),所以这里不显示它。
 */
fun CommandRunResponse.statusText(): String? {
    if (type != CMD_TYPE_STATUS) return null
    val obj = payload as? JsonObject ?: return null
    return buildList {
        obj.str("sessionId")?.takeIf { it.isNotBlank() }?.let { add("会话  ${it.take(8)}") }
        obj.str("cwd")?.takeIf { it.isNotBlank() }?.let { add("目录  $it") }
        obj.str("model")?.takeIf { it.isNotBlank() }?.let { add("模型  $it") }
        obj.str("version")?.takeIf { it.isNotBlank() }?.let { add("版本  $it") }
    }.joinToString("\n")
}

// ===== 输入解析 / 过滤(纯函数)=====

/** 拆好的命令输入:名字(不含 `/`) + 原始参数串 + 用户敲的原文。 */
data class SlashInput(
    val name: String,
    val args: String,
    /** 原文(含 `/`),用于本地乐观展示与「原文转发」兜底。 */
    val raw: String,
)

/**
 * 命令名的合法字符集 —— 与服务端调用约定一致(字母数字 + `:` `_` `-`)。
 *
 * 关键是**不含 `/`**:`/Users/ethan/proj` 这种路径首 token 是 `Users/ethan/proj`,
 * 含 `/` 直接判不合法 → 不当命令(照常当消息发出去)。web 端同一道闸
 * (`AgentInputBox.tsx:1314` 的 `^[A-Za-z0-9:_-]+$`)。
 */
private val COMMAND_NAME_RE = Regex("^[A-Za-z0-9:_-]+$")

/**
 * 把输入解析成命令调用。**不是命令就返回 null**(调用方照常当普通消息发)。
 *
 * 判定:整体以 `/` 开头(允许前导空白),首 token 匹配 [COMMAND_NAME_RE]。
 * 参数取首 token 之后的全部内容(允许换行)。
 */
fun parseSlashInput(text: String): SlashInput? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return null
    val body = trimmed.substring(1)
    if (body.isEmpty()) return null
    val firstLine = body.substringBefore('\n')
    val space = firstLine.indexOfFirst { it == ' ' || it == '\t' }
    val name = if (space < 0) firstLine else firstLine.substring(0, space)
    if (!COMMAND_NAME_RE.matches(name)) return null
    val args = if (space < 0) "" else body.substring(space + 1).trim()
    return SlashInput(name = name, args = args, raw = trimmed)
}

/**
 * 面板过滤/排序。返回条数上限 [limit]。
 *
 * **打分与分段规则逐行对齐 web 端 `AgentInputBox.filteredSlash`
 * (packages/zai/src/web/src/components/AgentInputBox.tsx:617-657)**,
 * 只有空查询那一支是本端改的(见下):
 *
 *   - 命中判定:名字(或插件项的 [SlashItem.displayName])或插件名必须有分,
 *     **两者都为 0 的直接丢掉**。所以「只在描述里出现这个词」捞不出来 ——
 *     打 `comp` 不会因为某个 skill 描述里写了 compact 就混进候选(web 就是这
 *     条,别自作聪明把描述命中当准入条件)。
 *   - 分数 = 名字分 + 插件名分×1.5 + 描述分×0.3(描述只是加权,不是准入)。
 *   - **先分段再排序**:命令整段排在 skill 整段之前,段内各自按分数降序。
 *     所以哪怕某个 skill 分数高过 `/status`,它也只能待在命令后面 —— 这条
 *     决定了回车选中谁,不能改成全局按分数混排。
 *   - 空查询(刚敲下 `/`):命令按名字升序、skill 按名字升序,两段相接。
 *     **本端偏离**:web 这里只列 `isBuiltIn` 的命令(它另有一个「+ 按钮」弹层
 *     兜底浏览全部命令),本端没有第二个入口,所以命令全列,否则用户自定义的
 *     `/commit` 之类在面板里根本翻不到。
 */
fun filterSlashItems(
    items: List<SlashItem>,
    query: String,
    limit: Int = SLASH_MAX_ITEMS,
): List<SlashItem> {
    val q = query.trim()
    if (q.isEmpty()) {
        val cmds = items.filter { !it.isSkill }.sortedBy { it.name }
        val sks = items.filter { it.isSkill }.sortedBy { it.name }
        return (cmds + sks).take(limit)
    }

    val lower = q.lowercase()

    fun scoreOf(item: SlashItem): Int {
        val nameScore = maxOf(
            fuzzyScore(lower, item.name),
            // 插件项左侧显示的是去前缀名,匹配它,这样敲可见的 `commit`
            // 也能命中 `superpowers:commit`。
            item.displayName?.let { fuzzyScore(lower, it) } ?: 0,
        )
        // 与 web 一致:Fuse 里插件名是显式加权的搜索字段(权重 1.5)。
        val pluginScore = item.pluginName?.let { fuzzyScore(lower, it) * 3 / 2 } ?: 0
        if (nameScore == 0 && pluginScore == 0) return 0
        val descScore = fuzzyScore(lower, item.description)
        return nameScore + pluginScore + if (descScore > 0) descScore * 3 / 10 else 0
    }

    fun rank(pool: List<SlashItem>): List<SlashItem> = pool
        .map { it to scoreOf(it) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }   // 稳定排序 → 同分保持服务端原序
        .map { it.first }

    return (rank(items.filter { !it.isSkill }) + rank(items.filter { it.isSkill })).take(limit)
}

/**
 * 子序列模糊打分(web 端 `fuzzyMatch` 的同款,**逐行对齐**)。
 *
 * 返回 0 表示「没命中」—— `query` 为空时也返回 0,调用方靠 [filterSlashItems]
 * 的空查询分支处理,不要把这个 0 当成「弱命中」。
 *
 * 加权:每命中一个字符至少 +1;与上一个命中**相邻** +10(连续命中最高,
 * 所以 `cmt` 命中 `commit` 强于 `c_o_m_t`);相隔越远衰减越快。
 */
internal fun fuzzyScore(query: String, target: String): Int {
    val q = query.lowercase()
    if (q.isEmpty()) return 0
    val t = target.lowercase()
    var qi = 0
    var score = 0
    var lastMatchIdx = -1
    for (ti in t.indices) {
        if (qi >= q.length) break
        if (t[ti] != q[qi]) continue
        val gap = if (lastMatchIdx >= 0) ti - lastMatchIdx - 1 else ti
        score += if (gap == 0) 10 else maxOf(1, 10 - gap)
        lastMatchIdx = ti
        qi++
    }
    return if (qi == q.length) score else 0
}
