// ui/MermaidDiagramLabelTest.kt — 卡片 header 上的图类型中文名
//
// 值得钉的原因:这张表是照抄 opencc-web `mermaidRenderer.ts` 的 TYPE_LABELS,
// 两端 header 要说同一种话;抄漏一条不会让图渲染不出来,只会让某类图的标题变成
// 兜底的「图表」—— 那种退化在真机上根本看不出来(图是对的,只是标题泛了),
// 放在单测里钉比事后翻 git 便宜。
//
// 同时钉住「首行有效声明」的取值规则:mermaid 允许图前面放 `%%{init}%%` 指令块
// 和整行注释,直接读第一行会把这些当成类型头。
package io.github.hotmanxp.lanagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MermaidDiagramLabelTest {

    private fun label(head: String) = mermaidDiagramLabel("$head\n  A --> B")

    @Test
    fun `流程图两写法都命中`() {
        assertEquals("流程图", label("flowchart TD"))
        assertEquals("流程图", label("graph LR"))
    }

    @Test
    fun `主干图型`() {
        assertEquals("时序图", label("sequenceDiagram"))
        assertEquals("类图", label("classDiagram"))
        assertEquals("状态图", label("stateDiagram-v2"))
        assertEquals("状态图", label("stateDiagram"))
        assertEquals("ER 图", label("erDiagram"))
        assertEquals("甘特图", label("gantt"))
        assertEquals("思维导图", label("mindmap"))
        assertEquals("思维导图", label("gitGraph"))
        assertEquals("旅程图", label("journey"))
        assertEquals("旅程图", label("timeline"))
        assertEquals("桑基图", label("sankey-beta"))
        assertEquals("桑基图", label("sankey"))
    }

    @Test
    fun `统计图型都归到图表`() {
        assertEquals("图表", label("pie"))
        assertEquals("图表", label("pie showData"))
        assertEquals("图表", label("xychart-beta"))
        assertEquals("图表", label("quadrantChart"))
        assertEquals("图表", label("requirementDiagram"))
    }

    @Test
    fun `架构图与看板族`() {
        assertEquals("架构图", label("C4Context"))
        assertEquals("架构图", label("C4Container"))
        assertEquals("架构图", label("C4Component"))
        assertEquals("图", label("kanban"))
        assertEquals("图", label("radar-beta"))
        assertEquals("图", label("treemap"))
        assertEquals("图", label("packet-beta"))
        assertEquals("图", label("block-beta"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals("时序图", mermaidDiagramLabel("SequenceDiagram\nA->>B: x"))
        assertEquals("流程图", mermaidDiagramLabel("FLOWCHART TD\nA-->B"))
    }

    @Test
    fun `跳过开头的空行与百分号注释`() {
        assertEquals("流程图", mermaidDiagramLabel("\n\n%% 这是注释\nflowchart TD\nA-->B"))
        assertEquals(
            "流程图",
            mermaidDiagramLabel("%%{init: {'theme':'dark'}}%%\nflowchart TD\nA-->B"),
        )
    }

    @Test
    fun `认不出就回落图表`() {
        assertEquals("图表", mermaidDiagramLabel("随便一段文字"))
        assertEquals("图表", mermaidDiagramLabel(""))
        assertEquals("图表", mermaidDiagramLabel("   \n  \n"))
    }

    @Test
    fun `不把包含关系的名字误判成类型头`() {
        // flowchart 是行首匹配:mermaidRender / mygraph 不该命中「流程图」
        assertEquals("图表", mermaidDiagramLabel("mermaidRender\nA-->B"))
        assertEquals("图表", mermaidDiagramLabel("mygraph TD\nA-->B"))
    }
}
