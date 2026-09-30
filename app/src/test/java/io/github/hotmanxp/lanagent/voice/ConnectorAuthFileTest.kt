// voice/ConnectorAuthFileTest.kt — `AsrUrlProvider.ConnectorAuthFile` 的回归保护。
//
// 这个 provider 走的是「手机 → AA 官方服务端 → 你的 Mac」这条 connector 文件通道
// （`fs.readText`），凭据来源是 Mac 上 WorkBuddy 落盘的 auth 文件。测试完全绕开网络：
// `readAuthFile` 是个 lambda，直接喂文件原文；`nowMs` 是假时钟。
//
// 覆盖范围：
//   · auth 文件两种形态解析（标准 `{account,auth}` / 扁平 `{uid,accessToken}`）
//   · 过期时间三级兜底里可单测的两级（`expiresAt` 直给 / `lastRefreshTime + expiresIn`）
//   · 缺 accessToken → 抛
//   · endpoint 恒为 copilot.tencent.com（文件里不带，也没有别的取值）
//   · 缓存命中 / 快过期重读 / invalidateAuth 后重读 / 过期时间解不出则不缓存
//   · 端到端：凭据 → SignedAsrUrl 的协议、header、dialect
//
// ⚠️ JWT `exp` 兜底那条**没法在 JVM 单测里真验**：`expiresAtFromJwt` 走
// `android.util.Base64`，JVM 下是未 mock 的 stub，会抛 "not mocked" 被 runCatching
// 吞掉返回 0。所以这里断言的是「解不出过期时间 → 0 → 不缓存」这个**行为**，
// 不是 JWT 解码本身正确。JWT 解码的真实性靠真机行为兜底。
package io.github.hotmanxp.lanagent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val NOW = 1_700_000_000_000L
private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

/** 够长的假 token —— 生产解析只要求非空，但长度贴近真实 JWT 便于肉眼核对。 */
private const val TOKEN_A = "aaaa.bbbb.cccc"
private const val TOKEN_B = "dddd.eeee.ffff"

/** WorkBuddy 模式忽略 engine（网关地址固定），这里只是占位。 */
private const val ENGINE = "16k_zh"

/** 标准形态：桌面端 macOS 上真实落盘的结构。 */
private fun standardAuthFile(
    accessToken: String = TOKEN_A,
    uid: String? = "user-42",
    expiresAt: Long? = NOW + 3 * DAY,
): String {
    val auth = buildString {
        append("\"accessToken\":\"$accessToken\"")
        append(",\"refreshToken\":\"rt-abc\"")
        if (expiresAt != null) append(",\"expiresAt\":$expiresAt")
    }
    val account = uid?.let { "\"uid\":\"$it\"" } ?: ""
    return """{"account":{$account},"auth":{$auth}}"""
}

