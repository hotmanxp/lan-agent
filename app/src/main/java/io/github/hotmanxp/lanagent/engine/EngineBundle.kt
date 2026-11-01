// EngineBundle.kt — 「按需下载」引擎包 + 原生库。
//
// ## 形态
//
// 引擎不再打进 APK(那会让 APK 变成 90MB+ 的大包)。改成:用户在设置页点
// 「下载引擎」,App 从一个 manifest URL 拿清单,再按清单下载 zip:
//
//   GET <下载源>/engine-manifest.json
//     → { version, fingerprint, file, sha256, size }
//   GET <下载源>/<file>
//     → zip(约 34MB),里面两个顶层目录:
//          engine/  引擎包(server.mjs + opencc-core bundle + node_modules)
//          native/  7 个 .so(Node 运行时 + JNI)
//
// ## 三道关,一道都不能省
//
//   1. **sha256 校验** —— 传输层不可信(局域网也可能被投毒 / 传一半损坏)。
//      校验不过就删掉重下,绝不解包。
//   2. **解到 staging 再切换** —— 解一半被杀是最难查的坏法(「一半新一半旧」)。
//      所以先解到 `.bundle-staging-<指纹>/`,全解完、全校验过,再 rename 到位。
//   3. **解包防目录穿越** —— zip 里的条目名可能是 `../../xxx`,照着写就写到
//      app 私有目录外面去了。
//
// ## 装完之后
//
//   filesDir/engine/<指纹>/   引擎包(带 .installed 标记)
//   filesDir/native/          7 个 .so(JNI 用 System.load 绝对路径加载)
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 引擎包的下载 / 校验 / 解包。
 */
object EngineBundle {

    private const val TAG = "EngineBundle"

    /** manifest 里的字段。 */
    data class Manifest(
        val version: String,
        val fingerprint: String,
        val file: String,
        val sha256: String,
        val size: Long,
    )

    /** 下载状态。UI 直接订阅它显示进度 / 错误。 */
    sealed interface State {
        /** 还没开始。 */
        data object Idle : State

        /** 下载中(已下载字节 / 总字节)。 */
        data class Running(val downloaded: Long, val total: Long) : State

        /** 失败。message 是给用户看的中文说明。 */
        data class Failed(val message: String) : State

