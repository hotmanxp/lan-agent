// EngineShims.kt — 给引擎用的可执行入口(软链)。
//
// ## 为什么需要这个
//
// 引擎(core bundle)要两样 Android 上默认没有的东西:
//
// 1. **一个「叫 bash 的 shell」** —— opencc 的 findSuitableShell()
//    (`zn-agent-core/src/opencc-src/utils/Shell.ts:74`) 硬性要求 shell 路径
//    **含 "bash" 或 "zsh"**:`shellOverride.includes('bash') || shellOverride.includes('zsh')`,
//    而且要过 isExecutable。`SHELL` 环境变量走的是同一套判断。
//    Android 自带的是 /system/bin/sh(toybox),名字里没有 bash → 直接被拒,
//    抛 "No suitable shell found",**BashTool 全废**。
//
//    解法不是打一个静态 bash 进 APK(几十 MB),而是建一个叫 "bash" 的软链
//    指向 /system/bin/sh。Android 34 的 toybox sh 实测支持 [[ ]]、数组、
//    `set -o pipefail` —— opencc 的 BashTool 用到的语法它都有。
//
// 2. **ripgrep** —— opencc 的 vendor 里只有 darwin-arm64 / darwin-x64 /
//    win32-x64,**没有 android**。但它的 resolveRgSystem()
//    (`zn-agent-core/src/compat/vendor/ripgrep.ts:96`) 走的是 `which rg`,
//    所以只要 PATH 里有个叫 rg 的可执行文件就行 —— 不用改 opencc 源码。
//    librg.so 随 DSH-Android 的原生包一起(ripgrep 15.2.0,带 pcre2),
//    0.29.0 起跟别的原生库一样是**运行时下载**的,解到 filesDir/native/。
//
// ## 为什么放在 app 私有目录
//
// filesDir 下的软链是 app 自己的东西:不碰 /system、不需要 root、卸载即清。
// Android 允许在 app 私有目录里建指向系统路径的绝对软链。

package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.util.Log
import java.io.File

/** 建引擎用的软链。 */
object EngineShims {

    private const val TAG = "EngineShims"

    /**
     * 建好这两个软链。幂等 —— 每次引擎启动都会调一遍。
     *
     * 失败**不抛异常**:软链建不出来不该让整个引擎起不来。顶多是
     * BashTool / GrepTool 退化(工具报 "shell not found" / "ripgrep 未安装"),
     * 其余功能照常。真正致命的只有引擎包本身解不开。
     */
    fun install(context: Context) {
        val bin = EnginePaths.binDir(context)
        if (!bin.exists() && !bin.mkdirs()) {
            Log.w(TAG, "建 bin 目录失败:${bin.absolutePath}")
            return
        }

        // bash → /system/bin/sh
        link(EnginePaths.shellShim(context), "/system/bin/sh", "bash")

        // rg → 下载下来的 librg.so(filesDir/native/)
        //
        // 目标不再是 nativeLibraryDir —— 0.29.0 起原生库是运行时下载的,
        // 躺在 app 私有目录里,不在 APK 里。
        val rg = EnginePaths.rgShim(context)
        val librg = File(EnginePaths.nativeDir(context), "librg.so")
        if (librg.exists()) {
            link(rg, librg.absolutePath, "rg")
        } else {
            Log.w(TAG, "没有 librg.so,ripgrep 用不了(Grep/Glob 工具会降级)")
        }
    }

    /**
     * 建软链;已经指向正确目标就什么都不做。
     *
     * 先删再建 —— Android 的 [File.createSymbolicLink] 在目标已存在时会抛
     * FileAlreadyExistsException,而「重建」是这里最常见的情况(每次启动都调)。
     */
    private fun link(target: File, dest: String, label: String) {
        runCatching {
            if (target.exists()) {
                // 已经是软链且指向对了就跳过
                val current = runCatching { target.canonicalPath }.getOrNull()
                val want = runCatching { File(dest).canonicalPath }.getOrNull()
                if (current != null && current == want) return
                target.delete()
            } else {
                target.parentFile?.mkdirs()
            }
            android.system.Os.symlink(dest, target.absolutePath)
            Log.i(TAG, "软链 $label → $dest")
        }.onFailure {
            Log.w(TAG, "建软链 $label 失败(工具会降级,但引擎照常起)", it)
        }
    }
}
