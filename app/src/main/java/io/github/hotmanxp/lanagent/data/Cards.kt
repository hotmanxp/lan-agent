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

/**
 * 从卡片 URL 抽 `http://host:port` 形式的 baseUrl —— 给原生 Agent / 实例
 * 管理用的统一入口。
 *
 * - 去掉 query / fragment / 末尾斜杠
 * - 必须含 `scheme://` 才认为合法(`http://`、`https://`),否则返 null
 * - 含 path 时截到第一个 `/`(`http://h:9988/instances` → `http://h:9988`,
 *   `http://h:9922/m` → `http://h:9922`,`http://h:9988/` → `http://h:9988`)
 *
 * 无法解析返 null(典型场景:卡片 URL 是 `APP` / 空串 / 域名手抖漏了冒号)。
 * HomeScreen 据此决定「原生 / Web」两按钮是否显示 —— 不显示比点击再
 * 弹 snackbar 更克制。
 */
fun extractBaseUrl(url: String): String? {
    val trimmed = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
    if (trimmed.isEmpty()) return null
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val schemeOk = trimmed.substring(0, schemeEnd).let {
        it.equals("http", ignoreCase = true) || it.equals("https", ignoreCase = true)
    }
    if (!schemeOk) return null
    val afterScheme = trimmed.substring(schemeEnd + 3)
    if (afterScheme.isEmpty()) return null
    val pathStart = afterScheme.indexOf('/')
    return if (pathStart == -1) trimmed else trimmed.substring(0, schemeEnd + 3 + pathStart)
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