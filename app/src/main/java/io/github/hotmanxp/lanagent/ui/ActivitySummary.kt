// ui/ActivitySummary.kt — 「一段工作」压成一行人话(0.26.3)
//
// 对齐 Trae 移动端 Agent 对话的折叠行:工具调用不再铺卡片,会话流里只留
// `执行 2 条命令 ›` / `搜索 6 次,读取 2 个文件 ›`,点开才看详情(底部弹层)。
//
// 纯 Kotlin(不 import Compose)—— 与 `buildAgentBlocks` 同一约定:派生逻辑
// 留在渲染层之外,便于推理与单测。中文硬编码而非走 strings.xml,跟被替换掉的
// 「工具调用 · N 次」/「思考过程」/「入参」保持同一惯例(见 §18 会话精简模式)。
package io.github.hotmanxp.lanagent.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 详情弹层与摘要行共用的标题。 */
internal const val THINKING_LABEL = "思考过程"

/**
 * 一个动作类别 —— 摘要行里 `动词 + 次数 + 量词` 的模板。
 *
 * [order] 决定**同一行里多个类别的先后**(不是首次出现顺序):编码会话里
 * 「先读后写」比「先写后读」更符合叙述,固定成 执行 → 搜索 → 读取 → 查找 →
 * 写入 → 编辑 → 启动 → 待办 → 检索,摘要读起来才顺。
 *
 * [toolName] 只在**认不出的工具**上非空 —— 那时唯一能让用户看懂这行在说什么的
 * 线索就是工具名,得原样带出来。认得出的类别不带名(「执行 2 条命令」比
 * 「执行 2 次 Bash」干净)。
 */
internal data class ActivityStat(
    val verb: String,
    val unit: String,
    val order: Int,
    val count: Int,
    val toolName: String? = null,
) {
    /**
     * 渲染成一行摘要里的一个分句。
     *
     * 数字两侧**留空格**(`执行 2 条命令`):中文里阿拉伯数字夹在汉字中间不加
     * 空格会挤成一团,现代排版惯例与 Trae 那边都是加的。
     */
    fun render(): String =
        if (toolName == null) "$verb $count $unit" else "$verb $toolName $count $unit"

    /** 详情弹层里那一行的「已做什么」前缀(已完成态)。 */
    fun pastTense(): String = "已" + (if (toolName == null) verb else "$verb $toolName")
}

private object Order {
    const val EXEC = 0
    const val SEARCH = 1
    const val READ = 2
    const val FIND = 3
    const val WRITE = 4
    const val EDIT = 5
    const val SPAWN = 6
    const val TODO = 7
    const val WEB = 8
    const val UNKNOWN = 9
}

/** 认不出的工具归到这一档。 */
private val UNKNOWN = ActivityStat("调用", "次", Order.UNKNOWN, 0)

/**
 * 工具名 → 动作类别。
 *
 * 名字**大小写敏感**但认得两种形态:opencc 内核的驼峰(`Bash` / `MultiEdit`)
 * 与 Claude Code 风格的蛇 kebab(`run_shell_command` / `read_file`)。对不上
 * 就走 [UNKNOWN] —— 摘要退化但**不丢信息**(带工具名),所以这里漏一条新工具名
 * 只会让文案糙一点,不会把这次调用从摘要里抹掉。
 */
private val ACTIONS: Map<String, ActivityStat> = run {
    val m = HashMap<String, ActivityStat>()
    fun reg(vararg names: String, verb: String, unit: String, order: Int) {
        val a = ActivityStat(verb, unit, order, 0)
        names.forEach { m[it] = a }
    }
    reg(
        "Bash", "Shell", "shell", "run_shell", "run_shell_command", "run_command",
        "ExecuteBash", "execute_command", "bash",
        verb = "执行", unit = "条命令", order = Order.EXEC,
    )
    reg(
        "Grep", "grep", "search", "search_files", "Search", "ripgrep", "codebase_search",
        verb = "搜索", unit = "次", order = Order.SEARCH,
    )
    reg(
        "Read", "read", "read_file", "ReadFile", "view",
        verb = "读取", unit = "个文件", order = Order.READ,
    )
    reg(
        "Glob", "glob", "find", "find_files", "list_dir", "list_files", "LS",
        verb = "查找", unit = "个文件", order = Order.FIND,
    )
    reg(
        "Write", "write", "write_file", "WriteFile", "create_file",
        verb = "写入", unit = "个文件", order = Order.WRITE,
    )
    reg(
        "Edit", "edit", "MultiEdit", "multiedit", "str_replace", "NotebookEdit",
        "notebook_edit", "apply_patch", "replace",
        verb = "编辑", unit = "个文件", order = Order.EDIT,
    )
    reg(
        "Task", "task", "Agent", "agent", "CliAgent", "cli_agent", "Workflow", "workflow", "subagent",
        verb = "启动", unit = "个任务", order = Order.SPAWN,
    )
    reg(
        "TodoWrite", "todo_write", "TaskCreate", "TaskUpdate", "update_plan", "ExitPlanMode",
        verb = "更新", unit = "项待办", order = Order.TODO,
    )
    reg(
        "WebFetch", "web_fetch", "WebSearch", "web_search", "fetch", "Fetch", "browser",
        verb = "检索", unit = "次", order = Order.WEB,
    )
    m
}

