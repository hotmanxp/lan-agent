// model/RemoteService.kt — 「远程服务」条目数据模型
//
// 与 Card 的区别:Card 是「入口卡片」(可能指向 zai 实例,能拉起原生 Agent);
// RemoteService 是「局域网里的一个自建服务」—— 视频插帧控制台、临时起的
// 调试页之类。语义上只做两件事:探活(端口通不通)+ 点开看 WebView。
package io.github.hotmanxp.lanagent.model

import kotlinx.serialization.Serializable

/**
 * 一条远程服务。
 *
 * [accent] 与 [Card.accent] 同一套约定:ARGB Int,UI 侧 `Color(accent)`,
 * 保证 @Serializable 不需要给 Compose Color 写 KSerializer。
 *
 * [probePath] 探活附加在 [url] 的 **origin** 后面(默认 `/`)。留空 = 探
 * `/`。之所以不直接探 [url],是因为有些控制台的首页路由带鉴权/重定向,
 * 而 `/api/health` 这类浅路由才是稳定的存活信号。
 */
@Serializable
data class RemoteService(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val url: String,
    val accent: Int = 0xFF1677FF.toInt(),
    val probePath: String = "",
)
