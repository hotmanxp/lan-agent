// EngineDownloadPrefs.kt — 「引擎下载源」的持久化。
//
// ## 存的是什么
//
// 一个 manifest 的完整地址,例如:
//
//     http://192.168.101.69:8766/engine-manifest.json
//
// App 侧只认这一个地址:清单里写着 zip 的文件名与 sha256,App 按清单下载。
// **换下载源不用改 App** —— 把 manifest 放到哪儿、把 zip 放哪儿,都无所谓,
// 清单自己会说。当前用的是局域网上的 Mac(见 tools/serve-engine.sh);
// 将来换 GitHub release,只要那边的 manifest 长一个样,这里填新地址就行。
//
// ## 默认值怎么来
//
// 入口卡片本来就指向局域网里那台跑 opencc-web 的机器(192.168.101.69:9201),
// 那个主机名是现成的 —— 引擎包就默认从同一台机器的 8766 端口下载。
// 用户可以在设置页改。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.engineDownloadDataStore by preferencesDataStore(name = "lan_agent_engine_download")

/** 引擎下载包静态服务的默认端口(见 tools/serve-engine.sh)。 */
const val ENGINE_DOWNLOAD_DEFAULT_PORT = 8766

object EngineDownloadPrefs {

    private val KEY_SOURCE = stringPreferencesKey("source_url")

    /** 下载源(manifest 地址);没设过返回空串。 */
    fun flow(context: Context): Flow<String> =
        context.engineDownloadDataStore.data.map { prefs -> prefs[KEY_SOURCE].orEmpty() }

    /** 当前下载源(同步读一次)。 */
    fun current(context: Context): String = runBlocking {
        context.engineDownloadDataStore.data.map { it[KEY_SOURCE].orEmpty() }.first()
    }

    /** 保存下载源。空串表示清空(回到默认)。 */
    suspend fun saveSource(context: Context, url: String) {
        context.engineDownloadDataStore.edit { prefs ->
            prefs[KEY_SOURCE] = url.trim()
        }
    }

    /**
     * 由入口卡片的主机推出来的默认下载源。
     *
     * 入口卡片指向局域网里那台跑 opencc-web 的机器,引擎包默认从**同一台机器**
     * 的 8766 端口下载 —— 用户不用手填 IP。
     *
     * 卡片里没有 http(s) 地址就返回空串(让用户自己填)。
     */
    fun defaultSource(cardUrls: List<String>): String {
        for (raw in cardUrls) {
            val host = raw.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
            if (host.isNotBlank() && host.contains('.')) {
                return "http://$host:$ENGINE_DOWNLOAD_DEFAULT_PORT/engine-manifest.json"
            }
        }
        return ""
    }
}
