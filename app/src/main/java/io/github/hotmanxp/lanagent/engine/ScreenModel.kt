// ScreenModel.kt — 屏幕元素模型 + 稳定 id。
//
// ## 为什么需要「稳定 id」
//
// 模型的用法是「读一次屏 → 拿到元素 id → 拿 id 去点」。如果每次读屏 id 都变
// (e1 变成 e17),模型手里那个 id 就指不到东西了 —— 它得重读一遍,多一轮往返。
//
// 所以 id 要**跨读稳定**:同一个元素(同 class + 同 resourceId + 同文字)
// 在下一次读屏里继续用同一个 id。
//
// 做法(对齐 DSH-Android 的 StableIds):
//   1. 每个元素算一个 identity(不含 id 本身):class|resourceId|text|desc;
//   2. 跟上一帧比 —— identity 相同就沿用旧 id;
//   3. 新的 identity 发一个新 id(e1 / e2 / …);
//   4. 上一帧有、这一帧没了的 id 记进「退役表」:之后模型拿着旧 id 来点,
//      如果这一帧里恰好有一个 identity 相同且位置没跑太远的元素,告诉模型
//      「它挪到 e23 了」,而不是干巴巴地说「找不到」。
package io.github.hotmanxp.lanagent.engine

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.hypot

/**
 * 一个屏幕元素。
 *
 * [node] 是这次读屏时的节点引用 —— 拿它做动作(点/输入)。
 * 节点会过期(页面变了),所以用它的动作都要包 runCatching。
 */
data class UiElement(
    /** 跨读稳定的身份:不含 id,不含坐标。 */
    val identity: String,
    /** 短类型名(Button / TextView / EditText …)。 */
    val type: String,
    val text: String,
    val desc: String,
    val resourceId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val focused: Boolean,
    val checked: Boolean,
    /** 中心点(屏幕像素)。 */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val node: AccessibilityNodeInfo?,
) {
    /** 给模型看的一行。 */
    fun render(id: String): String {
        val sb = StringBuilder()
        sb.append("[").append(id).append("] ").append(type)
        if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append("\"")
        if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc.take(80)).append("\"")
        if (resourceId.isNotEmpty()) sb.append(" #").append(resourceId)
        val flags = buildList {
            if (clickable) add("clickable")
            if (editable) add("editable")
            if (scrollable) add("scrollable")
            if (focused) add("focused")
            if (checked) add("checked")
        }
        if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append("}")
        sb.append(" @").append(x).append(",").append(y)
        return sb.toString()
    }
}

/** 一次读屏的结果。 */
data class ScreenFrame(
    val packageName: String?,
    val activityName: String?,
    val elements: List<UiElement>,
    /** 与 [elements] 一一对应的 id。 */
    val ids: List<String>,
) {
    fun indexOf(id: String): Int? = ids.indexOf(id).takeIf { it >= 0 }

    /** 渲染成给模型看的文本。 */
    fun render(maxLines: Int = 200): String {
        val sb = StringBuilder()
        sb.append("App: ").append(packageName ?: "(未知)")
        if (!activityName.isNullOrEmpty()) sb.append(" · ").append(activityName)
        sb.append(" · 元素 ").append(elements.size).append(" 个")
        val shown = elements.take(maxLines)
        for (i in shown.indices) {
            sb.append('\n').append(shown[i].render(ids[i]))
        }
        if (elements.size > shown.size) {
            sb.append("\n…(还有 ").append(elements.size - shown.size)
                .append(" 个元素没显示,分页读或用 screen_wait 等)")
        }
        return sb.toString()
    }
}

/**
 * 跨读稳定的 id 分配器。
 *
 * 单线程使用(能力服务在固定线程池里,但同一个能力服务实例串行化调用即可)。
 */
class StableIds {

    private var next = 1
    private var latest: ScreenFrame? = null
    private val retired = LinkedHashMap<String, UiElement>()

    /** 分配 id:跟上一帧 identity 相同的沿用旧 id,新的发新 id。 */
    @Synchronized
    fun assign(packageName: String?, activityName: String?, elements: List<UiElement>): ScreenFrame {
        val prev = latest?.takeIf { it.packageName == packageName }
        if (prev == null) retired.clear()
        val freeByIdentity = prev?.elements?.indices
            ?.groupBy { prev.elements[it].identity }
            ?.mapValues { it.value.toMutableList() }
            .orEmpty()

        val ids = elements.map { e ->
            val candidates = freeByIdentity[e.identity]
            val best = candidates?.minByOrNull { j -> dist(prev!!.elements[j], e) }
            if (best != null && best in candidates) {
                candidates.remove(best)
                prev!!.ids[best]
            } else {
                "e${next++}"
            }
        }

        // 上一帧有、这一帧没有的 id → 退役表(留着做「挪位了」判断)
        prev?.let { old ->
            val kept = ids.toSet()
            old.ids.forEachIndexed { i, id -> if (id !in kept) retired[id] = old.elements[i] }
        }
        ids.forEach { retired.remove(it) }

        return ScreenFrame(packageName, activityName, elements, ids).also { latest = it }
    }

    /** 最近一帧。 */
    @Synchronized
    fun latest(): ScreenFrame? = latest

    /**
     * 拿 id 找元素。
     *
     * 找不到时:如果这个 id 是「退役的」,且这一帧里有一个 identity 相同、
     * 位置没跑太远的元素,返回 [Lookup.Relocated](新下标,新 id)。
     */
    @Synchronized
    fun resolve(id: String): Lookup {
        val frame = latest ?: return Lookup.Unknown
        frame.indexOf(id)?.let { return Lookup.Found(it) }
        val was = retired[id] ?: return Lookup.Unknown
        val i = frame.elements.indices.singleOrNull { frame.elements[it].identity == was.identity }
            ?.takeIf { dist(frame.elements[it], was) <= MAX_RELOCATE_PX } ?: return Lookup.Gone
        return Lookup.Relocated(i, frame.ids[i])
    }

    sealed interface Lookup {
        data class Found(val index: Int) : Lookup
        /** id 指的东西还在,只是换了位置。 */
        data class Relocated(val index: Int, val newId: String) : Lookup
        /** 彻底没了。 */
        data object Gone : Lookup
        data object Unknown : Lookup
    }

    private fun dist(a: UiElement, b: UiElement) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    companion object {
        /** 「挪了多远还算同一个东西」(px)。 */
        private const val MAX_RELOCATE_PX = 500.0

        /** "12" / "e12" / "[e12]" → "e12"。 */
        fun normalize(raw: String): String {
            val s = raw.trim().removePrefix("[").removeSuffix("]").lowercase()
            return if (s.toIntOrNull() != null) "e$s" else s
        }
    }
}
