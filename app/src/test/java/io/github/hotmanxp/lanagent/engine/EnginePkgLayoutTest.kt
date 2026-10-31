// engine/EnginePkgLayoutTest.kt — 引擎包(tools/build-engine-pkg.mjs 的产物)的结构回归测试。
//
// ## 为什么这些要钉死
//
// 引擎包会进 APK,然后被解到手机上给 Node 跑。这里面的结构约束不是审美问题,
// 每一条都对应一种「装上了但引擎起不来」或者「起得来但工具全废」的坏法:
//
//   - core 必须外置在 node_modules/@zn-ai/zn-agent-core/ 下。core 用
//     import.meta.resolve('@zn-ai/zn-agent-core') 自引用找回自己,bundle
//     进去就 resolve 不到 → agent 工具全废,而服务启动得好好的(最难查的一类)。
//   - symlink 打进 APK 就是断链,运行时才炸,日志里只有一句 ENOENT。
//   - core 的 package.json 的 main/exports 指错文件,core 一样 resolve 不到。
//   - zod 的 v3 / v4 / v4-mini 少一个,core 静态 import 就炸(它 import 了
//     108 次 zod/v4)。
//
// 跑法:./gradlew :app:testDebugUnitTest
package io.github.hotmanxp.lanagent.engine

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 引擎包的结构校验器。
 *
 * 单独拎出来是因为它既被真实产物的测试用,也被「拿一个坏包试试校验器会不会
 * 报错」那条用例用 —— 校验器自己必须是被测过的,不然它可能永远说「没问题」。
 */
internal object EnginePkgLayout {

    /** 必须存在的文件(相对引擎包根目录)。 */
    val REQUIRED_FILES = listOf(
        "server.mjs",
        "zai-server.mjs",
        "engine.sha256",
        "node_modules/@zn-ai/zn-agent-core/package.json",
        "node_modules/@zn-ai/zn-agent-core/opencc-core.mjs",
        "node_modules/zod/package.json",
        "node_modules/zod/index.js",
        "node_modules/fflate/package.json",
    )

    /** 必须存在的 zod 子路径。core 静态 import 了 108 次 zod/v4。 */
    val ZOD_SUBPATHS = listOf("v3", "v4", "v4-mini")

    /** 返回问题列表;空列表 = 这个包没问题。 */
    fun problems(root: File): List<String> {
        val problems = mutableListOf<String>()

        for (rel in REQUIRED_FILES) {
            val f = File(root, rel)
            if (!f.isFile) {
                problems += "缺文件 $rel"
            } else if (f.length() == 0L) {
                problems += "$rel 是空文件"
            }
        }

        // core 的 package.json:main / exports 必须指向真实存在的文件 ——
        // core 是靠 package.json 被 import.meta.resolve 找到的,指错了就是
        // 「服务起得来但 agent 工具全废」。
        val corePkg = File(root, "node_modules/@zn-ai/zn-agent-core/package.json")
        if (corePkg.isFile) {
            val pkg = runCatching { EnginePkgLayoutJson.parse(corePkg) }.getOrNull()
            if (pkg == null) {
                problems += "core 的 package.json 解析失败"
            } else {
                val targets = mutableListOf<Pair<String, String>>()
                pkg.main?.let { targets += ("main" to it) }
                pkg.exportsDot?.let { targets += ("exports[\".\"]" to it) }
                if (targets.isEmpty()) {
                    problems += "core 的 package.json 既没有 main 也没有 exports[\".\"]"
                }
                val coreDir = corePkg.parentFile
                for ((label, target) in targets) {
                    val resolved = File(coreDir, target.removePrefix("./"))
                    if (!resolved.isFile) {
                        problems += "core 的 package.json $label 指向不存在的文件:$target"
                    }
                }
            }
        }

        // zod 子路径
        for (sp in ZOD_SUBPATHS) {
            val idx = File(root, "node_modules/zod/$sp/index.js")
            if (!idx.isFile) {
                problems += "zod 的 $sp 子路径没有 index.js"
            }
        }

        // symlink 是硬要求:断链进 APK 会静默失败
        val symlinks = findSymlinks(root)
        if (symlinks.isNotEmpty()) {
            problems += "产物里有 ${symlinks.size} 个 symlink(断链进 APK 会静默失败):" +
                symlinks.take(5).joinToString(", ") { it.relativeTo(root).path }
        }

        return problems
    }

