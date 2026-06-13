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
import kotlinx.coroutines.delay
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
    private var resumeJob: Job? = null
    private var postConnectSettleJob: Job? = null

    // Bounded retry policy for saved-token reconnect. Tuned for Wi-Fi handoff after a
    // screen-off / wake (~1-2s for DHCP) and short Pi API restarts (~3-4s for uvicorn
    // to come back). ~5.7s total budget keeps the user from staring at a spinner.
    private val reconnectBackoffMillis = longArrayOf(700L, 1_500L, 3_500L)

    // After a successful reconnect, re-poll status once or twice with short backoff so a
    // transient `degraded` health reading (audio-service starting up post-resume) doesn't
    // freeze on the dashboard as a permanent banner.
    private val postConnectSettleBackoffMillis = longArrayOf(1_500L, 4_000L)

    // Skip a resume-triggered reconnect if we just finished one — Compose can drive several
    // ON_RESUME callbacks in rapid succession during recreate, and we don't want to thrash
    // the network.
    private var lastSuccessfulConnectAtMs: Long = 0L
    private val resumeRefreshDebounceMs: Long = 3_000L

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
        // Preserve the on-screen panel when we already have a dashboard or are on the
        // ConnectionFailed card — flipping to a generic "Connecting..." is what users
        // perceive as a reconnect "glitch". Show a small reconnecting indicator instead.
        val previous = _state.value
        val keepStage = previous.dashboard != null || previous.stage == HomeStage.ConnectionFailed
        _state.update {
            it.copy(
                stage = if (keepStage) it.stage else HomeStage.Connecting,
                connectionLabel = if (keepStage) it.connectionLabel else ConnectionLabel.None,
                isReconnecting = true,
                savedHost = host,
                statusMessage = if (keepStage) it.statusMessage else null
            )
        }
        var result = repository.connect(host, token)
        // Wi-Fi wake/roam and Pi API restarts surface as IOException → NotFound or
        // ApiUnavailable. Retry with bounded backoff before tossing the user to Failed.
        // Auth/identity outcomes are deterministic — don't retry those.
        for (delayMs in reconnectBackoffMillis) {
            if (!result.isRetryable()) break
            delay(delayMs)
            result = repository.connect(host, token)
        }
        applyConnectionResult(host, result)
    }

    private fun ConnectionResult.isRetryable(): Boolean = when (this) {
        ConnectionResult.NotFound,
        is ConnectionResult.ApiUnavailable -> true
        is ConnectionResult.FoundHealthy,
        is ConnectionResult.FoundDemo,
        is ConnectionResult.FoundUnhealthy,
        is ConnectionResult.WrongDevice,
        is ConnectionResult.Unauthorized -> false
    }

    private fun applyConnectionResult(host: String, result: ConnectionResult) {
        // Any terminal connection outcome cancels a pending settle pass; we'll start a
        // new one for healthy/demo/unhealthy cases below.
        postConnectSettleJob?.cancel()
        postConnectSettleJob = null

        if (result is ConnectionResult.FoundHealthy ||
            result is ConnectionResult.FoundDemo ||
            result is ConnectionResult.FoundUnhealthy
        ) {
            lastSuccessfulConnectAtMs = System.currentTimeMillis()
        }

        _state.update { current ->
            when (result) {
                is ConnectionResult.FoundHealthy -> current.copy(
                    stage = HomeStage.Connected,
                    connectionLabel = ConnectionLabel.Connected(host),
                    savedHost = host,
                    dashboard = result.dashboard,
                    isReconnecting = false,
                    statusMessage = null
                )
                is ConnectionResult.FoundDemo -> current.copy(
                    stage = HomeStage.Connected,
                    connectionLabel = ConnectionLabel.Demo(host),
                    savedHost = host,
                    dashboard = result.dashboard,
                    isReconnecting = false,
                    statusMessage = null
                )
                is ConnectionResult.FoundUnhealthy -> current.copy(
                    stage = HomeStage.Connected,
                    connectionLabel = ConnectionLabel.Degraded(host),
                    savedHost = host,
                    dashboard = result.dashboard,
                    isReconnecting = false,
                    statusMessage = "Pi is reachable but reports degraded health."
                )
                is ConnectionResult.WrongDevice -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    isReconnecting = false,
                    statusMessage = "Host $host now reports a different Pi identity. Forget this Pi to pair the new one."
                )
                is ConnectionResult.Unauthorized -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    isReconnecting = false,
                    statusMessage = "Pi rejected the saved pairing token. Forget this Pi and pair again."
                )
                is ConnectionResult.ApiUnavailable -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    isReconnecting = false,
                    statusMessage = "Could not reach $host: ${result.cause}"
                )
                ConnectionResult.NotFound -> current.copy(
                    stage = HomeStage.ConnectionFailed,
                    connectionLabel = ConnectionLabel.Failed,
                    savedHost = host,
                    isReconnecting = false,
                    statusMessage = "Could not reach $host on this network. Make sure the Pi is online and on the same Wi-Fi."
                )
            }
        }

        when (result) {
            is ConnectionResult.FoundHealthy,
            is ConnectionResult.FoundDemo,
            is ConnectionResult.FoundUnhealthy -> startPostConnectSettle()
            else -> Unit
        }
    }

    private fun startPostConnectSettle() {
        postConnectSettleJob?.cancel()
        postConnectSettleJob = viewModelScope.launch {
            for (delayMs in postConnectSettleBackoffMillis) {
                delay(delayMs)
                val dashboard = state.value.dashboard ?: return@launch
                val refreshed = try {
                    repository.refresh(dashboard)
                } catch (_: Exception) {
                    return@launch
                }
                val next = refreshed.dashboard ?: continue
                _state.update { current ->
                    val staleMessage = current.statusMessage == "Pi is reachable but reports degraded health."
                    val label = current.connectionLabel
                    val nextLabel = when {
                        label is ConnectionLabel.Degraded && next.healthState == "healthy" ->
                            ConnectionLabel.Connected(label.host)
                        else -> label
                    }
                    current.copy(
                        dashboard = next,
                        connectionLabel = nextLabel,
                        statusMessage = if (staleMessage && next.healthState == "healthy") null else current.statusMessage
                    )
                }
            }
        }
    }

    fun onAppResumed() {
        // RC-02: Compose lifecycle ON_RESUME hook. Re-validate the saved Pi without
        // blanking the dashboard or showing the Failed card unless the bounded retry
        // policy actually fails. We debounce so a quick recreate (orientation change,
        // theme switch) doesn't immediately bombard the Pi.
        val now = System.currentTimeMillis()
        if (now - lastSuccessfulConnectAtMs < resumeRefreshDebounceMs) return
        if (state.value.isReconnecting) return
        val stage = state.value.stage
        // Only auto-reconnect when we're on a saved-Pi panel. Pairing flows, the empty
        // splash, and the Discovery sheet drive their own network traffic and shouldn't
        // be interrupted.
        if (stage != HomeStage.Connected && stage != HomeStage.ConnectionFailed) return
        val host = state.value.savedHost ?: return
        val token = repository.savedToken()
        if (token.isBlank()) return
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch { attemptConnect(host, token) }
    }

    fun retrySavedConnection() {
        if (state.value.isReconnecting) return
        val host = state.value.savedHost ?: return
        val token = repository.savedToken()
        viewModelScope.launch {
            if (token.isBlank()) {
                _state.update {
                    it.copy(
                        stage = HomeStage.ConnectionFailed,
                        isReconnecting = false,
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
            // If a dashboard is already on screen, return to it directly — re-bootstrapping
            // would tear the dashboard down to flash "Connecting..." for no reason.
            if (state.value.dashboard != null) {
                _state.update { it.copy(stage = HomeStage.Connected, pairing = null) }
                return@launch
            }
            val hasSaved = repository.hasSavedPi()
            _state.update {
                it.copy(
                    stage = if (hasSaved) HomeStage.Connecting else HomeStage.Empty,
                    pairing = null
                )
            }
            if (hasSaved) bootstrap()
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
        resumeJob?.cancel()
        postConnectSettleJob?.cancel()
        lastSuccessfulConnectAtMs = 0L
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
        when (result.failureCode) {
            // A 401 during refresh or any other operation means the saved token is stale —
            // demote to ConnectionFailed instead of silently painting an error banner over
            // a screen that still claims it's Connected.
            "unauthorized" -> {
                _state.update {
                    it.copy(
                        isBusy = false,
                        isReconnecting = false,
                        stage = HomeStage.ConnectionFailed,
                        connectionLabel = ConnectionLabel.Failed,
                        statusMessage = "Pi rejected the saved pairing token. Forget this Pi and pair again."
                    )
                }
                return
            }
            // RC-08: the warm-refresh identity check caught a swapped Pi at the same host.
            "wrong_device" -> {
                _state.update {
                    it.copy(
                        isBusy = false,
                        isReconnecting = false,
                        stage = HomeStage.ConnectionFailed,
                        connectionLabel = ConnectionLabel.Failed,
                        statusMessage = result.message.ifBlank {
                            "This host now reports a different Pi identity. Forget this Pi to pair the new one."
                        }
                    )
                }
                return
            }
            // The Pi rebooted between observation and the operation. Auto-refresh status so
            // the user can retry without reading a cryptic 409.
            "boot_changed" -> {
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Pi rebooted — refreshing status."
                    )
                }
                refreshStatus()
                return
            }
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
        resumeJob?.cancel()
        postConnectSettleJob?.cancel()
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
    val isReconnecting: Boolean = false,
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
