// EngineInstaller.kt — 引擎「装没装」的判据 + 已装引擎的位置。
//
// ## 为什么这里不再有「解包」
//
// 0.29.0 之前,引擎包在 APK 的 assets 里,App 启动时把它拷到 filesDir。
// 现在引擎包改成**按需下载**(见 [EngineBundle]):用户在设置页点「下载引擎」,
// 下载 → 校验 sha256 → 解压到 filesDir/engine/<指纹>/ 与 filesDir/native/。
//
// 所以这个类现在只回答两个问题:
//   1. 装没装?([isInstalled])
//   2. 装好的引擎在哪?([install])
//
// ## 为什么原生库也要算进「装没装」
//
// 引擎进程起来第一件事就是 `System.load(libnode.so)` —— 库不在就直接
// UnsatisfiedLinkError。而「引擎包在、原生库不在」是**可能发生的中间态**
// (下载解了一半、或者只解了引擎那部分)。那时候引擎入口还显示着「可用」,
// 点下去却起不来 —— 所以判据要两个都齐。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.util.Log
import java.io.File

object EngineInstaller {

    private const val TAG = "EngineInstaller"

    /**
     * 引擎起不来的最小集。
     *
     * libnode.so 是 Node 24 运行时本体;libdshengine.so 是 JNI 壳
     * (node::Start);libdshnode.so 是给引擎子进程用的 node 启动器
     * (MCP server);libc++_shared.so 是 NDK 的 C++ 运行时。
     *
     * 少任何一个都是「装上了但引擎起不来」,所以 [isInstalled] 要全查:
     * 少一个 libdshengine.so 就在 System.load 那里 UnsatisfiedLinkError 崩掉,
     * 那时候已经开了个「本机引擎」入口给用户了。
     */
    val REQUIRED_NATIVES = listOf(
        "libnode.so",
        "libdshengine.so",
        "libdshnode.so",
        "libc++_shared.so",
    )

    /** ripgrep —— Grep / Glob 工具用。缺了只是那两个工具废,不影响引擎起来。 */
    private const val RG_NATIVE = "librg.so"

    /**
     * 引擎装好了没有。
     *
     * 判据 = 原生库齐全 **且** 有个装好的引擎目录(带 `.installed` 标记)。
     */
    fun isInstalled(context: Context): Boolean {
        val nativeDir = EnginePaths.nativeDir(context)
        val nativesReady = REQUIRED_NATIVES.all { File(nativeDir, it).isFile }
        if (!nativesReady) return false
        return installedEngineDir(context) != null
    }

    /**
     * 已装好的引擎目录;没装返回 null。
     *
     * 有多个目录时取**最新写入的**那个 —— 安装流程会先解到新目录再切换,
     * 旧的还没来得及删时这里拿到的是新的。
     */
    fun installedEngineDir(context: Context): File? =
        EnginePaths.engineRoot(context).listFiles()
            ?.filter { it.isDirectory && File(it, ".installed").isFile }
            ?.maxByOrNull { it.lastModified() }

    /**
     * 已装好的引擎目录。
     *
     * @throws IllegalStateException 引擎没装(调用方应该先判 [isInstalled])
     */
    fun install(context: Context): File =
        installedEngineDir(context)
            ?: throw IllegalStateException("本机引擎没装 —— 先在设置页下载引擎")

    /** 原生库齐不齐;不齐的话把缺的那些列出来(给 UI 显示原因用)。 */
    fun missingNatives(context: Context): List<String> {
        val nativeDir = EnginePaths.nativeDir(context)
        val missing = REQUIRED_NATIVES.filterNot { File(nativeDir, it).isFile }
        if (!File(nativeDir, RG_NATIVE).isFile) {
            Log.w(TAG, "缺 $RG_NATIVE —— Grep / Glob 工具会降级(引擎照常起)")
        }
        return missing
    }
}
