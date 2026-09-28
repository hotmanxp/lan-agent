// data/AgentInstanceRestartTest.kt — 选择实例面板「重启」入口的可用性判据。
//
// 钉的是两条**服务端硬约束**(opencc-web):
//   1. `__current__` 不能重启 —— route `POST /instances/:id/restart` 直接 400
//      `cannot restart current instance`,supervisor 内 `ensureNotCurrent` 再拦一道。
//   2. 生命周期路由挂在 supervisor 上,子实例自己会被 `ensureNotInstanceChild`
//      挡掉;卡片回落路径压根没有 supervisor(`managerBaseUrl == null`)。
//
// 这两条错了不会编译报错,只会在真机上表现为「点了没反应 / 报 400」,所以要有测试。
package io.github.hotmanxp.lanagent.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInstanceRestartTest {

    private fun instance(
        id: String = "inst_code",
        isCurrent: Boolean = false,
        managerBaseUrl: String? = "http://192.168.101.69:9201",
    ) = AgentInstance(
        id = id,
        name = "code",
        baseUrl = "http://192.168.101.69:9202",
        online = true,
        isCurrent = isCurrent,
        managerBaseUrl = managerBaseUrl,
    )

    @Test
    fun `supervisor 管的子实例可以重启`() {
        assertTrue(instance().canRestart())
    }

    @Test
    fun `supervisor 自己不能重启`() {
        // 服务端 route 与 ensureNotCurrent 双重拦截,本地必须先判掉。
        assertFalse(instance(id = "__current__", isCurrent = true).canRestart())
    }

    @Test
    fun `卡片回落路径没有 supervisor 不能重启`() {
        // managerBaseUrl == null —— 连能发请求的地方都没有。
        assertFalse(instance(managerBaseUrl = null).canRestart())
    }

    @Test
    fun `离线实例也能重启`() {
        // restart = doStop + doStart,而 doStop 对没有活子进程的条目是 no-op,
        // 所以「重启一个停掉的实例」等价于把它拉起来 —— 正是从选择面板
        // 救一个崩掉的实例最想做的事。不该按 online 拦。
        val offline = instance().copy(online = false)
        assertTrue(offline.canRestart())
    }

    @Test
    fun `空 managerBaseUrl 不算 supervisor`() {
        // findManagerBaseUrl 理论上不返空串,但 baseUrl 解析失败时会 —— 别让它
        // 变成「对着 http:///api/instances/... 发请求」。
        assertFalse(instance(managerBaseUrl = "").canRestart())
    }
}
