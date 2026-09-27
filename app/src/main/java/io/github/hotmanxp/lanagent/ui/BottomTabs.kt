// ui/BottomTabs.kt — 底部 5 栏导航(视觉对齐 WorkBuddy 手机端底栏)
//
// 与 Material3 `NavigationBar` 的差异(照 WorkBuddy 截图逐项对的):
//   1. **高度收窄**:M3 默认 80dp + 图标底下一堆留白,WorkBuddy 是 ~56dp
//      紧凑条 —— 手机端这条栏每年被看几万次,多 20dp 都是从内容里抢的。
//   2. **选中态用「深色图标 + 深色文字」**表达,不用 M3 的 indicator 药丸
//      (`NavigationBarItem` 那颗灰底胶囊在浅色主题下很抢眼,和 WorkBuddy
//      的克制风格不搭)。
//   3. **图标一律 Lucide 线性描边**(0.21.0 起全项目换掉 Material Symbols)。
//      更早经历过 Filled + Outlined 两套 → 统一 Rounded → 统一 Lucide 三轮:
//      底栏**始终不需要**"实心 / 描边"两套 ImageVector,选中态完全由
//      **颜色 + 字重**区分。顺带解决一个体感问题:换形状会让选中瞬间
//      "跳"一下,同形状只变色就平滑。
//   4. 顶部一条 0.5dp hairline —— WorkBuddy 底栏和内容之间靠这根线分隔,
//      而不是靠阴影。
//
// ⚠️ **Lucide 的两个坑**(改动前必读):
//   - Lucide 是 24dp 网格 / **2px 描边**,比 Material 的可变字重细。23dp 下
//     尚可,但 20dp 以下会灰掉 —— 小尺寸站点已在各屏单独调过。
//   - Lucide 的 `ImageVector` **不带 autoMirrored 元数据**,方向性图标在 RTL
//     locale 下不会自动翻转。需要翻转的走 `WbMirroredIcon(autoMirror = true)`。
//
// 图标名对着 `com.composables:icons-lucide-android:1.1.0` 的 classes.jar
// 核过(1521 个图标),`AlertTriangle` / `CheckCircle` 这类 2024 前的旧名
// **已不存在**,别用。
package io.github.hotmanxp.lanagent.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hotmanxp.lanagent.R

/**
 * 五个 tab 的单一事实来源:路由、显示名、图标都在这。
 *
 * 路由统一 `tab/` 前缀,只作命名空间 —— 底栏现在永远渲染,高亮哪个 tab 由
 * MainScaffold 的显式 currentTab 状态决定,不再从路由推导。
 */
enum class TabDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Tasks(
        route = "tab/tasks",
        labelRes = R.string.tab_tasks,
        icon = Lucide.MessageCircle,
    ),
    Instances(
        route = "tab/instances",
        labelRes = R.string.tab_instances,
        icon = Lucide.Server,
    ),
    Ssh(
        route = "tab/ssh",
        labelRes = R.string.tab_ssh,
        icon = Lucide.Terminal,
    ),
    Services(
        route = "tab/services",
        labelRes = R.string.tab_services,
        // Lucide 没有 Hub(多层节点枢纽);HubGlyph 手绘的「三节点互连」语义更准。
        icon = Lucide.Network,
    ),
    Settings(
        route = "tab/settings",
        labelRes = R.string.tab_settings,
        icon = Lucide.Settings,
    ),
    ;

    companion object {
        /** 当前路由对应的 tab;详情页(webview / 会话 / 终端 …)返回 null → 隐藏底栏。 */
        fun fromRoute(route: String?): TabDestination? =
            entries.firstOrNull { it.route == route }
    }
}

/**
 * 未选中态的图标不透明度。低于 1 是为了让"没选中"更轻 —— 颜色本身
 * (onSurfaceVariant vs onSurface)在深色主题下差异偏小,补一点透明度更稳。
 *
 * 0.72 而不是上一版的 0.78:换 Lucide 后线条是 2px 描边,再叠 0.78 的 alpha 会
 * 让未选中的格子整体偏淡偏糊。降一档补回对比(AGENTS.md §16 已同步)。
 */
private const val INACTIVE_ICON_ALPHA = 0.72f

/** 底栏本体。永远渲染(详情页也有),`current` 是当前高亮的 tab。 */
@Composable
fun WbBottomBar(
    current: TabDestination,
    onSelect: (TabDestination) -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            // 沉浸模式下导航栏已隐藏,这个 inset 通常是 0;留着是为了
            // 系统短暂唤出导航栏(上滑手势)时底栏不被盖住。
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TabDestination.entries.forEach { tab ->
                WbBottomBarItem(
                    tab = tab,
                    selected = tab == current,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun WbBottomBarItem(
    tab: TabDestination,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val active = MaterialTheme.colorScheme.onSurface
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    val target = if (selected) active else inactive
    val tint by animateColorAsState(target, label = "tabTint")

    // 去掉水波纹:底栏是高频点按区,涟漪在 56dp 的窄条上会溢到相邻格子。
    Column(
        modifier = modifier
            .noRippleClickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = stringResource(tab.labelRes),
            tint = tint,
            // 选中态满不透明,未选中略淡。
            // 注意:这里的 material3 `Icon` 没有 `alpha` 参数(只有 bitmap/painter
            // 重载带),所以只能走 Modifier。
            modifier = Modifier
                .alpha(if (selected) 1f else INACTIVE_ICON_ALPHA)
                .size(23.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(tab.labelRes),
            color = tint,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
