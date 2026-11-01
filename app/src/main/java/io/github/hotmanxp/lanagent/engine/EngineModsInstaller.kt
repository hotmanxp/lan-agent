// EngineModsInstaller.kt — 把手机能力桥的 mod 物化进引擎的 mods 目录。
//
// ## 为什么 mod 跟着 APK 走,而不是躺在引擎包里
//
// 手机能力桥(phone-bridge mod)把「截屏 / 通知 / 剪贴板 / 启动 App /
// 系统设置 / 屏幕操作」注册成引擎的工具。**加一个能力 = 改这个 mod**。
//
// 如果 mod 在引擎包里(34MB 下载),每加一个能力都要:
//   重打引擎包 → 换指纹 → sha256 变了 → **用户重下 34MB**。
// 一个能力改动让用户重下 34MB,这个循环没法转。
//
// 所以 mod 放进 APK assets,起引擎时拷进引擎的 mods 目录 —— 改能力只要
// 重发 APK(39MB,但用户本来就要更新 App)。
//
// ## 拷贝到哪
//
// core 的 mods 系统从磁盘目录加载 mod,目录由 `OPENCC_MODS_DIR` 指过来
// (= `<引擎目录>/mods`,见 [EnginePaths.modsDir])。引擎包解压后那儿是空的,
// 我们把 mod 拷进去。
//
// ## 幂等
//
// 每次起引擎都调一遍(跟 [EngineAgentProfile] 一个套路)。引擎包升级后引擎目录
// 整个换新,旧 mod 自然没了,下次起引擎再拷。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.util.Log
import java.io.File

/** 手机能力桥 mod 的物化。 */
object EngineModsInstaller {

    private const val TAG = "EngineModsInstaller"

    /** APK assets 里的 mod 根目录(与手机端 agent 配置同源)。 */
    private const val ASSET_MODS_DIR = "local-engine/mods"

    /**
     * 把 assets 里的 mod 拷进引擎的 mods 目录。
     *
     * @return 拷进去的 mod 名字(空 = 没有 / 失败)
     */
    fun install(context: Context): List<String> {
        val engineDir = EnginePaths.currentEngineDir(context) ?: return emptyList()
        val modsDir = EnginePaths.modsDir(context)
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            Log.w(TAG, "建 mods 目录失败:${modsDir.absolutePath}")
            return emptyList()
        }

        val installed = mutableListOf<String>()
        // assets 下的每个一级目录 = 一个 mod
        val children = runCatching {
            context.assets.list(ASSET_MODS_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        for (name in children) {
            val assetDir = "$ASSET_MODS_DIR/$name"
            // 是目录才有 manifest;list() 拿不到类型,靠 open 试。
            val manifest = runCatching {
                context.assets.open("$assetDir/opencc-mod.json").use { it.readBytes() }
            }.getOrNull() ?: continue

            val target = File(modsDir, name)
            try {
                target.mkdirs()
                copyDir(context, assetDir, target)
                installed += name
                Log.i(TAG, "mod 已物化:$name → ${target.absolutePath}")
            } catch (e: Throwable) {
                Log.e(TAG, "物化 mod $name 失败", e)
            }
        }
        return installed
    }

    /** 递归拷贝 assets 里的一个目录。 */
    private fun copyDir(context: Context, assetPath: String, target: File) {
        val children = runCatching {
            context.assets.list(assetPath)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        for (child in children) {
            val childPath = "$assetPath/$child"
            // ⚠️ **不能用 `assets.list(childPath)` 判类型** —— 对着一个**文件**
            // 路径调 list,某些 ROM 上返回的是**空数组**而不是 null,于是文件
            // 被当成目录,结果一个字节都没拷出去(而且不报错,「已物化」的日志
            // 照样打出来)。判据改成「能不能打开并读出第一个字节」。
            val isFile = try {
                context.assets.open(childPath).use { it.read(); true }
            } catch (e: Throwable) {
                false
            }
            if (isFile) {
                context.assets.open(childPath).use { input ->
                    File(target, child).outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyDir(context, childPath, File(target, child).apply { mkdirs() })
            }
        }
    }
}
