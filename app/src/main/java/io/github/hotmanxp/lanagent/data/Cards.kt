// data/Cards.kt — 硬编码入口列表(改 IP 改这里)
package io.github.hotmanxp.lanagent.data

import androidx.compose.ui.graphics.Color
import io.github.hotmanxp.lanagent.model.Card

val defaultCards: List<Card> = listOf(
    Card(
        id = "opencc-default",
        title = "opencc-web Agent",
        subtitle = "192.168.1.100:8101/m",
        url = "http://192.168.1.100:8101/m",
        accent = Color(0xFF1677FF)
    ),
    Card(
        id = "opencc-alt",
        title = "opencc-web 实验实例",
        subtitle = "192.168.1.101:8101/m",
        url = "http://192.168.1.101:8101/m",
        accent = Color(0xFF52C41A)
    ),
    Card(
        id = "opencc-dashboard",
        title = "opencc-web Dashboard",
        subtitle = "192.168.1.100:8101/dashboard",
        url = "http://192.168.1.100:8101/dashboard",
        accent = Color(0xFF722ED1)
    )
)
