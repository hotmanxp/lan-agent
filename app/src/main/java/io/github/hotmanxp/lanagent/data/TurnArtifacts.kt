// data/TurnArtifacts.kt — 「本轮产物」的写入类工具白名单 + 从工具入参抽路径。
//
// 对齐 opencc-web `packages/zai/src/web/src/components/transcript/deriveTurnArtifacts.ts`
// 的 `ARTIFACT_WRITE_TOOLS`:每轮对话结束时,会话流末尾插一个「本轮产物」块,
// 列出这一轮**生成和修改过的文件**。数据源就是同一份消息列表(直播流与历史回放
// 同形态),所以纯客户端派生即可 —— 不落 transcript、不需要后端配合。
//
// 为什么是**显式白名单**而不是「input 里有 file_path 就算」:`Read` / `Grep` /
// `Glob` 同样带 path 字段,泛化会把只读调用误报成「产物」。新增写入类工具时
// 在下面表里加一行即可。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次写入调用的落点。
 *
 * @param path 工具输入里的路径**原文**,未做规范化 —— 解析交给点开预览时的服务端。
 * @param label 展示徽标文案(`写入` / `编辑`)。
 * @param written 该路径本轮是否出现过 `Write` —— 驱动徽标配色(写入绿 / 编辑紫)。
 */
data class WriteTarget(
    val path: String,
    val label: String,
    val written: Boolean,
)

/** 工具名 → (徽标, 路径字段名)。 */
private val ARTIFACT_WRITE_TOOLS: Map<String, Pair<String, String>> = mapOf(
    "Write" to ("写入" to "file_path"),
    "Edit" to ("编辑" to "file_path"),
    "MultiEdit" to ("编辑" to "file_path"),
    "NotebookEdit" to ("编辑" to "notebook_path"),
)

/**
 * 从一次工具调用的入参抽「它写了哪个文件」。非白名单工具 / 入参里没有可用路径
 * → null(调用方据此跳过)。
 *
 * 路径字段只认**字符串**:[JsonPrimitive.contentOrNull] 会把数字也转成文本
 * (`1` → "1"),那会凭空造出一条假产物。
 */
fun writeTargetOf(toolName: String, input: JsonElement?): WriteTarget? {
    val spec = ARTIFACT_WRITE_TOOLS[toolName] ?: return null
    val obj = input as? JsonObject ?: return null
    val prim = obj[spec.second] as? JsonPrimitive ?: return null
    if (!prim.isString) return null
    val path = prim.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return WriteTarget(path = path, label = spec.first, written = toolName == "Write")
}

/**
 * 路径末段(basename)。纯字符串处理,不做平台判断 —— 两种分隔符都吃
 * (Agent 在 Mac 上跑,但路径也可能来自 Windows 风格的输入)。
 */
fun baseName(path: String): String {
    val i = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (i >= 0) path.substring(i + 1) else path
}

/** 路径里 basename 之前的那一段(展示用的次要信息,可能是空串)。 */
fun parentDir(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i > 0) path.substring(0, i) else ""
}
