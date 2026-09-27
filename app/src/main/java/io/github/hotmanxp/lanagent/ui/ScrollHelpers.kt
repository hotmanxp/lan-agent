// ui/ScrollHelpers.kt — 会话流的滚动行为(0.23.0)
//
// 两个问题,手法对齐 Agents-Anywhere `ui/screens/sessiondetail/SessionMessages.kt`:
//
// ## 1. 折叠展开时视口跳动
//
// LazyColumn 的 item 在展开/折叠瞬间高度突变,视口**不会**自动补偿 ——
// 展开一张折叠态的卡片,下方所有内容整体上跳一截;用户刚点开想看的内容就被
// 推走了,得手动滚回来。
//
// 解法:状态切换**前**记住这张卡的窗口 Y,布局**后**若它挪了,用
// `listState.dispatchRawDelta(位移)` 补回去 —— 卡片钉在原地,只有它下方的
// 内容流动。
//
// 用 dispatchRawDelta 而不是 scrollToItem 反推:后者要拿 item 高度算绝对偏移,
// 在高度未知的折叠态上根本算不出来;这里只需要「这张卡自己的位移」,是精确的。
//
// ## 2. 流式输出抢视口
//
// 本项目原来只用 `firstVisibleItemIndex <= 3` 判「用户在底部附近」。问题:
// 用户上滑**不到 3 屏**时,新消息到达仍会把视口拽回底部 —— 正好是用户正在
// 回看前面内容的那个区间。
//
// 解法:用 [NestedScrollConnection] 捕捉**用户手势本身**(而不是位置快照)。
// 手指一动就置 paused,之后新内容不再自动跟;滑回底部再解除。
//
// ⚠️ AA 那边同时用 NestedScrollConnection 和裸 pointerInput 做两遍同样的事,
// 这里只取 NestedScrollConnection 一层 —— pointerInput 那份是重复实现,
// 多指 / 手势取消时容易漏事件。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ── 1. 折叠展开防跳视口 ─────────────────────────────────────────────────

/**
 * 一张可折叠卡片的视口锚点。
 *
 * 用法固定三步:
 * ```kotlin
 * val anchor = rememberCardTopAnchor()
 * // 1) 状态切换前 capture()
 * onClick = { anchor.capture(); expanded = !expanded }
 * // 2) 根节点挂 anchorCardTop(anchor, listState)
 * // 3) 完成 —— 补偿在 modifier 内部自动做
 * ```
 */
internal class CardTopAnchor {
    private var lastTop = 0f
    private var captured: Float? = null

    /** 状态即将变化时调用,记下当前 Y。 */
    fun capture() {
        captured = lastTop
    }

    internal fun onPositioned(top: Float, scroller: ScrollableState) {
        val before = captured
        captured = null
        // 阈值 1f:布局抖动带来的亚像素位移不值得补偿,否则会有轻微的
        // 「越滚越飘」累积。
        if (before != null && abs(before - top) > 1f) {
            scroller.dispatchRawDelta(before - top)
        }
        lastTop = top
    }
}

@Composable
internal fun rememberCardTopAnchor(): CardTopAnchor = remember { CardTopAnchor() }

/**
 * 挂到需要防跳的卡片根节点。配套 [CardTopAnchor.capture] 在状态切换前调用。
 *
 * `listState` 传 `LazyListState` 而不是 `ScrollableState` 是为了省掉调用方的
 * 强转;内部只用 `dispatchRawDelta`,不需要 list 特有能力。
 */
internal fun Modifier.anchorCardTop(
    anchor: CardTopAnchor,
    listState: LazyListState,
): Modifier = this
    .onGloballyPositioned { anchor.onPositioned(it.positionInWindow().y, listState) }

// ── 2. 流式输出不抢视口 ─────────────────────────────────────────────────

/**
 * 自动跟随状态机。[paused] 为 true 时,新内容**不**抢视口。
 *
 * 三个字段都是 [androidx.compose.runtime.MutableState] —— 组合期读它们会自动
 * 订阅,调用方在 `LaunchedEffect` 里读 `paused` 即可,不需要额外 collect。
 */
internal class AutoFollowController {
    /** 用户手势开始后置 true,滑回底部后解除。 */
    var paused by mutableStateOf(false)
        private set

    /** 滚动方向:1 = 朝旧内容, -1 = 朝新内容, 0 = 未知。 */
    var direction by mutableStateOf(0)
        private set

    internal fun onUserScroll(deltaY: Float) {
        if (deltaY == 0f) return
        direction = if (deltaY > 0f) 1 else -1
        paused = true
    }

    internal fun resume() {
        paused = false
        direction = 0
    }
}

/**
 * 建立手势 → 跟随的联动。返回一个 Modifier 挂到 LazyColumn 上。
 *
 * 解除暂停的条件写得很挑(必须同时满足「已在底部」「不在滚动中」「方向朝新内容」):
 * 用户刚抬手那一刻,列表可能还没动,但位置快照已经报了「贴底」—— 这时若直接
 * 解除暂停,刚上滑的用户会立刻被新消息拽回去。用 collectLatest 让滑动过程中
 * 的旧判定自动作废。
 */
@Composable
internal fun rememberAutoFollowController(
    listState: LazyListState,
    scope: kotlinx.coroutines.CoroutineScope,
): AutoFollowController {
    val controller = remember { AutoFollowController() }

    val atBottom by remember(listState) {
        derivedStateOf {
            // reverseLayout 下 index 0 是底部,所以「贴底」= index 0 且无偏移。
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    remember(controller, listState, atBottom) {
        scope.launch {
            snapshotFlow { Triple(atBottom, controller.direction, listState.isScrollInProgress) }
                .distinctUntilChanged()
                .collectLatest { (bottom, direction, scrolling) ->
                    if (bottom && !scrolling && direction < 0) controller.resume()
                }
        }
    }
    return controller
}

/**
 * 手势检测,挂到 LazyColumn 的 modifier 链上。
 *
 * 只负责**置位**,不负责滚动 —— 「要不要滚」还取决于块数是否变化,那是
 * 调用方 `LaunchedEffect` 的事。
 */
internal fun Modifier.userScrollDetection(controller: AutoFollowController): Modifier =
    nestedScroll(
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) controller.onUserScroll(available.y)
                return Offset.Zero
            }
        },
    )
