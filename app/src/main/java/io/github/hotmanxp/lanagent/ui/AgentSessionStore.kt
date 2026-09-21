// ui/AgentSessionStore.kt — 会话详情页的状态机。
//
// 两件事:
//   1. **hydrate** — 把 `GET /api/agent/sessions/:id` 的原始 JSONL 消息归一化成
//      可渲染的 [AgentItem] 列表(对齐 web 端 useAgentStore.loadTranscriptMessages,
//      见 useAgentStore.ts:435-564)。
//   2. **apply** — 把 `GET /api/event?sid=` 的 SSE 事件 reduce 成
//      items / status / queue / v2Tasks / bgAgentTasks / bgBashTasks / pending
//      (对齐 useAgentStore.ts:1492-1851 的 applyRuntimeEvent)。
//
// 后台任务(agent 子代理 / 后台 bash)单独成列,不混进 [items]:它们由主会话
// 之外的东西驱动(lifecycle 事件 + state 快照),渲染位置也不同(底部任务栏,
// 见 `ui/AgentSessionViews.kt` 的 TaskDockStrip)。见 `data/BackgroundTasks.kt`。
//
// 为什么不直接渲染原始 transcript:JSONL 一行是 Anthropic 原生信封
// (`{type, message:{role, content: ContentBlock[]}}`),一条 assistant 消息里
// 混着 thinking / text / tool_use,而 tool_result 又藏在**下一条 user 消息**里。
// 不归一化的话渲染层要写一堆跨条目的状态拼装。
//
// 状态用 Compose 的 `mutableStateOf` / `mutableStateListOf` 直接持有 ——
// 项目约定不引 ViewModel / Hilt(见 AGENTS.md「非目标」),屏内 `remember`
// 一个实例即可。
package io.github.hotmanxp.lanagent.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.hotmanxp.lanagent.data.AgentEvent
import io.github.hotmanxp.lanagent.data.AskQuestion
import io.github.hotmanxp.lanagent.data.BG_RECENT_TTL_MS
import io.github.hotmanxp.lanagent.data.BgAgentTask
import io.github.hotmanxp.lanagent.data.BgBashTask
import io.github.hotmanxp.lanagent.data.DISPLAY_FILES_TOOL
import io.github.hotmanxp.lanagent.data.DisplayFile
import io.github.hotmanxp.lanagent.data.DisplayFilesCache
import io.github.hotmanxp.lanagent.data.PendingInteraction
import io.github.hotmanxp.lanagent.data.bgTerminal
import io.github.hotmanxp.lanagent.data.mergeDisplayFiles
import io.github.hotmanxp.lanagent.data.parseDisplayFileMeta
import io.github.hotmanxp.lanagent.data.parseDisplayFilePaths
import io.github.hotmanxp.lanagent.data.QueuedPrompt
import io.github.hotmanxp.lanagent.data.SessionStateResponse
import io.github.hotmanxp.lanagent.data.Transcript
import io.github.hotmanxp.lanagent.data.TranscriptBody
import io.github.hotmanxp.lanagent.data.V2Task
import io.github.hotmanxp.lanagent.data.capForDisplay
import io.github.hotmanxp.lanagent.data.DISPLAY_INPUT_CAP
import io.github.hotmanxp.lanagent.data.pretty
import io.github.hotmanxp.lanagent.data.str
import io.github.hotmanxp.lanagent.data.toContentBlocks
import io.github.hotmanxp.lanagent.data.toolResultText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** 会话的运行态。`streaming` / `retrying` 时输入框变「停止」按钮。 */
enum class AgentRunStatus { Idle, Streaming, Retrying, Aborted, Error }

/**
 * 渲染单元。`sealed interface` + 稳定 [key] —— LazyColumn 的 key 必须稳定,
 * 否则流式追加时整表重建,滚动位置会跳。
 */
sealed interface AgentItem {
    val key: String

    data class UserText(
        override val key: String,
        val text: String,
        val timestamp: Long?,
        /**
         * 图片附件数量(`image` block 的数量)。
         * 历史回放时没有 [attachmentUris](base64 没保留),UI 退化成「N 张图片」文字;
         * 本地乐观追加时**同时**填 [attachmentUris],UI 渲染方形缩略图。
         */
        val attachments: Int = 0,
        /**
         * 图片附件的本地 content:// URI —— 用来在气泡里直接渲染缩略图,
         * 不必从 base64 重新解码(发送时 base64 已丢,见 [appendLocalUser])。
         */
        val attachmentUris: List<Uri> = emptyList(),
    ) : AgentItem

