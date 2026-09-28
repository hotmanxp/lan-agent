// ui/TerminalTransport.kt — 终端「字节管道」抽象(0.24.2)
//
// ## 为什么要抽这一层
//
// lan-agent 有两处终端,数据来源完全不同但**形态完全相同** —— 都是「一串字节
// 往里送、一串字节往外吐」:
//
//   1. 局域网 SSH 终端  —— `SshShell`(JSch ChannelShell)+ xterm.js
//   2. AA 远程终端      —— WebSocket(base64 帧)+ xterm.js
//
// AA 官方原本给远程终端配的是 termux `TerminalView`,与本项目的 xterm.js 是两套
// 渲染器;但它吐出来的就是原始 PTY 字节(`{"type":"output","data":"<base64>"}`
// 解码后直接 `write(bytes)`),没有任何 termux 特有语义。所以 0.24.2 把 termux
// 整个摘掉,远程终端改用本项目的 xterm.js —— 两处终端共用同一个渲染器。
//
// `SshTerminalWebView` 原本直接吃 `SshTerminalStore`,里面掺了 SSH 的状态机
// (连接中 / 重连 / 快捷命令)。这里把**渲染器真正需要的**四个动作抽成接口,
// 两种数据源各实现一份:
//   - [SshTerminalTransport]  —— 包一层现有的 store,**不改它**(SSH 路径零风险)
//   - `AaTerminalTransport`    —— 见 ssh/AaTerminalTransport.kt,走 AA 的 WS
//
// 这样新增一种远程终端不用再碰渲染器,也让「换数据源」这件事在类型上是显式的。
package io.github.hotmanxp.lanagent.ui

/**
 * 终端渲染器需要的数据源能力。
 *
 * 名字刻意用「管道」语义而不是「连接」—— 渲染器不关心对面是 SSH 还是
 * WebSocket,也不关心有没有鉴权,只管收发字节。
 */
internal interface TerminalTransport {

    /**
     * WebView/xterm.js 起来之后调用一次,交出「往页面推输出」的函数。
     *
     * 多次调用以最后一次为准(配置变了会重建 WebView);传 null 表示该通道已失效
     * (WebView 被销毁),实现方**必须**停止往里写 —— 否则会往已 destroy 的
     * WebView 里 `evaluateJavascript`,直接崩。
     */
    fun onSinkReady(sink: ((String) -> Unit)?)

    /** 用户键入 / 粘贴的字节(原始键盘输入,未编码)。 */
    fun onInput(bytes: ByteArray)

    /** 终端尺寸变化(xterm 的 refit 驱动),需要同步给对端发 window-change。 */
    fun onResize(cols: Int, rows: Int)

    /**
     * WebView 被销毁。数据源必须在这里**彻底停手** —— 关闭 socket / 取消协程 /
     * 丢弃 sink,否则会往已 destroy 的 WebView 里 `evaluateJavascript`。
     */
    fun onReleased()
}

/**
 * 把现有的 [SshTerminalStore] 适配成 [TerminalTransport]。
 *
 * **刻意不改 SshTerminalStore 本身** —— 它是 SSH 终端的实现细节,已经有
 * 快捷命令 / 断线重连 / 状态机,动它风险高。这里只做一层薄适配,SSH 路径
 * 保持原样。
 */
internal class SshTerminalTransport(
    private val store: SshTerminalStore,
) : TerminalTransport {

    /**
     * store 的 `onWebViewReady` 没有「解除注册」的概念,所以这里自己留一份
     * 引用:`onReleased` 之后 sink 置空,即使 store 之后还回调(它内部有
     * `onWebViewGone` 关通道,双保险),也不会再碰已销毁的 WebView。
     */
    @Volatile
    private var sink: ((String) -> Unit)? = null

    override fun onSinkReady(sink: ((String) -> Unit)?) {
        this.sink = sink
        if (sink != null) {
            store.onWebViewReady { js -> this.sink?.invoke(js) }
        }
    }

    override fun onInput(bytes: ByteArray) {
        store.sendToShell(bytes)
    }

    override fun onResize(cols: Int, rows: Int) {
        store.onPtyResize(cols, rows)
    }

    override fun onReleased() {
        sink = null
    }
}
