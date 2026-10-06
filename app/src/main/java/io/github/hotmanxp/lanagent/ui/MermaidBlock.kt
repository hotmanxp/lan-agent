// ui/MermaidBlock.kt — 会话里 ```mermaid 围栏块的渲染外壳
//
// 职责很薄:向 [MermaidRenderer] 要一张图,拿到就画,拿不到就把位置让回给
// [fallback](通常是 CodeBox)。**不自己处理任何 mermaid 语法** —— 解析在 JS 那边,
// 这里只管「成 / 不成」这一个二值结果。
//
// 加载中的占位刻意做得很轻(一个矮 Surface),因为流式输出时这块会短暂存在
// 一两帧,画个转圈会在消息流里跳得很扎眼。
//
// ## 卡片形态
//
// 顶部一条细 header(左:图类型中文名 / 右:缩放提示 + 全屏钮),下面才是图。
// 对齐 opencc-web `MermaidBlock.tsx` 的卡片语言,但**不抄它的 ⋯ 菜单** ——
// 那边是桌面宽度下的选择,手机上 30dp 高的 header 塞菜单是噪音。web 菜单里的
// 「复制源码」挪进全屏层(那里才是用户真要拿源码的地方),web 没做的「全屏」提到
// 卡片上直接给一个钮。§29 的教训:同屏几十行时图标只堆噪声,所以卡片上只留
// label + 缩放提示 + 一个全屏钮,复制只在全屏层出现。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.X
import io.github.hotmanxp.lanagent.R
import kotlinx.coroutines.launch

/** 宽:高 超过这个值就当「超宽图」,铺满卡片宽会被压成看不清的细带。 */
private const val WIDE_ASPECT = 2.2f

/** 宽:高 低于这个值就当「超高图」,铺满卡片宽会拉出一屏接不到底的长条。 */
private const val TALL_ASPECT = 0.45f

/**
 * 缩到低于这个比例就浮出「缩至 NN%」提示 —— 图里的字已经不太读得清,提示用户
 * 点开全屏看。同 web 的 `SCALE_HINT_THRESHOLD`。
 */
private const val SCALE_HINT_THRESHOLD = 0.85f

/** 卡片内图的左右留白(px 与 dp 同值,够用)。 */
private const val CARD_IMAGE_PADDING = 4

/** 全屏层自绘圆钮直径。**别用 IconButton** —— 它的 48dp 最小点击区会把这个
 *  顶栏撑爆(§30 记的同一个坑)。 */
private val FULLSCREEN_BTN = 34.dp

/**
 * 「是否渲染 Mermaid」开关。由 MainActivity 顶层 provide,所有走
 * [MarkdownText] 的地方自动吃到 —— 五个调用点一个都不用改。
 *
 * 默认 **true**:与 DataStore 里的默认值一致,首帧不会因为「还没读到偏好」
 * 而闪一下代码块。
 */
internal val LocalMermaidEnabled = staticCompositionLocalOf { true }

/**
 * 会话对话字号缩放(0.26.0)。由 MainActivity 顶层 provide,所有走
 * [MarkdownText] / 用户气泡 / 工具卡的正文 Text 读到后乘这个因子决定字号。
 *
 * 模仿 AA `aa/ui/screens/sessiondetail/AgentMarkdownText.kt` 的 `compact`
 * 二态 —— 那里非 compact 是 17sp / compact 是 14sp,17/14 ≈ 1.21。我们落
 * 三档(0.85 / 1.0 / 1.15),Default=1.0 起步,不再放大。
 *
 * 默认 **1.0f**:与 [MessageFontScale.Standard] 一致,首帧不会因为「还没读到偏好」
 * 而闪一下大号字。
 *
 * 用 [staticCompositionLocalOf] 是有意的:整个会话屏幕都用同一个常量,不该因
 * 重组而频繁重读 DataStore。改字号靠 Activity 重组触发,不是依赖此值更新。
 */
internal val LocalMessageFontScale = staticCompositionLocalOf { 1.0f }

/**
 * 这个围栏块是不是 Mermaid 流程图。
 *
 * 只看 `info` 的**第一个词** —— ```` ```mermaid ```` 后面可以跟属性
 * (```` ```mermaid {theme:dark} ````),整体比对会漏。
 *
 * 抽成纯函数是为了能进 JVM 单测(见 ui/MermaidFenceTest),渲染链路里那行
 * 判定本身不值得在真机上验。
 */
internal fun isMermaidFence(info: String?): Boolean =
    info?.trim()?.takeWhile { !it.isWhitespace() }?.equals("mermaid", ignoreCase = true) == true

