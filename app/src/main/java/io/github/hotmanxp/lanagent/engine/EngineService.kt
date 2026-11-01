// EngineService.kt — 本地引擎的前台服务,跑在独立的 :engine 进程里。
//
// ## 为什么必须独立进程
//
// node::Start 一个进程只能调一次(V8 / Node 的全局状态不可重入),
// 所以引擎崩溃后没法在原地重启 —— 只能整个进程换新的。这条约束决定了
// 服务必须声明 android:process=":engine",崩了就杀进程,由主进程重新拉。
//
// ## 进程之间怎么说话
//
// 不走 Binder:引擎起的是一个 HTTP 服务,监听 127.0.0.1:9420。同一个设备上
// 所有进程共享同一个 loopback,所以 App 主进程直接 http://127.0.0.1:9420
// 就能连上 —— 跟连局域网实例没区别,lan-agent 现有的 AgentApi / SSE 客户端
// 一行都不用改。跨进程只额外传一个「引擎活了没」的状态文件。
package io.github.hotmanxp.lanagent.engine

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.hotmanxp.lanagent.LanAgentApp
import io.github.hotmanxp.lanagent.R
import io.github.jackwener.dsh.engine.NodeRuntime
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 在 :engine 进程里跑 Node + zai server。
 *
 * 生命周期很短命也很简单:起来 → 解引擎包 → 起 Node → Node 退出 → 杀进程。
 */
class EngineService : Service() {

    @Volatile
    private var nodeStarted = false

    /**
     * 引导监控线程还该不该干活。
     *
     * 引擎从「进程起来了」到「HTTP 真的在监听」中间有一段(解 12MB 的包 +
     * Node 起 V8),这段里主进程不知道引擎是死是活。引导线程负责在
     * HTTP 通了的那一刻把状态写成 running;超时了就判定引擎没起来,
     * 写 dead 让主进程重拉。
     *
     * 引擎正常退出时这个标记先置 false,免得引导线程在最后关头又写一次
     * running,把已经写好的 dead 覆盖掉。
     */
    @Volatile
    private var booting = false

    override fun onCreate() {
        super.onCreate()
        // startForeground 必须在 5 秒内调用,否则系统抛
        // ForegroundServiceDidNotTimeOutException 把进程杀了。放在 onCreate
        // 最前面,别让解包之类的活儿挡住它。
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重启请求:用户在设置页改了凭据 → 主进程发这个 Intent 过来。
        //
        // 为什么必须换进程:Node 是在**这个进程**里跑的,它启动时就把
        // settings.json / .zai.json 读进内存了 —— 改了文件它也不知道。
        // 而 node::Start 一个进程只能调一次,原地再起一次会直接崩。
        // 所以只能让整个 :engine 进程换新的(主进程的 EngineController
        // 看到 dead 状态会按退避重拉)。
        if (intent?.getBooleanExtra(EXTRA_RESTART, false) == true) {
            Log.i(TAG, "收到重启请求(凭据改了),换新进程")
            killSelf(intent.getIntExtra(EXTRA_PORT, EnginePaths.DEFAULT_PORT))
            return START_NOT_STICKY
        }
        if (nodeStarted) {
            // node::Start 一个进程只能一次。系统要是把服务在**同一个进程**里
            // 重新拉起来(比如 stopSelf 之后又被 startService),这里不能再起
            // 一次 Node —— 直接退出,让主进程拉一个干净的 :engine 进程。
            Log.w(TAG, "这个进程里 Node 已经起过了,不再重复启动")
            stopSelf()
            return START_NOT_STICKY
        }
        nodeStarted = true
        val port = intent?.getIntExtra(EXTRA_PORT, EnginePaths.DEFAULT_PORT)
            ?: EnginePaths.DEFAULT_PORT
        // 状态必须在**这里**同步写,不能等到 runEngine 的线程里再写。
        //
        // 主进程的 EngineController 每 2 秒看一次状态文件,看到「没有状态」
        // 就认为引擎死了,会再发一次 startService —— 那会在**同一个**
        // :engine 进程里撞上上面的 nodeStarted 守卫,stopSelf() 把刚起来的
        // Node 一起带走。解包 12MB 的引擎包要好几秒,这个窗口足够致命。
        //
        // 在这里写,进程一 fork 完就落盘,窗口缩到几十毫秒。
        EngineStatus.write(this, EngineStatus.STATE_STARTING, port)
        booting = true
        Thread(null, { runEngine(port) }, "node-main", 16L * 1024 * 1024).start()
        // 引导监控:HTTP 一通就把状态改成 running,超时则判死。
        Thread(null, { watchBoot(port) }, "node-boot", 256 * 1024).start()
        // 重启交给主进程的 EngineController(带退避),不用系统的 START_STICKY:
        // 系统重启的时机不可控,而且可能落在同一个已经起过 Node 的进程里。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "EngineService 销毁")
    }

