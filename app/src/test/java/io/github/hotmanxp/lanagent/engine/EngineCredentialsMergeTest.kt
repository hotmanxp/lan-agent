package io.github.hotmanxp.lanagent.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 凭据物化必须是**合并**而不是整份重写。
 *
 * 背景:App 每次起引擎都会把 DataStore 里的凭据重新物化成
 * `$ZAI_DATA_DIR/settings.json` 和 `$HOME/.zai.json`。原来是整份重写
 * (`val root = JSONObject()`),于是:
 *   - 用户手写的 env 变量(比如 agent 加的 MINIMAX_API_KEY)每次起引擎被冲掉;
 *   - `.zai.json` 里用户自己的 provider profile 被整条抹掉。
 * 症状是「改了文件、存不住」—— 写进去的东西在下一次引擎启动时消失。
 */
class EngineCredentialsMergeTest {

    private val complete = EngineCredentials(
        provider = EngineCredentials.PROVIDER_ANTHROPIC,
        baseUrl = "https://api.example.com/",
        model = "m3",
        apiKey = "sk-new",
    )

    @Test
    fun `settings 合并保留用户手写的 env 变量`() {
        val root = JSONObject()
            .put("env", JSONObject().put("MINIMAX_API_KEY", "sk-user").put("CUSTOM", "1"))
            .put("mainAgent", "mobile")

        mergeSettingsJson(root, complete)

        val env = root.getJSONObject("env")
        assertEquals("sk-user", env.getString("MINIMAX_API_KEY"))
        assertEquals("1", env.getString("CUSTOM"))
    }

    @Test
    fun `settings 合并覆盖 App 自己管的 5 个 env 键`() {
        val root = JSONObject().put(
            "env",
            JSONObject().put("ANTHROPIC_BASE_URL", "https://old").put("ANTHROPIC_AUTH_TOKEN", "old-key"),
        )

        mergeSettingsJson(root, complete)

        val env = root.getJSONObject("env")
        assertEquals("https://api.example.com", env.getString("ANTHROPIC_BASE_URL"))
        assertEquals("sk-new", env.getString("ANTHROPIC_AUTH_TOKEN"))
        assertEquals("m3", env.getString("ANTHROPIC_DEFAULT_SONNET_MODEL"))
        assertEquals("https://api.example.com", env.getString("OPENAI_BASE_URL"))
        assertEquals("sk-new", env.getString("OPENAI_API_KEY"))
    }

    @Test
    fun `settings 合并保留其它顶层字段`() {
        val root = JSONObject().put("mainAgent", "mobile").put("plugins", JSONArray().put("x"))

        mergeSettingsJson(root, complete)

        assertEquals("mobile", root.getString("mainAgent"))
        assertEquals(1, root.getJSONArray("plugins").length())
    }

    @Test
    fun `permissions 强制 bypassPermissions 但保留 allow 列表`() {
        val root = JSONObject().put(
            "permissions",
            JSONObject().put("defaultMode", "ask").put("allow", JSONArray().put("Bash(rm:*)")),
        )

        mergeSettingsJson(root, complete)

        val permissions = root.getJSONObject("permissions")
        assertEquals("bypassPermissions", permissions.getString("defaultMode"))
        assertEquals("Bash(rm:*)", permissions.getJSONArray("allow").getString(0))
    }

    @Test
    fun `凭据没配全时不写 env 也不动已有的 env`() {
        val root = JSONObject().put("env", JSONObject().put("MINIMAX_API_KEY", "sk-user"))

        mergeSettingsJson(root, EngineCredentials(apiKey = "only-key"))

        assertEquals("sk-user", root.getJSONObject("env").getString("MINIMAX_API_KEY"))
        assertFalse(root.getJSONObject("env").has("ANTHROPIC_BASE_URL"))
    }

    @Test
    fun `zai json 合并保留别人的 provider profile`() {
        val other = JSONObject().put("id", "other").put("baseUrl", "https://other")
        val root = JSONObject().put("providerProfiles", JSONArray().put(other))

        mergeGlobalConfigJson(root, complete)

        val profiles = root.getJSONArray("providerProfiles")
        assertEquals(2, profiles.length())
        assertEquals("other", profiles.getJSONObject(0).getString("id"))
        assertEquals(EngineCredentials.PROFILE_ID, profiles.getJSONObject(1).getString("id"))
    }

    @Test
    fun `zai json 合并替换自己那条而不重复追加`() {
        val stale = JSONObject().put("id", EngineCredentials.PROFILE_ID).put("apiKey", "old")
        val root = JSONObject().put("providerProfiles", JSONArray().put(stale))

        mergeGlobalConfigJson(root, complete)
        mergeGlobalConfigJson(root, complete)

        val profiles = root.getJSONArray("providerProfiles")
        assertEquals(1, profiles.length())
        assertEquals("sk-new", profiles.getJSONObject(0).getString("apiKey"))
        assertEquals("https://api.example.com", profiles.getJSONObject(0).getString("baseUrl"))
    }

    @Test
    fun `zai json 合并保留其它顶层字段`() {
        val root = JSONObject().put("someOtherTool", "keep-me")

        mergeGlobalConfigJson(root, complete)

        assertTrue(root.has("someOtherTool"))
        assertEquals("keep-me", root.getString("someOtherTool"))
    }
}