// 图类型 → 中文标签,**只用于卡片头部展示**,不参与渲染决策 —— 全谱系都交给
// mermaid 自己解析,认不出的类型由它抛错再走回退。表照抄 opencc-web
// `mermaidRenderer.ts` 的 TYPE_LABELS,两端 header 才会说同一种话。
private val TYPE_LABELS: List<Pair<Regex, String>> = listOf(
    Regex("^sequenceDiagram\\b", RegexOption.IGNORE_CASE) to "时序图",
    Regex("^(flowchart|graph)\\b", RegexOption.IGNORE_CASE) to "流程图",
    Regex("^classDiagram\\b", RegexOption.IGNORE_CASE) to "类图",
    Regex("^stateDiagram(-v2)?\\b", RegexOption.IGNORE_CASE) to "状态图",
    Regex("^erDiagram\\b", RegexOption.IGNORE_CASE) to "ER 图",
    Regex("^(pie|xychart|quadrantChart|requirementDiagram)\\b", RegexOption.IGNORE_CASE) to "图表",
    Regex("^gantt\\b", RegexOption.IGNORE_CASE) to "甘特图",
    Regex("^(mindmap|gitGraph)\\b", RegexOption.IGNORE_CASE) to "思维导图",
    Regex("^(journey|timeline)\\b", RegexOption.IGNORE_CASE) to "旅程图",
    Regex("^(sankey-beta|sankey)\\b", RegexOption.IGNORE_CASE) to "桑基图",
    Regex("^(C4Context|C4Container|C4Component|C4Dynamic|C4Deployment)\\b", RegexOption.IGNORE_CASE) to "架构图",
    Regex("^(block-beta|packet-beta|architecture-beta|kanban|radar|treemap|zenuml)\\b", RegexOption.IGNORE_CASE) to "图",
)

/** 认不出类型时的兜底名,跟 web 一致。 */
private const val FALLBACK_LABEL = "图表"

/**
 * 取首行有效声明:跳过空行与 `%%` 注释行。
 *
 * mermaid 允许在图前面放 `%%{init}%%` 指令块和整行注释,第一行不一定是类型头。
 */
private fun firstMeaningfulLine(source: String): String =
    source.lineSequence()
        .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("%%") }
        .orEmpty()

/**
 * 这段源码是什么图 —— 给卡片 header 显示。识别不出返回「图表」。
 *
 * 纯函数、不 import Compose,所以能进 JVM 单测(见 ui/MermaidDiagramLabelTest)。
 * export 的唯一调用点是 [MermaidBlock] 的 header。
 */
internal fun mermaidDiagramLabel(source: String): String {
    val first = firstMeaningfulLine(source).trim()
    return TYPE_LABELS.firstOrNull { (re, _) -> re.containsMatchIn(first) }?.second
        ?: FALLBACK_LABEL
}

private sealed interface MermaidState {
    data object Loading : MermaidState
    data class Ready(val image: MermaidImage) : MermaidState
    data object Failed : MermaidState
}

@Composable
internal fun MermaidBlock(
    source: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = LocalWbDarkTheme.current
    // 底色烤进 PNG:深色主题下 mermaid 会画浅色节点,透明底会让字糊在深色卡片上。
    val bg = MaterialTheme.colorScheme.surfaceContainerLow

    val state by produceState<MermaidState>(MermaidState.Loading, source, dark, bg) {
        val image = MermaidRenderer.render(context, source, dark, bg.toHexString())
        value = if (image != null) MermaidState.Ready(image) else MermaidState.Failed
    }

    // 全屏层挂在 Dialog(独立窗口)上,不受消息流 LazyColumn 的回收影响。
    var fullscreen by remember { mutableStateOf(false) }

    when (val s = state) {
        MermaidState.Loading -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier.fillMaxWidth().height(64.dp),
        ) {}

        is MermaidState.Ready -> {
            MermaidCard(
                image = s.image,
                label = mermaidDiagramLabel(source),
                onExpand = { fullscreen = true },
                modifier = modifier,
            )
            if (fullscreen) {
                MermaidFullscreenDialog(
                    image = s.image.bitmap,
                    label = mermaidDiagramLabel(source),
                    source = source,
                    onDismiss = { fullscreen = false },
                )
            }
        }

        // 语法错 / 流式半截 / 超时 —— 一律退回代码块,和「关掉开关」时长得一样。
        MermaidState.Failed -> Box(modifier = modifier) { fallback() }
    }
}

