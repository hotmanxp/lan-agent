// ui/MermaidBlock.kt — 会话里 ```mermaid 围栏块的渲染外壳
//
// 职责很薄:向 [MermaidRenderer] 要一张 Bitmap,拿到就画,拿不到就把位置让回
// 给 [fallback](通常是 CodeBox)。**不自己处理任何 mermaid 语法** —— 解析在
// JS 那边,这里只管「成 / 不成」这一个二值结果。
//
// 加载中的占位刻意做得很轻(一个矮 Surface),因为流式输出时这块会短暂存在
// 一两帧,画个转圈会在消息流里跳得很扎眼。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 宽:高 超过这个值就当「超宽图」,铺满卡片宽会被压成看不清的细带。 */
private const val WIDE_ASPECT = 2.2f

/** 宽:高 低于这个值就当「超高图」,铺满卡片宽会拉出一屏接不到底的长条。 */
private const val TALL_ASPECT = 0.45f

/**
 * 「是否渲染 Mermaid」开关。由 MainActivity 顶层 provide,所有走
 * [MarkdownText] 的地方自动吃到 —— 五个调用点一个都不用改。
 *
 * 默认 **true**:与 DataStore 里的默认值一致,首帧不会因为「还没读到偏好」
 * 而闪一下代码块。
 */
internal val LocalMermaidEnabled = staticCompositionLocalOf { true }

/**
 * 这个围栏块是不是 Mermaid 流程图。
 *
 * 只看 `info` 的**第一个词** —— ```` ```mermaid ```` 后面可以跟属性
 * (```` ```mermaid {theme:dark} ````),整体比对会漏。
 *
 * 抽成纯函数是为了能进 JVM 单测(见 ui/MermaidFenceTest),渲染链路里那行
 * 判定本身不值得在真机上验。
 */
internal fun isMermaidFence(info: String?): Boolean =
    info?.trim()?.takeWhile { !it.isWhitespace() }?.equals("mermaid", ignoreCase = true) == true

private sealed interface MermaidState {
    data object Loading : MermaidState
    data class Ready(val image: ImageBitmap) : MermaidState
    data object Failed : MermaidState
}

@Composable
internal fun MermaidBlock(
    source: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = LocalWbDarkTheme.current
    // 底色烤进 PNG:深色主题下 mermaid 会画浅色节点,透明底会让字糊在深色卡片上。
    val bg = MaterialTheme.colorScheme.surfaceContainerLow

    val state by produceState<MermaidState>(MermaidState.Loading, source, dark, bg) {
        val image = MermaidRenderer.render(context, source, dark, bg.toHexString())
        value = if (image != null) MermaidState.Ready(image) else MermaidState.Failed
    }

    when (val s = state) {
        MermaidState.Loading -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier.fillMaxWidth().height(64.dp),
        ) {}

        is MermaidState.Ready -> {
            val aspect = if (s.image.height > 0) s.image.width.toFloat() / s.image.height else 1f
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(10.dp),
                modifier = modifier.fillMaxWidth(),
            ) {
                // 长宽比正常的图按卡片宽度铺满即可。两种极端要单独处理,否则
                // 纯 fit-width 会把图压成一条完全看不清的细带 / 一屏拉不到底的长条。
                when {
                    aspect > WIDE_ASPECT -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val w = maxWidth
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Image(
                                bitmap = s.image,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.width(w * 2).padding(4.dp),
                            )
                        }
                    }

                    aspect < TALL_ASPECT -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val w = maxWidth
                        Column(
                            modifier = Modifier
                                .heightIn(max = w * 3)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Image(
                                bitmap = s.image,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.width(w).padding(4.dp),
                            )
                        }
                    }

                    else -> Image(
                        bitmap = s.image,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                    )
                }
            }
        }

        // 语法错 / 流式半截 / 超时 —— 一律退回代码块,和「关掉开关」时长得一样。
        MermaidState.Failed -> Box(modifier = modifier) { fallback() }
    }
}

private fun Color.toHexString(): String {
    fun channel(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", channel(red), channel(green), channel(blue))
}
