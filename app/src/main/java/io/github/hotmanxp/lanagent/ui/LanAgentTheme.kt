// ui/LanAgentTheme.kt — WorkBuddy 视觉体系 + 平安橙品牌色。
//
// 改造要点(0.10.0,0.10.2 微调品牌色):
//   1. **关掉 Material You 动态取色**。上一版默认 `dynamicColor = true`,配色
//      跟着手机壁纸跑 —— 截图里整屏泛紫,跟 WorkBuddy 完全不是一个东西。
//      现在固定用 WorkBuddy 的色板(见 [WbPalette])。
//   2. **页底灰 / 卡面白**。WorkBuddy 手机端的观感是「浅灰底 #F8F8F8 +
//      白色圆角卡片」,所以这里把 `surface` 直接设成页底色,把
//      `surfaceContainerLow/Lowest/Container/High` 全部设成白色:
//      于是 Scaffold / TopAppBar 默认取 `surface` = 灰(顶栏跟页面连成一片,
//      没有白条),而 Card / ModalBottomSheet / 会话卡 / 工具卡默认取
//      `surfaceContainerHigh` = 白。全项目一百多处 `MaterialTheme.colorScheme`
//      调用因此一次性对齐,不用逐个文件改。
//   3. **品牌平安橙 #ff6600**(沿用 0.10.0 之前的 zai `/m` AI-Agent 头像色)
//      作为亮色 primary,只用在「主按钮 / 发送按钮 / 运行中状态」这些点睛位置,
//      正文保持黑白灰。深色主题保持青绿 #35D6B6(0.10.0 的取值)。
//   4. 状态栏图标明暗自适应,深色下自动切浅色图标。
package io.github.hotmanxp.lanagent.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * WorkBuddy 视觉色板。数值来自两处硬证据:
 *   - 官方 App 图标(`icon.png`)—— 青绿渐变 #0DC8A6 → #14CA85;
 *   - WorkBuddy 手机端截图采样 —— 页底 #F8F8F8、卡面 #FFFFFF、正文 #242424、
 *     次要文字 #8C8C8C。
 *
 * **亮色主题品牌色** 在 0.10.2 改为平安橙 `#ff6600`(沿用 0.10.0 之前的 zai
 * `/m` AI-Agent 头像色;0.10.0 临时改成青绿,现改回)。深色主题保持青绿
 * `#35D6B6` 体系,启动图标底色统一走 [BrandOrange]。
 */
object WbPalette {
    /** 亮色主题品牌主色:主按钮 / 发送按钮 / 选中态 / 启动图标底。 */
    val BrandOrange = Color(0xFFff6600)

    /** 深色主题 inversePrimary(深色下显示在反色面上的品牌色)。 */
    val Teal = Color(0xFF0CC8A6)

    /** 品牌绿(图标右下角那个绿):次级强调、模型标签。 */
    val Green = Color(0xFF14CA85)

    // ---- 亮色 ----
    val PageLight = Color(0xFFF8F8F8)
    val CardLight = Color(0xFFFFFFFF)
    val InkLight = Color(0xFF1F1F1F)
    val InkMutedLight = Color(0xFF8C8C8C)
    val HairlineLight = Color(0xFFEBEDF0)
    val SunkenLight = Color(0xFFF3F4F6)

    /**
     * 用户消息气泡底色。**这是 WorkBuddy 与常见「绿色气泡」IM 的分水岭** ——
     * WorkBuddy 手机端的用户气泡是中性浅灰(不是品牌绿),品牌平安橙只留给
     * 发送按钮/主按钮。采样值 #E2E4E3。
     */
    val BubbleLight = Color(0xFFE2E4E3)

    /** 「发送」按钮的禁用态底色(WorkBuddy 空输入时那个浅蓝灰圆钮)。 */
    val SendDisabledLight = Color(0xFFE0E3E8)

    // ---- 暗色 ----
    val PageDark = Color(0xFF141517)
    val CardDark = Color(0xFF1F2124)
    val InkDark = Color(0xFFECEDEF)
    val InkMutedDark = Color(0xFF9AA0A8)
    val HairlineDark = Color(0xFF2B2D31)
    val SunkenDark = Color(0xFF26282C)
    val BubbleDark = Color(0xFF2A2D2C)
    val SendDisabledDark = Color(0xFF34383D)
}

/**
 * 没有对应 M3 语义槽的 WorkBuddy 专用色。M3 的 colorScheme 槽位不够表达
 * 「用户气泡」「禁用态发送钮」这类组件级色,硬塞进 surfaceVariant 会连带
 * 改掉代码块底色等无关位置,所以单开一个 CompositionLocal。
 */
@Immutable
data class WbExtras(
    val userBubble: Color,
    val sendDisabled: Color,
)

private val LightExtras = WbExtras(
    userBubble = WbPalette.BubbleLight,
    sendDisabled = WbPalette.SendDisabledLight,
)

private val DarkExtras = WbExtras(
    userBubble = WbPalette.BubbleDark,
    sendDisabled = WbPalette.SendDisabledDark,
)

