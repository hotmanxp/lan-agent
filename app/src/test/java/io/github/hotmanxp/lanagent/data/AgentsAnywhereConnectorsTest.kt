// app/src/test/.../data/AgentsAnywhereConnectorsTest.kt — AA 设备 / connector 解析(0.24.0)
//
// 钉三件事:
//   1. `toAaConnectorOrNull` 的字段映射(照 Agents-Anywhere `DevicesApi.parseDevice`)
//   2. `AaConnector.online` 的**宽松**判定 —— 只认 offline 为离线
//   3. OAuth token 响应是 **snake_case**,和 mobile-login 的 camelCase 是两套
//      形状(拿同一个 DTO 套两条流会静默解不出 accessToken)
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

class AgentsAnywhereConnectorsTest {

    private fun obj(raw: String) = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `全字段映射`() {
        val c = obj(
            """
            {"id":"conn_1","name":"Studio Mac","status":"online","deviceOs":"darwin",
             "lastSeenAt":"2026-09-28T10:00:00Z","createdAt":"2026-09-01T00:00:00Z",
             "updatedAt":"2026-09-28T10:00:00Z","extraField":123}
            """
        ).toAaConnectorOrNull()!!
        assertEquals("conn_1", c.id)
        assertEquals("Studio Mac", c.name)
        assertEquals("online", c.status)
        assertEquals("darwin", c.deviceOs)
        assertEquals("2026-09-28T10:00:00Z", c.lastSeenAt)
        // 未知字段不能炸,也不能丢 —— raw 留了一份。
        assertEquals(123, c.raw["extraField"].toString().toInt())
    }

    @Test
    fun `缺 id 视为无效条目被丢弃`() {
        assertNull(obj("""{"name":"无 id 的设备"}""").toAaConnectorOrNull())
    }

    /** 「没名字」保持 null —— 展示层自己兜底,不在 parser 里抹平。 */
    @Test
    fun `缺 name 保持 null 而不是兜成字符串`() {
        val c = obj("""{"id":"conn_2","status":"online"}""").toAaConnectorOrNull()!!
        assertNull(c.name)
    }

    // ── online 判定刻意宽松 ────────────────────────────────────────────

    @Test
    fun `只有 offline 算离线`() {
        assertFalse(obj("""{"id":"a","status":"offline"}""").toAaConnectorOrNull()!!.online)
    }

    @Test
    fun `offline 大小写不敏感`() {
        assertFalse(obj("""{"id":"a","status":"OFFLINE"}""").toAaConnectorOrNull()!!.online)
    }

    /** 反向策略(未知当离线)会让服务端加个新状态就集体变灰,比误报在线更难查。 */
    @Test
    fun `未知与缺失状态都算在线`() {
        assertTrue(obj("""{"id":"a","status":"busy"}""").toAaConnectorOrNull()!!.online)
        assertTrue(obj("""{"id":"a","status":"something_new"}""").toAaConnectorOrNull()!!.online)
        assertTrue(obj("""{"id":"a"}""").toAaConnectorOrNull()!!.online)
        assertTrue(obj("""{"id":"a","status":null}""").toAaConnectorOrNull()!!.online)
    }

    // ── OAuth 响应形状 ─────────────────────────────────────────────────

    /** wire 是 snake_case —— 写成 camelCase 会解不出 accessToken(整条登录流哑掉)。 */
    @Test
    fun `OAuth token 响应按 snake_case 解`() {
        val t = json.decodeFromString(
            AaOAuthTokenResponse.serializer(),
            """{"access_token":"AT","token_type":"Bearer","expires_in":3600,"scope":"profile","refresh_token":"RT"}""",
        )
        assertEquals("AT", t.accessToken)
        assertEquals("Bearer", t.tokenType)
        assertEquals(3600L, t.expiresIn)
        assertEquals("RT", t.refreshToken)
    }

    @Test
    fun `OAuth 响应缺可选字段不炸`() {
        val t = json.decodeFromString(
            AaOAuthTokenResponse.serializer(),
            """{"access_token":"AT"}""",
        )
        assertEquals("AT", t.accessToken)
        assertNull(t.refreshToken)
    }

    @Test
    fun `配对请求体字段名与服务端一致`() {
        val body = json.encodeToString(
            AaPairingClaimRequest.serializer(),
            AaPairingClaimRequest(
                code = "123456",
                name = "我的设备",
                serverUrl = "https://server.example.com",
                connectorId = "conn_1",
                connectorToken = "tok",
            ),
        )
        listOf("\"code\"", "\"name\"", "\"serverUrl\"", "\"connectorId\"", "\"connectorToken\"")
            .forEach { assertTrue("缺 $it: $body", body.contains(it)) }
    }
}
