package com.pistream.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pistream.companion.data.BluetoothScanResult
import com.pistream.companion.data.OperationActionResult
import com.pistream.companion.data.PairingAttempt
import com.pistream.companion.data.PiNetworkDiscoverer
import com.pistream.companion.data.PiRepository
import com.pistream.companion.domain.BluetoothDeviceModel
import com.pistream.companion.domain.ConnectionResult
import com.pistream.companion.domain.DashboardModel
import com.pistream.companion.domain.DiscoveredPi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: PiRepository,
    private val discoverer: PiNetworkDiscoverer,
    private val clientInstanceId: String
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var discoveryJob: Job? = null

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            if (!repository.hasSavedPi()) {
                _state.update { it.copy(stage = HomeStage.Empty) }
                return@launch
            }
            val host = repository.initialHost()
            val token = repository.savedToken()
            if (token.isBlank()) {
                _state.update {
                    it.copy(
                        stage = HomeStage.ConnectionFailed,
                        savedHost = host,
                        statusMessage = "Saved Pi has no stored pairing token. Forget the Pi and pair again."
                    )
                }
                return@launch
            }
            attemptConnect(host, token)
        }
    }

    private suspend fun attemptConnect(host: String, token: String) {
        _state.update {
            it.copy(
                stage = HomeStage.Connecting,
                connectionLabel = ConnectionLabel.None,
                savedHost = host,
                statusMessage = null
            )
        }
        val result = repository.connect(host, token)
        applyConnectionResult(host, result)
    }

    private fun applyConnectionResult(host: String, result: ConnectionResult) {
        _state.update { current ->
            when (result) {
                is ConnectionResult.FoundHealthy -> current.copy(
                    stage = HomeStage.Connected,
                    connectionLabel = ConnectionLabel.Connected(host),
                    savedHost = host,
                    dashboard = result.dashboard,
                    statusMessage = null
                )
                is ConnectionResult.FoundDemo -> current.copy(
                    stage = HomeStage.Connected,
                    connectionLabel = ConnectionLabel.Demo(host),
                    savedHost = host,
                    dashboard = result.dashboard,
                    statusMessage = null
                )
                is ConnectionResult.FoundUnhealthy -> current.copy(
                    stage = HomeStage.Connected,
                    connectionLabel = ConnectionLabel.Degraded(host),
                    savedHost = host,
                    dashboard = result.dashboard,
                    statusMessage = "Pi is reachable but reports degraded health."
                )
                is ConnectionResult.WrongDevice -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    statusMessage = "Host $host now reports a different Pi identity. Forget this Pi to pair the new one."
                )
                is ConnectionResult.Unauthorized -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    statusMessage = "Pi rejected the saved pairing token. Forget this Pi and pair again."
                )
                is ConnectionResult.ApiUnavailable -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    statusMessage = "Could not reach $host: ${result.cause}"
                )
                ConnectionResult.NotFound -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    statusMessage = "Could not reach $host on this network. Make sure the Pi is online and on the same Wi-Fi."
                )
            }
        }
    }

    fun retrySavedConnection() {
        val host = state.value.savedHost ?: return
        val token = repository.savedToken()
        viewModelScope.launch {
            if (token.isBlank()) {
                _state.update {
                    it.copy(
                        stage = HomeStage.ConnectionFailed,
                        statusMessage = "No pairing token stored. Forget this Pi and pair again."
                    )
                }
                return@launch
            }
            attemptConnect(host, token)
        }
    }

    fun startConnectFlow() {
        _state.update {
            it.copy(
                stage = HomeStage.Discovering,
                discoveredPis = emptyList(),
                pairing = null,
                manualHostInput = "",
                manualTokenInput = "",
                statusMessage = null
            )
        }
        startDiscovery()
    }

    fun cancelConnectFlow() {
        stopDiscovery()
        viewModelScope.launch {
            val nextStage = if (repository.hasSavedPi()) HomeStage.Connecting else HomeStage.Empty
            _state.update { it.copy(stage = nextStage, pairing = null) }
            if (nextStage == HomeStage.Connecting) bootstrap()
        }
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            try {
                discoverer.discoveredPis().collect { devices ->
                    _state.update { it.copy(discoveredPis = devices) }
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        statusMessage = "Could not start network discovery. Use 'Add by IP address' instead."
                    )
                }
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun updateManualHostInput(value: String) {
        _state.update { it.copy(manualHostInput = value) }
    }

    fun updateManualTokenInput(value: String) {
        _state.update { it.copy(manualTokenInput = value) }
    }

    fun chooseDiscoveredPi(pi: DiscoveredPi) {
        beginPairing(pi.host)
    }

    fun submitManualHost() {
        val host = state.value.manualHostInput.trim()
        if (host.isBlank()) return
        beginPairing(host)
    }

    private fun beginPairing(host: String) {
        stopDiscovery()
        _state.update {
            it.copy(
                pairing = PairingUiState(host = host, busy = true),
                statusMessage = null
            )
        }
        viewModelScope.launch {
            when (val attempt = repository.requestPairingToken(host, clientInstanceId)) {
                is PairingAttempt.Issued -> attemptConnect(host, attempt.token)
                PairingAttempt.NotSupported -> _state.update {
                    it.copy(
                        pairing = PairingUiState(
                            host = host,
                            busy = false,
                            requiresManualToken = true,
                            note = "This Pi does not advertise automatic pairing. Read the pairing code from the Pi setup screen and paste it below."
                        )
                    )
                }
                is PairingAttempt.WindowClosed -> _state.update {
                    it.copy(
                        pairing = PairingUiState(
                            host = host,
                            busy = false,
                            canRetry = true,
                            note = "Pairing is closed on the Pi — open a window on the device, then retry.\n\n${attempt.detail}"
                        )
                    )
                }
                is PairingAttempt.Transient -> _state.update {
                    it.copy(
                        pairing = PairingUiState(
                            host = host,
                            busy = false,
                            canRetry = true,
                            note = "The Pi could not issue a token right now. ${attempt.detail} Try again in a moment."
                        )
                    )
                }
                is PairingAttempt.Failed -> _state.update {
                    it.copy(
                        pairing = PairingUiState(
                            host = host,
                            busy = false,
                            requiresManualToken = true,
                            note = attempt.message
                        )
                    )
                }
            }
        }
    }

    fun retryPairing() {
        val host = state.value.pairing?.host ?: return
        beginPairing(host)
    }

    fun submitManualToken() {
        val pairing = state.value.pairing ?: return
        val token = state.value.manualTokenInput.trim()
        if (token.isBlank()) return
        _state.update { it.copy(pairing = pairing.copy(busy = true)) }
        viewModelScope.launch { attemptConnect(pairing.host, token) }
    }

    fun forgetPi() {
        stopDiscovery()
        viewModelScope.launch {
            repository.forgetPi()
            _state.update {
                HomeUiState(stage = HomeStage.Empty, statusMessage = "Pi forgotten. Pair again when ready.")
            }
        }
    }

    fun refreshStatus() {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch { runOperation { repository.refresh(dashboard) } }
    }

    fun setZoneOn(zoneId: String, on: Boolean) {
        val dashboard = state.value.dashboard ?: return
        val next = if (on) {
            (dashboard.enabledSystemIds + zoneId).distinct()
        } else {
            dashboard.enabledSystemIds.filterNot { it == zoneId }
        }
        viewModelScope.launch { runOperation { repository.setSpeakerSystems(dashboard, next) } }
    }

    fun setAllZones(on: Boolean) {
        val dashboard = state.value.dashboard ?: return
        val next = if (on) dashboard.speakerSystems.map { it.id }.distinct() else emptyList()
        viewModelScope.launch { runOperation { repository.setSpeakerSystems(dashboard, next) } }
    }

    fun reconnectSpeaker(speakerId: String) {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch { runOperation { repository.reconnect(dashboard, speakerId) } }
    }

    fun startAssignSpeaker(speakerId: String, label: String) {
        val dashboard = state.value.dashboard ?: return
        _state.update {
            it.copy(
                assignSpeaker = AssignSpeakerUiState(
                    targetSpeakerId = speakerId,
                    targetLabel = label,
                    phase = AssignPhase.Scanning
                )
            )
        }
        viewModelScope.launch { runBluetoothScanForAssign(dashboard) }
    }

    fun rescanForAssign() {
        val dashboard = state.value.dashboard ?: return
        val assign = state.value.assignSpeaker ?: return
        _state.update {
            it.copy(
                assignSpeaker = assign.copy(
                    phase = AssignPhase.Scanning,
                    devices = emptyList(),
                    selectedAddress = null,
                    message = null
                )
            )
        }
        viewModelScope.launch { runBluetoothScanForAssign(dashboard) }
    }

    fun selectDeviceForAssign(address: String) {
        _state.update { current ->
            val assign = current.assignSpeaker ?: return@update current
            current.copy(assignSpeaker = assign.copy(selectedAddress = address, message = null))
        }
    }

    fun assignSelectedSpeaker() {
        val current = state.value
        val dashboard = current.dashboard ?: return
        val assign = current.assignSpeaker ?: return
        val address = assign.selectedAddress ?: return
        val device = assign.devices.firstOrNull { it.address == address } ?: return
        _state.update {
            it.copy(
                assignSpeaker = assign.copy(phase = AssignPhase.Assigning, message = null),
                isBusy = true
            )
        }
        viewModelScope.launch {
            val result = try {
                repository.assignSpeaker(
                    dashboard = dashboard,
                    speakerId = assign.targetSpeakerId,
                    address = device.address,
                    displayName = device.name
                )
            } catch (exception: Exception) {
                OperationActionResult(exception.message ?: "Assign failed.")
            }
            _state.update { now ->
                if (result.dashboard != null) {
                    now.copy(
                        isBusy = false,
                        dashboard = result.dashboard,
                        assignSpeaker = null,
                        statusMessage = result.message.ifBlank { null }
                    )
                } else {
                    now.copy(
                        isBusy = false,
                        assignSpeaker = assign.copy(
                            phase = AssignPhase.ShowingDevices,
                            message = result.message.ifBlank { "Assign failed." }
                        )
                    )
                }
            }
        }
    }

    fun cancelAssignSpeaker() {
        _state.update { it.copy(assignSpeaker = null) }
    }

    private suspend fun runBluetoothScanForAssign(dashboard: DashboardModel) {
        val result = try {
            repository.scanBluetoothDevices(dashboard)
        } catch (exception: Exception) {
            BluetoothScanResult(exception.message ?: "Bluetooth scan failed.", null)
        }
        _state.update { current ->
            val assign = current.assignSpeaker ?: return@update current
            current.copy(
                assignSpeaker = assign.copy(
                    phase = AssignPhase.ShowingDevices,
                    devices = result.devices.orEmpty(),
                    message = if (result.devices == null) result.message else null
                )
            )
        }
    }

    private suspend fun runOperation(block: suspend () -> OperationActionResult) {
        _state.update { it.copy(isBusy = true, statusMessage = null) }
        val result = try {
            block()
        } catch (exception: Exception) {
            OperationActionResult(exception.message ?: "Operation failed.")
        }
        _state.update {
            it.copy(
                isBusy = false,
                dashboard = result.dashboard ?: it.dashboard,
                statusMessage = result.message.ifBlank { null }
            )
        }
    }

    fun dismissStatusMessage() {
        _state.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        stopDiscovery()
        super.onCleared()
    }
}

