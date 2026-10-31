// EngineInstaller.kt — 把 assets 里的引擎包解到 filesDir。
//
// ## 为什么不直接用 assets 里的文件
//
// Node 要的是**真实的文件系统路径**(import、fs.open 都按路径走),
// APK 里的 assets 是压缩在包内的,Node 读不到。所以第一次用之前必须解一份
// 到 app 私有目录。
//
// ## 为什么按指纹分目录
//
// 引擎包升级后内容指纹会变 → 目录名变 → 自动解一份新的、旧的删掉。
// 原地覆盖的话,升级解到一半被杀会留下「一半新一半旧」的目录 —— 那是最难
// 查的一种坏法(表现为某些工具能调、某些不能)。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.util.Log
import java.io.File

object EngineInstaller {

    private const val TAG = "EngineInstaller"

    /** 解压完成的标记文件。写它之前崩溃 → 下次重解。 */
    private const val MARKER = ".installed"

    /**
     * 引擎要用的原生库。缺任何一个都起不来(所以 isInstalled 要查它们)。
     *
     * libnode.so 是 Node 24 运行时本体;libdshengine.so 是 JNI 壳
     * (node::Start);libdshnode.so 是给引擎子进程用的 node 启动器
     * (pnpm / MCP server);libc++_shared.so 是 NDK 的 C++ 运行时。
     */
    private val REQUIRED_NATIVES = listOf(
        "libnode.so",
        "libdshengine.so",
        "libdshnode.so",
        "libc++_shared.so",
    )

    /**
     * 解出可用的引擎目录;已经解过就直接返回。
     *
     * @throws IllegalStateException assets 里没有引擎包(没跑 tools/build-engine-pkg.mjs)
     */
    fun install(context: Context): File {
        val fingerprint = readFingerprint(context)
        val dir = EnginePaths.engineDir(context, fingerprint)
        val marker = File(dir, MARKER)
        if (marker.exists()) return dir

        Log.i(TAG, "解引擎包 → $dir (指纹 ${fingerprint.take(16)})")
        dir.deleteRecursively()
        dir.mkdirs()
        copyAssetTree(context, EnginePaths.ASSET_ENGINE_DIR, dir)
        marker.writeText(fingerprint)

        // 旧版本的目录清掉,免得 filesDir 越滚越大(每次引擎升级都留一份 12MB)。
        EnginePaths.engineRoot(context).listFiles()?.forEach { old ->
            if (old != dir) {
                Log.i(TAG, "清掉旧引擎目录 ${old.name}")
                old.deleteRecursively()
            }
        }
        EngineShims.install(context)
        return dir
    }

    /**
     * 读构建脚本写的指纹。
     *
     * 指纹同时当「引擎包在不在」的判据:文件不存在 = 压根没构建过引擎包,
     * 这时候要报清楚的错(「先跑 tools/build-engine-pkg.mjs」),而不是等
     * Node 起来报一句看不懂的 module not found。
     */
    fun readFingerprint(context: Context): String {
        val text = runCatching {
            context.assets.open(EnginePaths.ASSET_SHA_FILE).bufferedReader().use { it.readText() }
        }.getOrElse { e ->
            throw IllegalStateException(
                "assets 里没有引擎包(${EnginePaths.ASSET_SHA_FILE}): ${e.message}\n" +
                    "先跑 tools/build-engine-pkg.mjs 生成引擎包,再重新打 APK",
                e,
            )
        }
        val fingerprint = text.trim()
        require(fingerprint.isNotEmpty()) { "引擎包指纹文件是空的" }
        return fingerprint
    }

    /**
     * 引擎包在不在(能不能用本地引擎)。
     *
     * 判据是 assets 里有指纹文件 —— 构建脚本 build-engine-pkg.mjs 跑完才有。
     * 顺手也确认原生库在不在:引擎包在但 libnode.so 没进 APK(没跑
     * fetch-native.sh)的话,起引擎会 UnsatisfiedLinkError 崩在
     * System.loadLibrary("node"),不如一开始就不显示这个选项。
     */
    fun isInstalled(context: Context): Boolean {
        val hasAsset = runCatching {
            context.assets.open(EnginePaths.ASSET_SHA_FILE).close()
        }.isSuccess
        if (!hasAsset) return false
        // 原生库在 nativeLibraryDir 里(APK 安装时由系统解包 .so)。
        // 必须全查:少一个 libdshengine.so 就在 System.loadLibrary 那里
        // UnsatisfiedLinkError 崩掉,那时候已经开了个"本地引擎"入口给用户了。
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return REQUIRED_NATIVES.all { File(nativeDir, it).exists() }
    }

    /**
     * 递归把 assets 里的目录树拷到磁盘。
     *
     * AssetManager.list() 对目录返回子项、对文件返回空数组 —— 用这个区分
     * 目录和文件,不用额外探测。
     */
    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val am = context.assets
        val names = am.list(assetPath) ?: return
        for (name in names) {
            val childAsset = "$assetPath/$name"
            val child = File(dest, name)
            if (am.list(childAsset)?.isNotEmpty() == true) {
                child.mkdirs()
                copyAssetTree(context, childAsset, child)
            } else {
                child.parentFile?.mkdirs()
                am.open(childAsset).use { input ->
                    child.outputStream().use { out -> input.copyTo(out) }
                }
            }
        }
    }
}
