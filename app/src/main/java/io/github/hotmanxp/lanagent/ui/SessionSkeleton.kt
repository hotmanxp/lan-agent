// ui/SessionSkeleton.kt — 任务页(原生 Agent 工作区)加载骨架屏。
//
// 抄 AA `aa/ui/screens/sessiondetail/SessionMessages.kt:150` 的
// `SessionDetailLoadingState`:同一套「横条占位符 + shimmer 扫光」。之前这里
// 是 `CenterSpinner()`(裸 `CircularProgressIndicator`)—— 页面拉 transcript
// 期间屏幕正中央孤零零转个圈,数据到位后内容从 0 跳到满屏,视觉上很跳。
// 骨架先摆出「一段对话大概长这样」的轮廓,数据到位后原地替换,没有那一下断层。
//
// 动画用 AA 同款 `com.valentinilk.shimmer`(aa/ 在用,依赖已在本 module),
// **颜色走本页自己的 WorkBuddy 色板**,不用 AA 的 `LocalAAColors` —— 本页是
// lan-agent 原生屏,不是 AA 屏,两套色板混用会串。深浅切 `LocalWbDarkTheme`,
// 理由同 `CodeHighlight.kt`:设置栏可手选亮/暗,`isSystemInDarkTheme()` 会
// 跟页面真实明暗打架。
package io.github.hotmanxp.lanagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer

/**
 * 会话内容加载中的占位骨架。
 *
 * 底部对齐 —— 真列表是 `reverseLayout`(index 0 贴底),骨架贴着输入条摆才和
 * 数据到位后的落点一致;顶部留白会让人以为内容从上面长出来。
 */
@Composable
internal fun SessionSkeleton(modifier: Modifier = Modifier) {
    val dark = LocalWbDarkTheme.current
    val line = if (dark) WbPalette.SkeletonLineDark else WbPalette.SkeletonLineLight
    val card = if (dark) WbPalette.CardDark else WbPalette.CardLight
    val hairline = if (dark) WbPalette.HairlineDark else WbPalette.HairlineLight
    val bubble = if (dark) WbPalette.BubbleDark else WbPalette.BubbleLight

    Column(
        modifier = modifier
            .fillMaxSize()
            .shimmer()
            .padding(PaddingValues(horizontal = 12.dp, vertical = 12.dp)),
        // 与真列表 `verticalArrangement = spacedBy(10.dp)` 对齐,块间距不跳。
        verticalArrangement = Arrangement.Bottom,
    ) {
        Spacer(Modifier.height(12.dp))

        // 一段 agent 正文(全宽,行宽参差 —— 末行短是正文最明显的轮廓特征)
        SkeletonLines(line, listOf(0.86f, 0.68f, 0.44f))
        Spacer(Modifier.height(10.dp))

        // 工具组卡片:白卡 + 左侧一颗点 + 一条标题线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(card)
                .border(1.dp, hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBlock(Modifier.size(12.dp), line, CircleShape)
                SkeletonBlock(Modifier.width(112.dp).height(12.dp), line, RoundedCornerShape(6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        // 用户气泡:右对齐 + 大圆角,和左对齐的正文一眼分得开。
        // 22dp 必须跟 UserBubble 的真气泡一致(0.26.2 起)——骨架只占位,
        // 半径对不上会在 hydrate 那一刻看出一个圆角跳变。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SkeletonBlock(
                Modifier
                    .fillMaxWidth(0.36f)
                    .height(40.dp),
                bubble,
                RoundedCornerShape(22.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        // 再一段正文,行数更多 —— 让骨架高度接近一屏真实对话,避免数据到位时
        // 整页往下推一大截。
        SkeletonLines(line, listOf(0.80f, 0.92f, 0.62f, 0.38f))
    }
}

/** 若干条左对齐的正文占位条。[widths] 是每行占屏宽比例。 */
@Composable
private fun SkeletonLines(color: Color, widths: List<Float>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        widths.forEach { fraction ->
            SkeletonBlock(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp),
                color,
                RoundedCornerShape(7.dp),
            )
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, color: Color, shape: Shape) {
    Box(modifier = modifier.clip(shape).background(color))
}
