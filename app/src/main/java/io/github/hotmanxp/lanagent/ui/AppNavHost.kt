// ui/AppNavHost.kt — 内层导航图
//
// 路由分两类(底栏永远渲染,高亮 tab 由 MainScaffold 的显式 currentTab 状态决定):
//
//   **tab 根**(`tab/` 前缀,顺序 = 底栏从左到右):
//     tab/tasks     → 任务(原生 Agent 工作区:实例 + 会话 + 会话切换面板)
//     tab/remote    → 远程(Agents-Anywhere 官方客户端整体,自带导航)
//     tab/services  → 服务 hub(内部三张卡片,各自 push 到下面 service/* 路由)
//     tab/settings  → 设置(主题 / AA 登录配置 / 入口卡片 / 概览 / 关于)
//
//   **详情页**(底栏保留,按所属 tab 高亮):
//     service/instances                           → 局域网实例管理
//     service/ssh                                 → 局域网 SSH 服务
//     service/remote                              → 转码服务
//     scan                                        → 扫码添加
//     webview/{url}                               → 全屏 WebView
//     agent-sessions/{baseUrl}/{instanceName}     → 局域网会话列表
//     agent-session/{baseUrl}/{instanceName}/{sid} → 局域网会话详情
//     ssh-terminal/{hostId}                       → SSH 终端
//
// 0.24.0 的结构变更:底栏从五栏收成四栏,原「实例」「SSH」两个 tab 降级成
// 「服务」栏下的 `service/*` 路由,新增「远程」栏承载 AA。
//
// ⚠️ **老用户进程恢复**:0.23.0 之前的 SavedState 里存着 `Instances` / `Ssh`
// 两个已删除的 tab name。底栏状态的兜底在 [TabDestination.fromNameOrDefault];
// 路由侧无所谓 —— NavHost 存的是 destination id 字符串,导航图变化时
// Navigation 组件自己按名字重建,认不出的目的地直接丢弃。
//
// 注意:**PresentFile 文件预览不在这里** —— 它是会话面板内从右侧滑入的 overlay
// (`ui/FileViewerOverlay.kt`),由 AgentSessionPane 自己持有状态,不占路由。
//
// 参数里带 `://`、`:`、中文、空格的必须在 navigate 前 Uri.encode —— route 匹配
// 是按 `/` 切的,不编码会碎在路径段里。
package io.github.hotmanxp.lanagent.ui

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

