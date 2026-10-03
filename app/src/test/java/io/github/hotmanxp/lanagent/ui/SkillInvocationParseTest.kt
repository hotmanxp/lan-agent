// ui/SkillInvocationParseTest.kt — 「Skill 注入识别」防御层测试。
//
// SkillTool 把 SKILL.md 内容以 `Base directory for this skill: <dir>\n\n<body>`
// 形态写进 transcript user 消息,理论上是 isMeta=true,AgentSessionStore
// hydrateTranscript 在 line 380 会按 isMeta 跳过。但万一 isMeta 没落盘,
// 这条 user.text 会带着完整 SKILL.md 渲染成大段灰色气泡。渲染层
// `parseSkillInvocation` 做兜底: 检测到这个起头就只画 Skill 名 pill。
//
// 跑法: ./gradlew :app:testDebugUnitTest --tests "*SkillInvocationParseTest*"
package io.github.hotmanxp.lanagent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SkillInvocationParseTest {

    @Test
    fun `unix style path`() {
        val text = "Base directory for this skill: /Users/ethan/.agents/skills/port-claude-tool-features\n\n" +
            "# port-claude-tool-features\n\nPorts tool-level features."
        val parsed = parseSkillInvocation(text)
        assertNotNull(parsed)
        assertEquals("port-claude-tool-features", parsed.skillName)
    }

    @Test
    fun `relative path with trailing slash`() {
        val text = "Base directory for this skill: ./skills/ego-browser/\n\nbody"
        val parsed = parseSkillInvocation(text)
        assertNotNull(parsed)
        // 结尾斜杠的 segment 是空,filter 后取到上一个
        assertEquals("ego-browser", parsed.skillName)
    }

    @Test
    fun `windows style path with backslashes`() {
        val text = "Base directory for this skill: C:\\Users\\me\\.agents\\skills\\my-skill\n\nbody"
        val parsed = parseSkillInvocation(text)
        assertNotNull(parsed)
        assertEquals("my-skill", parsed.skillName)
    }

    @Test
    fun `non-skill text is rejected`() {
        // 普通用户输入的 user.text 不能误判
        assertNull(parseSkillInvocation("hello"))
        assertNull(parseSkillInvocation(""))
        assertNull(parseSkillInvocation("Base directory for this skill")) // 缺冒号空格
        assertNull(parseSkillInvocation("base directory for this skill: foo")) // 小写不算
        assertNull(parseSkillInvocation("看一下 port-claude-tool-features 的代码")) // 没有前缀
    }

    @Test
    fun `empty path returns null`() {
        // 起头对了但路径为空 —— 罕见但格式合法, 不应崩
        assertNull(parseSkillInvocation("Base directory for this skill: \n\nbody"))
    }

    @Test
    fun `body is ignored, only first line counts`() {
        // 路径后面跟什么不影响识别 —— SKILL.md 的 markdown body 可能有奇怪的格式
        val text = "Base directory for this skill: /tmp/x/super-special\n\n" +
            "# super-special\n\nThis is a skill\n\nWith multiple\n\nparagraphs."
        val parsed = parseSkillInvocation(text)
        assertNotNull(parsed)
        assertEquals("super-special", parsed.skillName)
    }

    @Test
    fun `single segment path`() {
        // 路径只有一个段(裸 skill 名)
        val text = "Base directory for this skill: my-skill\n\nbody"
        val parsed = parseSkillInvocation(text)
        assertNotNull(parsed)
        assertEquals("my-skill", parsed.skillName)
    }
}