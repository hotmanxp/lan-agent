// data/RemoteServicesRepository.kt — 远程服务清单 DataStore 持久化
//
// 和 CardRepository / SshRepository 同款形态:一个 JSON 串整体读写,
// 首次启动落到 [defaultRemoteServices] 种子。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hotmanxp.lanagent.model.RemoteService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.remoteServicesDataStore by preferencesDataStore(name = "lan_agent_remote_services")
private val REMOTE_SERVICES_KEY = stringPreferencesKey("remote_services_json")
private val remoteServicesSerializer = ListSerializer(RemoteService.serializer())
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 本机(Mac mini)在局域网里的地址。和 data/Cards.kt 的 HOST 保持一致。 */
private const val HOST = "192.168.101.69"

/**
 * 种子服务清单。
 *
 * 目前只有一条 —— 视频插帧控制台(`~/soft/vt-ui`,VT-FRC 硬件补帧,默认 8780)。
 * 这是「远程服务」这个 tab 的由来:它不是 zai 实例、没有 Agent 会话,
 * 卡片列表放它不合适(启动原生 / 打开网页双按钮里「启动原生」永远失败),
 * 所以单开一栏。后续加服务直接在设置里手动加,不用改代码。
 */
val defaultRemoteServices: List<RemoteService> = listOf(
    RemoteService(
        id = "seed-vt-ui",
        name = "视频插帧控制台",
        subtitle = "VT-FRC 硬件补帧 · $HOST:8780",
        url = "http://$HOST:8780/",
        accent = 0xFF722ED1.toInt(),
    ),
)

/** 服务清单 Flow;首次启动(未落盘)返回 [defaultRemoteServices]。 */
fun Context.remoteServicesFlow(): Flow<List<RemoteService>> = remoteServicesDataStore.data.map { prefs ->
    val raw = prefs[REMOTE_SERVICES_KEY]
    if (raw.isNullOrBlank()) {
        defaultRemoteServices
    } else {
        runCatching { json.decodeFromString(remoteServicesSerializer, raw) }
            .getOrElse { defaultRemoteServices }
    }
}

/** 整体覆盖写入。写入失败不会污染上一份(DataStore edit 是原子的)。 */
suspend fun Context.saveRemoteServices(services: List<RemoteService>) {
    remoteServicesDataStore.edit { prefs ->
        prefs[REMOTE_SERVICES_KEY] = json.encodeToString(remoteServicesSerializer, services)
    }
}

/** 恢复成种子清单(清掉用户增删改)。 */
suspend fun Context.resetRemoteServices() {
    remoteServicesDataStore.edit { it.remove(REMOTE_SERVICES_KEY) }
}
