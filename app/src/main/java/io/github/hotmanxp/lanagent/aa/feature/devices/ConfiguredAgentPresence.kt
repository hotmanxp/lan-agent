package io.github.hotmanxp.lanagent.aa.feature.devices

import io.github.hotmanxp.lanagent.aa.model.AgentDevice

/** Count persisted configuration, including offline devices and stopped Agents. */
suspend fun hasConfiguredDeviceAgents(
    devices: List<AgentDevice>,
    listRuntimes: suspend (String) -> Result<DeviceRuntimeList>,
): Boolean = devices.any { device ->
    listRuntimes(device.id).getOrThrow().runtimes.any { it.configured }
}
