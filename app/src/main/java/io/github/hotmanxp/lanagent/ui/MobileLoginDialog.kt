// ui/MobileLoginDialog.kt — Agents-Anywhere mobile-login QR 弹层(0.21.0)。
//
// 流程(对齐 server `routes/api/auth.py:433`):
//   ① `mobileLoginQr()` → `{userId, loginToken, expiresAt}` —— server 端会
//      在 `/auth/mobile-login/qr` 上要求 Bearer token(0.21.0),所以这条流
//      **只适用已登录设备换 token**,不适用于全新设备首次登录。
//   ② 把 loginToken 编进 web URL:
//        `{baseUrl}/auth/mobile-login/confirm?loginToken=…&userId=…`
//      让已登录浏览器打开;那边点确认后 server 推 `status=approved`。
//   ③ 后台协程每 2s 调 `mobileLoginStatus` 拿 status,5 分钟超时:
//
//        pending_scan        → 继续轮询
//        pending_web_confirm → 继续轮询
//        approved            → 调 `mobileLoginExchange` → 拿
//                              `auth.accessToken` + `refreshToken` →
//                              写 [SecureTokenStore] → 通知外部刷新 → 关
//                              dialog
//        rejected / expired / consumed → 停止轮询 + UI 提示
//
// **轮询实现**:在一个 `LaunchedEffect(dialogOpen)` 里启 `while(true)`
// delay 2s,挂起条件 `status in approved/rejected/expired/consumed`。dialog
// `onDispose` 时 LaunchedEffect 自动取消,无需手动 Job 清理。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AgentsAnywhereApi
import io.github.hotmanxp.lanagent.data.generateQrBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val POLL_INTERVAL_MS = 2_000L
private const val POLL_TIMEOUT_MS = 5 * 60 * 1_000L

/**
 * @param api REST 客户端。`api.mobileLoginQr / Status / Exchange` 都已存在
 *            (v4 加的)
 * @param baseUrl 当前配置的 baseUrl —— QR 内容拼成
 *                `{baseUrl}/auth/mobile-login/confirm?…`
 * @param onSuccess exchange 拿到新 token 后回调 —— suspend,父层负责把 token
 *                  写进 SecureTokenStore + Prefs + 触发重连。**先**调 onSuccess
 *                  再 dismiss,父层写完 token 后 `accessTokenFlow` 会 emit 新
 *                  值,屏上表单自动回填
 * @param onDismiss 用户点关闭 / status 终态后回调 —— 父层清 `showDialog`
 */
