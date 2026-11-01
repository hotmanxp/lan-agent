// ui/LocalEngineSettingsSection.kt — 设置页里的「本机引擎」卡片。
//
// 干的事有三件:
//   1. **下载引擎** —— 引擎包不再打进 APK(那会让 APK 变成 90MB+ 大包),
//      用户点按钮从「引擎下载源」拉一个 zip 下来,校验 sha256 后解到 app 私有目录。
//   2. **模型接入** —— provider / baseUrl / model / apiKey,存进 DataStore,
//      下次起引擎时物化成引擎认得的配置文件(见 engine/EngineCredentials.kt)。
//   3. **状态** —— 引擎装没装、跑没跑。
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
import io.github.hotmanxp.lanagent.data.cardsFlow
import io.github.hotmanxp.lanagent.engine.EngineBundle
import io.github.hotmanxp.lanagent.engine.EngineController
import io.github.hotmanxp.lanagent.engine.EngineCredentials
import io.github.hotmanxp.lanagent.engine.EngineCredentialsStore
import io.github.hotmanxp.lanagent.engine.EngineDownloadPrefs
import io.github.hotmanxp.lanagent.engine.EngineInstaller
import io.github.hotmanxp.lanagent.engine.EnginePaths
import io.github.hotmanxp.lanagent.engine.EngineStatus
import kotlinx.coroutines.launch

/**
 * 「本机引擎」设置卡片。
 */
@Composable
fun LocalEngineSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 引擎装没装 —— 装好后要刷新这个值(下载完成会 set)。
    var installed by remember { mutableStateOf(EngineInstaller.isInstalled(context)) }
    val status = remember { EngineStatus.read(context) }
    val engineAlive = status?.isAlive() == true
    val downloadState by EngineBundle.state.collectAsState()

    // 下载源:用户填的值优先,没填就用「上次存的」,再没有就从入口卡片的主机推。
    val cards by context.cardsFlow().collectAsState(initial = null)
    val savedSource by EngineDownloadPrefs.flow(context).collectAsState("")
    val defaultSource = remember(cards) {
        EngineDownloadPrefs.defaultSource(cards?.map { it.url } ?: emptyList())
    }
    var sourceInput by remember { mutableStateOf("") }
    val source = sourceInput.ifBlank { savedSource.ifBlank { defaultSource } }

    val stored by EngineCredentialsStore.flow(context)
        .collectAsState(EngineCredentials())

    // 编辑用的本地状态:进页面时用已存的值填,用户改完点保存才写回。
    var provider by remember { mutableStateOf(stored.provider) }
    var baseUrl by remember { mutableStateOf(stored.baseUrl) }
    var model by remember { mutableStateOf(stored.model) }
    var apiKey by remember { mutableStateOf(stored.apiKey) }

    // 保存后的反馈文案(成功 / 失败)。不给反馈的话用户点了「保存」看到界面
    // 一点变化都没有,只会觉得「保存不了」。
    var savedHint by remember { mutableStateOf<String?>(null) }
    // stringResource 只能在 composable 上下文里调 —— 先取出来,协程里用。
    val savedText = stringResource(R.string.settings_local_engine_saved)
    val incompleteText = stringResource(R.string.settings_local_engine_save_incomplete)

    SettingsCard(
        title = stringResource(R.string.settings_section_local_engine),
        subtitle = stringResource(R.string.settings_local_engine_subtitle),
    ) {
        // ── 状态 ────────────────────────────────────────────────────────────
        val running = downloadState is EngineBundle.State.Running
        when {
            running -> {
                val s = downloadState as EngineBundle.State.Running
                Text(
                    text = stringResource(R.string.settings_local_engine_downloading),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                val pct = if (s.total > 0) (s.downloaded * 100 / s.total) else 0
                Text(
                    text = "$pct% · ${s.downloaded / 1024 / 1024}MB / ${s.total / 1024 / 1024}MB",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            installed -> {
                Text(
                    text = stringResource(R.string.settings_local_engine_running)
                        .takeIf { engineAlive }
                        ?: stringResource(R.string.settings_local_engine_installed),
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
            }

            else -> {
                Text(
                    text = stringResource(R.string.settings_local_engine_not_installed),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 下载失败的原因 —— 不给提示的话用户只看到「还是没装上」。
        if (downloadState is EngineBundle.State.Failed) {
            Text(
                text = stringResource(R.string.settings_local_engine_download_failed) +
                    "：" + (downloadState as EngineBundle.State.Failed).message,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── 下载源 + 下载按钮 ───────────────────────────────────────────────
        OutlinedTextField(
            value = source,
            onValueChange = { sourceInput = it },
            label = { Text(stringResource(R.string.settings_local_engine_download_source)) },
            placeholder = { Text(stringResource(R.string.settings_local_engine_download_source_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !running && source.isNotBlank(),
                onClick = {
                    scope.launch {
                        EngineDownloadPrefs.saveSource(context, source)
                        EngineBundle.downloadAndInstall(source)
                            .onSuccess {
                                installed = true
                                // 装完直接把它拉起来 —— 否则用户得重启 App
                                // 才看得到「本机引擎」这个实例。
                                EngineController.start(context)
                            }
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (installed) R.string.settings_local_engine_redownload
                        else R.string.settings_local_engine_download,
                    ),
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.settings_local_engine_download_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 模型接入 ───────────────────────────────────────────────────────
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
                    val applied = EngineCredentialsStore.apply(context)
                    if (applied) {
                        // ⚠️ **必须重启引擎**,否则等于没保存。
                        //
                        // 引擎进程启动时就把 settings.json / .zai.json 读进内存了,
                        // 改了文件它不知道 —— 用户会看到「保存了但还是报
                        // Not logged in」,结论就是「保存不了」。
                        //
                        // node::Start 一个进程只能调一次,只能换整个进程。
                        EngineController.restart(context)
                        savedHint = savedText
                    } else {
                        savedHint = incompleteText
                    }
                }
            }) {
                Text(stringResource(R.string.settings_local_engine_save), fontSize = 13.sp)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = savedHint ?: stringResource(R.string.settings_local_engine_save_hint),
                fontSize = 11.sp,
                color = if (savedHint != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
