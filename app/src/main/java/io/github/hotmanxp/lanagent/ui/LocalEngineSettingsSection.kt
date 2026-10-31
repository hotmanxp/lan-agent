// ui/LocalEngineSettingsSection.kt — 设置页里的「本机引擎」卡片。
//
// 干的事只有一件:让用户填「模型服务地址 / 模型名 / 密钥」,存进 DataStore,
// 下次起引擎时物化成引擎认得的配置文件(见 engine/EngineCredentials.kt)。
//
// 为什么不自动从桌面同步:桌面上那份 ~/.zai 里有几十个插件、几十个 token,
// 手机上用不上也不该带下去。用户手填三个值是最低成本、最可控的做法。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R
import io.github.hotmanxp.lanagent.engine.EngineCredentials
import io.github.hotmanxp.lanagent.engine.EngineCredentialsStore
import io.github.hotmanxp.lanagent.engine.EngineInstaller
import io.github.hotmanxp.lanagent.engine.EnginePaths
import io.github.hotmanxp.lanagent.engine.EngineStatus
import kotlinx.coroutines.launch

/**
 * 「本机引擎」设置卡片。
 *
 * 三块:
 *   1. 引擎状态 —— APK 里有没有引擎、有没有在跑(引擎没装的话整块只显示提示)。
 *   2. 模型接入 —— provider / baseUrl / model / apiKey。
 *   3. 保存 —— 写 DataStore,并立刻物化一次配置文件(不用等下次重启引擎)。
 */
@Composable
fun LocalEngineSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val installed = remember { EngineInstaller.isInstalled(context) }
    val status = remember { EngineStatus.read(context) }
    val engineAlive = status?.isAlive() == true

    val stored by EngineCredentialsStore.flow(context)
        .collectAsState(EngineCredentials())

    // 编辑用的本地状态:进页面时用已存的值填,用户改完点保存才写回。
    var provider by remember { mutableStateOf(stored.provider) }
    var baseUrl by remember { mutableStateOf(stored.baseUrl) }
    var model by remember { mutableStateOf(stored.model) }
    var apiKey by remember { mutableStateOf(stored.apiKey) }

    SettingsCard(
        title = stringResource(R.string.settings_section_local_engine),
        subtitle = stringResource(R.string.settings_local_engine_subtitle),
    ) {
        if (!installed) {
            Text(
                text = stringResource(R.string.settings_local_engine_not_installed),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        } else {
            Text(
                text = stringResource(
                    if (engineAlive) R.string.settings_local_engine_running
                    else R.string.settings_local_engine_stopped,
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (engineAlive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = EnginePaths.baseUrl(status?.port ?: EnginePaths.DEFAULT_PORT),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        // provider —— 两态:anthropic(原生 Anthropic 协议)/ openai(兼容网关)。
        Text(
            text = stringResource(R.string.settings_local_engine_provider),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderChip(
                label = "anthropic",
                selected = provider == EngineCredentials.PROVIDER_ANTHROPIC,
                onClick = { provider = EngineCredentials.PROVIDER_ANTHROPIC },
            )
            Spacer(Modifier.width(8.dp))
            ProviderChip(
                label = "openai",
                selected = provider == EngineCredentials.PROVIDER_OPENAI,
                onClick = { provider = EngineCredentials.PROVIDER_OPENAI },
            )
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.settings_local_engine_base_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(stringResource(R.string.settings_local_engine_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.settings_local_engine_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch {
                    EngineCredentialsStore.save(
                        context,
                        EngineCredentials(
                            provider = provider,
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            apiKey = apiKey.trim(),
                        ),
                    )
                    // 立刻物化一次 —— 不用等下次重启引擎才生效。
                    EngineCredentialsStore.apply(context)
                }
            }) {
                Text(stringResource(R.string.settings_local_engine_save), fontSize = 13.sp)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.settings_local_engine_save_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** provider 二选一的小圆角按钮。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
    )
}
