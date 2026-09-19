// ui/AppNavHost.kt — 内层导航图
//
// 路由分两类(底栏永远渲染,高亮 tab 由 MainScaffold 的显式 currentTab 状态决定):
//
//   **tab 根**(`tab/` 前缀,顺序 = 底栏从左到右):
//     tab/tasks     → 任务(原生 Agent 工作区:实例 + 会话 + 会话切换面板)
//     tab/instances → 实例管理(暂存超时/停止/删除,baseUrl 从卡片里认)
//     tab/ssh       → SSH 主机列表
//     tab/services  → 远程服务(视频插帧控制台等)
//     tab/settings  → 设置(主题 / 入口卡片 / 概览 / 关于)
//
//   **详情页**(底栏隐藏):
//     scan                                   → 扫码添加
//     webview/{url}                          → 全屏 WebView
//     agent-sessions/{baseUrl}/{instanceName} → 会话列表
//     agent-session/{baseUrl}/{instanceName}/{sid} → 会话详情
//     ssh-terminal/{hostId}                   → SSH 终端
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
    // 跨 tab 跳转(实例栏引导页「去添加」)复用底栏同一条切换逻辑,
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
        composable(TabDestination.Instances.route) {
            InstancesTabScreen(
                onOpenUrl = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onOpenSessions = { instanceBaseUrl, instanceName ->
                    navController.navigate(
                        "agent-sessions/${Uri.encode(instanceBaseUrl)}/${Uri.encode(instanceName)}"
                    )
                },
                // 入口卡片搬到设置栏后,「还没有实例管理器」时的引导指向设置。
                onGoSettings = { onSelectTab(TabDestination.Settings) },
            )
        }
        composable(TabDestination.Ssh.route) {
            SshHostListScreen(
                onBack = null,
                // 不再 popBackStack:这一屏已经是 tab 根,pop 掉它等于把
                // SSH 栏从返回栈里删了 —— 从 WebView 返回会落到「任务」栏。
                onOpenWebview = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onOpenTerminal = { host ->
                    navController.navigate("ssh-terminal/${Uri.encode(host.id)}")
                },
            )
        }
        composable(TabDestination.Services.route) {
            RemoteServicesScreen(
                onOpenUrl = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
            )
        }
        composable(TabDestination.Settings.route) {
            // 设置栏现在带「入口卡片」区块(0.15.0 从任务栏搬来),所以它也需要
            // 扫码 / 打开网页 / 启动原生 Agent 三个导航动作。
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

        // ===== 详情页(底栏自动隐藏) =====
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

/** 跨 tab 跳转 —— 语义等同点底栏,不叠新层。 */
private fun NavHostController.goToTab(target: TabDestination) {
    navigate(target.route) {
        popUpTo("tab/tasks") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
