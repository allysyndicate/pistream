package com.pistream.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pistream.companion.data.BluetoothScanResult
import com.pistream.companion.data.OperationActionResult
import com.pistream.companion.data.PiRepository
import com.pistream.companion.domain.BluetoothDeviceModel
import com.pistream.companion.domain.ConnectionResult
import com.pistream.companion.domain.DashboardModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(private val repository: PiRepository) : ViewModel() {
    private val _state = MutableStateFlow(PiUiState())
    val state: StateFlow<PiUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    host = repository.initialHost(),
                    token = repository.savedToken()
                )
            }
        }
    }

    fun updateHost(host: String) {
        _state.update { it.copy(host = host) }
    }

    fun updateToken(token: String) {
        _state.update { it.copy(token = token) }
    }

    fun connect() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = null) }
            val result = repository.connect(state.value.host, state.value.token)
            _state.update {
                when (result) {
                    is ConnectionResult.FoundHealthy -> it.copy(
                        isLoading = false,
                        connectionLabel = "pi_service_healthy",
                        dashboard = result.dashboard,
                        message = result.dashboard.unconfirmedSpeakerStateMessage()
                    )
                    is ConnectionResult.FoundUnhealthy -> it.copy(
                        isLoading = false,
                        connectionLabel = "pi_service_degraded",
                        dashboard = result.dashboard,
                        message = listOfNotNull(
                            "Pi is reachable but reports degraded service health.",
                            result.dashboard.unconfirmedSpeakerStateMessage()
                        ).joinToString("\n")
                    )
                    is ConnectionResult.WrongDevice -> it.copy(
                        isLoading = false,
                        connectionLabel = "wrong_device",
                        dashboard = null,
                        message = "Host ${result.host} does not match the saved Pi identity."
                    )
                    is ConnectionResult.ApiUnavailable -> it.copy(
                        isLoading = false,
                        connectionLabel = "api_unavailable",
                        dashboard = null,
                        message = result.cause
                    )
                    is ConnectionResult.Unauthorized -> it.copy(
                        isLoading = false,
                        connectionLabel = "unauthorized",
                        dashboard = null,
                        message = "Bearer token was rejected. Re-enter the Pi token and retry."
                    )
                    ConnectionResult.NotFound -> it.copy(
                        isLoading = false,
                        connectionLabel = "not_found",
                        dashboard = null,
                        message = "No Pi API responded at the saved, default, or entered host."
                    )
                }
            }
        }
    }

    fun refreshStatus() {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            runOperation { repository.refresh(dashboard) }
        }
    }

    fun reconnect(speakerId: String) {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            runOperation { repository.reconnect(dashboard, speakerId) }
        }
    }

    fun runWatchdog() {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            runOperation { repository.runWatchdog(dashboard) }
        }
    }

    fun restartService() {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            runOperation { repository.restartService(dashboard, "bt_watchdog") }
        }
    }

    fun selectRoute(routeId: String) {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            runOperation { repository.selectRoute(dashboard, routeId) }
        }
    }

    fun updateZoneNameInput(zoneName: String) {
        _state.update { it.copy(zoneNameInput = zoneName) }
    }

    fun scanBluetoothDevices() {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, operationMessage = "Scanning for Bluetooth speakers on the Pi...") }
            val result = try {
                repository.scanBluetoothDevices(dashboard)
            } catch (exception: Exception) {
                BluetoothScanResult(
                    message = exception.message ?: "Bluetooth scan failed.",
                    devices = null
                )
            }
            _state.update {
                it.copy(
                    isScanning = false,
                    operationMessage = result.message,
                    bluetoothDevices = result.devices ?: it.bluetoothDevices
                )
            }
        }
    }

    fun pairSpeaker(address: String) {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            runOperation { repository.pairSpeaker(dashboard, address) }
            refreshBluetoothDevicesQuietly()
        }
    }

    fun assignSpeaker(systemId: String, address: String) {
        val dashboard = state.value.dashboard ?: return
        val zoneName = state.value.zoneNameInput
        viewModelScope.launch {
            runOperation { repository.assignSpeaker(dashboard, systemId, address, zoneName) }
            _state.update { it.copy(zoneNameInput = "") }
        }
    }

    private suspend fun refreshBluetoothDevicesQuietly() {
        val dashboard = state.value.dashboard ?: return
        val result = try {
            repository.scanBluetoothDevices(dashboard, scanSeconds = 0)
        } catch (exception: Exception) {
            return
        }
        result.devices?.let { devices ->
            _state.update { it.copy(bluetoothDevices = devices) }
        }
    }

    fun setSpeakerSystemEnabled(systemId: String, enabled: Boolean) {
        val dashboard = state.value.dashboard ?: return
        val nextEnabledSystemIds = if (enabled) {
            (dashboard.enabledSystemIds + systemId).distinct()
        } else {
            dashboard.enabledSystemIds.filterNot { it == systemId }
        }

        viewModelScope.launch {
            runOperation { repository.setSpeakerSystems(dashboard, nextEnabledSystemIds) }
        }
    }

    private suspend fun runOperation(block: suspend () -> OperationActionResult) {
        _state.update { it.copy(isLoading = true, operationMessage = null) }
        val result = try {
            block()
        } catch (exception: Exception) {
            OperationActionResult(exception.message ?: "Operation failed.")
        }
        _state.update {
            val refreshedDashboard = result.dashboard ?: it.dashboard
            it.copy(
                isLoading = false,
                operationMessage = listOfNotNull(
                    result.message,
                    refreshedDashboard?.unconfirmedSpeakerStateMessage()
                ).joinToString("\n"),
                dashboard = refreshedDashboard
            )
        }
    }
}

private fun DashboardModel.unconfirmedSpeakerStateMessage(): String? {
    val missing = buildList {
        if (!hasSpeakerSystemStatus) add("speaker systems")
        if (!hasAudioOutputStatus) add("audio output assignment")
        if (!hasSpeakerConnectionStatus) add("speaker connection")
    }
    if (missing.isEmpty()) return null
    return "Pi/service health is separate from speaker readiness. Missing Pi-reported ${missing.joinToString()} status."
}

data class PiUiState(
    val host: String = "audiopi.local",
    val token: String = "",
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val connectionLabel: String = "unpaired",
    val message: String? = null,
    val operationMessage: String? = null,
    val dashboard: DashboardModel? = null,
    val bluetoothDevices: List<BluetoothDeviceModel> = emptyList(),
    val zoneNameInput: String = ""
)

class MainViewModelFactory(
    private val repository: PiRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(repository) as T
    }
}
