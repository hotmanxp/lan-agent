// data/RemoteServiceProbe.kt — 远程服务存活探测
//
// 判据很松:**拿到任何 HTTP 响应就算在线**(含 401/403/404/500)。原因:
// 这一栏要回答的是「这台机器上那个服务还在跑吗」,不是「这个 URL 能不能打开」。
// 控制台首页要登录(401)、探活路径不存在(404)、服务自身 500 —— 三种情况都
// 证明进程活着、端口在听。只有连接被拒 / 超时 / DNS 失败才算离线。
//
// 超时必须短(2s):列表页会并发探 N 个服务,一个挂掉的 IP 用默认 10s 超时
// 会把整屏状态点拖住。
package io.github.hotmanxp.lanagent.data

import io.github.hotmanxp.lanagent.model.RemoteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object RemoteServiceProbe {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 从 [RemoteService.url] 的 origin 拼探活地址:
     * `http://h:8780/m?x=1` + probePath `/` → `http://h:8780/`。
     * origin 解析不出来(手抖漏了 scheme)时直接用原 url 兜底。
     */
    fun probeUrlOf(service: RemoteService): String {
        val base = extractBaseUrl(service.url) ?: return service.url
        val path = service.probePath.trim().ifBlank { "/" }
        return base.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
    }

    /** true = 端口有服务在应答。任何异常一律 false。 */
    suspend fun probe(service: RemoteService): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(probeUrlOf(service))
                    .get()
                    .build()
            ).execute().use { resp -> resp.code in 100..599 }
        }.getOrDefault(false)
    }
}
