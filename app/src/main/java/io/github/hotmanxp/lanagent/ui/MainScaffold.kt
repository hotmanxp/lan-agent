// ui/MainScaffold.kt — App 根容器:底栏 + 内层 NavHost
//
// 结构选择说明(为什么不是「每个 tab 一个 NavHost」):
// 单 NavHost + `tab/` 前缀路由,底栏显隐就是一句
// `TabDestination.fromRoute(当前路由)` —— 详情页(会话 / 终端 / WebView)天然
// 匹配不到 tab 路由,底栏自动消失,不用在十来个屏幕里各写一遍「隐藏底栏」。
// 代价是 tab 切换要自己带 saveState/restoreState,见 [switchTab]。
//
// 窗口 inset 全交给内层屏幕的 Scaffold 处理(`contentWindowInsets = 0`),
// 否则会出现「外层扣一次状态栏、内层 TopAppBar 再扣一次」的双重留白。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Composable
fun MainScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = TabDestination.fromRoute(backStackEntry?.destination?.route)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // currentTab == null(详情页)→ 整个底栏不渲染。这里不做动画:
            // 进会话详情时底栏瞬间让位,比「先播一段收起动画再切屏」更跟手。
            if (currentTab != null) {
                WbBottomBar(
                    current = currentTab,
                    onSelect = { tab -> switchTab(navController, currentTab, tab) },
                )
            }
        },
    ) { padding ->
        AppNavHost(
            navController = navController,
            contentPadding = padding,
        )
    }
}

/**
 * 切 tab。
 *
 * - `popUpTo(起始 tab) + saveState` —— 保证返回栈里永远只有「起始 tab + 当前
 *   tab」两层,不会因为来回点攒出十个实例。
 * - `restoreState = true` —— 会话列表的滚动位置、任务栏的展开态在切走再切回
 *   时保留(用户视角:切 tab 不该把列表弹回顶部)。
 * - 重复点当前 tab 直接吞掉:重放一次 navigate 会白跑一轮 restore。
 */
private fun switchTab(
    navController: NavHostController,
    current: TabDestination?,
    target: TabDestination,
) {
    if (target == current) return
    navController.navigate(target.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
