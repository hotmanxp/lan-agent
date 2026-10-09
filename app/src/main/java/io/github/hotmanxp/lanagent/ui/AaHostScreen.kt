// ui/AaHostScreen.kt — 把 AA 官方客户端挂进 lan-agent 的「远程」栏(0.24.2)
//
// ## 为什么是「挂载」而不是「重写」
//
// 0.24.0 那版 AA 集成是照着 server 源码手写复刻的,结果在真实 server 上连续踩坑:
// 漏 `/api/v2` 前缀(被 SPA fallback 吞成 index.html)、dashboard WS 路径与线上
// 不一致、snapshot 反序列化失败导致会话页一片空白。0.24.2 起改为把
// **Agents-Anywhere 官方 Android 客户端**整份搬进 `lanagent/aa/`(见该目录文件头),
// 本屏只做一件事:给它一个可用的挂载点。
//
// ## 与上游的已知差异(6 个文件,全是「删掉远程终端」)
//
// lan-agent 已有自己的终端(Services → 局域网 SSH,xterm.js),AA 那套基于
// termux `TerminalView` 的远程终端就被摘掉了,改放「服务」栏与局域网终端并排。
// 受影响:`SessionDetailScreen` / `SessionAgentFilesScreen` / `AgentsAnywhereApp`
// / `AgentsAnywhereNavHost` / `HomeScreen` / `AppDestination`。
// 以后从 AA 同步更新时,这几个文件会冲突,其余文件可直接覆盖。
//
// ## 凭据
//
// AA 原本把 accessToken 存**明文** SharedPreferences。本项目已有
// EncryptedSharedPreferences + Keystore 的实现,所以没搬那份明文存储,而是让
// AA 的 `AuthSessionStore` 指向它(见该文件的 0.24.2 注释)。
package io.github.hotmanxp.lanagent.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.hotmanxp.lanagent.aa.app.AgentsAnywhereApp
import io.github.hotmanxp.lanagent.aa.feature.auth.WebLoginViewModel
import io.github.hotmanxp.lanagent.aa.feature.update.AppUpdateViewModel
import io.github.hotmanxp.lanagent.aa.ui.designsystem.AALanguageMode
import io.github.hotmanxp.lanagent.aa.ui.screens.home.HomeSidebarViewMode
import io.github.hotmanxp.lanagent.data.ThemeMode
import io.github.hotmanxp.lanagent.data.themeModeFlow

/**
 * AA OAuth 回调深链,由 `MainActivity` 在 onCreate / onNewIntent 写入。
 *
 * 之所以挂 CompositionLocal 而不是顺着 MainScaffold → AppNavHost 一路传参:
 * 那条链路上已经有 `onSelectTab` 一个跨栏回调,再加一个只在「远程」栏消费的
 * 单向数据流纯属噪音。放顶层跟 [io.github.hotmanxp.lanagent.ui.LocalMermaidEnabled]、
 * [io.github.hotmanxp.lanagent.ui.LocalMessageFontScale] 同一位置同一写法。
 *
 * 传的是 state 本身而不是 Uri:消费方(AaHostScreen)要回写 null 表示「已消费」,
 * 避免同一个回调被处理两次 —— 官方 AA 是用 `onOAuthCallbackConsumed` 回调做的,
 * 这里直接给可写 state,少一层转发。
 */
val LocalOauthCallbackUri = staticCompositionLocalOf<MutableState<Uri?>> {
    error("LocalOauthCallbackUri 只应由 MainActivity 提供")
}

/**
 * 底栏「远程」栏的根屏 = AA 官方客户端本体。
 *
 * `AgentsAnywhereApp` 自带导航、主题、登录态与会话详情,lan-agent 这边不插手,
 * 只提供 `remember` 的两个 ViewModel(AA 原本是在自己的 Activity 里创建的)。
 *
 * 外观跟随 lan-agent 的主题设置:深色用 AA 的深色色板,浅色用浅色色板;AA 默认
 * `appearanceMode = "system"`,而 lan-agent 有自己的 `ThemeMode`,两者语义一致,
 * 这里直接把存储的枚举名传下去。
 */
@Composable
fun AaHostScreen() {
    val context = LocalContext.current
    val themeMode by context.themeModeFlow().collectAsState(initial = ThemeMode.System)
    val oauthCallback = LocalOauthCallbackUri.current
    val webLoginViewModel = remember {
        WebLoginViewModel(context.applicationContext as android.app.Application)
    }
    val appUpdateViewModel = remember {
        AppUpdateViewModel(context.applicationContext as android.app.Application)
    }

    AgentsAnywhereApp(
        // lan-agent 的 ThemeMode 落盘值就是 "system" / "light" / "dark",
        // 与 AA 的 appearanceMode 取值完全一致,直接透传,两个 App 的外观开关联动。
        appearanceMode = themeMode.storageKey,
        languageMode = AALanguageMode.System,
        sidebarViewMode = HomeSidebarViewMode.Project,
        webLoginViewModel = webLoginViewModel,
        appUpdateViewModel = appUpdateViewModel,
        // OAuth 回调深链(`agents-anywhere://oauth/callback`)。AA 端只在
        // `currentDestination == ServerSetup` 且登录 WebView 还开着时才消费,
        // 消费完立刻回写 null —— 见 AaHostScreen 文件里对 LocalOauthCallbackUri 的说明。
        oauthCallbackUri = oauthCallback.value,
        onOAuthCallbackConsumed = { oauthCallback.value = null },
    )
}
