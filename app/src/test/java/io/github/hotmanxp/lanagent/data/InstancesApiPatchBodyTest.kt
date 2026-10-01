// data/InstancesApiPatchBodyTest.kt — `PATCH /api/instances/:id` 请求体构造的
// 回归测试。
//
// 这里钉的是**一条服务端契约 + 一个已经上过手机的 bug**:
//
//   服务端 supervisor 对「空补丁」是 fail loud 的(`Object.keys(next).length
//   === 0` → 400 `no patchable fields supplied`),设计意图是挡掉调用方 typo
//   导致的静默 no-op。AA 开关的「关」态曾经正好撞上它 —— 手机端发的是 `{}`,
//   于是用户点 AA 开关永远关不掉,只弹一条英文报错。
//
// 「关」态发什么很关键,三选一:
//   - `Unset`(不发 key)→ 服务端视为不改,def.aa 保持 true,下次 start 又带
//     `--aa`。开关看着动了,实际没关。
//   - `Set(false)`→ force-off,即便 root 带 `--aa` 也不给它。**UI 走这条**,
//     符合「这个实例别被 AA Cloud 看到」的直觉。
//   - `Null`(发字面 null)→ 清除覆盖回到 auto(跟随 root),只从 API 侧用。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstancesApiPatchBodyTest {

    @Test
    fun `aa off sends explicit false, not an empty body`() {
        // 回归钉:这一条以前发出去的是 `{}` → 服务端 400。
        val body = instancePatchBody(aa = PatchValue.Set(false))

        assertEquals(false, body["aa"]?.jsonPrimitive?.boolean)
        // 关键:body 里必须真的有东西,否则又回到空补丁。
        assertTrue(body.isNotEmpty(), "aa off must not produce an empty patch body")
    }

    @Test
    fun `aa on sends true`() {
        assertEquals(true, instancePatchBody(aa = PatchValue.Set(true))["aa"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `aa Unset omits the key entirely`() {
        // 「不改」：服务端看到没这个字段,def.aa 原样保留。
        val body = instancePatchBody(aa = PatchValue.Unset)

        assertNull(body["aa"])
    }

    @Test
    fun `aa Null sends a literal JSON null to clear the override`() {
        val body = instancePatchBody(aa = PatchValue.Null)

        assertEquals(JsonNull, body["aa"])
        assertTrue(body.isNotEmpty(), "aa clear must not produce an empty patch body")
    }

    @Test
    fun `lan and port keep their own three-state encoding`() {
        assertEquals(true, instancePatchBody(lan = PatchValue.Set(true))["lan"]?.jsonPrimitive?.boolean)
        assertEquals(JsonNull, instancePatchBody(port = PatchValue.Null)["port"])
        assertEquals(9500, instancePatchBody(port = PatchValue.Set(9500))["port"]?.jsonPrimitive?.int)
        assertNull(instancePatchBody(lan = PatchValue.Unset, port = PatchValue.Unset)["lan"])
    }

    @Test
    fun `multiple fields merge into one patch`() {
        val body = instancePatchBody(
            lan = PatchValue.Set(false),
            port = PatchValue.Set(9500),
            aa = PatchValue.Set(true),
        )

        assertEquals(false, body["lan"]?.jsonPrimitive?.boolean)
        assertEquals(9500, body["port"]?.jsonPrimitive?.int)
        assertEquals(true, body["aa"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `no arguments yields an empty body (caller error, not a silent success)`() {
        val body: JsonObject = instancePatchBody()

        assertTrue(body.isEmpty())
        // 只是把这个事实钉住:空 body 服务端会 400,调用方必须至少带一个字段。
        assertFalse(body.containsKey("aa"))
    }
}
