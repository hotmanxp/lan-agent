// app/src/test/.../ui/NavTabRecoveryTest.kt — 底栏 tab 恢复兜底(0.24.0)
//
// 钉的是一条**启动即崩**的回归:0.24.0 把底栏从五栏收成四栏,删掉了
// `Instances` / `Ssh` 两个枚举值。而 `MainScaffold` 的 `currentTab` 走
// `rememberSaveable`,持久化的是**枚举 name**。已装用户进程被杀后恢复
// SavedState 时,写回的是 `"Instances"` —— `valueOf` 撞上直接
// `IllegalArgumentException`,崩在启动路径上,用户连 App 都打不开。
//
// 修法是 `TabDestination.fromNameOrDefault` 的 `runCatching` 兜底。这个测试
// 保证**兜底逻辑不许被删**,也保证新的野值(未来再次改名)同样安全。
package io.github.hotmanxp.lanagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTabRecoveryTest {

    @Test
    fun `四个 tab 的 name 与 route 一一对应`() {
        assertEquals(
            listOf("tab/tasks", "tab/remote", "tab/services", "tab/settings"),
            TabDestination.entries.map { it.route },
        )
    }

    @Test
    fun `fromNameOrDefault 认得出全部现役 tab`() {
        TabDestination.entries.forEach { tab ->
            assertEquals(tab, TabDestination.fromNameOrDefault(tab.name))
        }
    }

    /** 0.23.0 之前存在的两个 tab name —— 老用户 SavedState 里就是它们。 */
    @Test
    fun `已删除的旧 tab name 落回任务栏而不是抛异常`() {
        assertEquals(TabDestination.Tasks, TabDestination.fromNameOrDefault("Instances"))
        assertEquals(TabDestination.Tasks, TabDestination.fromNameOrDefault("Ssh"))
    }

    @Test
    fun `null 与空串也落回任务栏`() {
        assertEquals(TabDestination.Tasks, TabDestination.fromNameOrDefault(null))
        assertEquals(TabDestination.Tasks, TabDestination.fromNameOrDefault(""))
    }

    @Test
    fun `完全未知的 name 落回任务栏`() {
        assertEquals(TabDestination.Tasks, TabDestination.fromNameOrDefault("SomethingElse"))
    }

    @Test
    fun `显式传入的 default 生效`() {
        assertEquals(
            TabDestination.Services,
            TabDestination.fromNameOrDefault("Nonsense", TabDestination.Services),
        )
    }

    /** `Enum.valueOf` 大小写敏感 —— 小写 name 是野值,和未知名一样兜底。 */
    @Test
    fun `大小写不匹配也当野值兜底`() {
        assertEquals(TabDestination.Tasks, TabDestination.fromNameOrDefault("settings"))
    }
}