    data class AssistantText(
        override val key: String,
        val text: String,
        val timestamp: Long?,
    ) : AgentItem

    data class Thinking(
        override val key: String,
        val text: String,
        val timestamp: Long?,
    ) : AgentItem

    /**
     * 工具调用。`output == null && !isError` 视为还在跑(转圈),
     * 有 output 或 isError 即终态。key 固定按 `toolUseId` —— 同一次调用
     * 可能在 `assistant` 消息块和独立的 `tool_use` 行里各出现一次,靠 key
     * 去重(web 端同款做法:`tool-${toolUseId}`)。
     */
    data class ToolCall(
        override val key: String,
        val toolUseId: String,
        val name: String,
        val input: String?,
        val output: String?,
        val isError: Boolean,
        val timestamp: Long?,
        /**
         * `DisplayFiles` 工具展示的文件列表,见 `data/DisplayFiles.kt`。
         * 非空时 [ToolCallCard] 渲染成文件卡片(而不是入参/输出两段 code)。
         *
         * 其余工具恒为空。两条来源合并而成:**tool_use 的 `input.paths`**
         * (任何时态都有)+ **tool_result 的元数据**(仅直播态,重开会话时
         * transcript 里是字面量 `'done'`)。
         */
        val files: List<DisplayFile> = emptyList(),
    ) : AgentItem {
        val running: Boolean get() = output == null && !isError
    }

    /** 运行期提示(runtime.error / runtime.compacted)。`isError=false` 是中性的灰条。 */
    data class Note(
        override val key: String,
        val text: String,
        val timestamp: Long?,
        val isError: Boolean,
    ) : AgentItem
}

/**
 * **渲染块** —— 精简模式下把「一段连续的工作」折成一个 [ToolGroup]。
 *
 * 为什么不直接把分组塞进 [AgentItem]:分组是**渲染粒度**,不是数据。
 * store 依旧逐条持有 AgentItem(工具输出回流要按 `toolUseId` 原地更新),
 * 折叠只发生在列表这一层,所以关掉精简模式不需要重建任何数据。
 */
sealed interface AgentBlock {
    /** LazyColumn 的 key —— 必须稳定,否则流式追加时整表重建、滚动位置会跳。 */
    val key: String

    /** 单条渲染单元,下标指向 [AgentSessionStore.items]。 */
    data class Single(val index: Int, override val key: String) : AgentBlock

    /**
     * 一段工作的聚合(工具调用 + 其间的思考过程)。`indices` 指向 items 的
     * **下标而不是快照** —— 工具输出是原地替换(`applyToolResult`),存快照
     * 会渲染出过期内容。key 取首条成员的 key,所以段落继续增长时 key 不变,
     * 展开状态与滚动位置都稳。
     */
    data class ToolGroup(val indices: List<Int>, override val key: String) : AgentBlock
}

/**
 * items → 渲染块。**纯函数**(不 import 任何 Compose 类型),便于推理与复用。
 *
 * 规则:
 *   - `compact = true` 时,一段**连续的工作**(工具调用 + 夹在中间的思考过程)
 *     里只要有 **>= 2** 次工具调用,整段合成一个 [ToolGroup];
 *   - **思考过程不打断段落**(0.15.2 定稿):编码会话里最常见的形态是
 *     「Bash → 思考 → Bash → 思考」,若按严格连续分组,整屏还是单张工具卡,
 *     聚合形同没做。展开后思考卡按原顺序排在工具卡之间,内容一点没少;
 *   - 只有一条工具调用时保持 [Single] —— 它本来就是一张卡,再套一层聚合行
 *     只是让用户多点一次(要治的是截图里那种 7 连击);
 *   - `compact = false` 时全部 [Single],即改动前的逐条渲染;
 *   - 正文 / 用户消息 / 提示条会断开段落 —— 段落是「这一轮的一段工作」,
 *     助手开始说话或用户插话就该断。
 *
 * 调用方用 `remember(items.size, compact)` 缓存:items 只会 append,原地更新
 * 全是**同类替换**(见 `appendText` / `applyToolResult` / `upsertToolCall`),
 * 所以「下标 → 类型」的映射只在 size 变化时才会变。渲染侧再兜一层类型检查,
 * 任何情况下都不会把正文画进工具段。
 */
