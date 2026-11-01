package io.github.jackwener.dsh.engine

// ⚠️⚠️ 这个包名**不能改** —— 它是给 JNI 符号对上的,不是随手起的。⚠️⚠️
//
// libdshengine.so 导出的 JNI 符号是硬编码的:
//
//     Java_io_github_jackwener_dsh_engine_NodeRuntime_nativeStart
//
// JNI 查符号用的是**类的全限定名**(把 `.` 换成 `_`),类名对不上就是
// UnsatisfiedLinkError,而且报错发生在 System.loadLibrary 那一刻 —— 现场
// 什么日志都没有,只有一句 "UnsatisfiedLinkError: No implementation found
// for 'nativeStart'..."。
//
// lan-agent 自己的包名是 io.github.hotmanxp.lanagent,改不了(会影响已装用户
// 的数据目录和快捷方式),所以只能在这个 app 里开一个**同名 Java 包**来对上
// 上游预编译 .so 里的符号。这不是笔误,是有意为之 —— 「顺手改成 lanagent」
// 会让 nativeStart 直接炸。同理,类名 NodeRuntime 和方法名 nativeStart
// 也一个字都不能动。

import java.io.File

/**
 * JNI 入口:进 libdshengine.so,由它调 node::Start 跑起 Node 运行时。
 *
 * `args` 是**不含 argv[0]** 的参数表(原生侧自己补 "node")。
 * nativeStart 会一直阻塞到 Node 事件循环退出,返回退出码。
 *
 * node::Start **一个进程只能调一次**(V8 / Node 的全局状态不可重入),
 * 所以引擎必须跑在独立进程(`android:process=":engine"`),起第二次得整个
 * 进程重来。
 */
object NodeRuntime {

    /**
     * 加载原生库。
     *
     * ## 为什么用 System.load(绝对路径)而不是 System.loadLibrary
     *
     * `System.loadLibrary` 只认 APK 解包出来的 `nativeLibraryDir` —— 那是
     * **构建期**打进 APK 的路子。0.29.0 起原生库改成运行时下载,躺在
     * `filesDir/native/` 里,不在 nativeLibraryDir,所以必须给绝对路径。
     *
     * ## 加载顺序为什么是这三个
     *
     * `libdshengine.so` 的 `DT_NEEDED` 是 `libnode.so` + `libc++_shared.so`,
     * 而 `libnode.so` 的 SONAME 就是 `libnode.so`。Android 的 linker 在同一个
     * namespace 内**按 SONAME 复用已加载的库** —— 所以先把 libnode.so 加载
     * (它自己 NEEDED 的 libc++_shared.so 也就顺带被解析了),再加载
     * libdshengine.so 时它的 NEEDED 就能对上,不需要改 RPATH。
     *
     * ⚠️ 这条是实测出来的:加载顺序错了就是一句
     * `dlopen failed: cannot locate symbol "..."`,而引擎那边只看到
     * 「引擎没起来」。改这块之前先看 docs/agents/local-engine.md 的记录。
     */
    @Synchronized
    fun load(nativeDir: File) {
        if (loaded) return
        val cxx = File(nativeDir, "libc++_shared.so")
        val node = File(nativeDir, "libnode.so")
        val engine = File(nativeDir, "libdshengine.so")
        for (f in listOf(cxx, node, engine)) {
            if (!f.isFile) {
                throw UnsatisfiedLinkError("原生库不在:${f.absolutePath}\n  先在设置页下载引擎")
            }
        }
        System.load(cxx.absolutePath)
        System.load(node.absolutePath)
        System.load(engine.absolutePath)
        loaded = true
    }

    @Volatile
    private var loaded = false

    @JvmStatic
    external fun nativeStart(args: Array<String>, stdinFd: Int, stdoutFd: Int, stderrFd: Int): Int
}
