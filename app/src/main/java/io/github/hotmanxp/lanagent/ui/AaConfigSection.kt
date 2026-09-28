// ui/AaConfigSection.kt — 设置栏的 AA 登录配置区块(0.24.0)
//
// 内容分四块,按「用户实际要做什么的顺序」排,不是按实现顺序:
//
//   1. **服务器地址** —— 没配就什么都干不了,所以排最前
//   2. **登录态** —— 已登录就显示是谁 + 退出按钮;没登录显示三条登录路径
//   3. **设备** —— 接入 / 管理远程设备
//
// ## 三条登录路径为什么都要
//
// - **Web 登录(主)**:唯一能完成「全新安装首次登录」的路。OAuth 授权码 + PKCE,
//   见 `data/AaWebLogin.kt`。
// - **手动粘贴 token(兜底)**:内网自建 server 常见,或者 Web 授权页被 SSO 挡了。
//   没有它这两类用户进不去「远程」栏。
// - **扫码换 token(快捷)**:已登录设备之间快速换 token。**它要求请求本身带
//   Bearer token**(0.21.0 起),所以对未登录设备必然失败 —— 按钮因此只在
//   已登录时启用,不制造「点了没反应」的困惑。
//
// ## 登录态以谁为准
//
// 显示的 userId / email / role 一律取 `GET /auth/me` 的响应,不信任本地存的那份
// —— 服务端可能改过角色 / 邮箱,本地那份是登录瞬间的快照。拉不到就退回本地
// 存的 userId,并标出「未验证」。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AaMeResponse
import io.github.hotmanxp.lanagent.data.SecureTokenStore
import kotlinx.coroutines.launch

@Composable
fun AaConfigSection(
    onOpenWebLogin: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    val context = LocalContext.current
    val rt = rememberAaRuntime()
    val scope = rememberCoroutineScope()
    val baseUrlOverridden by rt.prefs.baseUrlOverriddenFlow.collectAsState(initial = false)

    // 表单态与已存值分离:输入框回显 prefs,但用户正在敲的内容不能被 prefs 的
    // 异步回写冲掉(同 AaScreen 旧版的 formBaseUrl 做法)。
    var formBaseUrl by remember(rt.prefs) { mutableStateOf(rt.baseUrl) }
    var formToken by remember(rt.prefs) { mutableStateOf("") }
    var showManualToken by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf<AaMeResponse?>(null) }
    var meError by remember { mutableStateOf<String?>(null) }

    // **首帧 prefs 还没回来** —— baseUrlFlow 是异步的,`remember` 初始化时拿到的
    // 是空串。不补这一下,输入框会一直空着而「保存」按钮因为
    // `"" != 实际值` 一直可点 —— 用户手一抖就把服务器地址存成空的了
    // (0.24.1 真机截图里就是这个状态)。
    // 只在表单为空时回填,用户正在敲的内容不会被冲掉。
    LaunchedEffect(rt.baseUrl) {
        if (formBaseUrl.isBlank() && rt.baseUrl.isNotBlank()) formBaseUrl = rt.baseUrl
    }

    // 登录态:token 一变就重新问一次 server。`meError` 非空 = 拿不到权威信息,
    // 展示层据此说明「显示的是本地快照」。
    LaunchedEffect(rt.accessToken, rt.baseUrl) {
        me = null
        meError = null
        if (!rt.configured || !rt.authenticated) return@LaunchedEffect
        runCatching { rt.api.me() }
            .onSuccess { me = it }
            .onFailure { meError = it.message }
    }

    fun signOut() {
        SecureTokenStore.get(context).clearAll()
        AaSessionHolder.stopAll()
        formToken = ""
        me = null
    }

    SettingsCard(title = stringResource(R.string.settings_section_agents_anywhere)) {
        // ── 1. 服务器地址 ──
        OutlinedTextField(
            value = formBaseUrl,
            onValueChange = { formBaseUrl = it },
            label = { Text(stringResource(R.string.agents_anywhere_field_base_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (rt.prefs.hasBuildConfigDefaults && !baseUrlOverridden) {
            Text(
                text = stringResource(R.string.agents_anywhere_local_props_active),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Button(
            onClick = { scope.launch { rt.prefs.setBaseUrl(formBaseUrl) } },
            enabled = formBaseUrl.trim() != rt.baseUrl.trim(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.agents_anywhere_save))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // ── 2. 登录态 / 登录入口 ──
        if (rt.authenticated) {
            MeBlock(me = me, meError = meError, onSignOut = ::signOut)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenWebLogin, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.aa_web_login_title), fontSize = 13.sp)
                }
                TextButton(onClick = { showManualToken = !showManualToken }) {
                    Text(stringResource(R.string.aa_manual_token), fontSize = 13.sp)
                }
            }
        } else {
            Button(onClick = onOpenWebLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.aa_web_login_start))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { showManualToken = !showManualToken }) {
                    Text(stringResource(R.string.aa_manual_token), fontSize = 13.sp)
                }
                // 扫码换 token 要求请求自带 Bearer token,未登录时点了必然失败,
                // 所以这里置灰而不是给一个必然报错的按钮。
                TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.aa_qr_swap_token), fontSize = 13.sp)
                }
            }
        }

        if (showManualToken) {
            ManualTokenBlock(
                value = formToken,
                onValueChange = { formToken = it },
                onSave = {
                    scope.launch {
                        rt.prefs.setAccessToken(formToken.trim())
                        formToken = ""
                        showManualToken = false
                    }
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // ── 3. 设备 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onOpenDevices, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.aa_devices), fontSize = 13.sp)
            }
            OutlinedButton(onClick = onOpenPairing, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.aa_devices_add), fontSize = 13.sp)
            }
        }
    }
}

/** 已登录态:userId / 邮箱 / 角色 + 退出。 */
@Composable
private fun MeBlock(me: AaMeResponse?, meError: String?, onSignOut: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = me?.userId ?: "已登录",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        val lines = buildList {
            me?.email?.takeIf { it.isNotBlank() }?.let { add(it) }
            me?.displayName?.takeIf { it.isNotBlank() }?.let { add(it) }
            me?.role?.takeIf { it.isNotBlank() }?.let { add("role=$it") }
        }
        if (lines.isNotEmpty()) {
            Text(
                text = lines.joinToString(" · "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (meError != null) {
            Text(
                text = stringResource(R.string.aa_me_unverified),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSignOut, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.aa_sign_out), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 手动粘贴 accessToken —— 首次登录 / 内网自建 server / Web 授权页被挡时的兜底。 */
@Composable
private fun ManualTokenBlock(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.agents_anywhere_field_access_token)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Spacer(Modifier.width(4.dp))
            Button(onClick = onSave, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.agents_anywhere_save))
            }
        }
    }
}
