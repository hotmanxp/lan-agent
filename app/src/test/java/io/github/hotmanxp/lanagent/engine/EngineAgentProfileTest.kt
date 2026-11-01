package io.github.hotmanxp.lanagent.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 手机端主 Agent 配置的静态检查。
 *
 * 这个配置文件是**纯 JS 资源**(随 APK 走,不进引擎包),JVM 单测读不了 APK 里的
 * assets,但读得到源码树里的那份 —— 用它守住两件事:
 *   1. 文件在(改名/挪目录会立刻红);
 *   2. 关键约定还在(提示词讲清手机环境、工具黑名单没被削)。
 */
class EngineAgentProfileTest {

    private val asset = File("src/main/assets/local-engine/mobile-main-agent.js")

    private fun source(): String {
        assertTrue(asset.isFile, "手机端 agent 配置文件不在:${asset.absolutePath}")
        return asset.readText()
    }

    @Test
    fun `配置文件存在且导出 main-agent 形状`() {
        val src = source()
        assertTrue(src.contains("module.exports"), "外置 agent 必须导出 module.exports")
        assertTrue(src.contains("name: 'mobile'"), "agent 名必须是 mobile(settings.json 里按它选)")
        assertTrue(src.contains("systemPrompt:"), "必须有 systemPrompt 槽")
        assertTrue(src.contains("tools:"), "必须有 tools 槽")
    }

    @Test
    fun `提示词交代了手机环境`() {
        val src = source()
        for (marker in listOf(
            "Android phone",       // 设备
            "files/workspace",     // 工作目录
            "node",                // 有 node
            "phone_screenshot",    // 手机能力
            "Simplified Chinese",  // 回复语言
        )) {
            assertTrue(src.contains(marker), "提示词里少了关键约定:$marker")
        }
    }

    @Test
    fun `依赖电脑的工具在黑名单里`() {
        val src = source()
        for (tool in listOf("CliAgent", "CronCreate", "EnterWorktree", "LSP", "PowerShell")) {
            assertTrue(src.contains("'$tool'"), "黑名单里少了:$tool")
        }
    }

    @Test
    fun `没有误砍手机能力层`() {
        val src = source()
        // mod 工具带 mcp__<mod>__ 前缀 —— 黑名单里出现它就等于砍掉了手机能力。
        assertTrue(
            !src.contains("'mcp__phone-bridge__phone_screenshot'"),
            "手机能力层工具不能进黑名单",
        )
    }
}