@Composable
fun MobileLoginDialog(
    api: AgentsAnywhereApi,
    baseUrl: String,
    onSuccess: suspend (accessToken: String, refreshToken: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 一次性副作用:dialog 打开就生成 QR —— 失败就立刻关 dialog + 提示,
    // 不开无 QR 的空 dialog 浪费用户时间。
    var qrError by remember { mutableStateOf<String?>(null) }
    val qrState = produceState<QrReady?>(initialValue = null, baseUrl, api) {
        runCatching {
            val resp = api.mobileLoginQr()
            val url = buildConfirmUrl(baseUrl, resp.userId, resp.loginToken)
            val bmp = generateQrBitmap(content = url, sizePx = 720)
            QrReady(loginToken = resp.loginToken, userId = resp.userId, bitmap = bmp)
        }.onSuccess { value = it }
            .onFailure { value = null; qrError = it.message ?: it.javaClass.simpleName }
    }

    var statusText by remember { mutableStateOf<StatusKind>(StatusKind.PendingScan) }
    var statusDetail by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var timeoutReached by remember { mutableStateOf(false) }

    // 轮询:QR 拿到才启动;终态 / dialog 关闭自动停。
    // **直接 try/catch 不走 runCatching**:因为内层 onSuccess/exchange 完成后要
    // 调 suspend 的 `onSuccess` 回调,`Result.onSuccess` 不是 inline,里面调
    // suspend 不行 —— 用 try/catch 直接走 LaunchedEffect 的 CoroutineScope。
    LaunchedEffect(qrState.value) {
        val ready = qrState.value ?: return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (isActive && !finished) {
            if (System.currentTimeMillis() - start > POLL_TIMEOUT_MS) {
                timeoutReached = true
                statusText = StatusKind.Expired
                break
            }
            val resp = try {
                api.mobileLoginStatus(ready.loginToken)
            } catch (err: Throwable) {
                statusDetail = err.message ?: err.javaClass.simpleName
                delay(POLL_INTERVAL_MS)
                continue
            }
            when (resp.status) {
                "pending_scan" -> {
                    statusText = StatusKind.PendingScan
                }
                "pending_web_confirm" -> {
                    statusText = StatusKind.PendingConfirm
                }
                "approved" -> {
                    statusText = StatusKind.Approved
                    try {
                        val exch = api.mobileLoginExchange(
                            userId = ready.userId,
                            loginToken = ready.loginToken,
                        )
                        finished = true
                        onSuccess(exch.auth.accessToken, exch.refreshToken)
                        onDismiss()
                    } catch (err: Throwable) {
                        statusText = StatusKind.Failed
                        statusDetail = err.message ?: err.javaClass.simpleName
                    }
                    break
                }
                "rejected" -> {
                    statusText = StatusKind.Rejected
                    finished = true
                    break
                }
                "expired" -> {
                    statusText = StatusKind.Expired
                    finished = true
                    break
                }
                "consumed" -> {
                    statusText = StatusKind.Consumed
                    finished = true
                    break
                }
                else -> {
                    // 未知 status 不算错,继续轮询 —— 跟 web 端 `pending_scan`
                    // 同样兜底。
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.agents_anywhere_mobile_login_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.agents_anywhere_mobile_login_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // QR 区:失败 / 加载中 / 正常 三态。
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        qrError != null -> Text(
                            text = qrError!!,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp),
                        )
                        qrState.value == null -> CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                        else -> Image(
                            bitmap = qrState.value!!.bitmap.asImageBitmap(),
                            contentDescription = stringResource(
                                R.string.agents_anywhere_mobile_login_title,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                StatusRow(
                    kind = statusText,
                    detail = statusDetail,
                    timeoutReached = timeoutReached,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.agents_anywhere_mobile_login_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    kind: StatusKind,
    detail: String?,
    timeoutReached: Boolean,
) {
    val text = when {
        timeoutReached -> stringResource(R.string.agents_anywhere_mobile_login_status_expired)
        detail != null && kind == StatusKind.Failed ->
            stringResource(R.string.agents_anywhere_mobile_login_status_failed, detail)
        else -> when (kind) {
            StatusKind.PendingScan -> stringResource(
                R.string.agents_anywhere_mobile_login_status_pending_scan,
            )
            StatusKind.PendingConfirm -> stringResource(
                R.string.agents_anywhere_mobile_login_status_pending_confirm,
            )
            StatusKind.Approved -> stringResource(
                R.string.agents_anywhere_mobile_login_status_approved,
            )
            StatusKind.Rejected -> stringResource(
                R.string.agents_anywhere_mobile_login_status_rejected,
            )
            StatusKind.Expired -> stringResource(
                R.string.agents_anywhere_mobile_login_status_expired,
            )
            StatusKind.Consumed -> stringResource(
                R.string.agents_anywhere_mobile_login_status_consumed,
            )
            StatusKind.Failed -> detail.orEmpty()
        }
    }
    val color = when {
        timeoutReached -> MaterialTheme.colorScheme.error
        kind == StatusKind.Failed -> MaterialTheme.colorScheme.error
        kind == StatusKind.Rejected || kind == StatusKind.Expired ||
            kind == StatusKind.Consumed -> MaterialTheme.colorScheme.error
        kind == StatusKind.Approved -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (kind == StatusKind.PendingScan || kind == StatusKind.PendingConfirm ||
            kind == StatusKind.Approved
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = color,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
        )
    }
}

private enum class StatusKind {
    PendingScan,
    PendingConfirm,
    Approved,
    Rejected,
    Expired,
    Consumed,
    Failed,
}

private data class QrReady(
    val loginToken: String,
    val userId: String,
    val bitmap: android.graphics.Bitmap,
)

private fun buildConfirmUrl(baseUrl: String, userId: String, loginToken: String): String {
    val base = baseUrl.trimEnd('/')
    val sep = if (base.contains('?')) "&" else "?"
    return "$base/auth/mobile-login/confirm?userId=$userId&loginToken=$loginToken"
}