internal fun buildAgentBlocks(items: List<AgentItem>, compact: Boolean): List<AgentBlock> {
    val out = ArrayList<AgentBlock>(items.size)
    var i = 0
    while (i < items.size) {
        if (!items[i].isWork) {
            out.add(AgentBlock.Single(i, items[i].key))
            i++
            continue
        }
        var j = i
        var tools = 0
        while (j < items.size && items[j].isWork) {
            if (items[j] is AgentItem.ToolCall) tools++
            j++
        }
        if (compact && tools >= 2) {
            out.add(AgentBlock.ToolGroup((i until j).toList(), "tgroup-${items[i].key}"))
        } else {
            for (k in i until j) out.add(AgentBlock.Single(k, items[k].key))
        }
        i = j
    }
    return out
}

/** 段落成员:工具调用,以及夹在它们之间的思考过程。 */
private val AgentItem.isWork: Boolean
    get() = this is AgentItem.ToolCall || this is AgentItem.Thinking

class AgentSessionStore(val sessionId: String) {

    val items = mutableStateListOf<AgentItem>()

    var status by mutableStateOf(AgentRunStatus.Idle)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var cwd by mutableStateOf<String?>(null)
        private set
    var model by mutableStateOf<String?>(null)
        private set
    var queue by mutableStateOf<List<QueuedPrompt>>(emptyList())
        private set
    var pending by mutableStateOf<PendingInteraction?>(null)
        private set
    var hydrated by mutableStateOf(false)
        private set
    var hydrateError by mutableStateOf<String?>(null)
        private set
    /**
     * 「当前上下文 token 数」(0.15.1 引入,对应 opencc-web 会话信息面板的
     * 「上下文 / current」一行)。来自 SSE 三路:
     *   - `runtime.started.contextTokens`  — 每次 LLM 调用起点推一次
     *   - `runtime.done.contextTokens`     — 整轮 prompt 跑完推一次
     *   - `session/projection` key="context.tokens" — 新通路(host 算完的派生值)
     *
     * 三路等价,谁先到用谁。null = 还没推过(transcript 重放 / 早期 query),
     * UI 渲染为 "—"。
     */
    var contextTokens by mutableStateOf<Long?>(null)
        private set

    val v2Tasks = mutableStateListOf<V2Task>()

    /**
     * 后台 agent 子代理(Agent / CliAgent / BackgroundAgent 工具派出去的那些)。
     * 来源两路:`agent_task.changed` SSE + state 冷启动快照。
     *
     * 这是**主会话视角**的后台任务 —— 主 agent 早就把工具调用标成「完成」了,
     * 子代理还在跑,不单独展示的话用户在会话里完全看不到它。
     */
    val bgAgentTasks = mutableStateListOf<BgAgentTask>()

    /** 后台 bash 任务(`run_in_background` 那种)。来源同上。 */
    val bgBashTasks = mutableStateListOf<BgBashTask>()

    /**
     * 底部任务栏是否有内容(任务清单 或 后台任务)。
     *
     * 读的是 Compose 状态列表,组合期读取会被正常订阅 —— 调用方直接写
     * `if (store.hasDockContent)` 即可。
     */
    val hasDockContent: Boolean
        get() = v2Tasks.isNotEmpty() || bgAgentTasks.isNotEmpty() || bgBashTasks.isNotEmpty()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /**
     * 流式气泡的**下标**缓存。items 只会 append(会话内不删),所以下标稳定。
     * 用下标而不是每帧 `indexOfFirst { it.key == ... }`,避免长会话下
     * 每个 delta 都 O(n) 扫一遍列表。
     */
    private var curTextIdx = -1
    private var curThinkIdx = -1
    private var turnIndex = -1
    private var segCounter = 0

    // ===== hydrate =====

