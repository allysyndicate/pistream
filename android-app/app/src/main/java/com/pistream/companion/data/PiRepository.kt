package com.pistream.companion.data

import com.pistream.companion.data.dto.OperationRequestDto
import com.pistream.companion.data.dto.OperationDto
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

    fun savedToken(): String = tokenStore.loadToken()

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
            if (dashboard.healthState == "healthy") {
                ConnectionResult.FoundHealthy(dashboard)
            } else {
                ConnectionResult.FoundUnhealthy(dashboard)
            }
        } catch (exception: IOException) {
            ConnectionResult.NotFound
        } catch (exception: Exception) {
            ConnectionResult.ApiUnavailable(host, exception.message ?: "Malformed or unavailable Pi API.")
        }
    }

    suspend fun reconnect(dashboard: DashboardModel, speakerId: String): String {
        val request = dashboard.operationRequest(speakerId = speakerId)
        return when (val result = apiClient.reconnect(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Reconnect", result.value)
            is ApiCallResult.Failure -> "${result.code}: ${result.message}"
        }
    }

    suspend fun runWatchdog(dashboard: DashboardModel): String {
        val request = dashboard.operationRequest()
        return when (val result = apiClient.runWatchdog(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Watchdog", result.value)
            is ApiCallResult.Failure -> "${result.code}: ${result.message}"
        }
    }

    suspend fun restartService(dashboard: DashboardModel, serviceId: String): String {
        val request = dashboard.operationRequest(serviceId = serviceId)
        return when (val result = apiClient.restartService(piBaseUrl(dashboard.host), tokenStore.loadToken(), request)) {
            is ApiCallResult.Success -> operationStateMessage(dashboard, "Restart", result.value)
            is ApiCallResult.Failure -> "${result.code}: ${result.message}"
        }
    }

    private suspend fun operationStateMessage(
        dashboard: DashboardModel,
        label: String,
        accepted: OperationDto
    ): String {
        if (accepted.status !in setOf("queued", "running")) {
            return "$label ${accepted.status}: ${accepted.operationId}"
        }

        return when (val polled = apiClient.operation(piBaseUrl(dashboard.host), tokenStore.loadToken(), accepted.operationId)) {
            is ApiCallResult.Success -> "$label ${polled.value.status}: ${polled.value.operationId}"
            is ApiCallResult.Failure -> "$label pending: ${accepted.operationId}; poll ${polled.code}: ${polled.message}"
        }
    }

    companion object {
        const val DEFAULT_HOST = "audiopi.local"
    }
}

private fun DashboardModel.operationRequest(
    speakerId: String? = null,
    serviceId: String? = null
): OperationRequestDto {
    return OperationRequestDto(
        observedBootId = requireNotNull(observedBootId) { "Missing observedBootId." },
        observedAt = requireNotNull(observedAt) { "Missing observedAt." },
        clientRequestId = UUID.randomUUID().toString(),
        speakerId = speakerId,
        serviceId = serviceId
    )
}
