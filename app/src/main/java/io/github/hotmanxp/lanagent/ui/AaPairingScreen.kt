// ui/AaPairingScreen.kt — AA 设备接入(0.24.0,路由 aa-pairing)
//
// ## 流程(两步,不能颠倒)
//
//   ① 「注册并生成凭据」→ `POST /connectors` {name} → 拿到 connector + **一次性**
//      connectorToken。**token 只在这一刻返回,之后再也取不到**(server 只存哈希),
//      所以拿到就立刻落盘。
//   ② 把配对码填进来 → `POST /pairing/claim` {code,name,serverUrl,connectorId,connectorToken}
//      → 设备认领到当前账号,之后 `GET /connectors` 就能看到它。
//
// ## connectorToken 存哪
//
// 存 [io.github.hotmanxp.lanagent.data.SecureTokenStore](EncryptedSharedPreferences,
// Keystore 主密钥包装),和 accessToken 同一条路子 —— 它是凭据,不能明文进
// DataStore。见 `data/SecureTokenStore.kt`。
//
// ⚠️ **A 的 JWT 不是 B 的 token**:注册用的是**当前登录用户的 accessToken**
// (`GET /connectors` 认的是 user),claim 才把 connector 绑到该 user。把两者
// 搞混的典型症状是「注册成功但设备一直显示离线」。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.data.AaPairingClaimRequest
import io.github.hotmanxp.lanagent.data.SecureTokenStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AaPairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rt = rememberAaRuntime()

    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 第 ① 步的产物 —— 换 token 之前不能丢,否则只能重新注册。
    var registeredId by remember { mutableStateOf<String?>(null) }
    var registeredToken by remember { mutableStateOf<String?>(null) }

    fun register() {
        if (busy) return
        val deviceName = name.trim().ifBlank { "我的设备" }
        busy = true
        error = null
        status = null
        rt.scope.launch {
            runCatching { rt.api.registerConnector(deviceName) }
                .onSuccess { cred ->
                    val id = cred.connector?.id
                    val token = cred.deviceToken
                    if (id.isNullOrBlank() || token.isNullOrBlank()) {
                        error = "服务器没返回 connectorId / deviceToken"
                    } else {
                        registeredId = id
                        registeredToken = token
                        // token 一次性 —— 当场落盘,别等 claim 成功再存。
                        SecureTokenStore.get(context).putAaConnectorToken(id, token)
                        status = context.getString(
                            R.string.aa_pairing_registered,
                            cred.connector?.name ?: id,
                        )
                    }
                }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    fun claim() {
        if (busy) return
        val id = registeredId
        val token = registeredToken
        if (id == null || token == null) {
            error = "先点「注册并生成凭据」"
            return
        }
        if (code.isBlank()) {
            error = "先填配对码"
            return
        }
        busy = true
        error = null
        rt.scope.launch {
            runCatching {
                rt.api.claimConnector(
                    AaPairingClaimRequest(
                        code = code.trim(),
                        name = name.trim().ifBlank { "我的设备" },
                        serverUrl = rt.baseUrl.trim(),
                        connectorId = id,
                        connectorToken = token,
                    )
                )
            }
                .onSuccess { resp ->
                    status = context.getString(
                        R.string.aa_pairing_claimed,
                        resp.connector?.name ?: id,
                    )
                    registeredId = null
                    registeredToken = null
                    code = ""
                }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aa_pairing_title)) },
                navigationIcon = { WbBackIcon(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.aa_pairing_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.aa_pairing_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = ::register,
                enabled = !busy && rt.configured && rt.authenticated,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.aa_pairing_register))
            }

            if (registeredId != null) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.aa_pairing_code)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "connectorId: $registeredId",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = ::claim,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.aa_pairing_claim))
                }
            }

            if (status != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = status!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (error != null) {
                Text(text = error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.webview_back_cd))
            }
        }
    }
}
