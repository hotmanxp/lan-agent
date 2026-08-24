// model/Card.kt — 卡片数据模型
package io.github.hotmanxp.lanagent.model

import androidx.compose.ui.graphics.Color

data class Card(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val accent: Color
)
