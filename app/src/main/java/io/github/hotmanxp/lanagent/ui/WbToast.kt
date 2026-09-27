// ui/WbToast.kt — 顶部轻提示(0.22.0)
//
// 抄 Agents-Anywhere `ui/designsystem/AAToast.kt`。色值不抄 AA 的中性暖灰,
// 全部走本项目的 `MaterialTheme.colorScheme` / `LocalWbExtras` —— 品牌橙体系不动。
//
// ## 为什么不直接用 M3 默认 Snackbar
//
// M3 的 SnackbarHost 在 Scaffold 底部、文案左对齐、动作键在右侧、默认 4s
// 常驻。本项目这些提示大多是「已在 Mac 上打开所在目录」这种**一次性确认**,
// 底部会跟输入条打架(输入条是双行白卡,Scaffold snackbar 恰好落在它后面),
// 4s 也太长 —— 用户点完复制就该知道了。
//
// 所以:挂**顶部**、胶囊形、1.6s 自动消失、带一个对勾图标(错误态是警示图标 +
// 红边)。跟 WorkBuddy 手机端「顶部滑入一条」的观感一致。
//
// 用法:屏里 `val toastHost = remember { SnackbarHostState() }` + Scaffold
// `snackbarHost = { WbToastHost(toastHost) }`,然后继续用
// `toastHost.showSnackbar("…")` —— 不改调用点。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import kotlinx.coroutines.delay

/**
 * 本项目专用的 [SnackbarVisuals]。
 *
 * 带一个 [isError],让失败提示长成红边 + 警示图标而不是和成功提示一个样 ——
 * 「打开目录失败（Mac 可能没起图形界面）」和「已在 Mac 上打开」长得一样的话,
 * 用户得读完字才知道成没成。
 *
 * [timeoutMillis] 默认 1.6s;带 [actionLabel] 时默认不自动消失(AA 的做法),
 * 等用户点掉 —— 有操作要确认的提示不该自己跑掉。
 */
data class WbToastVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    val isError: Boolean = false,
    val timeoutMillis: Long? = if (actionLabel == null) 1_600L else null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

/** 提示成功(绿勾图标)。 */
internal suspend fun SnackbarHostState.showToast(message: String) {
    showSnackbar(
        WbToastVisuals(message = message),
    )
}

/** 提示失败(红边 + 警示图标)。 */
internal suspend fun SnackbarHostState.showErrorToast(message: String) {
    showSnackbar(
        WbToastVisuals(message = message, isError = true),
    )
}

/** 挂顶部的提示条。放到 Scaffold 的 `snackbarHost = { … }` 里。 */
@Composable
internal fun WbToastHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        SnackbarHost(hostState = hostState) { data ->
            // M3 的 SnackbarHost 只认 duration 档位(Short=4s),做不到 1.6s。
            // 这里自己计时 —— data.dismiss() 是幂等的,不会被重复触发出问题。
            val timeout = (data.visuals as? WbToastVisuals)?.timeoutMillis
            LaunchedEffect(data, timeout) {
                if (timeout != null) {
                    delay(timeout)
                    data.dismiss()
                }
            }
            WbToast(data)
        }
    }
}

@Composable
private fun WbToast(data: SnackbarData) {
    val isError = (data.visuals as? WbToastVisuals)?.isError == true
    val shape = RoundedCornerShape(18.dp)

    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val borderColor = if (isError) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val iconColor = if (isError) MaterialTheme.colorScheme.error else WbPalette.Green

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 340.dp)
            .shadow(14.dp, shape)
            .clip(shape)
            .background(container)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isError) Lucide.TriangleAlert else Glyph.SolidCheckCircle,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = data.visuals.message,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
