// EngineController.kt — 主进程侧的引擎控制器。
//
// 职责:起引擎服务、盯着它、崩了按退避重拉,并把「引擎活了没」翻译成
// 一个 baseUrl 给上层用。
//
// ## 为什么不靠系统重启服务
//
// node::Start 一个进程只能一次,引擎崩了必须换整个进程。系统的
// START_STICKY 重启时机不可控,而且可能落在同一个已经起过 Node 的进程里
// (那就再也起不来了)。所以重启逻辑放在主进程,自己按退避来。
package io.github.hotmanxp.lanagent.engine

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * 本地引擎的主进程控制器。
 *
 * 单例:主进程里全局一份就够了。
 */
object EngineController {

    private const val TAG = "EngineController"

    /** 多久看一次引擎还活着。 */
    private const val CHECK_INTERVAL_MS = 2000L

    /** 重启退避:1s → 2s → 4s … 封顶 30s。 */
    private const val BACKOFF_BASE_MS = 1000L
    private const val BACKOFF_MAX_MS = 30_000L

    /**
     * 「刚发起过启动」的宽限期。
     *
     * 进程起来之前状态文件里没有新记录(旧记录里的 pid 已经死了),这段
     * 窗口里单看状态文件会以为引擎又挂了。解包 + 起 V8 要十几秒,窗口
     * 给 45s。
     */
    private const val BOOT_GRACE_MS = 45_000L

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var port = EnginePaths.DEFAULT_PORT
    private var started = false
    private var backoffMs = BACKOFF_BASE_MS
    private var nextAttemptAt = 0L

    /** 上次发起启动的时间(elapsedRealtime);没发起过是 false。 */
    private var startRequested = false
    private var lastStartAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            if (!started) return
            val status = EngineStatus.read(ctx)
            val now = SystemClock.elapsedRealtime()

            when {
                // 引擎自己报了 dead(起失败 / 引导超时 / Node 退出)→ 立刻拉。
                status != null && status.state == EngineStatus.STATE_DEAD -> {
                    Log.w(TAG, "引擎报了 dead,拉起")
                    requestStart(ctx, now)
                }

                // 状态文件里的 pid 还活着 → 不管它是 starting 还是 running,都别动。
                status != null && status.pid > 0 && EngineStatus.pidAlive(status.pid) -> {
                    backoffMs = BACKOFF_BASE_MS
                }

                // pid 没了,但我们刚请求过启动 → 进程正在起来的路上,等等。
                startRequested && now - lastStartAt < BOOT_GRACE_MS -> Unit

                // pid 没了,也不是刚请求的 → 引擎被系统杀了,拉。
                else -> {
                    Log.w(TAG, "引擎进程不在了,拉起")
                    requestStart(ctx, now)
                }
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** 发起一次引擎启动,并把下次允许重试的时间往后推(退避)。 */
    private fun requestStart(ctx: Context, now: Long) {
        if (now < nextAttemptAt) return
        nextAttemptAt = now + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        startRequested = true
        lastStartAt = now
        Log.i(TAG, "起引擎服务(下次退避 ${backoffMs}ms)")
        // 凭据在**主进程**物化 —— DataStore 是单进程的文件锁实现,两个进程
        // 同时写会损坏。每次(重)起引擎都重新写一遍,用户改了设置下次重启
        // 就生效。
        runCatching { EngineCredentialsStore.apply(ctx) }
            .onFailure { Log.w(TAG, "写引擎凭据失败", it) }
        runCatching { EngineService.start(ctx, port) }
            .onFailure { Log.e(TAG, "起引擎服务失败", it) }
    }

    /**
     * 开始托管引擎。
     *
     * 幂等:重复调不会起两个。
     *
     * **只在主进程里生效。** Application.onCreate 在每个进程都会跑一遍
     * (包括 :engine),要是这里不拦,就在 :engine 进程里也起一份控制器 ——
     * 它会发现状态还没写("starting" 是 onStartCommand 里写的,晚一步)→
     * 再调一次 EngineService.start() → 同一个进程收到第二个 onStartCommand →
     * 撞上 nodeStarted 守卫 → stopSelf() → 进程连带 Node 一起死。
     * 那是个自己杀自己的循环,引擎永远起不来。
     */
    fun start(context: Context, port: Int = EnginePaths.DEFAULT_PORT) {
        val ctx = context.applicationContext
        if (!isMainProcess(ctx)) {
            Log.i(TAG, "不在主进程,不托管引擎")
            return
        }
        if (started) return
        started = true
        appContext = ctx
        this.port = port
        backoffMs = BACKOFF_BASE_MS
        nextAttemptAt = 0L
        startRequested = false
        Log.i(TAG, "开始托管本地引擎,端口 $port")
        handler.post(tick)
    }

    /**
     * 当前进程是不是主进程。
     *
     * 判据是进程名:主进程 = 包名,子进程 = 包名 + ":" + 后缀
     * (:engine 就是 "io.github.hotmanxp.lanagent:engine")。
     */
    fun isMainProcess(context: Context): Boolean {
        val expected = context.packageName
        val actual = processName(context)
        // 拿不到进程名时按「是主进程」处理:宁可多跑一次控制器(它有
        // 幂等守卫),也不要因为一次探测失败就让引擎永远起不来。
        if (actual.isNullOrBlank()) {
            Log.w(TAG, "拿不到进程名,按主进程处理")
            return true
        }
        return actual == expected
    }

    /** 当前进程名;拿不到返回 null。 */
    private fun processName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        // API 26/27 没有 getProcessName()。runningAppProcesses 从 API 24
        // 起只返回调用方自己的进程 —— 正好够用。
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }

    /** 停止托管(服务本身由系统回收;引擎进程会被 OS 杀掉)。 */
    fun stop() {
        started = false
        handler.removeCallbacks(tick)
        appContext?.let { ctx ->
            runCatching { ctx.stopService(Intent(ctx, EngineService::class.java)) }
        }
        appContext = null
    }

    /**
     * 引擎的 baseUrl;还没起来返回 null。
     *
     * 上层拿到这个就能直接喂给现有的 AgentApi —— 本地引擎和局域网实例
     * 在协议层完全一样,只是 baseUrl 不同。
     */
    fun baseUrl(context: Context): String? {
        val status = EngineStatus.read(context) ?: return null
        return if (status.isAlive()) EnginePaths.baseUrl(status.port) else null
    }

    /** 当前引擎状态;没写过状态文件时返回 null。 */
    fun status(context: Context): EngineStatus? = EngineStatus.read(context)
}
