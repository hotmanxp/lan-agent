// EngineCredentials.kt — 把「本机引擎用哪个模型、哪个 key」落到引擎认得的位置。
//
// ## 引擎在哪儿读凭据
//
// 引擎跑的是 opencc 的 zai server(core + server),它找凭据有两条路:
//
//   1. `$HOME/.zai.json` → `providerProfiles[]`
//      —— zai server 侧的 modelCaller / resolveModel 直接读这个文件:
//         baseUrl、model、apiKey(或者 apiKeyEnv 指向的 env 变量名)。
//   2. `$ZAI_DATA_DIR/settings.json` → `env{...}`
//      —— core 侧的 getGlobalConfig() 读它:模型别名解析、API key env、
//         permissions.defaultMode 都在这儿。
//
// 两条路都得写:**只有第 1 条**,模型能解析出来但 key 是空的(401);
// **只有第 2 条**,provider 是默认的 anthropic,baseUrl 和 model 落到
// 内置 fallback 上。
//
// ## 为什么不是把整个 ~/.zai 拷过去
//
// 桌面上那份 settings.json 里有几十个字段(plugins、skillUsage、weixinBot…),
// 跟手机上的引擎没关系,还把桌面上的 token 一并带下去。手机上只写需要的
// 几个字段。
//
// ## 存哪儿
//
// 原始输入存在 App 的 DataStore 里(跟 UI 编辑的那份是同一份),
// 每次起引擎前**重新物化**成上面那两个文件 —— 这样用户在设置页改了 key,
// 下次起引擎就生效,不需要额外的一致性维护。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.engineCredentialsDataStore by preferencesDataStore(name = "lan_agent_engine_credentials")

/**
 * 引擎的模型接入配置。
 *
 * @param provider anthropic / openai —— 决定用哪套鉴权头和 URL 规范。
 * @param baseUrl  模型服务的地址(不带尾斜杠)。
 * @param model    模型名。可以是逗号分隔的多个(跟 ~/.zai.json 的语义一样)。
 * @param apiKey   密钥。明文存在 app 私有目录 —— 见类注释末尾的安全说明。
 */
data class EngineCredentials(
    val provider: String = PROVIDER_ANTHROPIC,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
) {
    /** 够不够格让引擎去调模型。 */
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /**
     * 去掉 key 里的**所有**空白字符(baseUrl / model 只 trim 两端)。
     *
     * 为什么必须去:用户复制 key 时,下划线常被打成空格 —— 输入法联想、软键盘
     * 长按空格、PDF/聊天记录复制都会干这事。Node 的 `Headers.append` 拒绝
     * 含空白字符的 header 值,于是报:
     *
     *   API Error: Headers.append: "sk-…Bvp dKQHRk…M1e9 bPMY4…" is an
     *   invalid header value
     *
     * 报错里还会把整个 key 打出来 —— 既难查又漏密钥。所以入口处就掐掉。
     */
    fun sanitized(): EngineCredentials = copy(
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.filterNot { it.isWhitespace() },
    )

    /** [sanitized] 相对当前值去掉几个空白字符(给设置页提示用)。 */
    fun strippedWhitespaceCount(): Int =
        apiKey.length - apiKey.filterNot { it.isWhitespace() }.length

    companion object {
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"

        /** ~/.zai.json 里那条 profile 的 id。写死 —— 引擎端按 id 引用它。 */
        const val PROFILE_ID = "provider_local_engine"
    }
}

object EngineCredentialsStore {

    private val KEY_PROVIDER = stringPreferencesKey("provider")
    private val KEY_BASE_URL = stringPreferencesKey("base_url")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_API_KEY = stringPreferencesKey("api_key")

    /** 读当前配置。没配过就是空的(引擎照样起,只是调不了模型)。 */
    fun flow(context: Context): Flow<EngineCredentials> =
        context.engineCredentialsDataStore.data.map { prefs -> prefs.toCredentials() }

    /**
     * 当前配置(同步读一次)。
     *
     * runBlocking 会阻塞调用线程。调用点都在引擎启动路径上(不是主线程的
     * 每帧),读一次本地 DataStore 是几十微秒的事。
     */
    fun current(context: Context): EngineCredentials = runBlocking {
        context.engineCredentialsDataStore.data.map { it.toCredentials() }.first()
    }

    /** 保存配置。空字符串表示清空。 */
    suspend fun save(context: Context, credentials: EngineCredentials) {
        // 存之前先去空白 —— 见 EngineCredentials.sanitized() 的说明。
        val clean = credentials.sanitized()
        context.engineCredentialsDataStore.edit { prefs ->
            prefs[KEY_PROVIDER] = clean.provider
            prefs[KEY_BASE_URL] = clean.baseUrl
            prefs[KEY_MODEL] = clean.model
            prefs[KEY_API_KEY] = clean.apiKey
        }
    }

