// ui/AppNavHost.kt — 内层导航图
//
// 路由分两类(底栏显隐完全由这个前缀决定,见 MainScaffold):
//
//   **tab 根**(底栏可见,`tab/` 前缀,顺序 = 底栏从左到右):
//     tab/tasks     → 任务(卡片入口 + 跨实例进行中任务)
//     tab/instances → 实例管理(暂存超时/停止/删除,baseUrl 从卡片里认)
//     tab/ssh       → SSH 主机列表
//     tab/services  → 远程服务(视频插帧控制台等)
//     tab/settings  → 设置(主题 / 概览 / 关于)
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
) {
    NavHost(
        navController = navController,
        startDestination = TabDestination.Tasks.route,
        modifier = Modifier.padding(contentPadding),
    ) {
        // ===== tab 根 =====
        composable(TabDestination.Tasks.route) {
            TasksTabScreen(
                onCardClick = { card ->
                    navController.navigate("webview/${Uri.encode(card.url)}")
                },
                onScanClick = {
                    navController.navigate("scan")
                },
                onOpenSession = { baseUrl, instanceName, sid ->
                    navController.navigate(
                        "agent-session/${Uri.encode(baseUrl)}/${Uri.encode(instanceName)}/${Uri.encode(sid)}"
                    )
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
                onGoTasks = { navController.goToTab(TabDestination.Tasks) },
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
            SettingsScreen()
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