    fun hydrate(transcript: Transcript) {
        title = transcript.meta.title
        cwd = transcript.meta.cwd
        model = transcript.meta.model

        items.clear()
        curTextIdx = -1
        curThinkIdx = -1
        turnIndex = -1
        // 状态归零再让 SSE 重建 —— 断连期间这一轮可能已经结束,如果保留旧的
        // Streaming,replay 里又没有对应的 runtime.done,UI 会永远卡在「运行中」。
        // 归零后有两条自愈路径:replay 尾部的 runtime.done → Idle;
        // 或者下一帧 runtime.delta → noteStreaming() → Streaming。
        status = AgentRunStatus.Idle
        // 0.15.1:contextTokens 也是同一份「直播态」语义,hydrate 时归零
        // —— transcript 落盘不带这个数字,等 runtime.started 或
        // session/projection 重新推上来。
        contextTokens = null

        for (entry in transcript.messages) {
            // `isMeta=true` 是给 LLM 看的旁路内容(展开后的 slash 指令、inbox
            // 注入等),UI 必须隐藏 —— 与 web 端 useAgentStore.ts:454/483 一致。
            if (entry.isMeta) continue
            val content = entry.message?.content ?: continue
            when (entry.type) {
                "user" -> ingestUser(content, entry.tsMs, entry.uuid)
                "assistant" -> ingestAssistant(content, entry.tsMs, entry.uuid)
                "tool_use" -> ingestToolUseOnly(content, entry.tsMs)
                "tool_result" -> ingestToolResultOnly(content)
                // system / attachment / compact_boundary / custom-title /
                // session-meta 都不渲染(web 端同样跳过)。
                else -> Unit
            }
        }
        hydrated = true
        hydrateError = null
    }

    fun hydrateFailed(message: String) {
        hydrated = true
        hydrateError = message
    }

    fun hydrateState(state: SessionStateResponse) {
        state.cwd?.cwd?.takeIf { it.isNotBlank() }?.let { cwd = it }
        v2Tasks.clear()
        v2Tasks.addAll(state.v2Tasks)
        // 后台任务:state 快照是冷启动的唯一来源(bash 侧没有 SSE 合成重推)。
        // 空 id 的条目丢掉 —— 没有 id 就无法按 SSE 增量去重,留着只会重复。
        bgAgentTasks.clear()
        bgAgentTasks.addAll(state.agentTasks.filter { it.id.isNotBlank() })
        bgBashTasks.clear()
        bgBashTasks.addAll(state.bashTasks.filter { it.taskId.isNotBlank() })
        // 快照里带着历史终态任务,照样裁一遍,否则一打开会话就先堆一排「完成」。
        pruneBgTasks()
    }

    private fun ingestUser(content: JsonElement, ts: Long?, uuid: String?) {
        val blocks = TranscriptBody(content = content).toContentBlocks()
        if (blocks.isEmpty()) return

        var text = ""
        var images = 0
        for (b in blocks) {
            when (b.type) {
                "tool_result" -> applyToolResult(b.toolUseId, b.content, b.isError)
                "text" -> text += b.text.orEmpty()
                "image" -> images++
                else -> Unit
            }
        }
        if (text.isNotBlank() || images > 0) {
            items.add(
                AgentItem.UserText(
                    key = "u-${uuid ?: items.size}",
                    text = text.trim(),
                    timestamp = ts,
                    attachments = images,
                )
            )
        }
    }

    private fun ingestAssistant(content: JsonElement, ts: Long?, uuid: String?) {
        var n = 0
        for (b in TranscriptBody(content = content).toContentBlocks()) {
            when (b.type) {
                "thinking" -> b.thinking?.takeIf { it.isNotBlank() }?.let {
                    items.add(AgentItem.Thinking("t-${uuid ?: items.size}-${n++}", it, ts))
                }

                "text" -> b.text?.takeIf { it.isNotBlank() }?.let {
                    items.add(AgentItem.AssistantText("a-${uuid ?: items.size}-${n++}", it, ts))
                }

                "tool_use" -> upsertToolCall(
                    toolUseId = b.id.orEmpty(),
                    name = b.name.orEmpty().ifEmpty { "tool" },
                    input = b.input.cleanText(),
                    output = null,
                    isError = false,
                    ts = ts,
                    files = displayFilesFromInput(b.id, b.input),
                )

                else -> Unit
            }
        }
    }

    private fun ingestToolUseOnly(content: JsonElement, ts: Long?) {
        for (b in TranscriptBody(content = content).toContentBlocks()) {
            if (b.type != "tool_use") continue
            upsertToolCall(
                toolUseId = b.id.orEmpty(),
                name = b.name.orEmpty().ifEmpty { "tool" },
                input = b.input.cleanText(),
                output = null,
                isError = false,
                ts = ts,
                files = displayFilesFromInput(b.id, b.input),
            )
        }
    }

    private fun ingestToolResultOnly(content: JsonElement) {
        for (b in TranscriptBody(content = content).toContentBlocks()) {
            if (b.type == "tool_result") applyToolResult(b.toolUseId, b.content, b.isError)
        }
    }

