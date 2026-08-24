// data/Cards.kt — 硬编码入口列表(首次启动种子;用户编辑后由 CardRepository 接管)
package io.github.hotmanxp.lanagent.data

import io.github.hotmanxp.lanagent.model.Card

private const val HOST = "192.168.101.69"

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