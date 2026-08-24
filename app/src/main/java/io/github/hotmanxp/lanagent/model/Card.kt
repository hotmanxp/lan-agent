// model/Card.kt — 卡片数据模型
package io.github.hotmanxp.lanagent.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * `accent` stored as ARGB Int (0xAARRGGBB) so it can be `@Serializable`
 * without a custom KSerializer for Compose `Color`. Convert at the UI edge:
 * `Color(card.accent)`.
 */
@Serializable
data class Card(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val accent: Int
)