        /** 装好了。 */
        data class Done(val version: String, val sha256: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * 拉 manifest。
     *
     * @param url manifest 的完整地址(例如 http://192.168.101.69:8766/engine-manifest.json)
     */
    suspend fun fetchManifest(url: String): Manifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("下载清单失败:HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            parseManifest(body)
        }
    }

    private fun parseManifest(body: String): Manifest {
        val obj = try {
            JSONObject(body)
        } catch (e: Throwable) {
            error("清单不是合法 JSON:${e.message}")
        }
        val file = obj.optString("file")
        val sha = obj.optString("sha256")
        if (file.isBlank() || sha.isBlank()) error("清单缺 file / sha256 字段")
        return Manifest(
            version = obj.optString("version", "?"),
            fingerprint = obj.optString("fingerprint").ifBlank { sha.take(16) },
            file = file,
            sha256 = sha.lowercase(),
            size = obj.optLong("size", 0L),
        )
    }

    /** zip 的下载地址:跟 manifest 同目录。 */
    private fun zipUrlFor(manifestUrl: String, file: String): String {
        val base = manifestUrl.substringBefore('?').substringBeforeLast('/')
        return "$base/$file"
    }

    /**
     * 完整流程:拉清单 → 下载 → 校验 → 解包 → 切换到位。
     *
     * 任何一步失败都**不留下半成品**:临时文件删掉,现役引擎原样保留。
     */
    suspend fun downloadAndInstall(manifestUrl: String): Result<Manifest> = withContext(Dispatchers.IO) {
        val ctx = requireContext()
        var zipFile: File? = null
        try {
            val manifest = fetchManifest(manifestUrl)
            Log.i(TAG, "清单:版本 ${manifest.version} 大小 ${manifest.size} 指纹 ${manifest.fingerprint}")

            // 已经装过同一个指纹 → 什么都不用做(幂等,重复点按钮不会重下)。
            val target = EnginePaths.engineDir(ctx, manifest.fingerprint)
            if (File(target, ".installed").isFile && EngineInstaller.isInstalled(ctx)) {
                _state.value = State.Done(manifest.version, manifest.sha256)
                return@withContext Result.success(manifest)
            }

            val zip = EnginePaths.bundleFile(ctx)
            zipFile = zip
            download(zipUrlFor(manifestUrl, manifest.file), zip, manifest.size)

            val actual = sha256Of(zip)
            if (actual != manifest.sha256) {
                error("校验和不符\n  期望 ${manifest.sha256}\n  实际 $actual")
            }
            Log.i(TAG, "校验通过 ${actual.take(16)}")

            installZip(ctx, zip, manifest)
            zip.delete()
            zipFile = null

            _state.value = State.Done(manifest.version, manifest.sha256)
            Result.success(manifest)
        } catch (e: Throwable) {
            zipFile?.delete()
            val message = e.message ?: e.toString()
            Log.w(TAG, "下载引擎失败", e)
            _state.value = State.Failed(message)
            Result.failure(e)
        }
    }

    /** 流式下载 + 进度回调。 */
    private fun download(url: String, dest: File, expectedSize: Long) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败:HTTP ${resp.code}")
            val body = resp.body ?: error("下载失败:没有响应体")
            val total = body.contentLength().let { if (it > 0) it else expectedSize }
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 进度最多每 200ms 报一次 —— 每 KB 报一次会把 UI 线程打爆。
                        if (done - lastEmit > 256 * 1024) {
                            lastEmit = done
                            _state.value = State.Running(done, total)
                        }
                    }
                    out.flush()
                }
            }
            _state.value = State.Running(total, total)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 解包 + 切换到位。
     *
     * 顺序很重要:全部解完 → 全部校验过 → 才 rename 到正式位置。
     */
    private fun installZip(context: Context, zip: File, manifest: Manifest) {
        val staging = File(context.filesDir, ".bundle-staging-${manifest.fingerprint}")
        staging.deleteRecursively()
        val stagingEngine = File(staging, "engine")
        val stagingNative = File(staging, "native")
        stagingEngine.mkdirs()
        stagingNative.mkdirs()

        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // 目录穿越防护:条目名里带 ".." 或以 "/" 开头的一律跳过。
                    if (name.contains("..") || name.startsWith("/")) {
                        Log.w(TAG, "跳过可疑条目 $name")
                    } else {
                        val base = when {
                            name.startsWith("engine/") -> stagingEngine
                            name.startsWith("native/") -> stagingNative
                            else -> null
                        }
                        if (base != null && !entry.isDirectory) {
                            val rel = name.substringAfter('/')
                            val out = File(base, rel)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { os -> zis.copyTo(os) }
                            // .so 要能执行:librg.so 是被 exec 的(ripgrep),
                            // libdshnode.so 是 node 启动器。
                            if (out.name.endsWith(".so")) {
                                out.setExecutable(true, false)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 解完先自检 —— 解压坏了的话在这儿就被拦住,不会把坏引擎切上位。
            val missing = EngineInstaller.REQUIRED_NATIVES.filterNot {
                File(stagingNative, it).isFile
            }
            if (missing.isNotEmpty()) error("包里缺原生库:${missing.joinToString(", ")}")
            for (rel in listOf("server.mjs", "zai-server.mjs")) {
                if (!File(stagingEngine, rel).isFile) error("包里缺 $rel")
            }
            File(stagingEngine, ".installed").writeText(manifest.sha256)

            // 切换到位。
            val engineRoot = EnginePaths.engineRoot(context)
            engineRoot.mkdirs()
            val target = EnginePaths.engineDir(context, manifest.fingerprint)
            if (target.exists()) target.deleteRecursively()
            if (!stagingEngine.renameTo(target)) error("引擎目录切换失败")

            // 原生库:先挪走旧的,再放新的 —— 顺序反了中间有个「native 目录不存在」
            // 的窗口,那一下引擎重启会 System.load 失败。
            val nativeDir = EnginePaths.nativeDir(context)
            val oldNative = File(context.filesDir, "native.old")
            oldNative.deleteRecursively()
            if (nativeDir.exists()) nativeDir.renameTo(oldNative)
            if (!stagingNative.renameTo(nativeDir)) {
                // 挪回去,别把现役的引擎弄瘫
                if (oldNative.exists()) oldNative.renameTo(nativeDir)
                error("原生库目录切换失败")
            }
            oldNative.deleteRecursively()

            // 旧版本的引擎目录清掉(指纹变了才有旧目录)。
            engineRoot.listFiles()?.forEach { old ->
                if (old != target && old.isDirectory) old.deleteRecursively()
            }
            Log.i(TAG, "引擎已装好:${target.absolutePath}")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 当前 App 上下文。
     *
     * 由 [bind] 在 Application.onCreate 里设一次 —— 下载要写 app 私有目录,
     * 拿不到 Context 就没法落地。
     */
    @Volatile
    private var appContext: Context? = null

    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("EngineBundle 还没 bind 上下文(Application.onCreate 里调 bind)")
}
