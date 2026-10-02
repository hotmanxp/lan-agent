package io.github.hotmanxp.lanagent.aa.feature.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hotmanxp.lanagent.aa.api.DevicesApi
import io.github.hotmanxp.lanagent.aa.api.RemoteDevice
import io.github.hotmanxp.lanagent.aa.api.RemoteInstance
import io.github.hotmanxp.lanagent.aa.api.ShellApi
import io.github.hotmanxp.lanagent.aa.feature.auth.AuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for the remote instance management screen. Lives in `feature/`
 * because it is the screen's source of truth — UI only renders, never
 * decides.
 */
sealed class RemoteInstancesUiState {
    data object Loading : RemoteInstancesUiState()
    data object NotSignedIn : RemoteInstancesUiState()
    data class Error(val message: String) : RemoteInstancesUiState()
    data class Loaded(
        val devices: List<RemoteDevice>,
        val instances: List<RemoteInstance>,
        val selectedDeviceId: String,
        val operatingId: String? = null,
    ) : RemoteInstancesUiState()
}

/**
 * Drives the remote instances screen. Talks to AA Cloud through the
 * `DevicesApi` / `ShellApi` surface, both of which reuse the same Bearer
 * token that the AA client already holds — there is no new credential.
 */
class RemoteInstancesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AuthSessionStore(application)
    private val devicesApi = DevicesApi()
    private val shellApi = ShellApi()

    private val _state = MutableStateFlow<RemoteInstancesUiState>(RemoteInstancesUiState.Loading)
    val state: StateFlow<RemoteInstancesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = RemoteInstancesUiState.Loading
            if (!store.hasAuthSession()) {
                _state.value = RemoteInstancesUiState.NotSignedIn
                return@launch
            }
            val serverUrl = store.readServerUrl()
            val token = store.readAccessToken()
            try {
                val devices = withContext(Dispatchers.IO) { devicesApi.listDevices(serverUrl, token) }
                val device = devices.firstOrNull()
                    ?: run {
                        _state.value = RemoteInstancesUiState.Error("账号下没有已配对的设备")
                        return@launch
                    }
                val instances = withContext(Dispatchers.IO) { shellApi.listInstances(serverUrl, token, device.id) }
                _state.value = RemoteInstancesUiState.Loaded(
                    devices = devices,
                    instances = instances,
                    selectedDeviceId = device.id,
                )
            } catch (e: Throwable) {
                _state.value = RemoteInstancesUiState.Error(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun start(id: String) = operate(id) { serverUrl, token, deviceId -> shellApi.startInstance(serverUrl, token, deviceId, id) }

    // 停止后必须重拉列表:行按钮的可用性直接由 `state` 推导,不刷新的话停止
    // 已经成功,start 却还是禁用、stop 还是可点 —— 界面说的不是远端的实况。
    // start / restart 有同样的陈旧状态问题,同样需要 thenRefresh。
    fun stop(id: String) = operate(id, thenRefresh = true) { serverUrl, token, deviceId -> shellApi.stopInstance(serverUrl, token, deviceId, id) }

    fun restart(id: String) = operate(id) { serverUrl, token, deviceId -> shellApi.restartInstance(serverUrl, token, deviceId, id) }
    fun remove(id: String) = operate(id, thenRefresh = true) { serverUrl, token, deviceId -> shellApi.removeInstance(serverUrl, token, deviceId, id); null }

    /**
     * Run one shell.exec action against the selected device, refresh the
     * list from the new snapshot, and surface any failure as a transient
     * error state. The remote terminal is the only state in flight we
     * expose so the UI can disable the row's buttons during the round trip.
     */
    private fun operate(
        id: String,
        thenRefresh: Boolean = false,
        action: (serverUrl: String, token: String, deviceId: String) -> RemoteInstance?,
    ) {
        val loaded = _state.value as? RemoteInstancesUiState.Loaded ?: return
        viewModelScope.launch {
            _state.value = loaded.copy(operatingId = id)
            try {
                withContext(Dispatchers.IO) {
                    action(store.readServerUrl(), store.readAccessToken(), loaded.selectedDeviceId)
                }
                if (thenRefresh) refreshOnce(loaded)
                else _state.value = loaded.copy(operatingId = null)
            } catch (e: Throwable) {
                _state.value = RemoteInstancesUiState.Error(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    private suspend fun refreshOnce(prev: RemoteInstancesUiState.Loaded) {
        try {
            val instances = withContext(Dispatchers.IO) {
                shellApi.listInstances(store.readServerUrl(), store.readAccessToken(), prev.selectedDeviceId)
            }
            _state.value = prev.copy(instances = instances, operatingId = null)
        } catch (e: Throwable) {
            _state.value = RemoteInstancesUiState.Error(e.message ?: e::class.simpleName.orEmpty())
        }
    }
}