// ui/MainScaffold.kt — App 根容器:常驻底栏 + 内层 NavHost
//
// 结构选择说明(为什么仍是单 NavHost):
// 底栏**永远渲染**(详情页也有)。「当前高亮哪个 tab」不再从路由推导 ——
// 详情页的路由(webview / 会话 / 终端)匹配不到任何 tab,而是维护一份显式
// UI 状态 [currentTab]:
//   - 点底栏 → currentTab 更新 + navigate(popUpTo 起始 tab + saveState/restoreState)
//   - 在 tab 内进详情页 → 路由变了但 currentTab 不动 → 底栏照常显示并高亮所属 tab
//   - 从详情页返回 → 回到 tab 根,currentTab 从未变过,高亮天然正确
//
// tab 状态不销毁靠 navigate 的 saveState/restoreState:切走时把该 tab 的返回栈
// (含详情页)和 Compose 可保存状态(列表滚动位置、输入框内容)整体存档,
// 切回时原样恢复;tab 根目的地的 ViewModel 也不销毁,回来数据直接就是旧的。
//
// 窗口 inset 全交给内层屏幕的 Scaffold 处理(`contentWindowInsets = 0`),
// 否则会出现「外层扣一次状态栏、内层 TopAppBar 再扣一次」的双重留白。
// 注意:底栏常驻后,详情页里自己再加 navigationBarsPadding 的地方都会双重
// padding(会话输入条已随本次改动去掉,见 AgentSessionViews)。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

@Composable
fun MainScaffold(navController: NavHostController = rememberNavController()) {
    // 显式 tab 状态(不推导自路由,理由见文件头)。rememberSaveable 覆盖进程重建。
    var currentTab by rememberSaveable { mutableStateOf(TabDestination.Tasks) }

    // 点底栏和 App 内跨 tab 跳转(实例栏引导页「去添加」)走同一条路:
    // 语义等同,都不叠新层。
    val selectTab: (TabDestination) -> Unit = selectTab@{ target ->
        if (target == currentTab) return@selectTab
        currentTab = target
        navController.navigate(target.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            WbBottomBar(
                current = currentTab,
                onSelect = selectTab,
            )
        },
    ) { padding ->
        AppNavHost(
            navController = navController,
            contentPadding = padding,
            onSelectTab = selectTab,
        )
    }
}
