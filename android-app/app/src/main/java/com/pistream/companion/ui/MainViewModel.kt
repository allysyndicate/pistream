package com.pistream.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pistream.companion.data.PiRepository
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
                        connectionLabel = "found_healthy",
                        dashboard = result.dashboard,
                        message = null
                    )
                    is ConnectionResult.FoundUnhealthy -> it.copy(
                        isLoading = false,
                        connectionLabel = "found_unhealthy",
                        dashboard = result.dashboard,
                        message = "Pi is reachable but reports degraded status."
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

    fun reconnect() {
        val dashboard = state.value.dashboard ?: return
        viewModelScope.launch {
            runOperation {
                val speaker = dashboard.speakers.firstOrNull { it.state != "healthy" }?.id
                    ?: dashboard.speakers.firstOrNull()?.id
                    ?: "outdoor"
                repository.reconnect(dashboard, speaker)
            }
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

    private suspend fun runOperation(block: suspend () -> String) {
        _state.update { it.copy(isLoading = true, operationMessage = null) }
        val message = try {
            block()
        } catch (exception: Exception) {
            exception.message ?: "Operation failed."
        }
        _state.update { it.copy(isLoading = false, operationMessage = message) }
    }
}

data class PiUiState(
    val host: String = "audiopi.local",
    val token: String = "",
    val isLoading: Boolean = false,
    val connectionLabel: String = "unpaired",
    val message: String? = null,
    val operationMessage: String? = null,
    val dashboard: DashboardModel? = null
)

class MainViewModelFactory(
    private val repository: PiRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(repository) as T
    }
}
