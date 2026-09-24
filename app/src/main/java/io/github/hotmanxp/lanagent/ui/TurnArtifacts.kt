// ui/TurnArtifacts.kt — 「本轮产物」的派生逻辑(纯函数,不碰 Compose)。
//
// 对齐 opencc-web `packages/zai/src/web/src/components/transcript/deriveTurnArtifacts.ts`:
// 遍历会话的 [AgentItem] 列表,按**用户消息**切轮,把每轮里写入类工具
// (`Write` / `Edit` / `MultiEdit` / `NotebookEdit`)动过的文件结算成一份清单。
// 数据源就是渲染用的那一份 items —— 直播流与历史回放同形态,结果天然一致。
//
// 几个刻意的取舍(与 web 端逐条对齐):
//   - 轮次以**用户消息**切分,不用服务端的 turnIndex(那边恒为 0,不可用);
//   - 只有**已结束**的轮次出块(流式中不出,避免文件列表边跑边跳);已出过的块
//     永久留在原位 —— 浏览历史时每轮都看得到;
//   - 块内容**只含本轮**文件,不跨轮累加;
//   - 看不到 subagent 内部改动与 Bash 间接写入(`sed -i` / 输出重定向)——
//     纯客户端方案的固有代价。
package io.github.hotmanxp.lanagent.ui

/**
 * 产物清单里的一行。
 *
 * @param path 工具输入里的路径原文(点击预览时交给服务端解析)。
 * @param label 徽标文案:`写入` / `编辑`。
 * @param count 本轮出现次数(UI 只在 > 1 时显示 `×N`)。
 * @param written 该路径本轮是否出现过 `Write` —— 驱动徽标配色。
 */
data class ArtifactFile(
    val path: String,
    val label: String,
    val count: Int,
    val written: Boolean,
)

/**
 * 一轮的产物清单。
 *
 * @param endIndex 该轮最后一条消息在 items 里的下标 —— 渲染块插在它**之后**。
 * @param turnKey 该轮首条用户消息的 key,用作 LazyColumn 的 key:
 *   新消息 append 不会重挂载,用户的展开/收起意图得以保留。
 */
data class TurnArtifacts(
    val endIndex: Int,
    val turnKey: String,
    val files: List<ArtifactFile>,
)

/** 文件数超过此值时产物块默认折叠(块头仍显示总数)。 */
const val ARTIFACTS_AUTO_COLLAPSE = 8

/**
 * 这一轮算不算「结束了」。只有真正在跑的两种状态不算 ——
 * `Idle` / `Aborted` / `Error` 都算结束(中断和报错的轮次,已产生的产物照常列出)。
 */
val AgentRunStatus.turnClosed: Boolean
    get() = this != AgentRunStatus.Streaming && this != AgentRunStatus.Retrying

/**
 * 派生每一轮的产物清单。**只返回有产物且已结束的轮次**,按时间顺序排列。
 *
 * @param closed 最后一轮是否已结束(前面的轮次被下一轮顶掉即视为结束)。
 */
fun deriveTurnArtifacts(items: List<AgentItem>, closed: Boolean): List<TurnArtifacts> {
    val out = ArrayList<TurnArtifacts>()
    // 当前轮区间:start 指向该轮首条用户消息的下标,-1 表示还没进入任何一轮
    var start = -1
    var turnKey = ""

    fun finalize(end: Int, isClosed: Boolean) {
        // start < 0 → 数组头部没有用户消息(历史被裁过),不构成一轮
        if (start < 0 || !isClosed || end < start) return
        val files = ArrayList<ArtifactFile>()
        val index = HashMap<String, Int>()
        for (i in start..end) {
            val item = items.getOrNull(i) as? AgentItem.ToolCall ?: continue
            val write = item.write ?: continue
            val at = index[write.path]
            if (at == null) {
                index[write.path] = files.size
                files.add(ArtifactFile(write.path, write.label, 1, write.written))
                continue
            }
            // 同一路径被反复修改合并为一行,count 累加;
            // 该路径本轮只要出现过 Write,徽标就是「写入」(与出现顺序无关)
            val cur = files[at]
            val written = cur.written || write.written
            files[at] = cur.copy(
                count = cur.count + 1,
                written = written,
                label = if (written) "写入" else cur.label,
            )
        }
        if (files.isNotEmpty()) out.add(TurnArtifacts(end, turnKey, files))
    }

    for (i in items.indices) {
        val item = items[i]
        if (item !is AgentItem.UserText) continue
        // 上一轮在 i - 1 结束,且已被新轮顶掉 → 视为已结束
        finalize(i - 1, isClosed = true)
        start = i
        turnKey = item.key
    }
    // 最后一轮:只有不在流式中才算结束
    finalize(items.lastIndex, closed)

    return out
}
