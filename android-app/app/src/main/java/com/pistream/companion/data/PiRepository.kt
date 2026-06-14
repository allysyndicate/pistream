package com.pistream.companion.data

import com.pistream.companion.data.dto.OperationRequestDto
import com.pistream.companion.data.dto.OperationDto
import com.pistream.companion.data.dto.PairingRequestDto
import com.pistream.companion.data.dto.SetSpeakerSystemsRequestDto
import com.pistream.companion.domain.BluetoothDeviceModel
import com.pistream.companion.domain.ConnectionResult
import com.pistream.companion.domain.DashboardModel
import com.pistream.companion.domain.isCompatible
import com.pistream.companion.domain.matchesSaved
import com.pistream.companion.domain.toDashboard
import com.pistream.companion.domain.toTrustedIdentity
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first

class PiRepository(
    private val apiClient: PiApiClient,
    private val savedPiStore: SavedPiStore,
    private val tokenStore: SecureTokenStore
) {
    suspend fun initialHost(): String = savedPiStore.savedPi.first()?.host ?: DEFAULT_HOST

    suspend fun hasSavedPi(): Boolean = savedPiStore.savedPi.first()?.identity != null

    fun savedToken(): String = tokenStore.loadToken()

    suspend fun forgetPi() {
        tokenStore.clearToken()
        savedPiStore.forget()
    }

    suspend fun requestPairingToken(host: String, clientInstanceId: String): PairingAttempt {
        val baseUrl = piBaseUrl(host)
        return try {
            when (val result = apiClient.requestPairingToken(
                baseUrl,
                PairingRequestDto(
                    clientName = "PiStream Companion",
                    clientInstanceId = clientInstanceId
                )
            )) {
                is ApiCallResult.Success -> PairingAttempt.Issued(result.value.token)
                is ApiCallResult.Failure -> when (result.code) {
                    // 404 = pairing route absent on an older Pi → fall back to manual paste.
                    "not_found" -> PairingAttempt.NotSupported
                    // 403 `pairing_closed` is a distinct outcome — NOT a manual-token fallback.
                    // The server message carries the operator hint (e.g. `pihouse-pair --open 5m`).
                    "pairing_closed" -> PairingAttempt.WindowClosed(result.message)
                    // 503 `token_unavailable` is transient — user should retry, not paste.
                    "token_unavailable", "api_unavailable" ->
                        PairingAttempt.Transient(result.message)
                    else -> PairingAttempt.Failed("${result.code}: ${result.message}")
                }
            }
        } catch (exception: IOException) {
            PairingAttempt.Failed("Could not reach the Pi at $host.")
        } catch (exception: Exception) {
            PairingAttempt.Failed(exception.message ?: "Pairing failed.")
        }
    }

    suspend fun connect(hostInput: String, tokenInput: String): ConnectionResult {
        val host = normalizePiHost(hostInput)
        val baseUrl = piBaseUrl(host)
        val token = tokenInput.trim()
        val savedIdentity = savedPiStore.savedPi.first()?.identity

        return try {
            val identity = when (val result = apiClient.identity(baseUrl)) {
                is ApiCallResult.Success -> result.value
                is ApiCallResult.Failure -> return ConnectionResult.ApiUnavailable(host, result.message)
            }

            if (!identity.isCompatible()) {
                return ConnectionResult.ApiUnavailable(host, "Unsupported Pi API contract or version.")
            }

            if (!identity.matchesSaved(savedIdentity)) {
                return ConnectionResult.WrongDevice(host, identity.deviceId)
            }

            val health = when (val result = apiClient.health(baseUrl)) {
                is ApiCallResult.Success -> result.value
                is ApiCallResult.Failure -> null
            }

            val status = when (val result = apiClient.status(baseUrl, token)) {
                is ApiCallResult.Success -> result.value
                is ApiCallResult.Failure -> {
                    if (result.code == "unauthorized") return ConnectionResult.Unauthorized(host)
                    return ConnectionResult.ApiUnavailable(host, result.message)
                }
            }

            tokenStore.saveToken(token)
            savedPiStore.save(host, identity.toTrustedIdentity())

            val dashboard = status.copy(health = status.health ?: health).toDashboard(host)
            when {
                dashboard.isDemoMode -> ConnectionResult.FoundDemo(dashboard)
                dashboard.healthState == "healthy" -> ConnectionResult.FoundHealthy(dashboard)
                else -> ConnectionResult.FoundUnhealthy(dashboard)
            }
        } catch (exception: IOException) {
            ConnectionResult.NotFound
        } catch (exception: Exception) {
            ConnectionResult.ApiUnavailable(host, exception.message ?: "Malformed or unavailable Pi API.")
        }
    }

    suspend fun refresh(dashboard: DashboardModel): OperationActionResult {
        val baseUrl = piBaseUrl(dashboard.host)
        val savedIdentity = savedPiStore.savedPi.first()?.identity

        // RC-08: a swapped Pi at the same IP/host can hold the saved bearer's hash but
        // serve a different `deviceId`. `/status` alone wouldn't catch that — `/identity`
        // does. Cold restart already verifies this via `connect()`; warm refresh did not.
        if (savedIdentity != null) {
            when (val result = apiClient.identity(baseUrl)) {
                is ApiCallResult.Success -> {
                    if (!result.value.isCompatible()) {
                        return OperationActionResult(
                            message = "Unsupported Pi API contract or version.",
                            dashboard = null,
                            failureCode = "api_unavailable"
                        )
                    }
                    if (!result.value.matchesSaved(savedIdentity)) {
                        return OperationActionResult(
                            message = "Host ${dashboard.host} now reports a different Pi identity.",
                            dashboard = null,
                            failureCode = "wrong_device"
                        )
                    }
                }
                is ApiCallResult.Failure -> return OperationActionResult(
                    message = "${result.code}: ${result.message}",
                    dashboard = null,
                    failureCode = result.code
                )
            }
        }

        return when (val result = apiClient.status(baseUrl, tokenStore.loadToken())) {
            is ApiCallResult.Success -> OperationActionResult(
                message = "Status refreshed.",
                dashboard = result.value.toDashboard(dashboard.host)
            )
            is ApiCallResult.Failure -> OperationActionResult(
                message = "${result.code}: ${result.message}",
                dashboard = null,
                failureCode = result.code
            )
        }
    }

    suspend fun reconnect(dashboard: DashboardModel, speakerId: String): OperationActionResult {
        val request = dashboard.operationRequest(speakerId = speakerId)
        return when (val result = apiClient.reconnect(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Reconnect", result.value)
            is ApiCallResult.Failure -> OperationActionResult("${result.code}: ${result.message}", failureCode = result.code)
        }
    }

    suspend fun runWatchdog(dashboard: DashboardModel): OperationActionResult {
        val request = dashboard.operationRequest()
        return when (val result = apiClient.runWatchdog(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Watchdog", result.value)
            is ApiCallResult.Failure -> OperationActionResult("${result.code}: ${result.message}", failureCode = result.code)
        }
    }

    suspend fun selectRoute(dashboard: DashboardModel, routeId: String): OperationActionResult {
        val request = dashboard.operationRequest(routeId = routeId)
        return when (val result = apiClient.selectRoute(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Route", result.value)
            is ApiCallResult.Failure -> OperationActionResult("${result.code}: ${result.message}", failureCode = result.code)
        }
    }

    suspend fun setSpeakerSystems(dashboard: DashboardModel, enabledSystemIds: List<String>): OperationActionResult {
        val normalizedIds = enabledSystemIds.distinct()
        val baseUrl = piBaseUrl(dashboard.host)
        val token = tokenStore.loadToken()

        if (dashboard.canRunOperation("set_speaker_systems")) {
            val request = dashboard.setSpeakerSystemsRequest(normalizedIds)
            return when (val result = apiClient.setSpeakerSystems(baseUrl, token, request)) {
                is ApiCallResult.Success -> operationStateMessage(dashboard, "Speaker systems", result.value)
                is ApiCallResult.Failure -> OperationActionResult("${result.code}: ${result.message}", failureCode = result.code)
            }
        }

        val legacyRoute = normalizedIds.toLegacyRouteId()
            ?: return OperationActionResult("All-off requires the set_speaker_systems operation on the Pi.")
        if (!dashboard.canRunOperation("select_route")) {
            return OperationActionResult("Speaker system controls are unavailable on this Pi status snapshot.")
        }

        val request = dashboard.operationRequest(routeId = legacyRoute)
        return when (val result = apiClient.selectRoute(baseUrl, token, request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Route", result.value)
            is ApiCallResult.Failure -> OperationActionResult("${result.code}: ${result.message}", failureCode = result.code)
        }
    }

    suspend fun scanBluetoothDevices(dashboard: DashboardModel, scanSeconds: Int = 8): BluetoothScanResult {
        return when (val result = apiClient.bluetoothDevices(piBaseUrl(dashboard.host), tokenStore.loadToken(), scanSeconds)) {
            is ApiCallResult.Success -> BluetoothScanResult(
                message = "Found ${result.value.devices.size} Bluetooth device(s) visible to the Pi.",
                devices = result.value.devices.map {
                    BluetoothDeviceModel(
                        address = it.address,
                        name = it.name,
                        paired = it.paired,
                        trusted = it.trusted,
                        connected = it.connected
                    )
                }
            )
            is ApiCallResult.Failure -> BluetoothScanResult(
                message = "${result.code}: ${result.message}",
                devices = null
            )
        }
    }

    suspend fun assignSpeaker(
        dashboard: DashboardModel,
        speakerId: String,
        address: String,
        displayName: String?
    ): OperationActionResult {
        val request = dashboard.operationRequest(
            speakerId = speakerId,
            address = address,
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
        )
        return when (val result = apiClient.pairSpeaker(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> {
                // Pair-speaker is synchronous: the first response already carries terminal status.
                // No polling — GET /operations/{id} is retry-safety only per the locked contract.
                val op = result.value
                if (op.status == "succeeded") {
                    val refreshed = refresh(dashboard)
                    refreshed.copy(
                        message = listOf("Pair succeeded: ${op.operationId}", refreshed.message)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                    )
                } else {
                    // 200 OK with status=failed means the Pi attempted the bond/connect chain and
                    // it failed. The real wire code lives in op.error.code (set by pi-api
                    // operations.py when an AdapterCommandError is raised); fall back to
                    // bluetooth_pair_failed only if the envelope is unexpectedly empty.
                    OperationActionResult(
                        message = op.error?.message?.takeIf { it.isNotBlank() }
                            ?: op.message?.takeIf { it.isNotBlank() }
                            ?: "Pair failed (${op.operationId}).",
                        failureCode = op.error?.code ?: "bluetooth_pair_failed"
                    )
                }
            }
            is ApiCallResult.Failure -> OperationActionResult(
                message = result.message,
                failureCode = result.code
            )
        }
    }

    suspend fun restartService(dashboard: DashboardModel, serviceId: String): OperationActionResult {
        val request = dashboard.operationRequest(serviceId = serviceId)
        return when (val result = apiClient.restartService(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Restart", result.value)
            is ApiCallResult.Failure -> OperationActionResult("${result.code}: ${result.message}", failureCode = result.code)
        }
    }

    private suspend fun operationStateMessage(
        dashboard: DashboardModel,
        label: String,
        accepted: OperationDto
    ): OperationActionResult {
        val message = if (accepted.status !in setOf("queued", "running")) {
            "$label ${accepted.status}: ${accepted.operationId}"
        } else {
            when (val polled = apiClient.operation(piBaseUrl(dashboard.host), tokenStore.loadToken(), accepted.operationId)) {
                is ApiCallResult.Success -> "$label ${polled.value.status}: ${polled.value.operationId}"
                is ApiCallResult.Failure -> "$label pending: ${accepted.operationId}; poll ${polled.code}: ${polled.message}"
            }
        }

        val refreshed = refresh(dashboard)
        return refreshed.copy(message = "$message\n${refreshed.message}")
    }

    companion object {
        const val DEFAULT_HOST = "audiopi.local"
    }
}

data class OperationActionResult(
    val message: String,
    val dashboard: DashboardModel? = null,
    val failureCode: String? = null
)

data class BluetoothScanResult(
    val message: String,
    val devices: List<BluetoothDeviceModel>?
)

sealed interface PairingAttempt {
    data class Issued(val token: String) : PairingAttempt
    data object NotSupported : PairingAttempt
    data class WindowClosed(val detail: String) : PairingAttempt
    data class Transient(val detail: String) : PairingAttempt
    data class Failed(val message: String) : PairingAttempt
}

private fun DashboardModel.operationRequest(
    speakerId: String? = null,
    serviceId: String? = null,
    routeId: String? = null,
    address: String? = null,
    displayName: String? = null
): OperationRequestDto {
    return OperationRequestDto(
        observedBootId = requireNotNull(observedBootId) { "Missing observedBootId." },
        observedAt = requireNotNull(observedAt) { "Missing observedAt." },
        clientRequestId = UUID.randomUUID().toString(),
        speakerId = speakerId,
        serviceId = serviceId,
        routeId = routeId,
        address = address,
        displayName = displayName
    )
}

private fun DashboardModel.setSpeakerSystemsRequest(enabledSystemIds: List<String>): SetSpeakerSystemsRequestDto {
    return SetSpeakerSystemsRequestDto(
        observedBootId = requireNotNull(observedBootId) { "Missing observedBootId." },
        observedAt = requireNotNull(observedAt) { "Missing observedAt." },
        clientRequestId = UUID.randomUUID().toString(),
        enabledSystemIds = enabledSystemIds
    )
}

private fun List<String>.toLegacyRouteId(): String? {
    val ids = toSet()
    return when (ids) {
        setOf("indoor") -> "indoor"
        setOf("outdoor") -> "outdoor"
        setOf("indoor", "outdoor") -> "both"
        else -> null
    }
}
