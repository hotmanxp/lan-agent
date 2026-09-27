// ui/Glyphs.kt — Lucide 补不到的手绘图元(0.21.0)
//
// **为什么需要这个文件**:Lucide 库**只有描边版,没有实心版**。而 lan-agent 有一批
// 位置依赖「实心块面」的图标 —— 圆形实心按钮里的播放/停止、Markdown 任务框的勾选、
// 收藏星标。这些位置换成空心描边会明显变弱(实心圆钮里一个线框三角形看起来是坏的)。
//
// 第二个理由是**语义最偏的几个 Material 图标**在 Lucide 里没有 1:1 对应:
// `Hub`(服务互联)、`Psychology`(思考)、`Construction` / `Build`(工具)、`Slideshow`(PPT)。
// 与其拿一个语义偏移的近似图标,不如照原样手绘。
//
// ## 为什么是 ImageVector 而不是 Composable
//
// 这些图元的调用点跟 Lucide 图标**完全一样**(`Icon(imageVector = …)`),所以这里
// 也暴露成 [ImageVector],而不是一个 Composable —— 否则每个调用点都要为
// 「用哪个渲染通道」写不同的分支,反而更容易出错。统一的 ImageVector 意味着
// `Icon` / `WbMirroredIcon` / tint / alpha 全部原样可用。
//
// 用 `remember` 缓存住构造结果(每个图标一个稳定单例),避免每次重组重新构建
// PathData —— ImageVector 的构建不是免费的。
//
// ⚠️ 这里手绘的图标**不是**「Lucide 替代品」,而是「Lucide 补不到的那一小块」。
// 其余全部图标都直接用 Lucide,不要往这里堆。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 手绘图元的命名空间,用法跟 Lucide **完全对称**:
 * `Icon(Glyph.Brain)` / `Icon(Lucide.Brain)` 长得一样,调用点不需要知道
 * 某个图标到底来自哪一边。
 */
internal object Glyph {
    val SolidSquare: ImageVector get() = SolidSquareGlyph
    val SolidStar: ImageVector get() = SolidStarGlyph
    val SolidCircle: ImageVector get() = SolidCircleGlyph
    val SolidCheckCircle: ImageVector get() = SolidCheckCircleGlyph
    val Hub: ImageVector get() = HubGlyph
    val Brain: ImageVector get() = BrainGlyph
    val Hammer: ImageVector get() = HammerGlyph
    val Slide: ImageVector get() = SlideGlyph
}

/** 全部手绘图元统一按 24dp 网格 + 24 单位坐标画(与 Lucide 对齐,便于等尺寸替换)。 */
private const val G = 24f

/** 描边宽度。Lucide 默认 2;这几个多数出现在 18-22dp,加粗到 2.4 才不糊。 */
private const val STROKE = 2.4f

private fun strokeColor() = SolidColor(Color.Black)

private fun strokeJoin() = StrokeJoin.Round

// ── 实心块面(占位在实心按钮 / 任务框里,空心会显空)──────────────────────

/** 实心方块 —— 「停止」按钮。Lucide 的 `Square` 是空心框,实心钮里会显空。 */
internal val SolidSquareGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbSolidSquare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 7f)
            lineTo(17f, 7f)
            lineTo(17f, 17f)
            lineTo(7f, 17f)
            close()
        }
    }.build()
}

/** 实心五角星 —— 收藏态。Lucide 的 `Star` 是线框轮廓,收藏的语义就是「填满」。 */
internal val SolidStarGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbSolidStar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // 外 5 内 5 交替,相邻顶点夹角 36°,-90° 起手让首个顶点朝正上方。
            val cx = 12f
            val cy = 12f
            val outer = 10f
            val inner = outer * 0.382f          // 经典五角星内径比 1/2.618
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) outer else inner
                val rad = Math.toRadians((i * 36.0 - 90.0))
                val x = cx + (r * kotlin.math.cos(rad)).toFloat()
                val y = cy + (r * kotlin.math.sin(rad)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }.build()
}

/** 实心圆 —— Markdown 未勾选任务框。Lucide 的 `Circle` 是空心圈,勾选前的框应更实。 */
internal val SolidCircleGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbSolidCircle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        // 用四段贝塞尔拼一个视觉上足够圆的圆 —— 直接用 circle 指令的
        // ImageVector 支持不稳,四段三次贝塞尔(k = 0.5523)是标准做法。
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4.4f)
            curveTo(15.6f, 4.4f, 19.6f, 8.4f, 19.6f, 12f)
            curveTo(19.6f, 15.6f, 15.6f, 19.6f, 12f, 19.6f)
            curveTo(8.4f, 19.6f, 4.4f, 15.6f, 4.4f, 12f)
            curveTo(4.4f, 8.4f, 8.4f, 4.4f, 12f, 4.4f)
            close()
        }
    }.build()
}

/**
 * 实心圆 + 反白对勾 —— Markdown 已勾选任务框。
 *
 * 对勾走白色描边:调用点的 tint 会被 [SolidCircleGlyph] 之外的白色压住吗?
 * 不会 —— `Icon` 的 tint 只作用在这一个 ImageVector 的所有 path 上,所以
 * 「白勾」必须自己画死。这也是唯一一个**双色**图元,其余都是单色。
 */