    /** 找出整棵树里的 symlink。 */
    fun findSymlinks(root: File): List<File> {
        val found = mutableListOf<File>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                // 用 isDirectory 会跟着软链走,必须先看是不是软链本身
                if (java.nio.file.Files.isSymbolicLink(c.toPath())) {
                    found += c
                } else if (c.isDirectory) {
                    walk(c)
                }
            }
        }
        if (root.isDirectory) walk(root)
        return found
    }
}

/** core 的 package.json 里我们关心的两个字段。 */
private data class CorePackageJson(val main: String?, val exportsDot: String?)

/**
 * 极简 JSON 抽取 —— 只取 main 和 exports["."]。
 *
 * 不用 org.json / kotlinx.serialization:这个测试要能对**任意** package.json
 * 报错(而不是解析失败抛异常),所以宁可自己抠字符串。
 */
private object EnginePkgLayoutJson {
    fun parse(file: File): CorePackageJson {
        val text = file.readText()
        val main = Regex("\"main\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        // exports 可能是字符串,也可能是对象;两种都要认。
        val exportsBlock = Regex("\"exports\"\\s*:\\s*(\\{[^{}]*\\}|\"[^\"]*\")").find(text)?.groupValues?.get(1)
        val dot = exportsBlock?.let { block ->
            if (block.startsWith("\"")) {
                block.trim('"').takeIf { it.isNotEmpty() }
            } else {
                Regex("\"\\.\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1)
            }
        }
        return CorePackageJson(main, dot)
    }
}

class EnginePkgLayoutTest {

    // ── 真实产物 ─────────────────────────────────────────────────────────────

    @Test
    fun `引擎包结构完整`() {
        val root = requireEnginePkg()
        val problems = EnginePkgLayout.problems(root)
        assertTrue(
            problems.isEmpty(),
            "引擎包结构有问题:\n" + problems.joinToString("\n") { "  - $it" } +
                "\n(引擎包位置:$root)",
        )
    }

    @Test
    fun `没有 symlink`() {
        val root = requireEnginePkg()
        val symlinks = EnginePkgLayout.findSymlinks(root)
        assertTrue(
            symlinks.isEmpty(),
            "引擎包里有 symlink,进 APK 就是断链:" +
                symlinks.joinToString(", ") { it.relativeTo(root).path },
        )
    }

    @Test
    fun `core 的 package json 指向真实存在的文件`() {
        val root = requireEnginePkg()
        val coreDir = File(root, "node_modules/@zn-ai/zn-agent-core")
        val pkg = EnginePkgLayoutJson.parse(File(coreDir, "package.json"))
        assertTrue(pkg.main != null, "core 的 package.json 没有 main")
        assertTrue(pkg.exportsDot != null, "core 的 package.json 没有 exports[\".\"]")
        assertTrue(
            File(coreDir, pkg.main!!.removePrefix("./")).isFile,
            "core 的 main 指向不存在的文件:${pkg.main}",
        )
        assertTrue(
            File(coreDir, pkg.exportsDot!!.removePrefix("./")).isFile,
            "core 的 exports[\".\"] 指向不存在的文件:${pkg.exportsDot}",
        )
    }

    @Test
    fun `zod 的子路径齐全`() {
        val root = requireEnginePkg()
        for (sp in EnginePkgLayout.ZOD_SUBPATHS) {
            assertTrue(
                File(root, "node_modules/zod/$sp/index.js").isFile,
                "zod 缺 $sp 子路径 —— core 静态 import 了 zod/v4,少一个就炸",
            )
        }
    }

    // ── 校验器自己 ───────────────────────────────────────────────────────────

    @Test
    fun `校验器能挑出残缺的包`() {
        // 造一个只有壳的假包:校验器必须报出来。
        // 少了这条,校验器可能因为写错而永远返回空列表 —— 那所有真实产物的
        // 测试都会「通过」,等于没测。
        val dir = createTempDirectory("engine-pkg-broken").toFile()
        try {
            File(dir, "server.mjs").writeText("// fake")
            val problems = EnginePkgLayout.problems(dir)
            assertTrue(problems.isNotEmpty(), "残缺的包居然没报问题")
            assertTrue(
                problems.any { it.contains("zai-server.mjs") },
                "应该报出缺 zai-server.mjs,实际:$problems",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `校验器能挑出 symlink`() {
        val dir = createTempDirectory("engine-pkg-link").toFile()
        try {
            EnginePkgLayout.REQUIRED_FILES.forEach { rel ->
                val f = File(dir, rel)
                f.parentFile?.mkdirs()
                f.writeText("x")
            }
            for (sp in EnginePkgLayout.ZOD_SUBPATHS) {
                val f = File(dir, "node_modules/zod/$sp/index.js")
                f.parentFile?.mkdirs()
                f.writeText("x")
            }
            // 造一个断链:指向不存在的目标
            val link = File(dir, "node_modules/zod/v4/broken")
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(dir, "nope").toPath())
            val problems = EnginePkgLayout.problems(dir)
            assertTrue(
                problems.any { it.contains("symlink") },
                "应该报出 symlink,实际:$problems",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 构建脚本本身的约束 ───────────────────────────────────────────────────

    @Test
    fun `构建脚本把必须外置的依赖声明对了`() {
        val script = requireBuildScript()
        val text = script.readText()
        // 这些必须留在 bundle 之外,否则要么 resolve 不到,要么直接崩。
        for (spec in listOf("sharp", "node-pty", "@zn-ai/zn-agent-core", "zod", "zod/v3", "zod/v4")) {
            assertTrue(
                text.contains("'$spec'"),
                "构建脚本的 external 列表里没有 $spec",
            )
        }
    }

    @Test
    fun `构建脚本的 createRequire 标识符带前缀`() {
        val script = requireBuildScript()
        val text = script.readText()
        // 标识符不能叫 __createRequire —— core bundle 里已经声明了同名标识符,
        // 报 "Identifier '__createRequire' has already been declared"。
        assertTrue(
            text.contains("as __zaiCreateRequire"),
            "banner 里应该用 __zaiCreateRequire",
        )
        assertTrue(
            !text.contains("as __createRequire"),
            "banner 里不能出现 as __createRequire —— 会跟 core bundle 的标识符撞名",
        )
    }

    @Test
    fun `构建脚本用 cp 解引用而不是直接拷`() {
        val script = requireBuildScript()
        val text = script.readText()
        // pnpm 的 node_modules 全是软链,不解引用打进 APK 就是断链。
        assertTrue(
            text.contains("dereference: true"),
            "拷贝 node_modules 时必须 dereference —— pnpm 的软链直接拷进 APK 会断链",
        )
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun requireEnginePkg(): File {
        val dir = enginePkgDirOrNull()
        // JUnit4 的 assumeTrue 参数顺序是 (message, condition)。
        assumeTrue(
            "引擎包还没构建(产物在 app/src/main/assets/engine,已 gitignore)—— " +
                "先跑 tools/build-engine-pkg.mjs 再跑这条用例",
            dir != null,
        )
        return dir!!
    }

    private fun requireBuildScript(): File {
        val f = File(repoRoot(), "tools/build-engine-pkg.mjs")
        assertTrue(f.isFile, "找不到构建脚本:${f.absolutePath}")
        return f
    }

    private fun enginePkgDirOrNull(): File? {
        System.getProperty("engine.pkg.dir")?.takeIf { it.isNotBlank() }?.let {
            return File(it).takeIf { p -> p.isDirectory }
        }
        System.getenv("ENGINE_PKG_DIR")?.takeIf { it.isNotBlank() }?.let {
            return File(it).takeIf { p -> p.isDirectory }
        }
        return File(repoRoot(), "app/src/main/assets/engine").takeIf { it.isDirectory }
    }

    /** 往上找带 tools/ 的目录 —— 单测的 CWD 是模块目录,不是仓库根。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "tools/build-engine-pkg.mjs").isFile) return dir
            val parent = dir.parentFile ?: return dir
            if (parent == dir) return dir
            dir = parent
        }
    }
}