    /**
     * 把 tool_result 贴回对应的工具卡。找不到对应 tool_use 就丢弃 —— 同 web
     * 端行为(`useAgentStore.ts:488-498` 只改已存在的卡)。正常 transcript 里
     * tool_use 一定先于 tool_result 出现,所以命中率是 100%。
     */
    private fun applyToolResult(toolUseId: String?, content: JsonElement?, isError: Boolean) {
        val key = toolKey(toolUseId.orEmpty()) ?: return
        val idx = items.indexOfFirst { it.key == key }
        if (idx < 0) return
        val cur = items[idx] as? AgentItem.ToolCall ?: return
        val text = content?.toolResultText().orEmpty().capForDisplay()
        // DisplayFiles 的结果是给前端渲染的文件元数据 JSON(不是给人读的
        // 文本),抽成文件列表交给文件卡片渲染。**重开历史会话时这里解析出
        // 空列表** —— transcript 里存的是字面量 'done'。三级来源,从严到宽:
        //   1. 本次 result 的元数据(直播态,有真 size/kind)—— 顺手进缓存
        //   2. 进程内缓存(进过一次查看器/切过 tab 后回来,wire 上已经没有了)
        //   3. upsert 时从 input.paths 派生的那份(只有路径 —— 冷启动的兜底)
        val files = if (cur.name == DISPLAY_FILES_TOOL) {
            val meta = parseDisplayFileMeta(content)
            val best = if (meta.isNotEmpty()) {
                DisplayFilesCache.remember(cur.toolUseId, meta)
            } else {
                DisplayFilesCache.recall(cur.toolUseId)
            }
            mergeDisplayFiles(cur.files, best)
        } else {
            cur.files
        }
        items[idx] = cur.copy(output = text, isError = isError, files = files)
    }

    // ===== SSE reduce =====

    fun apply(ev: AgentEvent) {
        when (ev.type) {
            "runtime.started" -> {
                status = AgentRunStatus.Streaming
                val ti = ev.int("turnIndex")
                if (ti != null && ti != turnIndex) {
                    // 新一轮 → 下一个 text/thinking 开新气泡
                    turnIndex = ti
                    curTextIdx = -1
                    curThinkIdx = -1
                }
                // 0.15.1:服务端在每次 LLM 调用起点带 contextTokens(见
                // opencc-web routes/agent.ts:425-431 的注释),顺手记下来给
                // 「上下文」行用。null 时不覆盖(避免 0 误清)。
                ev.long("contextTokens")?.let { contextTokens = it }
            }

            "runtime.delta" -> ev.str("delta")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    noteStreaming()
                    appendText(it)
                }

            "runtime.thinking" -> ev.str("thinking")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    noteStreaming()
                    appendThinking(it)
                }

            "runtime.tool_call" -> {
                noteStreaming()
                upsertToolCall(
                    toolUseId = ev.str("toolUseId").orEmpty(),
                    name = ev.str("toolName").orEmpty().ifEmpty { "tool" },
                    input = ev.payload["input"].cleanText(),
                    output = null,
                    isError = false,
                    ts = ev.long("ts"),
                    files = parseDisplayFilePaths(ev.payload["input"]),
                )
            }

            "runtime.tool_result" -> applyToolResult(
                ev.str("toolUseId"),
                ev.payload["output"],
                false,
            )

            "runtime.retrying" -> status = AgentRunStatus.Retrying

            "runtime.done" -> {
                // 0.15.1:整轮 prompt 跑完时服务端会推最新的 contextTokens,
                // 覆盖之前的值(数字单调上升,直接覆盖没问题)。
                ev.long("contextTokens")?.let { contextTokens = it }
                if (queue.isEmpty()) status = AgentRunStatus.Idle
            }

            "runtime.aborted" ->
                status = if (queue.isEmpty()) AgentRunStatus.Aborted else AgentRunStatus.Streaming

            "runtime.error" -> {
                val toolUseId = ev.str("toolUseId")
                val msg = ev.obj("error")?.str("message") ?: ev.str("error") ?: "运行出错"
                if (!toolUseId.isNullOrEmpty()) {
                    markToolError(toolUseId, msg)
                } else {
                    items.add(AgentItem.Note("err-${ev.seq}", msg, ev.long("ts"), isError = true))
                    status = AgentRunStatus.Error
                }
            }

            "runtime.compacted" -> {
                val pre = ev.long("preTokens") ?: 0L
                val post = ev.long("postTokens") ?: 0L
                items.add(
                    AgentItem.Note(
                        key = "cmp-${ev.seq}",
                        text = "上下文已压缩：$pre → $post tokens",
                        timestamp = ev.long("ts"),
                        isError = false,
                    )
                )
            }

