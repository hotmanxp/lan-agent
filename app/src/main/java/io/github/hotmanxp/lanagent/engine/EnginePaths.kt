// EnginePaths.kt — 本地引擎的目录布局 + 环境变量。
//
// ## 为什么这些目录都得显式指
//
// Node 在 Android 上有三个「默认路径」全是坑:
//
//   - os.homedir() 读的是 $HOME。Android 上不设 $HOME 会退化成 "/",
//     ~/.zai 就写到根目录去了 —— 非 root 直接 EACCES,而且报错发生在
//     agent 第一次写配置的时机,现场非常难查。所以 HOME 必须显式指向
//     app 私有目录。
//   - os.tmpdir() 读的是 $TMPDIR,Android 上默认 /tmp,不可写。
//   - zai 的数据目录(process.env.ZAI_DATA_DIR || ~/.zai)同理。
//
// 三个都得由 App 侧显式喂给引擎进程,不能指望默认值。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import java.io.File
import java.util.TimeZone

/**
 * 本地引擎的路径与运行时参数。
 *
 * 全 App 只有这一个地方知道引擎的文件在哪 —— 别的地方要用就问这里,
 * 不要自己拼路径。
 */
object EnginePaths {

    /**
     * 引擎监听端口。
     *
     * 固定端口而不是动态分配:引擎只监听 127.0.0.1,同一台设备上的所有进程
     * 共享同一个 loopback,所以 App 主进程直接 `http://127.0.0.1:9420` 就能连上,
     * 跟连局域网实例没有任何区别 —— lan-agent 现有的 AgentApi / SSE 客户端
     * 一行都不用改,只是多了一种 baseUrl 来源。
     */
    const val DEFAULT_PORT = 9420

    /**
     * 能力桥端口:主进程上的 HTTP 服务,给引擎调。
     *
     * 与 [DEFAULT_PORT] 分开是因为两个服务在**不同进程**里:
     * 引擎在 :engine 进程(监听 9420),能力桥在主进程(监听 9421)。
     * 同设备共享 loopback,所以互相能通。
     */
    const val CAPABILITY_PORT = 9421

    /**
     * 下载下来的原生库(Node 运行时 + JNI)所在目录。
     *
     * 0.29.0 起这些 .so **不再打进 APK** —— 由用户在设置页点「下载引擎」时
     * 拉下来解到这里。JNI 用 `System.load(<这里的绝对路径>)` 加载
     * (不再用 `System.loadLibrary`,那个只认 APK 里的 nativeLibraryDir)。
     */
    fun nativeDir(context: Context): File = File(context.filesDir, "native")

    /** 下载中的 zip 落盘位置(解完就删)。 */
    fun bundleFile(context: Context): File = File(context.filesDir, "engine-bundle.zip")

    /** 解压后的引擎包根目录。 */
    fun engineRoot(context: Context): File = File(context.filesDir, "engine")

    /**
     * 解压后的引擎目录,按内容指纹分目录。
     *
     * 按指纹分目录而不是原地覆盖:引擎包升级了(指纹变了)自动换一份新的,
     * 旧的目录删掉。原地覆盖的话,升级中途被杀会留下一个「一半新一半旧」的
     * 引擎目录 —— 那是最难查的一种坏法。
     */
    fun engineDir(context: Context, fingerprint: String): File =
        File(engineRoot(context), fingerprint.take(16))

    /**
     * HOME:Node 的 os.homedir() 就是它。
     * 放 filesDir 下面 —— app 私有目录,卸载即清,不需要额外权限。
     */
    fun homeDir(context: Context): File = File(context.filesDir, "home")

    /** 工作区:agent 的 cwd(createApp 的 cwd 参数)。 */
    fun workspaceDir(context: Context): File = File(context.filesDir, "workspace")

    /** zai 的数据目录(~/.zai 的等价物)。 */
    fun dataDir(context: Context): File = File(homeDir(context), ".zai")

    /** 临时目录:Android 上 os.tmpdir() 默认 /tmp,不可写。 */
    fun tmpDir(context: Context): File = File(homeDir(context), "tmp")

    /**
     * 引擎进程的 PATH 里的「自己的 bin」目录。
     *
     * 放两个东西:
     *   - `bash` → /system/bin/sh 的符号链接(opencc 的 BashTool 用它)
     *   - `rg`   → nativeLibraryDir/librg.so 的符号链接(ripgrep)
     *
     * 建软链的活儿在 [EngineShims] 里,这里只管路径。
     */
    fun binDir(context: Context): File = File(context.filesDir, "bin")

    /**
     * 运行时状态文件:引擎进程(:engine)写,主进程读。
     *
     * 放在 engineRoot 外面 —— engineRoot 下面装的是按指纹分的引擎目录,
     * 升级时会被整目录删掉,状态文件放里面会被误删。
     */
    fun runtimeFile(context: Context): File = File(context.filesDir, "engine-runtime.txt")

