// model/QuickCommand.kt — 快捷命令数据模型
package io.github.hotmanxp.lanagent.model

import kotlinx.serialization.Serializable

/**
 * One user-defined quick command, rendered as a tappable chip in
 * SshTerminalScreen and executed over the interactive SSH session.
 *
 * Storage is GLOBAL (one list for all hosts — see
 * [io.github.hotmanxp.lanagent.data.quickCommandsFlow]) because the
 * commands the user reaches for most (tail a log, restart a service,
 * check disk) are mostly host-specific, so the list is ordered by
 * drag-free up/down moves into whatever order the user wants.
 *
 * [confirm] gates execution behind an AlertDialog. Set it for anything
 * destructive (`rm`, `reboot`, `killall`) — a mis-tap on a chip row is
 * much easier than a mis-typed command, so the confirmation belongs on
 * the model rather than the screen.
 *
 * [id] is a UUID string; reorder / edit / delete all match by it so the
 * label can be renamed freely.
 */
@Serializable
data class QuickCommand(
    val id: String,
    val label: String,
    val command: String,
    val confirm: Boolean = false,
)