val LocalWbExtras = staticCompositionLocalOf { LightExtras }

/**
 * 当前**实际生效**的明暗状态。
 *
 * 不能直接用 `isSystemInDarkTheme()` 判断:设置栏可以手动选亮/暗,此时系统值
 * 与页面真实明暗不一致 —— 代码块语法高亮若按系统值选色板,就会出现「浅色卡片
 * 上刷深色代码」。这里把 [LanAgentTheme] 解析后的结果传给下面。
 */
val LocalWbDarkTheme = staticCompositionLocalOf { false }

private val LightScheme = lightColorScheme(
    primary = WbPalette.BrandOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2CC),
    onPrimaryContainer = Color(0xFF5C2400),
    inversePrimary = Color(0xFFFF944D),

    secondary = WbPalette.Green,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1F7EA),
    onSecondaryContainer = Color(0xFF0A3A26),

    // tertiary 在本项目里被用作「运行中」的强调色(工具调用卡 / 状态徽标),
    // 给一个暖橙 —— 跟 primary(平安橙)同色族但更浅,不至于重复。
    tertiary = Color(0xFFE2932F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCF0DD),
    onTertiaryContainer = Color(0xFF5C3D0E),

    // 页底灰:Scaffold / TopAppBar 的默认底色都取这里。
    background = WbPalette.PageLight,
    onBackground = WbPalette.InkLight,
    surface = WbPalette.PageLight,
    onSurface = WbPalette.InkLight,
    surfaceVariant = WbPalette.SunkenLight,
    onSurfaceVariant = WbPalette.InkMutedLight,
    surfaceTint = WbPalette.BrandOrange,

    // 卡片族:全部白色 —— Card / ModalBottomSheet(surfaceContainerLow)/
    // 会话行与工具卡(surfaceContainerHigh)/ AlertDialog 都落在这里。
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFEFEFEF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    // 唯一比卡片再深一档的槽位:代码块 / 内嵌输入区 / 未选中选项。
    surfaceContainerHighest = WbPalette.SunkenLight,

    inverseSurface = Color(0xFF2A2C2F),
    inverseOnSurface = Color(0xFFF4F5F6),

    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8A1F23),

    outline = Color(0xFFD9DBDF),
    outlineVariant = WbPalette.HairlineLight,
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF35D6B6),
    onPrimary = Color(0xFF04241E),
    primaryContainer = Color(0xFF123029),
    onPrimaryContainer = Color(0xFFB6EDE1),
    inversePrimary = WbPalette.Teal,

    secondary = Color(0xFF3FD693),
    onSecondary = Color(0xFF04291C),
    secondaryContainer = Color(0xFF123527),
    onSecondaryContainer = Color(0xFFC6F2DC),

    tertiary = Color(0xFFF0B160),
    onTertiary = Color(0xFF3A2606),
    tertiaryContainer = Color(0xFF3A2A12),
    onTertiaryContainer = Color(0xFFF7DFB9),

    background = WbPalette.PageDark,
    onBackground = WbPalette.InkDark,
    surface = WbPalette.PageDark,
    onSurface = WbPalette.InkDark,
    surfaceVariant = WbPalette.SunkenDark,
    onSurfaceVariant = WbPalette.InkMutedDark,
    surfaceTint = Color(0xFF35D6B6),

    surfaceBright = Color(0xFF2A2C30),
    surfaceDim = Color(0xFF111214),
    surfaceContainerLowest = Color(0xFF101113),
    surfaceContainerLow = WbPalette.CardDark,
    surfaceContainer = WbPalette.CardDark,
    surfaceContainerHigh = WbPalette.CardDark,
    surfaceContainerHighest = WbPalette.SunkenDark,

    inverseSurface = WbPalette.InkDark,
    inverseOnSurface = WbPalette.PageDark,

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0B),
    errorContainer = Color(0xFF3D1D1E),
    onErrorContainer = Color(0xFFFFD9D9),

    outline = Color(0xFF3A3C41),
    outlineVariant = WbPalette.HairlineDark,
    scrim = Color(0xFF000000),
)

/**
 * 字号阶梯对齐 WorkBuddy 手机端:顶栏标题 16sp/SemiBold、正文 15sp/行高 22sp、
 * 次要文字 12sp、说明小字 10–11sp。只调这几个高频档位,其余沿用 M3 默认。
 */
private val WbTypography = Typography().let { base ->
    base.copy(
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
    )
}

/**
 * 应用主题。
 *
 * `dynamicColor` 默认 **false** —— 保留这个参数只是为了万一想临时开回
 * Material You;正常路径一律走 WorkBuddy 固定色板。
 */
@Composable
fun LanAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme

    // 状态栏是透明的(内容画到状态栏后面),所以图标颜色必须跟着页面深浅走,
    // 否则浅色页面上会出现「白图标压白底」的隐形状态栏。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WbTypography,
    ) {
        CompositionLocalProvider(
            LocalWbExtras provides if (darkTheme) DarkExtras else LightExtras,
            LocalWbDarkTheme provides darkTheme,
            content = content,
        )
    }
}