    // ── 引擎本体 ────────────────────────────────────────────────────────────

    private fun runEngine(port: Int) {
        val engineDir = try {
            EngineInstaller.install(this)
        } catch (e: Throwable) {
            Log.e(TAG, "引擎包解不开,引擎起不来", e)
            killSelf(port)
            return
        }
        EnginePaths.ensureDirs(this)
        // 软链要在设环境变量**之前**建好 —— env 里那几条(SHELL / CLAUDE_CODE_SHELL /
        // PATH)指的就是 files/bin/bash 和 files/bin/rg,软链不存在的话 opencc
        // 的 findSuitableShell() 直接抛 "No suitable shell found",BashTool 全废。
        EngineShims.install(this)
        // bash / rg 的软链必须在起 Node **之前**建好:引擎一起来就可能
        // 解析 shell(opencc 的 findSuitableShell 在启动早期就会跑)。

        // 凭据物化:把用户在设置页填的「服务地址 / 模型名 / key」写成
        // 引擎认得的两个文件($HOME/.zai.json 和 $ZAI_DATA_DIR/settings.json)。
        //
        // 为什么每次起引擎都重写一遍:用户在设置页改了 key 之后不必重启
        // App —— 引擎下次被拉起时自然用新值,不用额外的一致性维护。
        // 没配完整就跳过(引擎照样起,只是调不了模型)。
        val credentialsApplied = EngineCredentialsStore.apply(this)
        Log.i(TAG, "凭据物化 = $credentialsApplied")

        // 手机端专用主 Agent:提示词讲清「你在手机上跑」,工具池砍掉依赖电脑的
        // 那些(CliAgent / 定时任务 / LSP / 桌面流程)。
        //
        // 必须在起 Node **之前**物化 —— 引擎启动时就扫 $ZAI_DATA_DIR/main-agents/,
        // 晚一步就得重启引擎才生效。
        val profileApplied = EngineAgentProfile.apply(this)
        Log.i(TAG, "手机端 agent 配置 = $profileApplied")

        // 环境变量必须在 nativeStart 之前设好 —— Node 一起来就会读 HOME /
        // ZAI_DATA_DIR,晚了就写到 / 底下去了。
        EnginePaths.env(this, port).forEach { (k, v) ->
            runCatching { Os.setenv(k, v, true) }
                .onFailure { Log.w(TAG, "设环境变量 $k 失败", it) }
        }

        // 工作目录 —— **Node 继承的是 App 进程的 cwd**,Android 上那是 "/"。
        // 不切的话 BashTool 的相对路径全落在根目录:读文件碰巧能读(根目录可读),
        // 写文件直接 EACCES,而且报错信息里带着 "/foo.txt" 这种一眼假的路径,
        // 极难查。
        //
        // 为什么不在这里切:android.system.Os 里**没有 chdir** —— Android 压根
        // 不公开这个 API。所以切目录交给 Node 自己:preload(android-env.cjs)
        // 里有 `process.chdir(process.env.WS)`,那是 Node 的 API,不受
        // AT_SECURE 影响。preload 在 server.mjs 之前跑,所以引擎起来第一眼
        // 看到的工作区就是对的。
        //
        // 这里只把路径记下来,便于对着 logcat 核对 preload 有没有生效。
        Log.i(TAG, "工作目录(交给 preload 切) = ${EnginePaths.workspaceDir(this).absolutePath}")

        // 三根管道:stdin 给引擎(它不读,但 nativeStart 要求一个 fd)、
        // stdout 转发到 logcat、stderr 落文件(node 的 stack trace 在这儿)。
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val stderrFd = ParcelFileDescriptor.open(
            EnginePaths.stderrFile(this),
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )

        // nativeStart 要的是裸 fd(int)。detachFd 把所有权从 PFD 转给调用方。
        // 之后**不手动关这些 fd** —— 进程马上就要被 kill,让内核回收更省事,
        // 也少一个「关了但 dup2 出来的 fd 1 还在用」的坑。
        val inRead = stdin[0].detachFd()
        val outWrite = stdout[1].detachFd()
        val errWrite = stderrFd.detachFd()
        // stdin 的写端留着不关:关了 Node 会看到 EOF,有些库会因此提前退出。
        stdin[1].detachFd()

        // stdout 的读端:用 PFD 包一层 AutoCloseInputStream 读,不用裸 fd ——
        // android.system.Os.read 收的是 FileDescriptor 不是 int,自己造
        // FileDescriptor 得靠反射碰隐藏字段,不划算。
        val outRead = stdout[0]
        Thread(null, { pump(outRead) }, "node-stdout", 256 * 1024).start()

        // 状态文件:引擎真正能服务了才写 running —— 由 watchBoot 负责。
        //
        // 为什么不能直接写:node::Start 是**阻塞**的(Node 退出才返回),
        // 在它前后写都拿不到「起来了」这个时刻 —— 写在前面是骗人(引擎可能
        // 起不来),写在后面是永远写不到(引擎一直在跑)。
        //
        // 所以另起一个线程轮询 /api/health:通了就是真起来了。这跟主进程
        // 判定实例在线用的是同一个端点,判据一致。

        // argv[0]("node")由 libdshengine 自己补,这里给的是它后面的参数。
        val entry = File(engineDir, "server.mjs")
        // --require 的 preload 必须在最前面(Node 按顺序处理 argv),
        // 它把 AT_SECURE=1 下被 SafeGetenv 拒掉的 env(TMPDIR 等)恢复回来。
        //
        // --expose-internals:opencc core 现在**不需要**它(core 是 esbuild
        // 打的自包含 bundle,内部 0 命中 require-builtin —— 那个 flag 是
        // dsh 的需求)。留着是保险:万一 core 以后要 require 内置模块,
        // 这个 flag 就是唯一能在 android 上生效的替代(require-builtin 的
        // 原生 addon 没为 android 编)。多一个 flag 没有副作用。
        val preload = File(engineDir, "android-env.cjs")
        val args = buildList {
            add("--max-old-space-size=$HEAP_MB_MB")
            add("--expose-internals")
            if (preload.exists()) {
                add("--require")
                add(preload.absolutePath)
            } else {
                Log.w(TAG, "engine 包里没有 android-env.cjs,跳过 preload(AT_SECURE env 不会被修复)")
            }
            add(entry.absolutePath)
        }.toTypedArray()
        Log.i(TAG, "起 Node: ${args.joinToString(" ")} (cwd=${EnginePaths.workspaceDir(this)})")

        // 加载原生库(**必须在 nativeStart 之前**):库不在就 UnsatisfiedLinkError,
        // 而 JNI 符号找不到时报错现场什么日志都没有。
        //
        // 0.29.0 起原生库是运行时下载的,躺在 filesDir/native/ ——
        // 所以用 System.load(绝对路径),不是 System.loadLibrary(那个只认
        // APK 里的 nativeLibraryDir)。细节见 NodeRuntime 的注释。
        try {
            NodeRuntime.load(EnginePaths.nativeDir(this))
        } catch (e: Throwable) {
            Log.e(TAG, "加载原生库失败(引擎没装?下过引擎吗?)", e)
            killSelf(port)
            return
        }

        val code = try {
            NodeRuntime.nativeStart(args, inRead, outWrite, errWrite)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeStart 炸了", e)
            -1
        }
        Log.w(TAG, "Node 退出了,退出码 $code")
        killSelf(port)
    }

