// ui/AskAnswerTest.kt — AskCard 提交逻辑的回归(对齐 web QuestionCard)。
//
// 钉两件事:
//   1. `buildAskPayload` —— UI 状态(`__other__` 占位 + otherText) → 服务端
//      期待的 answers(单选 = label / Other 文本;多选 = ", " join + Other
//      槽替换)
//   2. `allAnsweredFor` —— Submit 启用条件:Other + 空文本 = 不允许提交
//
// 这两条契约是 web `QuestionCard.tsx` 在 web 端已经实现并测试过的能力,lan-agent
// 这边不能错。同时验 wire 解码侧 `AskOption.preview` / `AskQuestion.multiSelect`
// 新字段不破坏现有路径。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*AskAnswerTest*"
package io.github.hotmanxp.lanagent.ui

import io.github.hotmanxp.lanagent.data.AskQuestion
import io.github.hotmanxp.lanagent.ui.OTHER_LABEL
import io.github.hotmanxp.lanagent.ui.OTHER_VALUE
import io.github.hotmanxp.lanagent.ui.allAnsweredFor
import io.github.hotmanxp.lanagent.ui.buildAskPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private val q = buildJsonObject {
    put("question", "Which approach?")
    put("header", "Topic")
    putJsonArray("options") {
        add(buildJsonObject {
            put("label", "A")
            put("description", "first option")
        })
        add(buildJsonObject {
            put("label", "B")
        })
    }
}

private val qMulti = buildJsonObject {
    put("question", "Pick features")
    put("header", "Features")
    put("multiSelect", true)
    putJsonArray("options") {
        add(buildJsonObject { put("label", "A") })
        add(buildJsonObject { put("label", "B") })
    }
}

private val qWithPreview = buildJsonObject {
    put("question", "Pick a mockup")
    put("header", "UI")
    putJsonArray("options") {
        add(buildJsonObject {
            put("label", "Dark")
            put("preview", "<div>dark theme</div>")
        })
        add(buildJsonObject {
            put("label", "Light")
            put("preview", "<div>light theme</div>")
        })
    }
}

private inline fun <reified T> decode(json: JsonObject): T =
    wireJson.decodeFromString(json.toString())

class AskAnswerTest {

    // ===== buildAskPayload =====

    @Test
    fun `payload single-select regular option returns the label verbatim`() {
        val questions = listOf(decode<AskQuestion>(q))
        val answers = mapOf("Which approach?" to "A")
        val otherTexts = emptyMap<String, String>()

        val payload = buildAskPayload(answers, otherTexts, questions)

        assertEquals(mapOf("Which approach?" to "A"), payload)
    }

    @Test
    fun `payload single-select Other replaces sentinel with actual text`() {
        val questions = listOf(decode<AskQuestion>(q))
        val answers = mapOf("Which approach?" to OTHER_VALUE)
        val otherTexts = mapOf("Which approach?" to "use Rust")

        val payload = buildAskPayload(answers, otherTexts, questions)

        // __other__ 占位符被替换为用户实际输入,服务端收到的就是「user 写了什么」
        assertEquals(mapOf("Which approach?" to "use Rust"), payload)
    }

    @Test
    fun `payload multi-select joins selected labels with comma-space`() {
        val questions = listOf(decode<AskQuestion>(qMulti))
        val answers = mapOf("Pick features" to "A, B")
        val otherTexts = emptyMap<String, String>()

        val payload = buildAskPayload(answers, otherTexts, questions)

        assertEquals(mapOf("Pick features" to "A, B"), payload)
    }

    @Test
    fun `payload multi-select Other replaces sentinel preserving order`() {
        val questions = listOf(decode<AskQuestion>(qMulti))
        // Web QuestionCard 约定 Other 永远 join 在首位(便于槽替换)
        val answers = mapOf("Pick features" to "$OTHER_VALUE, A")
        val otherTexts = mapOf("Pick features" to "custom feature")

        val payload = buildAskPayload(answers, otherTexts, questions)

        assertEquals(mapOf("Pick features" to "custom feature, A"), payload)
    }

