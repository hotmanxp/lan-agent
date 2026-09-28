// ui/AaSessionScreen.kt — AA 远程会话详情(0.24.0 新增路由 aa-session/{sid})
//
// 0.24.0 之前这是 `AgentsAnywhereScreen` 里的屏内状态(`selectedSessionId` 非空
// 就渲染 `SessionPane`)。现在提成独立路由,好处和局域网 Agent 会话一致:返回栈
// 正常、切走 tab 能 `saveState` 保住 timeline 滚动位置与未发送草稿。
//
// 绘制全部委托给 `SessionPane`(在 AaSessionViews.kt),本屏只管:
//   1. 进屏时开 WS(经 [AaSessionHolder],状态跨路由存活)
//   2. 退屏时断 WS 但**保留数据** —— 返回列表时气泡还在
//   3. interrupt 动作
//
// 标题:真正的会话标题由 `SessionHeader` 从 server 推来的 `sessionMeta.title`
// 渲染(它自己就在页面顶部),TopAppBar 这里只放一个静态「会话详情」—— 两处
// 都显示标题是重复。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hotmanxp.lanagent.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AaSessionScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val rt = rememberAaRuntime()

    // remember 的是**引用**而不是内容 —— holder 已保证同一 sessionId 拿到同一
    // 个对象,这里只是别每次重组都去 map 里查一遍。真正驱动重组的是
    // `AgentsAnywhereSessionState` 内部的 mutableState 字段。
    val state = remember(sessionId) { AaSessionHolder.stateOf(sessionId) }

    LaunchedEffect(sessionId, rt.baseUrl, rt.accessToken) {
        if (sessionId.isNotBlank() && rt.configured) {
            AaSessionHolder.open(
                sessionId = sessionId,
                scope = rt.scope,
                client = rt.client,
                api = rt.api,
                baseUrl = rt.baseUrl,
                accessToken = rt.accessToken,
            )
        }
    }

    // 只断连接,不清 items —— 用户返回列表再进来,气泡不该空。
    DisposableEffect(sessionId) {
        onDispose { AaSessionHolder.stop(sessionId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agents_anywhere_session_title)) },
                navigationIcon = { WbBackIcon(onBack) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStatusBar(state = state)
            SessionPane(
                state = state,
                api = rt.api,
                onInterrupt = {
                    rt.scope.launch {
                        runCatching { rt.api.interrupt(sessionId) }
                    }
                },
            )
        }
    }
}