    private fun Preferences.toCredentials() = EngineCredentials(
        provider = this[KEY_PROVIDER] ?: EngineCredentials.PROVIDER_ANTHROPIC,
        baseUrl = this[KEY_BASE_URL].orEmpty(),
        model = this[KEY_MODEL].orEmpty(),
        apiKey = this[KEY_API_KEY].orEmpty(),
    )

    /**
     * 把凭据物化成引擎认得的两个文件。
     *
     * 幂等:每次起引擎前都会调一遍,用户改了设置不用管缓存。
     *
     * 没配完整就不写 —— 写半个配置比不写更糟(引擎会去调一个空 baseUrl,
     * 报错信息还特别难懂)。
     *
     * @return true = 写了文件;false = 没配 / 写失败
     */
    fun apply(context: Context, credentials: EngineCredentials = current(context)): Boolean {
        // 物化前再去一次空白 —— 老版本存进去的值可能带空格(用户升级前
        // 填过),不在这儿掐掉的话引擎会拿着非法 header 去调模型。
        val clean = credentials.sanitized()
        if (!clean.isComplete) return false
        return try {
            EnginePaths.ensureDirs(context)
            writeGlobalConfig(context, clean)
            writeSettings(context, clean)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * `$HOME/.zai.json` —— zai server 读 providerProfiles 的地方。
     *
     * 注意是 `$HOME` 不是 `$ZAI_DATA_DIR`:zai server 的 modelCaller 直接
     * `join(homedir(), '.zai.json')`,不认 ZAI_DATA_DIR。
     */
    private fun writeGlobalConfig(context: Context, credentials: EngineCredentials) {
        val file = File(EnginePaths.homeDir(context), ".zai.json")
        val profile = JSONObject().apply {
            put("id", EngineCredentials.PROFILE_ID)
            put("name", "本机引擎")
            put("provider", credentials.provider)
            put("baseUrl", credentials.baseUrl.trimEnd('/'))
            put("model", credentials.model)
            // apiKey 明文写进来 —— modelCaller 的取值顺序是
            // profile.apiKey → env[apiKeyEnv] → 家族级 env。放第一个最稳。
            put("apiKey", credentials.apiKey)
        }
        val root = JSONObject().apply {
            put("providerProfiles", JSONArray().put(profile))
        }
        file.writeText(root.toString())
    }

    /**
     * `$ZAI_DATA_DIR/settings.json` —— core 侧读 env / model / permissions 的地方。
     *
     * `env` 里放的三个值:
     *   - ANTHROPIC_DEFAULT_SONNET_MODEL:会话没指定模型时的兜底
     *   - ANTHROPIC_BASE_URL / OPENAI_BASE_URL:两个家族都给,免得切 provider
     *     的时候忘了改 env(实际生效的是 profile.baseUrl,env 只是兜底)
     *   - ANTHROPIC_AUTH_TOKEN / OPENAI_API_KEY:同上,给 profile.apiKeyEnv 用
     */
    private fun writeSettings(context: Context, credentials: EngineCredentials) {
        val file = File(EnginePaths.dataDir(context), "settings.json")
        val env = JSONObject().apply {
            put("ANTHROPIC_BASE_URL", credentials.baseUrl.trimEnd('/'))
            put("ANTHROPIC_AUTH_TOKEN", credentials.apiKey)
            put("ANTHROPIC_DEFAULT_SONNET_MODEL", credentials.model)
            put("OPENAI_BASE_URL", credentials.baseUrl.trimEnd('/'))
            put("OPENAI_API_KEY", credentials.apiKey)
        }
        val root = JSONObject().apply {
            put("env", env)
            // 引擎跑在手机上,没有「人」点确认 —— 直接放行,否则每个工具调用
            // 都在等一个永远不会来的批准。
            put(
                "permissions",
                JSONObject().apply {
                    put("defaultMode", "bypassPermissions")
                    put("allow", JSONArray())
                },
            )
        }
        file.writeText(root.toString())
    }
}

/**
 * DataStore 的 flow 是 suspend 的,但 [EngineCredentialsStore.current] 要在
 * 非 suspend 的上下文里用(比如 EngineController 的启动路径)。
 *
 * 这里用 runBlocking —— 调用点都在主线程之外(引擎启动路径),阻塞几十微秒
 * 读一次本地文件不成问题。真正的关键是别在 Compose 重组里调它。
 */
