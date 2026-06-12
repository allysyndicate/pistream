package com.pistream.companion.data

private const val DEFAULT_SCHEME = "http"
private const val PI_PORT = 8765
private const val API_PATH = "/api/v1"

fun normalizePiHost(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "audiopi.local"
    val withoutScheme = trimmed
        .removePrefix("http://")
        .removePrefix("https://")
    val hostPort = withoutScheme.substringBefore("/")
    return hostPort.substringBefore(":").ifBlank { "audiopi.local" }
}

fun piBaseUrl(host: String): String {
    val normalizedHost = normalizePiHost(host)
    return "$DEFAULT_SCHEME://$normalizedHost:$PI_PORT$API_PATH"
}
