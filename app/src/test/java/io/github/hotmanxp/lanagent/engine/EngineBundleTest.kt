// engine/EngineBundleTest.kt — 「按需下载」的引擎包(zip + manifest)结构回归测试。
//
// ## 为什么这些要钉死
//
// App 下载引擎靠的是 manifest 里那个 sha256:校验不过就删掉重下,绝不解包。
// 所以 manifest 与 zip 对不上 = 用户永远下载不成功,而且报错只有一句
// 「校验和不符」,极难查。
//
// zip 里的布局同样要钉:engine/ 与 native/ 两个顶层目录,少一个就装不成。
//
// 跑法:./gradlew :app:testDebugUnitTest
package io.github.hotmanxp.lanagent.engine

import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineBundleTest {

    private val bundleDir: File?
        get() = File(repoRoot(), "dist/engine-bundle").takeIf { it.isDirectory }

    private fun manifestFile(): File? =
        bundleDir?.let { File(it, "engine-manifest.json") }?.takeIf { it.isFile }

    @Test
    fun `manifest 的 sha256 与 zip 实测一致`() {
        val manifest = manifestFile()
        assumeTrue(
            "引擎包还没打包(产物在 dist/engine-bundle,已 gitignore)—— " +
                "先跑 tools/pack-engine-bundle.mjs 再跑这条用例",
            manifest != null,
        )
        val text = manifest!!.readText()
        val expected = jsonString(text, "sha256")
        val fileName = jsonString(text, "file")
        assertTrue(expected.isNotBlank(), "manifest 没有 sha256")
        val zip = File(manifest.parentFile, fileName)
        assertTrue(zip.isFile, "manifest 指的 zip 不存在:${zip.absolutePath}")
        assertEquals(expected, sha256Of(zip), "manifest 的 sha256 与 zip 实测对不上")
    }

    @Test
    fun `manifest 的 size 与 zip 实际大小一致`() {
        val manifest = manifestFile()
        assumeTrue("引擎包还没打包", manifest != null)
        val text = manifest!!.readText()
        val size = jsonNumber(text, "size")
        val zip = File(manifest.parentFile, jsonString(text, "file"))
        assertTrue(zip.isFile, "manifest 指的 zip 不存在")
        assertEquals(zip.length(), size, "manifest 的 size 与 zip 实际大小对不上")
    }

    @Test
    fun `zip 里有 engine 与 native 两个顶层目录且内容齐全`() {
        val manifest = manifestFile()
        assumeTrue("引擎包还没打包", manifest != null)
        val zip = File(manifest!!.parentFile, jsonString(manifest.readText(), "file"))
        assumeTrue("zip 不存在", zip.isFile)

        val names = mutableSetOf<String>()
        ZipFile(zip).use { zf ->
            for (e in zf.entries()) {
                names += e.name
            }
        }
        // 引擎包:App 侧解到 filesDir/engine/<指纹>/
        for (rel in listOf(
            "engine/server.mjs",
            "engine/zai-server.mjs",
            "engine/engine.sha256",
            "engine/node_modules/@zn-ai/zn-agent-core/opencc-core.mjs",
            "engine/node_modules/zod/v4/index.js",
        )) {
            assertTrue(names.contains(rel), "zip 里缺 $rel")
        }
        // 原生库:App 侧解到 filesDir/native/,JNI 用 System.load 绝对路径加载
        for (name in EngineInstaller.REQUIRED_NATIVES) {
            assertTrue(names.contains("native/$name"), "zip 里缺原生库 native/$name")
        }
        assertTrue(names.contains("native/librg.so"), "zip 里缺 native/librg.so(Grep/Glob 要用)")
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

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

    /** manifest 是我们自己写的扁平 JSON,正则取字段就够(不引 JSON 库)。 */
    private fun jsonString(text: String, key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text) ?: return ""
        return m.groupValues[1]
    }

    private fun jsonNumber(text: String, key: String): Long {
        val m = Regex("\"$key\"\\s*:\\s*(\\d+)").find(text) ?: return -1
        return m.groupValues[1].toLong()
    }

    /** 往上找带 tools/ 的目录 —— 单测的 CWD 是模块目录,不是仓库根。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "tools/pack-engine-bundle.mjs").isFile) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }
}
