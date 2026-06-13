package com.pistream.companion.data

import com.pistream.companion.data.dto.ApiEnvelope
import com.pistream.companion.data.dto.BluetoothDevicesDto
import com.pistream.companion.data.dto.HealthDto
import com.pistream.companion.data.dto.IdentityDto
import com.pistream.companion.data.dto.OperationDto
import com.pistream.companion.data.dto.OperationRequestDto
import com.pistream.companion.data.dto.PairingRequestDto
import com.pistream.companion.data.dto.PairingResponseDto
import com.pistream.companion.data.dto.SetSpeakerSystemsRequestDto
import com.pistream.companion.data.dto.StatusDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class PiApiClient {
    private val serializerJson = Json {
        ignoreUnknownKeys = true
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(serializerJson)
        }
        install(HttpTimeout) {
            // Pi-side Bluetooth scans and pair/connect attempts block the request.
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }

    suspend fun identity(baseUrl: String): ApiCallResult<IdentityDto> {
        val response = client.get("$baseUrl/identity")
        if (response.status != HttpStatusCode.OK) return response.status.toApiError()
        return ApiCallResult.Success(response.body())
    }

    suspend fun health(baseUrl: String): ApiCallResult<HealthDto> {
        val response = client.get("$baseUrl/health")
        if (!response.status.isSuccess()) return response.status.toApiError()
        return ApiCallResult.Success(response.body())
    }

    suspend fun status(baseUrl: String, token: String): ApiCallResult<StatusDto> {
        val response = client.get("$baseUrl/status") {
            bearerAuth(token)
        }
        if (!response.status.isSuccess()) return response.status.toApiError()
        return ApiCallResult.Success(response.body())
    }

    suspend fun requestPairingToken(
        baseUrl: String,
        body: PairingRequestDto
    ): ApiCallResult<PairingResponseDto> {
        val response = client.post("$baseUrl/pairing/request-token") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.isSuccess()) return ApiCallResult.Success(response.body())
        // Pairing errors carry actionable detail (e.g. the 403 `pairing_closed` operator hint).
        // Prefer the body-supplied code/message over the canned status-code label.
        val envelope = runCatching { response.body<ApiEnvelope<PairingResponseDto>>() }.getOrNull()
        val error = envelope?.error
        return if (error != null) {
            ApiCallResult.Failure(error.code, error.message)
        } else {
            response.status.toApiError()
        }
    }

    suspend fun reconnect(baseUrl: String, token: String, body: OperationRequestDto): ApiCallResult<OperationDto> {
        return postOperation("$baseUrl/operations/reconnect", token, body)
    }

    suspend fun restartService(baseUrl: String, token: String, body: OperationRequestDto): ApiCallResult<OperationDto> {
        return postOperation("$baseUrl/operations/restart-service", token, body)
    }

    suspend fun runWatchdog(baseUrl: String, token: String, body: OperationRequestDto): ApiCallResult<OperationDto> {
        return postOperation("$baseUrl/operations/run-watchdog", token, body)
    }

    suspend fun selectRoute(baseUrl: String, token: String, body: OperationRequestDto): ApiCallResult<OperationDto> {
        return postOperation("$baseUrl/operations/select-route", token, body)
    }

    suspend fun setSpeakerSystems(
        baseUrl: String,
        token: String,
        body: SetSpeakerSystemsRequestDto
    ): ApiCallResult<OperationDto> {
        return postOperation("$baseUrl/operations/set-speaker-systems", token, body)
    }

    suspend fun bluetoothDevices(baseUrl: String, token: String, scanSeconds: Int): ApiCallResult<BluetoothDevicesDto> {
        val response = client.get("$baseUrl/bluetooth/devices?scanSeconds=$scanSeconds") {
            bearerAuth(token)
        }
        if (!response.status.isSuccess()) return response.status.toApiError()
        return ApiCallResult.Success(response.body())
    }

    suspend fun pairSpeaker(baseUrl: String, token: String, body: OperationRequestDto): ApiCallResult<OperationDto> {
        return postOperation("$baseUrl/operations/pair-speaker", token, body)
    }

    suspend fun assignSpeaker(baseUrl: String, token: String, body: OperationRequestDto): ApiCallResult<OperationDto> {
        return postOperation("$baseUrl/operations/assign-speaker", token, body)
    }

    suspend fun operation(baseUrl: String, token: String, operationId: String): ApiCallResult<OperationDto> {
        val response = client.get("$baseUrl/operations/$operationId") {
            bearerAuth(token)
        }
        if (!response.status.isSuccess()) return response.status.toApiError()
        val envelope = response.body<ApiEnvelope<OperationDto>>()
        return envelope.operation?.let { operation -> ApiCallResult.Success(operation) }
            ?: ApiCallResult.Failure("api_unavailable", "Missing operation payload.")
    }

    private suspend fun postOperation(
        url: String,
        token: String,
        body: Any
    ): ApiCallResult<OperationDto> {
        val response = client.post(url) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            // 409 distinguishes `boot_changed` from `stale_observation` in the body.
            // The HTTP-status fallback collapses both to `stale_observation`, which makes
            // a real Pi reboot look like a status-cache miss. Prefer the body code.
            val envelope = runCatching { response.body<ApiEnvelope<OperationDto>>() }.getOrNull()
            val error = envelope?.error
            return if (error != null) {
                ApiCallResult.Failure(error.code, error.message)
            } else {
                response.status.toApiError()
            }
        }
        val envelope = response.body<ApiEnvelope<OperationDto>>()
        return envelope.operation?.let { operation -> ApiCallResult.Success(operation) }
            ?: ApiCallResult.Failure("api_unavailable", "Missing operation payload.")
    }
}

sealed interface ApiCallResult<out T> {
    data class Success<T>(val value: T) : ApiCallResult<T>
    data class Failure(val code: String, val message: String) : ApiCallResult<Nothing>
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

private fun HttpStatusCode.toApiError(): ApiCallResult.Failure = when (this) {
    HttpStatusCode.Unauthorized -> ApiCallResult.Failure("unauthorized", "Authorization is required.")
    HttpStatusCode.Forbidden -> ApiCallResult.Failure("forbidden", "This token cannot use that control.")
    HttpStatusCode.NotFound -> ApiCallResult.Failure("not_found", "The requested endpoint was not found.")
    HttpStatusCode.Conflict -> ApiCallResult.Failure("stale_observation", "Refresh status before retrying.")
    HttpStatusCode.Gone -> ApiCallResult.Failure("operation_expired", "The operation is no longer available.")
    HttpStatusCode.ServiceUnavailable -> ApiCallResult.Failure("api_unavailable", "The Pi API is unavailable.")
    else -> ApiCallResult.Failure("api_unavailable", "HTTP ${value}: ${description}")
}
