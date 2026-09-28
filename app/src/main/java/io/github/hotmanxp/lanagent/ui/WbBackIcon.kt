// ui/WbBackIcon.kt — 顶栏返回箭头(0.24.0)
//
// 0.24.0 底栏从五栏收成四栏,原本的「实例」「SSH」两个 tab 降级成「服务」
// 栏下的路由,于是**一批屏从 tab 根变成了详情页** —— 它们以前 `onBack = null`
// 不画返回箭头,现在要画。
//
// 原来每处都是内联的 `IconButton { Icon(Lucide.ArrowLeft) }`,抄了 5 遍。
// 图标、尺寸、contentDescription 全一样,只有 onClick 不同,抽出来。
//
// 仍然按需条件渲染(`if (onBack != null)`),**不要**无条件摆一个占位按钮 ——
// 标题左边会顶出一段莫名空白(InstancesScreen 的原注释也记了这个坑)。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import io.github.hotmanxp.lanagent.R

/** TopAppBar 的 `navigationIcon` 插槽直接用它:`navigationIcon = { WbBackIcon(onBack) }`。 */
@Composable
fun WbBackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Lucide.ArrowLeft,
            contentDescription = stringResource(R.string.webview_back_cd),
        )
    }
}