    /** 引擎进程的 stderr 落盘位置(node 的 stack trace 全在这儿)。 */
    fun stderrFile(context: Context): File = File(context.filesDir, "engine-stderr.log")

    /**
     * 起引擎进程时要塞进去的环境变量。
     *
     * 引擎入口(server.mjs)只认这几个,别的地方改环境变量名会直接让引擎起不来。
     *
     * ## 每一条的理由(都是在模拟器上实测出来的,不是猜的)
     *
     * - HOME:Node 的 os.homedir() 就是它。Android 上不设会退化成 "/",
     *   ~/.zai 写不了,进程直接崩。
     * - WS / ZAI_DATA_DIR / TMPDIR / PORT:引擎入口(server.mjs)读它们。
     *   **WS 漏传 = 服务启动即崩** —— initAgentRuntime(opts.cwd) 收到
     *   undefined,projectDirFor(cwd) 抛 "The path argument must be of type
     *   string",createApp 直接把进程带走了。日志里能看到两处报错。
     * - SHELL / PATH:opencc 的 findSuitableShell() 只认路径里含 "bash"
     *   或 "zsh" 的 shell,而且要 isExecutable。Android 自带的 /system/bin/sh
     *   两个条件都不满足 → 所以下面另建一个叫 "bash" 的软链指过去。
     * - CLAUDE_CODE_SHELL:直接把上面那个软链交给 opencc,省得它去 which 找。
     * - PATH 里带 files/bin:opencc 解析 ripgrep 走的是 `which rg`
     *   (resolveRgSystem),所以 rg 在 PATH 里就够了,不用改 opencc 代码。
     * - DSH_ANDROID_TZ / TZ:Android 没有 Node 的 ICU 能认的时区数据,
     *   引擎会跑在 UTC —— prompt 里的时间上下文、预算的日切都会错。
     *   preload(android-env.cjs)读 DSH_ANDROID_TZ 决定要不要重设 process.env.TZ。
     * - NODE_COMPILE_CACHE:V8 编译缓存。第二次起引擎能省掉一整轮解析,
     *   手机上这值好几秒。
     */
    fun env(context: Context, port: Int = DEFAULT_PORT): Map<String, String> = mapOf(
        "HOME" to homeDir(context).absolutePath,
        "WS" to workspaceDir(context).absolutePath,
        "ZAI_DATA_DIR" to dataDir(context).absolutePath,
        "TMPDIR" to tmpDir(context).absolutePath,
        "PORT" to port.toString(),
        // opencc 的 BashTool 要一个「路径含 bash 的 shell」。Android 的
        // /system/bin/sh(toybox)实测支持 [[ ]]、数组、set -o pipefail,
        // 能力够用,但名字里没有 bash 会被 findSuitableShell() 拒掉。
        "SHELL" to shellShim(context).absolutePath,
        "CLAUDE_CODE_SHELL" to shellShim(context).absolutePath,
        // PATH 前置 files/bin —— 里面的 bash 软链和 rg 软链靠这个被找到。
        "PATH" to listOf(
            binDir(context).absolutePath,
            "/system/bin",
            "/system/xbin",
        ).joinToString(":"),
        // 时区:设备当前的 IANA 时区(如 Asia/Shanghai)。preload 拿它校验
        // 之后决定要不要 env.TZ = ... 重置 ICU。
        "DSH_ANDROID_TZ" to TimeZone.getDefault().id,
        "TZ" to TimeZone.getDefault().id,
        "NODE_COMPILE_CACHE" to compileCacheDir(context).absolutePath,
        // 能力桥:phone-bridge mod 靠这两个 env 找到主进程上的能力服务。
        // OPENCC_MODS_DIR 指向引擎包里自带的 mod 目录 —— 不指的话 core
        // 会去找 ~/.zai/mods,那儿是空的,工具一个都不会注册。
        "LANAGENT_CAP_BASE" to "http://127.0.0.1:$CAPABILITY_PORT",
        "OPENCC_MODS_DIR" to modsDir(context).absolutePath,
        // LD_LIBRARY_PATH:给 `files/bin/node`(APK 里的 libdshnode.so 启动器)
        // 找 libnode.so / libc++_shared.so —— 那两个在下载目录里(nativeDir),
        // 启动器的 RPATH 只有 $ORIGIN(指向 APK 的 nativeLibraryDir)。
        //
        // 为什么这条能生效:引擎进程本身是 AT_SECURE=1(zygote fork 来的),
        // 动态链接器会忽略它自己 env 里的 LD_LIBRARY_PATH —— 所以引擎内的
        // Node 靠 android-env.cjs preload 修 env。但**引擎 exec 出去的子进程**
        // (bash、node)是重新 execve 的,auxv 由内核重新算,AT_SECURE=0 →
        // LD_LIBRARY_PATH 正常生效。
        //
        // 为什么不能把 libnode.so 放 APK:82MB,APK 直接翻三倍。
        //
        // 注意:dlopen 只需要 execute 权限(SELinux 给了),execve 才需要
        // execute_no_trans(没给)—— 所以「.so 放下载目录 + 启动器放 APK」
        // 这个组合是唯一可行的。
        "LD_LIBRARY_PATH" to nativeDir(context).absolutePath,
        // RIPGREP_PATH:ripgrep **本体**的绝对路径(不是 files/bin/rg 软链)。
        //
        // 软链本身在 app_data_file 里,Android 15/16 的 SELinux 禁止 exec
        // (deny untrusted_app app_data_file:file execute)→ spawn rg EACCES,
        // Grep/Glob 工具全废。core 拿到这个路径后直接 exec 本体。
        "RIPGREP_PATH" to rgTarget(context).absolutePath,
        // NODE_PATH:让 agent 的 node 脚本能 require 引擎自带的依赖
        // (zod / fflate / @zn-ai/zn-agent-core)。只对 CJS 的 require 生效,
        // ESM 的 import 不走这条。
        "NODE_PATH" to File(
            currentEngineDir(context) ?: File(context.filesDir, "engine"),
            "node_modules",
        ).absolutePath,
    )

