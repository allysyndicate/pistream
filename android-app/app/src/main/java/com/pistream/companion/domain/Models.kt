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
    val healthReasons: List<String>,
    val observedBootId: String?,
    val observedAt: String?,
    val speakers: List<ComponentRow>,
    val speakerSystems: List<SpeakerSystemRow>,
    val sinks: List<ComponentRow>,
    val spotifyEndpoints: List<ComponentRow>,
    val routes: List<ComponentRow>,
    val selectedRouteId: String?,
    val activeRouteId: String?,
    val enabledSystemIds: List<String>,
    val activeSystemIds: List<String>,
    val audioOutputLastChangedAt: String?,
    val audioOutputAdapterMode: String?,
    val hasSpeakerSystemStatus: Boolean,
    val hasAudioOutputStatus: Boolean,
    val hasSpeakerConnectionStatus: Boolean,
    val spotify: SpotifySummary,
    val watchdog: ComponentRow?,
    val operations: List<OperationRow>,
    val supportedOperations: List<String>
) {
    val canRunOperations: Boolean = !observedBootId.isNullOrBlank() && !observedAt.isNullOrBlank()

    val isDemoMode: Boolean = audioOutputAdapterMode == "stub"

    fun canRunOperation(operation: String): Boolean {
        val requested = operation.normalizedOperationName()
        return canRunOperations && supportedOperations.any { it.normalizedOperationName() == requested }
    }
}

data class ComponentRow(
    val id: String,
    val label: String,
    val state: String,
    val reasonCodes: List<String>,
    val detail: String? = null
)

data class SpeakerSystemRow(
    val id: String,
    val label: String,
    val sinkId: String?,
    val enabled: Boolean,
    val active: Boolean,
    val readiness: String,
    val reasonCodes: List<String>,
    val statusReported: Boolean
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

data class BluetoothDeviceModel(
    val address: String,
    val name: String?,
    val paired: Boolean,
    val trusted: Boolean,
    val connected: Boolean
) {
    val label: String = name ?: address
}

data class DiscoveredPi(
    val serviceName: String,
    val host: String,
    val port: Int,
    val deviceId: String?,
    val contractVersion: String?,
    val pairingOpen: Boolean
) {
    val label: String = if (serviceName.isNotBlank()) serviceName else host
    val authority: String = "$host:$port"
}

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
    val hasAudioOutputStatus = audioOutput != null
    val hasSpeakerSystemStatus = speakerSystems.isNotEmpty()
    val hasSpeakerConnectionStatus = speakers.any { it.connected != null || it.state != null }
    val enabledSystemIds = audioOutput?.enabledSystemIds ?: audioRoute?.selectedRouteId.toEnabledSystemIds()
    val activeSystemIds = audioOutput?.activeSystemIds ?: audioRoute?.activeRouteId.toEnabledSystemIds()
    val systemRows = speakerSystems.map {
        SpeakerSystemRow(
            id = it.systemId,
            label = it.displayName ?: it.systemId.replaceFirstChar { char -> char.uppercase() },
            sinkId = it.sinkId,
            enabled = it.enabled,
            active = it.active,
            readiness = it.readiness ?: when (it.ready) {
                true -> "ready"
                false -> "not_ready"
                null -> "unknown"
            },
            reasonCodes = it.reasonCodes,
            statusReported = true
        )
    }.ifEmpty {
        listOf(
            unreportedSpeakerSystem("indoor", "Indoor", enabledSystemIds, activeSystemIds),
            unreportedSpeakerSystem("outdoor", "Outdoor", enabledSystemIds, activeSystemIds)
        )
    }

    return DashboardModel(
        host = host,
        identity = identity.toTrustedIdentity(),
        healthState = healthDto.state ?: "unknown",
        phase = healthDto.phase,
        healthReasons = healthDto.reasonCodes,
        observedBootId = reboot?.bootId,
        observedAt = observedAt,
        speakers = speakers.map {
            ComponentRow(
                id = it.id,
                label = it.displayName ?: it.id,
                state = it.state ?: when (it.connected) {
                    true -> "connected"
                    false -> "disconnected"
                    null -> "unknown"
                },
                reasonCodes = it.reasonCodes,
                detail = listOfNotNull(
                    when (it.connected) {
                        true -> "Connected"
                        false -> "Disconnected"
                        null -> "Connection unknown"
                    },
                    it.address?.let { address -> "Device $address" }
                ).joinToString(" | ")
            )
        },
        speakerSystems = systemRows,
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
                state = it.componentState ?: it.activeState ?: "unknown",
                reasonCodes = it.reasonCodes,
                detail = it.serviceId
            )
        },
        routes = routes.map {
            ComponentRow(
                id = it.routeId,
                label = it.displayName ?: it.routeId,
                state = when {
                    it.active -> "active"
                    it.selected -> "selected"
                    it.ready -> "ready"
                    else -> "not_ready"
                },
                reasonCodes = it.reasonCodes,
                detail = if (it.selected || it.active) {
                    listOfNotNull(
                        if (it.selected) "Selected" else null,
                        if (it.active) "Active" else null
                    ).joinToString()
                } else {
                    null
                }
            )
        },
        selectedRouteId = audioRoute?.selectedRouteId,
        activeRouteId = audioRoute?.activeRouteId,
        enabledSystemIds = enabledSystemIds,
        activeSystemIds = activeSystemIds,
        audioOutputLastChangedAt = audioOutput?.lastChangedAt ?: audioRoute?.lastChangedAt,
        audioOutputAdapterMode = audioOutput?.adapterMode,
        hasSpeakerSystemStatus = hasSpeakerSystemStatus,
        hasAudioOutputStatus = hasAudioOutputStatus,
        hasSpeakerConnectionStatus = hasSpeakerConnectionStatus,
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
        },
        supportedOperations = controls?.supportedActions.orEmpty()
    )
}

private fun String.normalizedOperationName(): String = replace("-", "_")

private fun String?.toEnabledSystemIds(): List<String> = when (this) {
    "indoor" -> listOf("indoor")
    "outdoor" -> listOf("outdoor")
    "both", "whole_house" -> listOf("indoor", "outdoor")
    else -> emptyList()
}

private fun unreportedSpeakerSystem(
    id: String,
    label: String,
    enabledSystemIds: List<String>,
    activeSystemIds: List<String>
): SpeakerSystemRow {
    return SpeakerSystemRow(
        id = id,
        label = label,
        sinkId = null,
        enabled = enabledSystemIds.contains(id),
        active = activeSystemIds.contains(id),
        readiness = "unreported",
        reasonCodes = listOf("speaker_system_status_missing"),
        statusReported = false
    )
}
