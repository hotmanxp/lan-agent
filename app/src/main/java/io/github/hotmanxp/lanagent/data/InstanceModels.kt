// data/InstanceModels.kt — 与 opencc-web packages/zai/src/shared/instances.ts
// 对齐的实例快照模型 + fs picker 模型。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.Serializable

/**
 * 五种状态机。`down` 是心跳超时但还没超过 3 分钟 — UI 层会把超过 3 分钟的
 * `down` 视为 `stopped` 让「启动」按钮可点(见 InstancesScreen 的 effectiveState)。
 */
@Serializable
enum class InstanceState { stopped, starting, running, stopping, down }

@Serializable
enum class InstanceKernel { opencc, dsh }

@Serializable
data class InstanceError(val at: String, val message: String)

/**
 * 完整实例快照 = definition + runtime status + isCurrent。
 * 与 web 端 `InstanceSnapshot = InstanceDefinition & InstanceStatus & { isCurrent }` 对齐。
 *
 * `lan`、`startPort`、`kernel` 仅 child 实例有;`__current__` 实例不会发送这些字段
 * (kotlinx.serialization 在 ignoreUnknownKeys=true + 字段可空时,缺失字段视作 null)。
 * `kernel` 为 null 表示继承全局设置(只在 PATCH 时显式 null 才会清除)。
 */
@Serializable
data class InstanceSnapshot(
    val id: String,
    val name: String,
    val cwd: String,
    val createdAt: String,
    val lan: Boolean? = null,
    val startPort: Int? = null,
    val kernel: InstanceKernel? = null,
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