internal val SolidCheckCircleGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbSolidCheckCircle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3f)
            curveTo(16.4f, 3f, 20f, 6.6f, 20f, 11f)
            curveTo(20f, 15.4f, 16.4f, 19f, 12f, 19f)
            curveTo(7.6f, 19f, 4f, 15.4f, 4f, 11f)
            curveTo(4f, 6.6f, 7.6f, 3f, 12f, 3f)
            close()
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = strokeJoin(),
        ) {
            moveTo(8.2f, 11.6f)
            lineTo(11f, 14.4f)
            lineTo(16f, 8.4f)
        }
    }.build()
}

// ── Material 有、Lucide 无 1:1 对应的四个语义图元 ──────────────────────────

/**
 * Hub —— 三节点互连(底栏「服务」栏 + 远程服务页)。
 * Lucide 的 `Network` 是分层结构、`Share2` 是分享箭头,都不是「多设备互联」的意思。
 */
internal val HubGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbHub",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        // 先画连线再画节点 —— 节点压在连线上,视觉上像是「连上了」。
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(5.5f, 12f); lineTo(18.5f, 5.5f)
        }
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(5.5f, 12f); lineTo(18.5f, 18.5f)
        }
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(18.5f, 5.5f); lineTo(18.5f, 18.5f)
        }
        listOf(5.5f to 12f, 18.5f to 5.5f, 18.5f to 18.5f).forEach { (x, y) ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(x, y - 2.4f)
                curveTo(x + 1.5f, y - 2.4f, x + 2.4f, y - 1.5f, x + 2.4f, y)
                curveTo(x + 2.4f, y + 1.5f, x + 1.5f, y + 2.4f, x, y + 2.4f)
                curveTo(x - 1.5f, y + 2.4f, x - 2.4f, y + 1.5f, x - 2.4f, y)
                curveTo(x - 2.4f, y - 1.5f, x - 1.5f, y - 2.4f, x, y - 2.4f)
                close()
            }
        }
    }.build()
}

/**
 * Psychology —— 思考中的「脑」(`Icons.Rounded.Psychology`,思考卡块头 / agent 子任务类型)。
 * Lucide 有 `Brain`,但那是个非常细密的折线脑回,18dp 下完全糊成一团黑;
 * 这里画一个简化版:外轮廓 + 中缝 + 两道回折。
 */
internal val BrainGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbBrain",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        path(
            stroke = strokeColor(),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = strokeJoin(),
        ) {
            moveTo(19f, 4.5f)
            lineTo(10f, 4.5f)
            quadTo(5.5f, 5f, 6.5f, 10f)   // 左上隆起
            quadTo(3.5f, 12.5f, 7f, 15.5f)
            quadTo(5.5f, 19.5f, 10f, 20f)
            lineTo(19f, 20f)
            quadTo(20.5f, 17f, 19.5f, 13.5f)
            quadTo(22f, 10.5f, 19.5f, 7.5f)
            quadTo(20.5f, 5f, 19f, 4.5f)
            close()
        }
        // 中缝。
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(13f, 5.5f); lineTo(13f, 19.5f)
        }
        // 左脑回折两道。
        path(stroke = strokeColor(), strokeLineWidth = STROKE * 0.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(8.5f, 9.5f); lineTo(11.5f, 11f)
        }
        path(stroke = strokeColor(), strokeLineWidth = STROKE * 0.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(8.5f, 14.5f); lineTo(11.5f, 15.5f)
        }
    }.build()
}

/**
 * Construction / Build —— 锤子(工具提示、工具调用组)。
 * Lucide 有 `Hammer`,但那个图形偏「羊角锤」;这里画一把更常见的圆头锤,
 * 且能同时顶替原来的 `Construction`(路锥)和 `Build`(扳手)两处。
 */
internal val HammerGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbHammer",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        // 锤头:横竖两笔构成一个 T。
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(4.5f, 8.5f); lineTo(12f, 8.5f)
        }
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(8.2f, 4.8f); lineTo(8.2f, 12.2f)
        }
        // 手柄:锤头中心斜向右下。
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(10f, 10.5f); lineTo(19.5f, 20f)
        }
    }.build()
}

/**
 * Slideshow —— PPT(`fileKindIcon` 里 kind=ppt)。
 * Lucide 的 `Presentation` 是投影幕布,跟「一个幻灯片文件」差得远;这里画单张幻灯片。
 */
internal val SlideGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "WbSlide",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = G,
        viewportHeight = G,
    ).apply {
        // 幻灯片主体矩形(用圆角描边 —— ImageVector 没有 roundRect,走四段贝塞尔近似)。
        path(
            stroke = strokeColor(),
            strokeLineWidth = STROKE,
            strokeLineJoin = strokeJoin(),
        ) {
            moveTo(6f, 6f)
            lineTo(18f, 6f)
            lineTo(18f, 15f)
            lineTo(6f, 15f)
            close()
        }
        // 底座。
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 15f); lineTo(12f, 19f)
        }
        path(stroke = strokeColor(), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
            moveTo(8f, 19.5f); lineTo(16f, 19.5f)
        }
        // 画面里一道「内容线」。
        path(stroke = strokeColor(), strokeLineWidth = STROKE * 0.85f, strokeLineCap = StrokeCap.Round) {
            moveTo(8.5f, 12f); lineTo(15.5f, 12f)
        }
    }.build()
}