    @Test
    fun `payload multi-select Other alone with text returns only the text`() {
        val questions = listOf(decode<AskQuestion>(qMulti))
        val answers = mapOf("Pick features" to OTHER_VALUE)
        val otherTexts = mapOf("Pick features" to "edge case")

        val payload = buildAskPayload(answers, otherTexts, questions)

        assertEquals(mapOf("Pick features" to "edge case"), payload)
    }

    @Test
    fun `payload omits questions with no selection at all`() {
        val questions = listOf(
            decode<AskQuestion>(q),
            decode<AskQuestion>(qMulti),
        )
        val answers = mapOf("Which approach?" to "A")
        val otherTexts = emptyMap<String, String>()

        val payload = buildAskPayload(answers, otherTexts, questions)

        // 第二题没答 → 不出现在 payload 里(服务端按缺失处理)
        assertEquals(mapOf("Which approach?" to "A"), payload)
    }

    // ===== allAnsweredFor =====

    @Test
    fun `allAnswered single-select regular option is true`() {
        val questions = listOf(decode<AskQuestion>(q))
        assertTrue(
            allAnsweredFor(
                answers = mapOf("Which approach?" to "A"),
                otherTexts = emptyMap(),
                questions = questions,
            )
        )
    }

    @Test
    fun `allAnswered single-select Other with empty text is false`() {
        val questions = listOf(decode<AskQuestion>(q))
        assertFalse(
            allAnsweredFor(
                answers = mapOf("Which approach?" to OTHER_VALUE),
                // 全空白都不算「已答」
                otherTexts = mapOf("Which approach?" to "   "),
                questions = questions,
            )
        )
    }

    @Test
    fun `allAnswered single-select Other with text is true`() {
        val questions = listOf(decode<AskQuestion>(q))
        assertTrue(
            allAnsweredFor(
                answers = mapOf("Which approach?" to OTHER_VALUE),
                otherTexts = mapOf("Which approach?" to "anything"),
                questions = questions,
            )
        )
    }

    @Test
    fun `allAnswered multi-select Other with empty text is false`() {
        val questions = listOf(decode<AskQuestion>(qMulti))
        assertFalse(
            allAnsweredFor(
                answers = mapOf("Pick features" to "A, $OTHER_VALUE"),
                otherTexts = mapOf("Pick features" to ""),
                questions = questions,
            )
        )
    }

    @Test
    fun `allAnswered multi-select regular options only is true`() {
        val questions = listOf(decode<AskQuestion>(qMulti))
        assertTrue(
            allAnsweredFor(
                answers = mapOf("Pick features" to "A"),
                otherTexts = emptyMap(),
                questions = questions,
            )
        )
    }

    @Test
    fun `allAnswered unanswered question makes the whole card not submit`() {
        val questions = listOf(decode<AskQuestion>(q), decode<AskQuestion>(qMulti))
        assertFalse(
            allAnsweredFor(
                answers = mapOf("Which approach?" to "A"),
                otherTexts = emptyMap(),
                questions = questions,
            )
        )
    }

    // ===== wire decode =====

    @Test
    fun `AskQuestion decodes without multiSelect field as default false`() {
        // 旧 wire 没有 multiSelect 字段,要按默认 false 解(单选)
        val question = decode<AskQuestion>(q)
        assertFalse(question.multiSelect)
        assertEquals(2, question.options.size)
    }

    @Test
    fun `AskQuestion decodes multiSelect true`() {
        val question = decode<AskQuestion>(qMulti)
        assertTrue(question.multiSelect)
    }

    @Test
    fun `AskOption decodes preview field as nullable string`() {
        val question = decode<AskQuestion>(qWithPreview)
        assertEquals(2, question.options.size)
        assertEquals("<div>dark theme</div>", question.options[0].preview)
        assertNull(question.options[0].description)
    }

    @Test
    fun `AskQuestion with preview-free options decodes preview as null`() {
        val question = decode<AskQuestion>(q)
        question.options.forEach {
            assertNull(it.preview)
        }
    }

    // ===== 常量 =====
    @Test
    fun `OTHER_VALUE sentinel matches web QuestionCard convention`() {
        // 与 opencc-web packages/zai/src/web/src/components/QuestionCard.tsx
        // 的 OTHER_OPTION_VALUE = '__other__' 保持一致 —— 两端协同契约
        assertEquals("__other__", OTHER_VALUE)
        assertEquals("Other", OTHER_LABEL)
    }
}