            "queue.changed" -> queue = parseQueue(ev)

            "cwd.changed" -> ev.str("cwd")?.takeIf { it.isNotBlank() }?.let { cwd = it }

            "session.renamed" -> ev.str("title")?.let { title = it }

            "v2_task.changed" -> upsertV2Task(ev)

            // 后台任务。服务端每次新 SSE 连接建立时会把当前所有 agent 任务
            // 合成一条 `agent_task.changed` 重推(`routes/event.ts:120-135`),
            // 所以断线重连后 running 的不会丢。
            "agent_task.changed" -> upsertBgAgentTask(ev)
            "bash_task.changed" -> upsertBgBashTask(ev)

            // 0.15.1:服务端「投影」通路 — host 算完的派生值快照,目前
            // 试点迁移了 title / context.tokens 两个 key(见 opencc-web
            // routes/agent.ts:1725-1738)。重连后 host 会整体重发,
            // 客户端只做 higher-seq-wins。我们这边 per-session store,
            // 简单覆盖即可。value 是 unknown(JsonObject 透传),长整型直接
            // 取 number;不是数字就丢掉。
            "session/projection" -> {
                when (ev.str("key")) {
                    "context.tokens" -> ev.long("value")?.let { contextTokens = it }
                    else -> Unit
                }
            }

            "prompt.ask" -> pending = PendingInteraction(
                kind = "ask",
                toolUseId = ev.str("toolUseId").orEmpty(),
                questions = parseAskQuestions(ev),
            )

            "prompt.permission" -> pending = PendingInteraction(
                kind = "permission",
                toolUseId = ev.str("toolUseId").orEmpty(),
                toolName = ev.str("toolName"),
                description = ev.str("description"),
                input = ev.payload["input"],
                message = ev.str("message"),
            )

