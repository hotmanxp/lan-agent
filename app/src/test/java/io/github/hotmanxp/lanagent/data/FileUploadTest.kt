// data/FileUploadTest.kt — 「文件」通道纯函数的回归测试。
//
// 钉的是**服务端契约**（opencc-web fs.ts `POST /fs/upload`）：走
// octet-stream 流式分支时,文件名走 `X-File-Name` 头、body 是原始字节。
// 这两样对不上,表现都是「上传失败」或一个 400 短句,很难从 App 侧反推
// 是哪一环错了。
//
// **本文件刻意不构造 [PickedFile]**:它带一个 `Uri`,而 `Uri.parse` 在没有
// Robolectric 的纯 JVM 单测里是 stub,一碰就 `ExceptionInInitializerError`
// —— 那会让整个 test class 建不起来(不是一条用例红,是 13 条全红)。所以
// 需要元信息的逻辑抽成了顶层纯函数(见 `formatSizeLabel`)。
//
// 读字节 / 流式 body 依赖 ContentResolver 与 OkHttp,跑不了 JVM 单测;
// 「大文件不撑爆内存」要靠真机传大包看内存曲线 —— 那部分靠人工验收。
package io.github.hotmanxp.lanagent.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileUploadTest {

    // ===== X-File-Name 头(fs.ts handleStreamUpload) =====

    @Test
    fun `filename header round trips through decodeURIComponent`() {
        // 服务端 URL-decode 后拿到的必须跟原名逐字相同。中间任何一个环节
        // 改写(spaces 变 `+`、`#` 被当 fragment 吃掉、非 ASCII 被 latin1 截)
        // 都会让落盘名跟用户看到的名字对不上。
        for (name in listOf(
            "stream.bin",
            "报告.pdf",
            "a b.txt",
            "u1688#orgname=dinods.zip",
            "a+b&c=d",
            "100%.txt",
            "C:\\Users\\me\\a.txt",
        )) {
            val encoded = uploadFileNameHeader(name)
            assertEquals(name, java.net.URLDecoder.decode(encoded, "UTF-8"), "往返失败: $name")
        }
    }

    @Test
    fun `filename header encodes space as %20 not plus`() {
        // `+` 在 query 里是空格,在 header 的百分号编码里不是。客户端必须
        // 发 %20 —— 否则服务端 decodeURIComponent 得到的是字面 `+`。
        assertEquals("a%20b.txt", uploadFileNameHeader("a b.txt"))
    }

    // 编码后不含 CR/LF —— 否则能被拿来注入第二个 header
    @Test
    fun `filename header cannot smuggle a newline`() {
        val encoded = uploadFileNameHeader("a\r\nX-Injected: 1")

        assertTrue('\n' !in encoded, "换行未编码: $encoded")
        assertTrue('\r' !in encoded, "回车未编码: $encoded")
    }

    // ===== 文件名清洗(对齐 fs.ts:618 sanitizeUploadName) =====

    @Test
    fun `sanitize strips directory components`() {
        assertEquals("a.txt", sanitizeFileName("/Users/me/a.txt"))
        assertEquals("a.txt", sanitizeFileName("C:\\Users\\me\\a.txt"))
        assertEquals("a.txt", sanitizeFileName("  a.txt  "))
    }

    @Test
    fun `sanitize rejects names the server would 400 on`() {
        // 服务端这几种一律「缺少或非法的 X-File-Name 头」,本地就该早一步
        assertNull(sanitizeFileName(null))
        assertNull(sanitizeFileName(""))
        assertNull(sanitizeFileName("   "))
        assertNull(sanitizeFileName("."))
        assertNull(sanitizeFileName(".."))
        assertNull(sanitizeFileName("/"))
        assertNull(sanitizeFileName("a\nb.txt"), "控制符会破坏后面粘进消息的路径")
        assertNull(sanitizeFileName("a".repeat(201) + ".txt"), "超 200 字服务端拒")
    }

    @Test
    fun `sanitize keeps a 200 char name`() {
        val name = "a".repeat(196) + ".txt" // 200

        assertEquals(name, sanitizeFileName(name))
    }

    // DownloadManager 缓存名那种带 # / = 的要原样留下 —— 它们是真实文件名,
    // 服务端 sanitizeUploadName 也不剥,剥了反而对不上用户看到的名字。
    @Test
    fun `sanitize keeps hash and equals from download manager names`() {
        assertEquals(
            "u1688789024143#orgname=dinods.zip",
            sanitizeFileName("u1688789024143#orgname=dinods.zip"),
        )
    }

    // ===== 大小展示(纯展示,不再参与拦截) =====

    @Test
    fun `size label formats known sizes`() {
        assertEquals("312 KB", formatSizeLabel(320_000))
        assertEquals("1.5 MB", formatSizeLabel(1_572_864))
        assertEquals("1.0 GB", formatSizeLabel(1024L * 1024 * 1024))
    }

    // provider 常给 -1 或不给这一列 —— 别编一个假大小出来
    @Test
    fun `size label is null when size is unknown`() {
        assertNull(formatSizeLabel(-1))
    }

    // 0.27.1 起没有客户端大小预检:字节由 OkHttp 边读边写,大小限制只剩
    // 服务端那一道(1 GiB)。这条测试存在的意义是**钉住「没有预检」** ——
    // 日后有人想加回来时,这条会提醒他先想清楚内存。
    @Test
    fun `no client side size cap exists`() {
        val gb = formatSizeLabel(900L * 1024 * 1024)

        assertTrue(gb!!.endsWith("MB"), "900MB 应照常格式化,不被拦: $gb")
    }

    // ===== 并进输入框(与粘贴同一个约定) =====

    @Test
    fun `path lands alone when input is empty`() {
        assertEquals("/Users/ethan/.zai/uploads/a.pdf", mergeIntoInput("", "/Users/ethan/.zai/uploads/a.pdf"))
        assertEquals("/Users/ethan/.zai/uploads/a.pdf", mergeIntoInput("   ", "/Users/ethan/.zai/uploads/a.pdf"))
    }

    // 已有文字时必须换行,否则路径会和末尾那个词黏成一个 token
    @Test
    fun `path goes on its own line after existing text`() {
        assertEquals(
            "看看这个\n/Users/ethan/.zai/uploads/a.pdf",
            mergeIntoInput("看看这个", "/Users/ethan/.zai/uploads/a.pdf"),
        )
    }

    @Test
    fun `trailing whitespace is trimmed before the newline`() {
        assertEquals("看看这个\n/p.pdf", mergeIntoInput("看看这个  \n ", "/p.pdf"))
    }

    // 连续选两个文件:两个路径各占一行(对齐 web 的 paths.join("\n"))
    @Test
    fun `two files stack as two lines`() {
        val afterOne = mergeIntoInput("", "/a.pdf")
        val afterTwo = mergeIntoInput(afterOne, "/b.pdf")

        assertEquals("/a.pdf\n/b.pdf", afterTwo)
    }
}
