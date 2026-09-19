// ui/TasksTabScreen.kt — 任务栏(底栏第 1 栏)。
//
// 0.14.x 这一栏是「进行中(跨实例聚合)+ 入口卡片列表」两段式;0.15.0 改成
// **直接就是原生 Agent 工作区** —— 打开 App 点任务栏,看到的是你上次干活那个
// opencc 实例的最近一条会话,而不是一层需要再点的入口。
//
// 为什么改成这样:
//   - 这一栏叫「任务」,点进去却要先在卡片列表里挑一个实例再挑会话,中间两层
//     全是导航噪声;真正的入口(实例管理)已经是底栏第 2 栏了。
//   - Agent 页自己带「会话切换面板」(左侧抽屉):顶部实例行 → 「选择实例」弹层,
//     下面就是该实例的会话列表。所以「换实例 / 换会话」两个动作在原地完成,
//     不需要退回一个列表页。
//
// 落到哪个实例由 `data/AgentInstances.kt` + `data/AgentWorkspacePrefs.kt` 决定:
// 上次连接的那个;它下线了就落到第一个在线的实例。落到哪条会话同理:
// 上次停的那条;它没了就用最新一条;一条都没有就给「新建会话」。
//
// 原来那两段东西的去向:
//   - **入口卡片列表 + 扫码** → 设置栏的「入口卡片」区块(`ui/CardListSection.kt`)。
//     它仍然是 `findManagerBaseUrl` 与「管理器不可达时的兜底实例目录」的数据源,
//     不能删;降级成低频管理面。
//   - **「进行中」跨实例聚合**(`data/ActiveTasks.kt`)→ 本栏不再露出。任务栏现在
//     按实例分家,抽屉里的会话列表自带相对时间与模型标签,跨实例那层聚合失去
//     了位置。数据层保留(其他屏没依赖,但删了会丢一段已验证的扇出/超时逻辑)。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.runtime.Composable

@Composable
fun TasksTabScreen(onOpenWeb: (String) -> Unit) {
    AgentSessionPane(
        // 三个 null = 「自己解析」:实例按「上次连接 → 第一个在线」,
        // 会话按「上次停的 → 最新一条」。
        initialBaseUrl = null,
        initialInstanceName = "",
        initialSessionId = null,
        // tab 根不渲染返回箭头(底栏才是这一层的导航)。
        onBack = null,
        onOpenWeb = onOpenWeb,
    )
}
