package com.pistream.companion.domain

import com.pistream.companion.data.dto.HealthDto
import com.pistream.companion.data.dto.IdentityDto
import com.pistream.companion.data.dto.StatusDto

const val EXPECTED_API_NAME = "pihouse-audio-api"
const val EXPECTED_CONTRACT_VERSION = "2026-06-phase3"

data class TrustedIdentity(
    val apiName: String,
    val contractVersion: String,
    val deviceId: String,
    val controllerInstanceId: String
)

data class DashboardModel(
    val host: String,
    val identity: TrustedIdentity,
    val healthState: String,
    val phase: String?,
    val observedBootId: String?,
    val observedAt: String?,
    val speakers: List<ComponentRow>,
    val sinks: List<ComponentRow>,
    val spotifyEndpoints: List<ComponentRow>,
    val spotify: SpotifySummary,
    val watchdog: ComponentRow?,
    val operations: List<OperationRow>
) {
    val canRunOperations: Boolean = !observedBootId.isNullOrBlank() && !observedAt.isNullOrBlank()
}

data class ComponentRow(
    val id: String,
    val label: String,
    val state: String,
    val reasonCodes: List<String>
)

data class SpotifySummary(
    val accountState: String,
    val activeDevice: String,
    val playbackState: String,
    val recommendedAction: String,
    val routeReadiness: List<ComponentRow>
)

data class OperationRow(
    val operationId: String,
    val type: String,
    val status: String
)

sealed interface ConnectionResult {
    data class FoundHealthy(val dashboard: DashboardModel) : ConnectionResult
    data class FoundUnhealthy(val dashboard: DashboardModel) : ConnectionResult
    data class WrongDevice(val host: String, val summary: String?) : ConnectionResult
    data class ApiUnavailable(val host: String, val cause: String) : ConnectionResult
    data class Unauthorized(val host: String) : ConnectionResult
    data object NotFound : ConnectionResult
}

fun IdentityDto.toTrustedIdentity() = TrustedIdentity(
    apiName = apiName,
    contractVersion = contractVersion,
    deviceId = deviceId,
    controllerInstanceId = controllerInstanceId
)

fun IdentityDto.isCompatible(): Boolean {
    val majorVersion = apiVersion.substringBefore(".").toIntOrNull()
    return apiName == EXPECTED_API_NAME &&
        contractVersion == EXPECTED_CONTRACT_VERSION &&
        majorVersion == 1 &&
        deviceId.isNotBlank() &&
        controllerInstanceId.isNotBlank()
}

fun IdentityDto.matchesSaved(saved: TrustedIdentity?): Boolean {
    if (saved == null) return true
    return apiName == saved.apiName &&
        contractVersion == saved.contractVersion &&
        deviceId == saved.deviceId &&
        controllerInstanceId == saved.controllerInstanceId
}

fun StatusDto.toDashboard(host: String): DashboardModel {
    val healthDto: HealthDto = health ?: HealthDto()
    return DashboardModel(
        host = host,
        identity = identity.toTrustedIdentity(),
        healthState = healthDto.state ?: "unknown",
        phase = healthDto.phase,
        observedBootId = reboot?.bootId,
        observedAt = observedAt,
        speakers = speakers.map {
            ComponentRow(
                id = it.id,
                label = it.displayName ?: it.id,
                state = it.state ?: if (it.connected == true) "healthy" else "unknown",
                reasonCodes = it.reasonCodes
            )
        },
        sinks = sinks.map {
            ComponentRow(
                id = it.id,
                label = it.displayName ?: it.id,
                state = it.state ?: "unknown",
                reasonCodes = it.reasonCodes
            )
        },
        spotifyEndpoints = spotifyEndpoints.map {
            ComponentRow(
                id = it.id,
                label = it.displayName ?: it.id,
                state = it.componentState ?: "unknown",
                reasonCodes = it.reasonCodes
            )
        },
        spotify = SpotifySummary(
            accountState = spotify?.accountState ?: "unknown",
            activeDevice = spotify?.activeDevice?.category ?: "unknown",
            playbackState = spotify?.playback?.state ?: "unknown",
            recommendedAction = spotify?.recommendedAction ?: "none",
            routeReadiness = spotify?.routeReadiness.orEmpty().map {
                ComponentRow(
                    id = it.endpointId,
                    label = it.endpointId,
                    state = if (it.ready) "ready" else "not_ready",
                    reasonCodes = it.reasonCodes
                )
            }
        ),
        watchdog = watchdog?.let {
            ComponentRow(
                id = it.serviceId ?: "bt_watchdog",
                label = "Bluetooth watchdog",
                state = it.state ?: "unknown",
                reasonCodes = it.reasonCodes
            )
        },
        operations = buildList {
            operations?.active?.let { add(it) }
            addAll(operations?.recent.orEmpty())
        }.map {
            OperationRow(
                operationId = it.operationId,
                type = it.type,
                status = it.status
            )
        }
    )
}
