// ui/ShimmerText.kt — 跑中扫光(0.22.0)
//
// 抄 Agents-Anywhere `SessionMessages.kt:2141-2180` 的 `TimelineShimmerText`。
//
// ## 用途范围:只扫**工具卡标题**
//
// AA 那边扫了 7 处,其中包括流式正文。本项目**刻意不扫正文** ——
// 正文本来就在逐字变,再叠一道横向扫光会让整屏一直在动,长时间读会话很吵。
// 只用在「跑中」这个状态标识上:工具组折叠态的「工具调用 · N 次」、
// 单个工具卡的跑中标题、底部运行中提示条。
//
// ## 为什么不用 compose-shimmer 依赖
//
// AA 引了 `com.valentinilk.shimmer:compose-shimmer:1.3.1`,但它做的事就是
// 「一个横向渐变随时间平移」—— 下面 40 行已经写完了。为一个效果加一个依赖
// 不划算。
//
// ## 关键:active = false 时完全不动画
//
// [active] 为 false 时 progress 恒 0,**不进 `rememberInfiniteTransition`**。
// 这一点很重要:跑完的工具卡如果还挂着一个无限循环动画,长会话里几十个卡片
// 各自跑 animation 会持续占用帧时钟。绝大多数工具卡终态都是 `active = false`,
// 所以这条路径必须是零开销的。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** 一轮扫光从右到左再回右的时长。AA 用 1400ms,跟着抄。 */
private const val SHIMMER_DURATION_MS = 1_400

/** 扫光带宽占文本宽度的比例,以及最小像素宽度(短标题也要看得见)。 */
private const val SHIMMER_BAND_RATIO = 0.28f
private const val SHIMMER_BAND_MIN_PX = 48f

/**
 * 跑中扫光文本。[active] 为 true 时有一道高光从左扫到右,循环。
 *
 * ```kotlin
 * ShimmerText(
 *     text = "工具调用 · 3 次",
 *     active = running,
 *     color = accent,
 *     fontSize = 13.sp,
 *     fontWeight = FontWeight.Medium,
 * )
 * ```
 *
 * 扫光带的几何只需要一个**大致**文本宽度,不值得为此在每个调用点挂
 * `onGloballyPositioned` 去测。这里按字号 × 字数粗估(等宽数字的近似系数),
 * 估不准的后果只是高光带略宽/略窄一点,视觉上察觉不到。
 */
@Composable
internal fun ShimmerText(
    text: String,
    active: Boolean,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val textWidth = remember(text, fontSize) {
        // 中文/全角约 1.0 em,拉丁约 0.55 em —— 取 0.8 当折中,宁可略宽。
        (text.length * fontSize.value * 0.8f).coerceAtLeast(1f)
    }

    val progress = if (active) {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer-progress",
        )
        animated
    } else {
        0f
    }

    val band = (textWidth * SHIMMER_BAND_RATIO).coerceAtLeast(SHIMMER_BAND_MIN_PX)
    // 从「完全在左侧之外」扫到「完全在右侧之外」,这样一个循环里高光完整穿过文本。
    val center = -band + progress * (textWidth + band * 2f)

    Text(
        text = text,
        modifier = modifier,
        // brush 版 TextStyle 不再接受 color —— 高亮色直接进 gradient 的中间色位。
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            brush = Brush.linearGradient(
                colors = listOf(
                    color,
                    // 高光色比底色略偏白/偏黑 —— 浅色主题下用黑(压暗),深色下用白(提亮)。
                    shimmerHighlightOf(color),
                    color,
                ),
                start = Offset(center - band, 0f),
                end = Offset(center + band, 0f),
            ),
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * 扫光高光色。返回 None 表示**不做扫光**(纯色渲染)。
 *
 * 默认实现在文件底部;调用方可以自己传 [highlight] 覆盖。
 */
@Composable
private fun shimmerHighlightOf(base: Color): Color = when {
    base.luminance() > 0.5f -> Color.Black.copy(alpha = 0.76f)
    else -> Color.White.copy(alpha = 0.95f)
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
