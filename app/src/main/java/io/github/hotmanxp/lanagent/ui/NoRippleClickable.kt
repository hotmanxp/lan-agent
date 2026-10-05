// ui/NoRippleClickable.kt — 无涟漪点击(0.21.0)
//
// 抄 Agents-Anywhere `ui/designsystem/NoRippleClickable.kt`(19 行,零依赖)。
//
// 为什么需要:底栏 tab、工具卡的折叠箭头、代码块右上角的「复制」
// 这些位置的点击反馈**不需要**水波纹 ——
//   - 底栏:AGENTS.md §16 明确「去水波纹」,M3 默认的 ripple 在浅色主题下是
//     一颗灰底胶囊,跟 WorkBuddy 的克制风格不搭;
//   - 折叠箭头:ripple 的圆形扩散范围远大于箭头本身,视觉上像误触;
//   - 复制钮:点完就消失的操作,给个瞬时反馈比涟漪更合适。
//
// `indication = null` 只去掉**绘制**,`interactionSource` 仍然收集 pressed 状态,
// 所以 `Modifier.clickable` 的语义(可访问性、hover)完全保留 —— 这跟
// 「真的不用 clickable」是两回事。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 无涟漪的 [clickable]。
 *
 * ⚠️ 提供的 [MutableInteractionSource] 是 `remember` 出来的 —— **不要从外部传入**,
 * 否则每次重组传新实例会丢掉 pressed 状态缓存。需要读 pressed 时用局部
 * `val interaction = remember { MutableInteractionSource() }` + `collectIsPressedAsState()`。
 */
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}