enum class HomeStage { Connecting, Connected, Empty, Discovering, ConnectionFailed }

sealed interface ConnectionLabel {
    data object None : ConnectionLabel
    data class Connected(val host: String) : ConnectionLabel
    data class Demo(val host: String) : ConnectionLabel
    data class Degraded(val host: String) : ConnectionLabel
    data object Failed : ConnectionLabel
}

data class HomeUiState(
    val stage: HomeStage = HomeStage.Connecting,
    val savedHost: String? = null,
    val dashboard: DashboardModel? = null,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val discoveredPis: List<DiscoveredPi> = emptyList(),
    val manualHostInput: String = "",
    val manualTokenInput: String = "",
    val pairing: PairingUiState? = null,
    val connectionLabel: ConnectionLabel = ConnectionLabel.None,
    val assignSpeaker: AssignSpeakerUiState? = null
)

enum class AssignPhase { Scanning, ShowingDevices, Assigning }

data class AssignSpeakerUiState(
    val targetSpeakerId: String,
    val targetLabel: String,
    val phase: AssignPhase = AssignPhase.Scanning,
    val devices: List<BluetoothDeviceModel> = emptyList(),
    val selectedAddress: String? = null,
    val message: String? = null
)

data class PairingUiState(
    val host: String,
    val busy: Boolean,
    val requiresManualToken: Boolean = false,
    val canRetry: Boolean = false,
    val note: String? = null
)

class MainViewModelFactory(
    private val repository: PiRepository,
    private val discoverer: PiNetworkDiscoverer,
    private val clientInstanceId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(repository, discoverer, clientInstanceId) as T
    }
}