@Composable
fun AppNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    // 跨 tab 跳转(服务 hub / 实例栏引导页)复用底栏同一条切换逻辑,
    // 否则 navigate 了但 currentTab 不动,底栏高亮就错位。
    onSelectTab: (TabDestination) -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = TabDestination.Tasks.route,
        modifier = Modifier.padding(contentPadding),
    ) {
        // ===== tab 根 =====
        // 任务栏 = 原生 Agent 工作区本身(0.15.0)。实例由
        // data/AgentInstances.kt + data/AgentWorkspacePrefs.kt 解析(上次连接的
        // → 第一个在线的),会话在屏内切换,不占路由。
        composable(TabDestination.Tasks.route) {
            TasksTabScreen(
                onOpenWeb = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
            )
        }
        // 远程栏 = Agents-Anywhere 官方客户端(0.24.2 起整份移植进来,
        // 见 lanagent/aa/ 与 ui/AaHostScreen.kt 的文件头)。0.21.0~0.24.1 那版
        // 手写复刻的代码已删除。
        composable(TabDestination.Remote.route) {
            AaHostScreen()
        }
        composable(TabDestination.Services.route) {
            ServiceHubScreen(
                onOpenInstances = { navController.navigate("service/instances") },
                onOpenSsh = { navController.navigate("service/ssh") },
                onOpenRemoteServices = { navController.navigate("service/remote") },
                onOpenAaTerminal = { navController.navigate("service/aa-terminal") },
                onOpenAaInstances = { navController.navigate("service/aa-instances") },
            )
        }
        composable(TabDestination.Settings.route) {
            // 设置栏带「入口卡片」区块(0.15.0 从任务栏搬来)与 AA 登录配置
            // (0.24.0),所以它需要扫码 / 打开网页 / 启动原生 Agent / 三个
            // AA 子页的导航动作。
            SettingsScreen(
                onScan = { navController.navigate("scan") },
                onOpenUrl = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onOpenSession = { baseUrl, instanceName, sid ->
                    navController.navigate(
                        "agent-session/${Uri.encode(baseUrl)}/${Uri.encode(instanceName)}/${Uri.encode(sid)}"
                    )
                },
            )
        }

        // ===== 「服务」栏下的三个子页 =====
        composable("service/instances") {
            InstancesTabScreen(
                onBack = { navController.popBackStack() },
                onOpenUrl = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onOpenSessions = { instanceBaseUrl, instanceName ->
                    navController.navigate(
                        "agent-sessions/${Uri.encode(instanceBaseUrl)}/${Uri.encode(instanceName)}"
                    )
                },
                // 入口卡片在设置栏,所以「还没有实例管理器」的引导指向设置。
                onGoSettings = { onSelectTab(TabDestination.Settings) },
            )
        }
        composable("service/ssh") {
            SshHostListScreen(
                onBack = { navController.popBackStack() },
                // 详情页照常 popBackStack:叠在 service/ssh 之上,返回两层
                // 分别落回 SSH 主机列表和服务 hub,底栏始终高亮服务栏。
                onOpenWebview = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onOpenTerminal = { host ->
                    navController.navigate("ssh-terminal/${Uri.encode(host.id)}")
                },
            )
        }
        composable("service/aa-terminal") {
            AaTerminalScreen(onBack = { navController.popBackStack() })
        }
        composable("service/aa-instances") {
            RemoteInstancesScreen(onBack = { navController.popBackStack() })
        }
        composable("service/remote") {
            RemoteServicesScreen(
                onBack = { navController.popBackStack() },
                onOpenUrl = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
            )
        }

        // ===== 详情页(其他) =====
        composable("scan") {
            ScanQrScreen(
                onScanned = { url ->
                    // Pop the scan screen first so a back press from the
                    // WebView lands on the tab that opened it, not on the
                    // (now-finished) scanner.
                    navController.popBackStack()
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onBack = { navController.popBackStack() },
            )
        }
        // 原生 Agent 会话列表(0.9.0)。baseUrl 里带 "://" 和 ":",instanceName
        // 可能含空格/中文 —— 两者都必须 Uri.encode。
        composable(
            route = "agent-sessions/{baseUrl}/{instanceName}",
            arguments = listOf(
                navArgument("baseUrl") { type = NavType.StringType },
                navArgument("instanceName") { type = NavType.StringType },
            )
        ) { entry ->
            val baseUrl = Uri.decode(entry.arguments?.getString("baseUrl").orEmpty())
            val instanceName = Uri.decode(entry.arguments?.getString("instanceName").orEmpty())
            AgentSessionsScreen(
                baseUrl = baseUrl.ifBlank { "http://127.0.0.1:9201" },
                instanceName = instanceName,
                onBack = { navController.popBackStack() },
                onOpenSession = { sid ->
                    navController.navigate(
                        "agent-session/${Uri.encode(baseUrl)}/${Uri.encode(instanceName)}/${Uri.encode(sid)}"
                    )
                },
            )
        }
        // 原生 Agent 会话详情(0.9.0)。任务栏的「进行中」行也直接落到这里。
        composable(
            route = "agent-session/{baseUrl}/{instanceName}/{sid}",
            arguments = listOf(
                navArgument("baseUrl") { type = NavType.StringType },
                navArgument("instanceName") { type = NavType.StringType },
                navArgument("sid") { type = NavType.StringType },
            )
        ) { entry ->
            val baseUrl = Uri.decode(entry.arguments?.getString("baseUrl").orEmpty())
            val instanceName = Uri.decode(entry.arguments?.getString("instanceName").orEmpty())
            val sid = Uri.decode(entry.arguments?.getString("sid").orEmpty())
            AgentSessionScreen(
                baseUrl = baseUrl.ifBlank { "http://127.0.0.1:9201" },
                instanceName = instanceName,
                sessionId = sid,
                onBack = { navController.popBackStack() },
                onOpenWeb = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
            )
        }
        // SSH 终端 + 快捷命令(0.13.0)。只传 hostId,主机凭证由屏内从
        // DataStore 现取 —— 密码不能进导航参数(会进 back stack 状态)。
        composable(
            route = "ssh-terminal/{hostId}",
            arguments = listOf(navArgument("hostId") { type = NavType.StringType })
        ) { entry ->
            val hostId = Uri.decode(entry.arguments?.getString("hostId").orEmpty())
            SshTerminalScreen(
                hostId = hostId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "webview/{url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { entry ->
            val raw = entry.arguments?.getString("url").orEmpty()
            val decoded = Uri.decode(raw)
            WebViewScreen(
                url = decoded.ifBlank { "about:blank" },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