/** 摘要行里出现未登记的工具名 —— 详情行要用它,这里留个查询口。 */
internal fun activityStatFor(toolName: String, count: Int = 1): ActivityStat =
    ACTIONS[toolName]?.copy(count = count)
        ?: ActivityStat(UNKNOWN.verb, UNKNOWN.unit, Order.UNKNOWN, count, toolName = toolName)

/**
 * 一段工作的摘要行文案。
 *
 * 聚合规则:同类合并计数([ActivityStat.order] 定先后),不同名但同类的工具
 * (`Bash` 与 `run_shell_command`)合成一句,不按首次出现顺序散成三四个分句。
 *
 * 纯思考段没有工具可数,回落成 [THINKING_LABEL](与旧的思考卡同名同义)。
 */
internal fun activityStats(members: List<AgentItem>): List<ActivityStat> {
    val tools = members.filterIsInstance<AgentItem.ToolCall>()
    if (tools.isEmpty()) return emptyList()

    // 未知工具**按名字分开**:两个不同的未登记工具都归 UNKNOWN 时若合成一句
    // 「调用 5 次」就等于什么也没说。认得出的类别按 order 归堆。
    val grouped = LinkedHashMap<String, ActivityStat>()
    for (t in tools) {
        val base = ACTIONS[t.name]
        val key = if (base == null) "name:${t.name}" else "cat:${base.order}"
        val prev = grouped[key]
        grouped[key] = base?.copy(count = (prev?.count ?: 0) + 1)
            ?: ActivityStat(UNKNOWN.verb, UNKNOWN.unit, Order.UNKNOWN, (prev?.count ?: 0) + 1, t.name)
    }
    return grouped.values.sortedBy { it.order }
}

/** 一行摘要文案,分句用「,」串(对齐 Trae 的「搜索 6 次,读取 2 个文件」)。 */
internal fun activitySummaryText(members: List<AgentItem>): String {
    val stats = activityStats(members)
    if (stats.isEmpty()) return THINKING_LABEL
    return stats.joinToString(",") { it.render() }
}

private val probeJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 从入参里挑「这次调用在干什么」的那一个字段。认不出的键就靠入参首行兜底。 */
private val SUBJECT_KEYS = listOf(
    "command", "file_path", "notebook_path", "path", "pattern", "query", "url", "prompt", "description",
)

/**
 * 截断过的 JSON 里捞 `"key":"value"`。
 *
 * **这条不是边角路径,是写文件类工具的常态** —— `capForDisplay` 在 6000 字符
 * 处砍断入参,`Write` / `Edit` 的 `content` 一进去就超,于是它们的入参**必然**
 * 是非法 JSON,`parseToJsonElement` 必然失败。不捞这一把,标题就变成
 * `已写入 {"file_path":"/a.ts","content":"很长的内容…` —— 把原始 JSON 糊在
 * 折叠头上,比显示工具名还糟。
 */
private val LOOSE_FIELD = Regex("\"(${SUBJECT_KEYS.joinToString("|")})\"\\s*:\\s*\"([^\"\\\\]{0,200})")

/**
 * 详情弹层里那一行的主语:命令首行 / 文件名 / 搜索词。
 *
 * 入参是**给人看的文本**(见 [AgentItem.ToolCall.input]:对象型工具存的是
 * `pretty()` 后的 JSON 字符串,标量型工具(Bash 的 command)存的是裸文本),
 * 且早被 [io.github.hotmanxp.lanagent.data.capForDisplay] 截断过。所以这里
 * **不解析成对象用**,只做「取前几百字符里的第一个有意义片段」——
 * 截断成非法 JSON 的入参也能给出可读的一行。
 */
internal fun toolSubject(item: AgentItem.ToolCall, maxLen: Int = 72): String {
    val raw = item.input?.trim().orEmpty()
    if (raw.isEmpty()) return item.name

    // 入参是 JSON 对象时优先取语义字段;截断过的 JSON 走正则捞前缀;
    // 两条都捞不到才退到入参首行。
    subjectFromJson(raw, maxLen)?.let { return it }
    return firstLine(raw, maxLen)
}

private fun subjectFromJson(raw: String, maxLen: Int): String? {
    val parsed = runCatching {
        val obj = probeJson.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        SUBJECT_KEYS.firstNotNullOfOrNull { k ->
            (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
    if (parsed != null) return firstLine(parsed, maxLen)

    val loose = LOOSE_FIELD.find(raw)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
    return loose?.let { firstLine(it, maxLen) }
}

/** 单行化 + 限长。工具输出 / 多行命令在摘要行里必须收成一行,否则行高会跳。 */
private fun firstLine(s: String, maxLen: Int): String {
    val line = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (line.length <= maxLen) return line
    return line.take(maxLen - 1).trimEnd() + "…"
}
