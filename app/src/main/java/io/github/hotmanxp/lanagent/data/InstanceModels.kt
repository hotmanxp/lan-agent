// data/InstanceModels.kt — 与 opencc-web packages/zai/src/shared/instances.ts
// 对齐的实例快照模型 + fs picker 模型。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 五种状态机。`down` 是心跳超时但还没超过 3 分钟 — UI 层会把超过 3 分钟的
 * `down` 视为 `stopped` 让「启动」按钮可点(见 InstancesScreen 的 effectiveState)。
 */
@Serializable
enum class InstanceState { stopped, starting, running, stopping, down }

/**
 * 实例启动 profile(0.8.0 新增) — 与 opencc-web `InstanceDefinition.app`
 * 字段对齐(见 `packages/zai/src/shared/instances.ts`)。
 *
 * 仅 `'task-factory'` 一个字面量(没有 `'standard'`):标准实例对应字段缺省。
 * 创建路径 supervisor spawn 时把它转成 `--app task-factory` flag 传给子进程,
 * `cli/index.ts` 把 `process.env.ZAI_APP = 'task-factory'` 落到进程环境,
 * `routes/agent.ts` 据此强制把 `mainAgent` 锁定为 `'task-factory'`,不走全局
 * `settings.mainAgent`;`/api/system` 回显后前端 `TaskFactoryRedirect` 把入口
 * 重定向到 `/super-tasks`。**创建后不可改**(PATCH 不接受 `app` 字段)。
 *
 * 注意:`@SerialName("task-factory")` 把 Kotlin enum 名 `TaskFactory` 序列化为
 * web 端字符串 `'task-factory'`(`-` 不是合法 Kotlin 标识符)。
 *
 * 历史字段:`runtimeCore`(对齐 opencc-web `RuntimeCore` 枚举)于 2026-09-12
 * opencc-web 阶段 3 删除后从本文件移除 — 运行时只剩 `repl` 一种形态,
 * 不再允许用户切换(`packages/zai/src/shared/settings.ts` 已删 `RuntimeCore`
 * 类型与 `runtimeCore` 字段,见 `docs/superpowers/specs/
 * 2026-08-30-inproc-repl-extract-design.md` §5.1)。
 */
@Serializable
enum class InstanceAppProfile {
    @SerialName("task-factory")
    TaskFactory,
}

@Serializable
data class InstanceError(val at: String, val message: String)

/**
 * 完整实例快照 = definition + runtime status + isCurrent。
 * 与 web 端 `InstanceSnapshot = InstanceDefinition & InstanceStatus & { isCurrent }` 对齐。
 *
 * `lan`、`startPort` 仅 child 实例有;`__current__` 实例不会发送这些字段
 * (kotlinx.serialization 在 ignoreUnknownKeys=true + 字段可空时,缺失字段视作 null)。
 */
@Serializable
data class InstanceSnapshot(
    val id: String,
    val name: String,
    val cwd: String,
    val createdAt: String,
    val lan: Boolean? = null,
    val startPort: Int? = null,
    /**
     * 启动 profile(0.8.0 新增,见 [InstanceAppProfile])。仅 `task-factory`
     * 实例存在;`null` / 缺省 = 标准实例。**只读** — 创建后不可改,
     * PATCH `/api/instances/:id` 不接受 `app` 字段。
     */
    val app: InstanceAppProfile? = null,
    val state: InstanceState,
    val port: Int? = null,
    val pid: Int? = null,
    val startedAt: String? = null,
    val lastHeartbeatAt: String? = null,
    val lastError: InstanceError? = null,
    val isCurrent: Boolean = false,
)

@Serializable
data class FsPickerEntry(val name: String, val path: String, val type: String)

@Serializable
data class FsPickerList(
    val ok: Boolean = false,
    val error: String? = null,
    val path: String? = null,
    val parent: String? = null,
    val home: String? = null,
    val entries: List<FsPickerEntry> = emptyList(),
)