class ConnectorAuthFileTest {

@Test
fun `标准形态解析出 endpoint token uid 与过期时间`() {
    val cred = AsrUrlProvider.ConnectorAuthFile.parseAuthFile(standardAuthFile())

    assertEquals("https://copilot.tencent.com", cred.endpoint)
    assertEquals(TOKEN_A, cred.accessToken)
    assertEquals("user-42", cred.uid)
    assertEquals(NOW + 3 * DAY, cred.expiresAtMs)
}

@Test
fun `扁平形态同样能解出 uid 与 token`() {
    val cred = AsrUrlProvider.ConnectorAuthFile.parseAuthFile(
        """{"uid":"u-7","accessToken":"$TOKEN_A","expiresAt":${NOW + DAY}}"""
    )

    assertEquals(TOKEN_A, cred.accessToken)
    assertEquals("u-7", cred.uid)
    assertEquals(NOW + DAY, cred.expiresAtMs)
}

@Test
fun `uid 缺失时为 null 而不是空串`() {
    val cred = AsrUrlProvider.ConnectorAuthFile.parseAuthFile(
        """{"auth":{"accessToken":"$TOKEN_A","expiresAt":${NOW + DAY}}}"""
    )

    assertEquals(null, cred.uid)
}

@Test
fun `expiresIn 加 lastRefreshTime 折算成过期毫秒`() {
    // 桌面端原始形态：没有 expiresAt，只有 lastRefreshTime + expiresIn。
    val cred = AsrUrlProvider.ConnectorAuthFile.parseAuthFile(
        """{"account":{"uid":"u"},"auth":{"accessToken":"$TOKEN_A",
           "lastRefreshTime":$NOW,"expiresIn":259200}}"""
    )

    assertEquals(NOW + 259_200_000L, cred.expiresAtMs)
}

@Test
fun `只有 expiresIn 没有 lastRefreshTime 时不猜 落成 0`() {
    // 拿「读取时刻」当基准的话，一个放了两天的文件会被算成还有三天过期 ——
    // 误差以天计，会把早就作废的 token 缓存起来。宁可返回 0（不缓存、每次现读）。
    val cred = AsrUrlProvider.ConnectorAuthFile.parseAuthFile(
        """{"auth":{"accessToken":"$TOKEN_A","expiresIn":259200}}"""
    )

    assertEquals(0L, cred.expiresAtMs)
}

@Test
fun `expiresAt 直给时优先于 expiresIn 折算`() {
    val cred = AsrUrlProvider.ConnectorAuthFile.parseAuthFile(
        """{"auth":{"accessToken":"$TOKEN_A","expiresAt":${NOW + 2 * DAY},
           "lastRefreshTime":$NOW,"expiresIn":259200}}"""
    )

    assertEquals(NOW + 2 * DAY, cred.expiresAtMs)
}

@Test
fun `缺 accessToken 直接抛`() {
    assertFailsWith<IllegalArgumentException> {
        AsrUrlProvider.ConnectorAuthFile.parseAuthFile("""{"account":{"uid":"u"}}""")
    }
}

@Test
fun `内容不是 JSON 时抛`() {
    assertFailsWith<Exception> {
        AsrUrlProvider.ConnectorAuthFile.parseAuthFile("not json at all")
    }
}

// ── 缓存行为 ──────────────────────────────────────────────────────────────

@Test
fun `会话指纹变了就丢弃缓存 重读文件`() {
    // provider 实例被 composable 记住，可能比一次登录周期活得久。换了账号之后
    // AA token 换了，但 Mac 上的 WorkBuddy token 还在有效期内 —— 只按 expiresAt
    // 判鲜的话缓存照样命中，然后每次按住都吃一发 401。
    var reads = 0
    var fingerprint = "fp-1"
    val provider = AsrUrlProvider.ConnectorAuthFile(
        readAuthFile = { reads++; standardAuthFile() },
        sessionFingerprint = { fingerprint },
        nowMs = { NOW },
    )

    provider.provide(ENGINE)
    assertEquals(1, reads)

    fingerprint = "fp-2" // 重新登录 / 换账号
    provider.provide(ENGINE)

    assertEquals(2, reads)
}

@Test
fun `会话指纹没变时缓存照常命中`() {
    var reads = 0
    val provider = AsrUrlProvider.ConnectorAuthFile(
        readAuthFile = { reads++; standardAuthFile() },
        sessionFingerprint = { "fp-stable" },
        nowMs = { NOW },
    )

    provider.provide(ENGINE)
    provider.provide(ENGINE)

    assertEquals(1, reads, "指纹没变就不该白白重读")
}

@Test
fun `过期时间充裕时只读一次文件`() {
    var reads = 0
    val provider = AsrUrlProvider.ConnectorAuthFile(
        readAuthFile = { reads++; standardAuthFile() },
        nowMs = { NOW },
    )

    provider.provide(ENGINE)
    provider.provide(ENGINE)
    provider.provide(ENGINE)

    assertEquals(1, reads)
}

@Test
fun `距过期不足五分钟时重新读文件`() {
    val expiresAt = NOW + 4 * MIN
    var reads = 0
    val provider = AsrUrlProvider.ConnectorAuthFile(
        readAuthFile = { reads++; standardAuthFile(expiresAt = expiresAt) },
        nowMs = { NOW },
    )

    provider.provide(ENGINE)
    provider.provide(ENGINE)

    assertEquals(2, reads, "过期边界前 5 分钟就该重读，避免建连时正好卡在边界上")
}

@Test
fun `invalidateAuth 之后重新读文件并拿到新 token`() {
    var reads = 0
    val provider = AsrUrlProvider.ConnectorAuthFile(
        readAuthFile = {
            reads++
            standardAuthFile(accessToken = if (reads == 1) TOKEN_A else TOKEN_B)
        },
        nowMs = { NOW },
    )

    val first = provider.provide(ENGINE)
    provider.invalidateAuth()
    val second = provider.provide(ENGINE)

    assertEquals(2, reads)
    assertEquals("Bearer $TOKEN_A", first.headers["Authorization"])
    assertEquals("Bearer $TOKEN_B", second.headers["Authorization"])
}

@Test
fun `过期时间解不出就不缓存 每次现读`() {
    var reads = 0
    val provider = AsrUrlProvider.ConnectorAuthFile(
        readAuthFile = { reads++; standardAuthFile(expiresAt = null) },
        nowMs = { NOW },
    )

    provider.provide(ENGINE)
    provider.provide(ENGINE)

    // 宁可比正常多读一次，也不押一个不知道何时失效的 token。
    assertEquals(2, reads)
}

// ── 端到端 ────────────────────────────────────────────────────────────────

@Test
fun `provide 产出 WorkBuddy 语义的握手地址与请求头`() {
    val provider = AsrUrlProvider.ConnectorAuthFile(
        readAuthFile = { standardAuthFile(uid = "user-42") },
        nowMs = { NOW },
    )

    val signed = provider.provide(ENGINE)

    assertEquals(
        "wss://copilot.tencent.com/clientcap/v2/asr/stream?source=desktop",
        signed.url,
    )
    assertEquals("Bearer $TOKEN_A", signed.headers["Authorization"])
    assertEquals("user-42", signed.headers["X-User-Id"])
    assertEquals(AsrDialect.WorkBuddy, signed.dialect)
    assertTrue(signed.voiceId.isNotBlank())
}
}
