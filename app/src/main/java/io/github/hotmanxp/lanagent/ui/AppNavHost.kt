// ui/AppNavHost.kt — NavHost("home" → HomeScreen, "webview/{url}" → WebViewScreen,
// "instances/{baseUrl}" → 原生 InstancesScreen,
// "agent-sessions/{baseUrl}/{instanceName}" → 原生会话列表,
// "agent-session/{baseUrl}/{sid}" → 原生会话详情)
package io.github.hotmanxp.lanagent.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onCardClick = { card ->
                    navController.navigate("webview/${Uri.encode(card.url)}")
                },
                onScanClick = {
                    navController.navigate("scan")
                },
                onInstancesClick = { baseUrl ->
                    navController.navigate("instances/${Uri.encode(baseUrl)}")
                },
                onSshHostsClick = {
                    navController.navigate("ssh-hosts")
                },
            )
        }
        composable("scan") {
            ScanQrScreen(
                onScanned = { url ->
                    // Pop the scan screen first so a back press from the
                    // WebView lands on Home, not on the (now-finished)
                    // scanner. navigate(...) here would push scan onto the
                    // back stack again and the user would have to back out
                    // twice to reach Home.
                    navController.popBackStack()
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "instances/{baseUrl}",
            arguments = listOf(navArgument("baseUrl") { type = NavType.StringType })
        ) { entry ->
            val baseUrl = Uri.decode(entry.arguments?.getString("baseUrl").orEmpty())
            InstancesScreen(
                baseUrl = baseUrl.ifBlank { "http://127.0.0.1:9201" },
                onBack = { navController.popBackStack() },
                onOpenUrl = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
                onOpenSessions = { instanceBaseUrl, instanceName ->
                    navController.navigate(
                        "agent-sessions/${Uri.encode(instanceBaseUrl)}/${Uri.encode(instanceName)}"
                    )
                },
            )
        }
        // 原生 Agent 会话列表(0.9.0)。baseUrl 里带 "://" 和 ":",instanceName 可能
        // 含空格/中文 —— 两者都必须 Uri.encode,否则 route 匹配会碎在 "/" 上。
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
                        "agent-session/${Uri.encode(baseUrl)}/${Uri.encode(sid)}"
                    )
                },
            )
        }
        // 原生 Agent 会话详情(0.9.0)。
        composable(
            route = "agent-session/{baseUrl}/{sid}",
            arguments = listOf(
                navArgument("baseUrl") { type = NavType.StringType },
                navArgument("sid") { type = NavType.StringType },
            )
        ) { entry ->
            val baseUrl = Uri.decode(entry.arguments?.getString("baseUrl").orEmpty())
            val sid = Uri.decode(entry.arguments?.getString("sid").orEmpty())
            AgentSessionScreen(
                baseUrl = baseUrl.ifBlank { "http://127.0.0.1:9201" },
                sessionId = sid,
                onBack = { navController.popBackStack() },
                onOpenWeb = { url ->
                    navController.navigate("webview/${Uri.encode(url)}")
                },
            )
        }
        composable("ssh-hosts") {
            SshHostListScreen(
                onBack = { navController.popBackStack() },
                onOpenWebview = { url ->
                    // Pop ssh-hosts first so back from the auto-launched
                    // WebView lands on Home, mirroring the scan flow.
                    navController.popBackStack()
                    navController.navigate("webview/${Uri.encode(url)}")
                },
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
