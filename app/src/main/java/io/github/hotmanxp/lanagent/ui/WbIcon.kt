// ui/WbIcon.kt — Lucide 图标的 RTL 包装(0.21.0)
//
// **Lucide 的 `ImageVector` 不带 autoMirrored 元数据**。Material 的
// `Icons.AutoMirrored.Rounded.*` 会在 RTL locale 下自动翻转,Lucide 不会 ——
// 换过去之后,原本靠 AutoMirrored 表达的 11 处方向性图标(返回箭头、跳转箭头、
// 打开文件夹方向、发送方向)在阿拉伯语 / 希伯来语下会指反。
//
// 所以方向性图标统一走 [WbMirroredIcon]。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * 方向性图标的 RTL 包装。
 *
 * [autoMirror] 为 true 时,在 RTL locale 下水平翻转。只在**语义确实依赖方向**的图标上
 * 用(返回箭头 / 跳转箭头 / 文件夹打开方向 / 发送方向);纯几何图形(齿轮、时钟、
 * 垃圾桶)不要传 true —— 翻了反而怪。
 *
 * ```kotlin
 * WbMirroredIcon(Lucide.ArrowLeft, contentDescription = "返回")
 * WbMirroredIcon(Lucide.FolderOpen, contentDescription = "目录", autoMirror = false)
 * ```
 */
@Composable
internal fun WbMirroredIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    autoMirror: Boolean = true,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        modifier = if (autoMirror && rtl) modifier.graphicsLayer(scaleX = -1f) else modifier,
        tint = tint,
    )
}
