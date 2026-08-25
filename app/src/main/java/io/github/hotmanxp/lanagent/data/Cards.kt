// data/Cards.kt — 硬编码入口列表(首次启动种子;用户编辑后由 CardRepository 接管)
package io.github.hotmanxp.lanagent.data

import io.github.hotmanxp.lanagent.model.Card

private const val HOST = "192.168.101.69"

/**
 * 从卡片列表里识别「实例管理器入口」:URL 路径以 `/instances` 结尾的卡片。
 *
 * 返回去掉路径与尾部斜杠后的 baseURL,形如 `http://192.168.101.69:9201`,
 * 给原生 InstancesScreen 用作 API 根。没找到返回 null,UI 应引导用户先配置卡片。
 */
fun findManagerBaseUrl(cards: List<Card>): String? {
    val manager = cards.firstOrNull { c ->
        val path = c.url.substringBefore('?').substringBefore('#').trimEnd('/')
        path.endsWith("/instances")
    } ?: return null
    val url = manager.url.substringBefore('?').substringBefore('#').trimEnd('/')
    // url = "http://host:port/instances" → base = "http://host:port"
    val base = url.substringBefore("/instances")
    return if (base.endsWith("/")) base.dropLast(1) else base
}

val defaultCards: List<Card> = listOf(
    Card(
        id = "seed-instances",
        title = "Instances 实例管理",
        subtitle = "$HOST:9201/instances",
        url = "http://$HOST:9201/instances",
        accent = 0xFF1677FF.toInt()
    ),
    Card(
        id = "seed-opencc-web",
        title = "opencc-web",
        subtitle = "$HOST:9988",
        url = "http://$HOST:9988/",
        accent = 0xFF52C41A.toInt()
    ),
    Card(
        id = "seed-opencc-web-dsh",
        title = "opencc-web-dsh",
        subtitle = "$HOST:9977",
        url = "http://$HOST:9977/",
        accent = 0xFF722ED1.toInt()
    ),
    Card(
        id = "seed-code-opencc",
        title = "code-opencc",
        subtitle = "$HOST:9966",
        url = "http://$HOST:9966/",
        accent = 0xFFFA8C16.toInt()
    ),
    Card(
        id = "seed-code-dash",
        title = "code-dash",
        subtitle = "$HOST:9955",
        url = "http://$HOST:9955/",
        accent = 0xFF13C2C2.toInt()
    )
)