    /**
     * 引擎包里的 mod 目录。
     *
     * 引擎包解压后 mods/ 在引擎目录下面(跟 node_modules 平级),
     * 所以这里得知道引擎目录 —— 用「当前正在用的那个」。
     */
    fun modsDir(context: Context): File =
        File(currentEngineDir(context) ?: File(context.filesDir, "engine"), "mods")

    /**
     * 当前在用的引擎目录。
     *
     * 引擎目录按指纹分家(engineRoot 下面可能有多个),取**最新写入的
     * 那个** —— 装的时候旧目录会被删掉,但万一有残留(装到一半被杀),
     * 「最新」才是刚解出来的那份。
     */
    fun currentEngineDir(context: Context): File? =
        engineRoot(context).listFiles()
            ?.filter { it.isDirectory && File(it, ".installed").exists() }
            ?.maxByOrNull { it.lastModified() }

    /** V8 编译缓存目录(cacheDir 会被系统按空间压力清理,正合适)。 */
    fun compileCacheDir(context: Context): File =
        File(context.cacheDir, "node-compile-cache").apply { mkdirs() }

    /**
     * 「叫 bash 的 shell」软链:指向 /system/bin/sh。
     *
     * 为什么非得叫 bash:opencc 的 findSuitableShell() 里
     * `shellOverride.includes('bash') || shellOverride.includes('zsh')`
     * 是硬条件,SHELL 环境变量走同一套判断。所以光设
     * SHELL=/system/bin/sh 没用,名字里必须有 bash。
     */
    fun shellShim(context: Context): File = File(binDir(context), "bash")

    /**
     * ripgrep 入口软链:指向 APK 里的 librg.so(随 DSH-Android 的原生包一起进来)。
     *
     * opencc 的 resolveRgSystem() 用 `which rg` 找,所以 PATH 里有就行。
     */
    fun rgShim(context: Context): File = File(binDir(context), "rg")

    /**
     * ripgrep **本体**的位置 —— 不是软链,是真正的可执行文件。
     *
     * 优先 APK 里的那份(nativeLibraryDir,system_file 标签);拿不到再退回
     * 下载目录里的。
     *
     * ## 为什么必须把本体路径告诉引擎
     *
     * 软链 `files/bin/rg` 本身在 app 私有目录(app_data_file)里。Android 15/16
     * 的 SELinux 有 `deny untrusted_app app_data_file:file execute` ——
     * 软链**本身**不可执行。core 的 resolveRipgrepConfig 找到 rg 的绝对路径
     * 后又故意丢掉、改用裸名 "rg"(注释写明是为了防 PATH 劫持),于是 spawn
     * 走的是 PATH 里的 files/bin/rg 这条软链 → `spawn rg EACCES` →
     * Grep / Glob 工具全废。
     *
     * 引擎启动时把这个路径通过 RIPGREP_PATH 传给 core,core 直接 exec 本体,
     * 绕开 app_data_file 里的软链。
     */
    fun rgTarget(context: Context): File {
        val apkRg = File(context.applicationInfo.nativeLibraryDir, "librg.so")
        if (apkRg.isFile) return apkRg
        return File(nativeDir(context), "librg.so")
    }

    /** node 入口软链:指向 APK 里的 libdshnode.so(node 启动器,4.6KB)。 */
    fun nodeShim(context: Context): File = File(binDir(context), "node")

    /** 需要预先建出来的目录(引擎自己也会建,但先建好能少一批 EACCES 噪音)。 */
    fun ensureDirs(context: Context): List<File> = listOf(
        engineRoot(context),
        homeDir(context),
        workspaceDir(context),
        dataDir(context),
        tmpDir(context),
        binDir(context),
    ).also { dirs -> dirs.forEach { it.mkdirs() } }

    /** 引擎的 baseUrl —— 主进程侧拿到这个就能直接喂给现有的 AgentApi。 */
    fun baseUrl(port: Int = DEFAULT_PORT): String = "http://127.0.0.1:$port"
}
