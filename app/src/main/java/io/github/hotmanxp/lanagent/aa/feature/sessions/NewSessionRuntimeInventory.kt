package io.github.hotmanxp.lanagent.aa.feature.sessions

import io.github.hotmanxp.lanagent.aa.feature.devices.DeviceRuntime
import io.github.hotmanxp.lanagent.aa.feature.devices.DeviceRuntimeStatus
import java.util.Locale

fun activeNewSessionRuntimes(runtimes: List<DeviceRuntime>): List<DeviceRuntime> = runtimes
    .filter { it.configured && it.active && it.status == DeviceRuntimeStatus.Running }
    .sortedWith(compareBy<DeviceRuntime> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })

fun newSessionInventoryNeedsSettling(runtimes: List<DeviceRuntime>): Boolean =
    runtimes.isEmpty() || runtimes.any { it.configured && it.active && it.status != DeviceRuntimeStatus.Running }
