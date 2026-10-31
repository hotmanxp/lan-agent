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
    init {
        // 顺序有讲究:c++_shared 是 libnode.so 的 C++ 运行时依赖,必须先加载,
        // 否则 libnode 里的静态初始化会找不到 std:: 符号。
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("dshengine")
    }

    @JvmStatic
    external fun nativeStart(args: Array<String>, stdinFd: Int, stdoutFd: Int, stderrFd: Int): Int
}