    /** 把 stdout 从管道读出来转 logcat。 */
    private fun pump(pfd: ParcelFileDescriptor) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val text = String(buf, 0, n)
                    text.lineSequence().forEach { line ->
                        if (line.isNotBlank()) Log.i(TAG, "[node] $line")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stdout 泵子结束", e)
        }
    }

    /**
     * 引导监控:盯着引擎的 HTTP 端口,通了就把状态写成 running。
     *
     * 为什么需要它:「进程活着」不等于「引擎能用了」。Node 可能起了一半
     * 崩了、也可能端口被占、也可能 HTTP 起来但路由不对。主进程只关心
     * 「这个 baseUrl 能不能真的连上」,所以状态要以「HTTP 探通了」为准。
     *
     * 超时(BOOT_TIMEOUT_MS)判死:写 dead + 杀进程,让主进程退避后重拉。
     */
    private fun watchBoot(port: Int) {
        val deadline = SystemClock.elapsedRealtime() + BOOT_TIMEOUT_MS
        while (booting) {
            if (probeHealth(port)) {
                if (!booting) return
                Log.i(TAG, "引擎 HTTP 通了,端口 $port")
                EngineStatus.write(this, EngineStatus.STATE_RUNNING, port)
                return
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                Log.w(TAG, "引擎 $BOOT_TIMEOUT_MS ms 内没起来,判死")
                killSelf(port)
                return
            }
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /**
     * 探一下引擎的 /api/health。
     *
     * 用 HttpURLConnection 而不是 OkHttp:这个探活跑在 :engine 进程里,
     * 每次引擎重启都要探,不值得为它拉起一个 OkHttp 客户端。
     */
    private fun probeHealth(port: Int): Boolean = runCatching {
        val url = java.net.URL("http://127.0.0.1:$port/api/health")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 1000
            readTimeout = 1000
            requestMethod = "GET"
        }
        try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /**
     * 引擎死了:写状态 → 停服务 → 杀进程。
     *
     * 杀进程是必须的:Node 起过之后这个进程的 V8 状态就废了,留着它下次
     * 再想启动引擎会撞上「node::Start already called」。
     */
    private fun killSelf(port: Int) {
        // 先把引导线程停掉:它可能正在写 running,不能让它盖掉我们要写的 dead。
        booting = false
        EngineStatus.write(this, EngineStatus.STATE_DEAD, port)
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    // ── 前台通知 ────────────────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, LanAgentApp.CHANNEL_ID_ENGINE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_engine_title))
            .setContentText(getString(R.string.notif_engine_desc))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34:必须显式给类型,否则系统认为类型不匹配直接拒。
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground 被拒", it) }
    }

    companion object {
        private const val TAG = "EngineService"
        private const val NOTIF_ID = 0x4547 // 'EG'

        /** Node 的堆上限(MB)。手机内存有限,别让 V8 顺手把系统撑爆。 */
        private const val HEAP_MB_MB = 768

        /**
         * 引擎从进程起来到 HTTP 通的时限。
         *
         * 12MB 引擎包解压 + Node 起 V8,在中低端机上 10~20 秒都见过,
         * 所以给 60 秒 —— 超了基本就是真起不来(缺 .so、包解不开、
         * 端口被占),早判死早让主进程退避重拉。
         */
        private const val BOOT_TIMEOUT_MS = 60_000L

        const val EXTRA_PORT = "port"

        /** 「换新进程」的标记:凭据改了要重启引擎时用。 */
        const val EXTRA_RESTART = "restart"

        /** 启动引擎服务。 */
        fun start(context: Context, port: Int = EnginePaths.DEFAULT_PORT) {
            val intent = Intent(context, EngineService::class.java)
                .putExtra(EXTRA_PORT, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 让引擎换一个新进程(凭据 / 工作区变了之后用)。
         *
         * 发一个带 [EXTRA_RESTART] 的 Intent 给服务,服务收到就自杀
         * (写 dead 状态 + 杀进程)。主进程的 EngineController 看到 dead
         * 会按退避把新进程拉起来 —— 新进程启动时会重新读 settings.json。
         */
        fun requestRestart(context: Context, port: Int = EnginePaths.DEFAULT_PORT) {
            val intent = Intent(context, EngineService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_RESTART, true)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "发重启请求失败", it) }
        }
    }
}
