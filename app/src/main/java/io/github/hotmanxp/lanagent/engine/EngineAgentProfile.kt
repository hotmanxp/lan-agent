// EngineAgentProfile.kt — 把「手机端专用主 Agent 配置」物化到引擎认得的位置。
//
// ## 这套配置是什么
//
// opencc 的主 Agent 有三个插槽(systemPrompt / tools / mcp),可以被 agent 配置
// 整体替换 —— 见 core 的 `server/mainAgents.ts`。微信通道有一份专用的
// (`mainAgents-weixin.ts`),手机端也该有一份:提示词讲清「你在手机上」,
// 工具池砍掉依赖电脑的那些(CliAgent / 定时任务 / LSP / 桌面流程)。
//
// ## 为什么用「外置 agent」而不是改 core
//
// - 手机专属的提示词与工具策略,不该跟着 core 升级漂到桌面实例上;
// - 改 core 要重打 7.5MB 的 core bundle → 引擎包换指纹 → 用户重下 34MB。
//   外置文件跟着 APK 走,改提示词只要重发 APK。
//
// ## 引擎怎么找到它
//
// 引擎启动时 initAgentRuntime 扫 `$ZAI_DATA_DIR/main-agents/*.js`,逐个 import,
// 取 default 导出注册进 AgentRegistry(core: `server/agentRegistry.ts`)。
// 之后 `settings.json` 的 `mainAgent: "mobile"` 让**新会话**选中它
// (会话在第一次 prompt 时把 agent 冻结进 transcript.meta.mainAgent)。
//
// ## 失败怎么办
//
// 物化失败不抛 —— 引擎照常起,只是 agent 落回 default(桌面的编码 agent)。
// 那意味着提示词会讲「你在电脑上」,比没有手机配置更糟,但不至于不能聊。
// 所以失败要打日志。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** 手机端主 Agent 配置的物化。 */
object EngineAgentProfile {

    private const val TAG = "EngineAgentProfile"

    /** agent 名 —— settings.json 的 mainAgent 与文件名都用它。 */
    const val MAIN_AGENT_NAME = "mobile"

    /** APK assets 里的配置文件(随 App 走,改提示词不用重下引擎包)。 */
    private const val ASSET_PATH = "local-engine/mobile-main-agent.js"

    /**
     * 物化:写 `$ZAI_DATA_DIR/main-agents/mobile.js` + 把 settings.json 的
     * mainAgent 设成 "mobile"。
     *
     * 幂等 —— 每次起引擎都调一遍。
     *
     * @return true = 物化成功(引擎会用手机 agent)
     */
    fun apply(context: Context): Boolean {
        return try {
            val dataDir = EnginePaths.dataDir(context)
            val dir = File(dataDir, "main-agents")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "建 main-agents 目录失败:${dir.absolutePath}")
                return false
            }
            val target = File(dir, "$MAIN_AGENT_NAME.js")
            context.assets.open(ASSET_PATH).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "手机端 agent 配置已物化:${target.absolutePath}")

            val ok = setMainAgentSetting(context)
            if (!ok) Log.w(TAG, "settings.json 写 mainAgent 失败")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "物化手机端 agent 配置失败(引擎会落回 default agent)", e)
            false
        }
    }

    /**
     * 把 `mainAgent: "mobile"` 合并进 settings.json。
     *
     * **读改写**,不是覆盖 —— 同一个文件里还有引擎要的 `env`(模型地址 / 密钥)
     * 和 `permissions`(免批准模式),覆盖会把它们抹掉。
     */
    private fun setMainAgentSetting(context: Context): Boolean {
        val file = File(EnginePaths.dataDir(context), "settings.json")
        val root = if (file.isFile) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        root.put("mainAgent", MAIN_AGENT_NAME)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
        return true
    }
}
