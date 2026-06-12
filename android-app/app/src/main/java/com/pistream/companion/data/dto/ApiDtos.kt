package com.pistream.companion.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiEnvelope<T>(
    val ok: Boolean,
    val observedAt: String? = null,
    val error: ApiErrorDto? = null,
    val identity: T? = null,
    val health: T? = null,
    val status: T? = null,
    val operation: T? = null
)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val details: JsonObject? = null
)

@Serializable
data class IdentityDto(
    val apiName: String,
    val deviceId: String,
    val controllerInstanceId: String,
    val hostname: String? = null,
    val apiVersion: String,
    val contractVersion: String
)

@Serializable
data class HealthDto(
    val state: String? = null,
    val phase: String? = null,
    @SerialName("reasons")
    val reasonCodes: List<String> = emptyList()
)

@Serializable
data class StatusDto(
    val ok: Boolean = true,
    val identity: IdentityDto,
    val health: HealthDto? = null,
    val reboot: RebootDto? = null,
    val observedAt: String? = null,
    val services: List<ServiceDto> = emptyList(),
    val speakers: List<SpeakerDto> = emptyList(),
    val sinks: List<SinkDto> = emptyList(),
    val spotifyEndpoints: List<SpotifyEndpointDto> = emptyList(),
    val spotify: SpotifyDto? = null,
    val watchdog: WatchdogDto? = null,
    val operations: OperationsDto? = null,
    val controls: ControlsDto? = null
)

@Serializable
data class SpeakerDto(
    @SerialName("speakerId")
    val id: String,
    val displayName: String? = null,
    @SerialName("componentState")
    val state: String? = null,
    val connected: Boolean? = null,
    val reasonCodes: List<String> = emptyList()
)

@Serializable
data class SinkDto(
    @SerialName("sinkId")
    val id: String,
    val displayName: String? = null,
    @SerialName("componentState")
    val state: String? = null,
    val reasonCodes: List<String> = emptyList()
)

@Serializable
data class SpotifyEndpointDto(
    @SerialName("endpointId")
    val id: String,
    val displayName: String? = null,
    val componentState: String? = null,
    val serviceId: String? = null,
    val reasonCodes: List<String> = emptyList()
)

@Serializable
data class SpotifyDto(
    val integrationMode: String? = null,
    val connectOwnedBy: String? = null,
    val accountState: String? = null,
    val activeDevice: SpotifyActiveDeviceDto? = null,
    val playback: SpotifyPlaybackDto? = null,
    val recommendedAction: String? = null,
    val routeReadiness: List<RouteReadinessDto> = emptyList()
)

@Serializable
data class SpotifyActiveDeviceDto(
    val category: String? = null,
    val displayName: String? = null,
    val isExpectedPiEndpoint: Boolean? = null
)

@Serializable
data class SpotifyPlaybackDto(
    val state: String? = null
)

@Serializable
data class RouteReadinessDto(
    val endpointId: String,
    val ready: Boolean,
    val reasonCodes: List<String> = emptyList()
)

@Serializable
data class WatchdogDto(
    val serviceId: String? = null,
    @SerialName("componentState")
    val state: String? = null,
    val reasonCodes: List<String> = emptyList()
)

@Serializable
data class ControlsDto(
    val restartServiceMode: String? = null,
    @SerialName("supportedOperations")
    val supportedActions: List<String> = emptyList()
)

@Serializable
data class RebootDto(
    val bootId: String? = null,
    val bootTime: String? = null,
    val uptimeSeconds: Long? = null
)

@Serializable
data class ServiceDto(
    val serviceId: String,
    val displayName: String? = null,
    val activeState: String? = null,
    val componentState: String? = null,
    val reasonCodes: List<String> = emptyList()
)

@Serializable
data class OperationsDto(
    val active: OperationSummaryDto? = null,
    val recent: List<OperationSummaryDto> = emptyList()
)

@Serializable
data class OperationSummaryDto(
    val operationId: String,
    val type: String,
    val status: String
)

@Serializable
data class OperationRequestDto(
    val observedBootId: String,
    val observedAt: String,
    val clientRequestId: String,
    val speakerId: String? = null,
    val serviceId: String? = null
)

@Serializable
data class OperationDto(
    val operationId: String,
    val type: String,
    val status: String,
    val pollAfterMs: Long? = null,
    val message: String? = null,
    @SerialName("restartIssued")
    val restartIssued: Boolean? = null
)