            "prompt.approve" -> pending = PendingInteraction(
                kind = "approve",
                toolUseId = ev.str("toolUseId").orEmpty(),
                title = ev.str("title"),
                summary = ev.str("summary"),
                filePath = ev.str("filePath"),
            )
        }
    }

    fun clearPending() {
        pending = null
    }

    /**
     * `/clear` 的本地落地。
     *
     * 服务端那一侧已经把 transcript 的消息删掉(`builtin/clear.ts`,保留
     * sessionId),我们这边必须把渲染列表一起清 —— 否则屏幕上还挂着
     * 服务端已经不存在的消息,下一轮回复会接在一堆「幽灵上下文」后面,
     * 用户会以为 clear 没生效。
     *
     * **只清 items**:会话本身还在(sid / 标题 / cwd / 模型都不动),pending
     * 与 status 也不是「上下文」,清掉只会让 UI 状态和真实运行态脱节。
     * 游标重置成与 [hydrate] 相同的初始值,让后续流式回复重新开气泡。
     */
    fun clearAll() {
        items.clear()
        curTextIdx = -1
        curThinkIdx = -1
        turnIndex = -1
        segCounter = 0
    }

    /**
     * 本地追加一条提示条 —— 命令的本地结果(`/status` 的状态块、`/compact`
     * 的压缩回执、命令报错)落在这里。
     *
     * 用 [AgentItem.Note] 而不是 Snackbar / toast:这些内容有信息量(状态、
     * 摘要),值得留在会话流里回看,而 Snackbar 几秒就没了。key 里带时间戳
     * 与下标 —— 同一条命令连敲两次必须是两条独立的条。
     */
    fun appendNote(text: String, isError: Boolean = false) {
        items.add(
            AgentItem.Note(
                key = "local-note-${System.currentTimeMillis()}-${items.size}",
                text = text,
                timestamp = System.currentTimeMillis(),
                isError = isError,
            )
        )
    }

    /**
     * 本地乐观追加一条用户消息。
     *
     * **必须本地追加**：SSE 事件面里没有「用户发了消息」这一类事件
     * （`runtime.*` 全是助手侧），用户消息只在 assistant 回复落盘时间接进
     * transcript。不追加的话，自己刚发的消息要等下一次 re-hydrate 才出现。
     *
     * [attachments] 是图片张数, [attachmentUris] 是 content:// URI 列表 —
     * 气泡里渲染方形缩略图(0.10.2 起,见 [AgentItem.UserText.attachmentUris])。
     * base64 在 [AgentApi.sendPrompt] 发完就丢了,所以**渲染**得用 URI,不能再
     * 从 base64 解码;只要进程在 URI 就能读,跨配置变更也不会丢。
     */
    fun appendLocalUser(
        text: String,
        attachments: Int = 0,
        attachmentUris: List<Uri> = emptyList(),
    ) {
        curTextIdx = -1
        curThinkIdx = -1
        items.add(
            AgentItem.UserText(
                key = nextKey("local-user"),
                text = text,
                timestamp = System.currentTimeMillis(),
                attachments = attachments,
                attachmentUris = attachmentUris,
            )
        )
    }

    fun setError(message: String) {
        status = AgentRunStatus.Error
        items.add(AgentItem.Note("local-err-${System.currentTimeMillis()}", message, null, true))
    }

    private fun parseQueue(ev: AgentEvent): List<QueuedPrompt> {
        val arr = ev.arr("pending") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            QueuedPrompt(id = id, text = o.str("text").orEmpty())
        }
    }

    private fun parseAskQuestions(ev: AgentEvent): List<AskQuestion> {
        val arr = ev.arr("questions") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            runCatching { json.decodeFromJsonElement<AskQuestion>(o) }.getOrNull()
        }
    }

    private fun upsertV2Task(ev: AgentEvent) {
        val taskObj = ev.obj("task") ?: return
        val task = runCatching { json.decodeFromJsonElement<V2Task>(taskObj) }.getOrNull() ?: return
        val idx = v2Tasks.indexOfFirst { it.id == task.id }
        if (ev.str("action") == "delete") {
            if (idx >= 0) v2Tasks.removeAt(idx)
            return
        }
        if (idx >= 0) v2Tasks[idx] = task else v2Tasks.add(task)
    }

    /**
     * `agent_task.changed` 的 payload 是 `{sessionId, task}`(见 opencc-web
     * `shared/events.ts` 的 `AgentTaskChangedEvent`),`task` 即 [BgAgentTask]
     * 全量快照 —— 所以按 id 覆盖即可,不需要自己拼状态机。
     *
     * 就地替换而不是「先删后加」:同一条任务会推很多次(queued → running →
     * completed),原位替换让它在列表里的位置稳定,不会来回跳。
     */
    private fun upsertBgAgentTask(ev: AgentEvent) {
        val obj = ev.obj("task") ?: return
        val task = runCatching { json.decodeFromJsonElement<BgAgentTask>(obj) }.getOrNull() ?: return
        if (task.id.isBlank()) return
        val idx = bgAgentTasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) bgAgentTasks[idx] = task else bgAgentTasks.add(task)
        pruneBgTasks()
    }

    /** 同 [upsertBgAgentTask],bash 侧的任务字段是 `taskId`。 */
    private fun upsertBgBashTask(ev: AgentEvent) {
        val obj = ev.obj("task") ?: return
        val task = runCatching { json.decodeFromJsonElement<BgBashTask>(obj) }.getOrNull() ?: return
        if (task.taskId.isBlank()) return
        val idx = bgBashTasks.indexOfFirst { it.taskId == task.taskId }
        if (idx >= 0) bgBashTasks[idx] = task else bgBashTasks.add(task)
        pruneBgTasks()
    }

    /**
     * 清掉早已结束的后台任务(内存 + 视觉双重用途):终态任务超过
     * [BG_RECENT_TTL_MS] 就直接丢掉,running / queued 永不动。
     *
     * **两个裁剪点缺一不可**:这里管「有事件来的时候」,渲染层还按同一个 TTL
     * 再滤一次(见 `TaskDockStrip` 的 now 参数)—— 会话安静下来之后没有新事件,
     * 光靠这里那条「完成」会一直挂在任务栏上。
     */
    private fun pruneBgTasks() {
        val cutoff = System.currentTimeMillis() - BG_RECENT_TTL_MS
        bgAgentTasks.removeAll { bgTerminal(it.status) && (it.finishedAt ?: it.createdAt) < cutoff }
        bgBashTasks.removeAll { bgTerminal(it.status) && (it.finishedAt ?: it.startedAt) < cutoff }
    }

    // ===== 流式拼装 =====

    /**
     * 「有内容在流」的兜底信号。不能只依赖 `runtime.started` —— replay 切片
     * (最近 256 条)可能已经把那一轮的 started 挤掉了,而 delta 还在源源不断
     * 过来。所以每个流式事件都顺手把状态顶回 Streaming(已 Streaming/Retrying
     * 时不动,避免覆盖 retrying)。
     */
    private fun noteStreaming() {
        if (status != AgentRunStatus.Streaming && status != AgentRunStatus.Retrying) {
            status = AgentRunStatus.Streaming
        }
    }

    private fun nextKey(prefix: String): String = "$prefix-${sessionId}-${++segCounter}"

    private fun appendText(delta: String) {
        // 一旦开始出 text,上一段 thinking 收口;两者不共用气泡。
        curThinkIdx = -1
        val idx = curTextIdx
        if (idx in items.indices && items[idx] is AgentItem.AssistantText) {
            val cur = items[idx] as AgentItem.AssistantText
            items[idx] = cur.copy(text = cur.text + delta)
            return
        }
        items.add(AgentItem.AssistantText(nextKey("stream-text"), delta, System.currentTimeMillis()))
        curTextIdx = items.lastIndex
    }

    private fun appendThinking(delta: String) {
        curTextIdx = -1
        val idx = curThinkIdx
        if (idx in items.indices && items[idx] is AgentItem.Thinking) {
            val cur = items[idx] as AgentItem.Thinking
            items[idx] = cur.copy(text = cur.text + delta)
            return
        }
        items.add(AgentItem.Thinking(nextKey("stream-think"), delta, System.currentTimeMillis()))
        curThinkIdx = items.lastIndex
    }

    /**
     * 工具卡的 upsert。`toolUseId` 为空时退回按名字兜底,避免所有匿名工具撞
     * 同一个 key(协议没强制有 id,实测都有)。
     */
    private fun upsertToolCall(
        toolUseId: String,
        name: String,
        input: String?,
        output: String?,
        isError: Boolean,
        ts: Long?,
        /** 仅 `DisplayFiles` 非空 —— 从 tool_use 的 `input.paths` 派生。 */
        files: List<DisplayFile> = emptyList(),
    ) {
        // 工具卡之后的 text 必须落在**新**气泡里(否则会 append 到工具卡前面
        // 那个旧气泡,视觉顺序就错了)。
        curTextIdx = -1
        curThinkIdx = -1

        val key = toolKey(toolUseId) ?: nextKey("tool-$name")
        val idx = items.indexOfFirst { it.key == key }
        if (idx >= 0) {
            val cur = items[idx] as? AgentItem.ToolCall ?: return
            items[idx] = cur.copy(
                name = name.ifEmpty { cur.name },
                input = input ?: cur.input,
                // 已终态不被后续 start 覆盖
                output = cur.output ?: output,
                isError = cur.isError || isError,
                // 上一次已有的元数据(带 size/error 的那份)不能被这次的
                // 纯路径版本覆盖 —— transcript 里 tool_use 会先于 tool_result
                // 被读到,但同一会话重放时两个来源都可能再来一遍。
                files = if (files.isNotEmpty()) files else cur.files,
            )
            return
        }
        items.add(
            AgentItem.ToolCall(
                key = key,
                toolUseId = toolUseId,
                name = name,
                input = input,
                output = output,
                isError = isError,
                timestamp = ts,
                files = files,
            )
        )
    }

    private fun markToolError(toolUseId: String, message: String) {
        val key = toolKey(toolUseId) ?: return
        val idx = items.indexOfFirst { it.key == key }
        if (idx < 0) return
        val cur = items[idx] as? AgentItem.ToolCall ?: return
        items[idx] = cur.copy(output = message, isError = true)
    }

    private fun toolKey(toolUseId: String): String? =
        toolUseId.takeIf { it.isNotEmpty() }?.let { "tool-$it" }

    /**
     * tool_use 侧的 DisplayFiles 文件列表 —— 路径来自 input,元数据优先取
     * 进程内缓存(见 [DisplayFilesCache]:重新 hydrate 时 wire 上那份已经没了,
     * 不补的话卡片上的 size / 时间会凭空消失)。
     */
    private fun displayFilesFromInput(
        toolUseId: String?,
        input: JsonElement?,
    ): List<DisplayFile> = mergeDisplayFiles(
        fromInput = parseDisplayFilePaths(input),
        fromResult = DisplayFilesCache.recall(toolUseId),
    )
}

/**
 * 工具入参的展示文本。除空白/null 归一外还做长度截断 —— 有些工具的 input
 * 是一整份文件内容,把它原样塞进折叠卡会在展开时卡住渲染。
 */
private fun JsonElement?.cleanText(): String? =
    this?.pretty()?.takeIf { it.isNotBlank() }?.capForDisplay(DISPLAY_INPUT_CAP, "入参")