@Composable
private fun MermaidCard(
    image: MermaidImage,
    label: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // outlineVariant 就是 LanAgentTheme 映射的 WbPalette.HairlineLight/Dark
    // (见 LanAgentTheme.kt)。**别拿 surfaceVariant 画边** —— 压 #F8F8F8 页底
    // 只有约 2% 对比,真机静态截图里几乎看不见(§25 记过)。
    val hairline = MaterialTheme.colorScheme.outlineVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val contentWidth = maxWidth - (CARD_IMAGE_PADDING * 2).dp
                // 图按本来的设计尺寸(viewBox)有多大,现在被压到多少。单位取 dp
                // 是因为可读性瓶颈在「CSS px 尺寸的设计稿缩到多窄」,不是物理像素
                // —— PNG 那边光栅化时已经按 dpr 放大过了。
                val scale = if (image.naturalWidth > 0) {
                    contentWidth.value / image.naturalWidth
                } else {
                    1f
                }
                val scaledDown = scale.isFinite() && scale > 0f && scale < SCALE_HINT_THRESHOLD

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mermaid_card_title, label),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (scaledDown) {
                        Text(
                            text = stringResource(
                                R.string.mermaid_card_scaled_to,
                                (scale * 100).toInt().coerceIn(1, 100),
                            ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onExpand)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    RoundIconButton(
                        icon = Lucide.Maximize2,
                        contentDescription = stringResource(R.string.mermaid_card_expand_cd),
                        onClick = onExpand,
                    )
                }
            }

            HorizontalDivider(color = hairline)

            MermaidImageBody(image = image.bitmap, onExpand = onExpand)
        }
    }
}

/**
 * 图本体。长宽比正常的按卡片宽度铺满;两种极端单独处理,否则纯 fit-width 会把图
 * 压成一条完全看不清的细带 / 一屏拉不到底的长条。
 *
 * 整块可点 = 进全屏(web 那边点的是 svg,这边点的是位图,交互语义一致)。
 */
@Composable
private fun MermaidImageBody(image: ImageBitmap, onExpand: () -> Unit) {
    val aspect = if (image.height > 0) image.width.toFloat() / image.height else 1f

    when {
        aspect > WIDE_ASPECT -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 先抓出来:下面嵌了 Box/Column,里面的 maxWidth 接收者会变。
            val w = maxWidth
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .width(w * 2)
                        .padding(CARD_IMAGE_PADDING.dp)
                        .clickable(onClick = onExpand),
                )
            }
        }

        aspect < TALL_ASPECT -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth
            Column(
                modifier = Modifier
                    .heightIn(max = w * 3)
                    .verticalScroll(rememberScrollState()),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .width(w)
                        .padding(CARD_IMAGE_PADDING.dp)
                        .clickable(onClick = onExpand),
                )
            }
        }

        else -> Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(CARD_IMAGE_PADDING.dp)
                .clickable(onClick = onExpand),
        )
    }
}

/**
 * 全屏预览。
 *
 * 用 [Dialog] 而不是加一层 AnimatedVisibility:它自带独立窗口与返回键处理
 * (onDismissRequest 就接系统返回),不会被消息流的 LazyColumn 回收掉,也
 * 不占路由。
 *
 * 底部黑底而不是卡片色:全屏看图要的是「像看图片」,沿用 ui/FileViewerOverlay
 * 的 ImageBytesBody 那套黑底观感。
 */
@Composable
private fun MermaidFullscreenDialog(
    image: ImageBitmap,
    label: String,
    source: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val clipboard = LocalClipboardManager.current
        val copiedText = stringResource(R.string.agent_bubble_copied)
        val toastHost = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        fun copySource() {
            clipboard.setText(AnnotatedString(source))
            scope.launch { toastHost.showToast(copiedText) }
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D))) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = 16.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xFF161618))
                    .statusBarsPadding()
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mermaid_fullscreen_title, label),
                    fontSize = 12.sp,
                    color = Color(0xFFB9BDC4),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                RoundIconButton(
                    icon = Lucide.Copy,
                    contentDescription = stringResource(R.string.mermaid_copy_source),
                    onClick = { copySource() },
                )
                Spacer(Modifier.width(6.dp))
                RoundIconButton(
                    icon = Lucide.X,
                    contentDescription = stringResource(R.string.mermaid_fullscreen_close),
                    onClick = onDismiss,
                )
            }

            WbToastHost(
                toastHost,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp),
            )
        }
    }
}

/** 自绘圆钮(见 [FULLSCREEN_BTN] 的注释:别用 IconButton)。 */
@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(FULLSCREEN_BTN)
            .clip(CircleShape)
            .background(Color(0xFF26262A))
            .border(1.dp, Color(0xFF34343A), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color(0xFFE6E8EC),
            modifier = Modifier.size(17.dp),
        )
    }
}

private fun Color.toHexString(): String {
    fun channel(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", channel(red), channel(green), channel(blue))
}
