// voice/HoldToTalkOverlay.kt — 录音中的全屏动效层（对齐 WorkBuddy 手机端）。
//
// 视频逐帧拆出来的形态（Record_2026-09-14）：
//   * 按下 → 一块青绿色渐变面板从屏幕底部「涌起」，占屏约 36%，上缘羽化到透明；
//   * 面板上自下而上依次是：实时音量波形条（深色，向左滚动）、白色小圆点
//     （拖拽把手）、提示文案「松手发送，上移取消」；
//   * 上滑进取消区 → 整块变红，文案变「松开手指 取消发送」；
//   * 松手 → 面板带动画退回底部，组件不拦截任何触摸（手势仍在胶囊上）。
//
// 实现要点：
//   1. 波形不是随机噪声，是 44 格音量历史：每 55ms 把 [HoldToTalkState.level]
//      推进一格、整体左移 —— 滚动速度就是时间轴，高度就是当时的真实音量。
//   2. 进出场动画用 reveal(0→1) 同时驱动面板高度，涌起/退去共用一个曲线；
//      reveal 归零后整个组件不画任何东西，Idle 常驻组合也无开销。
//   3. 组件只用 Box/Canvas/Text 这类无 pointerInput 的节点 —— 全屏覆盖但
//      不吃事件，按住手势在胶囊上照常收。
package io.github.hotmanxp.lanagent.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.graphicsLayer

/** 面板主色：从视频帧采样的青绿 #3FD1B4（RGB 63,209,180）。 */
private val GreenPanel = Color(0xFF3FD1B4)

/** 取消态的面板红。 */
private val RedPanel = Color(0xFFEB4D5C)

/** 绿底上的波形条 / 文案：近黑的深青。 */
private val InkOnGreen = Color(0xFF123B31)

private const val BAR_COUNT = 44

private const val TICK_MS = 55L

@Composable
fun HoldToTalkOverlay(
    state: HoldToTalkState,
    modifier: Modifier = Modifier,
) {
    val recording = state.phase == HoldPhase.Recording
    val canceling = state.willCancel

    // 涌起(0→1)/退去(1→0)共用这一根曲线；松手进 Recognizing 时 recording
    // 变 false，面板带着波形一起滑回底部，比生硬消失自然。
    val reveal by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "hto-reveal",
    )
    // 收紧后不画任何东西（Idle 常驻组合零开销）。
    if (reveal <= 0.002f) return

    // ── 音量历史环形推进：tick 变化驱动 Canvas 重绘 ──────────────────────
    val history = remember { FloatArray(BAR_COUNT) { 0.06f } }
    // 每根条固定的形状系数，让同一音量下波形也有起伏，不是一排平头。
    val shape = remember { FloatArray(BAR_COUNT) { i -> 0.55f + 0.45f * abs(sin(i * 1.31f)) } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (state.phase == HoldPhase.Recording) {
            System.arraycopy(history, 1, history, 0, BAR_COUNT - 1)
            history[BAR_COUNT - 1] = state.level.coerceIn(0.05f, 1f)
            tick++
            delay(TICK_MS)
        }
    }

    val panel = if (canceling) RedPanel else GreenPanel
    val ink = if (canceling) Color.White else InkOnGreen
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxPanelHeight = screenHeight * 0.36f

    Box(modifier = modifier.fillMaxSize()) {
        // ── 底部渐变面板：高度 = 36% 屏高 × reveal ──────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxPanelHeight * reveal)
                .background(
                    Brush.verticalGradient(
                        // 视频：底部实、顶部 1/3 快速羽化到透明，没有硬边。
                        colorStops = arrayOf(
                            0.0f to panel.copy(alpha = 0f),     // 顶端羽化
                            0.38f to panel.copy(alpha = 0.92f),
                            0.62f to panel,
                            1.0f to panel,                      // 底部最实
                        ),
                    ),
                ),
        )
        // 上缘羽化：面板顶端叠一小段同色→透明的过渡，避免硬边。
        // （画在面板上方 48dp 处，跟随 reveal 高度走。）

        // ── 面板内容：文案 / 把手 / 波形，锚在屏幕底部 ──────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 56.dp)
                // 文案/波形等面板完全展开前不显形，避免涌起一半时浮在普通背景上。
                .graphicsLayer { alpha = ((reveal - 0.35f) / 0.5f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (canceling) "松开手指 取消发送" else "松手发送，上移取消",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ink,
                textAlign = TextAlign.Center,
                // 内容随面板一起浮现，reveal 小的时候先藏在面板外。
                modifier = Modifier.padding(bottom = 14.dp),
            )

            // 拖拽把手：白色小圆点（视频里居中那颗）。
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(Color.White.copy(alpha = 0.85f), CircleShape),
            )

            Spacer(Modifier.height(26.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth(fraction = 0.78f)
                    .height(64.dp),
            ) {
                drawWaveform(history, shape, ink, tick)
            }
        }
    }
}

/** 44 根圆角条：i 越靠右越新，最右一根就是「此刻」的音量。 */
private fun DrawScope.drawWaveform(
    history: FloatArray,
    shape: FloatArray,
    color: Color,
    @Suppress("UNUSED_PARAMETER") tick: Int,
) {
    val slot = size.width / BAR_COUNT
    val barWidth = slot * 0.42f
    val midY = size.height / 2f
    for (i in 0 until BAR_COUNT) {
        val v = (history[i] * shape[i]).coerceIn(0.06f, 1f)
        val h = size.height * (0.12f + 0.88f * v) * 0.5f
        val x = i * slot + (slot - barWidth) / 2f
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(x, midY - h),
            size = androidx.compose.ui.geometry.Size(barWidth, h * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
        )
    }
}
